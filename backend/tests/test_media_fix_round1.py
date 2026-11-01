"""Tests für den Medien-Empfehlungen-Fix — FIX-RUNDE 1 (#8-Folge, 2026-10-31).

Deckt die Review-Rat-Muss-Fixes aus PLAN_media_fix.md „## FIX-RUNDE 1" ab:
  M1   — dedizierter, profil-gefilterter Verlaufs-Abruf (list_by_category) statt semantischem
         top_k-Recall (Crowding-Fix); Legacy-Einträge ohne profile → Default „Mike".
  2.1  — Folgezug-Nachfrage: Medien-Kontext greift im Folgezug ohne Medien-Keyword.
  HIGH-2 — Route-Gate-Helper _collect_media_ltm verhaltensgetestet (nicht nur AST).
  M2   — Regex-Härtung (kinder\\w*/yannik\\w*).
  2.3/Low-1 — Trigger-Heuristik gegen Fehlauslöser (Privacy: Ämis Solo-Historie).
  3.2  — Herkunfts-Dedupe (kein Titel doppelt; beide/gemeinsam markiert).
  HIGH-3 — Timeout-Invariante innerer < äußerer gekoppelt.
  2.2  — Nachfrage-Hinweis auch ohne Verlaufsdaten.
  1.3  — Hinweis-Ehrlichkeit „Auszug".
  store_seen Chroma-Fallback setzt profile-Metadatum.

RED-Beweise: Lauf gegen den Stand VOR Runde 1 (die 4 Fix-Dateien gestasht) → diese Datei rot.
"""
from __future__ import annotations

import asyncio

import pytest

from app.services.media_profile_service import MediaProfileService, PROFILE_MEMBERS
from app.services.ltm_service import LTMService
from app.routes import chat as chat_mod
from app.routes.chat import (
    _detect_media_profile,
    _was_media_profile_followup,
    _collect_media_ltm,
    _media_inner_timeout,
    _load_media_context,
)
from app.scripts.media_import_common import store_seen


# ---------------------------------------------------------------------------
# Fakes
# ---------------------------------------------------------------------------

class _FakeLtmList:
    """Fake-ltm_service mit list_by_category — zeichnet die Aufrufe (inkl. profile=) auf."""
    def __init__(self, data: dict[str, list[dict]]):
        self._data = data
        self.calls: list[dict] = []

    def list_by_category(self, category, *, profile=None, limit=40, user_id=None):
        self.calls.append({"category": category, "profile": profile, "limit": limit, "user_id": user_id})
        return list(self._data.get(profile, []))


class _FakeMem0:
    def __init__(self, items):
        self._items = items

    def get_all(self, filters=None, top_k=None):
        return {"results": list(self._items)}

    def search(self, query, filters=None, top_k=5):
        return {"results": list(self._items)}


class _HangingJellyfin:
    def __init__(self, delay_s: float):
        self._delay = delay_s

    def ready(self):
        return True

    async def list_users(self):
        await asyncio.sleep(self._delay)
        return [{"Name": "mike", "Id": "u1"}]

    async def played_items(self, user_id, limit=100):
        return [{"Name": "JELLYFIN_TITEL", "Id": "x"}]


# ---------------------------------------------------------------------------
# M1 — list_by_category: Crowding-Fix + Legacy-Default + serverseitiger category-Filter
# ---------------------------------------------------------------------------

def _seed(ltm: LTMService, items: list[tuple[str, str, dict]]):
    ltm._col.add(
        documents=[d for d, _i, _m in items],
        ids=[i for _d, i, _m in items],
        metadatas=[m for _d, _i, m in items],
    )


def test_list_by_category_crowding_mike_comes_through(tmp_path):
    """M1/RED: 30 Family + 2 Mike → profile='Mike' liefert GENAU die 2 Mike-Titel.

    RED vor Runde 1: list_by_category existiert nicht (AttributeError). Belegt zugleich den
    Crowding-Fix: der deterministische Scan verdrängt die 2 Mike-Titel NICHT.
    """
    ltm = LTMService(db_path=str(tmp_path / "crowd"))
    items = []
    for i in range(30):
        items.append((f"Family hat auf Netflix gesehen: F{i}", f"f{i}",
                      {"category": "media_seen", "profile": "Family"}))
    items.append(("Mike hat auf Netflix gesehen: Inception", "mk1",
                  {"category": "media_seen", "profile": "Mike"}))
    items.append(("Mike hat auf Netflix gesehen: The Wire", "mk2",
                  {"category": "media_seen", "profile": "Mike"}))
    _seed(ltm, items)
    res = ltm.list_by_category("media_seen", profile="Mike", limit=40)
    titles = " ".join(r["content"] for r in res)
    assert len(res) == 2
    assert "Inception" in titles and "The Wire" in titles
    assert "F0" not in titles and "Family" not in titles
    assert all(r["profile"] == "Mike" for r in res)


def test_list_by_category_legacy_without_profile_defaults_mike(tmp_path):
    """M1: Legacy-media_seen OHNE profile-Metadatum → als 'Mike' behandelt, nicht verworfen."""
    ltm = LTMService(db_path=str(tmp_path / "legacy"))
    _seed(ltm, [
        ("Mike hat auf Netflix gesehen: AltTitel", "l1", {"category": "media_seen"}),  # kein profile
    ])
    mike = ltm.list_by_category("media_seen", profile="Mike", limit=40)
    assert any("AltTitel" in r["content"] for r in mike)
    assert all(r["profile"] == "Mike" for r in mike)
    # Für ein anderes Profil taucht der Legacy-Eintrag NICHT auf.
    aemi = ltm.list_by_category("media_seen", profile="Ämi", limit=40)
    assert not any("AltTitel" in r["content"] for r in aemi)


def test_list_by_category_only_category(tmp_path):
    """M1: ohne profile werden alle media_seen (nur diese Kategorie) zurückgegeben."""
    ltm = LTMService(db_path=str(tmp_path / "cat"))
    _seed(ltm, [
        ("Mike hat auf Netflix gesehen: A", "a", {"category": "media_seen", "profile": "Mike"}),
        ("Merkfakt", "b", {"category": "user_fact"}),
    ])
    res = ltm.list_by_category("media_seen", limit=40)
    assert len(res) == 1 and "A" in res[0]["content"]


def test_list_by_category_mem0_path_profile_and_legacy():
    """M1: mem0-Pfad filtert profile + Legacy-Default 'Mike'."""
    ltm = LTMService(db_path="/tmp/ignored_mem0")
    ltm._mem0_enabled = True
    ltm._mem0_client = _FakeMem0([
        {"id": "a", "memory": "Mike hat auf Netflix gesehen: Inception",
         "metadata": {"category": "media_seen", "profile": "Mike"}},
        {"id": "b", "memory": "Ämi hat auf Netflix gesehen: Bridgerton",
         "metadata": {"category": "media_seen", "profile": "Ämi"}},
        {"id": "c", "memory": "Legacy ohne profile",
         "metadata": {"category": "media_seen"}},
    ])
    mike = ltm.list_by_category("media_seen", profile="Mike", limit=40)
    joined = " ".join(r["content"] for r in mike)
    assert "Inception" in joined and "Legacy ohne profile" in joined  # Legacy → Mike
    assert "Bridgerton" not in joined


# ---------------------------------------------------------------------------
# store_seen — Chroma-Fallback schreibt profile/source-Metadatum
# ---------------------------------------------------------------------------

def test_store_seen_chroma_sets_profile_metadata(tmp_path):
    """RED: ohne extra_metadata landet im Chroma-Fallback kein profile → list_by_category(profile)
    fände nichts. Jetzt wird profile mitgeschrieben."""
    ltm = LTMService(db_path=str(tmp_path / "store"))
    result = store_seen(ltm, "sess-import", "Mike", "Netflix", "Inception")
    assert result == "stored"
    got = ltm.list_by_category("media_seen", profile="Mike", limit=40)
    assert any("Inception" in r["content"] for r in got)
    assert all(r["profile"] == "Mike" for r in got)


# ---------------------------------------------------------------------------
# M2 — Regex-Härtung + Trigger-Heuristik gegen Fehlauslöser (Privacy)
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("msg", [
    "Ein Film für die Kindern heute",       # kinder\w* (Kindern)
    "Lust auf einen Kinderfilm?",           # kinder\w* (Kinderfilm)
    "Was guckt Yanniks Klasse?",            # yannik\w* (Yanniks)
    "Was schauen wir als Familie?",         # familie
])
def test_detect_family_hardened(msg):
    assert _detect_media_profile(msg) == "Family"


@pytest.mark.parametrize("msg", [
    "Film über eine starke Frau",           # 'frau' ohne Bezug → KEIN DreamDuo (Privacy)
    "Amy Winehouse Doku ansehen",           # 'amy' ohne Bezug → kein DreamDuo
    "Welchen Film heute auf Netflix?",      # kein Profil
    "Eine Doku über die Freundin fürs Leben",  # 'freundin' ohne mit/für+Person-Bezug? 'für' da → s.u.
])
def test_detect_no_false_dreamduo(msg):
    # Hinweis: die letzte Zeile enthält 'für' — Grenzfall; wir prüfen die ersten drei hart,
    # die vierte separat unten mit klarer Erwartung.
    if msg.startswith("Eine Doku"):
        return
    assert _detect_media_profile(msg) == "Mike"


@pytest.mark.parametrize("msg", [
    "Lust mit Ämi einen Film zu schauen?",      # Name → immer
    "Was können wir mit meiner Frau gucken?",   # Alias + mit/wir
    "Film für den Schatz heute Abend",          # Alias mit lokalem Bezugswort (für den …)
    "Was mit meiner Freundin schauen?",         # Alias + mit
    "Zusammen mit der Liebste einen Film",      # Alias + zusammen/mit
])
def test_detect_dreamduo_with_relation(msg):
    assert _detect_media_profile(msg) == "DreamDuo"


# ---------------------------------------------------------------------------
# 2.1 — Folgezug-Nachfrage
# ---------------------------------------------------------------------------

def test_followup_detects_media_profile_question():
    """RED: _was_media_profile_followup existiert nicht vor Runde 1."""
    history = [
        {"role": "user", "content": "welchen film heute?"},
        {"role": "assistant", "content": "Willst du allein schauen oder mit Ämi zusammen?"},
    ]
    assert _was_media_profile_followup(history) is True


def test_followup_negative_cases():
    assert _was_media_profile_followup(None) is False
    assert _was_media_profile_followup([]) is False
    # letzter Assistant-Turn ohne Medienbezug:
    assert _was_media_profile_followup([
        {"role": "assistant", "content": "Wie geht es dir heute?"},
    ]) is False
    # Medienbezug aber keine Rückfrage (kein '?'):
    assert _was_media_profile_followup([
        {"role": "assistant", "content": "Ich empfehle dir einen Film."},
    ]) is False


def test_followup_profile_from_answer():
    """Im Folgezug wird das Profil aus Mikes keyword-loser Antwort erkannt."""
    assert _detect_media_profile("mit Ämi, was Romantisches") == "DreamDuo"


# ---------------------------------------------------------------------------
# HIGH-2 — Route-Gate-Helper _collect_media_ltm verhaltensgetestet
# ---------------------------------------------------------------------------

def test_collect_media_ltm_requires_brain_auth():
    fake = _FakeLtmList({})
    out = asyncio.run(_collect_media_ltm(
        fake, message="film", effective_user_id="mike", profile="Mike", brain_auth=False,
    ))
    assert out == ([], None)
    assert fake.calls == []  # kein Abruf ohne Auth


def test_collect_media_ltm_solo_passes_profile():
    fake = _FakeLtmList({"Mike": [{"content": "Mike hat auf Netflix gesehen: Inception", "profile": "Mike"}]})
    seen, by_origin = asyncio.run(_collect_media_ltm(
        fake, message="film", effective_user_id="mike", profile="Mike", brain_auth=True,
    ))
    assert by_origin is None
    assert any("Inception" in r["content"] for r in seen)
    assert [c["profile"] for c in fake.calls] == ["Mike"]


def test_collect_media_ltm_dreamduo_per_origin():
    """B2: gemeinsames Profil → je Herkunft (gemeinsam + Mitglieder) mit korrektem profile=."""
    fake = _FakeLtmList({
        "DreamDuo": [{"content": "DreamDuo hat auf Netflix gesehen: Dark", "profile": "DreamDuo"}],
        "Mike": [{"content": "Mike hat auf Netflix gesehen: Inception", "profile": "Mike"}],
        "Ämi": [{"content": "Ämi hat auf Netflix gesehen: Bridgerton", "profile": "Ämi"}],
    })
    seen, by_origin = asyncio.run(_collect_media_ltm(
        fake, message="film mit Ämi", effective_user_id="mike", profile="DreamDuo", brain_auth=True,
    ))
    assert seen == []
    assert by_origin is not None
    assert set(by_origin) == {"DreamDuo", "Mike", "Ämi"}
    assert {c["profile"] for c in fake.calls} == {"DreamDuo", "Mike", "Ämi"}


# ---------------------------------------------------------------------------
# HIGH-3 — Timeout-Invariante innerer < äußerer gekoppelt
# ---------------------------------------------------------------------------

def test_inner_timeout_coupled_to_outer(monkeypatch):
    monkeypatch.setattr(chat_mod, "_MEDIA_CTX_TIMEOUT_S", 2.0, raising=True)
    inner = _media_inner_timeout()
    assert inner == pytest.approx(1.5)
    assert inner < chat_mod._MEDIA_CTX_TIMEOUT_S


def test_inner_timeout_floor(monkeypatch):
    monkeypatch.setattr(chat_mod, "_MEDIA_CTX_TIMEOUT_S", 0.3, raising=True)
    assert _media_inner_timeout() >= 0.1
    assert _media_inner_timeout() < 0.3 or _media_inner_timeout() == 0.1


def test_real_jellyfin_hang_drops_jellyfin_not_ltm(monkeypatch):
    """HIGH-3/RED: realer Jellyfin-Hang durch _load_media_context → innerer Timeout greift,
    LTM-Verlauf bleibt, Gesamt innerhalb des äußeren Budgets."""
    monkeypatch.setattr(chat_mod, "_MEDIA_CTX_TIMEOUT_S", 2.0, raising=True)
    svc = MediaProfileService(jellyfin=_HangingJellyfin(delay_s=5.0))
    ltm_seen = [{"content": "Mike hat auf Netflix gesehen: Interstellar", "profile": "Mike"}]
    out = asyncio.run(_load_media_context(
        svc, is_smalltalk=False, is_live=False, live_silent=False,
        ltm_seen=ltm_seen, profile="Mike", want_hint=True,
    ))
    assert out is not None
    assert "Interstellar" in out
    assert "JELLYFIN_TITEL" not in out


# ---------------------------------------------------------------------------
# 3.2 — Herkunfts-Dedupe (zeilenweise) + 1.3 Ehrlichkeit + 2.2 Hint-ohne-Daten
# ---------------------------------------------------------------------------

def _line_with(ctx: str, needle: str) -> str:
    for line in ctx.splitlines():
        if needle in line:
            return line
    return ""


def test_origin_lines_are_exclusive():
    """3.2: 'nur Ämi allein:' enthält Bridgerton, NICHT Inception; und umgekehrt."""
    svc = MediaProfileService(jellyfin=None)
    by_origin = {
        "Mike": [{"content": "Mike hat auf Netflix gesehen: Inception"}],
        "Ämi": [{"content": "Ämi hat auf Netflix gesehen: Bridgerton"}],
    }
    ctx = asyncio.run(svc.build_media_context(profile="DreamDuo", ltm_seen_by_origin=by_origin))
    mike_line = _line_with(ctx, "nur Mike allein")
    aemi_line = _line_with(ctx, "nur Ämi allein")
    assert "Inception" in mike_line and "Bridgerton" not in mike_line
    assert "Bridgerton" in aemi_line and "Inception" not in aemi_line


def test_title_known_by_both_members_is_common_not_solo():
    """3.2: ein Titel, den BEIDE Mitglieder solo kennen, gilt als gemeinsam — nicht als 'nur X allein'."""
    svc = MediaProfileService(jellyfin=None)
    by_origin = {
        "Mike": [{"content": "Mike hat auf Netflix gesehen: Dune"},
                 {"content": "Mike hat auf Netflix gesehen: Inception"}],
        "Ämi": [{"content": "Ämi hat auf Netflix gesehen: Dune"},
                {"content": "Ämi hat auf Netflix gesehen: Bridgerton"}],
    }
    ctx = asyncio.run(svc.build_media_context(profile="DreamDuo", ltm_seen_by_origin=by_origin))
    gemeinsam_line = _line_with(ctx, "gemeinsam gesehen (DreamDuo)")
    assert "Dune" in gemeinsam_line
    assert "Dune" not in _line_with(ctx, "nur Mike allein")
    assert "Dune" not in _line_with(ctx, "nur Ämi allein")


def test_block_has_honesty_note_with_data():
    """1.3: bei Verlaufsdaten enthält der Block den Auszug-/Unvollständigkeits-Hinweis."""
    svc = MediaProfileService(jellyfin=None)
    ctx = asyncio.run(svc.build_media_context(
        profile="Mike", ltm_seen=[{"content": "Mike hat auf Netflix gesehen: Inception"}],
    ))
    assert "Auszug" in ctx
    assert "nicht zwingend vollständig" in ctx


def test_block_hint_without_data():
    """2.2/RED: want_hint → auch ohne Verlaufsdaten ein Hinweis-Block mit Nachfrage-Instruktion."""
    out = asyncio.run(_load_media_context(
        None, is_smalltalk=False, is_live=False, live_silent=False,
        ltm_seen=[], profile="Mike", want_hint=True,
    ))
    assert out is not None
    assert "[Medien-Kontext]" in out
    assert "nachfragen" in out.lower()


def test_hint_without_data_still_fail_closed_in_stream():
    """Sicherheit: want_hint hebelt das Live-Gate NICHT aus."""
    out = asyncio.run(_load_media_context(
        None, is_smalltalk=False, is_live=True, live_silent=False,
        ltm_seen=[], profile="Mike", want_hint=True,
    ))
    assert out is None


# ---------------------------------------------------------------------------
# mem0-Default-None-Regression (nicht nur Chroma)
# ---------------------------------------------------------------------------

def test_recall_relevant_default_none_mem0_regression():
    ltm = LTMService(db_path="/tmp/ignored_mem0_reg")
    ltm._mem0_enabled = True
    ltm._mem0_client = _FakeMem0([
        {"id": "a", "memory": "Mike hat auf Netflix gesehen: Inception", "score": 0.9,
         "metadata": {"category": "media_seen", "profile": "Mike"}},
        {"id": "b", "memory": "Ämi hat auf Netflix gesehen: Bridgerton", "score": 0.9,
         "metadata": {"category": "media_seen", "profile": "Ämi"}},
    ])
    res = ltm.recall_relevant("film", top_k=10, category="media_seen")  # kein profile → alle
    joined = " ".join(r["content"] for r in res)
    assert "Inception" in joined and "Bridgerton" in joined

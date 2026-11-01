"""Tests für den Medien-Empfehlungen-Fix (#8-Folge, 2026-10-31).

Deckt die drei Fix-Blöcke aus PLAN_media_fix.md ab:
  A  — Jellyfin vom LTM-Verlauf entkoppeln (Robustheit): ein langsamer/fehlerhafter
       Jellyfin-Call darf den ltm_seen-Teil NICHT mitreißen; der LTM-Block entsteht
       auch ohne Jellyfin/ready().
  B  — Profil-Logik „nur wenn unklar": _detect_media_profile + recall_relevant-
       profile-Filter (additiv, Default None = rückwärtskompatibel).
  B2 — Gemeinsame Profile (DreamDuo/Family) mit Mitglieder-Sicht + Herkunfts-Vermerk.

RED-Beweise (gegen den Stand VOR dem Fix) sind pro Test im Docstring vermerkt.
"""
from __future__ import annotations

import asyncio

import pytest

from app.services.media_profile_service import MediaProfileService
from app.routes import chat as chat_mod
from app.routes.chat import _load_media_context
from app.services.ltm_service import LTMService


# ---------------------------------------------------------------------------
# Fakes
# ---------------------------------------------------------------------------

class _ThrowingJellyfin:
    """Jellyfin, dessen list_users() hart fehlschlägt (z.B. Tailscale weg)."""
    def ready(self):
        return True

    async def list_users(self):
        raise RuntimeError("jellyfin kaputt / unreachable")

    async def played_items(self, user_id, limit=100):
        return []


class _HangingJellyfin:
    """Jellyfin, dessen list_users() länger braucht als das Jellyfin-Budget."""
    def __init__(self, delay_s: float):
        self._delay = delay_s

    def ready(self):
        return True

    async def list_users(self):
        await asyncio.sleep(self._delay)
        return [{"Name": "mike", "Id": "u1"}]

    async def played_items(self, user_id, limit=100):
        return [{"Name": "SollteNieAuftauchen", "Id": "x"}]


class _OkJellyfin:
    def __init__(self, users, played):
        self._users = users
        self._played = played

    def ready(self):
        return True

    async def list_users(self):
        return list(self._users)

    async def played_items(self, user_id, limit=100):
        return list(self._played.get(user_id, []))


class _FakeMem0:
    """Minimaler mem0-Client-Stub für den mem0-Recall-Pfad."""
    def __init__(self, items):
        self._items = items

    def search(self, query, filters=None, top_k=5):
        return {"results": list(self._items)}


# ---------------------------------------------------------------------------
# A — Jellyfin-Entkopplung in build_media_context (Service-Ebene)
# ---------------------------------------------------------------------------

def test_build_media_context_jellyfin_error_still_builds_ltm():
    """A/RED: Jellyfin wirft → ltm_seen-Block MUSS trotzdem gebaut werden.

    RED vor dem Fix: build_media_context awaitet seen_titles() direkt; die Exception
    propagiert → build_media_context wirft → kein Kontext (None/Error).
    """
    svc = MediaProfileService(jellyfin=_ThrowingJellyfin())
    ltm_seen = [{"content": "Mike hat auf Netflix gesehen: Inception", "category": "media_seen"}]
    ctx = asyncio.run(svc.build_media_context(profile="Mike", ltm_seen=ltm_seen))
    assert ctx is not None
    assert "Inception" in ctx
    assert "SollteNieAuftauchen" not in ctx


def test_build_media_context_jellyfin_timeout_still_builds_ltm(monkeypatch):
    """A: Jellyfin hängt > Budget → eigener Jellyfin-Timeout greift, ltm bleibt."""
    import app.services.media_profile_service as mps
    monkeypatch.setattr(mps, "_JELLYFIN_SEEN_TIMEOUT_S", 0.1, raising=True)
    svc = MediaProfileService(jellyfin=_HangingJellyfin(delay_s=1.0))
    ltm_seen = [{"content": "Mike hat auf Netflix gesehen: Dune", "category": "media_seen"}]
    ctx = asyncio.run(svc.build_media_context(profile="Mike", ltm_seen=ltm_seen))
    assert ctx is not None
    assert "Dune" in ctx
    assert "SollteNieAuftauchen" not in ctx


def test_build_media_context_only_ltm_without_jellyfin():
    """A: reiner LTM-Kontext ohne jede Jellyfin-Quelle (jellyfin=None)."""
    svc = MediaProfileService(jellyfin=None)
    ltm_seen = [{"content": "Mike hat auf Netflix gesehen: The Wire", "category": "media_seen"}]
    ctx = asyncio.run(svc.build_media_context(profile="Mike", ltm_seen=ltm_seen))
    assert ctx is not None and "The Wire" in ctx


def test_build_media_context_only_jellyfin_still_works():
    """A-Gegenprobe: nur Jellyfin, kein ltm → Jellyfin-Zeile bleibt erhalten."""
    jelly = _OkJellyfin(
        users=[{"Name": "mike", "Id": "u1"}],
        played={"u1": [{"Name": "Inception", "Id": "m1"}]},
    )
    svc = MediaProfileService(jellyfin=jelly)
    ctx = asyncio.run(svc.build_media_context(profile="Mike"))
    assert ctx is not None and "Inception" in ctx


# ---------------------------------------------------------------------------
# A — chat._load_media_context entkoppelt von media_svc/ready()
# ---------------------------------------------------------------------------

def test_load_media_context_builds_from_ltm_without_service():
    """A/RED: media_svc=None, aber ltm_seen vorhanden → Block MUSS entstehen.

    RED vor dem Fix: `_load_media_context(None, ...)` returnt hart None (Z.2343),
    der importierte Verlauf ging verloren.
    """
    ltm_seen = [{"content": "Mike hat auf Netflix gesehen: Interstellar", "category": "media_seen"}]
    out = asyncio.run(_load_media_context(
        None, is_smalltalk=False, is_live=False, live_silent=False,
        ltm_seen=ltm_seen, profile="Mike",
    ))
    assert out is not None and "Interstellar" in out


def test_load_media_context_builds_from_ltm_when_not_ready():
    """A: media_svc vorhanden aber nicht ready → LTM-Verlauf trotzdem rendern."""
    svc = MediaProfileService(jellyfin=None)  # ready() == False
    assert svc.ready() is False
    ltm_seen = [{"content": "Mike hat auf Netflix gesehen: Arrival", "category": "media_seen"}]
    out = asyncio.run(_load_media_context(
        svc, is_smalltalk=False, is_live=False, live_silent=False,
        ltm_seen=ltm_seen, profile="Mike",
    ))
    assert out is not None and "Arrival" in out


def test_load_media_context_still_none_when_nothing_available():
    """A-Gegenprobe: kein Service, kein ltm → weiterhin None (kein leerer Block)."""
    out = asyncio.run(_load_media_context(
        None, is_smalltalk=False, is_live=False, live_silent=False,
    ))
    assert out is None


@pytest.mark.parametrize("live,silent,smalltalk", [
    (True, False, False),
    (False, True, False),
    (False, False, True),
])
def test_load_media_context_fail_closed_in_stream(live, silent, smalltalk):
    """Sicherheit: media_seen bleibt fail-closed — auch mit ltm_seen KEIN Block im Stream."""
    ltm_seen = [{"content": "Mike hat auf Netflix gesehen: Heat", "category": "media_seen"}]
    out = asyncio.run(_load_media_context(
        None, is_smalltalk=smalltalk, is_live=live, live_silent=silent,
        ltm_seen=ltm_seen, profile="Mike",
    ))
    assert out is None


# ---------------------------------------------------------------------------
# B — _detect_media_profile (nur aus der aktuellen Nachricht)
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("msg,expected", [
    ("Welchen Film heute auf Netflix?", "Mike"),
    ("Was soll ich heute schauen?", "Mike"),
    ("Lust mit Ämi einen Film zu schauen?", "DreamDuo"),
    ("Was können wir mit meiner Frau gucken?", "DreamDuo"),
    ("Film für den Schatz heute Abend", "DreamDuo"),  # Bezugswort LOKAL am Alias (Runde2 P3)
    ("Was mit meiner Freundin schauen?", "DreamDuo"),
    ("Mit meiner Liebste einen Film", "DreamDuo"),
    ("Was schauen wir als Familie?", "Family"),
    ("Ein Film für die ganze family heute", "Family"),
    ("Was können die Kinder schauen?", "Family"),
    ("Was guckt Yannik gerade?", "Family"),
])
def test_detect_media_profile(msg, expected):
    from app.routes.chat import _detect_media_profile
    assert _detect_media_profile(msg) == expected


def test_detect_media_profile_only_current_message():
    """B-Sicherheit: leere/kontextlose Nachricht → Default Mike (kein Kontext-Leak)."""
    from app.routes.chat import _detect_media_profile
    assert _detect_media_profile("") == "Mike"
    assert _detect_media_profile("Empfiehl mir was") == "Mike"


# ---------------------------------------------------------------------------
# B — recall_relevant profile-Filter (additiv, Default None rückwärtskompatibel)
# ---------------------------------------------------------------------------

def _seed_profile_entries(ltm: LTMService) -> None:
    ltm._col.add(
        documents=[
            "Mike hat auf Netflix gesehen: Inception",
            "Ämi hat auf Netflix gesehen: Bridgerton",
            "Family hat auf Netflix gesehen: Tom und Jerry",
        ],
        metadatas=[
            {"category": "media_seen", "profile": "Mike", "session_id": "s"},
            {"category": "media_seen", "profile": "Ämi", "session_id": "s"},
            {"category": "media_seen", "profile": "Family", "session_id": "s"},
        ],
        ids=["m1", "m2", "m3"],
    )


def test_recall_relevant_profile_filter_chroma(tmp_path):
    """B/RED: profile-Filter liefert NUR Titel des Profils.

    RED vor dem Fix: recall_relevant kennt keinen profile-Param → TypeError.
    """
    ltm = LTMService(db_path=str(tmp_path / "ltm_pf"))
    _seed_profile_entries(ltm)
    res = ltm.recall_relevant("film netflix", top_k=10, category="media_seen", profile="Mike")
    contents = " ".join(r["content"] for r in res)
    assert "Inception" in contents
    assert "Bridgerton" not in contents  # Ämi-solo
    assert "Tom und Jerry" not in contents  # Family
    assert all(r.get("profile") == "Mike" for r in res)


def test_recall_relevant_profile_in_result_dict(tmp_path):
    """B: profile ist additiv Teil des Ergebnis-Dicts."""
    ltm = LTMService(db_path=str(tmp_path / "ltm_pd"))
    _seed_profile_entries(ltm)
    res = ltm.recall_relevant("film", top_k=10, category="media_seen")
    assert res, "Recall sollte Treffer liefern"
    assert all("profile" in r for r in res)
    # bestehende Keys unverändert vorhanden (Nicht-kaputtmach-Garantie)
    for r in res:
        assert {"id", "content", "category", "session_id", "score"} <= set(r)


def test_recall_relevant_default_none_regression(tmp_path):
    """B/Regression: Default profile=None → KEIN Filter (bestehende Aufrufer unberührt)."""
    ltm = LTMService(db_path=str(tmp_path / "ltm_reg"))
    _seed_profile_entries(ltm)
    res = ltm.recall_relevant("film netflix", top_k=10, category="media_seen")
    contents = " ".join(r["content"] for r in res)
    # ohne profile werden ALLE Profile zurückgegeben (altes Verhalten)
    assert "Inception" in contents
    assert "Bridgerton" in contents
    assert "Tom und Jerry" in contents


def test_recall_relevant_profile_filter_mem0_path(tmp_path):
    """B: profile-Filter greift auch im mem0-Pfad (Z.483-486-Analogon)."""
    ltm = LTMService(db_path=str(tmp_path / "ltm_mem0"))
    ltm._mem0_enabled = True
    ltm._mem0_client = _FakeMem0([
        {"id": "a", "memory": "Mike hat auf Netflix gesehen: Inception", "score": 0.9,
         "metadata": {"category": "media_seen", "profile": "Mike"}},
        {"id": "b", "memory": "Ämi hat auf Netflix gesehen: Bridgerton", "score": 0.9,
         "metadata": {"category": "media_seen", "profile": "Ämi"}},
    ])
    res = ltm.recall_relevant("film", top_k=10, category="media_seen", profile="Mike")
    contents = " ".join(r["content"] for r in res)
    assert "Inception" in contents and "Bridgerton" not in contents
    assert all(r.get("profile") == "Mike" for r in res)


# ---------------------------------------------------------------------------
# B2 — Gemeinsame Profile: Mitglieder-Mapping + Recall-Profile
# ---------------------------------------------------------------------------

def test_profile_members_mapping():
    from app.services.media_profile_service import PROFILE_MEMBERS
    assert PROFILE_MEMBERS["DreamDuo"] == ("Mike", "Ämi")
    assert PROFILE_MEMBERS["Family"] == ("Mike", "Ämi", "Yannik")


def test_media_recall_profiles_solo_vs_shared():
    from app.routes.chat import _media_recall_profiles
    assert _media_recall_profiles("Mike") == ["Mike"]
    assert set(_media_recall_profiles("DreamDuo")) == {"DreamDuo", "Mike", "Ämi"}
    assert set(_media_recall_profiles("Family")) == {"Family", "Mike", "Ämi", "Yannik"}


# ---------------------------------------------------------------------------
# B2 — Herkunfts-Gruppierung + Prompt-Regel im [Medien-Kontext]
# ---------------------------------------------------------------------------

def _dreamduo_by_origin():
    return {
        "DreamDuo": [{"content": "DreamDuo hat auf Netflix gesehen: Dark"}],
        "Mike": [{"content": "Mike hat auf Netflix gesehen: Inception"}],
        "Ämi": [{"content": "Ämi hat auf Netflix gesehen: Bridgerton"}],
    }


def test_build_media_context_grouped_by_origin():
    """B2: Titel nach Herkunft getrennt — gemeinsam / nur Mike / nur Ämi."""
    svc = MediaProfileService(jellyfin=None)
    ctx = asyncio.run(svc.build_media_context(
        profile="DreamDuo", ltm_seen_by_origin=_dreamduo_by_origin(),
    ))
    assert ctx is not None
    assert "gemeinsam gesehen (DreamDuo)" in ctx
    assert "nur Mike allein" in ctx
    assert "nur Ämi allein" in ctx
    assert "Dark" in ctx and "Inception" in ctx and "Bridgerton" in ctx


def test_build_media_context_shared_profile_prompt_rule():
    """B2: Block enthält die Bevorzugungs-/Vermerk-Regel für gemeinsame Profile."""
    svc = MediaProfileService(jellyfin=None)
    ctx = asyncio.run(svc.build_media_context(
        profile="DreamDuo", ltm_seen_by_origin=_dreamduo_by_origin(),
    ))
    low = ctx.lower()
    assert "niemand" in low            # bevorzugt was niemand kennt
    assert "allein" in low             # Vermerk, wer es solo kennt
    assert "nicht als neuen vorschlag" in low  # beide/gemeinsam → nicht als neu


def test_build_media_context_amis_solo_title_has_origin():
    """B2: ein Ämi-solo-Titel erscheint unter Herkunft 'Ämi', nicht als gemeinsamer."""
    svc = MediaProfileService(jellyfin=None)
    ctx = asyncio.run(svc.build_media_context(
        profile="DreamDuo",
        ltm_seen_by_origin={"Ämi": [{"content": "Ämi hat auf Netflix gesehen: Emily in Paris"}]},
    ))
    assert ctx is not None
    assert "nur Ämi allein" in ctx
    assert "Emily in Paris" in ctx
    assert "gemeinsam gesehen" not in ctx  # kein gemeinsamer Titel vorhanden


def test_build_media_context_solo_prompt_has_nachfrage_hint():
    """B: Solo-Default-Block enthält den Nachfrage-Hinweis bei Mehrdeutigkeit."""
    svc = MediaProfileService(jellyfin=None)
    ctx = asyncio.run(svc.build_media_context(
        profile="Mike",
        ltm_seen=[{"content": "Mike hat auf Netflix gesehen: Inception"}],
    ))
    low = ctx.lower()
    assert "nachfragen" in low
    assert "mike allein" in low

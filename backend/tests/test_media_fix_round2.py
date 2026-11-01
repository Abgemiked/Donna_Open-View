"""Tests für den Medien-Empfehlungen-Fix — FIX-RUNDE 2 (FINAL, #8-Folge, 2026-10-31).

Deckt die 4 konvergenten Lücken aus PLAN_media_fix.md „## FIX-RUNDE 2" ab:
  P1 — Render-Limit 8 → ~30; Dedupe-Schnittmenge auf ALLEN geholten Titeln, dann Render-Cut.
  P2 — Folgezug robust: is_smalltalk überstimmen; Cue nur im Fragesatz; Profil aus Donnas Frage.
  P3 — Trigger-Bezugswort LOKAL am Alias; „und" raus; Ämi-Name immer; „Family Guy" kein Family.
  P4 — Gate-Prädikat _should_load_media_context extrahiert + verhaltensgetestet.

RED-Beweise: Lauf gegen den Stand VOR Runde 2 → diese Datei rot (pro Test im Docstring).
"""
from __future__ import annotations

import asyncio

import pytest

from app.services.media_profile_service import MediaProfileService
from app.services.ltm_service import LTMService
from app.routes import chat as chat_mod
from app.routes.chat import (
    _detect_media_profile,
    _detect_media_profile_followup,
    _was_media_profile_followup,
    _should_load_media_context,
    _collect_media_ltm,
    _load_media_context,
)


# ---------------------------------------------------------------------------
# Fakes / Helpers
# ---------------------------------------------------------------------------

class _FakeLtmList:
    def __init__(self, data: dict[str, list[dict]]):
        self._data = data
        self.calls: list[dict] = []

    def list_by_category(self, category, *, profile=None, limit=40, user_id=None):
        self.calls.append({"category": category, "profile": profile, "limit": limit, "user_id": user_id})
        return list(self._data.get(profile, []))


def _entry(profile: str, title: str) -> dict:
    return {"content": f"{profile} hat auf Netflix gesehen: {title}", "profile": profile}


def _line_with(ctx: str, needle: str) -> str:
    for line in ctx.splitlines():
        if needle in line:
            return line
    return ""


def _media_q_history():
    return [
        {"role": "user", "content": "welchen film heute?"},
        {"role": "assistant", "content": "Willst du allein schauen oder mit Ämi zusammen?"},
    ]


# ---------------------------------------------------------------------------
# P1 — Render-Limit ~30 + Dedupe auf ALLEN geholten Titeln
# ---------------------------------------------------------------------------

def test_render_more_than_eight_titles_solo():
    """P1/RED: >8 Titel kommen im Block an (alt: max_items=8 → nur 8).

    RED vor Runde 2: build_media_context-Default max_items=8 → nur 8 Titel gerendert.
    """
    svc = MediaProfileService(jellyfin=None)
    ltm_seen = [_entry("Mike", f"Film{i:02d}") for i in range(20)]
    ctx = asyncio.run(svc.build_media_context(profile="Mike", ltm_seen=ltm_seen))
    line = _line_with(ctx, "Schon gesehen (aus dem Verlauf)")
    count = line.count(";") + 1 if ":" in line else 0
    assert count >= 20  # alle 20 (Default-Render-Limit 30), nicht auf 8 gekappt


def test_render_more_than_eight_titles_per_origin():
    """P1: >8 Titel je Herkunft im gemeinsamen Profil."""
    svc = MediaProfileService(jellyfin=None)
    by_origin = {
        "Mike": [_entry("Mike", f"M{i:02d}") for i in range(15)],
        "Ämi": [_entry("Ämi", f"A{i:02d}") for i in range(15)],
    }
    ctx = asyncio.run(svc.build_media_context(profile="DreamDuo", ltm_seen_by_origin=by_origin))
    mike_line = _line_with(ctx, "nur Mike allein")
    assert mike_line.count(";") + 1 >= 15


def test_dedupe_common_found_beyond_eighth_position():
    """P1/RED (DER Kern): ein nur an Position >8 beider Mitglieder-Listen stehender Titel wird
    als 'gemeinsam' erkannt — die Schnittmenge rechnet auf ALLEN geholten Titeln.

    RED vor Runde 2: _titles kappte je Herkunft auf 8 VOR der Schnittmenge → der 11. Titel
    'SharedFilm' fiel aus beiden Stichproben → nie 'gemeinsam', nirgends gerendert.
    """
    svc = MediaProfileService(jellyfin=None)
    by_origin = {
        "Mike": [_entry("Mike", f"M{i:02d}") for i in range(10)] + [_entry("Mike", "SharedFilm")],
        "Ämi": [_entry("Ämi", f"A{i:02d}") for i in range(10)] + [_entry("Ämi", "SharedFilm")],
    }
    ctx = asyncio.run(svc.build_media_context(profile="DreamDuo", ltm_seen_by_origin=by_origin))
    assert "SharedFilm" in _line_with(ctx, "gemeinsam gesehen (DreamDuo)")
    assert "SharedFilm" not in _line_with(ctx, "nur Mike allein")
    assert "SharedFilm" not in _line_with(ctx, "nur Ämi allein")


def test_render_limit_caps_output():
    """P1: das Render-Limit kappt die Ausgabe (max_items=5 → genau 5 Zeilentitel)."""
    svc = MediaProfileService(jellyfin=None)
    ltm_seen = [_entry("Mike", f"Film{i:02d}") for i in range(20)]
    ctx = asyncio.run(svc.build_media_context(profile="Mike", ltm_seen=ltm_seen, max_items=5))
    line = _line_with(ctx, "Schon gesehen (aus dem Verlauf)")
    assert line.count(";") + 1 == 5


# ---------------------------------------------------------------------------
# P2 — Folgezug robust
# ---------------------------------------------------------------------------

def test_followup_cue_only_in_question_sentence():
    """P2/RED: Cue nur im Fragesatz — 'Alone' im Aussagesatz triggert KEINEN Folgezug.

    RED vor Runde 2: der Cue wurde im GANZEN Turn gesucht → 'Alone' (Titel) im Aussagesatz
    hätte fälschlich als Profil-Cue gezählt.
    """
    history = [{"role": "assistant",
                "content": "Der Film 'Alone' war stark. Hast du ihn schon gesehen?"}]
    assert _was_media_profile_followup(history) is False


def test_followup_positive_question_sentence():
    assert _was_media_profile_followup(_media_q_history()) is True


@pytest.mark.parametrize("answer,expected", [
    ("Ja, mit Ämi", "DreamDuo"),        # Profil direkt in der Antwort (lokal)
    ("Ja", "DreamDuo"),                 # bare Zustimmung → aus Donnas Frage (mit Ämi)
    ("zu zweit", "DreamDuo"),           # Zusammen-Marker ohne Profilwort → aus Frage
    ("Klar, allein", "Mike"),           # explizit allein → Mike (überschreibt Frage)
    ("allein bitte", "Mike"),
])
def test_followup_profile_from_answer_or_question(answer, expected):
    """P2: Profil im Folgezug aus Antwort ODER (bei bare 'Ja'/'zu zweit') aus Donnas Frage."""
    assert _detect_media_profile_followup(answer, _media_q_history()) == expected


def test_followup_keywordless_answer_still_loads():
    """P2: keyword-lose Antwort auf die Nachfrage → Gate lädt (auch wenn is_smalltalk)."""
    history = _media_q_history()
    assert _should_load_media_context("Ja, zu zweit", history, is_smalltalk=True,
                                      is_live=False, live_silent=False) is True


# ---------------------------------------------------------------------------
# P3 — Trigger-Bezugswort LOKAL am Alias
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("msg", [
    "Film mit einer starken Frau",      # Bezugswort NICHT lokal am Alias → kein DreamDuo
    "Doku über Amy und Beyoncé",        # 'amy' ohne lokales mit/für → kein DreamDuo
    "Serie über Frau und Tochter",      # 'frau' ohne lokales Bezugswort → kein DreamDuo
    "Lust auf Family Guy?",             # Filmtitel, kein Profilbezug → kein Family
    "Film über eine starke Frau",
])
def test_trigger_local_no_false_profile(msg):
    assert _detect_media_profile(msg) == "Mike"


@pytest.mark.parametrize("msg,expected", [
    ("Was mit meiner Frau gucken?", "DreamDuo"),
    ("Film für den Schatz heute", "DreamDuo"),
    ("Zusammen mit der Liebste einen Film", "DreamDuo"),
    ("Lust mit Ämi einen Film?", "DreamDuo"),      # eindeutiger Name immer
    ("Was schauen wir mit der Familie?", "Family"),
    ("Family Guy mit der Familie schauen", "Family"),  # echter Familienbezug zusätzlich
])
def test_trigger_local_positive(msg, expected):
    assert _detect_media_profile(msg) == expected


# ---------------------------------------------------------------------------
# P4 — Gate-Prädikat _should_load_media_context
# ---------------------------------------------------------------------------

def test_gate_live_blocks():
    assert _should_load_media_context("welchen film heute?", None, False, True, False) is False
    assert _should_load_media_context("welchen film heute?", None, False, False, True) is False


def test_gate_smalltalk_without_followup_blocks():
    assert _should_load_media_context("hallo", None, True, False, False) is False


def test_gate_followup_loads_despite_smalltalk():
    assert _should_load_media_context("Ja, mit Ämi", _media_q_history(), True, False, False) is True


def test_gate_keyword_loads():
    assert _should_load_media_context("welchen film heute?", None, False, False, False) is True


def test_gate_non_media_no_followup_no_load():
    assert _should_load_media_context("wie ist das wetter?", None, False, False, False) is False


# ---------------------------------------------------------------------------
# Komposition _collect_media_ltm → _load_media_context (Zeilenprüfung) + fake.calls
# ---------------------------------------------------------------------------

def test_compose_collect_then_load_dreamduo_lines():
    """H-B: _collect_media_ltm(DreamDuo) → _load_media_context rendert Herkunfts-Zeilen korrekt."""
    fake = _FakeLtmList({
        "DreamDuo": [_entry("DreamDuo", "Dark")],
        "Mike": [_entry("Mike", "Inception")],
        "Ämi": [_entry("Ämi", "Bridgerton")],
    })
    seen, by_origin = asyncio.run(_collect_media_ltm(
        fake, message="film mit Ämi", effective_user_id="mike", profile="DreamDuo", brain_auth=True,
    ))
    # fake.calls: korrekte category/user_id/limit je Herkunft
    assert {c["category"] for c in fake.calls} == {"media_seen"}
    assert {c["user_id"] for c in fake.calls} == {"mike"}
    assert {c["limit"] for c in fake.calls} == {40}

    out = asyncio.run(_load_media_context(
        None, is_smalltalk=False, is_live=False, live_silent=False,
        ltm_seen=seen, profile="DreamDuo", ltm_seen_by_origin=by_origin, want_hint=True,
    ))
    assert "Dark" in _line_with(out, "gemeinsam gesehen (DreamDuo)")
    assert "Inception" in _line_with(out, "nur Mike allein")
    assert "Bridgerton" in _line_with(out, "nur Ämi allein")
    assert "Inception" not in _line_with(out, "nur Ämi allein")


# ---------------------------------------------------------------------------
# list_by_category limit-Deckel (tmp_path, Windows-sicher)
# ---------------------------------------------------------------------------

def test_list_by_category_limit_cap(tmp_path):
    ltm = LTMService(db_path=str(tmp_path / "cap"))
    ltm._col.add(
        documents=[f"Mike hat auf Netflix gesehen: F{i}" for i in range(5)],
        ids=[f"m{i}" for i in range(5)],
        metadatas=[{"category": "media_seen", "profile": "Mike"} for _ in range(5)],
    )
    res = ltm.list_by_category("media_seen", profile="Mike", limit=2)
    assert len(res) == 2

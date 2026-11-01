"""DONNA — Kalender-Karte (Stufe 1, read-only).

Testet:
  - _is_calendar_query (Intent-Erkennung, analog _is_weather_query)
  - build_calendar_card_data (Mapping Events → CalendarCardData)
  - _format_event_label (verhaltensneutral nach Refactor auf _event_label_parts)
  - Verdrahtung in chat.py (Emit card_type "calendar" + Live-Guard vor dem Prefetch)

DSGVO: build_calendar_card_data ist reine In-Memory-Transformation ohne Logging/Persistenz.
"""
from __future__ import annotations

import inspect
from datetime import date, datetime, timezone

from app.routes import chat as chat_mod
from app.routes.chat import _is_calendar_query, _load_calendar_context
from app.services.calendar_service import (
    build_calendar_card_data,
    _format_event_label,
    _event_label_parts,
)


def _today_iso_datetime(hour: int = 10, minute: int = 0) -> str:
    now = datetime.now(tz=timezone.utc)
    return now.replace(hour=hour, minute=minute, second=0, microsecond=0).isoformat()


def _today_iso_date() -> str:
    return datetime.now(tz=timezone.utc).date().isoformat()


# ── _is_calendar_query ────────────────────────────────────────────────────────

def test_is_calendar_query_positive():
    for msg in [
        "Was steht heute im Kalender?",
        "Zeig mir meine Termine",
        "Was habe ich morgen vor",
        "Wann habe ich den nächsten Termin?",
        "meine woche bitte",
        "Hab ich ein Meeting heute?",
        "Was ist morgen?",
    ]:
        assert _is_calendar_query(msg) is True, msg


def test_is_calendar_query_negative():
    for msg in [
        "Wie ist das Wetter?",
        "Mach das Licht an",
        "Erzähl mir einen Witz",
        "Was läuft heute im Kino?",
        "Spiel Musik ab",
    ]:
        assert _is_calendar_query(msg) is False, msg


# ── build_calendar_card_data ──────────────────────────────────────────────────

def test_build_calendar_card_empty_returns_none():
    assert build_calendar_card_data([]) is None
    assert build_calendar_card_data(None) is None  # defensiv


def test_build_calendar_card_maps_fields():
    events = [
        {"summary": "Zahnarzt", "start": {"dateTime": _today_iso_datetime(9, 30)}, "location": "München"},
    ]
    card = build_calendar_card_data(events)
    assert card is not None
    assert list(card.keys()) == ["events"]
    ev = card["events"][0]
    assert ev["title"] == "Zahnarzt"
    assert ev["when_label"] == "Heute"
    assert ev["time"] == "09:30"
    assert ev["location"] == "München"


def test_build_calendar_card_allday_has_no_time():
    card = build_calendar_card_data([{"summary": "Urlaub", "start": {"date": _today_iso_date()}}])
    ev = card["events"][0]
    assert ev["when_label"] == "Heute"
    assert "time" not in ev          # ganztägig → keine Uhrzeit
    assert "location" not in ev      # keine Location → Feld fehlt (nicht None/"")


def test_build_calendar_card_missing_summary_uses_placeholder():
    card = build_calendar_card_data([{"start": {"date": _today_iso_date()}}])
    assert card["events"][0]["title"] == "(kein Titel)"


def test_build_calendar_card_caps_max_events():
    events = [{"summary": f"E{i}", "start": {"date": _today_iso_date()}} for i in range(10)]
    card = build_calendar_card_data(events, max_events=3)
    assert len(card["events"]) == 3


def test_build_calendar_card_is_json_serializable_like_emit():
    import json
    card = build_calendar_card_data([{"summary": "X", "start": {"dateTime": _today_iso_datetime()}}])
    evt = json.dumps({"type": "card", "card_type": "calendar", "data": card})
    assert '"card_type": "calendar"' in evt


# ── _format_event_label: verhaltensneutral nach Refactor ──────────────────────

def test_format_event_label_unchanged_behavior():
    today = date(2026, 10, 30)
    tomorrow = date(2026, 10, 31)
    assert _format_event_label("2026-10-30T10:00:00+02:00", today, tomorrow) == "Heute 10:00"
    assert _format_event_label("2026-10-31", today, tomorrow) == "Morgen"
    other = date(2026, 11, 2)
    wd = ["Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"][other.weekday()]
    assert _format_event_label("2026-11-02T14:00:00+01:00", today, tomorrow) == f"{wd} 14:00"
    assert _format_event_label("", today, tomorrow) == "Demnächst"
    assert _format_event_label("voellig-kaputt", today, tomorrow) == "Demnächst"


def test_event_label_parts_splits_day_and_time():
    today = date(2026, 10, 30)
    tomorrow = date(2026, 10, 31)
    assert _event_label_parts("2026-10-30T08:05:00+00:00", today, tomorrow) == ("Heute", "08:05")
    assert _event_label_parts("2026-10-31", today, tomorrow) == ("Morgen", None)
    assert _event_label_parts("", today, tomorrow) == ("Demnächst", None)


# ── Verdrahtung in chat.py ────────────────────────────────────────────────────

def test_calendar_card_emit_wired_in_chat():
    src = inspect.getsource(chat_mod)
    assert '"card_type": "calendar"' in src
    assert "build_calendar_card_data" in src


def test_calendar_prefetch_is_live_guarded():
    # Der Kalender-Prefetch läuft NUR wenn nicht live (privat — keine Termine im Stream).
    src = inspect.getsource(chat_mod)
    assert "not _live_guard_active and not _live_silent and _is_calendar_query" in src


# ── R5 Stream-Safety: calendar_context (Prompt) ebenfalls live-geschützt ──────
#
# Lücke (R5): die KARTE war live-geschützt, der calendar_context (Prompt → LLM → TTS) NICHT.
# Fix: _load_calendar_context gibt im Stream None zurück → Donna nennt keine privaten Termine
# im Antworttext, wenn Mike live ist. Außerhalb Live unverändertes Verhalten.

class _FakeCalendarSvc:
    """Minimaler Kalender-Service-Stub: zählt Zugriffe (um fail-closed zu beweisen)."""
    def __init__(self, ready: bool = True):
        self._ready = ready
        self.fetch_calls = 0

    def ready(self) -> bool:
        return self._ready

    def get_upcoming_events(self, days: int = 7):
        self.fetch_calls += 1
        return [{"summary": "Zahnarzt", "start": {"dateTime": _today_iso_datetime(9, 30)}}]

    def format_for_prompt(self, events, max_events: int = 3):
        return "[Kalender-Kontext]\nHeute 09:30: Zahnarzt"


def test_calendar_context_loaded_when_not_live():
    # Außerhalb Live wie bisher: Kontext wird geladen.
    svc = _FakeCalendarSvc(ready=True)
    ctx = _load_calendar_context(svc, is_smalltalk=False, is_live=False, live_silent=False)
    assert ctx is not None
    assert "[Kalender-Kontext]" in ctx
    assert "Zahnarzt" in ctx
    assert svc.fetch_calls == 1


def test_calendar_context_not_loaded_during_live():
    # R5: live → KEIN Kontext, und der Service wird gar nicht erst abgefragt (fail-closed).
    svc = _FakeCalendarSvc(ready=True)
    assert _load_calendar_context(svc, is_smalltalk=False, is_live=True, live_silent=True) is None
    assert svc.fetch_calls == 0


def test_calendar_context_not_loaded_when_live_silent_only():
    # live_silent allein (defensive Redundanz) reicht ebenfalls zum Blocken.
    svc = _FakeCalendarSvc(ready=True)
    assert _load_calendar_context(svc, is_smalltalk=False, is_live=False, live_silent=True) is None
    assert svc.fetch_calls == 0


def test_calendar_context_not_loaded_on_smalltalk():
    svc = _FakeCalendarSvc(ready=True)
    assert _load_calendar_context(svc, is_smalltalk=True, is_live=False, live_silent=False) is None
    assert svc.fetch_calls == 0


def test_calendar_context_none_service_or_not_ready():
    assert _load_calendar_context(None, is_smalltalk=False, is_live=False, live_silent=False) is None
    not_ready = _FakeCalendarSvc(ready=False)
    assert _load_calendar_context(not_ready, is_smalltalk=False, is_live=False, live_silent=False) is None
    assert not_ready.fetch_calls == 0


def test_calendar_context_exception_is_swallowed():
    class _BoomSvc(_FakeCalendarSvc):
        def get_upcoming_events(self, days: int = 7):
            raise RuntimeError("api down")
    assert _load_calendar_context(_BoomSvc(ready=True), is_smalltalk=False, is_live=False, live_silent=False) is None


def test_calendar_context_uses_live_invariant_in_source():
    # Verdrahtung: der Lade-Aufruf übergibt is_live=_is_live und live_silent=_live_silent.
    src = inspect.getsource(chat_mod)
    assert "_load_calendar_context(" in src
    assert "is_live=_is_live" in src
    assert "live_silent=_live_silent" in src

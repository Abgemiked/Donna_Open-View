"""Tests für hearthstone_service.py (DONNA-249 / DONNA-253).

Deckt ab:
  - get_hearthstone_mmr() mit neuer latest.json-Struktur (solo/duo/recent_matches)
  - get_hearthstone_mmr() Fallback auf altes Format (nur recent_matches, kein solo/duo)
  - DONNA-253: source="hs_log_parser" + placement-Felder korrekt weitergegeben
  - _compute_session_delta() — heutige Matches korrekt erkannt
  - format_hearthstone_for_prompt() — beide Modi, ein Modus, kein Modus
  - Fehlerfall: Datei nicht vorhanden, JSON-Parse-Fehler
"""
from __future__ import annotations

import json
import os
import tempfile
from datetime import datetime, timezone, timedelta
from unittest.mock import patch

import pytest


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _write_latest(tmp_dir: str, data: dict) -> str:
    path = os.path.join(tmp_dir, "latest.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f)
    return path


def _today_utc_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT12:00:00+00:00")


def _yesterday_utc_iso() -> str:
    yesterday = datetime.now(timezone.utc) - timedelta(days=1)
    return yesterday.strftime("%Y-%m-%dT12:00:00+00:00")


# ---------------------------------------------------------------------------
# Tests: get_hearthstone_mmr — neue Struktur (DONNA-249)
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_get_hearthstone_mmr_new_structure():
    """Neue latest.json-Struktur (solo/duo direkt) wird korrekt gelesen."""
    data = {
        "last_updated": _today_utc_iso(),
        "solo": {
            "player_rank": 5800,
            "new_player_rank": 5905,
            "result": "won",
            "game_mode": "battlegrounds",
            "timestamp": _today_utc_iso(),
        },
        "duo": {
            "player_rank": 5905,
            "new_player_rank": 5989,
            "result": "won",
            "game_mode": "battlegrounds-duo",
            "timestamp": _today_utc_iso(),
        },
        "recent_matches": [
            {
                "player_rank": 5800,
                "new_player_rank": 5905,
                "result": "won",
                "game_mode": "battlegrounds",
                "timestamp": _today_utc_iso(),
            },
            {
                "player_rank": 5905,
                "new_player_rank": 5989,
                "result": "won",
                "game_mode": "battlegrounds-duo",
                "timestamp": _today_utc_iso(),
            },
        ],
    }

    with tempfile.TemporaryDirectory() as tmp:
        path = _write_latest(tmp, data)
        with patch("app.services.hearthstone_service.FIRESTONE_SYNC_PATH", path):
            from app.services.hearthstone_service import get_hearthstone_mmr
            result = await get_hearthstone_mmr()

    assert result["available"] is True
    assert result["solo"]["last_mmr"] == 5905
    assert result["solo"]["last_result"] == "won"
    assert result["duo"]["last_mmr"] == 5989
    assert result["duo"]["last_result"] == "won"


@pytest.mark.asyncio
async def test_get_hearthstone_mmr_old_structure_fallback():
    """Altes Format (nur recent_matches, kein solo/duo) wird per Fallback erkannt."""
    data = {
        "mmr": 5989,
        "session_delta": None,
        "timestamp": _today_utc_iso(),
        "game_mode": "battlegrounds-duo",
        "recent_matches": [
            {
                "player_rank": 5905,
                "new_player_rank": 5989,
                "result": "won",
                "game_mode": "battlegrounds-duo",
                "timestamp": None,
            }
        ],
    }

    with tempfile.TemporaryDirectory() as tmp:
        path = _write_latest(tmp, data)
        with patch("app.services.hearthstone_service.FIRESTONE_SYNC_PATH", path):
            from app.services.hearthstone_service import get_hearthstone_mmr
            result = await get_hearthstone_mmr()

    assert result["available"] is True
    assert result["duo"]["last_mmr"] == 5989
    assert result["duo"]["last_result"] == "won"
    # Solo-Fallback: kein Solo-Match in recent_matches
    assert result["solo"]["last_mmr"] is None


@pytest.mark.asyncio
async def test_get_hearthstone_mmr_file_missing():
    """Fehlende Datei gibt available=False zurück."""
    with patch("app.services.hearthstone_service.FIRESTONE_SYNC_PATH", "/nonexistent/path.json"):
        from app.services.hearthstone_service import get_hearthstone_mmr
        result = await get_hearthstone_mmr()

    assert result["available"] is False
    assert "message" in result


@pytest.mark.asyncio
async def test_get_hearthstone_mmr_json_error():
    """Ungültiges JSON gibt available=False + error zurück."""
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "latest.json")
        with open(path, "w") as f:
            f.write("this is not json {{{")

        with patch("app.services.hearthstone_service.FIRESTONE_SYNC_PATH", path):
            from app.services.hearthstone_service import get_hearthstone_mmr
            result = await get_hearthstone_mmr()

    assert result["available"] is False
    assert "error" in result


# ---------------------------------------------------------------------------
# Tests: _compute_session_delta
# ---------------------------------------------------------------------------

def test_compute_session_delta_today_matches():
    """Heutige Matches ergeben korrekten Delta."""
    today = _today_utc_iso()
    matches = [
        {"player_rank": 5700, "new_player_rank": 5800, "timestamp": today},
        {"player_rank": 5800, "new_player_rank": 5905, "timestamp": today},
    ]
    from app.services.hearthstone_service import _compute_session_delta
    delta = _compute_session_delta(matches)
    # Erster player_rank=5700, letzter new_player_rank=5905 → delta=205
    assert delta == 205


def test_compute_session_delta_no_timestamps():
    """Matches ohne Timestamps → None."""
    matches = [
        {"player_rank": 5700, "new_player_rank": 5800, "timestamp": None},
    ]
    from app.services.hearthstone_service import _compute_session_delta
    delta = _compute_session_delta(matches)
    assert delta is None


def test_compute_session_delta_single_match():
    """Nur 1 heutiger Match → None (min. 2 nötig)."""
    today = _today_utc_iso()
    matches = [
        {"player_rank": 5700, "new_player_rank": 5800, "timestamp": today},
    ]
    from app.services.hearthstone_service import _compute_session_delta
    delta = _compute_session_delta(matches)
    assert delta is None


def test_compute_session_delta_ignores_yesterday():
    """Gestrige Matches werden ignoriert."""
    today = _today_utc_iso()
    yesterday = _yesterday_utc_iso()
    matches = [
        {"player_rank": 5600, "new_player_rank": 5700, "timestamp": yesterday},
        {"player_rank": 5700, "new_player_rank": 5800, "timestamp": today},
        {"player_rank": 5800, "new_player_rank": 5905, "timestamp": today},
    ]
    from app.services.hearthstone_service import _compute_session_delta
    delta = _compute_session_delta(matches)
    # Nur heutige: 5700 → 5905 = +205
    assert delta == 205


# ---------------------------------------------------------------------------
# Tests: format_hearthstone_for_prompt
# ---------------------------------------------------------------------------

def test_format_both_modes():
    """Beide Modi werden separat ausgegeben."""
    from app.services.hearthstone_service import format_hearthstone_for_prompt

    stats = {
        "available": True,
        "solo": {"last_mmr": 5905, "last_result": "won", "last_placement": None},
        "duo": {"last_mmr": 5989, "last_result": "lost", "last_placement": None},
        "session_delta": 100,
        "last_updated": _today_utc_iso(),
        "recent_matches": [],
    }
    output = format_hearthstone_for_prompt(stats)

    assert "Solo MMR: 5905" in output
    assert "Duo MMR: 5989" in output
    assert "+100" in output


def test_format_only_duo():
    """Nur Duo-Daten → Solo zeigt 'keine Daten in History'."""
    from app.services.hearthstone_service import format_hearthstone_for_prompt

    stats = {
        "available": True,
        "solo": {"last_mmr": None, "last_result": None, "last_placement": None},
        "duo": {"last_mmr": 5989, "last_result": "won", "last_placement": None},
        "session_delta": None,
        "last_updated": _today_utc_iso(),
        "recent_matches": [],
    }
    output = format_hearthstone_for_prompt(stats)

    assert "Duo MMR: 5989" in output
    assert "Solo MMR: keine Daten in History" in output


def test_format_unavailable():
    """Nicht verfügbare Stats geben Fehlertext zurück."""
    from app.services.hearthstone_service import format_hearthstone_for_prompt

    stats = {"available": False, "message": "Firestone-Sync noch nicht eingerichtet"}
    output = format_hearthstone_for_prompt(stats)

    assert "nicht verfügbar" in output
    assert "Firestone" in output


def test_format_negative_session_delta():
    """Negativer Session-Delta ohne '+' Prefix."""
    from app.services.hearthstone_service import format_hearthstone_for_prompt

    stats = {
        "available": True,
        "solo": {"last_mmr": 5700, "last_result": "lost", "last_placement": None},
        "duo": {"last_mmr": None, "last_result": None, "last_placement": None},
        "session_delta": -150,
        "last_updated": _today_utc_iso(),
        "recent_matches": [],
    }
    output = format_hearthstone_for_prompt(stats)
    assert "-150" in output
    assert "Solo MMR: 5700" in output


# ---------------------------------------------------------------------------
# Tests: DONNA-253 — hs_log_parser source + placement
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_get_hearthstone_mmr_log_parser_source():
    """DONNA-253: source='hs_log_parser' wird korrekt weitergegeben."""
    data = {
        "last_updated": _today_utc_iso(),
        "source": "hs_log_parser",
        "solo": {
            "player_rank": 5800,
            "new_player_rank": 5905,
            "result": "won",
            "game_mode": "battlegrounds",
            "placement": 3,
            "timestamp": _today_utc_iso(),
        },
        "duo": None,
        "recent_matches": [],
    }

    with tempfile.TemporaryDirectory() as tmp:
        path = _write_latest(tmp, data)
        with patch("app.services.hearthstone_service.FIRESTONE_SYNC_PATH", path):
            from app.services.hearthstone_service import get_hearthstone_mmr
            result = await get_hearthstone_mmr()

    assert result["available"] is True
    assert result["source"] == "hs_log_parser"
    assert result["solo"]["last_placement"] == 3
    assert result["solo"]["last_mmr"] == 5905


@pytest.mark.asyncio
async def test_get_hearthstone_mmr_firestone_fallback_no_source():
    """DONNA-253: Firestone-Daten (kein source-Feld) → source=None."""
    data = {
        "last_updated": _today_utc_iso(),
        # kein "source"-Feld (alter Firestone-Export)
        "solo": {
            "player_rank": 5800,
            "new_player_rank": 5905,
            "result": "won",
            "game_mode": "battlegrounds",
            "timestamp": None,
        },
        "duo": None,
        "recent_matches": [],
    }

    with tempfile.TemporaryDirectory() as tmp:
        path = _write_latest(tmp, data)
        with patch("app.services.hearthstone_service.FIRESTONE_SYNC_PATH", path):
            from app.services.hearthstone_service import get_hearthstone_mmr
            result = await get_hearthstone_mmr()

    assert result["available"] is True
    assert result["source"] is None  # Firestone hat kein source-Feld
    assert result["solo"]["last_placement"] is None  # kein placement in Firestone-Daten


def test_format_log_parser_shows_placement():
    """DONNA-253: Placement wird in Prompt-Output angezeigt wenn source=hs_log_parser."""
    from app.services.hearthstone_service import format_hearthstone_for_prompt

    stats = {
        "available": True,
        "source": "hs_log_parser",
        "solo": {"last_mmr": 5905, "last_result": "lost", "last_placement": 5},
        "duo": {"last_mmr": 5989, "last_result": "won", "last_placement": 2},
        "session_delta": 50,
        "last_updated": _today_utc_iso(),
        "recent_matches": [],
    }
    output = format_hearthstone_for_prompt(stats)

    assert "Platz 5" in output
    assert "Platz 2" in output
    assert "hs_log_parser" in output or "Log-Watcher" in output

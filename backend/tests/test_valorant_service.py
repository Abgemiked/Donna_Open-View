"""Tests für valorant_service.py (DONNA-241 / DONNA-250).

Unit-Tests ohne echte HTTP-Calls — alle httpx-Requests werden gemockt.
Deckt ab:
  - HenrikDev als primäre Quelle (Erfolgsfall)
  - Fallback auf Hexe-Widget bei fehlendem API-Key
  - Fallback auf Hexe-Widget bei HTTP-Fehler von HenrikDev
  - Fallback auf Hexe-Widget bei Timeout von HenrikDev
  - Session-Stats aus heutigen Matches (Win/Loss aus teams-Scores, DONNA-250)
  - Timezone-Fix: Matches um 23:xx UTC (= 01:xx Berliner Zeit) korrekt erkannt (DONNA-250)
  - format_valorant_for_prompt() — Erfolg + Fehlerfall
"""
from __future__ import annotations

import asyncio
import datetime
from unittest.mock import AsyncMock, MagicMock, patch

import pytest


# ---------------------------------------------------------------------------
# Helpers — Mock-Response-Builder
# ---------------------------------------------------------------------------

def _make_response(json_data: dict, status_code: int = 200) -> MagicMock:
    resp = MagicMock()
    resp.status_code = status_code
    resp.json.return_value = json_data
    if status_code >= 400:
        import httpx
        resp.raise_for_status.side_effect = httpx.HTTPStatusError(
            "error",
            request=MagicMock(),
            response=resp,
        )
    else:
        resp.raise_for_status.return_value = None
    return resp


def _mmr_payload(rank: str = "Platinum 2", rr: int = 47, last_change: int = -13) -> dict:
    return {
        "data": {
            "current": {
                "tier": {"name": rank},
                "rr": rr,
                "last_change": last_change,
            }
        }
    }


def _matches_payload(matches: list[dict]) -> dict:
    return {"data": matches}


def _today_match_berlin(won: bool = True, hour_utc: int = 14) -> dict:
    """Erstellt ein Match das heute in Berliner Zeit gespielt wurde.

    DONNA-250: Win/Loss aus teams-Scores (nicht mehr outcome-Feld).
    team=Blue, won → blue > red.
    """
    import zoneinfo
    berlin = zoneinfo.ZoneInfo("Europe/Berlin")
    today_berlin = datetime.datetime.now(berlin).date()
    # Timestamp als UTC
    dt_utc = datetime.datetime(
        today_berlin.year, today_berlin.month, today_berlin.day,
        hour_utc, 30, 0, tzinfo=datetime.timezone.utc
    )
    started_at = dt_utc.strftime("%Y-%m-%dT%H:%M:%S.000Z")
    return {
        "meta": {"started_at": started_at},
        "stats": {"team": "Blue"},
        "teams": {"blue": 13 if won else 7, "red": 7 if won else 13},
    }


def _today_match(won: bool = True, rr_change: int = 20) -> dict:
    """Legacy-Alias für Kompatibilität — benutzt jetzt teams-Format."""
    return _today_match_berlin(won=won, hour_utc=14)


def _yesterday_match() -> dict:
    yesterday = (datetime.date.today() - datetime.timedelta(days=1)).isoformat()
    return {
        "meta": {"started_at": f"{yesterday}T20:00:00Z"},
        "stats": {"team": "Blue"},
        "teams": {"blue": 7, "red": 13},
    }


def _late_night_match_berlin(won: bool = True) -> dict:
    """Match das um 23:xx UTC gespielt wurde — ist 01:xx in Berlin am nächsten Tag.

    DONNA-250 Timezone-Fix: Dieses Match soll heute (Berliner Datum) gezählt werden.
    """
    import zoneinfo
    berlin = zoneinfo.ZoneInfo("Europe/Berlin")
    today_berlin = datetime.datetime.now(berlin).date()
    # Gestern UTC 23:30 = heute Berlin 01:30 (UTC+2)
    yesterday_utc = today_berlin - datetime.timedelta(days=1)
    dt_utc = datetime.datetime(
        yesterday_utc.year, yesterday_utc.month, yesterday_utc.day,
        23, 30, 0, tzinfo=datetime.timezone.utc
    )
    started_at = dt_utc.strftime("%Y-%m-%dT%H:%M:%S.000Z")
    return {
        "meta": {"started_at": started_at},
        "stats": {"team": "Blue"},
        "teams": {"blue": 13 if won else 7, "red": 7 if won else 13},
    }


# ---------------------------------------------------------------------------
# Tests: HenrikDev primäre Quelle
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_henrikdev_success_basic():
    """Erfolgreicher HenrikDev-Call gibt normalisierte Stats zurück."""
    mmr_resp = _make_response(_mmr_payload(rank="Platinum 2", rr=47, last_change=-13))
    matches_resp = _make_response(_matches_payload([]))

    with patch("app.services.valorant_service._HENRIK_API_KEY", "HDEV-test-key"):
        with patch("httpx.AsyncClient") as mock_client_cls:
            mock_client = AsyncMock()
            mock_client_cls.return_value.__aenter__.return_value = mock_client
            mock_client.get.side_effect = [mmr_resp, matches_resp]

            from app.services.valorant_service import _fetch_henrikdev
            result = await _fetch_henrikdev()

    assert result["source"] == "henrikdev"
    assert result["rank"] == "Platinum 2"
    assert result["rr"] == 47
    assert result["last_match_rr"] == -13
    assert result["rr_max"] == 100
    assert "error" not in result


@pytest.mark.asyncio
async def test_henrikdev_session_stats_today_only():
    """Nur heutige Matches (Berliner Zeit) fließen in Session-Stats ein."""
    today_win = _today_match_berlin(won=True, hour_utc=14)
    today_loss = _today_match_berlin(won=False, hour_utc=16)
    yesterday = _yesterday_match()  # soll ignoriert werden

    mmr_resp = _make_response(_mmr_payload())
    matches_resp = _make_response(_matches_payload([today_win, today_loss, yesterday]))

    with patch("app.services.valorant_service._HENRIK_API_KEY", "HDEV-test-key"):
        with patch("httpx.AsyncClient") as mock_client_cls:
            mock_client = AsyncMock()
            mock_client_cls.return_value.__aenter__.return_value = mock_client
            mock_client.get.side_effect = [mmr_resp, matches_resp]

            from app.services.valorant_service import _fetch_henrikdev
            result = await _fetch_henrikdev()

    assert result["wins"] == 1
    assert result["losses"] == 1


@pytest.mark.asyncio
async def test_henrikdev_late_night_matches_counted():
    """DONNA-250 Timezone-Fix: Matches um 23:xx UTC (= heute 01:xx Berliner Zeit) werden gezählt."""
    late_night_win = _late_night_match_berlin(won=True)

    mmr_resp = _make_response(_mmr_payload())
    matches_resp = _make_response(_matches_payload([late_night_win]))

    with patch("app.services.valorant_service._HENRIK_API_KEY", "HDEV-test-key"):
        with patch("httpx.AsyncClient") as mock_client_cls:
            mock_client = AsyncMock()
            mock_client_cls.return_value.__aenter__.return_value = mock_client
            mock_client.get.side_effect = [mmr_resp, matches_resp]

            from app.services.valorant_service import _fetch_henrikdev
            result = await _fetch_henrikdev()

    # Match um 23:30 UTC gestern = 01:30 Berlin heute → soll als heutiges Match gezählt werden
    assert result["wins"] == 1
    assert result["losses"] == 0


# ---------------------------------------------------------------------------
# Tests: Fallback-Szenarien
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_fallback_when_no_api_key():
    """Ohne API-Key wird direkt auf Hexe-Widget zurückgefallen."""
    hexe_data = {
        "rankName": "Gold 3",
        "rankRating": 72,
        "rankRatingMax": 100,
        "rankRatingToday": 15,
        "wins": 2,
        "losses": 1,
        "lastMatchRRChange": 15,
    }
    hexe_resp = _make_response(hexe_data)

    # Kein API-Key
    with patch("app.services.valorant_service._HENRIK_API_KEY", ""):
        with patch("httpx.AsyncClient") as mock_client_cls:
            mock_client = AsyncMock()
            mock_client_cls.return_value.__aenter__.return_value = mock_client
            mock_client.get.return_value = hexe_resp

            from app.services.valorant_service import get_valorant_session_stats
            result = await get_valorant_session_stats()

    assert result["source"] == "hexe_widget_fallback"
    assert result["rank"] == "Gold 3"
    assert result["rr"] == 72
    # Kein HenrikDev-Call — nur ein get()-Aufruf (Hexe)
    mock_client.get.assert_called_once()


@pytest.mark.asyncio
async def test_fallback_on_henrikdev_http_error():
    """Bei HTTP-Fehler von HenrikDev → Fallback auf Hexe."""
    hexe_result = {
        "rank": "Silver 1",
        "rr": 10,
        "rr_max": 100,
        "session_rr": -5,
        "wins": 0,
        "losses": 1,
        "last_match_rr": -5,
        "source": "hexe_widget_fallback",
    }

    with patch("app.services.valorant_service._fetch_henrikdev") as mock_henrik:
        mock_henrik.return_value = {"error": "http_403"}

        with patch("app.services.valorant_service._fetch_hexe_fallback") as mock_hexe:
            mock_hexe.return_value = hexe_result

            from app.services.valorant_service import get_valorant_session_stats
            result = await get_valorant_session_stats()

    assert result["source"] == "hexe_widget_fallback"
    assert result["rank"] == "Silver 1"
    assert "error" not in result
    mock_hexe.assert_called_once_with(reason="http_403")


@pytest.mark.asyncio
async def test_fallback_on_henrikdev_timeout():
    """Bei Timeout von HenrikDev → Fallback auf Hexe."""
    import httpx

    hexe_data = {
        "rankName": "Iron 3",
        "rankRating": 5,
        "rankRatingMax": 100,
        "rankRatingToday": 0,
        "wins": 0,
        "losses": 0,
        "lastMatchRRChange": 0,
    }

    with patch("app.services.valorant_service._HENRIK_API_KEY", "HDEV-test-key"):
        with patch("app.services.valorant_service._fetch_henrikdev") as mock_henrik:
            mock_henrik.return_value = {"error": "timeout"}

            with patch("app.services.valorant_service._fetch_hexe_fallback") as mock_hexe:
                mock_hexe.return_value = {
                    "rank": "Iron 3",
                    "rr": 5,
                    "rr_max": 100,
                    "session_rr": 0,
                    "wins": 0,
                    "losses": 0,
                    "last_match_rr": 0,
                    "source": "hexe_widget_fallback",
                }

                from app.services.valorant_service import get_valorant_session_stats
                result = await get_valorant_session_stats()

    assert result["source"] == "hexe_widget_fallback"
    mock_hexe.assert_called_once_with(reason="timeout")


# ---------------------------------------------------------------------------
# Tests: format_valorant_for_prompt
# ---------------------------------------------------------------------------

def test_format_success():
    """format_valorant_for_prompt() formatiert Stats korrekt."""
    from app.services.valorant_service import format_valorant_for_prompt

    stats = {
        "rank": "Platinum 2",
        "rr": 47,
        "rr_max": 100,
        "session_rr": 7,
        "wins": 2,
        "losses": 1,
        "last_match_rr": -13,
        "source": "henrikdev",
    }
    output = format_valorant_for_prompt(stats)

    assert "Platinum 2" in output
    assert "47" in output
    assert "2W / 1L" in output
    assert "+7" in output
    assert "-13" in output
    assert "Breach" in output


def test_format_error():
    """format_valorant_for_prompt() gibt Fehlertext bei error-Dict zurück."""
    from app.services.valorant_service import format_valorant_for_prompt

    stats = {"error": "timeout"}
    output = format_valorant_for_prompt(stats)

    assert "nicht verfügbar" in output
    assert "timeout" in output


def test_format_positive_session_rr_sign():
    """Positives session_rr bekommt '+' Prefix."""
    from app.services.valorant_service import format_valorant_for_prompt

    stats = {
        "rank": "Gold 1",
        "rr": 30,
        "rr_max": 100,
        "session_rr": 25,
        "wins": 2,
        "losses": 0,
        "last_match_rr": 25,
        "source": "henrikdev",
    }
    output = format_valorant_for_prompt(stats)
    assert "+25" in output


def test_format_zero_session_rr_positive():
    """0 RR-Delta bekommt '+' Prefix (nicht negativ)."""
    from app.services.valorant_service import format_valorant_for_prompt

    stats = {
        "rank": "Bronze 2",
        "rr": 50,
        "rr_max": 100,
        "session_rr": 0,
        "wins": 1,
        "losses": 1,
        "last_match_rr": 0,
        "source": "hexe_widget_fallback",
    }
    output = format_valorant_for_prompt(stats)
    assert "+0" in output

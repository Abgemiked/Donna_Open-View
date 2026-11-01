"""Tests für event_proactive.py (DONNA-109).

Drei Kern-Szenarien:
1. Feature-Flag false → keine Benachrichtigung
2. Event im Zeitfenster → ntfy wird aufgerufen (Mock)
3. Gleiche Event-ID → kein Doppel-Send (Dedup-Set)
"""
from __future__ import annotations

import os
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest


# ── Helpers ───────────────────────────────────────────────────────────────────

def _make_schedule_api_response(
    day_of_week: int,
    from_time: str,
    to_time: str = "20:00",
    activity: str = "Gaming",
) -> dict:
    """Erstellt eine minimale Schedule-API-Antwort für Tests."""
    return {
        "days": [
            {
                "day_of_week": day_of_week,
                "status": "stream",
                "entries": [{"from": from_time, "to": to_time, "activity": activity}],
            }
        ]
    }


def _now_berlin() -> datetime:
    """Aktuelle Zeit in Europe/Berlin."""
    from zoneinfo import ZoneInfo
    return datetime.now(ZoneInfo("Europe/Berlin"))


# ── Test 1: Feature-Flag false → kein Check, keine ntfy ──────────────────────

@pytest.mark.asyncio
async def test_stream_flag_disabled_no_notification():
    """DONNA_EVENT_PROACTIVITY=false → check_stream_event gibt checked=False zurück."""
    with patch.dict(os.environ, {"DONNA_EVENT_PROACTIVITY": "false"}):
        # Modul-Cache umgehen: _is_flag_enabled() liest os.environ direkt
        import app.jobs.event_proactive as ep
        result = await ep.check_stream_event(mistral=None)
    assert result["checked"] is False
    assert result.get("reason") == "flag_disabled"


@pytest.mark.asyncio
async def test_calendar_flag_disabled_no_notification():
    """DONNA_EVENT_PROACTIVITY=false → check_calendar_event gibt checked=False zurück."""
    with patch.dict(os.environ, {"DONNA_EVENT_PROACTIVITY": "false"}):
        import app.jobs.event_proactive as ep
        fake_cal = MagicMock()
        fake_cal.ready.return_value = True
        result = await ep.check_calendar_event(calendar_svc=fake_cal, mistral=None)
    assert result["checked"] is False
    assert result.get("reason") == "flag_disabled"


# ── Test 2: Event im Zeitfenster → ntfy wird aufgerufen ──────────────────────

@pytest.mark.asyncio
async def test_stream_event_in_window_sends_ntfy():
    """Stream startet in 20 Min → ntfy wird aufgerufen, sent=True."""
    import app.jobs.event_proactive as ep

    # Dedup-Set leeren für sauberen Test
    ep._sent_event_ids.clear()

    now = _now_berlin()
    # Stream startet in 20 Minuten
    start = now + timedelta(minutes=20)
    day_idx = start.weekday()
    from_time = start.strftime("%H:%M")

    schedule_data = _make_schedule_api_response(day_idx, from_time)

    with patch.dict(os.environ, {"DONNA_EVENT_PROACTIVITY": "true"}):
        with patch(
            "app.jobs.event_proactive._send_ntfy",
            new_callable=AsyncMock,
            return_value=True,
        ) as mock_ntfy:
            with patch(
                "app.jobs.event_proactive._generate_text",
                new_callable=AsyncMock,
                return_value="Dein Stream startet gleich!",
            ):
                with patch("httpx.AsyncClient") as mock_client_cls:
                    mock_resp = MagicMock()
                    mock_resp.raise_for_status = MagicMock()
                    mock_resp.json.return_value = schedule_data
                    mock_client = AsyncMock()
                    mock_client.__aenter__ = AsyncMock(return_value=mock_client)
                    mock_client.__aexit__ = AsyncMock(return_value=False)
                    mock_client.get = AsyncMock(return_value=mock_resp)
                    mock_client_cls.return_value = mock_client

                    result = await ep.check_stream_event(mistral=None)

    assert result["checked"] is True
    assert result["event_found"] is True
    assert result["sent"] is True
    assert result["minutes_until"] <= 30
    mock_ntfy.assert_called_once()


@pytest.mark.asyncio
async def test_calendar_event_in_window_sends_ntfy():
    """Kalender-Termin in 10 Min → ntfy wird aufgerufen, sent=True."""
    import app.jobs.event_proactive as ep

    ep._sent_event_ids.clear()

    now = datetime.now(tz=timezone.utc)
    event_start = now + timedelta(minutes=10)

    fake_events = [
        {
            "summary": "Meeting mit Kunden",
            "start": {"dateTime": event_start.isoformat()},
            "end": {"dateTime": (event_start + timedelta(hours=1)).isoformat()},
        }
    ]
    fake_cal = MagicMock()
    fake_cal.ready.return_value = True
    fake_cal.get_upcoming_events.return_value = fake_events

    with patch.dict(os.environ, {"DONNA_EVENT_PROACTIVITY": "true"}):
        with patch(
            "app.jobs.event_proactive._send_ntfy",
            new_callable=AsyncMock,
            return_value=True,
        ) as mock_ntfy:
            with patch(
                "app.jobs.event_proactive._generate_text",
                new_callable=AsyncMock,
                return_value="Du hast gleich einen Termin!",
            ):
                result = await ep.check_calendar_event(
                    calendar_svc=fake_cal,
                    mistral=None,
                )

    assert result["checked"] is True
    assert result["event_found"] is True
    assert result["sent"] is True
    assert result["minutes_until"] <= 15
    mock_ntfy.assert_called_once()


# ── Test 3: Gleiche Event-ID → kein Doppel-Send ───────────────────────────────

@pytest.mark.asyncio
async def test_stream_dedup_no_double_send():
    """Zweiter Check mit gleicher Event-ID → kein zweiter ntfy-Call."""
    import app.jobs.event_proactive as ep

    ep._sent_event_ids.clear()

    now = _now_berlin()
    start = now + timedelta(minutes=15)
    day_idx = start.weekday()
    from_time = start.strftime("%H:%M")
    schedule_data = _make_schedule_api_response(day_idx, from_time)

    send_count = 0

    async def _fake_send(*args, **kwargs):
        nonlocal send_count
        send_count += 1
        return True

    with patch.dict(os.environ, {"DONNA_EVENT_PROACTIVITY": "true"}):
        with patch("app.jobs.event_proactive._send_ntfy", side_effect=_fake_send):
            with patch("app.jobs.event_proactive._generate_text", new_callable=AsyncMock, return_value="Test"):
                with patch("httpx.AsyncClient") as mock_client_cls:
                    mock_resp = MagicMock()
                    mock_resp.raise_for_status = MagicMock()
                    mock_resp.json.return_value = schedule_data
                    mock_client = AsyncMock()
                    mock_client.__aenter__ = AsyncMock(return_value=mock_client)
                    mock_client.__aexit__ = AsyncMock(return_value=False)
                    mock_client.get = AsyncMock(return_value=mock_resp)
                    mock_client_cls.return_value = mock_client

                    # Erster Check: sollte senden
                    result1 = await ep.check_stream_event(mistral=None)
                    # Zweiter Check: gleiche Event-ID → kein Send
                    result2 = await ep.check_stream_event(mistral=None)

    assert result1["sent"] is True
    assert result2.get("sent") is False
    assert result2.get("reason") == "already_sent"
    assert send_count == 1  # Nur einmal gesendet


@pytest.mark.asyncio
async def test_calendar_dedup_no_double_send():
    """Zweiter Check mit gleicher Kalender-Event-ID → kein zweiter ntfy-Call."""
    import app.jobs.event_proactive as ep

    ep._sent_event_ids.clear()

    now = datetime.now(tz=timezone.utc)
    event_start = now + timedelta(minutes=10)

    fake_events = [
        {
            "summary": "Standup",
            "start": {"dateTime": event_start.isoformat()},
            "end": {"dateTime": (event_start + timedelta(hours=1)).isoformat()},
        }
    ]
    fake_cal = MagicMock()
    fake_cal.ready.return_value = True
    fake_cal.get_upcoming_events.return_value = fake_events

    send_count = 0

    async def _fake_send(*args, **kwargs):
        nonlocal send_count
        send_count += 1
        return True

    with patch.dict(os.environ, {"DONNA_EVENT_PROACTIVITY": "true"}):
        with patch("app.jobs.event_proactive._send_ntfy", side_effect=_fake_send):
            with patch("app.jobs.event_proactive._generate_text", new_callable=AsyncMock, return_value="Test"):
                result1 = await ep.check_calendar_event(calendar_svc=fake_cal)
                result2 = await ep.check_calendar_event(calendar_svc=fake_cal)

    assert result1["sent"] is True
    # Zweiter Aufruf: Event ist bereits in _sent_event_ids → kein neuer Event gefunden
    assert result2.get("event_found") is False
    assert send_count == 1


# ── Test 4: schedule_event_proactive_jobs registriert Jobs ───────────────────

def test_schedule_registers_two_jobs():
    """schedule_event_proactive_jobs() registriert genau 2 Jobs am Scheduler."""
    import app.jobs.event_proactive as ep

    mock_scheduler = MagicMock()
    mock_app_state = MagicMock()
    mock_app_state.mistral = None
    mock_app_state.calendar = None

    ep.schedule_event_proactive_jobs(mock_scheduler, mock_app_state)

    assert mock_scheduler.add_job.call_count == 2
    job_ids = [call.kwargs["id"] for call in mock_scheduler.add_job.call_args_list]
    assert "event_proactive_stream" in job_ids
    assert "event_proactive_calendar" in job_ids


# ── Test 5: ntfy down → graceful, kein Crash ─────────────────────────────────

@pytest.mark.asyncio
async def test_ntfy_down_graceful():
    """Wenn ntfy nicht erreichbar ist → _send_ntfy gibt False zurück, kein Exception."""
    import httpx
    import app.jobs.event_proactive as ep

    with patch("httpx.AsyncClient") as mock_cls:
        mock_client = AsyncMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)
        mock_client.post = AsyncMock(side_effect=httpx.ConnectError("Connection refused"))
        mock_cls.return_value = mock_client

        result = await ep._send_ntfy(body="Test", ntfy_url="http://localhost:9999", ntfy_topic="test")

    assert result is False  # Kein Crash — graceful False


# ── Test 6: Stream außerhalb Fenster → kein Send ─────────────────────────────

@pytest.mark.asyncio
async def test_stream_outside_window_no_send():
    """Stream startet in 60 Min → außerhalb des 30-Min-Fensters, kein Send."""
    import app.jobs.event_proactive as ep

    ep._sent_event_ids.clear()

    now = _now_berlin()
    start = now + timedelta(minutes=60)
    day_idx = start.weekday()
    from_time = start.strftime("%H:%M")
    schedule_data = _make_schedule_api_response(day_idx, from_time)

    with patch.dict(os.environ, {"DONNA_EVENT_PROACTIVITY": "true"}):
        with patch(
            "app.jobs.event_proactive._send_ntfy",
            new_callable=AsyncMock,
            return_value=True,
        ) as mock_ntfy:
            with patch("httpx.AsyncClient") as mock_client_cls:
                mock_resp = MagicMock()
                mock_resp.raise_for_status = MagicMock()
                mock_resp.json.return_value = schedule_data
                mock_client = AsyncMock()
                mock_client.__aenter__ = AsyncMock(return_value=mock_client)
                mock_client.__aexit__ = AsyncMock(return_value=False)
                mock_client.get = AsyncMock(return_value=mock_resp)
                mock_client_cls.return_value = mock_client

                result = await ep.check_stream_event(mistral=None)

    assert result["event_found"] is False
    mock_ntfy.assert_not_called()

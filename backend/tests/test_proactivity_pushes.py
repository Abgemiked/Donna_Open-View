"""Unit-Tests für DONNA-113: ProactivityService morning_brief() + evening_checkin()

Testet Level-Check, ntfy-Call und Fehlerbehandlung.
"""
from __future__ import annotations

import pytest
from unittest.mock import AsyncMock, MagicMock, patch


# ── Helpers ───────────────────────────────────────────────────────────────────

def make_proactivity_service(level: int = 3):
    """Erstellt einen ProactivityService mit gemocktem get_level()."""
    from app.services.feedback_service import FeedbackService
    from app.services.proactivity_service import ProactivityService

    feedback = MagicMock(spec=FeedbackService)
    svc = ProactivityService(feedback_svc=feedback)
    svc.get_level = MagicMock(return_value=level)
    return svc


def make_ntfy_mock(success: bool = True):
    """Mock für send_push_notification."""
    return AsyncMock(return_value=success)


# ── morning_brief() Tests ─────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_morning_brief_level2_sends_ntfy():
    """morning_brief() sendet bei Level 2 → ntfy aufgerufen 1x."""
    svc = make_proactivity_service(level=2)
    svc.send_push_notification = make_ntfy_mock(success=True)

    result = await svc.morning_brief(
        ntfy_url="http://test-ntfy:80",
        ntfy_topic="test",
    )

    assert result is True
    assert svc.send_push_notification.call_count == 1
    call_kwargs = svc.send_push_notification.call_args
    # Titel prüfen
    assert call_kwargs.kwargs.get("title") == "Guten Morgen, Mike!" or \
           (len(call_kwargs.args) > 1 and call_kwargs.args[1] == "Guten Morgen, Mike!")


@pytest.mark.asyncio
async def test_morning_brief_level1_no_send():
    """morning_brief() sendet NICHT bei Level 1 → ntfy NICHT aufgerufen."""
    svc = make_proactivity_service(level=1)
    svc.send_push_notification = make_ntfy_mock()

    result = await svc.morning_brief(
        ntfy_url="http://test-ntfy:80",
        ntfy_topic="test",
    )

    assert result is False
    svc.send_push_notification.assert_not_called()


@pytest.mark.asyncio
async def test_morning_brief_level3_sends():
    """morning_brief() sendet auch bei Level 3+."""
    svc = make_proactivity_service(level=3)
    svc.send_push_notification = make_ntfy_mock(success=True)

    result = await svc.morning_brief(
        ntfy_url="http://test-ntfy:80",
        ntfy_topic="test",
    )

    assert result is True
    assert svc.send_push_notification.call_count == 1


@pytest.mark.asyncio
async def test_morning_brief_with_calendar():
    """morning_brief() nutzt Calendar-Events für den Body."""
    svc = make_proactivity_service(level=2)
    svc.send_push_notification = make_ntfy_mock(success=True)

    calendar_mock = MagicMock()
    calendar_mock.get_upcoming_events = MagicMock(return_value=[
        {"summary": "Meeting mit Team", "start": {"dateTime": "2026-05-07T10:00:00Z"}}
    ])
    calendar_mock.format_for_prompt = MagicMock(return_value="[Kalender-Kontext]\nHeute 10:00: Meeting mit Team")

    result = await svc.morning_brief(
        calendar_svc=calendar_mock,
        ntfy_url="http://test-ntfy:80",
        ntfy_topic="test",
    )

    assert result is True
    calendar_mock.get_upcoming_events.assert_called_once_with(days=1)


@pytest.mark.asyncio
async def test_morning_brief_calendar_error_no_crash():
    """morning_brief() crasht nicht wenn Calendar-Service fehlschlägt."""
    svc = make_proactivity_service(level=2)
    svc.send_push_notification = make_ntfy_mock(success=True)

    calendar_mock = MagicMock()
    calendar_mock.get_upcoming_events = MagicMock(side_effect=Exception("Calendar error"))

    result = await svc.morning_brief(
        calendar_svc=calendar_mock,
        ntfy_url="http://test-ntfy:80",
        ntfy_topic="test",
    )

    # Kein Crash, ntfy trotzdem gesendet (mit Fallback-Text)
    assert result is True
    assert svc.send_push_notification.call_count == 1


# ── evening_checkin() Tests ───────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_evening_checkin_level2_sends():
    """evening_checkin() sendet bei Level 2 → ntfy aufgerufen 1x."""
    svc = make_proactivity_service(level=2)
    svc.send_push_notification = make_ntfy_mock(success=True)

    result = await svc.evening_checkin(
        ntfy_url="http://test-ntfy:80",
        ntfy_topic="test",
    )

    assert result is True
    assert svc.send_push_notification.call_count == 1
    call_kwargs = svc.send_push_notification.call_args
    assert call_kwargs.kwargs.get("title") == "Abend-Check-in" or \
           (len(call_kwargs.args) > 1 and call_kwargs.args[1] == "Abend-Check-in")


@pytest.mark.asyncio
async def test_evening_checkin_level1_no_send():
    """evening_checkin() sendet NICHT bei Level 1."""
    svc = make_proactivity_service(level=1)
    svc.send_push_notification = make_ntfy_mock()

    result = await svc.evening_checkin(
        ntfy_url="http://test-ntfy:80",
        ntfy_topic="test",
    )

    assert result is False
    svc.send_push_notification.assert_not_called()


@pytest.mark.asyncio
async def test_evening_checkin_level0_no_send():
    """evening_checkin() sendet NICHT bei Level 0 (Edge Case)."""
    svc = make_proactivity_service(level=0)
    svc.send_push_notification = make_ntfy_mock()

    result = await svc.evening_checkin(
        ntfy_url="http://test-ntfy:80",
        ntfy_topic="test",
    )

    assert result is False
    svc.send_push_notification.assert_not_called()


@pytest.mark.asyncio
async def test_evening_checkin_ntfy_failure_returns_false():
    """evening_checkin() gibt False zurück wenn ntfy fehlschlägt."""
    svc = make_proactivity_service(level=2)
    svc.send_push_notification = make_ntfy_mock(success=False)

    result = await svc.evening_checkin(
        ntfy_url="http://test-ntfy:80",
        ntfy_topic="test",
    )

    assert result is False
    assert svc.send_push_notification.call_count == 1

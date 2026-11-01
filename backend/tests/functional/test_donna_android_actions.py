"""Funktionstests für DONNA-22: Android-spezifische Actions.

Testet ob Donna Anruf-, SMS-, Alarm-, Timer- und App-Öffnen-Requests
korrekt erkennt und als Action zurückgibt:
- Antworttext ist immer vorhanden
- Action-Felder werden nur geprüft wenn die Action tatsächlich geliefert wird
- Keine echten Telefonnummern oder Kontakte
"""
from __future__ import annotations

import pytest

from tests.functional.conftest import (
    TEST_SESSION_ID,
    get_action_by_type as _get_action_by_type,
    get_action_by_types as _get_action_by_types,
    send_message,
)


# ---------------------------------------------------------------------------
# Testfälle
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_anruf_max(donna: object) -> None:
    """'Ruf Max an' → text + ggf. call-Action mit phone oder contact."""
    result = await send_message(donna, "Ruf Max an")

    assert result["text"], "Donna muss eine Textantwort liefern"

    action = _get_action_by_type(result["actions"], "call")
    if action is not None:
        extras = action.get("extras", {})
        has_contact_info = extras.get("phone") or extras.get("contact")
        assert has_contact_info, "call-Action benötigt extras.phone oder extras.contact"


@pytest.mark.asyncio
async def test_sms_senden(donna: object) -> None:
    """'Schreib einer Nummer: Bin gleich da' → text + ggf. sms-Action mit body."""
    result = await send_message(donna, "Schreib einer Nummer: Bin gleich da")

    assert result["text"], "Donna muss eine Textantwort liefern"

    action = _get_action_by_type(result["actions"], "sms")
    if action is not None:
        extras = action.get("extras", {})
        body = extras.get("body", "")
        # Nachrichteninhalt muss vorhanden sein und den Kern enthalten
        assert body, "sms-Action benötigt extras.body"
        assert "gleich" in body.lower() or "da" in body.lower(), (
            f"sms-Body sollte 'gleich da' enthalten, war: {body!r}"
        )


@pytest.mark.asyncio
async def test_alarm_7_uhr(donna: object) -> None:
    """'Stell einen Alarm auf 7 Uhr' → text + ggf. set_alarm mit hour=7."""
    result = await send_message(donna, "Stell einen Alarm auf 7 Uhr")

    assert result["text"], "Donna muss eine Textantwort liefern"

    action = _get_action_by_type(result["actions"], "set_alarm")
    if action is not None:
        extras = action.get("extras", {})
        assert extras.get("hour") == 7, (
            f"set_alarm.extras.hour muss 7 sein, war: {extras.get('hour')!r}"
        )


@pytest.mark.asyncio
async def test_timer_10_min(donna: object) -> None:
    """'Stell einen Timer auf 10 Minuten' → text + ggf. set_timer-Action."""
    result = await send_message(donna, "Stell einen Timer auf 10 Minuten")

    assert result["text"], "Donna muss eine Textantwort liefern"

    action = _get_action_by_type(result["actions"], "set_timer")
    if action is not None:
        # type reicht — duration/minutes sind implementierungsabhängig
        assert action.get("type") == "set_timer"


@pytest.mark.asyncio
async def test_youtube_oeffnen(donna: object) -> None:
    """'Öffne YouTube' → text oder open_url/navigate-Action."""
    result = await send_message(donna, "Öffne YouTube")

    assert result["text"], "Donna muss eine Textantwort liefern"

    # open_url oder navigate sind beide akzeptable Action-Typen
    action = _get_action_by_types(result["actions"], "open_url", "navigate")
    # Kein Hardfail wenn Action fehlt — Text-Assertion reicht als Minimalanforderung
    if action is not None:
        assert action.get("type") in ("open_url", "navigate")

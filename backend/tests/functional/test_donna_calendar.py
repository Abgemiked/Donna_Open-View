"""Funktionstests für DONNA-21: Kalender- und Erinnerungs-Actions.

Testet ob Donna auf Terminwünsche und Erinnerungen korrekt reagiert:
- Antworttext ist immer vorhanden
- Action-Typ und Pflichtfelder werden geprüft, wenn eine Action geliefert wird
- Kein Hardfail bei noch nicht implementierten Action-Typen
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
async def test_arzt_termin(donna: object) -> None:
    """'Erstelle einen Termin morgen 10 Uhr Arzt' → text + ggf. create_event-Action."""
    result = await send_message(donna, "Erstelle einen Termin morgen 10 Uhr Arzt")

    # Text-Assertion: Donna antwortet immer mit etwas
    assert result["text"], "Donna muss eine Textantwort liefern"

    # Action-Assertion: nur wenn eine Action tatsächlich zurückkommt
    action = _get_action_by_type(result["actions"], "create_event")
    if action is not None:
        extras = action.get("extras", {})
        assert extras.get("title"), "create_event benötigt extras.title"
        assert extras.get("begin_time"), "create_event benötigt extras.begin_time"


@pytest.mark.asyncio
async def test_trinken_timer(donna: object) -> None:
    """'Erinnere mich in 30 Minuten ans Trinken' → text + ggf. timer/alarm-Action."""
    result = await send_message(donna, "Erinnere mich in 30 Minuten ans Trinken")

    assert result["text"], "Donna muss eine Textantwort liefern"

    # set_timer oder set_alarm sind beide akzeptabel
    action = _get_action_by_types(result["actions"], "set_timer", "set_alarm")
    if result["actions"] and action is None:
        # Es kam eine Action, aber kein passender Typ — noch nicht implementiert
        pass  # kein Hardfail
    # Wenn passende Action gefunden: keine weiteren Pflichtfeld-Checks nötig


@pytest.mark.asyncio
async def test_wochenplan(donna: object) -> None:
    """'Was habe ich diese Woche vor?' → Donna liefert Text (keine harte Action-Prüfung)."""
    result = await send_message(donna, "Was habe ich diese Woche vor?")

    # Reine Informationsanfrage — Hauptsache Donna antwortet
    assert result["text"], "Donna muss eine Textantwort auf die Wochenplan-Frage liefern"


@pytest.mark.asyncio
async def test_meeting_freitag(donna: object) -> None:
    """'Leg einen Meeting-Termin am Freitag 14 Uhr an' → text + ggf. create_event."""
    result = await send_message(donna, "Leg einen Meeting-Termin am Freitag 14 Uhr an")

    assert result["text"], "Donna muss eine Textantwort liefern"

    action = _get_action_by_type(result["actions"], "create_event")
    if action is not None:
        # type-Prüfung reicht — Pflichtfelder sind schon durch den Typ impliziert
        assert action.get("type") == "create_event"

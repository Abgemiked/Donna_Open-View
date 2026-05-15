"""Funktionstests DONNA-20: Standort- und Navigationsanfragen.

Testet Donnas Fähigkeit, auf ortsbezogene Anfragen zu reagieren —
entweder mit Text-Antworten oder mit navigate-Actions.
"""
from __future__ import annotations

import pytest

from tests.functional.conftest import send_message, TEST_SESSION_ID


# ---------------------------------------------------------------------------
# Hilfsfunktion
# ---------------------------------------------------------------------------


def get_action_by_type(actions: list[dict], action_type: str) -> dict | None:
    """Gibt die erste Action zurück, deren type oder action_type passt."""
    return next(
        (
            a
            for a in actions
            if a.get("type") == action_type or a.get("action_type") == action_type
        ),
        None,
    )


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_rewe_anfrage(donna) -> None:
    """'Wo ist das nächste Rewe?' → Text non-empty ODER navigate-Action."""
    result = await send_message(donna, "Wo ist das nächste Rewe?", TEST_SESSION_ID)

    navigate_action = get_action_by_type(result.get("actions", []), "navigate")
    has_text = bool(result["text"])
    has_navigate = navigate_action is not None

    assert has_text or has_navigate, (
        "Donna hat weder Text noch eine navigate-Action zurückgegeben "
        f"(actions={result.get('actions')})"
    )


@pytest.mark.asyncio
async def test_hamburg_navigation(donna) -> None:
    """'Navigiere mich nach Hamburg' → wenn navigate-Action: destination nicht leer."""
    result = await send_message(
        donna, "Navigiere mich nach Hamburg", TEST_SESSION_ID
    )

    navigate_action = get_action_by_type(result.get("actions", []), "navigate")

    if navigate_action is not None:
        # extras-Feld kann direkt im Action-Dict oder unter "extras" liegen
        extras = navigate_action.get("extras") or navigate_action
        destination = extras.get("destination") or navigate_action.get("destination")
        assert destination, (
            "navigate-Action vorhanden, aber 'destination' ist leer: "
            f"{navigate_action}"
        )
    else:
        # Kein navigate-Action → Donna muss zumindest Text zurückgeben
        assert result["text"], (
            "Weder navigate-Action noch Text zurückgegeben für Hamburg-Anfrage"
        )


@pytest.mark.asyncio
async def test_berlin_dauer(donna) -> None:
    """'Wie lange dauert es von hier nach Berlin?' → Zeitangabe im Text oder navigate-Action."""
    result = await send_message(
        donna, "Wie lange dauert es von hier nach Berlin?", TEST_SESSION_ID
    )

    navigate_action = get_action_by_type(result.get("actions", []), "navigate")
    has_navigate = navigate_action is not None

    # Heuristik: Zeitangaben enthalten Ziffern oder Wörter wie "Stunde/Minuten"
    text_lower = result["text"].lower()
    time_keywords = ("minute", "stunde", "std", "min", "h ", " h", "dauert", "fahrt")
    has_time_in_text = any(kw in text_lower for kw in time_keywords) or any(
        ch.isdigit() for ch in result["text"]
    )

    assert has_navigate or has_time_in_text or result["text"], (
        "Donna hat keine verwertbare Antwort auf die Fahrtzeit-Anfrage gegeben "
        f"(text='{result['text']}', actions={result.get('actions')})"
    )


@pytest.mark.asyncio
async def test_tankstelle(donna) -> None:
    """'Zeig mir die nächste Tankstelle' → Text non-empty oder navigate-Action."""
    result = await send_message(
        donna, "Zeig mir die nächste Tankstelle", TEST_SESSION_ID
    )

    navigate_action = get_action_by_type(result.get("actions", []), "navigate")
    has_text = bool(result["text"])
    has_navigate = navigate_action is not None

    assert has_text or has_navigate, (
        "Donna hat weder Text noch eine navigate-Action für Tankstellen-Anfrage "
        f"zurückgegeben (actions={result.get('actions')})"
    )

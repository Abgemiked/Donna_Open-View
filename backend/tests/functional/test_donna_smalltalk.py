"""Funktionstests DONNA-19: Smalltalk / allgemeine Konversation.

Testet grundlegende Gesprächsfähigkeiten von Donna:
- Begrüßung und Befinden
- Eigene Tätigkeiten beschreiben
- Memory-Nutzung (Twitch-Stream-Erinnerung)
- Empathische Reaktion
- Wetteranfrage (Text oder Action)
"""
from __future__ import annotations

import pytest

from tests.functional.conftest import send_message, TEST_SESSION_ID


# ---------------------------------------------------------------------------
# Hilfsfunktion
# ---------------------------------------------------------------------------


def _assert_valid_response(result: dict) -> None:
    """Basisprüfung: Text vorhanden, kein unkontrollierter Error."""
    assert result["text"], "Donna hat keinen Text zurückgegeben"
    text_lower = result["text"].lower()
    assert "error" not in text_lower or len(result["text"]) > 20, (
        f"Donna hat einen Fehler zurückgegeben: {result['text']}"
    )


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_begruessung(donna) -> None:
    """'Hey Donna, wie geht's?' → Text non-empty, kein unkontrollierter Error."""
    result = await send_message(donna, "Hey Donna, wie geht's?", TEST_SESSION_ID)

    _assert_valid_response(result)


@pytest.mark.asyncio
async def test_was_machst_du(donna) -> None:
    """'Was machst du gerade?' → HTTP 200, Text non-empty."""
    result = await send_message(donna, "Was machst du gerade?", TEST_SESSION_ID)

    # send_message würde bei non-200 eine Exception werfen —
    # ein erfolgreiches result impliziert HTTP 200.
    assert result["text"], "Donna hat keinen Text zurückgegeben"


@pytest.mark.asyncio
async def test_twitch_erinnerung(donna) -> None:
    """'Erinnerst du dich an meinen letzten Twitch-Stream?' → testet Memory-Nutzung.

    Donna soll eine inhaltliche Antwort geben (non-empty) — ob sie sich
    tatsächlich erinnert, hängt vom LTM-Stand ab. Wichtig ist, dass kein
    unkontrollierter Fehler auftritt.
    """
    result = await send_message(
        donna,
        "Erinnerst du dich an meinen letzten Twitch-Stream?",
        TEST_SESSION_ID,
    )

    _assert_valid_response(result)


@pytest.mark.asyncio
async def test_keine_lust(donna) -> None:
    """'Ich habe heute keine Lust auf Arbeit' → empathische Antwort, Text non-empty."""
    result = await send_message(
        donna, "Ich habe heute keine Lust auf Arbeit", TEST_SESSION_ID
    )

    _assert_valid_response(result)
    # Empathische Antworten sind in der Regel länger als ein Einwort-Reply
    assert len(result["text"]) > 10, (
        "Donna hat auf eine emotionale Nachricht zu kurz geantwortet"
    )


@pytest.mark.asyncio
async def test_wetter(donna) -> None:
    """'Wie ist das Wetter heute?' → Text non-empty ODER Wetter-/Navigate-/Card-Action."""
    result = await send_message(donna, "Wie ist das Wetter heute?", TEST_SESSION_ID)

    expected_action_types = {"navigate", "card", "weather"}
    action_types = {
        a.get("type") or a.get("action_type")
        for a in result.get("actions", [])
        if a.get("type") or a.get("action_type")
    }

    has_text = bool(result["text"])
    has_action = bool(expected_action_types & action_types)

    assert has_text or has_action, (
        "Donna hat weder Text noch eine passende Action zurückgegeben "
        f"(actions={result.get('actions')})"
    )

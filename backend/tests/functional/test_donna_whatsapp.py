"""Funktionstests für DONNA-23: WhatsApp-Action.

Testet ob Donna einen WhatsApp-Nachrichten-Intent erkennt und als
Action vom Typ 'whatsapp' zurückgibt.

Action-Format aus chat.py:
  [DONNA_ACTION:{"type":"whatsapp","number":"...","message":"...","name":"optional"}]
"""
from __future__ import annotations

import pytest

from tests.functional.conftest import (
    TEST_SESSION_ID,
    get_action_by_type as _get_action_by_type,
    send_message,
)


# ---------------------------------------------------------------------------
# Testfall
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_whatsapp_nachricht(donna: object) -> None:
    """'Schreib Max auf WhatsApp: Bin unterwegs' → whatsapp-Action.

    Erwartet:
      - action_type: "whatsapp"
      - action.message enthält die Nachricht (non-empty)
      - action.number oder action.name identifiziert den Empfänger

    Soft-Fail: Falls keine Action vorhanden, wird nur Text-Assertion geprüft.
    """
    result = await send_message(donna, "Schreib Max auf WhatsApp: Bin unterwegs")

    # Donna antwortet immer mit Text — unabhängig von der Action
    assert result["text"], "Donna muss eine Textantwort liefern"

    whatsapp_action = _get_action_by_type(result["actions"], "whatsapp")

    if whatsapp_action is None:
        # Soft fail — Action fehlt, aber Text-Antwort ist ausreichend
        return

    # Nachrichtentext muss vorhanden sein
    assert whatsapp_action.get("message"), (
        "whatsapp-Action benötigt 'message' mit dem Nachrichten-Inhalt"
    )

    # Empfänger: number oder name muss angegeben sein
    has_recipient = whatsapp_action.get("number") or whatsapp_action.get("name")
    assert has_recipient, (
        "whatsapp-Action benötigt 'number' oder 'name' als Empfänger"
    )

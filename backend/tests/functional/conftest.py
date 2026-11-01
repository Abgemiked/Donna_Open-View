"""Shared fixtures and helpers for Donna functional tests.

Voraussetzungen:
  - Backend läuft unter DONNA_TEST_URL (default: http://localhost:8000)
  - ADMIN_TOKEN ist als Umgebungsvariable gesetzt
"""
from __future__ import annotations

import json
import os
import re

import httpx
import pytest

BASE_URL = os.environ.get("DONNA_TEST_URL", "http://localhost:8000")
ADMIN_TOKEN = os.environ.get("ADMIN_TOKEN", "")
TEST_SESSION_ID = "ft_mike_test"
MIKE_HEADERS = {"Authorization": f"Bearer {ADMIN_TOKEN}"}

# Regex für eingebettete Action-Marker: [DONNA_ACTION:{...}]
_ACTION_RE = re.compile(r"\[DONNA_ACTION:(\{.*?\})\]", re.DOTALL)


async def send_message(
    client: httpx.AsyncClient,
    message: str,
    session_id: str = TEST_SESSION_ID,
) -> dict:
    """Sendet eine Nachricht an Donna und gibt den vollständigen Stream zurück.

    Returns
    -------
    dict mit:
        text    (str)        Vollständiger Antwort-Text (delta-Inhalte zusammengefügt)
        actions (list[dict]) Aus [DONNA_ACTION:{...}]-Markern geparste Dicts
        cards   (list[dict]) Empfangene Card-Events (weather, map, …)
        raw     (str)        Roher SSE-Stream als Text
    """
    full_text = ""
    raw_lines: list[str] = []
    cards: list[dict] = []

    async with client.stream(
        "POST",
        "/chat",
        json={"message": message, "session_id": session_id},
    ) as response:
        async for line in response.aiter_lines():
            raw_lines.append(line)

            if not line.startswith("data:"):
                continue

            payload_str = line[len("data:"):].strip()
            if not payload_str:
                continue

            try:
                event = json.loads(payload_str)
            except json.JSONDecodeError:
                continue

            event_type = event.get("type")

            if event_type == "delta":
                full_text += event.get("content", "")

            elif event_type == "card":
                cards.append(event)

            # "action", "done", "error" werden nicht weiter angereichert —
            # Aufrufer kann raw oder actions auswerten.

    # Action-Marker aus dem zusammengesetzten Text extrahieren
    actions: list[dict] = []
    for match in _ACTION_RE.finditer(full_text):
        try:
            actions.append(json.loads(match.group(1)))
        except json.JSONDecodeError:
            pass

    return {
        "text": full_text,
        "actions": actions,
        "cards": cards,
        "raw": "\n".join(raw_lines),
    }


def get_action_by_type(actions: list[dict], action_type: str) -> dict | None:
    """Gibt die erste Action mit passendem type zurück, oder None."""
    return next((a for a in actions if a.get("type") == action_type), None)


def get_action_by_types(actions: list[dict], *types: str) -> dict | None:
    """Gibt die erste Action zurück, deren type in types enthalten ist."""
    return next((a for a in actions if a.get("type") in types), None)


@pytest.fixture
async def donna() -> httpx.AsyncClient:
    """AsyncClient gegen den laufenden Donna-Backend-Server."""
    async with httpx.AsyncClient(
        base_url=BASE_URL,
        headers=MIKE_HEADERS,
        timeout=60.0,
    ) as client:
        yield client


@pytest.fixture(autouse=False)
async def clean_session(donna: httpx.AsyncClient) -> None:  # noqa: PT004
    """Bereinigt die Test-Session nach dem Test (best-effort).

    Nicht autouse — nur in Tests einbinden, die eine saubere Session brauchen.
    """
    yield
    try:
        await donna.delete(f"/session/{TEST_SESSION_ID}")
    except Exception:  # noqa: BLE001
        pass

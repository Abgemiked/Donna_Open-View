"""Tests für DONNA-191: Piper-TTS entfernt — /tts gibt 501 zurück.

Testet:
1. /tts liefert 501 (Server-TTS nicht mehr verfügbar) — war Piper
2. was_voice_input=False → 501 (TTS generell deaktiviert)
3. pre_synthesize() ist no-op (kein Absturz, kein Fehler)
"""
from __future__ import annotations

import asyncio
import pytest
from fastapi.testclient import TestClient


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture
def client_with_mocks():
    """TestClient mit gemocktem require_admin via Dependency-Override."""
    from app.routes.tts import router
    from app.core.auth import require_admin
    from fastapi import FastAPI

    test_app = FastAPI()
    test_app.include_router(router)

    test_app.dependency_overrides[require_admin] = lambda: "admin"

    with TestClient(test_app) as c:
        yield c


# ---------------------------------------------------------------------------
# Test 1: Voice-Input → 501 (Piper entfernt, kein Server-TTS)
# ---------------------------------------------------------------------------

def test_tts_voice_input_returns_501(client_with_mocks):
    """POST /tts mit was_voice_input=True → 501 (Piper/Server-TTS entfernt, DONNA-191)."""
    resp = client_with_mocks.post("/tts", json={
        "text": "Hallo Mike",
        "was_voice_input": True,
    })
    assert resp.status_code == 501


# ---------------------------------------------------------------------------
# Test 2: Text-Input → 501
# ---------------------------------------------------------------------------

def test_tts_text_input_returns_501(client_with_mocks):
    """was_voice_input=False → 501 (TTS generell deaktiviert, DONNA-191)."""
    resp = client_with_mocks.post("/tts", json={
        "text": "Hallo Mike",
        "was_voice_input": False,
    })
    assert resp.status_code == 501


# ---------------------------------------------------------------------------
# Test 3: pre_synthesize() ist no-op
# ---------------------------------------------------------------------------

def test_pre_synthesize_is_noop():
    """pre_synthesize() läuft durch ohne Fehler (DONNA-191: Piper entfernt)."""
    from app.routes.tts import pre_synthesize
    # Kein Fehler, kein Exception
    asyncio.get_event_loop().run_until_complete(pre_synthesize("Hallo Mike"))


# ---------------------------------------------------------------------------
# Test 4: Backwards-Compat — voice-Feld wird akzeptiert (kein Fehler)
# ---------------------------------------------------------------------------

def test_tts_voice_field_accepted_returns_501(client_with_mocks):
    """Alte Clients dürfen weiter `voice` mitsenden — Endpoint antwortet 501, kein 422."""
    resp = client_with_mocks.post("/tts", json={
        "text": "Hallo Mike",
        "voice": "de_DE-kerstin-low",
        "was_voice_input": True,
    })
    assert resp.status_code == 501

"""Tests für den TTS-Settings-Store + Endpoint (TB2).

Deckt ab:
- Default-Werte (Eva + eleven_v4_turbo)
- Allowlist der model_ids
- set→get Roundtrip (persistiert)
- unbekannte voice_id/model_id → ValueError (Service) / 400 (Endpoint)
- fehlende Datei → Default
- korrupte Datei → Default (nie crash)
- Datei mit ungültigen Werten → Default-Fallback je Feld
- GET/POST /settings/tts (Admin-Auth, Struktur)
"""
from __future__ import annotations

import json

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.services import tts_settings_service as svc
from app.routes import tts_settings as tts_settings_route

EVA = "R24r0IV80Iw9eyByq1ps"
ELISABETH = "3EKymYQPdS1Q9fxZBuq8"


# ---------------------------------------------------------------------------
# Service: Defaults + Allowlist
# ---------------------------------------------------------------------------

def test_default_settings_values():
    d = svc.default_settings()
    assert d.voice_id == EVA
    assert d.model_id == "eleven_v4_turbo"
    assert svc.DEFAULT_MODEL == "eleven_v4_turbo"


def test_allowed_models_contains_expected():
    assert "eleven_v4_turbo" in svc.ALLOWED_MODELS
    assert "eleven_v4" in svc.ALLOWED_MODELS
    assert "eleven_multilingual_v2" in svc.ALLOWED_MODELS


def test_allowed_voice_ids_are_the_two_env_ids():
    assert set(svc.allowed_voice_ids()) == {EVA, ELISABETH}


def test_voices_meta_structure():
    meta = svc.voices_meta()
    names = {v["name"]: v["id"] for v in meta}
    assert names == {"Eva": EVA, "Elisabeth": ELISABETH}


# ---------------------------------------------------------------------------
# Service: load/save
# ---------------------------------------------------------------------------

def test_save_then_load_roundtrip(tmp_path):
    p = tmp_path / "tts_settings.json"
    svc.save_settings(ELISABETH, "eleven_v4", path=p)
    loaded = svc.load_settings(path=p)
    assert loaded.voice_id == ELISABETH
    assert loaded.model_id == "eleven_v4"


def test_save_unknown_voice_raises(tmp_path):
    p = tmp_path / "tts_settings.json"
    with pytest.raises(ValueError):
        svc.save_settings("bogus-voice", "eleven_v4_turbo", path=p)
    assert not p.exists()  # nichts persistiert


def test_save_unknown_model_raises(tmp_path):
    p = tmp_path / "tts_settings.json"
    with pytest.raises(ValueError):
        svc.save_settings(EVA, "gpt-voice-9000", path=p)
    assert not p.exists()


def test_load_missing_file_returns_default(tmp_path):
    p = tmp_path / "does_not_exist.json"
    loaded = svc.load_settings(path=p)
    assert loaded == svc.default_settings()


def test_load_corrupt_file_returns_default(tmp_path):
    p = tmp_path / "tts_settings.json"
    p.write_text("{ this is not valid json ]]", encoding="utf-8")
    loaded = svc.load_settings(path=p)  # darf NICHT crashen
    assert loaded == svc.default_settings()


def test_load_file_with_invalid_values_falls_back_per_field(tmp_path):
    p = tmp_path / "tts_settings.json"
    p.write_text(json.dumps({"voice_id": "nope", "model_id": "nope"}), encoding="utf-8")
    loaded = svc.load_settings(path=p)
    assert loaded == svc.default_settings()


# ---------------------------------------------------------------------------
# Endpoint GET/POST
# ---------------------------------------------------------------------------

@pytest.fixture
def client(tmp_path, monkeypatch):
    from app.core.auth import require_admin

    store = tmp_path / "tts_settings.json"
    monkeypatch.setattr(svc, "_store_path", lambda: store)

    app = FastAPI()
    app.include_router(tts_settings_route.router)
    app.dependency_overrides[require_admin] = lambda: "admin"
    with TestClient(app) as c:
        yield c


def test_get_returns_defaults_and_options(client):
    resp = client.get("/settings/tts")
    assert resp.status_code == 200
    data = resp.json()
    assert data["voice_id"] == EVA
    assert data["model_id"] == "eleven_v4_turbo"
    assert {v["name"] for v in data["voices"]} == {"Eva", "Elisabeth"}
    assert "eleven_v4_turbo" in data["models"]


def test_post_valid_persists_and_get_reflects(client):
    resp = client.post("/settings/tts", json={"voice_id": ELISABETH, "model_id": "eleven_v4"})
    assert resp.status_code == 200
    assert resp.json()["voice_id"] == ELISABETH
    # Persistenz: GET liefert den neuen Wert
    got = client.get("/settings/tts").json()
    assert got["voice_id"] == ELISABETH
    assert got["model_id"] == "eleven_v4"


def test_post_unknown_voice_returns_400(client):
    resp = client.post("/settings/tts", json={"voice_id": "bogus", "model_id": "eleven_v4_turbo"})
    assert resp.status_code == 400


def test_post_unknown_model_returns_400(client):
    resp = client.post("/settings/tts", json={"voice_id": EVA, "model_id": "bogus"})
    assert resp.status_code == 400


# ---------------------------------------------------------------------------
# Fix-Runde A #9: Auth — ohne Token → 401 (kein dependency_override)
# ---------------------------------------------------------------------------

def test_get_and_post_without_token_return_401(tmp_path, monkeypatch):
    from app.config import get_settings

    # require_admin braucht einen konfigurierten ADMIN_TOKEN, sonst käme 503.
    monkeypatch.setenv("ADMIN_TOKEN", "secret-test-token")
    get_settings.cache_clear()
    store = tmp_path / "tts_settings.json"
    monkeypatch.setattr(svc, "_store_path", lambda: store)

    app = FastAPI()
    app.include_router(tts_settings_route.router)  # KEIN require_admin-Override
    try:
        with TestClient(app) as c:
            assert c.get("/settings/tts").status_code == 401
            assert c.post(
                "/settings/tts", json={"voice_id": EVA, "model_id": "eleven_v4_turbo"}
            ).status_code == 401
    finally:
        get_settings.cache_clear()

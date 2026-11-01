"""Tests für DONNA-199: Admin Service Toggle-Endpoints.

Testet:
  GET  /admin/service/status
  POST /admin/service/twitch/enable
  POST /admin/service/twitch/disable

Nutzt FastAPI dependency_overrides um require_admin zu mocken.
State wird vor jedem Test zurückgesetzt.
"""
from __future__ import annotations

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from unittest.mock import MagicMock, patch

from app.core.auth import require_admin
from app.routes.admin_service import router
import app.core.service_state as service_state
import app.jobs.stream_live_watcher as slw_module


# ── Fixtures ──────────────────────────────────────────────────────────────────

@pytest.fixture
def client():
    """TestClient mit gemocktem require_admin (kein echter ADMIN_TOKEN nötig)."""
    test_app = FastAPI()
    test_app.include_router(router)
    test_app.dependency_overrides[require_admin] = lambda: "admin"
    with TestClient(test_app) as c:
        yield c


@pytest.fixture(autouse=True)
def reset_state():
    """Setzt service_state und slw_module vor jedem Test zurück."""
    original_twitch_enabled = service_state.DONNA_TWITCH_ENABLED
    original_proactive = slw_module.DONNA_TWITCH_PROACTIVE_ENABLED
    original_scheduler = service_state.scheduler
    yield
    service_state.DONNA_TWITCH_ENABLED = original_twitch_enabled
    slw_module.DONNA_TWITCH_PROACTIVE_ENABLED = original_proactive
    service_state.scheduler = original_scheduler


# ── GET /admin/service/status ─────────────────────────────────────────────────

class TestGetServiceStatus:
    def test_returns_200_with_correct_structure(self, client):
        response = client.get("/admin/service/status")
        assert response.status_code == 200
        body = response.json()
        assert "donna_assistentin" in body
        assert "donna_twitch" in body

    def test_donna_assistentin_always_enabled(self, client):
        response = client.get("/admin/service/status")
        da = response.json()["donna_assistentin"]
        assert da["enabled"] is True
        assert isinstance(da["uptime_seconds"], int)
        assert da["uptime_seconds"] >= 0

    def test_donna_twitch_reflects_state(self, client):
        service_state.DONNA_TWITCH_ENABLED = False
        slw_module.DONNA_TWITCH_PROACTIVE_ENABLED = False
        response = client.get("/admin/service/status")
        dt = response.json()["donna_twitch"]
        assert dt["enabled"] is False
        assert dt["proactive_enabled"] is False

    def test_requires_auth(self):
        test_app = FastAPI()
        test_app.include_router(router)
        with TestClient(test_app, raise_server_exceptions=False) as c:
            response = c.get("/admin/service/status")
        assert response.status_code in (401, 503)


# ── POST /admin/service/twitch/enable ────────────────────────────────────────

class TestEnableTwitch:
    def test_returns_twitch_enabled(self, client):
        service_state.DONNA_TWITCH_ENABLED = False
        slw_module.DONNA_TWITCH_PROACTIVE_ENABLED = False
        response = client.post("/admin/service/twitch/enable")
        assert response.status_code == 200
        assert response.json() == {"status": "twitch_enabled"}

    def test_sets_flags_true(self, client):
        service_state.DONNA_TWITCH_ENABLED = False
        slw_module.DONNA_TWITCH_PROACTIVE_ENABLED = False
        client.post("/admin/service/twitch/enable")
        assert service_state.DONNA_TWITCH_ENABLED is True
        assert slw_module.DONNA_TWITCH_PROACTIVE_ENABLED is True

    def test_resumes_scheduler_job(self, client):
        mock_scheduler = MagicMock()
        service_state.scheduler = mock_scheduler
        client.post("/admin/service/twitch/enable")
        mock_scheduler.resume_job.assert_called_once_with("stream_live_watcher")

    def test_graceful_when_job_not_found(self, client):
        """JobLookupError soll keinen 500 produzieren."""
        mock_scheduler = MagicMock()
        mock_scheduler.resume_job.side_effect = Exception("Job not found")
        service_state.scheduler = mock_scheduler
        response = client.post("/admin/service/twitch/enable")
        assert response.status_code == 200

    def test_graceful_when_no_scheduler(self, client):
        """Kein Scheduler → trotzdem 200, nur Flag gesetzt."""
        service_state.scheduler = None
        response = client.post("/admin/service/twitch/enable")
        assert response.status_code == 200
        assert service_state.DONNA_TWITCH_ENABLED is True

    def test_requires_auth(self):
        test_app = FastAPI()
        test_app.include_router(router)
        with TestClient(test_app, raise_server_exceptions=False) as c:
            response = c.post("/admin/service/twitch/enable")
        assert response.status_code in (401, 503)


# ── POST /admin/service/twitch/disable ───────────────────────────────────────

class TestDisableTwitch:
    def test_returns_twitch_disabled(self, client):
        response = client.post("/admin/service/twitch/disable")
        assert response.status_code == 200
        assert response.json() == {"status": "twitch_disabled"}

    def test_sets_flags_false(self, client):
        service_state.DONNA_TWITCH_ENABLED = True
        slw_module.DONNA_TWITCH_PROACTIVE_ENABLED = True
        client.post("/admin/service/twitch/disable")
        assert service_state.DONNA_TWITCH_ENABLED is False
        assert slw_module.DONNA_TWITCH_PROACTIVE_ENABLED is False

    def test_pauses_scheduler_job(self, client):
        mock_scheduler = MagicMock()
        service_state.scheduler = mock_scheduler
        client.post("/admin/service/twitch/disable")
        mock_scheduler.pause_job.assert_called_once_with("stream_live_watcher")

    def test_graceful_when_job_not_found(self, client):
        """JobLookupError soll keinen 500 produzieren."""
        mock_scheduler = MagicMock()
        mock_scheduler.pause_job.side_effect = Exception("Job not found")
        service_state.scheduler = mock_scheduler
        response = client.post("/admin/service/twitch/disable")
        assert response.status_code == 200

    def test_graceful_when_no_scheduler(self, client):
        service_state.scheduler = None
        response = client.post("/admin/service/twitch/disable")
        assert response.status_code == 200
        assert service_state.DONNA_TWITCH_ENABLED is False

    def test_requires_auth(self):
        test_app = FastAPI()
        test_app.include_router(router)
        with TestClient(test_app, raise_server_exceptions=False) as c:
            response = c.post("/admin/service/twitch/disable")
        assert response.status_code in (401, 503)

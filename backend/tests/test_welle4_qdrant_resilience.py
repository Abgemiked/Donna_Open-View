"""FIX 1 (funktionstest): /health + Chat-Pfad bleiben bei pausiertem/langsamem Qdrant
antwortfähig.

Deckt ab:
  - health._check_qdrant: harter Timeout, Exception → nicht erreichbar.
  - /health-Endpoint: Qdrant down → status="degraded", Core bleibt 200.
  - chat._ltm_call_bounded: Timeout/Fehler → [] (Event-Loop nie blockiert).
"""
from __future__ import annotations

import time

import httpx
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.routes import health as health_mod
from app.routes.chat import _ltm_call_bounded


# --------------------------------------------------------------------------- #
# _check_qdrant — bounded reachability probe
# --------------------------------------------------------------------------- #

class _FakeResp:
    def __init__(self, status_code: int) -> None:
        self.status_code = status_code


def _fake_client_factory(behavior):
    class _FakeClient:
        def __init__(self, *a, **k) -> None:
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            return False

        async def get(self, url):
            return await behavior(url)

    return _FakeClient


@pytest.mark.asyncio
async def test_check_qdrant_true_on_200(monkeypatch):
    async def _ok(url):
        return _FakeResp(200)
    monkeypatch.setattr(health_mod.httpx, "AsyncClient", _fake_client_factory(_ok))
    assert await health_mod._check_qdrant("http://qdrant:6333", 1.0) is True


@pytest.mark.asyncio
async def test_check_qdrant_true_on_404(monkeypatch):
    # Jede Antwort < 500 = Server lebt.
    async def _nf(url):
        return _FakeResp(404)
    monkeypatch.setattr(health_mod.httpx, "AsyncClient", _fake_client_factory(_nf))
    assert await health_mod._check_qdrant("http://qdrant:6333", 1.0) is True


@pytest.mark.asyncio
async def test_check_qdrant_false_on_timeout(monkeypatch):
    async def _timeout(url):
        raise httpx.TimeoutException("qdrant paused")
    monkeypatch.setattr(health_mod.httpx, "AsyncClient", _fake_client_factory(_timeout))
    assert await health_mod._check_qdrant("http://qdrant:6333", 0.5) is False


@pytest.mark.asyncio
async def test_check_qdrant_false_on_connect_error(monkeypatch):
    async def _refused(url):
        raise httpx.ConnectError("connection refused")
    monkeypatch.setattr(health_mod.httpx, "AsyncClient", _fake_client_factory(_refused))
    assert await health_mod._check_qdrant("http://qdrant:6333", 0.5) is False


# --------------------------------------------------------------------------- #
# /health endpoint — degraded, aber nie hängend
# --------------------------------------------------------------------------- #

class _Stub:
    def __init__(self, **kw):
        self._kw = kw

    def ready(self):
        return self._kw.get("ready", True)

    def count_notes(self):
        return self._kw.get("notes", 0)

    def count_all(self):
        return self._kw.get("all", 1)

    def mem0_ready(self):
        return self._kw.get("mem0_ready", True)

    def chroma_count(self):
        return self._kw.get("chroma", 7)


def _build_app() -> FastAPI:
    app = FastAPI()
    app.include_router(health_mod.router)
    app.state.vault = _Stub(ready=True, notes=3)
    app.state.vector = _Stub(ready=True, all=2)

    class _Gem:
        _model_name = "gemini-x"
        def ready(self):
            return False
    app.state.gemini = _Gem()
    app.state.local_llm = None
    app.state.ltm = _Stub(mem0_ready=True, chroma=7)
    app.state.graphiti = None
    return app


def test_health_degraded_when_qdrant_down(monkeypatch):
    monkeypatch.setattr(health_mod, "_DONNA_MEM0_ENABLED", True)

    async def _down(url, timeout):
        return False
    monkeypatch.setattr(health_mod, "_check_qdrant", _down)

    client = TestClient(_build_app())
    t0 = time.perf_counter()
    r = client.get("/health")
    elapsed = time.perf_counter() - t0

    assert r.status_code == 200  # Core bleibt 200
    body = r.json()
    assert body["status"] == "degraded"
    assert body["qdrant_ready"] is False
    assert body["vault_mounted"] is True
    assert body["chroma_ready"] is True
    assert elapsed < 3.0  # kein 15s-Hänger


def test_health_ok_when_qdrant_up(monkeypatch):
    monkeypatch.setattr(health_mod, "_DONNA_MEM0_ENABLED", True)

    async def _up(url, timeout):
        return True
    monkeypatch.setattr(health_mod, "_check_qdrant", _up)

    client = TestClient(_build_app())
    r = client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ok"
    assert body["qdrant_ready"] is True
    assert body["mem0_client_initialized"] is True


def test_health_real_probe_hang_is_bounded(monkeypatch):
    """R6 #2: Probe hängt echte 10s → asyncio.wait_for-Sicherheitsnetz greift.
    Nicht tautologisch: der Fake kehrt NICHT sofort zurück."""
    import asyncio
    monkeypatch.setattr(health_mod, "_DONNA_MEM0_ENABLED", True)
    monkeypatch.setattr(health_mod, "_QDRANT_HEALTH_TIMEOUT_S", 0.1)

    async def _hang(url, timeout):
        await asyncio.sleep(10)   # Qdrant pausiert/blackholed
        return True

    monkeypatch.setattr(health_mod, "_check_qdrant", _hang)

    client = TestClient(_build_app())
    t0 = time.perf_counter()
    r = client.get("/health")
    elapsed = time.perf_counter() - t0

    assert r.status_code == 200
    assert r.json()["status"] == "degraded"
    assert r.json()["qdrant_ready"] is False
    assert elapsed < 1.0, f"/health hing {elapsed:.2f}s — wait_for-Netz greift nicht"


def test_health_does_not_hang_when_probe_raises(monkeypatch):
    monkeypatch.setattr(health_mod, "_DONNA_MEM0_ENABLED", True)

    async def _boom(url, timeout):
        raise RuntimeError("qdrant client blew up")
    monkeypatch.setattr(health_mod, "_check_qdrant", _boom)

    client = TestClient(_build_app())
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json()["status"] == "degraded"
    assert r.json()["qdrant_ready"] is False


# --------------------------------------------------------------------------- #
# chat._ltm_call_bounded — Event-Loop frei halten
# --------------------------------------------------------------------------- #

@pytest.mark.asyncio
async def test_ltm_call_bounded_returns_data_on_success():
    def _fn(x):
        return [{"content": x}]
    out = await _ltm_call_bounded(_fn, "hallo")
    assert out == [{"content": "hallo"}]


@pytest.mark.asyncio
async def test_ltm_call_bounded_timeout_returns_empty():
    def _slow():
        time.sleep(0.6)
        return [{"content": "zu spät"}]
    t0 = time.perf_counter()
    out = await _ltm_call_bounded(_slow, timeout=0.1)
    elapsed = time.perf_counter() - t0
    assert out == []
    assert elapsed < 0.5  # Timeout greift, kein Warten auf die volle Blockdauer


@pytest.mark.asyncio
async def test_ltm_call_bounded_error_returns_empty():
    def _boom():
        raise RuntimeError("qdrant down")
    out = await _ltm_call_bounded(_boom, timeout=1.0)
    assert out == []

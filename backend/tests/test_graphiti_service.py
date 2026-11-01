"""Tests für GraphitiService (DONNA-111).

Unit-Tests laufen ohne Neo4j — alle externen Calls werden gemockt.
Integration-Tests (pytest.mark.integration) erfordern laufenden Neo4j-Container.
"""
from __future__ import annotations

import importlib
import sys
from unittest.mock import AsyncMock, MagicMock, patch

import pytest


def _fresh_service(monkeypatch, graphiti_enabled: bool = False):
    """Lädt GraphitiService mit frisch gesetztem Env-Flag."""
    monkeypatch.setenv("DONNA_GRAPHITI", "true" if graphiti_enabled else "false")
    # Modul neu laden damit Flag neu ausgewertet wird
    if "app.services.graphiti_service" in sys.modules:
        del sys.modules["app.services.graphiti_service"]
    from app.services.graphiti_service import GraphitiService
    return GraphitiService()


# ---------------------------------------------------------------------------
# Unit: Feature-Flag deaktiviert
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_disabled_by_default(monkeypatch):
    svc = _fresh_service(monkeypatch, graphiti_enabled=False)
    assert svc.enabled() is False
    assert await svc.neo4j_ready() is False
    assert await svc.add_episode("s1", "hallo", "hallo zurück") is False
    assert await svc.search("query") == []


@pytest.mark.asyncio
async def test_close_noop_when_disabled(monkeypatch):
    svc = _fresh_service(monkeypatch, graphiti_enabled=False)
    await svc.close()  # darf keinen Fehler werfen


# ---------------------------------------------------------------------------
# Unit: Init-Fehler → Latch verhindert Retry-Storm
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_init_failure_latches(monkeypatch):
    monkeypatch.setenv("DONNA_GRAPHITI", "true")
    if "app.services.graphiti_service" in sys.modules:
        del sys.modules["app.services.graphiti_service"]

    with patch.dict("sys.modules", {"graphiti_core": MagicMock(side_effect=ImportError("no graphiti"))}):
        from app.services.graphiti_service import GraphitiService
        svc = GraphitiService()
        ready1 = await svc.neo4j_ready()
        ready2 = await svc.neo4j_ready()  # zweiter Aufruf darf nicht neu versuchen
        assert ready1 is False
        assert ready2 is False
        assert svc._init_failed is True


# ---------------------------------------------------------------------------
# Unit: add_episode + search mit gemocktem Client
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_add_episode_calls_graphiti_client(monkeypatch):
    monkeypatch.setenv("DONNA_GRAPHITI", "true")
    if "app.services.graphiti_service" in sys.modules:
        del sys.modules["app.services.graphiti_service"]
    from app.services.graphiti_service import GraphitiService

    fake_client = AsyncMock()
    svc = GraphitiService()
    svc._client = fake_client
    svc._init_done = True

    ok = await svc.add_episode("sess-1", "wie geht's?", "gut, danke!")
    assert ok is True
    fake_client.add_episode.assert_awaited_once()
    call_kwargs = fake_client.add_episode.call_args.kwargs
    assert call_kwargs["name"] == "chat_sess-1"
    assert "wie geht" in call_kwargs["episode_body"]


@pytest.mark.asyncio
async def test_search_returns_facts(monkeypatch):
    monkeypatch.setenv("DONNA_GRAPHITI", "true")
    if "app.services.graphiti_service" in sys.modules:
        del sys.modules["app.services.graphiti_service"]
    from app.services.graphiti_service import GraphitiService

    fake_result = MagicMock()
    fake_result.uuid = "abc-123"
    fake_result.fact = "Mike mag Kaffee"
    fake_result.valid_at = None
    fake_result.invalid_at = None

    fake_client = AsyncMock()
    fake_client.search.return_value = [fake_result]

    svc = GraphitiService()
    svc._client = fake_client
    svc._init_done = True

    results = await svc.search("Kaffee")
    assert len(results) == 1
    assert results[0]["fact"] == "Mike mag Kaffee"
    assert results[0]["uuid"] == "abc-123"


@pytest.mark.asyncio
async def test_search_returns_empty_on_error(monkeypatch):
    monkeypatch.setenv("DONNA_GRAPHITI", "true")
    if "app.services.graphiti_service" in sys.modules:
        del sys.modules["app.services.graphiti_service"]
    from app.services.graphiti_service import GraphitiService

    fake_client = AsyncMock()
    fake_client.search.side_effect = RuntimeError("neo4j down")

    svc = GraphitiService()
    svc._client = fake_client
    svc._init_done = True

    results = await svc.search("irgendwas")
    assert results == []


# ---------------------------------------------------------------------------
# Unit: neo4j_ready mit initialisiertem Client
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_neo4j_ready_true_when_init_done(monkeypatch):
    monkeypatch.setenv("DONNA_GRAPHITI", "true")
    if "app.services.graphiti_service" in sys.modules:
        del sys.modules["app.services.graphiti_service"]
    from app.services.graphiti_service import GraphitiService

    svc = GraphitiService()
    svc._client = AsyncMock()
    svc._init_done = True

    assert await svc.neo4j_ready() is True


# ---------------------------------------------------------------------------
# Integration (markiert — nur mit laufendem Neo4j)
# ---------------------------------------------------------------------------

@pytest.mark.integration
@pytest.mark.asyncio
async def test_integration_add_and_search(monkeypatch):
    """Braucht NEO4J_URI und laufenden Neo4j + Ollama."""
    import os
    if not os.environ.get("NEO4J_URI"):
        pytest.skip("NEO4J_URI nicht gesetzt — Integration-Test übersprungen")

    monkeypatch.setenv("DONNA_GRAPHITI", "true")
    if "app.services.graphiti_service" in sys.modules:
        del sys.modules["app.services.graphiti_service"]
    from app.services.graphiti_service import GraphitiService

    svc = GraphitiService()
    ok = await svc.add_episode("test-int-1", "Mike trinkt morgens Kaffee", "Notiert!")
    assert ok is True

    results = await svc.search("Kaffee")
    assert len(results) >= 1
    await svc.close()

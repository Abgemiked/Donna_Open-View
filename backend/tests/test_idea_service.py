"""Tests für IdeaService (DONNA-115).

Unit-Tests laufen ohne echte LTM/Graphiti-Backends — alle externen Calls werden gemockt.
"""
from __future__ import annotations

import asyncio
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest


# ─── Fixtures ─────────────────────────────────────────────────────────────────

def _make_ltm_mock(stored_entries: list[dict] | None = None):
    """Erstellt einen LTMService-Mock mit konfigurierbaren Rückgabewerten."""
    ltm = MagicMock()
    ltm.store_memory.return_value = "ltm-id-abc123"
    ltm.delete_memory.return_value = True
    ltm.get_all.return_value = stored_entries or []
    ltm.recall_relevant.return_value = []
    return ltm


def _make_graphiti_mock(enabled: bool = False):
    """Erstellt einen GraphitiService-Mock.

    enabled() ist eine sync-Methode → MagicMock für sync-Aufruf.
    add_episode_raw / search sind async → AsyncMock.
    """
    gph = MagicMock()
    gph.enabled.return_value = enabled
    gph.add_episode_raw = AsyncMock(return_value=True)
    gph.search = AsyncMock(return_value=[])
    return gph


@pytest.fixture
def tmp_vault(tmp_path):
    """Temporäres Vault-Verzeichnis für Tests."""
    return str(tmp_path)


# ─── Tests ────────────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_capture_idea_basic(tmp_vault):
    """Idee anlegen: id, title, tags vorhanden, LTM-Speicherung aufgerufen."""
    from app.services.idea_service import IdeaService

    ltm = _make_ltm_mock()
    gph = _make_graphiti_mock(enabled=False)
    svc = IdeaService(ltm=ltm, vault_path=tmp_vault, graphiti_svc=gph)

    idea = await svc.capture_idea(
        raw_input="Ich hätte gerne eine App die meinen ADHS-Workflow automatisiert.",
        title="ADHS-Workflow-App",
        description="Eine App die Tasks automatisch priorisiert.",
        tags=["adhs", "produktivitaet"],
        source="api",
    )

    assert idea.id, "Idea-ID muss gesetzt sein"
    assert idea.title == "ADHS-Workflow-App"
    assert "adhs" in idea.tags
    assert idea.source == "api"
    # LTM-Speicherung muss aufgerufen worden sein
    ltm.store_memory.assert_called_once()
    call_kwargs = ltm.store_memory.call_args
    assert call_kwargs[1].get("category") == "idea" or call_kwargs[0][2] == "idea"


@pytest.mark.asyncio
async def test_capture_idea_obsidian_file(tmp_vault):
    """Vault-Datei wird nach capture_idea im ideas/ Ordner erstellt."""
    from app.services.idea_service import IdeaService

    ltm = _make_ltm_mock()
    gph = _make_graphiti_mock(enabled=False)
    svc = IdeaService(ltm=ltm, vault_path=tmp_vault, graphiti_svc=gph)

    idea = await svc.capture_idea(
        raw_input="Automatisches Obsidian-Tagging via KI wäre cool.",
        title="Obsidian-KI-Tagging",
        source="chat",
    )

    ideas_dir = Path(tmp_vault) / "ideas"
    md_files = list(ideas_dir.glob("*.md"))
    assert len(md_files) == 1, f"Erwartet 1 Obsidian-Datei, gefunden: {md_files}"
    content = md_files[0].read_text(encoding="utf-8")
    assert "Obsidian-KI-Tagging" in content
    assert "DONNA-115" in content
    assert idea.id[:8] in md_files[0].name


@pytest.mark.asyncio
async def test_search_ideas_returns_results(tmp_vault):
    """search_ideas gibt Ideen aus dem LTM zurück."""
    from app.services.idea_service import IdeaService

    ltm = _make_ltm_mock()
    ltm.recall_relevant.return_value = [
        {
            "id": "idea-abc",
            "content": "Idee: Automatisches Tagging\nEin Workflow für automatische Tags.",
            "category": "idea",
            "session_id": "idea:idea-abc",
            "score": 0.82,
        }
    ]
    gph = _make_graphiti_mock(enabled=False)
    svc = IdeaService(ltm=ltm, vault_path=tmp_vault, graphiti_svc=gph)

    results = await svc.search_ideas("automatisches tagging", top_k=3)

    assert len(results) == 1
    assert results[0].id == "idea-abc"
    assert "Tagging" in results[0].title
    ltm.recall_relevant.assert_called_once_with(query="automatisches tagging", top_k=3, category="idea")


@pytest.mark.asyncio
async def test_capture_idea_graphiti_disabled(tmp_vault):
    """Kein Crash wenn DONNA_GRAPHITI=false — Graphiti-Call wird übersprungen."""
    from app.services.idea_service import IdeaService

    ltm = _make_ltm_mock()
    gph = _make_graphiti_mock(enabled=False)
    svc = IdeaService(ltm=ltm, vault_path=tmp_vault, graphiti_svc=gph)

    # Darf keine Exception werfen
    idea = await svc.capture_idea(
        raw_input="Idee für ein neues Feature ohne Graphiti.",
        title="Feature ohne Graph",
        source="api",
    )

    assert idea.id, "Idee muss trotzdem gespeichert werden"
    # Graphiti.add_episode_raw darf NICHT aufgerufen worden sein
    gph.add_episode_raw.assert_not_called()
    # LTM muss aufgerufen worden sein
    ltm.store_memory.assert_called_once()


@pytest.mark.asyncio
async def test_capture_idea_graphiti_enabled(tmp_vault):
    """Wenn Graphiti aktiviert ist, wird add_episode_raw als Task geplant."""
    from app.services.idea_service import IdeaService

    ltm = _make_ltm_mock()
    gph = _make_graphiti_mock(enabled=True)
    svc = IdeaService(ltm=ltm, vault_path=tmp_vault, graphiti_svc=gph)

    # asyncio.create_task mocken damit wir prüfen können ob es aufgerufen wird
    created_tasks: list = []
    original_create_task = asyncio.create_task

    def mock_create_task(coro, **kwargs):
        created_tasks.append(coro)
        # Coroutine schedulieren damit sie nicht hängen bleibt
        task = original_create_task(coro, **kwargs)
        return task

    with patch("app.services.idea_service.asyncio.create_task", side_effect=mock_create_task):
        idea = await svc.capture_idea(
            raw_input="Idee mit aktivem Graphiti-Service.",
            title="Graphiti-Idee",
            tags=["graph", "neo4j"],
            source="api",
        )
        # Task abwarten
        if created_tasks:
            await asyncio.gather(*[t for t in created_tasks], return_exceptions=True)

    assert idea.id
    gph.add_episode_raw.assert_called_once()
    call_kwargs = gph.add_episode_raw.call_args.kwargs
    assert call_kwargs["group_id"] == "ideas"
    assert "Graphiti-Idee" in call_kwargs["name"]


@pytest.mark.asyncio
async def test_update_idea(tmp_vault):
    """update_idea ändert title und description; LTM wird delete+re-insert aufgerufen."""
    from app.services.idea_service import IdeaService

    existing_content = (
        "Idee: Alter Titel\n"
        "Alte Beschreibung.\n"
        "Tags: alt"
    )
    ltm = _make_ltm_mock(stored_entries=[
        {
            "id": "existing-idea-id",
            "content": existing_content,
            "category": "idea",
            "session_id": "idea:existing-idea-id",
        }
    ])
    gph = _make_graphiti_mock(enabled=False)
    svc = IdeaService(ltm=ltm, vault_path=tmp_vault, graphiti_svc=gph)

    updated = await svc.update_idea(
        idea_id="existing-idea-id",
        title="Neuer Titel",
        description="Neue ausführliche Beschreibung.",
        tags=["neu", "verbessert"],
    )

    assert updated is not None
    assert updated.title == "Neuer Titel"
    assert updated.description == "Neue ausführliche Beschreibung."
    assert "neu" in updated.tags
    # LTM: delete + store aufgerufen
    ltm.delete_memory.assert_called_once_with("existing-idea-id")
    ltm.store_memory.assert_called_once()
    # Obsidian-Datei erstellt
    ideas_dir = Path(tmp_vault) / "ideas"
    assert list(ideas_dir.glob("*.md")), "Obsidian-Datei muss erstellt worden sein"


@pytest.mark.asyncio
async def test_update_idea_not_found(tmp_vault):
    """update_idea gibt None zurück wenn Idee nicht gefunden."""
    from app.services.idea_service import IdeaService

    ltm = _make_ltm_mock(stored_entries=[])  # Keine Ideen im LTM
    gph = _make_graphiti_mock(enabled=False)
    svc = IdeaService(ltm=ltm, vault_path=tmp_vault, graphiti_svc=gph)

    result = await svc.update_idea(idea_id="nicht-vorhandene-id", title="Neuer Titel")

    assert result is None
    ltm.delete_memory.assert_not_called()


@pytest.mark.asyncio
async def test_list_ideas_filters_by_category(tmp_vault):
    """list_ideas filtert nur Einträge mit category='idea' aus dem LTM."""
    from app.services.idea_service import IdeaService

    ltm = _make_ltm_mock(stored_entries=[
        {"id": "idea-1", "content": "Idee: Erste Idee\nBeschreibung.", "category": "idea", "session_id": "idea:1"},
        {"id": "fact-1", "content": "Mike ist Streamer.", "category": "user_fact", "session_id": "s1"},
        {"id": "idea-2", "content": "Idee: Zweite Idee\nBeschreibung.", "category": "idea", "session_id": "idea:2"},
    ])
    gph = _make_graphiti_mock(enabled=False)
    svc = IdeaService(ltm=ltm, vault_path=tmp_vault, graphiti_svc=gph)

    ideas = await svc.list_ideas(limit=20)

    assert len(ideas) == 2
    assert all(i.source == "ltm" for i in ideas)


# ─── Hilfsfunktionen ──────────────────────────────────────────────────────────

def test_slugify():
    """_slugify erzeugt korrekte Slugs aus verschiedenen Eingaben."""
    from app.services.idea_service import _slugify

    assert _slugify("ADHS-Workflow-App") == "adhs-workflow-app"
    assert _slugify("Idee für Ämi") == "idee-fuer-aemi"
    assert _slugify("  Leerzeichen  ") == "leerzeichen"
    assert _slugify("") == "idee"  # Fallback
    assert len(_slugify("a" * 100)) <= 60


def test_detect_idea_intent_strong():
    """detect_idea_intent erkennt starke Ideen-Signale."""
    from app.services.idea_service import detect_idea_intent

    score = detect_idea_intent("Man müsste mal eine App bauen die das automatisch macht.")
    assert score >= 0.4


def test_detect_idea_intent_short_text():
    """Kurzer Text ergibt 0.0 Confidence."""
    from app.services.idea_service import detect_idea_intent

    assert detect_idea_intent("kurz") == 0.0
    assert detect_idea_intent("") == 0.0

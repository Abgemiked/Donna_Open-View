"""Tests für VerwaltungDbService (STE-217).

Alle Tests laufen ohne echte DB-Verbindung — asyncpg wird vollständig gemockt.

Test-Übersicht:
  1. Flag off (DONNA_VERWALTUNG_DB=false) → leere Liste, kein Verbindungsversuch
  2. Flag on, Mock-DB → list_projects() gibt korrektes Format zurück
  3. Flag on, Mock-DB → get_project_by_name() findet / findet nicht
  4. Sicherheits-Test: UPDATE/INSERT/DELETE existieren nicht im Service
  5. Ungültiger Status-Filter → wird ignoriert (leere Liste als Fallback)
  6. DONNA_VERWALTUNG_DB_URL fehlt → Service deaktiviert sich selbst
"""
from __future__ import annotations

import importlib
import sys
from unittest.mock import AsyncMock, MagicMock, patch

import pytest


# ---------------------------------------------------------------------------
# Helper: frischen Service-Import mit gesetzten ENV-Variablen
# ---------------------------------------------------------------------------

def _fresh_service(monkeypatch, enabled: bool = True, db_url: str | None = "postgresql://donna_readonly:pw@localhost/verwaltung"):
    """Lädt VerwaltungDbService mit frisch gesetzten Env-Flags."""
    monkeypatch.setenv("DONNA_VERWALTUNG_DB", "true" if enabled else "false")
    if db_url is not None:
        monkeypatch.setenv("DONNA_VERWALTUNG_DB_URL", db_url)
    else:
        monkeypatch.delenv("DONNA_VERWALTUNG_DB_URL", raising=False)

    # Modul-Cache leeren damit Env-Flags neu gelesen werden
    for mod in list(sys.modules.keys()):
        if "verwaltung_db" in mod:
            del sys.modules[mod]

    from app.services.verwaltung_db import VerwaltungDbService
    return VerwaltungDbService()


# ---------------------------------------------------------------------------
# Mock-Projekt-Daten (entspricht verwaltung.projects-Schema)
# ---------------------------------------------------------------------------

_MOCK_PROJECTS = [
    {
        "id": 1,
        "name": "Verwaltungstool",
        "status": "entwicklung",
        "subdomain": "verwaltung.example.com",
        "cwd": "/root/verwaltung",
        "stack": "React+TS+Vite, Node.js+Express, PostgreSQL, Docker",
        "brand": "Abgemiked Media",
        "description": "Zentrales Verwaltungs-Dashboard mit TOTP-Auth",
        "created_at": None,
        "updated_at": None,
    },
    {
        "id": 2,
        "name": "SteuernCRM",
        "status": "live",
        "subdomain": "crm.example.com",
        "cwd": "/root/steuern-crm",
        "stack": "React+TS, FastAPI, PostgreSQL",
        "brand": "Abgemiked Media",
        "description": "CRM für Steuerberatung",
        "created_at": None,
        "updated_at": None,
    },
]


# ===========================================================================
# Test 1: Feature-Flag off → kein Verbindungsversuch, leere Liste
# ===========================================================================

@pytest.mark.asyncio
async def test_flag_off_returns_empty_list(monkeypatch):
    """DONNA_VERWALTUNG_DB=false → list_projects() gibt [] zurück ohne DB-Call."""
    # asyncpg via sys.modules mocken (nicht installiert in Test-Env)
    mock_asyncpg = MagicMock()
    mock_asyncpg.create_pool = AsyncMock()
    with patch.dict(sys.modules, {"asyncpg": mock_asyncpg}):
        svc = _fresh_service(monkeypatch, enabled=False)
        assert svc.enabled() is False

        result = await svc.list_projects()
        assert result == []
        mock_asyncpg.create_pool.assert_not_called()


@pytest.mark.asyncio
async def test_flag_off_get_project_returns_none(monkeypatch):
    """DONNA_VERWALTUNG_DB=false → get_project_by_name() gibt None zurück ohne DB-Call."""
    mock_asyncpg = MagicMock()
    mock_asyncpg.create_pool = AsyncMock()
    with patch.dict(sys.modules, {"asyncpg": mock_asyncpg}):
        svc = _fresh_service(monkeypatch, enabled=False)

        result = await svc.get_project_by_name("Verwaltungstool")
        assert result is None
        mock_asyncpg.create_pool.assert_not_called()


# ===========================================================================
# Test 2: Flag on, Mock-DB → list_projects() gibt korrektes Format zurück
# ===========================================================================

@pytest.mark.asyncio
async def test_list_projects_returns_correct_format(monkeypatch):
    """list_projects() gibt Liste von Dicts mit erwarteten Feldern zurück."""
    svc = _fresh_service(monkeypatch, enabled=True)
    assert svc.enabled() is True

    # asyncpg Pool mocken
    mock_pool = AsyncMock()
    mock_pool.fetch = AsyncMock(return_value=[
        dict(row) for row in _MOCK_PROJECTS
    ])
    svc._pool = mock_pool

    result = await svc.list_projects()

    assert isinstance(result, list)
    assert len(result) == 2

    first = result[0]
    assert "id" in first
    assert "name" in first
    assert "status" in first
    assert "subdomain" in first
    assert "brand" in first
    assert first["name"] == "Verwaltungstool"
    assert first["status"] == "entwicklung"


@pytest.mark.asyncio
async def test_list_projects_with_valid_status_filter(monkeypatch):
    """list_projects(status='live') übergibt Status-Parameter an DB-Query."""
    svc = _fresh_service(monkeypatch, enabled=True)

    mock_pool = AsyncMock()
    live_projects = [dict(row) for row in _MOCK_PROJECTS if row["status"] == "live"]
    mock_pool.fetch = AsyncMock(return_value=live_projects)
    svc._pool = mock_pool

    result = await svc.list_projects(status="live")

    assert len(result) == 1
    assert result[0]["status"] == "live"
    # Verifikation dass fetch mit Parameter aufgerufen wurde
    mock_pool.fetch.assert_awaited_once()
    call_args = mock_pool.fetch.call_args
    assert "WHERE status = $1" in call_args[0][0]


# ===========================================================================
# Test 3: get_project_by_name() — findet / findet nicht
# ===========================================================================

@pytest.mark.asyncio
async def test_get_project_by_name_found(monkeypatch):
    """get_project_by_name('Verwaltung') findet das Projekt (ILIKE-Suche)."""
    svc = _fresh_service(monkeypatch, enabled=True)

    mock_pool = AsyncMock()
    mock_pool.fetchrow = AsyncMock(return_value=_MOCK_PROJECTS[0])
    svc._pool = mock_pool

    result = await svc.get_project_by_name("Verwaltung")

    assert result is not None
    assert result["name"] == "Verwaltungstool"
    mock_pool.fetchrow.assert_awaited_once()


@pytest.mark.asyncio
async def test_get_project_by_name_not_found(monkeypatch):
    """get_project_by_name('XYZ') gibt None zurück wenn kein Projekt gefunden."""
    svc = _fresh_service(monkeypatch, enabled=True)

    mock_pool = AsyncMock()
    mock_pool.fetchrow = AsyncMock(return_value=None)
    svc._pool = mock_pool

    result = await svc.get_project_by_name("NichtExistierendesProjekt")

    assert result is None


@pytest.mark.asyncio
async def test_get_project_by_name_empty_string(monkeypatch):
    """get_project_by_name('') gibt None zurück (Guard gegen leere Suche)."""
    svc = _fresh_service(monkeypatch, enabled=True)

    mock_pool = AsyncMock()
    svc._pool = mock_pool

    result = await svc.get_project_by_name("")

    assert result is None
    mock_pool.fetchrow.assert_not_called()


# ===========================================================================
# Test 4: Sicherheits-Test — keine Schreibmethoden im Service
# ===========================================================================

def test_no_insert_method(monkeypatch):
    """Sicherheit: VerwaltungDbService hat keine insert_project-Methode."""
    svc = _fresh_service(monkeypatch, enabled=True)
    assert not hasattr(svc, "insert_project"), \
        "VerwaltungDbService darf keine insert_project-Methode haben!"


def test_no_update_method(monkeypatch):
    """Sicherheit: VerwaltungDbService hat keine update_project-Methode."""
    svc = _fresh_service(monkeypatch, enabled=True)
    assert not hasattr(svc, "update_project"), \
        "VerwaltungDbService darf keine update_project-Methode haben!"


def test_no_delete_method(monkeypatch):
    """Sicherheit: VerwaltungDbService hat keine delete_project-Methode."""
    svc = _fresh_service(monkeypatch, enabled=True)
    assert not hasattr(svc, "delete_project"), \
        "VerwaltungDbService darf keine delete_project-Methode haben!"


def test_no_execute_method(monkeypatch):
    """Sicherheit: VerwaltungDbService hat keine generische execute()-Methode."""
    svc = _fresh_service(monkeypatch, enabled=True)
    assert not hasattr(svc, "execute"), \
        "VerwaltungDbService darf keine execute()-Methode haben!"


def test_write_methods_raise_attribute_error(monkeypatch):
    """Sicherheit: Schreibende Methoden-Aufrufe erzeugen AttributeError."""
    svc = _fresh_service(monkeypatch, enabled=True)
    # Direkter Attributzugriff muss AttributeError werfen
    with pytest.raises(AttributeError):
        _ = svc.insert_project  # type: ignore[attr-defined]
    with pytest.raises(AttributeError):
        _ = svc.update_project  # type: ignore[attr-defined]
    with pytest.raises(AttributeError):
        _ = svc.delete_project  # type: ignore[attr-defined]


# ===========================================================================
# Test 5: Ungültiger Status-Filter wird ignoriert
# ===========================================================================

@pytest.mark.asyncio
async def test_invalid_status_filter_ignored(monkeypatch):
    """Ungültiger Status-Filter ('hacked') wird ignoriert — Query läuft ohne Filter."""
    svc = _fresh_service(monkeypatch, enabled=True)

    mock_pool = AsyncMock()
    mock_pool.fetch = AsyncMock(return_value=list(_MOCK_PROJECTS))
    svc._pool = mock_pool

    # Ungültiger Status — soll graceful ignoriert werden (kein Exception)
    result = await svc.list_projects(status="hacked'; DROP TABLE verwaltung.projects;--")

    # Query muss ausgeführt worden sein (ohne Filter)
    assert isinstance(result, list)
    call_args = mock_pool.fetch.call_args
    # Kein WHERE-Clause mit dem ungültigen Status
    assert "hacked" not in call_args[0][0]


# ===========================================================================
# Test 6: Fehlende DB-URL → Service deaktiviert sich
# ===========================================================================

def test_missing_db_url_disables_service(monkeypatch):
    """DONNA_VERWALTUNG_DB=true aber DONNA_VERWALTUNG_DB_URL fehlt → disabled."""
    svc = _fresh_service(monkeypatch, enabled=True, db_url=None)
    assert svc.enabled() is False


# ===========================================================================
# Test 7: DB-Fehler → graceful Empty-List (kein Exception-Propagation)
# ===========================================================================

@pytest.mark.asyncio
async def test_db_error_returns_empty_list(monkeypatch):
    """Bei DB-Fehler gibt list_projects() [] zurück statt Exception zu werfen."""
    svc = _fresh_service(monkeypatch, enabled=True)

    mock_pool = AsyncMock()
    mock_pool.fetch = AsyncMock(side_effect=RuntimeError("Connection refused"))
    svc._pool = mock_pool

    result = await svc.list_projects()
    assert result == []


@pytest.mark.asyncio
async def test_db_error_get_project_returns_none(monkeypatch):
    """Bei DB-Fehler gibt get_project_by_name() None zurück."""
    svc = _fresh_service(monkeypatch, enabled=True)

    mock_pool = AsyncMock()
    mock_pool.fetchrow = AsyncMock(side_effect=RuntimeError("Connection refused"))
    svc._pool = mock_pool

    result = await svc.get_project_by_name("Verwaltungstool")
    assert result is None

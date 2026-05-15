"""projekte router — Donna Read-Only-Zugriff auf verwaltung.projects (STE-217).

Endpoints:
  GET  /projekte            — alle Projekte (optional: ?status=live)
  GET  /projekte/{name}     — einzelnes Projekt nach Name

Auth: Bearer-Token (identisch zu /ltm, /chat, /ideas).

Sicherheitshinweis:
  - Nur GET — kein POST, PUT, PATCH, DELETE
  - Daten kommen aus VerwaltungDbService (SELECT-only)
  - Kein Zugriff auf user_auth oder andere sensible Tabellen
"""
from __future__ import annotations

from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from pydantic import BaseModel, Field

from app.core.auth import require_admin
from app.services.verwaltung_db import VerwaltungDbService

router = APIRouter(prefix="/projekte", tags=["projekte"])


# ─── Pydantic-Modelle ─────────────────────────────────────────────────────────

class ProjektResponse(BaseModel):
    id: int
    name: str
    status: str
    subdomain: Optional[str] = None
    stack: Optional[str] = None
    brand: str
    description: Optional[str] = None


# ─── Helper ───────────────────────────────────────────────────────────────────

def _get_svc(request: Request) -> VerwaltungDbService:
    svc = getattr(request.app.state, "verwaltung_db", None)
    if svc is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="VerwaltungDbService nicht initialisiert.",
        )
    return svc  # type: ignore[return-value]


# ─── Endpoints ────────────────────────────────────────────────────────────────

@router.get("", response_model=list[ProjektResponse])
async def list_projekte(
    request: Request,
    status_filter: Optional[str] = Query(
        default=None,
        alias="status",
        description="Filtert nach Status: planung|entwicklung|live|archiviert",
    ),
    _admin: str = Depends(require_admin),
) -> list[ProjektResponse]:
    """Listet alle Projekte aus verwaltung.projects (read-only)."""
    svc = _get_svc(request)
    projects = await svc.list_projects(status=status_filter)
    return [ProjektResponse(**_to_response(p)) for p in projects]


@router.get("/{name}", response_model=ProjektResponse)
async def get_projekt(
    name: str,
    request: Request,
    _admin: str = Depends(require_admin),
) -> ProjektResponse:
    """Gibt ein einzelnes Projekt nach Name zurück."""
    svc = _get_svc(request)
    project = await svc.get_project_by_name(name)
    if project is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Projekt '{name}' nicht gefunden.",
        )
    return ProjektResponse(**_to_response(project))


# ─── Interne Helpers ──────────────────────────────────────────────────────────

def _to_response(p: dict) -> dict:
    """Filtert nur die öffentlichen Felder heraus (kein cwd etc.)."""
    return {
        "id": p["id"],
        "name": p["name"],
        "status": p["status"],
        "subdomain": p.get("subdomain"),
        "stack": p.get("stack"),
        "brand": p.get("brand", "Abgemiked Media"),
        "description": p.get("description"),
    }

"""ideas router — Strukturierte Ideen-Erfassung (DONNA-115).

Endpoints:
  POST   /ideas           — neue Idee erfassen
  GET    /ideas           — alle Ideen auflisten
  GET    /ideas/search    — semantische + Graph-Suche
  PATCH  /ideas/{id}      — Idee aktualisieren

Auth: Bearer-Token (identisch zu /ltm, /chat).
"""
from __future__ import annotations

from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from pydantic import BaseModel, Field

from app.core.auth import require_admin
from app.services.idea_service import IdeaService

router = APIRouter(prefix="/ideas", tags=["ideas"])


# ─── Pydantic-Modelle ─────────────────────────────────────────────────────────

class IdeaCaptureRequest(BaseModel):
    raw_input: str = Field(..., min_length=1, max_length=2000, description="Originaltext der Idee")
    title: str = Field(default="", max_length=120, description="Kurztitel (wird abgeleitet wenn leer)")
    description: str = Field(default="", max_length=4000, description="Ausführliche Beschreibung")
    tags: list[str] = Field(default_factory=list, description="Schlagworte")
    source: str = Field(default="api", description="Herkunft: chat | api | voice")


class IdeaUpdateRequest(BaseModel):
    title: Optional[str] = Field(default=None, max_length=120)
    description: Optional[str] = Field(default=None, max_length=4000)
    tags: Optional[list[str]] = Field(default=None)


class IdeaResponse(BaseModel):
    id: str
    title: str
    description: str
    raw_input: str
    tags: list[str]
    created_at: str
    updated_at: str
    source: str


# ─── Helper ───────────────────────────────────────────────────────────────────

def _get_idea_svc(request: Request) -> IdeaService:
    svc = getattr(request.app.state, "ideas", None)
    if svc is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="IdeaService nicht initialisiert.",
        )
    return svc  # type: ignore[return-value]


# ─── Endpoints ────────────────────────────────────────────────────────────────

@router.post("", response_model=IdeaResponse, status_code=status.HTTP_201_CREATED)
async def capture_idea(
    body: IdeaCaptureRequest,
    request: Request,
    _admin: str = Depends(require_admin),
) -> IdeaResponse:
    """Erfasst eine neue Idee und speichert sie in LTM + Obsidian + Graphiti."""
    svc = _get_idea_svc(request)
    idea = await svc.capture_idea(
        raw_input=body.raw_input,
        title=body.title,
        description=body.description,
        tags=body.tags,
        source=body.source,
    )
    return IdeaResponse(**idea.to_dict())


@router.get("", response_model=list[IdeaResponse])
async def list_ideas(
    request: Request,
    limit: int = Query(default=20, ge=1, le=100, description="Maximale Anzahl Ideen"),
    _admin: str = Depends(require_admin),
) -> list[IdeaResponse]:
    """Listet alle gespeicherten Ideen (aus LTM)."""
    svc = _get_idea_svc(request)
    ideas = await svc.list_ideas(limit=limit)
    return [IdeaResponse(**i.to_dict()) for i in ideas]


@router.get("/search", response_model=list[IdeaResponse])
async def search_ideas(
    request: Request,
    q: str = Query(..., min_length=2, max_length=500, description="Suchanfrage"),
    top_k: int = Query(default=5, ge=1, le=20, description="Max. Ergebnisse"),
    _admin: str = Depends(require_admin),
) -> list[IdeaResponse]:
    """Semantische Suche über Ideen (LTM + Graphiti wenn aktiviert)."""
    svc = _get_idea_svc(request)
    ideas = await svc.search_ideas(query=q, top_k=top_k)
    return [IdeaResponse(**i.to_dict()) for i in ideas]


@router.patch("/{idea_id}", response_model=IdeaResponse)
async def update_idea(
    idea_id: str,
    body: IdeaUpdateRequest,
    request: Request,
    _admin: str = Depends(require_admin),
) -> IdeaResponse:
    """Aktualisiert Titel, Beschreibung oder Tags einer bestehenden Idee."""
    svc = _get_idea_svc(request)
    updated = await svc.update_idea(
        idea_id=idea_id,
        title=body.title,
        description=body.description,
        tags=body.tags,
    )
    if updated is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Idee '{idea_id}' nicht gefunden.",
        )
    return IdeaResponse(**updated.to_dict())

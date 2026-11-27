"""LTM router — Long-Term Memory management endpoints.

Endpoints:
  GET    /ltm              — retrieve all stored memories
  DELETE /ltm/{memory_id} — delete a specific memory by ID
  POST   /ltm/curate       — manueller LTM-Curation-Trigger (dry_run-Support)
  POST   /ltm/seed         — Speichert eine Memory direkt (Admin-Seeding) — DONNA-241/242/243

Auth: Bearer-token (reuses existing require_admin dependency).
"""
from __future__ import annotations

import asyncio

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from pydantic import BaseModel, Field

from app.core.auth import require_admin
from app.jobs.ltm_curation import run_curation
from app.services.ltm_service import LTMService

router = APIRouter(prefix="/ltm", tags=["ltm"])


def _get_ltm(request: Request) -> LTMService:
    return request.app.state.ltm  # type: ignore[no-any-return]


@router.get("")
async def get_all_memories(
    request: Request,
    _admin: str = Depends(require_admin),
) -> list[dict]:
    """Return all stored long-term memories."""
    ltm: LTMService = _get_ltm(request)
    return ltm.get_all()


@router.delete("/{memory_id}")
async def delete_memory(
    memory_id: str,
    request: Request,
    _admin: str = Depends(require_admin),
) -> dict:
    """Delete a specific memory by ID."""
    ltm: LTMService = _get_ltm(request)
    deleted = ltm.delete_memory(memory_id)
    if not deleted:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Memory '{memory_id}' not found.",
        )
    return {"deleted": True, "memory_id": memory_id}


@router.post("/curate")
async def curate_ltm(
    request: Request,
    dry_run: bool = Query(default=False, description="Nur loggen, nichts löschen."),
    _admin: str = Depends(require_admin),
) -> dict:
    """Manueller LTM-Curation-Trigger. Mit ?dry_run=true nur Vorschau."""
    ltm: LTMService = _get_ltm(request)
    return await run_curation(ltm, dry_run=dry_run)


class SeedMemoryRequest(BaseModel):
    content: str = Field(..., min_length=1, max_length=4000)
    category: str = Field(default="user_fact")
    session_id: str = Field(default="admin_seed")


@router.post("/seed")
async def seed_memory(
    body: SeedMemoryRequest,
    request: Request,
    _admin: str = Depends(require_admin),
) -> dict:
    """Speichert eine Memory direkt — für Admin-Seeding (z.B. Static Knowledge).

    DONNA-241/242/243: Injiziert Spiele-Facts (Valorant/Hearthstone/Megabonk)
    als user_fact-Memories ohne LLM-Roundtrip.

    Gültige Kategorien: user_preference, user_fact, user_habit, idea
    """
    ltm: LTMService = _get_ltm(request)
    # DONNA-231: store_memory ist sync (ChromaDB/mem0-Netzwerk) → Event-Loop nicht blockieren
    mem_id = await asyncio.to_thread(
        ltm.store_memory,
        session_id=body.session_id,
        content=body.content,
        category=body.category,
    )
    if not mem_id:
        # Dedup-Skip oder PII-Block — kein Fehler, Memory existiert bereits oder war ungültig
        return {"stored": False, "reason": "dedup_or_pii_skip"}
    return {"stored": True, "memory_id": mem_id, "category": body.category}

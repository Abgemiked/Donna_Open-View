"""/stats — Consistency-Tracking + Mood-History Endpoints.

Routes:
  GET  /stats/consistency           — get_summary() (streak, total_30d, today_count)
  GET  /stats/mood?days=7           — Mood-History der letzten N Tage
  POST /stats/mood/{log_id}/correct — Mood-Korrektur durch Mike

Auth: Bearer-Token (require_admin Dependency).
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from pydantic import BaseModel

from app.core.auth import require_admin
from app.services.consistency_service import ConsistencyService
from app.services.mood_service import MoodService

router = APIRouter(prefix="/stats", tags=["stats"])


class MoodCorrection(BaseModel):
    correct_mood: str


def _get_consistency(request: Request) -> ConsistencyService:
    svc: ConsistencyService | None = getattr(request.app.state, "consistency", None)
    if svc is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="ConsistencyService not initialised.",
        )
    return svc


def _get_mood(request: Request) -> MoodService:
    svc: MoodService | None = getattr(request.app.state, "mood", None)
    if svc is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="MoodService not initialised.",
        )
    return svc


@router.get("/consistency")
async def get_consistency(
    request: Request,
    _admin: str = Depends(require_admin),
) -> dict:
    """Nutzungs-Zusammenfassung: Streak, 30d-Aktivität, heutiger Zähler."""
    svc = _get_consistency(request)
    return svc.get_summary()


@router.get("/mood")
async def get_mood_history(
    request: Request,
    days: int = Query(default=7, ge=1, le=90),
    _admin: str = Depends(require_admin),
) -> list[dict]:
    """Mood-History der letzten N Tage (max 90)."""
    svc = _get_mood(request)
    return svc.get_mood_history(days=days)


@router.post("/mood/{log_id}/correct")
async def correct_mood(
    log_id: int,
    body: MoodCorrection,
    request: Request,
    _admin: str = Depends(require_admin),
) -> dict:
    """Korrigiert eine Mood-Erkennung manuell."""
    svc = _get_mood(request)
    success = svc.correct_mood(log_id, body.correct_mood)
    if not success:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Mood-Log #{log_id} nicht gefunden oder ungültige Mood.",
        )
    return {"corrected": True, "log_id": log_id, "correct_mood": body.correct_mood}

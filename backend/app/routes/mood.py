"""mood.py — Mood-History und Korrektur-Endpoints."""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel

from app.core.auth import require_admin

router = APIRouter(prefix="/mood", tags=["mood"])


class MoodCorrection(BaseModel):
    correct_mood: str


@router.get("/history")
async def mood_history(
    request: Request,
    days: int = 7,
    _admin: str = Depends(require_admin),
) -> list[dict]:
    """Mood-Log der letzten N Tage (default 7)."""
    svc = getattr(request.app.state, "mood", None)
    if svc is None:
        return []
    return svc.get_mood_history(days=min(days, 90))


@router.post("/{log_id}/correct")
async def correct_mood(
    log_id: int,
    body: MoodCorrection,
    request: Request,
    _admin: str = Depends(require_admin),
) -> dict:
    """Korrigiert eine Mood-Erkennung (Mike kann falsche Einträge korrigieren)."""
    svc = getattr(request.app.state, "mood", None)
    if svc is None:
        raise HTTPException(status_code=503, detail="mood_service_unavailable")
    ok = svc.correct_mood(log_id, body.correct_mood)
    if not ok:
        raise HTTPException(status_code=404, detail="log_entry_not_found")
    return {"log_id": log_id, "corrected_mood": body.correct_mood}

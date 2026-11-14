"""consistency.py — Nutzungs-Tracking-Endpoints."""
from __future__ import annotations

from fastapi import APIRouter, Depends, Request

from app.core.auth import require_admin

router = APIRouter(prefix="/consistency", tags=["consistency"])


@router.get("/summary")
async def consistency_summary(
    request: Request,
    _admin: str = Depends(require_admin),
) -> dict:
    """Streak, total_30d, today_count — für Android Idle-Screen und Briefing."""
    svc = getattr(request.app.state, "consistency", None)
    if svc is None:
        return {"streak": 0, "total_30d": 0, "today_count": 0}
    return svc.get_summary()

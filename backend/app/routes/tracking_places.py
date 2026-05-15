"""tracking_places.py — GPS-Gewohnheitsanalyse Endpoints."""
from __future__ import annotations

from fastapi import APIRouter, Depends, Request

from app.core.auth import require_admin

router = APIRouter(prefix="/tracking/places", tags=["tracking"])


@router.get("")
async def get_frequent_places(
    request: Request,
    days: int = 30,
    min_visits: int = 3,
    _admin: str = Depends(require_admin),
) -> list[dict]:
    """Häufig besuchte Orte mit Geocoding (async, dauert ~2-5 Sek wegen Nominatim)."""
    svc = getattr(request.app.state, "places", None)
    if svc is None:
        return []
    return await svc.analyze_places(days=min(days, 90), min_visits=min_visits)


@router.get("/quick")
async def get_frequent_places_quick(
    request: Request,
    days: int = 30,
    _admin: str = Depends(require_admin),
) -> list[dict]:
    """Häufige Orte ohne Geocoding — nur Koordinaten + Besuchszahl (schnell)."""
    svc = getattr(request.app.state, "places", None)
    if svc is None:
        return []
    return svc.get_frequent_places_sync(days=min(days, 90))

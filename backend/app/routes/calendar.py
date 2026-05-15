"""calendar.py — Google Calendar Endpunkte (DONNA-107).

GET /calendar/events?days=7 — gibt anstehende Kalender-Events zurück.

Auth: Bearer-Token (require_admin).
DSGVO: Kein Logging von Event-Details — nur HTTP-Status und Anzahl.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, Query, Request

from app.core.auth import require_admin
from app.core.logger import get_logger

log = get_logger("route.calendar")

router = APIRouter(prefix="/calendar", tags=["calendar"])


@router.get("/events")
async def get_calendar_events(
    request: Request,
    days: int = Query(default=7, ge=1, le=30, description="Anzahl Tage voraus"),
    _admin: str = Depends(require_admin),
) -> dict:
    """Gibt anstehende Kalender-Events für die nächsten N Tage zurück.

    DSGVO: Kein Logging von Event-Details (Termine/Personen) — nur HTTP-Status.
    Kalender-PII nur In-Memory, keine LTM-Persistenz.  # Art. 5(2) DSGVO
    """
    calendar_svc = getattr(request.app.state, "calendar", None)

    if calendar_svc is None or not calendar_svc.ready():
        # DSGVO Auflage 3: Kein Logging von Kalender-Rohdaten — nur Status
        log.info("calendar_service_unavailable")
        return {"events": [], "count": 0, "available": False}

    events = calendar_svc.get_upcoming_events(days=days)

    # DSGVO Auflage 3: Kein Logging der Event-Details — nur Anzahl
    log.info("calendar_events_response", count=len(events))

    return {"events": events, "count": len(events), "available": True}

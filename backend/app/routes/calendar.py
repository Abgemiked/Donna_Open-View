"""calendar.py — Google Calendar Endpunkte (DONNA-107).

GET /calendar/events?days=7 — gibt anstehende Kalender-Events zurück.
GET /calendar/auth/status — Zustand des Google-Zugangs (ohne Token/Secret/Client-ID).
POST /calendar/auth/reload — liest die Token-Ablage neu ein (ohne Neustart; #97).

Auth: Bearer-Token (require_admin).
DSGVO: Kein Logging von Event-Details — nur HTTP-Status und Anzahl.
"""
from __future__ import annotations

import asyncio

from fastapi import APIRouter, Depends, HTTPException, Query, Request

from app.config import Settings, get_settings
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

    _err = getattr(events, "error", None)
    if isinstance(_err, str):
        return {"events": [], "count": 0, "available": False, "error": _err}

    return {"events": events, "count": len(events), "available": True}


@router.get("/auth/status")
async def get_calendar_auth_status(
    request: Request,
    _admin: str = Depends(require_admin),
) -> dict:
    """Zustand des Google-Zugangs: aktiv, Quelle (store/env/none), Auth-Fehler, Zeitstempel der Ablage.

    Enthält nie Token, Secret oder Client-ID.
    """
    svc = getattr(request.app.state, "calendar", None)
    if svc is None:
        return {"enabled": False, "source": "none", "auth_failed": False, "updated_at": None}
    return svc.auth_status()


@router.post("/auth/reload")
async def reload_calendar_auth(
    request: Request,
    _admin: str = Depends(require_admin),
    settings: Settings = Depends(get_settings),
) -> dict:
    """Liest die Token-Ablage neu ein und setzt den Auth-Fehler zurück — ohne Neustart.

    Nimmt keinen Token entgegen und gibt keinen zurück. Ist die Ablage aus oder der Eintrag ungültig,
    bleibt der bisherige Zustand unverändert (409 mit Grund); ein funktionierender Zugang wird nie abgeschaltet.
    """
    svc = getattr(request.app.state, "calendar", None)
    if svc is None:
        raise HTTPException(status_code=503, detail="Kalender-Dienst nicht verfügbar.")
    # Datei-IO nicht im Event-Loop
    outcome = await asyncio.to_thread(svc.reload_from_store, settings)
    if outcome != "reloaded":
        log.info("calendar_auth_reload_unchanged", reason=outcome)
        raise HTTPException(
            status_code=409,
            detail={"reloaded": False, "reason": outcome, **svc.auth_status()},
        )
    log.info("calendar_auth_reloaded")
    return svc.auth_status()

"""tracking.py — Activity & GPS Tracking Endpoints."""
from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel

from app.core.auth import require_admin

router = APIRouter(prefix="/tracking", tags=["tracking"])


class LocationPayload(BaseModel):
    lat: float
    lon: float
    accuracy: float | None = None
    speed: float | None = None
    altitude: float | None = None


class AppUsage(BaseModel):
    package: str
    usage_ms: int


class ActivityPayload(BaseModel):
    apps: list[AppUsage]
    window_min: int = 30


class ScreenPayload(BaseModel):
    package: str
    app: str
    content: str
    event_type: int | None = None


class HeartbeatPayload(BaseModel):
    """PC/Android-Heartbeat — DONNA-94/95."""
    device: str  # "pc" | "android"
    idle_sec: int = 0
    active_app: str | None = None
    donna_focused: bool = False
    screen_on: bool | None = None  # Android: PowerManager.isInteractive()


class TrackingPush(BaseModel):
    type: str  # "location" | "activity" | "screen" | "pc_heartbeat" | "screen_locked" | "pc_resume" | "activity_recognition" | "location_context"
    location: LocationPayload | None = None
    activity: ActivityPayload | None = None
    screen: ScreenPayload | None = None
    heartbeat: HeartbeatPayload | None = None  # DONNA-94/95
    # DONNA-119: Activity Recognition — physische Aktivität des Users
    activity_type: str | None = None  # "WALKING" | "RUNNING" | "IN_VEHICLE" | "ON_BICYCLE" | "STILL" | "UNKNOWN"
    # DONNA-121: Geofencing — semantischer Standort-Kontext
    location_context: str | None = None  # "home" | "work" | "transit"
    # DONNA-123: MediaSession — aktuell gespieltes Medium
    media_playing: dict | None = None  # {"app": "Spotify", "title": "...", "artist": "...", "playing": true}


@router.post("/push")
async def push_tracking(
    body: TrackingPush,
    request: Request,
    _admin: str = Depends(require_admin),
) -> dict:
    """Empfängt Tracking-Daten vom Android-Client."""
    svc = getattr(request.app.state, "tracking", None)
    if svc is None:
        return {"ok": False, "error": "tracking_service_unavailable"}

    if body.type == "location" and body.location:
        svc.push("location", body.location.model_dump())
    elif body.type == "activity" and body.activity:
        svc.push("activity", body.activity.model_dump())
    elif body.type == "screen" and body.screen:
        svc.push("screen", body.screen.model_dump())
    elif body.type == "pc_heartbeat" and body.heartbeat:
        svc.push("pc_heartbeat", body.heartbeat.model_dump())
    elif body.type in ("screen_locked", "pc_resume"):
        # Keine Nutzdaten — nur Zeitstempel + Typ
        svc.push(body.type, {"device": "pc"})
    elif body.type == "activity_recognition" and body.activity_type:
        # DONNA-119: Physische Aktivität des Users (WALKING, RUNNING, IN_VEHICLE, etc.)
        svc.push("activity_recognition", {"activity_type": body.activity_type})
    elif body.type == "location_context" and body.location_context:
        # DONNA-121: Geofencing — semantischer Standort-Kontext (home / work / transit)
        svc.push("location_context", {"location_context": body.location_context})
    elif body.type == "media_playing":
        # DONNA-123: MediaSession — aktuell gespieltes Medium (media_playing darf None sein = gestoppt)
        svc.push("media_playing", {"media_playing": body.media_playing})
    else:
        return {"ok": False, "error": "unknown_type_or_missing_payload"}

    return {"ok": True}


@router.get("/recent")
async def get_recent(
    request: Request,
    hours: int = 24,
    _admin: str = Depends(require_admin),
) -> list[dict]:
    """Alle Tracking-Events der letzten N Stunden."""
    svc = getattr(request.app.state, "tracking", None)
    if svc is None:
        return []
    return svc.get_recent(hours=min(hours, 168))


@router.get("/summary")
async def get_summary(
    request: Request,
    hours: int = 8,
    _admin: str = Depends(require_admin),
) -> dict:
    """Kompakte Zusammenfassung (Top-Apps + letzter Standort) für Donna."""
    svc = getattr(request.app.state, "tracking", None)
    if svc is None:
        return {}
    return svc.get_summary(hours=min(hours, 72))


@router.post("/screen")
async def push_screen(
    body: TrackingPush,
    request: Request,
    _admin: str = Depends(require_admin),
) -> dict:
    """Screen-Content-Endpunkt vom AccessibilityService.
    Akzeptiert {type:'screen', screen:{...}} von DonnaAccessibilityService.kt."""
    svc = getattr(request.app.state, "tracking", None)
    if svc is None:
        return {"ok": False}
    if body.screen:
        svc.push("screen", body.screen.model_dump())
    return {"ok": True}


@router.get("/screen/context")
async def get_screen_context(
    request: Request,
    hours: int = 4,
    _admin: str = Depends(require_admin),
) -> dict:
    """Screen-Kontext der letzten N Stunden — für Donna-Prompt-Injection."""
    svc = getattr(request.app.state, "tracking", None)
    if svc is None:
        return {}
    return svc.get_screen_context(hours=min(hours, 24))


@router.get("/location")
async def get_last_location(
    request: Request,
    _admin: str = Depends(require_admin),
) -> dict:
    """Letzter bekannter GPS-Standort."""
    svc = getattr(request.app.state, "tracking", None)
    if svc is None:
        return {}
    loc = svc.get_last_location()
    return loc or {}

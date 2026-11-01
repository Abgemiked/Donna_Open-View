"""health_data.py — DONNA-120: Health Connect Daten-Endpoint."""
from __future__ import annotations

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, Field

from app.core.auth import require_admin

router = APIRouter(prefix="/health", tags=["health_data"])


class HealthPushPayload(BaseModel):
    """Health-Daten vom Android Health Connect Client + Samsung Health Bridge."""
    # DONNA-120: Sleep, Steps, Herzrate — alle Felder optional (graceful degradation)
    sleep_hours: float | None = Field(default=None, description="Schlafdauer in Stunden (letzte 24h)")
    steps_today: int | None = Field(default=None, description="Schritte heute")
    resting_hr: int | None = Field(default=None, description="Ruhepuls (Minimum letzte 24h)")
    # DONNA-124: Samsung Health Bridge — zusätzliche Felder (nur auf Samsung-Geräten mit Samsung Health)
    stress_score: int | None = Field(default=None, description="Samsung Health Stress-Score (0–100)")
    spo2: int | None = Field(default=None, description="Sauerstoffsättigung SpO2 in % (Samsung Health)")
    sleep_stage: str | None = Field(default=None, description="Schlafphase (Samsung Health): awake|light|deep|rem")


@router.post("/push")
async def push_health_data(
    body: HealthPushPayload,
    request: Request,
    _admin: str = Depends(require_admin),
) -> dict:
    """Empfängt Health-Daten vom Android Health Connect Client.

    Speichert die Daten im Tracking-Service als User-Kontext-Event,
    sodass Donna beim Briefing/Chat Zugriff auf aktuelle Gesundheitsdaten hat.
    """
    svc = getattr(request.app.state, "tracking", None)
    if svc is None:
        return {"ok": False, "error": "tracking_service_unavailable"}

    # Health-Daten als Tracking-Event speichern (wie andere Kontext-Daten)
    health_data: dict = {}
    if body.sleep_hours is not None:
        health_data["sleep_hours"] = body.sleep_hours
    if body.steps_today is not None:
        health_data["steps_today"] = body.steps_today
    if body.resting_hr is not None:
        health_data["resting_hr"] = body.resting_hr
    # DONNA-124: Samsung Health Felder (optional — nur auf Samsung-Geräten)
    if body.stress_score is not None:
        health_data["stress_score"] = body.stress_score
    if body.spo2 is not None:
        health_data["spo2"] = body.spo2
    if body.sleep_stage is not None:
        health_data["sleep_stage"] = body.sleep_stage

    if not health_data:
        return {"ok": False, "error": "no_health_data_provided"}

    svc.push("health_connect", health_data)

    return {"ok": True, "stored": list(health_data.keys())}


@router.get("/latest")
async def get_latest_health(
    request: Request,
    _admin: str = Depends(require_admin),
) -> dict:
    """Letzte Health-Daten für Donna-Kontext (Briefing, Chat-Prompts)."""
    svc = getattr(request.app.state, "tracking", None)
    if svc is None:
        return {}

    # Letztes health_connect-Event der letzten 48h holen
    recent = svc.get_recent(hours=48)
    health_events = [e for e in recent if e.get("type") == "health_connect"]
    if not health_events:
        return {}

    # Neuestes Event zurückgeben
    return health_events[-1].get("data", {})

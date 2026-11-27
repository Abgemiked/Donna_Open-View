"""health_data.py — DONNA-120: Health Connect Daten-Endpoint.

#182 (additiv): Schlafsitzung (sleep_start/sleep_end), "null statt 0", Duplikate werden ignoriert.
Vertrag fuer den Android-Client: docs/HEALTH_PUSH_CONTRACT.md
"""
from __future__ import annotations

from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, Field, field_validator, model_validator

from app.core.auth import require_admin

router = APIRouter(prefix="/health", tags=["health_data"])

_MAX_SLEEP_H = 24.0


def _parse_aware(value: str | None) -> datetime | None:
    if value is None:
        return None
    dt = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    if dt.tzinfo is None:
        raise ValueError("Zeitzone erforderlich (ISO-8601 mit Offset oder Z)")
    return dt.astimezone(timezone.utc)


class HealthPushPayload(BaseModel):
    """Health-Daten vom Android Health Connect Client + Samsung Health Bridge."""
    # DONNA-120: Sleep, Steps, Herzrate — alle Felder optional (graceful degradation)
    sleep_hours: float | None = Field(default=None, description="Schlafdauer in Stunden. null = keine Daten (0 wird ebenfalls als 'keine Daten' behandelt)")
    # #182: Schlafsitzung aus Health Connect (SleepSessionRecord)
    sleep_start: str | None = Field(default=None, description="Beginn der Schlafsitzung, ISO-8601 MIT Zeitzone")
    sleep_end: str | None = Field(default=None, description="Ende der Schlafsitzung, ISO-8601 MIT Zeitzone")
    steps_today: int | None = Field(default=None, description="Schritte heute")
    resting_hr: int | None = Field(default=None, description="Ruhepuls (Minimum letzte 24h); null/0 = keine Daten")
    # DONNA-124: Samsung Health Bridge — zusätzliche Felder (nur auf Samsung-Geräten mit Samsung Health)
    stress_score: int | None = Field(default=None, description="Samsung Health Stress-Score (0–100)")
    spo2: int | None = Field(default=None, description="Sauerstoffsättigung SpO2 in % (Samsung Health)")
    sleep_stage: str | None = Field(default=None, description="Schlafphase (Samsung Health): awake|light|deep|rem")

    @field_validator("sleep_start", "sleep_end")
    @classmethod
    def _aware_iso(cls, v: str | None) -> str | None:
        return _parse_aware(v).isoformat() if v is not None else None

    @model_validator(mode="after")
    def _session_consistent(self):
        if (self.sleep_start is None) != (self.sleep_end is None):
            raise ValueError("sleep_start und sleep_end nur zusammen")
        if self.sleep_start is not None:
            span = (_parse_aware(self.sleep_end) - _parse_aware(self.sleep_start)).total_seconds() / 3600.0
            if not (0 < span <= _MAX_SLEEP_H):
                raise ValueError("Schlafsitzung unplausibel (Ende nach Start, hoechstens 24 h)")
        return self


def _clean(body: HealthPushPayload) -> dict:
    """Nur echte Werte: 0/0.0 fuer sleep_hours/resting_hr und unplausible Dauer = keine Daten."""
    d: dict = {}
    if body.sleep_start is not None:
        d["sleep_start"], d["sleep_end"] = body.sleep_start, body.sleep_end
        d["sleep_hours"] = round((_parse_aware(body.sleep_end) - _parse_aware(body.sleep_start)).total_seconds() / 3600.0, 2)
    elif body.sleep_hours is not None and 0 < body.sleep_hours <= _MAX_SLEEP_H:
        d["sleep_hours"] = body.sleep_hours
    if body.steps_today is not None and body.steps_today >= 0:
        d["steps_today"] = body.steps_today
    if body.resting_hr is not None and body.resting_hr > 0:
        d["resting_hr"] = body.resting_hr
    # DONNA-124: Samsung Health Felder (optional — nur auf Samsung-Geräten)
    if body.stress_score is not None:
        d["stress_score"] = body.stress_score
    if body.spo2 is not None:
        d["spo2"] = body.spo2
    if body.sleep_stage is not None:
        d["sleep_stage"] = body.sleep_stage
    return d


def _strip(ev: dict) -> dict:
    return {k: v for k, v in ev.items() if k not in ("ts", "type")}


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

    health_data = _clean(body)
    if not health_data:
        return {"ok": False, "error": "no_health_data_provided"}

    # #182: identisch zum zuletzt gespeicherten Event -> ignorieren (Client sendet bisher doppelt)
    last = svc.get_events(("health_connect",), hours=48, limit=1)
    if last and _strip(last[0]) == health_data:
        return {"ok": True, "duplicate": True, "stored": []}

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

    # Pro Feld der juengste echte Wert der letzten 48 h (Events sind flach gespeichert, neueste zuerst;
    # ein Teil-Push darf andere Felder nicht "loeschen"). "updated_at" = Zeitpunkt des neuesten Events.
    events = svc.get_events(("health_connect",), hours=48, limit=50)
    if not events:
        return {}
    merged: dict = {}
    for ev in events:
        for k, v in _strip(ev).items():
            merged.setdefault(k, v)
    merged["updated_at"] = events[0]["ts"]
    return merged

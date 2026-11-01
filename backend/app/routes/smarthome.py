"""smarthome.py — DONNA-125: SmartThings Integration.

Endpunkte:
    GET  /smarthome/devices         — Liste aller Geräte + Status
    POST /smarthome/command         — Gerät steuern
    GET  /smarthome/scenes          — Szenen auflisten
    POST /smarthome/scene/{scene_id} — Szene aktivieren

Auth: Bearer-Token (require_admin).
Token: SMARTTHINGS_TOKEN aus Umgebungsvariable.
Rate Limiting: max 20 Commands/Min (In-Memory-Counter).
Sicherheit: Türschlösser (capability "lock") → nur Status lesen, kein unlock.
"""
from __future__ import annotations

import os
import time
from collections import deque
from typing import Any

import httpx
from fastapi import APIRouter, Depends, HTTPException, Path, Request, status
from pydantic import BaseModel, Field

from app.core.auth import require_admin
from app.core.logger import get_logger

log = get_logger("route.smarthome")

router = APIRouter(prefix="/smarthome", tags=["smarthome"])

# SmartThings API Base URL
_ST_BASE = "https://api.smartthings.com/v1"

# Rate Limiting: max 20 Commands/Min (rolling window)
_RATE_LIMIT_MAX = 20
_RATE_LIMIT_WINDOW_SEC = 60.0

# In-Memory-Counter (pro Prozess — reicht für Single-User-System)
_command_timestamps: deque[float] = deque()

# Sicherheits-Blacklist: diese Kommandos auf lock-Geräten sind verboten
_FORBIDDEN_LOCK_COMMANDS = {"unlock"}


# ── Pydantic Models ───────────────────────────────────────────────────────────


class SmartThingsCommand(BaseModel):
    deviceId: str = Field(..., description="SmartThings Geräte-ID (UUID)")
    capability: str = Field(..., description="z.B. 'switch', 'lock', 'thermostat'")
    command: str = Field(..., description="z.B. 'on', 'off', 'lock'")
    arguments: list[Any] = Field(default_factory=list, description="Optionale Command-Argumente")


# ── Helpers ───────────────────────────────────────────────────────────────────


def _get_st_token() -> str:
    """Liest SMARTTHINGS_TOKEN aus der Umgebung. Raises 503 wenn nicht gesetzt."""
    token = os.environ.get("SMARTTHINGS_TOKEN", "")
    if not token:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="SMARTTHINGS_TOKEN nicht konfiguriert",
        )
    return token


def _check_rate_limit() -> None:
    """Prüft 20-Commands/Min Rolling-Window. Raises 429 bei Überschreitung."""
    now = time.monotonic()
    # Veraltete Einträge entfernen
    while _command_timestamps and (now - _command_timestamps[0]) > _RATE_LIMIT_WINDOW_SEC:
        _command_timestamps.popleft()

    if len(_command_timestamps) >= _RATE_LIMIT_MAX:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=f"Rate Limit überschritten: max {_RATE_LIMIT_MAX} Commands/Min",
        )

    _command_timestamps.append(now)


def _st_headers(token: str) -> dict[str, str]:
    return {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
    }


# ── Endpoints ─────────────────────────────────────────────────────────────────


@router.get("/devices")
async def list_devices(
    _admin: str = Depends(require_admin),
) -> dict:
    """Liste aller SmartThings-Geräte mit aktuellem Status.

    Gibt eine kompakte Liste mit deviceId, label, type und online-Status zurück.
    """
    token = _get_st_token()

    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.get(
                f"{_ST_BASE}/devices",
                headers=_st_headers(token),
            )
            resp.raise_for_status()
            data = resp.json()

        devices = data.get("items", [])
        log.info("smarthome_devices_listed", count=len(devices))

        # Kompakte Darstellung: nur relevante Felder
        result = [
            {
                "deviceId": d.get("deviceId"),
                "label": d.get("label"),
                "name": d.get("name"),
                "type": d.get("type"),
                "components": [c.get("id") for c in d.get("components", [])],
            }
            for d in devices
        ]
        return {"devices": result, "count": len(result)}

    except httpx.HTTPStatusError as exc:
        log.error("smarthome_devices_failed", status=exc.response.status_code)
        raise HTTPException(
            status_code=exc.response.status_code,
            detail=f"SmartThings API Fehler: {exc.response.status_code}",
        ) from exc
    except httpx.RequestError as exc:
        log.error("smarthome_devices_request_error", error=str(exc))
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="SmartThings API nicht erreichbar",
        ) from exc


@router.post("/command")
async def send_command(
    body: SmartThingsCommand,
    _admin: str = Depends(require_admin),
) -> dict:
    """Sendet ein Command an ein SmartThings-Gerät.

    Sicherheitsregel: Türschlösser (capability 'lock') → kein 'unlock'-Command.
    Rate Limiting: max 20 Commands/Min (In-Memory-Counter).
    """
    token = _get_st_token()

    # Sicherheits-Check: lock-Capability → unlock verboten
    if body.capability.lower() == "lock" and body.command.lower() in _FORBIDDEN_LOCK_COMMANDS:
        log.warning(
            "smarthome_command_forbidden",
            device=body.deviceId,
            capability=body.capability,
            command=body.command,
        )
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Sicherheitsregel: Schlösser können über die API nicht entsperrt werden.",
        )

    # Rate-Limit prüfen
    _check_rate_limit()

    # Command-Payload für SmartThings API
    command_payload = {
        "commands": [
            {
                "component": "main",
                "capability": body.capability,
                "command": body.command,
                "arguments": body.arguments,
            }
        ]
    }

    log.info(
        "smarthome_command",
        device=body.deviceId,
        capability=body.capability,
        command=body.command,
    )

    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.post(
                f"{_ST_BASE}/devices/{body.deviceId}/commands",
                headers=_st_headers(token),
                json=command_payload,
            )
            resp.raise_for_status()
            data = resp.json()

        return {"ok": True, "result": data}

    except httpx.HTTPStatusError as exc:
        log.error(
            "smarthome_command_failed",
            device=body.deviceId,
            status=exc.response.status_code,
        )
        raise HTTPException(
            status_code=exc.response.status_code,
            detail=f"SmartThings Command fehlgeschlagen: {exc.response.status_code}",
        ) from exc
    except httpx.RequestError as exc:
        log.error("smarthome_command_request_error", error=str(exc))
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="SmartThings API nicht erreichbar",
        ) from exc


@router.get("/scenes")
async def list_scenes(
    _admin: str = Depends(require_admin),
) -> dict:
    """Liste aller SmartThings-Szenen."""
    token = _get_st_token()

    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.get(
                f"{_ST_BASE}/scenes",
                headers=_st_headers(token),
            )
            resp.raise_for_status()
            data = resp.json()

        scenes = data.get("items", [])
        log.info("smarthome_scenes_listed", count=len(scenes))

        result = [
            {
                "sceneId": s.get("sceneId"),
                "sceneName": s.get("sceneName"),
                "locationId": s.get("locationId"),
            }
            for s in scenes
        ]
        return {"scenes": result, "count": len(result)}

    except httpx.HTTPStatusError as exc:
        log.error("smarthome_scenes_failed", status=exc.response.status_code)
        raise HTTPException(
            status_code=exc.response.status_code,
            detail=f"SmartThings API Fehler: {exc.response.status_code}",
        ) from exc
    except httpx.RequestError as exc:
        log.error("smarthome_scenes_request_error", error=str(exc))
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="SmartThings API nicht erreichbar",
        ) from exc


@router.post("/scene/{scene_id}")
async def activate_scene(
    scene_id: str = Path(..., description="SmartThings Szenen-ID"),
    _admin: str = Depends(require_admin),
) -> dict:
    """Aktiviert eine SmartThings-Szene.

    Rate Limiting: zählt zum 20-Commands/Min-Kontingent.
    """
    token = _get_st_token()

    # Rate-Limit prüfen (Szenen-Aktivierung zählt als Command)
    _check_rate_limit()

    log.info("smarthome_scene_activate", scene_id=scene_id)

    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.post(
                f"{_ST_BASE}/scenes/{scene_id}/execute",
                headers=_st_headers(token),
            )
            resp.raise_for_status()
            data = resp.json()

        return {"ok": True, "scene_id": scene_id, "result": data}

    except httpx.HTTPStatusError as exc:
        log.error(
            "smarthome_scene_failed",
            scene_id=scene_id,
            status=exc.response.status_code,
        )
        raise HTTPException(
            status_code=exc.response.status_code,
            detail=f"Szenen-Aktivierung fehlgeschlagen: {exc.response.status_code}",
        ) from exc
    except httpx.RequestError as exc:
        log.error("smarthome_scene_request_error", error=str(exc))
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="SmartThings API nicht erreichbar",
        ) from exc

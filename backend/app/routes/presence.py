"""presence.py — GET /presence: Liest aktuellen Präsenz-Status.

Gibt den Inhalt der presence.md als JSON zurück (inferiert aus Tracking-Events).
Kein Auth required — intern genutzter Endpoint.
"""
from __future__ import annotations

from pathlib import Path

from fastapi import APIRouter, HTTPException, Request

router = APIRouter(tags=["presence"])


@router.get("/presence")
async def get_presence(request: Request) -> dict:
    """Gibt den aktuellen Präsenz-Status von Mike zurück.

    Liest den Status direkt aus dem PresenceService (live, nicht aus Datei),
    damit der Endpoint auch dann funktioniert wenn presence.md noch nicht
    geschrieben wurde.

    Returns:
        {
            "active_device":      "pc" | "android" | "none",
            "idle_state":         "active" | "away" | "sleeping",
            "estimated_activity": "working" | "browsing" | "gaming" | "idle",
            "pc_active_app":      str | None,
            "android_screen_on":  bool | None,
            "presence_md_exists": bool,
            "last_update":        str | None,  # ISO-Timestamp der letzten presence.md
        }
    """
    presence_svc = getattr(request.app.state, "presence", None)
    if presence_svc is None:
        raise HTTPException(status_code=503, detail="PresenceService nicht initialisiert")

    ctx = presence_svc.get_presence_context()

    # Zusätzlich: Timestamp der letzten presence.md auslesen (falls vorhanden)
    last_update: str | None = None
    presence_md_exists = False
    try:
        vault_path = getattr(presence_svc, "_vault_path", None)
        if vault_path:
            presence_path = Path(vault_path) / "presence.md"
            if presence_path.exists():
                presence_md_exists = True
                import os
                mtime = os.path.getmtime(presence_path)
                from datetime import datetime, timezone
                last_update = datetime.fromtimestamp(mtime, tz=timezone.utc).strftime(
                    "%Y-%m-%dT%H:%M:%SZ"
                )
    except Exception:  # noqa: BLE001
        pass

    return {
        "active_device": ctx.get("active_device", "none"),
        "idle_state": ctx.get("idle_state", "active"),
        "estimated_activity": ctx.get("estimated_activity", "idle"),
        "pc_active_app": ctx.get("pc_active_app"),
        "android_screen_on": ctx.get("android_screen_on"),
        "presence_md_exists": presence_md_exists,
        "last_update": last_update,
    }

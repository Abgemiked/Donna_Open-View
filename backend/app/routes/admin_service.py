"""DONNA-199: Admin Service Toggle-Endpoints.

Routen:
  GET  /admin/service/status          → Service-Status (uptime, twitch-flags)
  POST /admin/service/twitch/enable   → Twitch-Service aktivieren
  POST /admin/service/twitch/disable  → Twitch-Service deaktivieren

Alle Routen: require_admin (Bearer ADMIN_TOKEN).
State ist in-memory — kein Persistieren über Restart hinaus.
"""
from __future__ import annotations

import time

from fastapi import APIRouter, Depends

from app.core.auth import require_admin
from app.core.logger import get_logger
import app.core.service_state as service_state
import app.jobs.stream_live_watcher as slw_module

log = get_logger("route.admin_service")

router = APIRouter(prefix="/admin", tags=["admin"])


@router.get("/service/status")
async def get_service_status(_: str = Depends(require_admin)) -> dict:
    """Gibt den aktuellen Status beider Services zurück.

    donna_assistentin.enabled ist immer True (solange die API läuft).
    donna_assistentin.uptime_seconds = Sekunden seit App-Start.
    donna_twitch.enabled = ob Twitch-Jobs im Scheduler laufen sollen.
    donna_twitch.proactive_enabled = ob der stream_live_watcher aktiv ist.
    """
    uptime = int(time.time() - service_state.APP_START_TIME)
    return {
        "donna_assistentin": {
            "enabled": True,
            "uptime_seconds": uptime,
        },
        "donna_twitch": {
            "enabled": service_state.DONNA_TWITCH_ENABLED,
            "proactive_enabled": slw_module.DONNA_TWITCH_PROACTIVE_ENABLED,
        },
    }


@router.post("/service/twitch/enable")
async def enable_twitch_service(_: str = Depends(require_admin)) -> dict:
    """Aktiviert den Twitch-Service.

    Setzt beide In-Memory-Flags auf True und versucht den APScheduler-Job
    stream_live_watcher zu resumieren. Falls der Job nicht existiert (z.B.
    weil DONNA_TWITCH_PROACTIVE beim Start False war), wird das geloggt
    aber kein Fehler geworfen — der Flag-Wechsel wirkt trotzdem auf
    zukünftige Job-Registrierungen.
    """
    service_state.DONNA_TWITCH_ENABLED = True
    slw_module.DONNA_TWITCH_PROACTIVE_ENABLED = True

    if service_state.scheduler is not None:
        try:
            service_state.scheduler.resume_job("stream_live_watcher")
            log.info("twitch_job_resumed", job_id="stream_live_watcher")
        except Exception as exc:  # noqa: BLE001
            log.warning(
                "twitch_job_resume_failed",
                job_id="stream_live_watcher",
                error=str(exc),
            )
    else:
        log.warning("twitch_enable_no_scheduler")

    log.info("twitch_service_enabled")
    return {"status": "twitch_enabled"}


@router.post("/service/twitch/disable")
async def disable_twitch_service(_: str = Depends(require_admin)) -> dict:
    """Deaktiviert den Twitch-Service.

    Setzt beide In-Memory-Flags auf False und pausiert den APScheduler-Job
    stream_live_watcher. Falls der Job nicht existiert, wird das geloggt
    aber kein Fehler geworfen.
    """
    service_state.DONNA_TWITCH_ENABLED = False
    slw_module.DONNA_TWITCH_PROACTIVE_ENABLED = False

    if service_state.scheduler is not None:
        try:
            service_state.scheduler.pause_job("stream_live_watcher")
            log.info("twitch_job_paused", job_id="stream_live_watcher")
        except Exception as exc:  # noqa: BLE001
            log.warning(
                "twitch_job_pause_failed",
                job_id="stream_live_watcher",
                error=str(exc),
            )
    else:
        log.warning("twitch_disable_no_scheduler")

    log.info("twitch_service_disabled")
    return {"status": "twitch_disabled"}

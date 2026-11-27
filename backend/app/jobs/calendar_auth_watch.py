"""Kalender-Auth-Waechter (#97) — warnt per Push, wenn der Google-Zugang abgelaufen ist.

Prueft periodisch per get_upcoming_events (im Thread, nicht im Event-Loop) und
sendet bei error == "auth" hoechstens einmal je Zeitfenster einen Push.
Netz-/API-Fehler ("fetch") loesen keine Warnung aus. Keine Token-/Exception-Texte
in Logs oder Push.
"""
from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass
from typing import Any, Awaitable, Callable

from app.core.logger import get_logger

log = get_logger("calendar_auth_watch")

# Gestaffelte Wiederholung: erster Push sofort, zweiter fruehestens nach 24 h,
# danach nur noch alle 72 h (nicht taeglich ohne Ende).
SHORT_WINDOW_SECONDS = 24 * 60 * 60  # Abstand nach dem 1. Push
LONG_WINDOW_SECONDS = 72 * 60 * 60  # Abstand ab dem 2. Push

PUSH_TITLE = "Donna: Google-Kalender getrennt"
PUSH_TEXT = (
    "Der Google-Kalender ist nicht mehr verbunden, Termine kann ich gerade nicht abrufen. "
    "Bitte am PC neu verbinden: reauth_google_calendar.py ausführen, die erzeugte Datei in die "
    "Token-Ablage des Servers legen und POST /calendar/auth/reload aufrufen "
    "(oder wie bisher den Token in die Server-.env eintragen und den Dienst api neu starten)."
)


@dataclass
class CalendarAuthWatchState:
    last_sent_at: float | None = None
    sent_count: int = 0


async def check_calendar_auth(
    calendar_svc: Any,
    push_fn: Callable[[str, str, int], Awaitable[bool]],
    state: CalendarAuthWatchState,
    *,
    now_fn: Callable[[], float] = time.time,
) -> dict:
    """Ein Pruflauf. Wirft nie. Rueckgabe: {"checked", "error", "pushed"}."""
    result: dict = {"checked": False, "error": None, "pushed": False}
    try:
        if not calendar_svc.ready():
            return result
        events = await asyncio.to_thread(calendar_svc.get_upcoming_events, 1)
    except Exception as exc:  # noqa: BLE001
        log.warning("calendar_auth_watch_error", error_type=type(exc).__name__)
        return result

    result["checked"] = True
    err = getattr(events, "error", None)
    err = err if isinstance(err, str) else None
    result["error"] = err

    if err is None:
        state.sent_count = 0
        state.last_sent_at = None  # Zugang ok -> naechster Auth-Fehler warnt sofort
        return result
    if err != "auth":
        return result

    now = now_fn()
    window = SHORT_WINDOW_SECONDS if state.sent_count <= 1 else LONG_WINDOW_SECONDS
    if state.last_sent_at is not None and (now - state.last_sent_at) < window:
        return result

    try:
        ok = bool(await push_fn(PUSH_TITLE, PUSH_TEXT, 4 if state.sent_count == 0 else 3))
    except Exception as exc:  # noqa: BLE001
        log.warning("calendar_auth_watch_push_failed", error_type=type(exc).__name__)
        return result
    if not ok:
        log.warning("calendar_auth_watch_push_failed", error_type="push_returned_false")
        return result

    state.last_sent_at = now
    state.sent_count += 1
    result["pushed"] = True
    log.info("calendar_auth_watch_pushed")
    return result

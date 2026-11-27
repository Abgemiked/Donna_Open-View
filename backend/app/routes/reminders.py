"""reminders.py — Rueckmeldungen der App zu Erinnerungen (#153).

Die App meldet nach dem Popup 'Erledigt' oder 'Spaeter' (Minuten oder neue Zeit). Die Routen aendern nur
den Zustand der Erinnerung — sie senden nichts und fuehren nichts anderes aus.
Auth: Bearer-Token (wie die uebrigen App-Routen)."""
from __future__ import annotations

import asyncio
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Path, Request
from pydantic import BaseModel, ConfigDict, Field

from app.core.auth import require_admin
from app.services.reminder_service import ReminderError, ReminderService

router = APIRouter(prefix="/reminders", tags=["reminders"])

_MAX_ID = 2**31 - 1   # Grenzen: 422 statt 500 bei absurden Ids
_Id = Annotated[int, Path(ge=1, le=_MAX_ID)]


class SnoozeIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    minutes: int | None = Field(default=None, ge=1, le=7 * 24 * 60)
    when_iso: str | None = Field(default=None, max_length=64)


def _svc(request: Request) -> ReminderService:
    svc = getattr(request.app.state, "reminders", None)
    if svc is None:
        raise HTTPException(status_code=503, detail="reminder_service_unavailable")
    return svc


@router.get("")
async def list_reminders(request: Request, _admin: str = Depends(require_admin)) -> dict:
    """Offene Erinnerungen (wartend), nach Zeit sortiert."""
    items = await asyncio.to_thread(_svc(request).list_open)
    return {"reminders": [{"id": i["id"], "text": i["text"], "due_utc": i["due_utc"], "tz": i["tz"]} for i in items]}


@router.post("/{reminder_id}/done")
async def reminder_done(reminder_id: _Id, request: Request, _admin: str = Depends(require_admin)) -> dict:
    status = await asyncio.to_thread(_svc(request).mark_done, reminder_id)
    if status is None:
        raise HTTPException(status_code=404, detail="reminder_not_found")
    if status != "done":
        raise HTTPException(status_code=409, detail=f"reminder_{status}")
    return {"id": reminder_id, "status": "done"}


@router.post("/{reminder_id}/snooze")
async def reminder_snooze(
    reminder_id: _Id, body: SnoozeIn, request: Request, _admin: str = Depends(require_admin),
) -> dict:
    try:
        row = await asyncio.to_thread(
            lambda: _svc(request).snooze(reminder_id, minutes=body.minutes, when_iso=body.when_iso))
    except ReminderError as exc:
        raise HTTPException(status_code=400, detail=exc.code) from None
    if row is None:
        raise HTTPException(status_code=404, detail="reminder_not_found_or_not_snoozable")
    return {"id": reminder_id, "status": row["status"], "due_utc": row["due_utc"]}

"""notifications.py — DONNA-122: Notification-Zusammenfassung via Gemini."""
from __future__ import annotations

import asyncio
import functools

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field

from app.core.auth import require_admin
from app.core.logger import get_logger

log = get_logger("route.notifications")

router = APIRouter(prefix="/notifications", tags=["notifications"])

_MAX_NOTIFICATIONS = 50  # Sicherheits-Limit: max N Notifications pro Request


class NotificationItem(BaseModel):
    app: str = Field(..., description="App-Label, z.B. 'WhatsApp'")
    title: str | None = Field(default=None, description="Notification-Titel")
    text: str | None = Field(default=None, description="Notification-Text")


class NotificationSummaryRequest(BaseModel):
    notifications: list[NotificationItem] = Field(
        ...,
        max_length=_MAX_NOTIFICATIONS,
        description="Liste der letzten Notifications (max 50)",
    )


class NotificationSummaryResponse(BaseModel):
    summary: str
    count: int


@router.post("/summary", response_model=NotificationSummaryResponse)
async def summarize_notifications(
    body: NotificationSummaryRequest,
    request: Request,
    _admin: str = Depends(require_admin),
) -> NotificationSummaryResponse:
    """Empfängt eine Liste von Notifications und gibt eine Gemini-Zusammenfassung zurück.

    Donna nutzt diesen Endpunkt um dem User einen kompakten Überblick über
    verpasste Notifications zu geben (ohne jede einzeln vorlesen zu müssen).
    """
    if not body.notifications:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Keine Notifications übergeben",
        )

    gemini = getattr(request.app.state, "gemini", None)
    if gemini is None or not gemini.ready():
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Gemini nicht verfügbar",
        )

    # Notifications in kompakten Text-Block formatieren
    lines: list[str] = []
    for notif in body.notifications:
        parts = [f"[{notif.app}]"]
        if notif.title:
            parts.append(notif.title)
        if notif.text:
            parts.append(f"— {notif.text}")
        lines.append(" ".join(parts))

    notifications_text = "\n".join(lines)

    prompt = (
        "Du bist Donnas kompakter Notification-Assistent. "
        "Fasse die folgenden Benachrichtigungen in 2-4 kurzen deutschen Sätzen zusammen. "
        "Gruppiere ähnliche Themen. Ignoriere rein werbliche Inhalte. "
        "Betone wichtige Nachrichten (Personen, Termine, Alerts).\n\n"
        f"Benachrichtigungen:\n{notifications_text}\n\n"
        "Zusammenfassung:"
    )

    try:
        loop = asyncio.get_event_loop()
        summary_text = await loop.run_in_executor(
            None, functools.partial(gemini.generate, prompt)
        )
        summary_text = summary_text.strip()
    except Exception as exc:
        log.error("notification_summary_failed", error=str(exc))
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Zusammenfassung fehlgeschlagen",
        ) from exc

    log.info(
        "notification_summary_generated",
        count=len(body.notifications),
        summary_len=len(summary_text),
    )

    return NotificationSummaryResponse(
        summary=summary_text,
        count=len(body.notifications),
    )

"""notify.py — POST /notify: Sendet eine Push-Notification via ntfy.

DONNA-13: Backend-Endpunkt zum Versenden von Notifications an das Android-Gerät.
ntfy läuft intern als assistent-ntfy Container (http://assistent-ntfy:80).
Kein FCM/Firebase — vollständig Google-frei via ntfy WebSocket/SSE.

Auth: Bearer-Token (ADMIN_TOKEN) — kein anonymer Zugriff.
Kein Blocking: httpx async, Timeout 5s, Fehler werden geloggt aber nicht weiterpropagiert
damit Chat-Flow nicht blockiert wird (fire-and-forget für proaktive Nachrichten).
"""
from __future__ import annotations

import structlog
from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field

import httpx

from app.core.auth import require_admin
from app.config import get_settings

log = structlog.get_logger(__name__)

router = APIRouter(prefix="/notify", tags=["notifications"])


class NotifyRequest(BaseModel):
    title: str = Field(default="Donna", max_length=128)
    body: str = Field(..., min_length=1, max_length=4096)
    priority: int = Field(default=3, ge=1, le=5)
    tags: list[str] = Field(default_factory=list)


class NotifyResponse(BaseModel):
    sent: bool
    reason: str | None = None


# ntfy priority 1–5 map auf ntfy-Bezeichner
_PRIORITY_MAP = {1: "min", 2: "low", 3: "default", 4: "high", 5: "urgent"}


@router.post("", response_model=NotifyResponse)
async def send_notification(
    payload: NotifyRequest,
    request: Request,
    _admin: str = Depends(require_admin),
) -> NotifyResponse:
    """Sendet eine Push-Notification via ntfy an das Donna-Topic.

    - title: Benachrichtigungstitel (default: "Donna")
    - body: Benachrichtigungstext (Pflicht)
    - priority: 1 (min) bis 5 (urgent), default 3
    - tags: optionale ntfy-Tags (Emoji-Shortcodes wie ["robot", "tada"])

    Auth: Bearer ADMIN_TOKEN erforderlich.
    """
    settings = get_settings()
    ntfy_url = settings.ntfy_url.rstrip("/")
    topic = settings.ntfy_topic

    priority_str = _PRIORITY_MAP.get(payload.priority, "default")

    headers: dict[str, str] = {
        "Title": payload.title,
        "Priority": priority_str,
        "Content-Type": "text/plain; charset=utf-8",
    }
    if payload.tags:
        headers["Tags"] = ",".join(payload.tags)

    target_url = f"{ntfy_url}/{topic}"

    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            r = await client.post(
                target_url,
                content=payload.body.encode("utf-8"),
                headers=headers,
            )
            r.raise_for_status()
        log.info(
            "notify_sent",
            title=payload.title,
            body_len=len(payload.body),
            priority=payload.priority,
            topic=topic,
            http_status=r.status_code,
        )
        return NotifyResponse(sent=True)

    except httpx.HTTPStatusError as e:
        log.error(
            "notify_http_error",
            status_code=e.response.status_code,
            detail=e.response.text[:200],
        )
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"ntfy returned HTTP {e.response.status_code}",
        )
    except httpx.RequestError as e:
        log.error("notify_request_error", error=str(e), url=target_url)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=f"ntfy not reachable: {e}",
        )

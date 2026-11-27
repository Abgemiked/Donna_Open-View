"""STM router — session short-term memory management endpoints.

Endpoints:
  GET  /stm/{session_id}  — retrieve recent messages for a session
  DELETE /stm/{session_id} — clear all messages for a session

Auth: Bearer-token (reuses existing require_admin dependency).
"""
from __future__ import annotations

from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from pydantic import BaseModel

from app.core.auth import require_admin
from app.services.stm_service import STMService

router = APIRouter(prefix="/stm", tags=["stm"])


class MessageOut(BaseModel):
    role: str
    content: str
    # #155: Karten dieser Antwort (Aktion flach + card_id + card_state: open|done|rejected|expired)
    actions: list[dict] | None = None


class CardStateIn(BaseModel):
    state: Literal["done", "rejected"]


class CardStateOut(BaseModel):
    session_id: str
    card_id: str
    state: str


class ContextResponse(BaseModel):
    session_id: str
    messages: list[MessageOut]
    count: int


class DeleteResponse(BaseModel):
    session_id: str
    deleted: int


class SessionOut(BaseModel):
    session_id: str
    started_at: float
    message_count: int
    preview: str


def _get_stm(request: Request) -> STMService:
    return request.app.state.stm  # type: ignore[no-any-return]


@router.get("/sessions", response_model=list[SessionOut])
async def list_sessions(
    request: Request,
    _admin: str = Depends(require_admin),
) -> list[SessionOut]:
    """Return all sessions from the last 24 h, newest first."""
    stm: STMService = _get_stm(request)
    sessions = await stm.list_sessions(max_age_hours=24.0)
    return [SessionOut(**s) for s in sessions]


@router.get("/{session_id}", response_model=ContextResponse)
async def get_context(
    session_id: str,
    request: Request,
    limit: int = Query(default=10, ge=1, le=100),
    history: bool = Query(default=False, description="True = kein TTL-Filter (für Verlauf-Panel)"),
    _admin: str = Depends(require_admin),
) -> ContextResponse:
    """Return messages for a session.
    history=true: alle Nachrichten ohne TTL (Verlauf-Ansicht).
    history=false (default): nur aktuelle innerhalb TTL (Chat-Kontext).
    """
    stm: STMService = _get_stm(request)
    if history:
        messages = await stm.get_session_history(session_id, max_messages=limit)
    else:
        messages = await stm.get_context(session_id, max_messages=limit)
    return ContextResponse(
        session_id=session_id,
        messages=[MessageOut(**m) for m in messages],
        count=len(messages),
    )


@router.post("/{session_id}/cards/{card_id}", response_model=CardStateOut)
async def set_card_state(
    session_id: str,
    card_id: str,
    body: CardStateIn,
    request: Request,
    _admin: str = Depends(require_admin),
) -> CardStateOut:
    """#155: vermerkt, dass eine Karte erledigt (`done`) oder abgelehnt (`rejected`) wurde.
    Fuehrt NIE etwas aus; prueft Sitzung und Kennung. `expired` gibt es nur berechnet beim Ausliefern."""
    stm: STMService = _get_stm(request)
    result = await stm.set_card_state(session_id, card_id, body.state)
    if result == "not_found":
        raise HTTPException(status_code=404, detail="Karte nicht gefunden.")
    if result == "conflict":
        raise HTTPException(status_code=409, detail="Karte ist schon anders entschieden.")
    return CardStateOut(session_id=session_id, card_id=card_id, state=body.state)


@router.delete("/{session_id}", response_model=DeleteResponse)
async def delete_session(
    session_id: str,
    request: Request,
    _admin: str = Depends(require_admin),
) -> DeleteResponse:
    """Delete all messages for a session."""
    stm: STMService = _get_stm(request)
    deleted = await stm.delete_session(session_id)
    return DeleteResponse(session_id=session_id, deleted=deleted)

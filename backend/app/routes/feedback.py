"""feedback.py — 👍/👎 Feedback-Endpunkte."""
from __future__ import annotations

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, Field

from app.core.auth import require_admin
from app.core.logger import get_logger

log = get_logger("route.feedback")

router = APIRouter(prefix="/feedback", tags=["feedback"])


class FeedbackIn(BaseModel):
    session_id: str = Field(..., max_length=64)
    rating: str = Field(..., pattern="^(positive|negative)$")
    snippet: str | None = Field(default=None, max_length=200)
    context: str | None = Field(default=None, max_length=100)
    # DONNA-139: Erweiterte Felder für LTM-Integration
    message_id: str | None = Field(default=None, max_length=64)
    message_text: str | None = Field(default=None, max_length=2000)
    user_message: str | None = Field(default=None, max_length=1000)
    category: str | None = Field(default=None, max_length=50)


def _build_ltm_memory(
    rating: str,
    message_text: str | None,
    user_message: str | None,
    category: str | None,
) -> str | None:
    """Baut den LTM-Memory-Text aus Feedback-Daten.

    Gibt None zurück wenn message_text fehlt (kein sinnvolles Memory möglich).
    """
    if not message_text:
        return None

    # Kurze Stil-Beschreibung: erste 120 Zeichen der Antwort als Kontext
    snippet = message_text.strip()[:120]
    if len(message_text.strip()) > 120:
        snippet += "…"

    if rating == "positive":
        return f"Mike findet diese Art von Antwort gut: {snippet}"
    else:
        reason = f" — Grund: {category}" if category else ""
        return f"Mike findet diese Art von Antwort nicht hilfreich: {snippet}{reason}"


@router.post("")
async def post_feedback(
    body: FeedbackIn,
    request: Request,
    _admin: str = Depends(require_admin),
) -> dict:
    """Speichert 👍/👎 Feedback auf eine Donna-Antwort und schreibt in LTM."""
    svc = getattr(request.app.state, "feedback", None)
    if svc is None:
        return {"ok": False, "error": "feedback_service_unavailable"}

    # Kompatibilität: snippet aus message_text ableiten wenn nicht direkt übergeben
    effective_snippet = body.snippet or (body.message_text[:200] if body.message_text else None)

    new_id = svc.log_feedback(
        session_id=body.session_id,
        rating=body.rating,
        snippet=effective_snippet,
        context=body.context or body.category,
    )

    # DONNA-139: LTM-Memory schreiben — Donna lernt aus Bewertungen
    ltm = getattr(request.app.state, "ltm", None)
    if ltm is not None:
        memory_text = _build_ltm_memory(
            rating=body.rating,
            message_text=body.message_text,
            user_message=body.user_message,
            category=body.category or body.context,
        )
        if memory_text:
            try:
                ltm.store_memory(
                    session_id=body.session_id,
                    content=memory_text,
                    category="user_preference",
                )
                log.info(
                    "feedback_ltm_stored",
                    rating=body.rating,
                    message_id=body.message_id,
                    session_id=body.session_id,
                )
            except Exception as exc:  # noqa: BLE001
                log.warning("feedback_ltm_store_failed", error=str(exc))

    return {"ok": True, "id": new_id, "status": "saved"}


@router.get("/summary")
async def get_summary(
    request: Request,
    days: int = 30,
    _admin: str = Depends(require_admin),
) -> dict:
    """Feedback-Statistik der letzten N Tage (positiv/negativ Ratio)."""
    svc = getattr(request.app.state, "feedback", None)
    if svc is None:
        return {}
    return svc.get_summary(days=min(days, 365))


@router.get("/recent")
async def get_recent(
    request: Request,
    limit: int = 50,
    _admin: str = Depends(require_admin),
) -> list[dict]:
    """Letzte N Feedback-Einträge."""
    svc = getattr(request.app.state, "feedback", None)
    if svc is None:
        return []
    return svc.get_recent(limit=min(limit, 200))

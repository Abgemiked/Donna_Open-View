"""briefing.py — Morning-Briefing-Endpoint für Donna.

GET /briefing → kombiniert Streak, Mood-Trend und Nutzungsmuster
zu einer kurzen Zusammenfassung die Donna dem User vorliest.
"""
from __future__ import annotations

import structlog
from fastapi import APIRouter, Depends, Request

from app.core.auth import require_admin

log = structlog.get_logger(__name__)

router = APIRouter(prefix="/briefing", tags=["briefing"])


@router.get("")
async def get_briefing(
    request: Request,
    _admin: str = Depends(require_admin),
) -> dict:
    """Morgen-Briefing: Streak + Mood-Trend + Muster-Highlights."""
    app = request.app
    consistency = getattr(app.state, "consistency", None)
    mood_svc = getattr(app.state, "mood", None)
    pattern_svc = getattr(app.state, "pattern", None)

    # ── Consistency ────────────────────────────────────────────────────────
    summary: dict = {"streak": 0, "total_30d": 0, "today_count": 0}
    if consistency:
        try:
            summary = consistency.get_summary()
        except Exception:
            pass

    # ── Mood (letzte 24h) ──────────────────────────────────────────────────
    recent_mood: str | None = None
    mood_confidence: float = 0.0
    if mood_svc:
        try:
            history = mood_svc.get_mood_history(days=1)
            if history:
                # Häufigster Mood der letzten 24h (korrigierter Mood bevorzugt)
                moods = [h.get("corrected_mood") or h.get("mood") for h in history]
                from collections import Counter
                most_common = Counter(moods).most_common(1)
                if most_common:
                    recent_mood = most_common[0][0]
                    mood_confidence = round(
                        sum(h["confidence"] for h in history) / len(history), 2
                    )
        except Exception:
            pass

    # ── Pattern-Highlights ─────────────────────────────────────────────────
    peak_time: str | None = None
    peak_weekdays: list[str] = []
    if pattern_svc:
        try:
            patterns = pattern_svc.detect_patterns(days=30)
            peak_time = patterns.get("peak_time")
            peak_weekdays = patterns.get("peak_weekdays", [])
        except Exception:
            pass

    # ── Briefing-Text generieren ───────────────────────────────────────────
    lines: list[str] = []

    streak = summary["streak"]
    total_30d = summary["total_30d"]

    if streak >= 7:
        lines.append(f"Du nutzt mich seit {streak} Tagen in Folge — starke Streak!")
    elif streak >= 2:
        lines.append(f"{streak} Tage in Folge — weiter so.")
    elif streak == 1:
        lines.append("Heute ist Tag 1 — guter Start.")
    else:
        lines.append("Schön, dass du wieder da bist.")

    if total_30d > 0:
        lines.append(f"In den letzten 30 Tagen hast du mich an {total_30d} Tagen genutzt.")

    mood_labels = {
        "happy": "guter Laune",
        "focused": "fokussiert",
        "tired": "müde",
        "frustrated": "frustriert",
        "neutral": "neutral",
    }
    if recent_mood and recent_mood != "neutral" and mood_confidence >= 0.05:
        lines.append(
            f"Gestern warst du überwiegend {mood_labels.get(recent_mood, recent_mood)}."
        )

    time_labels = {
        "morning": "morgens",
        "afternoon": "nachmittags",
        "evening": "abends",
        "night": "nachts",
    }
    if peak_time:
        lines.append(f"Du bist am aktivsten {time_labels.get(peak_time, peak_time)}.")

    # Proaktivitäts-Level (DONNA-7)
    proactivity_summary: dict = {"level": 3, "trend": "Feedback neutral — Standard-Proaktivität aktiv", "total": 0}
    proactivity_svc = getattr(request.app.state, "proactivity", None)
    if proactivity_svc is not None:
        try:
            proactivity_summary = proactivity_svc.get_summary(days=14)
        except Exception as exc:
            log.warning("briefing_proactivity_failed", error=str(exc))

    if proactivity_summary["level"] != 3:
        lines.append(f"Proaktivität: {proactivity_summary['trend']}.")

    text = " ".join(lines) if lines else "Guten Morgen. Wie kann ich dir heute helfen?"

    return {
        "text": text,
        "streak": streak,
        "total_30d": total_30d,
        "today_count": summary["today_count"],
        "recent_mood": recent_mood,
        "mood_confidence": mood_confidence,
        "peak_time": peak_time,
        "peak_weekdays": peak_weekdays,
        "proactivity_level": proactivity_summary["level"],
        "proactivity_trend": proactivity_summary["trend"],
    }

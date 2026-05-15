"""DONNA-7: Proaktivitäts-Feedback-Loop.

Liest aggregierte Feedback-Daten (👍/👎) und berechnet einen
Proaktivitäts-Level (1–5), der steuert wie stark Donna proaktiv
auf ungebetene Hinweise eingehen soll.

Level-Bedeutung:
  1 — Zurückhaltend (viele negative Bewertungen)
  2 — Reduziert
  3 — Standard (zu wenig Daten oder neutrales Feedback)
  4 — Erhöht (überwiegend positive Bewertungen)
  5 — Maximal proaktiv (konsistent sehr positive Bewertungen)

DONNA-113: morning_brief() + evening_checkin() für tägliche Morgen/Abend-Pushes.
"""

from __future__ import annotations

import os
from typing import Optional

import structlog
import httpx

from app.services.feedback_service import FeedbackService

log = structlog.get_logger(__name__)

# Schwellwerte für Feedback-Ratio (positive / total)
_RATIO_THRESHOLDS = [
    (0.85, 5),
    (0.70, 4),
    (0.50, 3),
    (0.30, 2),
]

_MIN_SAMPLES = 5  # Mindestanzahl Bewertungen für Ratio-Berechnung

_PROMPT_INSTRUCTIONS: dict[int, str] = {
    1: (
        "Sei sehr zurückhaltend mit ungebetenen Hinweisen. "
        "Antworte präzise auf das Gestellte — keine Zusatz-Tipps, keine Warnungen "
        "außer bei echten Risiken."
    ),
    2: (
        "Spare proaktive Hinweise für wirklich wichtige Situationen. "
        "Ergänze nur wenn es einen klaren Mehrwert hat."
    ),
    3: (
        "Handle proaktiv: weise auf übersehene Aspekte, Risiken und sinnvolle "
        "Ergänzungen hin, wenn sie relevant sind."
    ),
    4: (
        "Handle aktiv proaktiv: weise auf Muster, Zusammenhänge und potenzielle "
        "Optimierungen hin. Antizipiere Folgefragen."
    ),
    5: (
        "Handle maximal proaktiv: antizipiere Bedürfnisse, weise aktiv auf Muster, "
        "übersehene Chancen und Inkonsistenzen hin — auch wenn nicht explizit gefragt."
    ),
}

_TREND_LABELS: dict[int, str] = {
    1: "Feedback zeigt: weniger proaktive Hinweise gewünscht",
    2: "Feedback zeigt: proaktive Hinweise reduzieren",
    3: "Feedback neutral — Standard-Proaktivität aktiv",
    4: "Feedback positiv — proaktive Antworten werden geschätzt",
    5: "Feedback sehr positiv — maximale Proaktivität aktiviert",
}


class ProactivityService:
    """Berechnet Proaktivitäts-Level aus Feedback-Daten.

    SICHERHEIT: _PROMPT_INSTRUCTIONS enthält ausschließlich hardcodierte
    Konstanten — kein User-Input, keine Feedback-Inhalte fließen in den
    zurückgegebenen String ein. Prompt-Injection ist strukturell ausgeschlossen.
    """

    def __init__(self, feedback_svc: FeedbackService) -> None:
        self._fb = feedback_svc

    # ── Interne Berechnung ────────────────────────────────────────────────────

    def _compute_level(self, summary: dict) -> int:
        """Level aus bereits geladener Summary — kein zusätzlicher DB-Call."""
        if summary.get("status") == "no_data" or summary.get("total", 0) < _MIN_SAMPLES:
            return 3

        raw_ratio = summary.get("ratio")
        ratio: float = 0.5 if raw_ratio is None else raw_ratio
        for threshold, lvl in _RATIO_THRESHOLDS:
            if ratio >= threshold:
                return lvl
        return 1

    # ── Public API ────────────────────────────────────────────────────────────

    def get_level(self, days: int = 14) -> int:
        """Proaktivitäts-Level 1–5 aus Feedback der letzten N Tage.

        Gibt 3 (Standard) zurück wenn zu wenig Daten vorhanden.
        """
        try:
            summary = self._fb.get_summary(days=days)
        except Exception as exc:
            log.warning("proactivity_feedback_read_failed", error=str(exc))
            return 3
        return self._compute_level(summary)

    def get_prompt_instruction(self, days: int = 14) -> str:
        """System-Prompt-Anweisung passend zum aktuellen Proaktivitäts-Level.

        Gibt immer einen der 5 hardcodierten Strings aus _PROMPT_INSTRUCTIONS zurück.
        """
        return _PROMPT_INSTRUCTIONS[self.get_level(days=days)]

    async def send_push_notification(
        self,
        body: str,
        title: str = "Donna",
        priority: int = 3,
        *,
        ntfy_url: str = "http://assistent-ntfy:80",
        ntfy_topic: str = "donna",
    ) -> bool:
        """Sendet eine Push-Notification via ntfy (fire-and-forget für Proaktiv-Nachrichten).

        DONNA-13: Wird aus dem Chat-Flow aufgerufen wenn Donna eine proaktive Nachricht
        an das Handy schicken soll (z.B. Reflexions-Hinweis nach mehreren Gesprächen).

        Gibt True bei Erfolg zurück, False bei Fehler (kein raise — Chat-Flow darf
        nicht durch Notification-Fehler blockiert werden).

        ntfy_url / ntfy_topic: konfigurierbar für Tests, Default = interne Container-Adresse.
        """
        _priority_map = {1: "min", 2: "low", 3: "default", 4: "high", 5: "urgent"}
        headers = {
            "Title": title,
            "Priority": _priority_map.get(priority, "default"),
            "Content-Type": "text/plain; charset=utf-8",
        }
        target = f"{ntfy_url.rstrip('/')}/{ntfy_topic}"
        try:
            async with httpx.AsyncClient(timeout=5.0) as client:
                r = await client.post(target, content=body.encode("utf-8"), headers=headers)
                r.raise_for_status()
            log.info("proactive_push_sent", title=title, body_len=len(body), topic=ntfy_topic)
            return True
        except Exception as exc:  # noqa: BLE001
            log.warning("proactive_push_failed", error=str(exc), target=target)
            return False

    # ── DONNA-113: Morgen/Abend-Pushes ───────────────────────────────────────

    async def morning_brief(
        self,
        *,
        calendar_svc=None,
        ltm_svc=None,
        gemini_client=None,
        ntfy_url: Optional[str] = None,
        ntfy_topic: Optional[str] = None,
    ) -> bool:
        """Sendet das Morgen-Briefing via ntfy.

        DONNA-113: Wird täglich 10:00 UTC via CronTrigger aufgerufen.

        Ablauf:
        1. Level-Check: nur wenn ProactivityLevel >= 2
        2. Heute's Kalender-Termine laden (calendar_svc.get_upcoming_events(days=1))
        3. LTM-Kontext: ltm_svc.recall_relevant("tagesplan heute aufgaben") top_k=3
        4. Gemini generiert 1-2 Sätze aus Terminen + LTM
        5. ntfy push mit Titel "Guten Morgen, Mike!"

        Kein Crash bei Fehlern (fire-and-forget, ntfy-Fehler returnt False).
        Kalender-PII: nicht in LTM speichern (DSGVO Auflage 4 aus CalendarService).
        """
        level = self.get_level()
        if level < 2:
            log.info("morning_brief_skipped", reason="proactivity_level_too_low", level=level)
            return False

        # Kalender-Termine heute
        calendar_context = ""
        if calendar_svc is not None:
            try:
                events = calendar_svc.get_upcoming_events(days=1)
                if events:
                    formatted = calendar_svc.format_for_prompt(events, max_events=3)
                    if formatted:
                        calendar_context = formatted
            except Exception as exc:  # noqa: BLE001
                log.warning("morning_brief_calendar_failed", error=str(exc))

        # LTM-Kontext
        ltm_context = ""
        if ltm_svc is not None:
            try:
                results = ltm_svc.recall_relevant("tagesplan heute aufgaben", top_k=3)
                if results:
                    ltm_context = " | ".join(
                        r.get("content", "")[:80] for r in results[:3] if r.get("content")
                    )
            except Exception as exc:  # noqa: BLE001
                log.warning("morning_brief_ltm_failed", error=str(exc))

        # Morgen-Nachricht generieren
        body = await self._generate_morning_text(
            calendar_context=calendar_context,
            ltm_context=ltm_context,
            gemini_client=gemini_client,
        )

        # ntfy Push
        _ntfy_url = ntfy_url or os.environ.get("NTFY_URL", "http://assistent-ntfy:80")
        _ntfy_topic = ntfy_topic or os.environ.get("NTFY_TOPIC", "donna")
        return await self.send_push_notification(
            body=body,
            title="Guten Morgen, Mike!",
            priority=3,
            ntfy_url=_ntfy_url,
            ntfy_topic=_ntfy_topic,
        )

    async def evening_checkin(
        self,
        *,
        ltm_svc=None,
        gemini_client=None,
        ntfy_url: Optional[str] = None,
        ntfy_topic: Optional[str] = None,
    ) -> bool:
        """Sendet den Abend-Check-in via ntfy.

        DONNA-113: Wird täglich 16:00 UTC via CronTrigger aufgerufen.

        Ablauf:
        1. Level-Check: nur wenn ProactivityLevel >= 2
        2. Kurze Check-in-Frage basierend auf ProactivityLevel
        3. ntfy push mit Titel "Abend-Check-in"

        Level 2: generische Frage "Wie war dein Tag?"
        Level 3+: LTM-basierte spezifischere Frage
        """
        level = self.get_level()
        if level < 2:
            log.info("evening_checkin_skipped", reason="proactivity_level_too_low", level=level)
            return False

        body = await self._generate_evening_text(
            level=level,
            ltm_svc=ltm_svc,
            gemini_client=gemini_client,
        )

        _ntfy_url = ntfy_url or os.environ.get("NTFY_URL", "http://assistent-ntfy:80")
        _ntfy_topic = ntfy_topic or os.environ.get("NTFY_TOPIC", "donna")
        return await self.send_push_notification(
            body=body,
            title="Abend-Check-in",
            priority=2,
            ntfy_url=_ntfy_url,
            ntfy_topic=_ntfy_topic,
        )

    async def _generate_morning_text(
        self,
        *,
        calendar_context: str,
        ltm_context: str,
        gemini_client=None,
    ) -> str:
        """Generiert Morgen-Briefing-Text via Gemini (max 2 Sätze).

        Fallback auf statischen Text wenn Gemini nicht verfügbar.
        """
        has_calendar = bool(calendar_context)
        has_ltm = bool(ltm_context)

        default_parts = ["Guten Morgen! Ich bin bereit wenn du Fragen hast."]
        if has_calendar:
            default_parts.append("Du hast heute Termine. Schau in deinen Kalender.")

        default = " ".join(default_parts)

        if gemini_client is None:
            return default

        try:
            context_parts = []
            if has_calendar:
                context_parts.append(f"Kalender heute: {calendar_context[:300]}")
            if has_ltm:
                context_parts.append(f"Relevanter Kontext: {ltm_context[:200]}")

            context_str = "\n".join(context_parts) if context_parts else "Keine Termine heute."
            prompt = (
                "Du bist Donna, Mikes persönlicher KI-Assistent. "
                "Schreib eine kurze, motivierende Morgen-Nachricht für Mike "
                "(max 2 Sätze, kein Markdown). "
                "Antworte AUSSCHLIESSLICH auf Deutsch — niemals auf Englisch. "
                f"Kontext:\n{context_str}\n"
                "Sei direkt und konkret — keine Floskeln, keine generischen Tipps."
            )
            response = await gemini_client.generate_async(prompt)
            if response and len(response.strip()) > 5:
                return response.strip()[:300]
        except Exception as exc:  # noqa: BLE001
            log.warning("morning_brief_gemini_failed", error=str(exc))

        return default

    async def _generate_evening_text(
        self,
        *,
        level: int,
        ltm_svc=None,
        gemini_client=None,
    ) -> str:
        """Generiert Abend-Check-in-Text.

        Level 2: "Wie war dein Tag?"
        Level 3+: LTM-basierte spezifischere Frage via Gemini.
        """
        if level <= 2 or gemini_client is None:
            return "Hey Mike! Wie war dein Tag heute? Kurz Bescheid geben 👋"

        # Level 3+ mit LTM-Kontext
        ltm_context = ""
        if ltm_svc is not None:
            try:
                results = ltm_svc.recall_relevant("aktuelle projekte ziele aufgaben", top_k=3)
                if results:
                    ltm_context = " | ".join(
                        r.get("content", "")[:60] for r in results[:3] if r.get("content")
                    )
            except Exception as exc:  # noqa: BLE001
                log.warning("evening_checkin_ltm_failed", error=str(exc))

        try:
            context_str = f"Kontext aus Mikes Brain: {ltm_context}" if ltm_context else ""
            prompt = (
                "Du bist Donna, Mikes persönlicher KI-Assistent. "
                "Schreib eine kurze, persönliche Abend-Check-in-Frage für Mike "
                "(1 Satz, kein Markdown, direkt und freundlich). "
                "Antworte AUSSCHLIESSLICH auf Deutsch — niemals auf Englisch. "
                f"{context_str}"
            )
            response = await gemini_client.generate_async(prompt)
            if response and len(response.strip()) > 5:
                return response.strip()[:200]
        except Exception as exc:  # noqa: BLE001
            log.warning("evening_checkin_gemini_failed", error=str(exc))

        return "Hey Mike! Wie war dein Tag heute? Kurz Bescheid geben 👋"

    def get_summary(self, days: int = 14) -> dict:
        """Vollständige Proaktivitäts-Zusammenfassung für /briefing.

        Lädt Feedback-Daten einmal — Level wird aus demselben Ergebnis berechnet
        (kein zweiter DB-Call).
        """
        try:
            fb = self._fb.get_summary(days=days)
        except Exception as exc:
            log.warning("proactivity_summary_failed", error=str(exc))
            fb = {"status": "no_data", "positive": 0, "negative": 0, "total": 0, "ratio": None}

        level = self._compute_level(fb)  # direkt aus fb — kein zweiter _fb.get_summary()-Call
        return {
            "level": level,
            "trend": _TREND_LABELS[level],
            "feedback_status": fb.get("status", "no_data"),
            "positive": fb.get("positive", 0),
            "negative": fb.get("negative", 0),
            "total": fb.get("total", 0),
            "ratio": fb.get("ratio"),
        }

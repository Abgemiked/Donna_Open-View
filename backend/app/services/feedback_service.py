"""feedback_service.py — 👍/👎 Feedback auf Donna-Antworten.

Speichert User-Feedback in SQLite und stellt Auswertungen bereit.
Dient langfristig als Trainingssignal für proaktive Antwort-Kalibrierung.
"""
from __future__ import annotations

import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path

from app.core.logger import get_logger

log = get_logger("service.feedback")


class FeedbackService:
    """Speichert und wertet Feedback-Ratings auf Donna-Antworten aus."""

    def __init__(self, db_path: str) -> None:
        self._db_path = db_path
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        self._init_db()
        log.info("feedback_service_ready", db=db_path)

    def _conn(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self._db_path, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        return conn

    def _init_db(self) -> None:
        with self._conn() as conn:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS feedback_log (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts          TEXT NOT NULL,
                    session_id  TEXT NOT NULL,
                    rating      TEXT NOT NULL,    -- 'positive' | 'negative'
                    snippet     TEXT,             -- erste 200 Zeichen der Antwort
                    context     TEXT              -- optionaler Kontext (Thema, etc.)
                )
            """)
            conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_feedback_ts ON feedback_log(ts)"
            )
            conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_feedback_session ON feedback_log(session_id)"
            )

    # ── Write ─────────────────────────────────────────────────────────────

    def log_feedback(
        self,
        session_id: str,
        rating: str,
        snippet: str | None = None,
        context: str | None = None,
    ) -> int:
        """Speichert ein Feedback-Event. Gibt die neue ID zurück."""
        if rating not in ("positive", "negative"):
            raise ValueError(f"Invalid rating: {rating}")
        ts = datetime.now(timezone.utc).isoformat()
        with self._conn() as conn:
            cur = conn.execute(
                "INSERT INTO feedback_log (ts, session_id, rating, snippet, context) "
                "VALUES (?, ?, ?, ?, ?)",
                (ts, session_id, rating, (snippet or "")[:200], context),
            )
            new_id = cur.lastrowid or 0
        log.info("feedback_logged", rating=rating, session_id=session_id)
        return new_id

    # ── Read ──────────────────────────────────────────────────────────────

    def get_summary(self, days: int = 30) -> dict:
        """Feedback-Zusammenfassung der letzten N Tage."""
        since = (datetime.now(timezone.utc) - timedelta(days=days)).isoformat()
        with self._conn() as conn:
            rows = conn.execute(
                "SELECT rating, COUNT(*) as cnt FROM feedback_log "
                "WHERE ts >= ? GROUP BY rating",
                (since,),
            ).fetchall()
        counts = {r["rating"]: r["cnt"] for r in rows}
        pos = counts.get("positive", 0)
        neg = counts.get("negative", 0)
        total = pos + neg
        ratio = round(pos / total, 2) if total > 0 else None
        return {
            "positive": pos,
            "negative": neg,
            "total": total,
            "ratio": ratio,
            "days": days,
            "status": (
                "good" if ratio is not None and ratio >= 0.7
                else "needs_review" if ratio is not None
                else "no_data"
            ),
        }

    def get_recent(self, limit: int = 50) -> list[dict]:
        """Letzte N Feedback-Einträge."""
        with self._conn() as conn:
            rows = conn.execute(
                "SELECT ts, session_id, rating, snippet FROM feedback_log "
                "ORDER BY ts DESC LIMIT ?",
                (limit,),
            ).fetchall()
        return [dict(r) for r in rows]

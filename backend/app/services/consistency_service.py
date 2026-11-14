"""consistency_service.py — Nutzungs-Tracking für Donna.

Speichert pro Tag die Anzahl der Chat-Nachrichten.
Keine Scham-Trigger — reine Selbst-Sichtbarkeit für Mike.
"""
from __future__ import annotations

import sqlite3
from datetime import date, timedelta
from pathlib import Path

from app.core.logger import get_logger

log = get_logger("service.consistency")

_DEFAULT_DB_PATH = "/data/consistency.db"


def _get_db(db_path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(db_path, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    return conn


def _ensure_schema(db_path: str) -> None:
    Path(db_path).parent.mkdir(parents=True, exist_ok=True)
    conn = _get_db(db_path)
    with conn:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS usage_log (
                date          DATE    PRIMARY KEY,
                message_count INTEGER NOT NULL DEFAULT 0
            )
        """)
    conn.close()


class ConsistencyService:
    """SQLite-basiertes Nutzungs-Tracking."""

    def __init__(self, db_path: str = _DEFAULT_DB_PATH) -> None:
        self._db_path = db_path
        _ensure_schema(db_path)
        log.info("consistency_service_ready", db_path=db_path)

    def record_message(self) -> None:
        """Inkrementiert den Nachrichten-Counter für heute."""
        today = date.today().isoformat()
        conn = _get_db(self._db_path)
        try:
            with conn:
                conn.execute(
                    """
                    INSERT INTO usage_log (date, message_count)
                    VALUES (?, 1)
                    ON CONFLICT(date) DO UPDATE SET message_count = message_count + 1
                    """,
                    (today,),
                )
        finally:
            conn.close()

    def get_streak(self) -> int:
        """Berechnet aufeinanderfolgende Nutzungstage (heute rückwärts bis zur ersten Lücke)."""
        conn = _get_db(self._db_path)
        try:
            rows = conn.execute(
                "SELECT date FROM usage_log ORDER BY date DESC"
            ).fetchall()
        finally:
            conn.close()

        if not rows:
            return 0

        dates = [date.fromisoformat(r["date"]) for r in rows]
        today = date.today()

        # Streak beginnt nur wenn heute (oder gestern) Nutzung vorhanden
        if dates[0] < today - timedelta(days=1):
            return 0

        streak = 0
        expected = dates[0]
        for d in dates:
            if d == expected:
                streak += 1
                expected = expected - timedelta(days=1)
            else:
                break
        return streak

    def get_total_30d(self) -> int:
        """Anzahl Tage mit mindestens 1 Message in den letzten 30 Tagen."""
        cutoff = (date.today() - timedelta(days=30)).isoformat()
        conn = _get_db(self._db_path)
        try:
            row = conn.execute(
                "SELECT COUNT(*) as cnt FROM usage_log WHERE date >= ?",
                (cutoff,),
            ).fetchone()
            return int(row["cnt"]) if row else 0
        finally:
            conn.close()

    def get_today_count(self) -> int:
        """Anzahl Nachrichten heute."""
        today = date.today().isoformat()
        conn = _get_db(self._db_path)
        try:
            row = conn.execute(
                "SELECT message_count FROM usage_log WHERE date = ?",
                (today,),
            ).fetchone()
            return int(row["message_count"]) if row else 0
        finally:
            conn.close()

    def get_summary(self) -> dict:
        """Gibt eine Zusammenfassung der Nutzung zurück."""
        return {
            "streak": self.get_streak(),
            "total_30d": self.get_total_30d(),
            "today_count": self.get_today_count(),
        }

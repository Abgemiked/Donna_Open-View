"""mood_service.py — Keyword-basierte Stimmungserkennung für Donna.

Kein ML, kein externes Modell. Reine lokale Keyword-Analyse.
Mood-Daten werden NIE an Gemini oder externe Services gesendet.
"""
from __future__ import annotations

import sqlite3
from datetime import datetime, timezone
from pathlib import Path

from app.core.logger import get_logger

log = get_logger("service.mood")

MOOD_KEYWORDS: dict[str, list[str]] = {
    "frustrated": [
        "nervt", "ätzend", "atzend", "scheiß", "scheiss", "nicht mehr",
        "klappt nicht", "bricht ab", "funktioniert nicht", "kaputt",
        "mist", "verdammt", "nutzlos",
    ],
    "happy": [
        "super", "klappt", "perfekt", "danke", "cool", "läuft", "lauft",
        "klasse", "toll", "wunderbar", "großartig", "grossartig", "top",
        "prima", "hervorragend",
    ],
    "tired": [
        "müde", "mude", "kaputt", "erschöpft", "erschopft",
        "schlaf", "aufhören", "aufhoren", "genug", "pause",
    ],
    "focused": [
        "weiter", "nächster", "nachster", "schritt", "plan",
        "ziel", "weitergeht", "als nächstes", "todo", "aufgabe",
    ],
}

_VALID_MOODS = set(MOOD_KEYWORDS.keys()) | {"neutral"}
_DEFAULT_DB_PATH = "/data/mood.db"


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
            CREATE TABLE IF NOT EXISTS mood_log (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id  TEXT    NOT NULL,
                mood        TEXT    NOT NULL,
                confidence  REAL    NOT NULL,
                text_snippet TEXT   NOT NULL,
                corrected_mood TEXT,
                created_at  TEXT    NOT NULL
            )
        """)
    conn.close()


def detect_mood(text: str) -> tuple[str, float]:
    """Keyword-basierte Stimmungserkennung.

    Returns:
        (mood, confidence) where confidence = matched / total_keywords_in_category.
        Returns ("neutral", 0.0) wenn kein Match.
    """
    lower = text.lower()
    best_mood = "neutral"
    best_confidence = 0.0

    for mood, keywords in MOOD_KEYWORDS.items():
        matched = sum(1 for kw in keywords if kw in lower)
        if matched == 0:
            continue
        confidence = round(matched / len(keywords), 4)
        if confidence > best_confidence:
            best_confidence = confidence
            best_mood = mood

    return best_mood, best_confidence


class MoodService:
    """SQLite-basierter Mood-Log-Service."""

    def __init__(self, db_path: str = _DEFAULT_DB_PATH) -> None:
        self._db_path = db_path
        _ensure_schema(db_path)
        log.info("mood_service_ready", db_path=db_path)

    def log_mood(
        self,
        session_id: str,
        mood: str,
        confidence: float,
        text_snippet: str,
    ) -> int:
        """Schreibt eine Mood-Erkennung in die DB. Gibt die log_id zurück."""
        # Snippet kürzen — max 200 Zeichen
        snippet = text_snippet[:200]
        now = datetime.now(timezone.utc).isoformat()
        conn = _get_db(self._db_path)
        try:
            with conn:
                cur = conn.execute(
                    """
                    INSERT INTO mood_log (session_id, mood, confidence, text_snippet, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    (session_id, mood, confidence, snippet, now),
                )
                log_id = cur.lastrowid or 0
            log.info("mood_logged", log_id=log_id, mood=mood, confidence=confidence, session_id=session_id)
            return log_id
        finally:
            conn.close()

    def get_mood_history(self, days: int = 7) -> list[dict]:
        """Gibt Mood-Log der letzten N Tage zurück."""
        conn = _get_db(self._db_path)
        try:
            rows = conn.execute(
                """
                SELECT id, session_id, mood, confidence, text_snippet, corrected_mood, created_at
                FROM mood_log
                WHERE created_at >= datetime('now', ? || ' days')
                ORDER BY created_at DESC
                """,
                (f"-{days}",),
            ).fetchall()
            return [dict(r) for r in rows]
        finally:
            conn.close()

    def correct_mood(self, log_id: int, correct_mood: str) -> bool:
        """Korrigiert eine Mood-Erkennung. Gibt True zurück wenn Eintrag gefunden."""
        if correct_mood not in _VALID_MOODS:
            log.warning("mood_correct_invalid_mood", mood=correct_mood)
            return False
        conn = _get_db(self._db_path)
        try:
            with conn:
                cur = conn.execute(
                    "UPDATE mood_log SET corrected_mood = ? WHERE id = ?",
                    (correct_mood, log_id),
                )
            if cur.rowcount == 0:
                return False
            log.info("mood_corrected", log_id=log_id, correct_mood=correct_mood)
            return True
        finally:
            conn.close()

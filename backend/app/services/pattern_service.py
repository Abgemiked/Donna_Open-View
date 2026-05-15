"""
pattern_service.py — Muster-Erkennung aus dem Event-Log

Analysiert SQLite `event_log` Tabelle und erkennt Nutzungsmuster:
- Häufigste Tageszeit (morning/afternoon/evening/night)
- Häufigste Wochentage
- Durchschnittliche Session-Länge
- Häufigste LTM-Kategorien

Schreibt vault/profile/patterns.md nach jedem Clustering-Lauf.
"""
from __future__ import annotations

import json
import sqlite3
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

from app.core.logger import get_logger

log = get_logger("service.pattern")

# Event-Typen
EVENT_CHAT_MESSAGE = "chat_message"
EVENT_LTM_STORE = "ltm_store"
EVENT_MOOD_LOG = "mood_log"
EVENT_VOICE_INPUT = "voice_input"

_WEEKDAY_NAMES = ["Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"]

_CREATE_EVENT_LOG_SQL = """
CREATE TABLE IF NOT EXISTS event_log (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    event_type    TEXT    NOT NULL,
    metadata_json TEXT    NOT NULL DEFAULT '{}',
    created_at    REAL    NOT NULL
);
"""

_CREATE_EVENT_LOG_INDEX_SQL = """
CREATE INDEX IF NOT EXISTS idx_event_log_type_created
    ON event_log (event_type, created_at);
"""


def _hour_to_period(hour: int) -> str:
    """Ordnet eine Stunde (0-23) einer Tageszeit zu."""
    if 5 <= hour < 12:
        return "morning"
    if 12 <= hour < 17:
        return "afternoon"
    if 17 <= hour < 22:
        return "evening"
    return "night"


def _period_to_german(period: str) -> str:
    return {
        "morning": "morgens (5-12 Uhr)",
        "afternoon": "nachmittags (12-17 Uhr)",
        "evening": "abends (17-22 Uhr)",
        "night": "nachts (22-5 Uhr)",
    }.get(period, period)


class PatternService:
    """Muster-Erkennung aus dem SQLite Event-Log."""

    def __init__(
        self,
        db_path: str = "/data/stm.db",
        vault_path: str = "/vault",
    ) -> None:
        self._db_path = db_path
        self._vault_path = Path(vault_path)
        self._init_db()

    def _init_db(self) -> None:
        """Erstellt event_log Tabelle falls nicht vorhanden."""
        try:
            Path(self._db_path).parent.mkdir(parents=True, exist_ok=True)
            with sqlite3.connect(self._db_path) as conn:
                conn.execute(_CREATE_EVENT_LOG_SQL)
                conn.execute(_CREATE_EVENT_LOG_INDEX_SQL)
                conn.commit()
        except Exception as e:  # noqa: BLE001
            log.warning("pattern_db_init_failed", error=str(e))

    def log_event(
        self,
        event_type: str,
        metadata: dict | None = None,
    ) -> None:
        """Schreibt einen neuen Event in den Log."""
        try:
            with sqlite3.connect(self._db_path) as conn:
                conn.execute(
                    "INSERT INTO event_log (event_type, metadata_json, created_at) VALUES (?, ?, ?)",
                    (event_type, json.dumps(metadata or {}), time.time()),
                )
                conn.commit()
        except Exception as e:  # noqa: BLE001
            log.warning("pattern_log_event_failed", error=str(e))

    def _fetch_events(self, days: int = 30) -> list[dict]:
        """Lädt Events der letzten N Tage aus der DB."""
        since = time.time() - days * 86400
        try:
            with sqlite3.connect(self._db_path) as conn:
                rows = conn.execute(
                    "SELECT event_type, metadata_json, created_at FROM event_log "
                    "WHERE created_at >= ? ORDER BY created_at ASC",
                    (since,),
                ).fetchall()
            result = []
            for event_type, meta_json, created_at in rows:
                try:
                    meta = json.loads(meta_json)
                except (json.JSONDecodeError, TypeError):
                    meta = {}
                result.append({
                    "event_type": event_type,
                    "metadata": meta,
                    "created_at": created_at,
                })
            return result
        except Exception as e:  # noqa: BLE001
            log.warning("pattern_fetch_events_failed", error=str(e))
            return []

    def detect_patterns(self, days: int = 30) -> list[dict]:
        """
        Analysiert den Event-Log der letzten N Tage und erkennt Muster.

        Gibt eine Liste von Pattern-Dicts zurück:
        [{"type": "peak_time", "value": "evening", "confidence": 0.8}]
        """
        events = self._fetch_events(days)
        if not events:
            return []

        patterns: list[dict] = []

        # --- Pattern 1: Häufigste Tageszeit ---
        chat_events = [e for e in events if e["event_type"] == EVENT_CHAT_MESSAGE]
        if chat_events:
            period_counter: Counter[str] = Counter()
            for e in chat_events:
                dt = datetime.fromtimestamp(e["created_at"], tz=timezone.utc)
                period_counter[_hour_to_period(dt.hour)] += 1

            total_chat = sum(period_counter.values())
            if total_chat > 0:
                top_period, top_count = period_counter.most_common(1)[0]
                confidence = round(top_count / total_chat, 2)
                patterns.append({
                    "type": "peak_time",
                    "value": top_period,
                    "count": top_count,
                    "confidence": confidence,
                    "distribution": dict(period_counter),
                })

        # --- Pattern 2: Häufigste Wochentage ---
        if chat_events:
            weekday_counter: Counter[int] = Counter()
            for e in chat_events:
                dt = datetime.fromtimestamp(e["created_at"], tz=timezone.utc)
                weekday_counter[dt.weekday()] += 1

            total = sum(weekday_counter.values())
            if total > 0:
                top_days = weekday_counter.most_common(3)
                patterns.append({
                    "type": "peak_weekdays",
                    "value": [_WEEKDAY_NAMES[d] for d, _ in top_days],
                    "confidence": round(sum(c for _, c in top_days) / total, 2),
                    "distribution": {_WEEKDAY_NAMES[d]: c for d, c in weekday_counter.items()},
                })

        # --- Pattern 3: Durchschnittliche Session-Länge ---
        if chat_events:
            # Gruppiere nach session_id (aus Metadaten)
            sessions: dict[str, list] = {}
            for e in chat_events:
                sid = e["metadata"].get("session_id", "unknown")
                sessions.setdefault(sid, []).append(e)

            session_lengths = [len(msgs) for msgs in sessions.values()]
            if session_lengths:
                avg_len = round(sum(session_lengths) / len(session_lengths), 1)
                patterns.append({
                    "type": "avg_session_length",
                    "value": avg_len,
                    "session_count": len(sessions),
                    "confidence": 1.0,
                })

        # --- Pattern 4: Häufigste LTM-Kategorien ---
        ltm_events = [e for e in events if e["event_type"] == EVENT_LTM_STORE]
        if ltm_events:
            cat_counter: Counter[str] = Counter()
            for e in ltm_events:
                cat = e["metadata"].get("category", "user_fact")
                cat_counter[cat] += 1

            total_ltm = sum(cat_counter.values())
            if total_ltm > 0:
                top_cats = cat_counter.most_common(3)
                patterns.append({
                    "type": "ltm_categories",
                    "value": [cat for cat, _ in top_cats],
                    "distribution": dict(cat_counter),
                    "confidence": round(top_cats[0][1] / total_ltm, 2) if top_cats else 0.0,
                })

        log.info("patterns_detected", count=len(patterns), days=days)
        return patterns

    def write_patterns_md(self, patterns: list[dict], dry_run: bool = False) -> None:
        """Schreibt vault/profile/patterns.md."""
        timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
        content = self._build_patterns_md(patterns, timestamp)

        if dry_run:
            log.info("pattern_dry_run_skip_write", lines=content.count("\n"))
            return

        try:
            profile_dir = self._vault_path / "profile"
            profile_dir.mkdir(parents=True, exist_ok=True)
            (profile_dir / "patterns.md").write_text(content, encoding="utf-8")
            log.info("pattern_profile_written", path=str(profile_dir / "patterns.md"))
        except Exception as e:  # noqa: BLE001
            log.error("pattern_profile_write_failed", error=str(e))

    def _build_patterns_md(self, patterns: list[dict], timestamp: str) -> str:
        """Erstellt den Inhalt der patterns.md Profil-Datei."""
        lines = [
            f"# Donna — Deine Nutzungsmuster (Stand: {timestamp})",
            "",
            "## Wann du Donna meist nutzt",
        ]

        peak_time = next((p for p in patterns if p["type"] == "peak_time"), None)
        peak_days = next((p for p in patterns if p["type"] == "peak_weekdays"), None)
        avg_session = next((p for p in patterns if p["type"] == "avg_session_length"), None)
        ltm_cats = next((p for p in patterns if p["type"] == "ltm_categories"), None)

        if peak_time:
            period_de = _period_to_german(peak_time["value"])
            count = peak_time.get("count", 0)
            lines.append(f"- Hauptzeit: {period_de} — {count} Sessions")
        else:
            lines.append("- Keine Daten vorhanden")

        if peak_days:
            days_str = ", ".join(peak_days["value"])
            lines.append(f"- Häufigste Wochentage: {days_str}")

        lines += ["", "## Wie du Donna nutzt"]

        if avg_session:
            avg = avg_session["value"]
            n_sessions = avg_session["session_count"]
            lines.append(f"- Durchschnittliche Session: {avg} Nachrichten ({n_sessions} Sessions analysiert)")
        else:
            lines.append("- Keine Session-Daten vorhanden")

        if ltm_cats:
            cats_str = ", ".join(ltm_cats["value"])
            lines.append(f"- Häufigste Themen: {cats_str}")

        lines.append("")
        return "\n".join(lines)

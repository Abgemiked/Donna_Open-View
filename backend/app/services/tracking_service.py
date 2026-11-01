"""tracking_service.py — Activity & GPS-Tracking Service.

Empfängt Standort- und App-Nutzungsdaten vom Android-Client und
stellt sie für Donna-Kontext zur Verfügung.
"""
from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path

from app.core.logger import get_logger

log = get_logger("service.tracking")


class TrackingService:
    """Speichert Tracking-Events (GPS + App-Aktivität) in SQLite."""

    def __init__(self, db_path: str) -> None:
        self._db_path = db_path
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        self._init_db()
        log.info("tracking_service_ready", db_path=db_path)

    def _conn(self) -> sqlite3.Connection:
        # timeout=10: wartet bis zu 10s auf SQLite-Lock statt default 5s
        conn = sqlite3.connect(self._db_path, check_same_thread=False, timeout=10.0)
        conn.row_factory = sqlite3.Row
        # WAL-Mode: gleichzeitige Reads+Writes ohne gegenseitiges Blockieren
        conn.execute('PRAGMA journal_mode=WAL')
        return conn

    def _init_db(self) -> None:
        with self._conn() as conn:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS tracking_events (
                    id      INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts      TEXT NOT NULL,
                    type    TEXT NOT NULL,
                    payload TEXT NOT NULL
                )
            """)
            conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_tracking_ts ON tracking_events(ts)"
            )

    # ── Write ─────────────────────────────────────────────────────────────

    def push(self, event_type: str, data: dict) -> None:
        """Speichert ein neues Tracking-Event."""
        ts = datetime.now(timezone.utc).isoformat()
        payload = json.dumps(data, ensure_ascii=False)
        with self._conn() as conn:
            conn.execute(
                "INSERT INTO tracking_events (ts, type, payload) VALUES (?, ?, ?)",
                (ts, event_type, payload),
            )
        log.info("tracking_push", type=event_type)

    # ── Read ──────────────────────────────────────────────────────────────

    def get_recent(self, hours: int = 24) -> list[dict]:
        """Gibt alle Events der letzten N Stunden zurück."""
        since = (datetime.now(timezone.utc) - timedelta(hours=hours)).isoformat()
        with self._conn() as conn:
            rows = conn.execute(
                "SELECT ts, type, payload FROM tracking_events "
                "WHERE ts >= ? ORDER BY ts DESC LIMIT 500",
                (since,),
            ).fetchall()
        result = []
        for row in rows:
            entry = {"ts": row["ts"], "type": row["type"]}
            try:
                entry.update(json.loads(row["payload"]))
            except Exception:
                pass
            result.append(entry)
        return result

    def get_last_location(self) -> dict | None:
        """Letzter bekannter GPS-Standort."""
        with self._conn() as conn:
            row = conn.execute(
                "SELECT payload FROM tracking_events WHERE type='location' "
                "ORDER BY ts DESC LIMIT 1"
            ).fetchone()
        if not row:
            return None
        try:
            return json.loads(row["payload"])
        except Exception:
            return None

    def get_summary(self, hours: int = 8) -> dict:
        """Kompakte Zusammenfassung für Donna-Kontext-Injection."""
        events = self.get_recent(hours=hours)

        last_loc = next(
            (e for e in events if e["type"] == "location"), None
        )

        # App-Nutzung aggregieren
        app_times: dict[str, int] = {}
        for ev in events:
            if ev["type"] != "activity":
                continue
            for app in ev.get("apps", []):
                pkg = app.get("package", "")
                usage = int(app.get("usage_ms", 0))
                app_times[pkg] = app_times.get(pkg, 0) + usage

        top_apps = sorted(app_times.items(), key=lambda x: x[1], reverse=True)[:8]

        return {
            "last_location": {
                "lat": last_loc.get("lat"),
                "lon": last_loc.get("lon"),
                "accuracy": last_loc.get("accuracy"),
                "ts": last_loc.get("ts"),
            } if last_loc else None,
            "top_apps_8h": [
                {"package": k, "usage_min": round(v / 60000)}
                for k, v in top_apps
            ],
            "event_count": len(events),
            "window_hours": hours,
        }

    def get_screen_context(self, hours: int = 4) -> dict:
        """Fasst Screen-Events zusammen: welche Apps + was gelesen/gesehen wurde."""
        events = self.get_recent(hours=hours)
        screen_events = [e for e in events if e.get("type") == "screen"]

        if not screen_events:
            return {"apps": [], "snippets": [], "event_count": 0}

        # Pro App: alle Content-Snippets zusammenführen
        app_content: dict[str, list[str]] = {}
        for ev in screen_events:
            app = ev.get("app", ev.get("package", "unbekannt"))
            content = ev.get("content", "")
            if content:
                app_content.setdefault(app, []).append(content[:300])

        # Top-Apps nach Häufigkeit
        app_counts = {app: len(snippets) for app, snippets in app_content.items()}
        top_apps = sorted(app_counts.items(), key=lambda x: x[1], reverse=True)[:8]

        # Kompakte Zusammenfassung je App (max 2 Snippets)
        summaries = []
        for app, _ in top_apps:
            snippets = app_content[app][:2]
            summaries.append({
                "app": app,
                "snippets": snippets,
                "visits": app_counts[app],
            })

        return {
            "apps": [a for a, _ in top_apps],
            "details": summaries,
            "event_count": len(screen_events),
            "window_hours": hours,
        }

    # ── Maintenance ───────────────────────────────────────────────────────

    def cleanup_old_events(self, days: int = 7) -> int:
        """Löscht Events älter als N Tage."""
        since = (datetime.now(timezone.utc) - timedelta(days=days)).isoformat()
        with self._conn() as conn:
            cur = conn.execute(
                "DELETE FROM tracking_events WHERE ts < ?", (since,)
            )
            deleted = cur.rowcount
        if deleted:
            log.info("tracking_cleanup_done", deleted=deleted)
        return deleted

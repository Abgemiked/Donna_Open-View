"""STMService — session-based short-term memory with SQLite backend.

Stores the recent N messages per session so Donna can maintain conversational
context across multiple chat requests within the same session.

Design decisions:
- SQLite via aiosqlite (async, no Redis, no external cache)
- TTL filter: get_context excludes messages older than `ttl_hours` (default 2h)
- Cleanup: cleanup_old_messages() removes entries older than `max_age_hours` (24h)
- Thread-safe: all access through aiosqlite's async context manager
"""
from __future__ import annotations

import time
from pathlib import Path

import aiosqlite

from app.core.logger import get_logger

log = get_logger("stm_service")

# Module-level defaults — used both as __init__ defaults and for documentation
_TTL_HOURS: float = 2.0       # Messages older than this are excluded from context
_MAX_AGE_HOURS: float = 24.0  # Messages older than this are deleted on cleanup
_VALID_ROLES: frozenset[str] = frozenset({"user", "assistant", "system"})

_CREATE_TABLE_SQL = """
CREATE TABLE IF NOT EXISTS stm_messages (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT    NOT NULL,
    role       TEXT    NOT NULL,
    content    TEXT    NOT NULL,
    created_at REAL    NOT NULL
);
"""

_CREATE_INDEX_SQL = """
CREATE INDEX IF NOT EXISTS idx_stm_session_created
    ON stm_messages (session_id, created_at);
"""


class STMService:
    """Async SQLite-backed short-term memory for chat sessions."""

    def __init__(
        self,
        db_path: str = "/data/stm.db",
        ttl_hours: float = _TTL_HOURS,
        max_age_hours: float = _MAX_AGE_HOURS,
    ) -> None:
        self.db_path = db_path
        self._ttl_sec = ttl_hours * 3600.0
        self._max_age_sec = max_age_hours * 3600.0

    async def init(self) -> None:
        """Create DB directory + table if not present. Enables WAL mode.

        DONNA-42 C: Migrates schema to add `synced_to_vault_at REAL` if missing
        (used by stm_obsidian_sync to track which messages have been written
        to the Obsidian vault).
        """
        path = Path(self.db_path)
        path.parent.mkdir(parents=True, exist_ok=True)

        async with aiosqlite.connect(self.db_path) as db:
            await db.execute("PRAGMA journal_mode=WAL")
            await db.execute(_CREATE_TABLE_SQL)
            await db.execute(_CREATE_INDEX_SQL)
            # Migration: synced_to_vault_at-Spalte hinzufügen falls nicht vorhanden
            cursor = await db.execute("PRAGMA table_info(stm_messages)")
            cols = [row[1] for row in await cursor.fetchall()]
            if "synced_to_vault_at" not in cols:
                await db.execute(
                    "ALTER TABLE stm_messages ADD COLUMN synced_to_vault_at REAL"
                )
                log.info("stm_schema_migrated_synced_to_vault_at")
            await db.commit()

        log.info("stm_service_ready", db_path=self.db_path)

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    async def add_message(self, session_id: str, role: str, content: str) -> None:
        """Persist a single message for the given session.

        Raises ValueError for unknown roles to prevent garbage data in context.
        """
        if role not in _VALID_ROLES:
            raise ValueError(f"Invalid role {role!r}. Must be one of: {_VALID_ROLES}")
        now = time.time()
        async with aiosqlite.connect(self.db_path) as db:
            await db.execute(
                "PRAGMA journal_mode=WAL",
            )
            await db.execute(
                "INSERT INTO stm_messages (session_id, role, content, created_at) "
                "VALUES (?, ?, ?, ?)",
                (session_id, role, content, now),
            )
            await db.commit()
        log.debug("stm_message_added", session_id=session_id, role=role)

    async def get_context(
        self,
        session_id: str,
        max_messages: int = 10,
    ) -> list[dict[str, str]]:
        """Return the most recent *max_messages* within the TTL window.

        Returns messages in chronological order (oldest first), suitable for
        passing directly as a history list to the LLM.
        """
        cutoff = time.time() - self._ttl_sec
        async with aiosqlite.connect(self.db_path) as db:
            db.row_factory = aiosqlite.Row
            # Subquery: pick the N most recent within TTL, then re-order asc
            async with db.execute(
                """
                SELECT role, content FROM (
                    SELECT role, content, created_at
                    FROM stm_messages
                    WHERE session_id = ? AND created_at >= ?
                    ORDER BY created_at DESC
                    LIMIT ?
                )
                ORDER BY created_at ASC
                """,
                (session_id, cutoff, max_messages),
            ) as cursor:
                rows = await cursor.fetchall()

        return [{"role": row["role"], "content": row["content"]} for row in rows]

    async def delete_session(self, session_id: str) -> int:
        """Delete all messages for a session. Returns number of rows deleted."""
        async with aiosqlite.connect(self.db_path) as db:
            cursor = await db.execute(
                "DELETE FROM stm_messages WHERE session_id = ?",
                (session_id,),
            )
            await db.commit()
            deleted = cursor.rowcount

        log.info("stm_session_deleted", session_id=session_id, rows=deleted)
        return deleted

    async def list_sessions(self, max_age_hours: float = 24.0) -> list[dict]:
        """Return sessions from the last max_age_hours, newest first.

        Each entry: session_id, started_at (unix ts), preview (first user msg, ≤80 chars), message_count.
        """
        cutoff = time.time() - max_age_hours * 3600.0
        async with aiosqlite.connect(self.db_path) as db:
            db.row_factory = aiosqlite.Row
            async with db.execute(
                """
                SELECT
                    m.session_id,
                    MIN(m.created_at) AS started_at,
                    COUNT(*) AS message_count,
                    (
                        SELECT sub.content FROM stm_messages sub
                        WHERE sub.session_id = m.session_id AND sub.role = 'user'
                        ORDER BY sub.created_at ASC LIMIT 1
                    ) AS preview
                FROM stm_messages m
                WHERE m.created_at >= ?
                GROUP BY m.session_id
                ORDER BY started_at DESC
                """,
                (cutoff,),
            ) as cursor:
                rows = await cursor.fetchall()
        return [
            {
                "session_id": row["session_id"],
                "started_at": row["started_at"],
                "message_count": row["message_count"],
                "preview": (row["preview"] or "")[:80],
            }
            for row in rows
        ]

    async def get_session_messages(
        self,
        session_id: str,
        max_messages: int = 50,
    ) -> list[dict[str, str]]:
        """Return all messages for a session without TTL filter (for history display)."""
        async with aiosqlite.connect(self.db_path) as db:
            db.row_factory = aiosqlite.Row
            async with db.execute(
                """
                SELECT role, content FROM stm_messages
                WHERE session_id = ?
                ORDER BY created_at ASC
                LIMIT ?
                """,
                (session_id, max_messages),
            ) as cursor:
                rows = await cursor.fetchall()
        return [{"role": row["role"], "content": row["content"]} for row in rows]

    async def get_all_sessions(self, hours: int = 24) -> dict[str, list[dict[str, str]]]:
        """Gibt alle Sessions der letzten N Stunden zurück: {session_id: [messages]}.

        Wird vom STM→LTM Promotion-Job (DONNA-14) genutzt.
        Messages sind chronologisch (oldest first), enthalten role+content.
        """
        cutoff = time.time() - hours * 3600.0
        sessions: dict[str, list[dict[str, str]]] = {}
        async with aiosqlite.connect(self.db_path) as db:
            db.row_factory = aiosqlite.Row
            async with db.execute(
                """
                SELECT session_id, role, content, created_at
                FROM stm_messages
                WHERE created_at >= ?
                ORDER BY session_id ASC, created_at ASC
                """,
                (cutoff,),
            ) as cursor:
                rows = await cursor.fetchall()
        for row in rows:
            sid = row["session_id"]
            sessions.setdefault(sid, []).append(
                {"role": row["role"], "content": row["content"]}
            )
        return sessions

    async def cleanup_old_messages(self) -> int:
        """Remove all entries older than max_age_hours. Returns rows deleted."""
        cutoff = time.time() - self._max_age_sec
        async with aiosqlite.connect(self.db_path) as db:
            await db.execute("PRAGMA journal_mode=WAL")
            cursor = await db.execute(
                "DELETE FROM stm_messages WHERE created_at < ?",
                (cutoff,),
            )
            await db.commit()
            deleted = cursor.rowcount

        log.info("stm_cleanup_done", deleted_rows=deleted)
        return deleted

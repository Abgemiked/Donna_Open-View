"""stm_obsidian_sync.py — bridge from STM-SQLite to Obsidian /vault/stm (DONNA-42 C).

Hintergrund: STMService schreibt jede Chat-Message in `/data/appdata/stm.db`,
der Consolidation-Job liest aber aus `/vault/stm/*.md`. Ohne diese Brücke
sind Obsidian-STM/LTM faktisch leer und die Memories sind nur in der DB
sichtbar — die lokale KI kann sie nicht vernetzen, der wöchentliche
Consolidation-Job promotet nichts zu LTM.

Was dieser Service macht:
1. Liest alle STM-Messages mit `synced_to_vault_at IS NULL`
2. Gruppiert sie nach (Session-ID, Datum)
3. Schreibt pro Gruppe eine Markdown-Datei `/vault/stm/<YYYY-MM-DD>_<session-slug>.md`
   (idempotent — bei erneutem Sync werden vorhandene Files überschrieben mit
   der vollständigen Session-History dieses Tages)
4. Markiert die Messages als `synced_to_vault_at = NOW()`

Trigger:
- Beim Append einer neuen Message (fire-and-forget Task in STMService)
- Per Hintergrund-Task alle 5 Min (Backfill, Recovery)
- Beim Backend-Start einmalig (Initial-Backfill der bestehenden 242 Messages)
"""
from __future__ import annotations

import asyncio
import time
from datetime import datetime, timezone
from pathlib import Path

import aiosqlite

from app.core.logger import get_logger

log = get_logger("stm_obsidian_sync")


def _local_date_str(epoch: float) -> str:
    """Wandelt Epoch in lokales (Server-)Datum YYYY-MM-DD um."""
    return datetime.fromtimestamp(epoch).strftime("%Y-%m-%d")


def _local_time_str(epoch: float) -> str:
    """Wandelt Epoch in lokale Uhrzeit HH:MM um."""
    return datetime.fromtimestamp(epoch).strftime("%H:%M")


def _safe_session_slug(session_id: str, max_len: int = 24) -> str:
    """Macht eine Session-ID dateinamen-tauglich (nur a-z0-9 + Bindestrich)."""
    cleaned = "".join(c if c.isalnum() else "-" for c in session_id.lower())
    return cleaned[:max_len].strip("-") or "session"


class StmObsidianSync:
    """Sync-Service: STM-SQLite → Obsidian-/vault/stm/."""

    def __init__(self, stm_db_path: str, vault_root: str) -> None:
        self._db_path = stm_db_path
        self._vault_root = Path(vault_root)
        self._stm_dir = self._vault_root / "stm"
        self._lock = asyncio.Lock()  # serialisiert konkurrierende Sync-Calls
        self._running_task: asyncio.Task | None = None

    def _ensure_stm_dir(self) -> None:
        self._stm_dir.mkdir(parents=True, exist_ok=True)

    async def sync_unsynced(self, batch_limit: int = 500) -> int:
        """Synchronisiert ungesyncte STM-Messages in den Obsidian-Vault.

        Returns: Anzahl der gesyncten Messages (0 wenn nichts zu tun).
        """
        async with self._lock:
            self._ensure_stm_dir()
            now = time.time()
            # Sammle ungesyncte Messages
            async with aiosqlite.connect(self._db_path) as db:
                db.row_factory = aiosqlite.Row
                cursor = await db.execute(
                    """
                    SELECT id, session_id, role, content, created_at
                    FROM stm_messages
                    WHERE synced_to_vault_at IS NULL
                    ORDER BY created_at ASC
                    LIMIT ?
                    """,
                    (batch_limit,),
                )
                rows = await cursor.fetchall()
                if not rows:
                    return 0

                # Welche (session, date)-Kombinationen sind betroffen?
                affected: set[tuple[str, str]] = set()
                for row in rows:
                    affected.add((row["session_id"], _local_date_str(row["created_at"])))

                # Pro Gruppe: lade ALLE Messages dieses Tages für diese Session
                # (auch bereits gesyncte) und schreibe komplette Datei neu →
                # idempotent, immer aktueller Stand pro (session, date).
                for session_id, date_str in affected:
                    cursor = await db.execute(
                        """
                        SELECT id, role, content, created_at
                        FROM stm_messages
                        WHERE session_id = ?
                          AND date(created_at, 'unixepoch', 'localtime') = ?
                        ORDER BY created_at ASC
                        """,
                        (session_id, date_str),
                    )
                    session_rows = await cursor.fetchall()
                    if not session_rows:
                        continue

                    md = self._render_session_markdown(
                        session_id=session_id,
                        date_str=date_str,
                        rows=session_rows,
                    )
                    filename = f"{date_str}_{_safe_session_slug(session_id)}.md"
                    target = self._stm_dir / filename
                    target.write_text(md, encoding="utf-8")

                # Markiere die ungesyncten als gesynct
                ids = [row["id"] for row in rows]
                placeholders = ",".join("?" * len(ids))
                await db.execute(
                    f"UPDATE stm_messages SET synced_to_vault_at = ? WHERE id IN ({placeholders})",
                    [now, *ids],
                )
                await db.commit()

            synced_count = len(rows)
            log.info(
                "stm_obsidian_sync_done",
                messages=synced_count,
                files=len(affected),
            )
            return synced_count

    def _render_session_markdown(
        self,
        *,
        session_id: str,
        date_str: str,
        rows: list,
    ) -> str:
        """Rendert eine Session als Markdown.

        Format:
            ---
            session_id: "..."
            date: "2026-04-27"
            message_count: 12
            ---
            # STM — <session-id> — 27.04.2026

            ## 18:08 user
            was streamt mike am Montag

            ## 18:08 assistant
            Montag: 11:30–18:00
        """
        lines: list[str] = []
        # Frontmatter
        lines.append("---")
        lines.append(f'session_id: "{session_id}"')
        lines.append(f'date: "{date_str}"')
        lines.append(f"message_count: {len(rows)}")
        lines.append(f'first_seen: "{datetime.fromtimestamp(rows[0]["created_at"]).isoformat()}"')
        lines.append(f'last_seen: "{datetime.fromtimestamp(rows[-1]["created_at"]).isoformat()}"')
        lines.append("---")
        lines.append("")
        lines.append(f"# STM — `{session_id}` — {date_str}")
        lines.append("")
        for row in rows:
            ts = _local_time_str(row["created_at"])
            role_label = {"user": "🙂 user", "assistant": "🤖 donna", "system": "⚙️ system"}.get(
                row["role"], row["role"]
            )
            lines.append(f"## {ts} — {role_label}")
            lines.append("")
            content = row["content"].rstrip()
            # Ohne Code-Fences damit Obsidian-Graph + Wiki-Links funktionieren
            for content_line in content.splitlines() or [""]:
                lines.append(content_line)
            lines.append("")
        return "\n".join(lines).rstrip() + "\n"

    def start_background_loop(self, interval_sec: float = 300.0) -> asyncio.Task:
        """Startet eine Hintergrund-Schleife die alle interval_sec syncs läuft."""
        if self._running_task and not self._running_task.done():
            return self._running_task

        async def _loop() -> None:
            log.info("stm_obsidian_sync_loop_started", interval_sec=interval_sec)
            try:
                # Initial-Backfill beim Start
                await self.sync_unsynced(batch_limit=10000)
                while True:
                    await asyncio.sleep(interval_sec)
                    try:
                        await self.sync_unsynced()
                    except Exception as exc:  # noqa: BLE001
                        log.error("stm_obsidian_sync_iteration_failed", error=str(exc))
            except asyncio.CancelledError:
                log.info("stm_obsidian_sync_loop_cancelled")
                raise

        self._running_task = asyncio.create_task(_loop())
        return self._running_task

    async def stop(self) -> None:
        if self._running_task and not self._running_task.done():
            self._running_task.cancel()
            try:
                await self._running_task
            except asyncio.CancelledError:
                pass

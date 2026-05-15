"""Tests für DONNA-42 C: STM-SQLite → Obsidian-Vault Sync."""
from __future__ import annotations

import asyncio
import os
import tempfile
from pathlib import Path

import pytest

from app.services.stm_obsidian_sync import (
    StmObsidianSync,
    _safe_session_slug,
    _local_date_str,
)
from app.services.stm_service import STMService


@pytest.fixture
async def stm_with_sync():
    """STM-Service + Sync-Service mit isolierter Test-DB + temp Vault."""
    tmp_db = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
    tmp_db.close()
    tmp_vault = tempfile.mkdtemp(prefix="vault_test_")
    stm = STMService(db_path=tmp_db.name)
    await stm.init()
    sync = StmObsidianSync(stm_db_path=tmp_db.name, vault_root=tmp_vault)
    yield stm, sync, Path(tmp_vault)
    # Cleanup
    try:
        os.unlink(tmp_db.name)
    except OSError:
        pass
    import shutil
    shutil.rmtree(tmp_vault, ignore_errors=True)


# ── Helper-Funktionen ────────────────────────────────────────────────────────

class TestHelpers:
    def test_safe_session_slug_alphanumeric(self):
        assert _safe_session_slug("abc123") == "abc123"

    def test_safe_session_slug_strips_special(self):
        assert _safe_session_slug("smogek08cgwah60") == "smogek08cgwah60"

    def test_safe_session_slug_with_special_chars(self):
        # Sonderzeichen werden zu Bindestrichen
        assert _safe_session_slug("session/with:bad chars") == "session-with-bad-chars"

    def test_safe_session_slug_truncates(self):
        long_id = "a" * 100
        assert len(_safe_session_slug(long_id)) <= 24

    def test_local_date_str_format(self):
        # Sicher epoch testen
        result = _local_date_str(1777284576.0)
        assert len(result) == 10  # YYYY-MM-DD
        assert result[4] == "-"
        assert result[7] == "-"


# ── Sync-Verhalten ───────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_sync_creates_md_file(stm_with_sync):
    stm, sync, vault = stm_with_sync
    await stm.add_message("session1", "user", "Hallo Donna")
    await stm.add_message("session1", "assistant", "Hi Mike")

    count = await sync.sync_unsynced()
    assert count == 2

    stm_dir = vault / "stm"
    files = list(stm_dir.glob("*.md"))
    assert len(files) == 1
    md = files[0].read_text(encoding="utf-8")
    assert "Hallo Donna" in md
    assert "Hi Mike" in md
    assert "session1" in md


@pytest.mark.asyncio
async def test_sync_idempotent(stm_with_sync):
    """Zweiter Sync ohne neue Messages → 0 Messages, gleiche Datei bleibt."""
    stm, sync, vault = stm_with_sync
    await stm.add_message("s1", "user", "test")
    first = await sync.sync_unsynced()
    second = await sync.sync_unsynced()
    assert first == 1
    assert second == 0


@pytest.mark.asyncio
async def test_sync_marks_as_synced(stm_with_sync):
    """Nach Sync: synced_to_vault_at gesetzt → kein erneutes Sync für gleiche Message."""
    stm, sync, _ = stm_with_sync
    await stm.add_message("s1", "user", "msg1")
    await sync.sync_unsynced()

    import aiosqlite
    async with aiosqlite.connect(stm.db_path) as db:
        cursor = await db.execute(
            "SELECT synced_to_vault_at FROM stm_messages WHERE content=?",
            ("msg1",),
        )
        row = await cursor.fetchone()
    assert row[0] is not None  # timestamp gesetzt


@pytest.mark.asyncio
async def test_sync_separate_files_per_session(stm_with_sync):
    """Zwei verschiedene Sessions → zwei verschiedene MD-Files."""
    stm, sync, vault = stm_with_sync
    await stm.add_message("alice", "user", "frage A")
    await stm.add_message("bob", "user", "frage B")

    await sync.sync_unsynced()
    files = list((vault / "stm").glob("*.md"))
    assert len(files) == 2

    # Jede Datei enthält nur ihre eigene Session
    contents = {f.name: f.read_text(encoding="utf-8") for f in files}
    alice_file = next(f for f in files if "alice" in f.name)
    bob_file = next(f for f in files if "bob" in f.name)
    assert "frage A" in alice_file.read_text(encoding="utf-8")
    assert "frage B" in bob_file.read_text(encoding="utf-8")
    # Cross-Isolation: Alices Frage steht nicht in Bobs Datei
    assert "frage A" not in bob_file.read_text(encoding="utf-8")
    assert "frage B" not in alice_file.read_text(encoding="utf-8")


@pytest.mark.asyncio
async def test_sync_appends_new_messages_to_same_file(stm_with_sync):
    """Neue Message in gleicher Session+Tag → File wird mit ALLEN Messages neu geschrieben."""
    stm, sync, vault = stm_with_sync
    await stm.add_message("s1", "user", "msg1")
    await sync.sync_unsynced()
    await stm.add_message("s1", "assistant", "msg2-neu")
    await sync.sync_unsynced()

    files = list((vault / "stm").glob("*.md"))
    assert len(files) == 1
    md = files[0].read_text(encoding="utf-8")
    assert "msg1" in md
    assert "msg2-neu" in md


@pytest.mark.asyncio
async def test_sync_includes_frontmatter(stm_with_sync):
    """Markdown enthält YAML-Frontmatter mit session_id, date, message_count."""
    stm, sync, vault = stm_with_sync
    await stm.add_message("session1", "user", "test")
    await sync.sync_unsynced()
    files = list((vault / "stm").glob("*.md"))
    md = files[0].read_text(encoding="utf-8")
    assert md.startswith("---\n")
    assert 'session_id: "session1"' in md
    assert "message_count: 1" in md


@pytest.mark.asyncio
async def test_sync_empty_db_returns_zero(stm_with_sync):
    """Kein Eintrag in der DB → 0, keine Datei wird erstellt."""
    stm, sync, vault = stm_with_sync
    count = await sync.sync_unsynced()
    assert count == 0
    files = list((vault / "stm").glob("*.md"))
    assert len(files) == 0

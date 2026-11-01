"""Tests for STMService — session-based short-term memory with SQLite backend.

TDD: Tests written first, implementation follows.
"""
from __future__ import annotations

import asyncio
import time

import pytest
import pytest_asyncio

from app.services.stm_service import STMService


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest_asyncio.fixture
async def stm(tmp_path):
    """Fresh in-memory (tmp) STM instance per test."""
    db_path = str(tmp_path / "test_stm.db")
    service = STMService(db_path=db_path)
    await service.init()
    yield service


# ---------------------------------------------------------------------------
# Unit tests — STMService
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_add_and_get_messages(stm: STMService):
    """add_message stores messages; get_context returns them in order."""
    sid = "session-abc"
    await stm.add_message(sid, "user", "Hallo Donna")
    await stm.add_message(sid, "assistant", "Hallo! Wie kann ich helfen?")
    await stm.add_message(sid, "user", "Was ist 2+2?")

    ctx = await stm.get_context(sid)
    assert len(ctx) == 3
    assert ctx[0]["role"] == "user"
    assert ctx[0]["content"] == "Hallo Donna"
    assert ctx[1]["role"] == "assistant"
    assert ctx[2]["content"] == "Was ist 2+2?"


@pytest.mark.asyncio
async def test_ttl_filters_old_messages(stm: STMService):
    """Messages older than TTL (2h) are excluded from get_context."""
    sid = "session-ttl"
    # Insert a fresh message and an old one (manually via DB)
    await stm.add_message(sid, "user", "Aktuelle Nachricht")

    # Directly insert an expired row (created_at = now - 3h)
    import aiosqlite
    three_hours_ago = time.time() - (3 * 3600)
    async with aiosqlite.connect(stm.db_path) as db:
        await db.execute(
            "INSERT INTO stm_messages (session_id, role, content, created_at) "
            "VALUES (?, ?, ?, ?)",
            (sid, "user", "Alte Nachricht", three_hours_ago),
        )
        await db.commit()

    ctx = await stm.get_context(sid)
    contents = [m["content"] for m in ctx]
    assert "Aktuelle Nachricht" in contents
    assert "Alte Nachricht" not in contents


@pytest.mark.asyncio
async def test_cleanup_removes_old_entries(stm: STMService):
    """cleanup_old_messages() removes entries older than 24h."""
    import aiosqlite
    sid = "session-cleanup"
    await stm.add_message(sid, "user", "Frische Nachricht")

    # Insert entry older than 24h
    old_ts = time.time() - (25 * 3600)
    async with aiosqlite.connect(stm.db_path) as db:
        await db.execute(
            "INSERT INTO stm_messages (session_id, role, content, created_at) "
            "VALUES (?, ?, ?, ?)",
            (sid, "user", "Sehr alte Nachricht", old_ts),
        )
        await db.commit()

    await stm.cleanup_old_messages()

    # Only fresh message remains
    async with aiosqlite.connect(stm.db_path) as db:
        async with db.execute(
            "SELECT content FROM stm_messages WHERE session_id = ?", (sid,)
        ) as cursor:
            rows = await cursor.fetchall()

    contents = [r[0] for r in rows]
    assert "Frische Nachricht" in contents
    assert "Sehr alte Nachricht" not in contents


@pytest.mark.asyncio
async def test_session_isolation(stm: STMService):
    """Messages from different sessions are not mixed."""
    await stm.add_message("session-A", "user", "Nachricht von A")
    await stm.add_message("session-B", "user", "Nachricht von B")

    ctx_a = await stm.get_context("session-A")
    ctx_b = await stm.get_context("session-B")

    assert len(ctx_a) == 1
    assert ctx_a[0]["content"] == "Nachricht von A"
    assert len(ctx_b) == 1
    assert ctx_b[0]["content"] == "Nachricht von B"


@pytest.mark.asyncio
async def test_max_messages_limit(stm: STMService):
    """get_context respects max_messages — returns only the N most recent."""
    sid = "session-limit"
    for i in range(15):
        await stm.add_message(sid, "user", f"Nachricht {i}")

    ctx = await stm.get_context(sid, max_messages=5)
    assert len(ctx) == 5
    # Should return the 5 most recent (Nachricht 10–14)
    contents = [m["content"] for m in ctx]
    assert "Nachricht 14" in contents
    assert "Nachricht 0" not in contents


@pytest.mark.asyncio
async def test_delete_session(stm: STMService):
    """delete_session removes all messages for a session."""
    sid = "session-del"
    await stm.add_message(sid, "user", "Zu löschende Nachricht")
    await stm.add_message(sid, "assistant", "Antwort")

    await stm.delete_session(sid)

    ctx = await stm.get_context(sid)
    assert len(ctx) == 0

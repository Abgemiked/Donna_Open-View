"""Tests für _wrap_with_keepalive (SSE-Keep-Alive ohne den Stream zu killen)."""
from __future__ import annotations

import asyncio

from app.routes.chat import _KEEPALIVE_BYTES, _wrap_with_keepalive


async def _slow_gen(events: list[str], pause_s: float, state: dict):
    try:
        yield b"a"
        await asyncio.sleep(pause_s)
        state["survived_pause"] = True
        yield b"b"
    except asyncio.CancelledError:
        state["cancelled"] = True
        raise
    finally:
        state["closed"] = True


async def test_keepalive_during_long_pause_then_real_chunk_not_lost():
    state: dict = {}
    out = [c async for c in _wrap_with_keepalive(_slow_gen([], 0.35, state), interval=0.1)]

    assert out[0] == b"a"
    assert out[-1] == b"b"  # echter Chunk geht NICHT verloren
    assert out.count(_KEEPALIVE_BYTES) >= 2
    assert state.get("survived_pause") is True
    assert "cancelled" not in state  # Generator wurde nie abgebrochen


async def test_no_keepalive_when_chunks_are_fast():
    async def fast():
        for i in range(3):
            yield bytes([65 + i])

    out = [c async for c in _wrap_with_keepalive(fast(), interval=1.0)]
    assert out == [b"A", b"B", b"C"]


async def test_early_close_cancels_pending_task_and_closes_gen():
    state: dict = {}
    wrapped = _wrap_with_keepalive(_slow_gen([], 5.0, state), interval=0.05)
    assert await wrapped.__anext__() == b"a"
    assert await wrapped.__anext__() == _KEEPALIVE_BYTES
    await wrapped.aclose()

    assert state.get("cancelled") is True
    assert state.get("closed") is True

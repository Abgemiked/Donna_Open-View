"""Tests for NonceStore (backend/app/core/nonce_store.py).

TDD: Tests written before implementation.
"""
from __future__ import annotations

import asyncio
import time

import pytest

from app.core.nonce_store import NonceStore


class TestNonceAddAndConsumeOnce:
    """test_nonce_add_and_consume_once — A nonce can only be consumed once."""

    @pytest.mark.asyncio
    async def test_nonce_add_and_consume_once(self) -> None:
        store = NonceStore()
        nonce = "unique-nonce-abc-123"
        ttl = 30

        # First: add the nonce
        added = await store.add(nonce, ttl)
        assert added is True, "add() must return True for a new nonce"

        # First consume: must succeed
        consumed_first = await store.consume(nonce)
        assert consumed_first is True, "First consume() must succeed"

        # Second consume: must fail (single-use)
        consumed_second = await store.consume(nonce)
        assert consumed_second is False, "Second consume() must fail — nonce is single-use"

    @pytest.mark.asyncio
    async def test_add_duplicate_nonce_rejected(self) -> None:
        """Adding the same nonce twice must be rejected (replay protection)."""
        store = NonceStore()
        nonce = "replay-nonce-xyz"

        first = await store.add(nonce, ttl_sec=30)
        assert first is True

        second = await store.add(nonce, ttl_sec=30)
        assert second is False, "Duplicate nonce must be rejected"


class TestNonceExpiry:
    """test_nonce_expires_after_ttl — Expired nonces cannot be consumed."""

    @pytest.mark.asyncio
    async def test_nonce_expires_after_ttl(self) -> None:
        store = NonceStore()
        nonce = "expiring-nonce"

        await store.add(nonce, ttl_sec=1)

        # Wait for TTL to pass
        await asyncio.sleep(1.1)

        # Should be expired now
        consumed = await store.consume(nonce)
        assert consumed is False, "Expired nonce must not be consumable"

    @pytest.mark.asyncio
    async def test_nonce_valid_within_ttl(self) -> None:
        """Nonce is consumable within its TTL window."""
        store = NonceStore()
        nonce = "fresh-nonce"

        await store.add(nonce, ttl_sec=30)
        consumed = await store.consume(nonce)
        assert consumed is True, "Fresh nonce within TTL must be consumable"


class TestNonceCleanup:
    """test_nonce_cleanup_removes_expired — cleanup_expired() removes stale entries."""

    @pytest.mark.asyncio
    async def test_nonce_cleanup_removes_expired(self) -> None:
        store = NonceStore()

        # Add one short-lived and one long-lived nonce
        await store.add("short", ttl_sec=1)
        await store.add("long", ttl_sec=60)

        await asyncio.sleep(1.1)
        await store.cleanup_expired()

        # "short" is expired and cleaned up — cannot consume
        short_ok = await store.consume("short")
        assert short_ok is False, "Cleaned-up expired nonce must not be consumable"

        # "long" still alive
        long_ok = await store.consume("long")
        assert long_ok is True, "Non-expired nonce must survive cleanup"

    @pytest.mark.asyncio
    async def test_cleanup_empty_store_is_no_op(self) -> None:
        store = NonceStore()
        # Must not raise
        await store.cleanup_expired()


class TestNonceStoreConcurrency:
    """test_nonce_store_thread_safe — Concurrent access does not corrupt state."""

    @pytest.mark.asyncio
    async def test_nonce_store_thread_safe(self) -> None:
        """100 concurrent coroutines each add+consume a unique nonce — no corruption."""
        store = NonceStore()
        results: list[bool] = []

        async def add_and_consume(i: int) -> None:
            nonce = f"concurrent-nonce-{i}"
            added = await store.add(nonce, ttl_sec=30)
            if added:
                consumed = await store.consume(nonce)
                results.append(consumed)
            else:
                results.append(False)

        await asyncio.gather(*[add_and_consume(i) for i in range(100)])

        # All 100 should have succeeded (unique nonces, all consumed exactly once)
        assert len(results) == 100
        assert all(results), "All 100 unique nonces must be consumable exactly once"

    @pytest.mark.asyncio
    async def test_same_nonce_concurrent_consume(self) -> None:
        """Only one concurrent consume() wins — exactly one True, rest False."""
        store = NonceStore()
        nonce = "race-condition-nonce"
        await store.add(nonce, ttl_sec=30)

        # 10 coroutines race to consume the same nonce
        results = await asyncio.gather(*[store.consume(nonce) for _ in range(10)])
        trues = [r for r in results if r is True]
        assert len(trues) == 1, "Exactly one consume() must win the race"

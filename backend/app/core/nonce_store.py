"""Thread-safe In-Memory Nonce Store with TTL (Phase 3 — Voice-Auth Hardening).

Used for two purposes:
1. Nonces (single-use, short-lived) — replay protection for verify requests
2. Challenges (single-use, medium-lived) — liveness challenge IDs

Design:
- asyncio.Lock for all mutations (single-event-loop safe + thread-safe)
- Dict[nonce -> expiry_unix_float]
- consume() atomically checks + deletes (single-use guarantee)
- cleanup_expired() removes stale entries to bound memory growth
"""
from __future__ import annotations

import asyncio
import time


class NonceStore:
    """Async-safe in-memory store for single-use tokens with TTL.

    All operations are protected by asyncio.Lock to prevent race conditions
    in concurrent async contexts.
    """

    def __init__(self) -> None:
        self._store: dict[str, float] = {}  # token -> expiry_unix_timestamp
        self._lock: asyncio.Lock = asyncio.Lock()

    async def add(self, nonce: str, ttl_sec: int) -> bool:
        """Register a nonce with a time-to-live.

        Returns:
            True  — nonce was added successfully (it was unknown or expired)
            False — nonce already exists and is still valid (replay detected)
        """
        async with self._lock:
            now = time.time()
            existing_expiry = self._store.get(nonce)
            if existing_expiry is not None and existing_expiry > now:
                # Nonce is still alive — reject as duplicate
                return False
            self._store[nonce] = now + ttl_sec
            return True

    async def consume(self, nonce: str) -> bool:
        """Atomically check and remove a nonce (single-use guarantee).

        Returns:
            True  — nonce existed, was valid, and has been consumed
            False — nonce unknown, already consumed, or expired
        """
        async with self._lock:
            now = time.time()
            expiry = self._store.get(nonce)
            if expiry is None or expiry <= now:
                return False
            # Remove immediately — single-use
            del self._store[nonce]
            return True

    async def cleanup_expired(self) -> None:
        """Remove all expired entries from the store.

        Called periodically by a background task to bound memory usage.
        Safe to call concurrently — protected by lock.
        """
        async with self._lock:
            now = time.time()
            expired_keys = [k for k, exp in self._store.items() if exp <= now]
            for key in expired_keys:
                del self._store[key]

    async def size(self) -> int:
        """Return the current number of stored tokens (including expired ones)."""
        async with self._lock:
            return len(self._store)

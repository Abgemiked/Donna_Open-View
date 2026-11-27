"""Sliding-Window Rate Limiter with Cooldown (Phase 3 — Voice-Auth Hardening).

Algorithm:
- Sliding window: keeps a list of request timestamps per IP
- Window is defined by window_sec (e.g. 60s)
- If requests in window >= max_requests → trigger cooldown
- Cooldown: IP is blocked for cooldown_sec regardless of window

Failure recording:
- record_failure(ip) adds an extra attempt entry, making failures count
  more heavily against the rate limit (security intent: punish bad clients).

All operations protected by asyncio.Lock.
"""
from __future__ import annotations

import asyncio
import time


class SlidingWindowRateLimiter:
    """Per-IP sliding-window rate limiter with cooldown state.

    Args:
        max_requests: Maximum number of requests allowed within window_sec.
        window_sec:   Sliding window duration in seconds.
        cooldown_sec: How long an IP stays blocked after exceeding the limit.
    """

    def __init__(
        self,
        max_requests: int,
        window_sec: int,
        cooldown_sec: int,
    ) -> None:
        self._max_requests = max_requests
        self._window_sec = window_sec
        self._cooldown_sec = cooldown_sec

        # ip -> list of request timestamps (within window)
        self._requests: dict[str, list[float]] = {}
        # ip -> cooldown expiry timestamp
        self._cooldowns: dict[str, float] = {}
        self._lock: asyncio.Lock = asyncio.Lock()

    async def check(self, ip: str) -> tuple[bool, int]:
        """Check if IP is allowed and record the attempt.

        Returns:
            (True, 0)              — request allowed
            (False, retry_after)   — request blocked; retry_after is seconds to wait
        """
        async with self._lock:
            return self._check_and_record(ip)

    async def record_failure(self, ip: str) -> None:
        """Record an additional failure attempt for this IP.

        Called when auth checks fail (timestamp/nonce/challenge/hash) so that
        failed attempts count more heavily toward the rate limit.
        Does NOT check if the IP is currently blocked — just adds a timestamp.
        """
        async with self._lock:
            self._add_attempt(ip)

    # ------------------------------------------------------------------
    # Internal helpers (must be called under self._lock)
    # ------------------------------------------------------------------

    def _check_and_record(self, ip: str) -> tuple[bool, int]:
        """Internal: check cooldown → sliding window → record attempt."""
        now = time.time()

        # 1. Cooldown check
        cooldown_expiry = self._cooldowns.get(ip)
        if cooldown_expiry is not None and cooldown_expiry > now:
            retry_after = int(cooldown_expiry - now) + 1
            return False, retry_after

        # 2. Sliding window — prune old timestamps
        timestamps = self._requests.get(ip, [])
        cutoff = now - self._window_sec
        timestamps = [t for t in timestamps if t > cutoff]

        # 3. Check if already at max BEFORE adding the new attempt
        if len(timestamps) >= self._max_requests:
            # Trigger cooldown
            cooldown_until = now + self._cooldown_sec
            self._cooldowns[ip] = cooldown_until
            self._requests[ip] = []  # clear window after entering cooldown
            return False, self._cooldown_sec

        # 4. Allowed — record attempt
        timestamps.append(now)
        self._requests[ip] = timestamps
        return True, 0

    def _add_attempt(self, ip: str) -> None:
        """Internal: unconditionally add a timestamp entry (for failure recording)."""
        now = time.time()
        timestamps = self._requests.get(ip, [])
        cutoff = now - self._window_sec
        timestamps = [t for t in timestamps if t > cutoff]
        timestamps.append(now)
        self._requests[ip] = timestamps

    async def cleanup(self) -> None:
        """Remove stale IPs from internal state (called periodically)."""
        async with self._lock:
            now = time.time()
            # Remove expired cooldowns
            expired_cooldowns = [ip for ip, exp in self._cooldowns.items() if exp <= now]
            for ip in expired_cooldowns:
                del self._cooldowns[ip]
            # Remove IPs with no recent requests
            cutoff = now - self._window_sec
            stale_ips = [
                ip
                for ip, ts_list in self._requests.items()
                if not any(t > cutoff for t in ts_list)
            ]
            for ip in stale_ips:
                del self._requests[ip]

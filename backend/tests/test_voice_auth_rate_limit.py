"""Tests for SlidingWindowRateLimiter (backend/app/core/rate_limiter.py).

TDD: Tests written before implementation.
"""
from __future__ import annotations

import asyncio

import pytest

from app.core.rate_limiter import SlidingWindowRateLimiter


class TestRateLimitAllowsUnderThreshold:
    """test_rate_limit_allows_under_threshold — Requests under the limit pass."""

    @pytest.mark.asyncio
    async def test_rate_limit_allows_under_threshold(self) -> None:
        # Allow 5 requests per 60s window, 10-min cooldown
        limiter = SlidingWindowRateLimiter(max_requests=5, window_sec=60, cooldown_sec=600)
        ip = "YOUR_SERVER_IP"

        for i in range(4):
            allowed, retry_after = await limiter.check(ip)
            assert allowed is True, f"Request {i+1} should be allowed (under threshold)"
            assert retry_after == 0

    @pytest.mark.asyncio
    async def test_single_request_always_allowed(self) -> None:
        limiter = SlidingWindowRateLimiter(max_requests=5, window_sec=60, cooldown_sec=600)
        allowed, retry_after = await limiter.check("YOUR_SERVER_IP")
        assert allowed is True
        assert retry_after == 0

    @pytest.mark.asyncio
    async def test_different_ips_are_independent(self) -> None:
        """Each IP has its own sliding window — one blocked IP does not affect others."""
        limiter = SlidingWindowRateLimiter(max_requests=2, window_sec=60, cooldown_sec=600)

        # Exhaust IP A
        await limiter.check("YOUR_SERVER_IP")
        await limiter.check("YOUR_SERVER_IP")
        blocked, _ = await limiter.check("YOUR_SERVER_IP")
        assert blocked is False, "IP A should be blocked after exceeding limit"

        # IP B unaffected
        allowed, _ = await limiter.check("YOUR_SERVER_IP")
        assert allowed is True, "IP B should not be affected by IP A's rate limit"


class TestRateLimitBlocksOverThreshold:
    """test_rate_limit_blocks_over_threshold — Request at max+1 is rejected with 429."""

    @pytest.mark.asyncio
    async def test_rate_limit_blocks_over_threshold(self) -> None:
        limiter = SlidingWindowRateLimiter(max_requests=3, window_sec=60, cooldown_sec=600)
        ip = "YOUR_SERVER_IP"

        # Use up all 3 slots
        for _ in range(3):
            allowed, _ = await limiter.check(ip)
            assert allowed is True

        # 4th request: should be blocked
        allowed, retry_after = await limiter.check(ip)
        assert allowed is False, "Request exceeding limit must be blocked"
        assert retry_after > 0, "retry_after must be positive when blocked"

    @pytest.mark.asyncio
    async def test_blocked_ip_returns_retry_after(self) -> None:
        limiter = SlidingWindowRateLimiter(max_requests=1, window_sec=60, cooldown_sec=300)
        ip = "YOUR_SERVER_IP"

        await limiter.check(ip)  # exhaust
        allowed, retry_after = await limiter.check(ip)  # blocked

        assert allowed is False
        assert retry_after > 0, "Must tell client how long to wait"
        assert retry_after <= 300 + 1, "retry_after must not exceed cooldown_sec"


class TestRateLimitCooldown:
    """test_rate_limit_cooldown_active — After blocking, IP enters cooldown state."""

    @pytest.mark.asyncio
    async def test_rate_limit_cooldown_active(self) -> None:
        """After threshold exceeded, IP is in cooldown and all further requests blocked."""
        limiter = SlidingWindowRateLimiter(max_requests=2, window_sec=60, cooldown_sec=900)
        ip = "YOUR_SERVER_IP"

        # Exhaust + trigger block
        await limiter.check(ip)
        await limiter.check(ip)
        await limiter.check(ip)  # triggers cooldown

        # Multiple subsequent requests all blocked during cooldown
        for _ in range(3):
            allowed, retry_after = await limiter.check(ip)
            assert allowed is False, "All requests during cooldown must be blocked"
            assert retry_after > 0

    @pytest.mark.asyncio
    async def test_cooldown_expires_and_ip_is_unblocked(self) -> None:
        """After short cooldown, IP is unblocked again."""
        limiter = SlidingWindowRateLimiter(max_requests=1, window_sec=60, cooldown_sec=1)
        ip = "YOUR_SERVER_IP"

        await limiter.check(ip)  # exhaust
        await limiter.check(ip)  # triggers 1s cooldown

        # Still blocked immediately
        allowed, _ = await limiter.check(ip)
        assert allowed is False

        # After cooldown expires
        await asyncio.sleep(1.1)
        allowed_after, retry_after = await limiter.check(ip)
        assert allowed_after is True, "IP must be unblocked after cooldown expires"

    @pytest.mark.asyncio
    async def test_record_failure_increments_counter(self) -> None:
        """record_failure() counts toward the rate limit."""
        limiter = SlidingWindowRateLimiter(max_requests=3, window_sec=60, cooldown_sec=600)
        ip = "YOUR_SERVER_IP"

        # 2 normal checks + 1 failure record = 3 total
        await limiter.check(ip)
        await limiter.check(ip)
        await limiter.record_failure(ip)

        # Next check must be blocked (3 already recorded)
        allowed, _ = await limiter.check(ip)
        assert allowed is False, "Failure count must push over the threshold"

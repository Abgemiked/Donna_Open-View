"""Voice-Auth Business Logic — Phase 3 Hardening Layer.

Implements the hardened auth flow WITHOUT real voice biometrics (Phase 4).
Phase 3 only validates:
  1. Rate-Limit (sliding window per IP)
  2. Timestamp skew (max ±30s)
  3. Nonce (single-use, TTL=30s)
  4. Challenge (single-use, TTL=60s)
  5. Audio hash format (SHA-256 hex, 64 chars)

Real voice embedding + cosine-similarity → Phase 4.

All failures are logged structurally (structlog) with:
  event, ip, reason, challenge_id, nonce
"""
from __future__ import annotations

import random
import time
import uuid
from dataclasses import dataclass

import structlog

from app.core.nonce_store import NonceStore
from app.core.rate_limiter import SlidingWindowRateLimiter
from app.data.challenge_phrases import PHRASES
from app.schemas.voice_auth import ChallengeResponse, VerifyRequest, VerifyResponse

log = structlog.get_logger("voice_auth_service")

# Default settings (overridden by VoiceAuthService constructor parameters,
# which in turn are fed from app.config.Settings in production)
_DEFAULT_RATE_LIMIT = 5
_DEFAULT_WINDOW_SEC = 60
_DEFAULT_COOLDOWN_SEC = 15 * 60  # 15 minutes
_DEFAULT_NONCE_TTL_SEC = 30
_DEFAULT_CHALLENGE_TTL_SEC = 60
_DEFAULT_TIMESTAMP_SKEW_SEC = 30


@dataclass
class VoiceAuthError(Exception):
    """Raised when voice-auth verification fails at any step.

    Attributes:
        reason:      Machine-readable failure code (e.g. "rate_limited", "nonce_replay").
        http_status: HTTP status code to return to the client (401, 400, 429).
        message:     Human-readable explanation (included in JSON response body).
        retry_after: Seconds the client must wait before retrying (0 = no restriction).
    """

    reason: str
    http_status: int
    message: str
    retry_after: int = 0


class VoiceAuthService:
    """Handles challenge generation and multi-step verify flow.

    Designed to be instantiated once (app.state.voice_auth) and reused.
    All async operations are safe for concurrent FastAPI requests.
    """

    def __init__(
        self,
        rate_limit: int = _DEFAULT_RATE_LIMIT,
        window_sec: int = _DEFAULT_WINDOW_SEC,
        cooldown_sec: int = _DEFAULT_COOLDOWN_SEC,
        nonce_ttl_sec: int = _DEFAULT_NONCE_TTL_SEC,
        challenge_ttl_sec: int = _DEFAULT_CHALLENGE_TTL_SEC,
        timestamp_skew_sec: int = _DEFAULT_TIMESTAMP_SKEW_SEC,
    ) -> None:
        self._timestamp_skew_sec = timestamp_skew_sec
        self._nonce_ttl_sec = nonce_ttl_sec
        self._challenge_ttl_sec = challenge_ttl_sec

        self._nonce_store = NonceStore()
        self._challenge_store = NonceStore()
        self._rate_limiter = SlidingWindowRateLimiter(
            max_requests=rate_limit,
            window_sec=window_sec,
            cooldown_sec=cooldown_sec,
        )

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    async def check_challenge_rate_limit(self, client_ip: str) -> tuple[bool, int]:
        """Check if the client IP is allowed to request a new challenge.

        Delegates to the shared rate limiter so the router never touches
        internal implementation details directly (SRP compliance).

        Returns:
            (True, 0)             — request allowed
            (False, retry_after)  — blocked; client must wait retry_after seconds
        """
        return await self._rate_limiter.check(client_ip)

    async def generate_challenge(self) -> ChallengeResponse:
        """Generate a fresh liveness challenge.

        Returns a unique UUID4 challenge_id + a random German phrase.
        The challenge_id is stored in the challenge store (single-use, TTL=60s).
        """
        challenge_id = str(uuid.uuid4())
        phrase = random.choice(PHRASES)  # noqa: S311 — not crypto, liveness only

        # Register the challenge in the store (single-use)
        await self._challenge_store.add(challenge_id, self._challenge_ttl_sec)

        log.info("voice_auth_challenge_issued", challenge_id=challenge_id)
        return ChallengeResponse(challenge_id=challenge_id, phrase=phrase)

    async def verify(self, req: VerifyRequest, client_ip: str) -> VerifyResponse:
        """Execute the multi-step verify flow (verbindliche Reihenfolge).

        Order (from Plan-MD):
          1. Rate-Limit-Check
          2. Timestamp-Skew-Check
          3. Nonce-Consume
          4. Challenge-Consume
          5. Audio-Hash-Format-Validation
          6. Return "ok"

        Raises:
            VoiceAuthError — on any failure (caller maps to HTTP response)
        """
        # ── 1. Rate-Limit ──────────────────────────────────────────────
        allowed, retry_after = await self._rate_limiter.check(client_ip)
        if not allowed:
            log.warning(
                "voice_auth_failed",
                ip=client_ip,
                reason="rate_limited",
                challenge_id=req.challenge_id,
                nonce=req.nonce,
            )
            raise VoiceAuthError(
                reason="rate_limited",
                http_status=429,
                message="Too many authentication attempts. Please wait.",
                retry_after=retry_after,
            )

        # ── 2. Timestamp Skew ─────────────────────────────────────────
        skew_error = self._check_timestamp_skew(req.timestamp)
        if skew_error is not None:
            await self._on_failure(client_ip, "timestamp_skew", req)
            raise VoiceAuthError(
                reason="timestamp_skew",
                http_status=401,
                message=skew_error,
            )

        # ── 3. Nonce Consume ──────────────────────────────────────────
        nonce_added = await self._nonce_store.add(req.nonce, self._nonce_ttl_sec)
        if not nonce_added:
            await self._on_failure(client_ip, "nonce_replay", req)
            raise VoiceAuthError(
                reason="nonce_replay",
                http_status=401,
                message="Nonce already used or expired. Replay attack rejected.",
            )

        # ── 4. Challenge Consume ──────────────────────────────────────
        challenge_ok = await self._challenge_store.consume(req.challenge_id)
        if not challenge_ok:
            await self._on_failure(client_ip, "challenge_expired_or_unknown", req)
            raise VoiceAuthError(
                reason="challenge_expired_or_unknown",
                http_status=401,
                message="Challenge invalid, expired, or already used.",
            )

        # ── 5. Audio Hash Format ──────────────────────────────────────
        # Pydantic already validates format in VerifyRequest.validate_audio_hash_format.
        # This is a belt-and-suspenders check in case the model is bypassed in tests.
        hash_error = self._validate_audio_hash(req.audio_hash)
        if hash_error is not None:
            await self._on_failure(client_ip, "invalid_audio_hash", req)
            raise VoiceAuthError(
                reason="invalid_audio_hash",
                http_status=400,
                message=hash_error,
            )

        # ── 6. Success ────────────────────────────────────────────────
        log.info(
            "voice_auth_success",
            ip=client_ip,
            challenge_id=req.challenge_id,
        )
        return VerifyResponse()

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _check_timestamp_skew(self, request_timestamp: float) -> str | None:
        """Return an error string if the timestamp is outside the allowed skew.

        Returns None if the timestamp is acceptable.
        """
        now = time.time()
        skew = abs(now - request_timestamp)
        if skew > self._timestamp_skew_sec:
            return (
                f"Request timestamp is too far from server time "
                f"(skew={skew:.1f}s, max={self._timestamp_skew_sec}s)."
            )
        return None

    def _validate_audio_hash(self, audio_hash: str) -> str | None:
        """Validate that audio_hash is a 64-char hex string.

        Returns None on success, error string on failure.
        Phase 3: format only — no real audio analysis.
        """
        cleaned = audio_hash.strip().lower()
        if len(cleaned) != 64:
            return "audio_hash must be a 64-character SHA-256 hex string."
        if not all(c in "0123456789abcdef" for c in cleaned):
            return "audio_hash must contain only hex characters."
        return None

    async def _on_failure(self, client_ip: str, reason: str, req: VerifyRequest) -> None:
        """Log structured failure and increment rate-limit counter."""
        log.warning(
            "voice_auth_failed",
            ip=client_ip,
            reason=reason,
            challenge_id=req.challenge_id,
            nonce=req.nonce,
        )
        await self._rate_limiter.record_failure(client_ip)

    # ------------------------------------------------------------------
    # Store accessors (for background cleanup task)
    # ------------------------------------------------------------------

    async def cleanup_stores(self) -> None:
        """Clean up expired entries from nonce and challenge stores."""
        await self._nonce_store.cleanup_expired()
        await self._challenge_store.cleanup_expired()

"""Tests for Voice-Auth Challenge lifecycle (timestamp + challenge generation + expiry).

TDD: Tests written before implementation.
"""
from __future__ import annotations

import asyncio
import hashlib
import time
import uuid

import pytest

from app.schemas.voice_auth import VerifyRequest


class TestTimestampSkew:
    """Timestamp skew validation — max 30s allowed deviation."""

    def _make_request(self, timestamp: float) -> VerifyRequest:
        """Helper: build a VerifyRequest with the given timestamp."""
        return VerifyRequest(
            challenge_id=str(uuid.uuid4()),
            nonce="test-nonce-12345678",
            timestamp=timestamp,
            audio_hash="a" * 64,
        )

    @pytest.mark.asyncio
    async def test_timestamp_skew_accepted_within_30s(self) -> None:
        """A request within 30s of server time must pass timestamp check."""
        from app.services.voice_auth_service import VoiceAuthService

        service = VoiceAuthService()
        now = time.time()

        # Test with 0s skew, +20s, -20s
        for skew in [0, 15, -15, 29, -29]:
            ts = now + skew
            req = self._make_request(ts)
            error = service._check_timestamp_skew(req.timestamp)
            assert error is None, (
                f"Timestamp with skew={skew}s should be accepted, got error: {error}"
            )

    @pytest.mark.asyncio
    async def test_timestamp_skew_rejected_over_30s(self) -> None:
        """A request more than 30s away from server time must be rejected."""
        from app.services.voice_auth_service import VoiceAuthService

        service = VoiceAuthService()
        now = time.time()

        for skew in [31, -31, 60, -60, 3600]:
            ts = now + skew
            req = self._make_request(ts)
            error = service._check_timestamp_skew(req.timestamp)
            assert error is not None, (
                f"Timestamp with skew={skew}s should be rejected, but was accepted"
            )
            assert "timestamp" in error.lower() or "skew" in error.lower() or "time" in error.lower()


class TestChallengeGeneration:
    """test_challenge_generation_returns_id_and_phrase — Challenge has ID + phrase."""

    @pytest.mark.asyncio
    async def test_challenge_generation_returns_id_and_phrase(self) -> None:
        from app.services.voice_auth_service import VoiceAuthService

        service = VoiceAuthService()
        response = await service.generate_challenge()

        # Must have a valid UUID4
        assert response.challenge_id, "challenge_id must be present"
        parsed = uuid.UUID(response.challenge_id, version=4)
        assert str(parsed) == response.challenge_id, "challenge_id must be valid UUID4"

        # Must have a non-empty phrase
        assert response.phrase, "phrase must be present"
        assert len(response.phrase) > 0, "phrase must not be empty"

        # Phrase must be from the known set
        from app.data.challenge_phrases import PHRASES

        assert response.phrase in PHRASES, "phrase must come from challenge_phrases.PHRASES"

    @pytest.mark.asyncio
    async def test_challenge_ids_are_unique(self) -> None:
        """Two consecutive challenges must have different IDs."""
        from app.services.voice_auth_service import VoiceAuthService

        service = VoiceAuthService()
        r1 = await service.generate_challenge()
        r2 = await service.generate_challenge()

        assert r1.challenge_id != r2.challenge_id, "Challenge IDs must be unique"


class TestChallengeSingleUse:
    """test_challenge_single_use — A challenge can only be used once."""

    @pytest.mark.asyncio
    async def test_challenge_single_use(self) -> None:
        """Using the same challenge_id twice must fail on the second attempt."""
        from app.services.voice_auth_service import VoiceAuthService

        service = VoiceAuthService()
        challenge = await service.generate_challenge()

        # Build a valid-ish verify request using this challenge
        dummy_nonce_1 = "nonce-first-attempt-unique1"
        dummy_nonce_2 = "nonce-second-attempt-uniq2"
        valid_hash = "b" * 64
        now = time.time()

        req1 = VerifyRequest(
            challenge_id=challenge.challenge_id,
            nonce=dummy_nonce_1,
            timestamp=now,
            audio_hash=valid_hash,
        )

        # First attempt with this challenge — should reach "ok" (all checks pass)
        result1 = await service.verify(req1, client_ip="YOUR_SERVER_IP")
        assert result1.status == "ok", f"First verify should succeed, got: {result1}"

        req2 = VerifyRequest(
            challenge_id=challenge.challenge_id,  # same challenge!
            nonce=dummy_nonce_2,
            timestamp=now,
            audio_hash=valid_hash,
        )

        # Second attempt with same challenge — must fail
        from app.services.voice_auth_service import VoiceAuthError

        with pytest.raises(VoiceAuthError) as exc_info:
            await service.verify(req2, client_ip="YOUR_SERVER_IP")

        assert exc_info.value.reason in ("challenge_consumed", "challenge_expired_or_unknown")


class TestChallengeExpiry:
    """test_challenge_expires_after_60s — Challenge is invalid after TTL."""

    @pytest.mark.asyncio
    async def test_challenge_expires_after_60s(self) -> None:
        """A challenge with TTL=1s expires before it can be used."""
        from app.core.nonce_store import NonceStore
        from app.services.voice_auth_service import VoiceAuthService, VoiceAuthError

        # Create service with very short challenge TTL (1s for test speed)
        service = VoiceAuthService(challenge_ttl_sec=1)
        challenge = await service.generate_challenge()

        # Wait for challenge to expire
        await asyncio.sleep(1.2)

        req = VerifyRequest(
            challenge_id=challenge.challenge_id,
            nonce="fresh-nonce-12345678",
            timestamp=time.time(),
            audio_hash="c" * 64,
        )

        with pytest.raises(VoiceAuthError) as exc_info:
            await service.verify(req, client_ip="YOUR_SERVER_IP")

        assert exc_info.value.reason in ("challenge_expired_or_unknown", "challenge_consumed")

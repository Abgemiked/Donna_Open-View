"""Pydantic models for the Voice-Auth hardening endpoint (Phase 3).

Phase 3 does NOT implement real voice biometrics — only the hardening layer:
- Replay protection (nonce + timestamp)
- Rate limiting (sliding window + cooldown)
- Liveness challenge (random phrase, single-use)
"""
from __future__ import annotations

from pydantic import BaseModel, Field, field_validator


class ChallengeResponse(BaseModel):
    """Response for GET /voice-auth/challenge.

    The client must read the phrase aloud and submit the audio_hash
    together with the challenge_id in the verify step.
    """

    challenge_id: str = Field(
        ...,
        description="UUID4 identifier for this challenge (single-use, expires in 60 s).",
    )
    phrase: str = Field(
        ...,
        description="German liveness phrase the user must speak aloud.",
    )


class VerifyRequest(BaseModel):
    """Request body for POST /voice-auth/verify."""

    challenge_id: str = Field(
        ...,
        min_length=36,
        max_length=36,
        description="Challenge UUID4 returned by GET /voice-auth/challenge.",
    )
    nonce: str = Field(
        ...,
        min_length=8,
        max_length=128,
        description="Client-generated unique value to prevent replay attacks.",
    )
    timestamp: float = Field(
        ...,
        description="Unix timestamp (seconds) at the time of the request.",
    )
    audio_hash: str = Field(
        ...,
        description="SHA-256 hex digest of the recorded audio (placeholder for Phase 3).",
    )

    @field_validator("audio_hash")
    @classmethod
    def validate_audio_hash_format(cls, v: str) -> str:
        """Validate that audio_hash looks like a SHA-256 hex digest (64 hex chars)."""
        v = v.strip().lower()
        if len(v) != 64:
            raise ValueError("audio_hash must be a 64-character hex string (SHA-256).")
        if not all(c in "0123456789abcdef" for c in v):
            raise ValueError("audio_hash must contain only hex characters.")
        return v

    @field_validator("challenge_id")
    @classmethod
    def validate_challenge_id_format(cls, v: str) -> str:
        """Validate that challenge_id looks like a UUID4."""
        import uuid

        try:
            parsed = uuid.UUID(v, version=4)
        except ValueError as exc:
            raise ValueError("challenge_id must be a valid UUID4.") from exc
        return str(parsed)


class VerifyResponse(BaseModel):
    """Successful response for POST /voice-auth/verify."""

    status: str = Field(default="ok", description="Always 'ok' on success.")
    message: str = Field(
        default="Voice authentication accepted.",
        description="Human-readable confirmation.",
    )


class ErrorResponse(BaseModel):
    """Error response shape used across all voice-auth endpoints."""

    detail: str = Field(..., description="Error description.")
    retry_after: int | None = Field(
        default=None,
        description="Seconds to wait before retrying (only present on 429).",
    )

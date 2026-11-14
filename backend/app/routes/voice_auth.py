"""FastAPI Router — Voice-Auth Hardening Endpoints (Phase 3).

Endpoints:
  GET  /voice-auth/challenge   → Issue a liveness challenge phrase (rate-limited)
  POST /voice-auth/verify      → Verify the response (rate-limited, multi-step)

Both endpoints are rate-limited per client IP via the VoiceAuthService.
The existing Bearer-Token auth (core/auth.py) is intentionally NOT used here —
Voice-Auth is its own separate, self-contained authentication pathway.

Phase 3: No real voice biometrics. Only hardening layer (replay, rate-limit, liveness).
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Request, status

from app.schemas.voice_auth import (
    ChallengeResponse,
    ErrorResponse,
    VerifyRequest,
    VerifyResponse,
)
from app.services.voice_auth_service import VoiceAuthError, VoiceAuthService

router = APIRouter(prefix="/voice-auth", tags=["voice-auth"])


# ---------------------------------------------------------------------------
# Dependency: client IP extraction (X-Forwarded-For aware for reverse-proxy)
# ---------------------------------------------------------------------------


def get_client_ip(request: Request) -> str:
    """Extract the real client IP from the request.

    Respects X-Forwarded-For (set by Caddy/Nginx reverse proxy).
    Falls back to request.client.host if header is absent.
    """
    forwarded_for = request.headers.get("X-Forwarded-For")
    if forwarded_for:
        # X-Forwarded-For can be a comma-separated list; leftmost is the client
        return forwarded_for.split(",")[0].strip()
    if request.client:
        return request.client.host
    return "unknown"


def get_voice_auth_service(request: Request) -> VoiceAuthService:
    """FastAPI dependency: retrieve VoiceAuthService from app.state."""
    service: VoiceAuthService = request.app.state.voice_auth
    return service


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------


@router.get(
    "/challenge",
    response_model=ChallengeResponse,
    summary="Issue a liveness challenge",
    description=(
        "Returns a unique challenge_id and a random German phrase. "
        "The client must record the user speaking the phrase and submit the "
        "audio_hash together with the challenge_id to POST /voice-auth/verify."
    ),
    responses={
        200: {"model": ChallengeResponse},
        429: {"model": ErrorResponse, "description": "Rate limit exceeded"},
    },
)
async def get_challenge(
    request: Request,
    client_ip: str = Depends(get_client_ip),
    service: VoiceAuthService = Depends(get_voice_auth_service),
) -> ChallengeResponse:
    """Issue a fresh liveness challenge (rate-limited per IP)."""
    # Rate-limit the challenge endpoint as well (prevent phrase enumeration)
    allowed, retry_after = await service.check_challenge_rate_limit(client_ip)
    if not allowed:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=ErrorResponse(
                detail="Too many requests. Please wait before requesting a new challenge.",
                retry_after=retry_after,
            ).model_dump(),
            headers={"Retry-After": str(retry_after)},
        )

    return await service.generate_challenge()


@router.post(
    "/verify",
    response_model=VerifyResponse,
    summary="Verify a voice-auth attempt",
    description=(
        "Submit the challenge_id, a unique nonce, a Unix timestamp, and the "
        "SHA-256 hash of the recorded audio. All five checks must pass: "
        "rate-limit, timestamp skew (≤30s), nonce (single-use), "
        "challenge (single-use, ≤60s), audio_hash format."
    ),
    responses={
        200: {"model": VerifyResponse},
        400: {"model": ErrorResponse, "description": "Invalid audio_hash format"},
        401: {"model": ErrorResponse, "description": "Auth check failed"},
        429: {"model": ErrorResponse, "description": "Rate limit exceeded"},
    },
)
async def verify(
    body: VerifyRequest,
    request: Request,
    client_ip: str = Depends(get_client_ip),
    service: VoiceAuthService = Depends(get_voice_auth_service),
) -> VerifyResponse:
    """Run the multi-step verify flow (order verbindlich: see Plan-MD)."""
    try:
        return await service.verify(body, client_ip=client_ip)
    except VoiceAuthError as exc:
        error_body = ErrorResponse(
            detail=exc.message,
            retry_after=exc.retry_after if exc.retry_after else None,
        ).model_dump(exclude_none=True)

        headers: dict[str, str] = {}
        if exc.retry_after:
            headers["Retry-After"] = str(exc.retry_after)

        raise HTTPException(
            status_code=exc.http_status,
            detail=error_body,
            headers=headers or None,
        ) from exc

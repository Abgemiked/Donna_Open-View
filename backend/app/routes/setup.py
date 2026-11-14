"""
DONNA-103: TOTP-Pairing Endpoints

GET  /setup/qr   → QR-Code als PNG (base64) + otpauth-URI
                   Requires Bearer-Auth (ADMIN_TOKEN) — nur für den Server-Admin.
POST /setup/pair → validiert TOTP-Code, gibt ADMIN_TOKEN zurück.
                   Kein Auth-Header nötig — App ist noch nicht gepairt.

Replay-Schutz:
  Verbrauchte Codes werden 90 Sekunden lang gesperrt (app.state.used_totp_codes).
  Der Cleanup-Task in main.py ruft _cleanup_totp_codes() auf.

Rate-Limit:
  Max 5 Versuche pro Minute pro IP (app.state.totp_rate_limits).
"""
from __future__ import annotations

import base64
import io
import time
from typing import Annotated

import pyotp
import qrcode

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel

from app.core.auth import require_admin
from app.core.logger import get_logger

log = get_logger("route.setup")

router = APIRouter(prefix="/setup", tags=["setup"])

# Replay-Schutz: Codes werden 90 Sekunden gesperrt
_TOTP_CODE_TTL_SEC = 90

# Rate-Limit: max 5 Versuche pro IP pro 60 Sekunden
_RATE_LIMIT_MAX = 5
_RATE_LIMIT_WINDOW_SEC = 60


# ── Hilfsfunktionen ──────────────────────────────────────────────────────────


def _get_totp_secret(request: Request) -> str:
    """Liest TOTP-Secret aus app.state oder wirft 503."""
    secret: str | None = getattr(request.app.state, "totp_secret", None)
    if not secret:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="TOTP not configured. Set DONNA_TOTP_SECRET in .env.",
        )
    return secret


def _check_rate_limit(request: Request) -> None:
    """Blockiert bei >5 Versuchen pro IP in 60 Sekunden."""
    client_ip: str = request.client.host if request.client else "unknown"
    now = time.time()

    if not hasattr(request.app.state, "totp_rate_limits"):
        request.app.state.totp_rate_limits = {}

    rate_limits: dict[str, list[float]] = request.app.state.totp_rate_limits

    # Alte Einträge raus
    rate_limits[client_ip] = [
        ts for ts in rate_limits.get(client_ip, [])
        if now - ts < _RATE_LIMIT_WINDOW_SEC
    ]

    if len(rate_limits[client_ip]) >= _RATE_LIMIT_MAX:
        log.warning("totp_rate_limit_exceeded", ip=client_ip)
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Too many pairing attempts. Try again in 60 seconds.",
            headers={"Retry-After": "60"},
        )

    rate_limits[client_ip].append(now)


def _is_code_used(request: Request, code: str) -> bool:
    """Gibt True zurück wenn der Code in den letzten 90 Sekunden schon genutzt wurde."""
    if not hasattr(request.app.state, "used_totp_codes"):
        request.app.state.used_totp_codes = {}

    used: dict[str, float] = request.app.state.used_totp_codes
    now = time.time()

    # Cleanup abgelaufener Einträge
    expired = [c for c, ts in used.items() if now - ts >= _TOTP_CODE_TTL_SEC]
    for c in expired:
        del used[c]

    return code in used


def _mark_code_used(request: Request, code: str) -> None:
    """Markiert einen Code als verbraucht (Replay-Schutz)."""
    if not hasattr(request.app.state, "used_totp_codes"):
        request.app.state.used_totp_codes = {}
    request.app.state.used_totp_codes[code] = time.time()


def cleanup_totp_state(request: Request) -> None:
    """Bereinigt abgelaufene TOTP-Codes und Rate-Limit-Einträge.
    Kann periodisch aufgerufen werden (z.B. aus main.py Cleanup-Task).
    """
    now = time.time()

    if hasattr(request.app.state, "used_totp_codes"):
        used: dict[str, float] = request.app.state.used_totp_codes
        expired_codes = [c for c, ts in used.items() if now - ts >= _TOTP_CODE_TTL_SEC]
        for c in expired_codes:
            del used[c]

    if hasattr(request.app.state, "totp_rate_limits"):
        rate_limits: dict[str, list[float]] = request.app.state.totp_rate_limits
        for ip in list(rate_limits.keys()):
            rate_limits[ip] = [
                ts for ts in rate_limits[ip]
                if now - ts < _RATE_LIMIT_WINDOW_SEC
            ]
            if not rate_limits[ip]:
                del rate_limits[ip]


# ── Pydantic-Schemas ─────────────────────────────────────────────────────────


class PairRequest(BaseModel):
    totp: str


class PairResponse(BaseModel):
    token: str


class QRResponse(BaseModel):
    qr_base64: str
    uri: str


# ── Endpoints ────────────────────────────────────────────────────────────────


@router.get(
    "/qr",
    response_model=QRResponse,
    summary="QR-Code für Google Authenticator",
    description=(
        "Gibt einen base64-codierten QR-Code + otpauth-URI zurück. "
        "Nur einmalig beim ersten Setup aufrufen und mit Google Authenticator scannen. "
        "Erfordert Bearer-Auth (ADMIN_TOKEN)."
    ),
)
def get_setup_qr(
    request: Request,
    _admin: Annotated[str, Depends(require_admin)],
) -> QRResponse:
    secret = _get_totp_secret(request)

    uri = pyotp.totp.TOTP(secret).provisioning_uri(
        name="Mike",
        issuer_name="Donna",
    )

    # QR-Code als PNG in base64
    qr_img = qrcode.make(uri)
    buf = io.BytesIO()
    qr_img.save(buf, format="PNG")
    qr_b64 = base64.b64encode(buf.getvalue()).decode("utf-8")

    log.info("setup_qr_generated")
    return QRResponse(qr_base64=qr_b64, uri=uri)


@router.post(
    "/pair",
    response_model=PairResponse,
    summary="TOTP-Code validieren und Token erhalten",
    description=(
        "App sendet 6-stelligen TOTP-Code aus Google Authenticator. "
        "Bei Erfolg: ADMIN_TOKEN in der Antwort — App speichert ihn sicher. "
        "Kein Auth-Header nötig (App ist beim ersten Aufruf noch nicht gepairt). "
        "Replay-Schutz: jeder Code kann nur einmal verwendet werden. "
        "Rate-Limit: max 5 Versuche pro Minute pro IP."
    ),
)
def post_setup_pair(
    body: PairRequest,
    request: Request,
) -> PairResponse:
    # 1. Rate-Limit prüfen
    _check_rate_limit(request)

    # 2. TOTP-Secret vorhanden?
    secret = _get_totp_secret(request)

    # 3. Code normalisieren (nur Ziffern, max 6)
    code = body.totp.strip().replace(" ", "")
    if not code.isdigit() or len(code) != 6:
        log.warning("totp_invalid_format", code_len=len(code))
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid TOTP code format. Expected 6 digits.",
        )

    # 4. Replay-Schutz: Code schon benutzt?
    if _is_code_used(request, code):
        log.warning("totp_replay_attempt", code=code[:2] + "****")
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="TOTP code already used. Wait for the next code.",
        )

    # 5. Zeitfenster-Validierung (valid_window=1 → ±30 Sekunden Toleranz)
    totp = pyotp.TOTP(secret)
    if not totp.verify(code, valid_window=1):
        log.warning("totp_verification_failed")
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired TOTP code.",
        )

    # 6. Code verbrauchen (Replay-Schutz)
    _mark_code_used(request, code)

    # 7. ADMIN_TOKEN zurückgeben
    settings = request.app.state.settings
    admin_token: str | None = settings.admin_token
    if not admin_token:
        log.error("admin_token_not_configured_for_pair")
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Server not configured: ADMIN_TOKEN missing.",
        )

    log.info("totp_pair_success")
    return PairResponse(token=admin_token)

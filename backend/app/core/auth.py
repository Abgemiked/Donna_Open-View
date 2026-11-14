"""Bearer-token authentication (single shared secret, single-user system)."""
from __future__ import annotations

import hmac

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.config import Settings, get_settings
from app.core.logger import get_logger

log = get_logger("auth")

# auto_error=False so we can emit our own 401 shape consistently
_bearer_scheme = HTTPBearer(auto_error=False)


def require_admin(
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer_scheme),
    settings: Settings = Depends(get_settings),
) -> str:
    """FastAPI dependency: verify Bearer token matches ADMIN_TOKEN.

    Uses hmac.compare_digest to avoid timing attacks.
    Raises 401 if missing/invalid, 503 if server not configured.
    """
    expected = settings.admin_token
    if not expected:
        log.error("admin_token_not_configured")
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Server not configured: ADMIN_TOKEN missing.",
        )

    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing or invalid Authorization header.",
            headers={"WWW-Authenticate": "Bearer"},
        )

    provided = credentials.credentials or ""
    if not hmac.compare_digest(provided.encode("utf-8"), expected.encode("utf-8")):
        log.warning("auth_failed")
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid token.",
            headers={"WWW-Authenticate": "Bearer"},
        )

    return "admin"

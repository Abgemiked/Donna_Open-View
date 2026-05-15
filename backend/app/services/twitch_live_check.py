"""twitch_live_check.py — Prüft ob abgemiked gerade live streamt.

Verwendet Twitch Helix API (streams-Endpoint).
Circuit-Breaker: bei API-Down → live=True (konservativ, lieber zu viel blocken als Leak).
Cache: 30s In-Memory, verhindert API-Spam bei jedem Chat-Request.
"""
from __future__ import annotations

import time
from typing import Any

import httpx

from app.core.logger import get_logger

log = get_logger("service.twitch_live_check")

# Cache-Eintrag: {"live": bool, "ts": float}
_cache: dict[str, Any] = {}
_CACHE_TTL = 30.0  # Sekunden

# App-Token-Cache: {"token": str, "expires_at": float}
_token_cache: dict[str, Any] = {}


async def _fetch_app_token(client_id: str, client_secret: str) -> str | None:
    """Holt ein frisches App-Token via client_credentials flow."""
    try:
        async with httpx.AsyncClient(timeout=8.0) as client:
            resp = await client.post(
                "https://id.twitch.tv/oauth2/token",
                params={
                    "client_id": client_id,
                    "client_secret": client_secret,
                    "grant_type": "client_credentials",
                },
            )
            if resp.status_code == 200:
                data = resp.json()
                token = data.get("access_token")
                expires_in = data.get("expires_in", 3600)
                _token_cache["token"] = token
                _token_cache["expires_at"] = time.time() + expires_in - 60  # 1 min Puffer
                log.info("twitch_app_token_refreshed")
                return token
            log.warning("twitch_app_token_failed", status=resp.status_code)
    except Exception as e:  # noqa: BLE001
        log.warning("twitch_app_token_error", error=str(e))
    return None


async def _get_app_token(client_id: str, client_secret: str) -> str | None:
    """Gibt ein gültiges App-Token zurück, refresht bei Bedarf."""
    now = time.time()
    if _token_cache.get("token") and _token_cache.get("expires_at", 0) > now:
        return _token_cache["token"]  # type: ignore[return-value]
    return await _fetch_app_token(client_id, client_secret)


async def is_broadcaster_live(
    broadcaster_login: str = "your-twitch-channel",
    client_id: str | None = None,
    client_secret: str | None = None,
) -> bool:
    """Prüft ob der Broadcaster gerade live ist.

    Args:
        broadcaster_login: Twitch-Login (default: "your-twitch-channel").
        client_id: Twitch App Client-ID. Wird aus Settings geladen wenn None.
        client_secret: Twitch App Client-Secret. Wird aus Settings geladen wenn None.

    Returns:
        True wenn live (oder bei Fehler — fail-safe/konservativ).
        False wenn sicher offline.
    """
    # Credentials aus Settings holen wenn nicht übergeben.
    # DONNA-42 B+: wenn TWITCH_CLIENT_SECRET nicht gesetzt ist, fallback auf den
    # vorhandenen TWITCH_BOT_TOKEN (User-OAuth) — der funktioniert für Helix/streams
    # genauso. Damit braucht es kein separates App-Secret in der .env.
    bot_user_token: str | None = None
    if client_id is None or client_secret is None:
        try:
            from app.config import get_settings
            s = get_settings()
            client_id = client_id or s.twitch_client_id
            client_secret = client_secret or s.twitch_client_secret
            # twitch_bot_token (User-OAuth, oxxxxxxxxxx) kann direkt für Helix
            # genutzt werden — ohne dass wir ein App-Token mit client_credentials
            # generieren müssen.
            bot_user_token = getattr(s, "twitch_bot_token", None) or None
        except Exception as e:  # noqa: BLE001
            log.warning("twitch_live_check_settings_failed", error=str(e))

    # Wenn weder client_secret NOCH bot_user_token verfügbar → fail-safe live=True
    if not client_id or (not client_secret and not bot_user_token):
        log.warning("twitch_live_check_no_credentials", fail_safe=True)
        return True

    # Cache prüfen
    now = time.time()
    cache_key = f"live_{broadcaster_login}"
    cached = _cache.get(cache_key)
    if cached and isinstance(cached, dict) and now - cached.get("ts", 0) < _CACHE_TTL:
        log.debug("twitch_live_check_cache_hit", live=cached["live"])
        return bool(cached["live"])

    # API-Anfrage — Token-Strategie:
    # 1. Wenn client_secret vorhanden → App-Token via client_credentials (clean)
    # 2. Sonst: bot_user_token verwenden (User-OAuth) — funktioniert für streams-Endpoint
    try:
        token: str | None = None
        token_source = ""
        if client_secret:
            token = await _get_app_token(client_id, client_secret)
            token_source = "app"
        if not token and bot_user_token:
            # `oauth:xxx`-Prefix wird von twitchio benutzt, Helix erwartet aber
            # nur den nackten Token nach `Bearer`.
            token = bot_user_token.removeprefix("oauth:")
            token_source = "user"
        if not token:
            log.warning("twitch_live_check_no_token", fail_safe=True)
            _cache[cache_key] = {"live": True, "ts": now}
            return True

        headers = {
            "Client-Id": client_id,
            "Authorization": f"Bearer {token}",
        }
        log.debug("twitch_live_check_token_used", source=token_source)
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.get(
                "https://api.twitch.tv/helix/streams",
                params={"user_login": broadcaster_login},
                headers=headers,
            )

            if resp.status_code == 401:
                # Token abgelaufen → Token-Cache leeren + einmal neu versuchen
                _token_cache.clear()
                log.warning("twitch_live_check_401_token_refresh")
                new_token = await _fetch_app_token(client_id, client_secret)
                if new_token:
                    headers["Authorization"] = f"Bearer {new_token}"
                    resp = await client.get(
                        "https://api.twitch.tv/helix/streams",
                        params={"user_login": broadcaster_login},
                        headers=headers,
                    )
                else:
                    log.warning("twitch_live_check_token_refresh_failed", fail_safe=True)
                    _cache[cache_key] = {"live": True, "ts": now}
                    return True

            if resp.status_code != 200:
                log.warning("twitch_live_check_api_error", status=resp.status_code, fail_safe=True)
                _cache[cache_key] = {"live": True, "ts": now}
                return True

            data = resp.json()
            streams = data.get("data", [])
            is_live = len(streams) > 0
            _cache[cache_key] = {"live": is_live, "ts": now}
            log.info("twitch_live_check_result", broadcaster=broadcaster_login, live=is_live)
            return is_live

    except httpx.TimeoutException:
        log.warning("twitch_live_check_timeout", fail_safe=True)
        _cache[cache_key] = {"live": True, "ts": now}
        return True
    except Exception as e:  # noqa: BLE001
        log.warning("twitch_live_check_error", error=str(e), fail_safe=True)
        _cache[cache_key] = {"live": True, "ts": now}
        return True


def clear_cache() -> None:
    """Cache leeren (für Tests)."""
    _cache.clear()
    _token_cache.clear()

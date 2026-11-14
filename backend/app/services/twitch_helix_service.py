"""twitch_helix_service.py — Twitch Helix API Integration für Donna (DONNA-16).

Funktionen:
  - get_stream_status()  → Live-Status, Titel, Spiel, Startzeit
  - set_stream_title()   → Ändert Stream-Titel via PATCH /helix/channels
  - set_stream_game()    → Sucht Game-ID per Name, dann PATCH /helix/channels

OAuth2: App-Token via Client Credentials Grant (kein Browser-Flow).
Token-Cache: In-Memory, Expiry-Check mit 60s Safety-Margin.
Graceful Degradation: TWITCH_CLIENT_ID nicht gesetzt → {"error": "Twitch nicht konfiguriert"}.

Unterschied zu twitch_live_check.py:
  - twitch_live_check.py: nur is_broadcaster_live() (bool) — leichtgewichtig, fail-safe live=True
  - twitch_helix_service.py: vollständiges Status-Objekt + Schreiboperationen (Titel, Spiel)
"""
from __future__ import annotations

import time
from typing import Any

import httpx

from app.core.logger import get_logger

log = get_logger("service.twitch_helix")

# ── Token-Cache ────────────────────────────────────────────────────────────────
# {"token": str, "expires_at": float}
_token_cache: dict[str, Any] = {}

# ── Status-Cache (30s TTL — wie twitch_live_check) ────────────────────────────
# {"status": dict, "ts": float}
_status_cache: dict[str, Any] = {}
_STATUS_CACHE_TTL = 30.0


# ── Interne Helpers ────────────────────────────────────────────────────────────

def _get_credentials() -> tuple[str | None, str | None, str | None]:
    """Gibt (client_id, client_secret, broadcaster_id) aus Settings zurück."""
    try:
        from app.config import get_settings
        s = get_settings()
        return s.twitch_client_id, s.twitch_client_secret, s.twitch_broadcaster_id
    except Exception as e:  # noqa: BLE001
        log.warning("twitch_helix_settings_failed", error=str(e))
        return None, None, None


async def _fetch_app_token(client_id: str, client_secret: str) -> str | None:
    """Holt ein frisches App-Token via Client Credentials Grant."""
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
                # 60s Safety-Margin: Token nicht bis zum letzten Moment nutzen
                _token_cache["expires_at"] = time.time() + expires_in - 60
                log.info("twitch_helix_token_refreshed")
                return token
            log.warning("twitch_helix_token_failed", status=resp.status_code)
    except Exception as e:  # noqa: BLE001
        log.warning("twitch_helix_token_error", error=str(e))
    return None


async def _get_app_token(client_id: str, client_secret: str) -> str | None:
    """Gibt gültiges App-Token zurück, refresht bei Bedarf."""
    now = time.time()
    if _token_cache.get("token") and _token_cache.get("expires_at", 0) > now:
        return _token_cache["token"]  # type: ignore[return-value]
    return await _fetch_app_token(client_id, client_secret)


def _make_headers(client_id: str, token: str) -> dict[str, str]:
    return {
        "Client-Id": client_id,
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
    }


# ── Öffentliche API ────────────────────────────────────────────────────────────

async def get_stream_status(user_id: str | None = None) -> dict[str, Any]:
    """Gibt den aktuellen Stream-Status zurück.

    Returns:
        {"live": bool, "title": str, "game": str, "started_at": str | None}
        oder {"error": str} bei Konfigurations-/API-Fehler.
    """
    client_id, client_secret, broadcaster_id = _get_credentials()
    if not client_id:
        return {"error": "Twitch nicht konfiguriert"}

    # broadcaster_login für den Streams-Check: user_id-Param oder Einstellung
    broadcaster_login: str | None = None
    try:
        from app.config import get_settings
        s = get_settings()
        broadcaster_login = s.twitch_broadcaster_login
    except Exception:  # noqa: BLE001
        broadcaster_login = "your-twitch-channel"

    # Cache prüfen
    now = time.time()
    cache_entry = _status_cache.get("status")
    if cache_entry and now - _status_cache.get("ts", 0) < _STATUS_CACHE_TTL:
        log.debug("twitch_helix_status_cache_hit")
        return cache_entry  # type: ignore[return-value]

    if not client_secret:
        log.warning("twitch_helix_no_secret")
        return {"error": "Twitch nicht konfiguriert"}

    token = await _get_app_token(client_id, client_secret)
    if not token:
        return {"error": "Twitch Token konnte nicht abgerufen werden"}

    try:
        async with httpx.AsyncClient(timeout=5.0) as http:
            resp = await http.get(
                "https://api.twitch.tv/helix/streams",
                params={"user_login": broadcaster_login},
                headers=_make_headers(client_id, token),
            )
            if resp.status_code == 401:
                # Token abgelaufen → einmal neu versuchen
                _token_cache.clear()
                token = await _fetch_app_token(client_id, client_secret)
                if not token:
                    return {"error": "Twitch Token-Refresh fehlgeschlagen"}
                resp = await http.get(
                    "https://api.twitch.tv/helix/streams",
                    params={"user_login": broadcaster_login},
                    headers=_make_headers(client_id, token),
                )
            if resp.status_code != 200:
                log.warning("twitch_helix_streams_error", status=resp.status_code)
                return {"error": f"Twitch API Fehler {resp.status_code}"}

            data = resp.json()
            streams = data.get("data", [])
            if streams:
                s = streams[0]
                result: dict[str, Any] = {
                    "live": True,
                    "title": s.get("title", ""),
                    "game": s.get("game_name", ""),
                    "started_at": s.get("started_at"),
                }
            else:
                result = {"live": False, "title": "", "game": "", "started_at": None}

            _status_cache["status"] = result
            _status_cache["ts"] = now
            log.info("twitch_helix_status_fetched", live=result["live"])
            return result

    except httpx.TimeoutException:
        log.warning("twitch_helix_status_timeout")
        return {"error": "Twitch API Timeout"}
    except Exception as e:  # noqa: BLE001
        log.warning("twitch_helix_status_error", error=str(e))
        return {"error": f"Twitch Fehler: {e}"}


async def set_stream_title(title: str) -> dict[str, Any]:
    """Setzt den Stream-Titel via PATCH /helix/channels.

    Args:
        title: Neuer Stream-Titel (max 140 Zeichen).

    Returns:
        {"ok": True} oder {"error": str}.
    """
    client_id, client_secret, broadcaster_id = _get_credentials()
    if not client_id:
        return {"error": "Twitch nicht konfiguriert"}
    if not broadcaster_id:
        return {"error": "TWITCH_BROADCASTER_ID nicht konfiguriert"}
    if not client_secret:
        return {"error": "Twitch nicht konfiguriert"}

    token = await _get_app_token(client_id, client_secret)
    if not token:
        return {"error": "Twitch Token konnte nicht abgerufen werden"}

    try:
        async with httpx.AsyncClient(timeout=8.0) as http:
            resp = await http.patch(
                "https://api.twitch.tv/helix/channels",
                params={"broadcaster_id": broadcaster_id},
                headers=_make_headers(client_id, token),
                json={"title": title[:140]},  # Twitch-Limit: 140 Zeichen
            )
            if resp.status_code == 204:
                # Cache invalidieren — Titel hat sich geändert
                _status_cache.clear()
                log.info("twitch_helix_title_set", title=title[:40])
                return {"ok": True}
            log.warning("twitch_helix_title_error", status=resp.status_code, body=resp.text[:200])
            return {"error": f"Twitch PATCH Fehler {resp.status_code}"}
    except Exception as e:  # noqa: BLE001
        log.warning("twitch_helix_title_exception", error=str(e))
        return {"error": f"Twitch Fehler: {e}"}


async def set_stream_game(game_name: str) -> dict[str, Any]:
    """Setzt das Spiel des Streams.

    Schritt 1: GET /helix/games?name=... → game_id
    Schritt 2: PATCH /helix/channels mit game_id

    Args:
        game_name: Exakter oder annähernder Spielname.

    Returns:
        {"ok": True, "game_id": str, "game_name": str} oder {"error": str}.
    """
    client_id, client_secret, broadcaster_id = _get_credentials()
    if not client_id:
        return {"error": "Twitch nicht konfiguriert"}
    if not broadcaster_id:
        return {"error": "TWITCH_BROADCASTER_ID nicht konfiguriert"}
    if not client_secret:
        return {"error": "Twitch nicht konfiguriert"}

    token = await _get_app_token(client_id, client_secret)
    if not token:
        return {"error": "Twitch Token konnte nicht abgerufen werden"}

    try:
        async with httpx.AsyncClient(timeout=8.0) as http:
            # Schritt 1: Game-ID suchen
            games_resp = await http.get(
                "https://api.twitch.tv/helix/games",
                params={"name": game_name},
                headers=_make_headers(client_id, token),
            )
            if games_resp.status_code != 200:
                log.warning("twitch_helix_game_lookup_error", status=games_resp.status_code)
                return {"error": f"Spiel-Suche Fehler {games_resp.status_code}"}

            games_data = games_resp.json().get("data", [])
            if not games_data:
                log.info("twitch_helix_game_not_found", name=game_name)
                return {"error": f"Spiel '{game_name}' auf Twitch nicht gefunden"}

            game = games_data[0]
            game_id = game["id"]
            resolved_name = game["name"]

            # Schritt 2: Spiel setzen
            patch_resp = await http.patch(
                "https://api.twitch.tv/helix/channels",
                params={"broadcaster_id": broadcaster_id},
                headers=_make_headers(client_id, token),
                json={"game_id": game_id},
            )
            if patch_resp.status_code == 204:
                # Cache invalidieren
                _status_cache.clear()
                log.info("twitch_helix_game_set", game=resolved_name, game_id=game_id)
                return {"ok": True, "game_id": game_id, "game_name": resolved_name}
            log.warning("twitch_helix_game_patch_error", status=patch_resp.status_code)
            return {"error": f"Twitch PATCH Fehler {patch_resp.status_code}"}

    except Exception as e:  # noqa: BLE001
        log.warning("twitch_helix_game_exception", error=str(e))
        return {"error": f"Twitch Fehler: {e}"}


def clear_cache() -> None:
    """Cache leeren (für Tests)."""
    _token_cache.clear()
    _status_cache.clear()

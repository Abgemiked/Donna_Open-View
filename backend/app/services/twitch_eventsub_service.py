"""
DONNA-266/267 — Twitch EventSub WebSocket Service

Verbindet donna_bot via channel.chat.message EventSub WebSocket, damit Twitch
den Account als offiziellen "Chat-Bot" erkennt (Badge + Users-in-Chat-Listing).

Nutzt donna_bot's User Access Token (user:read:chat + user:bot).
DONNA-267: Automatischer Token-Refresh via Refresh-Token (Authorization Code Flow).
"""
from __future__ import annotations

import asyncio
import json
from typing import Optional

import httpx
import websockets
import websockets.exceptions

from app.core.logger import get_logger

log = get_logger("service.twitch_eventsub")

_TWITCH_VALIDATE_URL = "https://id.twitch.tv/oauth2/validate"
_TWITCH_TOKEN_URL = "https://id.twitch.tv/oauth2/token"
_TWITCH_EVENTSUB_API = "https://api.twitch.tv/helix/eventsub/subscriptions"
_TWITCH_WS_URL = "wss://eventsub.wss.twitch.tv/ws"
_BACKOFF_INITIAL = 5
_BACKOFF_MAX = 60
_REFRESH_THRESHOLD_SEC = 3600  # Refresh wenn Token in <1h abläuft


class TwitchEventSubService:
    """Leichtgewichtiger EventSub WebSocket Client für donna_bot Bot-Badge.

    Nutzt donna_bot's User Access Token (user:read:chat + user:bot).
    Mit refresh_token + client_secret wird der Token automatisch erneuert.
    """

    def __init__(
        self,
        client_id: str,
        bot_user_token: str,
        broadcaster_id: str,
        bot_user_id: str,
        client_secret: Optional[str] = None,
        refresh_token: Optional[str] = None,
    ) -> None:
        self.client_id = client_id
        self.bot_user_token = bot_user_token
        self.broadcaster_id = broadcaster_id
        self.bot_user_id = bot_user_id
        self._client_secret = client_secret  # nie loggen
        self._refresh_token = refresh_token  # nie loggen
        self._running = False

    # ── Token-Refresh ────────────────────────────────────────────────────────

    async def _validate_and_refresh(self) -> None:
        """Prüft Token-Gültigkeit und refresht bei Bedarf (in-memory)."""
        if not self._refresh_token or not self._client_secret:
            return  # Kein Refresh konfiguriert — Token läuft irgendwann ab

        try:
            async with httpx.AsyncClient(timeout=10) as http:
                resp = await http.get(
                    _TWITCH_VALIDATE_URL,
                    headers={"Authorization": f"OAuth {self.bot_user_token}"},
                )

            if resp.status_code == 200:
                expires_in = resp.json().get("expires_in", 9999)
                if expires_in > _REFRESH_THRESHOLD_SEC:
                    log.info("eventsub_token_valid", expires_in_sec=expires_in)
                    return  # Token noch frisch genug
                log.info("eventsub_token_refresh_needed", expires_in_sec=expires_in)
            else:
                log.warning("eventsub_token_invalid", status=resp.status_code)

            # Token refreshen
            async with httpx.AsyncClient(timeout=10) as http:
                refresh_resp = await http.post(
                    _TWITCH_TOKEN_URL,
                    data={
                        "grant_type": "refresh_token",
                        "refresh_token": self._refresh_token,
                        "client_id": self.client_id,
                        "client_secret": self._client_secret,
                    },
                )

            if refresh_resp.status_code == 200:
                data = refresh_resp.json()
                self.bot_user_token = data["access_token"]
                # Neuen Refresh-Token in-memory speichern (falls Twitch rotiert)
                if "refresh_token" in data:
                    self._refresh_token = data["refresh_token"]
                log.info("eventsub_token_refreshed")
            else:
                # Sanitized — kein Secret/Token in Fehlermeldung
                log.error(
                    "eventsub_token_refresh_failed",
                    status=refresh_resp.status_code,
                )

        except httpx.HTTPError as exc:
            log.error("eventsub_token_refresh_http_error", error=str(exc))

    # ── Subscription anlegen ─────────────────────────────────────────────────

    async def _subscribe(self, session_id: str) -> None:
        await self._validate_and_refresh()

        async with httpx.AsyncClient(timeout=10) as http:
            resp = await http.post(
                _TWITCH_EVENTSUB_API,
                headers={
                    "Authorization": f"Bearer {self.bot_user_token}",
                    "Client-Id": self.client_id,
                    "Content-Type": "application/json",
                },
                json={
                    "type": "channel.chat.message",
                    "version": "1",
                    "condition": {
                        "broadcaster_user_id": self.broadcaster_id,
                        "user_id": self.bot_user_id,
                    },
                    "transport": {
                        "method": "websocket",
                        "session_id": session_id,
                    },
                },
            )
        if resp.status_code in (200, 202):
            log.info(
                "eventsub_subscribed",
                type="channel.chat.message",
                broadcaster=self.broadcaster_id,
                bot=self.bot_user_id,
            )
        else:
            log.error(
                "eventsub_subscribe_failed",
                status=resp.status_code,
                body=resp.text[:200],
            )

    # ── WebSocket-Loop ───────────────────────────────────────────────────────

    async def _connect(self, ws_url: str = _TWITCH_WS_URL) -> str | None:
        """
        Verbindet sich und verarbeitet Nachrichten bis zum Disconnect.
        Gibt reconnect_url zurück falls session_reconnect empfangen wurde,
        sonst None.
        """
        async with websockets.connect(ws_url, ping_interval=20, ping_timeout=10) as ws:
            log.info("eventsub_ws_connected", url=ws_url)
            async for raw in ws:
                try:
                    msg = json.loads(raw)
                except json.JSONDecodeError:
                    continue

                msg_type: str = msg.get("metadata", {}).get("message_type", "")

                if msg_type == "session_welcome":
                    session_id: str = msg["payload"]["session"]["id"]
                    log.info("eventsub_session_welcome", session_id=session_id)
                    await self._subscribe(session_id)

                elif msg_type == "session_keepalive":
                    pass  # Twitch sendet alle ~10s einen Keepalive

                elif msg_type == "notification":
                    pass  # Nur Präsenz zählt, kein Processing

                elif msg_type == "session_reconnect":
                    reconnect_url: str = msg["payload"]["session"].get(
                        "reconnect_url", _TWITCH_WS_URL
                    )
                    log.info("eventsub_session_reconnect", url=reconnect_url)
                    return reconnect_url

                elif msg_type == "revocation":
                    status = (
                        msg.get("payload", {})
                        .get("subscription", {})
                        .get("status", "unknown")
                    )
                    log.warning("eventsub_revoked", status=status)
                    return None

        return None

    # ── Öffentliche API ──────────────────────────────────────────────────────

    async def run(self) -> None:
        """Hauptschleife mit automatischem Reconnect + exponential backoff."""
        self._running = True
        backoff = _BACKOFF_INITIAL
        ws_url = _TWITCH_WS_URL

        log.info(
            "eventsub_service_starting",
            broadcaster_id=self.broadcaster_id,
            bot_user_id=self.bot_user_id,
            auto_refresh=bool(self._refresh_token and self._client_secret),
        )

        while self._running:
            try:
                reconnect_url = await self._connect(ws_url)
                if reconnect_url:
                    ws_url = reconnect_url
                else:
                    ws_url = _TWITCH_WS_URL
                backoff = _BACKOFF_INITIAL
            except asyncio.CancelledError:
                break
            except (
                websockets.exceptions.WebSocketException,
                httpx.HTTPError,
                OSError,
            ) as exc:
                log.error("eventsub_connection_error", error=str(exc))
            except Exception as exc:  # noqa: BLE001
                log.error("eventsub_unexpected_error", error=str(exc))

            if not self._running:
                break

            log.info("eventsub_reconnecting", backoff_sec=backoff)
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, _BACKOFF_MAX)

        log.info("eventsub_service_stopped")

    def stop(self) -> None:
        """Stoppt die Reconnect-Schleife (graceful)."""
        self._running = False

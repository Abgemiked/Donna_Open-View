"""redis_subscriber.py — Twitch-Chat Pub/Sub Subscriber (DONNA-201).

Abonniert die Redis-Channels `twitch:msg` (Chatnachrichten) und
`twitch:event` (Stream-Events) auf DB 2. Redis läuft im chat-tool-Projekt
(Container `verwaltung-redis-1`, Netzwerk `verwaltung_default` — assistent-api
ist über `verwaltung_net` bereits darin).

Eingehende Chatnachrichten werden an dieselbe Verarbeitungs-Logik weitergeleitet
wie der HTTP-Endpoint `POST /chat/twitch` (`process_twitch_chat`), damit STM
(twitch_stm) UND LTM (twitch_ltm) identisch genutzt werden (DONNA-204).

Reconnect-Logik: exponential backoff, max 60s.
"""
from __future__ import annotations

import asyncio
import json
from typing import Any

from app.core.logger import get_logger

log = get_logger("service.redis_subscriber")

_TWITCH_MSG_CHANNEL = "twitch:msg"
_TWITCH_EVENT_CHANNEL = "twitch:event"

# Reconnect-Backoff
_BACKOFF_INITIAL_S = 1.0
_BACKOFF_MAX_S = 60.0


class RedisSubscriber:
    """Asyncio-basierter Redis-Pub/Sub-Subscriber für Twitch-Chat.

    Lifecycle:
        sub = RedisSubscriber(redis_url, app_state)
        sub.start()   # startet Hintergrund-Task
        ...
        await sub.stop()
    """

    def __init__(self, redis_url: str, app_state: Any) -> None:
        self._redis_url = redis_url
        self._app_state = app_state
        self._task: asyncio.Task | None = None
        self._stopped = asyncio.Event()
        self._redis = None  # type: ignore[var-annotated]

    # ── Lifecycle ─────────────────────────────────────────────────────────
    def start(self) -> None:
        """Startet den Subscriber als Hintergrund-Task."""
        if self._task is not None:
            log.warning("redis_subscriber_already_started")
            return
        self._stopped.clear()
        self._task = asyncio.create_task(self._run_loop())
        log.info("redis_subscriber_started", url=self._redis_url)

    async def stop(self) -> None:
        """Stoppt den Subscriber sauber."""
        self._stopped.set()
        if self._task is not None:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
            self._task = None
        if self._redis is not None:
            try:
                await self._redis.aclose()
            except Exception:  # noqa: BLE001
                pass
        log.info("redis_subscriber_stopped")

    # ── Main loop mit Reconnect ───────────────────────────────────────────
    async def _run_loop(self) -> None:
        """Verbindungs-Loop mit exponential backoff Reconnect (max 60s)."""
        import redis.asyncio as aioredis

        backoff = _BACKOFF_INITIAL_S
        while not self._stopped.is_set():
            try:
                self._redis = aioredis.from_url(
                    self._redis_url,
                    decode_responses=True,
                    socket_keepalive=True,
                    health_check_interval=30,
                )
                # Verbindung testen
                await self._redis.ping()
                log.info("redis_subscriber_connected", url=self._redis_url)
                backoff = _BACKOFF_INITIAL_S  # Reset nach erfolgreicher Verbindung

                pubsub = self._redis.pubsub()
                await pubsub.subscribe(_TWITCH_MSG_CHANNEL, _TWITCH_EVENT_CHANNEL)
                log.info(
                    "redis_subscriber_subscribed",
                    channels=[_TWITCH_MSG_CHANNEL, _TWITCH_EVENT_CHANNEL],
                )

                async for message in pubsub.listen():
                    if self._stopped.is_set():
                        break
                    if message.get("type") != "message":
                        continue
                    await self._dispatch(message)

            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001
                log.warning(
                    "redis_subscriber_connection_error",
                    error=str(exc),
                    retry_in_s=backoff,
                )
                if self._redis is not None:
                    try:
                        await self._redis.aclose()
                    except Exception:  # noqa: BLE001
                        pass
                    self._redis = None
                # Backoff vor Reconnect
                try:
                    await asyncio.wait_for(self._stopped.wait(), timeout=backoff)
                except asyncio.TimeoutError:
                    pass
                backoff = min(backoff * 2, _BACKOFF_MAX_S)

    # ── Dispatch ──────────────────────────────────────────────────────────
    async def _dispatch(self, message: dict) -> None:
        """Routet eine Redis-Nachricht an den passenden Handler."""
        channel = message.get("channel", "")
        raw = message.get("data", "")
        try:
            payload = json.loads(raw)
        except (json.JSONDecodeError, ValueError):
            log.warning("redis_subscriber_bad_payload", channel=channel)
            return

        if channel == _TWITCH_MSG_CHANNEL:
            await self._handle_twitch_msg(payload)
        elif channel == _TWITCH_EVENT_CHANNEL:
            await self._handle_twitch_event(payload)

    async def _handle_twitch_msg(self, payload: dict) -> None:
        """Verarbeitet eine Twitch-Chatnachricht.

        Format: {"channel": "#abgemiked", "user": "...", "text": "...",
                 "badges": {...}}

        Leitet an `process_twitch_chat` weiter — dieselbe Logik wie
        `POST /chat/twitch`, inkl. STM (twitch_stm) + LTM (twitch_ltm).
        """
        channel = payload.get("channel", "")
        user = payload.get("user", "") or "twitch_anon"
        text = (payload.get("text", "") or "").strip()
        badges = payload.get("badges", {}) or {}

        if not text:
            return

        # Nur Nachrichten an Donna verarbeiten (Mention oder Command-Präfix).
        # Verhindert dass jede Chat-Zeile ein LLM auslöst.
        lowered = text.lower()
        is_mention = lowered.startswith("@donna") or "donna" in lowered.split()[0:1]
        is_command = text.startswith("!donna")
        if not (is_mention or is_command):
            log.debug("redis_twitch_msg_skipped_no_mention", user=user)
            return

        # Import lokal — vermeidet Circular-Import (chat.py importiert nichts von hier).
        from app.routes.chat import process_twitch_chat

        # Badges als extra_context (Subscriber/VIP/Mod-Status kann Tonfall beeinflussen)
        extra_context = None
        if badges:
            badge_names = ", ".join(k for k in badges.keys())
            if badge_names:
                extra_context = f"[Viewer-Badges: {badge_names}]"

        # DONNA-211: explizites Live-Flag aus dem Bot-Payload durchreichen
        # (Vorrang vor Helix-API-Check). None wenn nicht mitgeschickt.
        stream_live_flag = payload.get("stream_live")
        if stream_live_flag is not None:
            stream_live_flag = bool(stream_live_flag)

        try:
            result = await process_twitch_chat(
                app_state=self._app_state,
                message=text,
                session_id=user,
                extra_context=extra_context,
                channel=channel,
                stream_live_flag=stream_live_flag,
            )
            # DONNA-213: response kann None sein (Caps-Spam/Echo verworfen) →
            # dann sendet der Bot bewusst nichts.
            response = result.get("response") or ""
            log.info(
                "redis_twitch_msg_processed",
                user=user,
                channel=channel,
                response_len=len(response),
            )
            # Antwort zurück in den Chat senden — via TwitchBotService falls aktiv.
            twitch_bot = getattr(self._app_state, "twitch_bot", None)
            if twitch_bot is not None and response:
                try:
                    await twitch_bot.send_message(response)
                except Exception as _send_e:  # noqa: BLE001
                    log.warning("redis_twitch_reply_send_failed", error=str(_send_e))
        except Exception as exc:  # noqa: BLE001
            log.error("redis_twitch_msg_failed", user=user, error=str(exc))

    async def _handle_twitch_event(self, payload: dict) -> None:
        """Verarbeitet ein Stream-Event von twitch:event."""
        event_type = payload.get("type", "") or payload.get("event_type", "")
        log.info("redis_twitch_event", event_type=event_type)
        await self.handle_eventsub_notification(event_type, payload)

    # ── EventSub-Bridge (von routes/chat.py /webhook/twitch genutzt) ──────
    async def handle_eventsub_notification(
        self, sub_type: str, event: dict,
    ) -> None:
        """Gemeinsamer Handler für Stream-Events.

        Wird sowohl vom Redis-Channel `twitch:event` als auch vom
        EventSub-Webhook (`POST /chat/webhook/twitch`, DONNA-205) aufgerufen,
        damit beide Pfade dieselbe Logik durchlaufen.
        """
        log.info(
            "twitch_eventsub_notification",
            sub_type=sub_type,
            keys=list(event.keys())[:8],
        )
        # Aktuell: nur strukturiertes Logging. Weiterverarbeitung (z.B.
        # proaktive Reaktionen auf follow/sub/raid) kann hier ergänzt werden.

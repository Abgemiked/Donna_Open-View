"""DONNA-112: stream_live_watcher.py — Proaktiver Twitch-Chat bei Stream-Statuswechsel.

Pollt alle 2 Minuten den Helix-Live-Status und erkennt offline→live / live→offline
Wechsel. Bei Wechsel: channel.send() mit Gemini-generierter Nachricht.

Feature-Flag: DONNA_TWITCH_PROACTIVE=true/false (default: false)
State-Tracking: verhindert Doppel-Send bei wiederholtem Polling im gleichen Zustand.
"""
from __future__ import annotations

import os
from typing import Optional

import httpx

from app.core.logger import get_logger

log = get_logger("jobs.stream_live_watcher")

# Feature-Flag — default: false (opt-in)
DONNA_TWITCH_PROACTIVE_ENABLED: bool = (
    os.environ.get("DONNA_TWITCH_PROACTIVE", "false").lower() == "true"
)

# State-Tracking: None = unbekannt (erster Lauf), True = live, False = offline
_last_live_state: Optional[bool] = None

# Twitch Helix API
_HELIX_STREAMS_URL = "https://api.twitch.tv/helix/streams"


async def _fetch_live_status(
    broadcaster_login: str,
    client_id: str,
    access_token: str,
) -> Optional[dict]:
    """Holt den aktuellen Live-Status via Helix API.

    Returns:
        Dict mit stream-Daten (game_name, title etc.) wenn live, None wenn offline.
        None auch bei API-Fehlern (fail-safe: kein Crash, kein Doppel-Send).
    """
    headers = {
        "Client-ID": client_id,
        "Authorization": f"Bearer {access_token}",
    }
    params = {"user_login": broadcaster_login}
    try:
        async with httpx.AsyncClient(timeout=8.0) as client:
            r = await client.get(_HELIX_STREAMS_URL, headers=headers, params=params)
            r.raise_for_status()
            data = r.json()
            streams = data.get("data", [])
            if streams:
                return streams[0]
            return None
    except Exception as exc:  # noqa: BLE001
        log.warning("helix_fetch_failed", error=str(exc))
        return None


async def _generate_greeting(game_name: str, gemini_client) -> str:
    """Generiert eine Begrüßungs-Nachricht via Gemini (max 280 Zeichen).

    Fallback auf statische Nachricht wenn Gemini nicht verfügbar.
    """
    default = f"Stream gestartet! Mike spielt heute {game_name} 🎮 Habt Spaß!"
    if gemini_client is None:
        return default
    try:
        prompt = (
            f"Donna ist der freundliche Twitch-Chat-Bot von abgemiked. "
            f"Mike hat gerade seinen Stream gestartet und spielt '{game_name}'. "
            f"Schreib eine kurze, lockere Begrüßungs-Nachricht für den Chat "
            f"(max 1-2 Sätze, kein Markdown, kein '@', kein '#'). "
            f"Auf Deutsch, locker und direkt."
        )
        response = await gemini_client.generate_async(prompt)
        if response and len(response.strip()) > 5:
            return response.strip()[:280]
    except Exception as exc:  # noqa: BLE001
        log.warning("greeting_gemini_failed", error=str(exc))
    return default


async def _generate_farewell(gemini_client) -> str:
    """Generiert eine Verabschiedungs-Nachricht via Gemini.

    Fallback auf statische Nachricht wenn Gemini nicht verfügbar.
    """
    default = "Stream vorbei! War schön heute dabei zu sein 👋 Bis zum nächsten Mal!"
    if gemini_client is None:
        return default
    try:
        prompt = (
            "Donna ist der freundliche Twitch-Chat-Bot von abgemiked. "
            "Der Stream ist gerade zu Ende gegangen. "
            "Schreib eine kurze, herzliche Verabschiedungs-Nachricht für den Chat "
            "(max 1-2 Sätze, kein Markdown, kein '@', kein '#'). "
            "Auf Deutsch, locker und direkt."
        )
        response = await gemini_client.generate_async(prompt)
        if response and len(response.strip()) > 5:
            return response.strip()[:280]
    except Exception as exc:  # noqa: BLE001
        log.warning("farewell_gemini_failed", error=str(exc))
    return default


async def check_and_notify(
    *,
    twitch_bot_service=None,
    gemini_client=None,
    broadcaster_login: str = "your-twitch-channel",
    client_id: Optional[str] = None,
    access_token: Optional[str] = None,
) -> None:
    """Haupt-Job: pollt Helix, erkennt Statuswechsel, sendet Chat-Nachricht.

    Wird alle 2 Minuten via APScheduler aufgerufen.
    Kein Crash bei Fehlern — alles logged und ignored (fire-and-forget-Pattern).

    Args:
        twitch_bot_service: TwitchBotService-Instanz (für get_channel()).
        gemini_client: GeminiClient-Instanz (für Nachrichtengenerierung).
        broadcaster_login: Twitch-Login des Streamers.
        client_id: Twitch App Client-ID.
        access_token: Twitch Bot OAuth-Token (für Helix-Calls).
    """
    global _last_live_state  # noqa: PLW0603

    if not DONNA_TWITCH_PROACTIVE_ENABLED:
        return

    if not client_id or not access_token:
        log.debug("stream_live_watcher_skipped", reason="client_id or access_token missing")
        return

    # IRC-Token hat "oauth:"-Präfix — Helix-API braucht Token ohne Präfix
    helix_token = access_token.removeprefix("oauth:")
    stream_data = await _fetch_live_status(broadcaster_login, client_id, helix_token)
    is_live_now = stream_data is not None

    # State-Transition erkennen
    if _last_live_state is None:
        # Erster Lauf — Zustand merken, aber NICHT senden
        # (verhindert Doppel-Send beim Neustart während laufendem Stream)
        log.info("stream_live_watcher_initialized", is_live=is_live_now)
        _last_live_state = is_live_now
        return

    if is_live_now == _last_live_state:
        # Kein Wechsel — nichts tun
        return

    # Wechsel erkannt
    _last_live_state = is_live_now

    if twitch_bot_service is None:
        log.warning("stream_live_watcher_no_bot", reason="TwitchBotService not available")
        return

    channel = twitch_bot_service.get_channel()
    if channel is None:
        log.warning("stream_live_watcher_no_channel", reason="Bot not connected or channel not joined")
        return

    if is_live_now:
        # offline → live
        game_name = (stream_data or {}).get("game_name", "etwas Spannendes")
        message = await _generate_greeting(game_name, gemini_client)
        log.info("stream_live_watcher_live", game=game_name, message_len=len(message))
        try:
            await channel.send(message)
        except Exception as exc:  # noqa: BLE001
            log.warning("stream_live_watcher_send_failed", error=str(exc))
    else:
        # live → offline
        message = await _generate_farewell(gemini_client)
        log.info("stream_live_watcher_offline", message_len=len(message))
        try:
            await channel.send(message)
        except Exception as exc:  # noqa: BLE001
            log.warning("stream_live_watcher_send_failed", error=str(exc))


def reset_state_for_testing() -> None:
    """Setzt den Modul-State zurück — nur für Unit-Tests."""
    global _last_live_state  # noqa: PLW0603
    _last_live_state = None

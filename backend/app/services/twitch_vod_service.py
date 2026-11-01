"""twitch_vod_service.py — DONNA-24: Vergangene Streams via Twitch Helix VOD-API.

Ruft die letzten archivierten VODs (type=archive) für abgemiked ab.

Endpoints:
  GET /helix/users?login=abgemiked   → broadcaster_id (TTL 24h gecacht)
  GET /helix/videos?user_id=...      → VOD-Liste (TTL 5min gecacht)

Token-Strategie:
  Identisch zu twitch_live_check.py:
  1. client_credentials (App-Token) wenn TWITCH_CLIENT_SECRET gesetzt
  2. Fallback: bot_user_token (User-OAuth, oauth:-Prefix wird entfernt)

Format-Output:
  Kompakt für Twitch-Chat (unter 400 Zeichen) und lesbar für Donna-App.
"""
from __future__ import annotations

import asyncio
import re
import time
from datetime import datetime, timezone
from typing import Any
from zoneinfo import ZoneInfo

import httpx

from app.core.logger import get_logger

log = get_logger("service.twitch_vod")

# ── Caches ────────────────────────────────────────────────────────────────────

# broadcaster_id: gecacht für 24h — ändert sich nie
_broadcaster_cache: dict[str, Any] = {}
_BROADCASTER_TTL = 86_400.0

# VOD-Liste: gecacht für 5 Minuten
_vod_cache: dict[str, Any] = {}
_VOD_CACHE_TTL = 300.0

# App-Token — lokaler Cache (kein Import aus twitch_live_check, verhindert circular imports)
_token_cache: dict[str, Any] = {}
_token_lock = asyncio.Lock()  # verhindert parallele Token-Refresh-Requests (TOCTOU)


# ── Token-Infrastruktur ───────────────────────────────────────────────────────

async def _fetch_app_token(client_id: str, client_secret: str) -> str | None:
    """Holt ein frisches App-Token via client_credentials.

    SECURITY: client_secret wird als POST-Body (data=) gesendet, NICHT als
    URL-Query-Parameter — verhindert Credential-Leak in Server/Proxy-Logs.
    """
    try:
        async with httpx.AsyncClient(timeout=8.0) as client:
            resp = await client.post(
                "https://id.twitch.tv/oauth2/token",
                data={  # data= → application/x-www-form-urlencoded Body, NICHT URL-Query
                    "client_id": client_id,
                    "client_secret": client_secret,
                    "grant_type": "client_credentials",
                },
            )
            resp.raise_for_status()
            data = resp.json()
            token = data.get("access_token")
            expires_in = data.get("expires_in", 3600)
            _token_cache["token"] = token
            _token_cache["expires_at"] = time.time() + expires_in - 60
            log.info("twitch_vod_app_token_refreshed")
            return token
    except Exception as e:  # noqa: BLE001
        log.warning("twitch_vod_app_token_error", error=str(e))
    return None


async def _get_app_token(client_id: str, client_secret: str) -> str | None:
    """Gibt ein gültiges App-Token zurück, refresht bei Bedarf.

    asyncio.Lock verhindert parallele Refresh-Requests bei concurrent Cache-Miss.
    """
    async with _token_lock:
        now = time.time()
        if _token_cache.get("token") and _token_cache.get("expires_at", 0) > now:
            return _token_cache["token"]  # type: ignore[return-value]
        return await _fetch_app_token(client_id, client_secret)


async def _get_credentials() -> tuple[str | None, str | None]:
    """Gibt (client_id, token) zurück — liest aus Settings."""
    try:
        from app.config import get_settings
        s = get_settings()
        client_id: str | None = s.twitch_client_id
        client_secret: str | None = s.twitch_client_secret
        bot_user_token: str | None = getattr(s, "twitch_bot_token", None) or None

        if not client_id:
            return None, None

        token: str | None = None
        if client_secret:
            token = await _get_app_token(client_id, client_secret)
        if not token and bot_user_token:
            token = bot_user_token.removeprefix("oauth:")

        return client_id, token
    except Exception as e:  # noqa: BLE001
        log.warning("twitch_vod_credentials_error", error=str(e))
        return None, None


# ── Helix-Helpers ─────────────────────────────────────────────────────────────

async def _get_broadcaster_id(
    login: str,
    client_id: str,
    token: str,
) -> str | None:
    """Gibt die numerische broadcaster_id für einen Twitch-Login zurück (gecacht 24h)."""
    now = time.time()
    cached = _broadcaster_cache.get(login)
    if cached and now - cached.get("ts", 0) < _BROADCASTER_TTL:
        return cached["id"]

    try:
        async with httpx.AsyncClient(timeout=8.0) as client:
            resp = await client.get(
                "https://api.twitch.tv/helix/users",
                params={"login": login},
                headers={"Client-Id": client_id, "Authorization": f"Bearer {token}"},
            )
            if resp.status_code != 200:
                log.warning("twitch_vod_users_error", status=resp.status_code, login=login)
                return None
            data = resp.json().get("data", [])
            if not data:
                log.warning("twitch_vod_user_not_found", login=login)
                return None  # Nicht cachen — Kanal könnte später existieren
            broadcaster_id = data[0]["id"]
            _broadcaster_cache[login] = {"id": broadcaster_id, "ts": now}  # nur positive IDs
            log.debug("twitch_vod_broadcaster_id_cached", login=login, id=broadcaster_id)
            return broadcaster_id
    except Exception as e:  # noqa: BLE001
        log.warning("twitch_vod_users_fetch_error", error=str(e))
        return None


async def _fetch_vods(
    broadcaster_id: str,
    client_id: str,
    token: str,
    first: int = 5,
) -> list[dict[str, Any]]:
    """Ruft die letzten archivierten VODs ab (gecacht 5 Minuten)."""
    now = time.time()
    cache_key = f"vods_{broadcaster_id}_{first}"
    cached = _vod_cache.get(cache_key)
    if cached and now - cached.get("ts", 0) < _VOD_CACHE_TTL:
        return cached["vods"]

    try:
        async with httpx.AsyncClient(timeout=8.0) as client:
            resp = await client.get(
                "https://api.twitch.tv/helix/videos",
                params={
                    "user_id": broadcaster_id,
                    "type": "archive",   # nur echte Stream-Aufzeichnungen
                    "first": first,
                    "sort": "time",      # neueste zuerst
                },
                headers={"Client-Id": client_id, "Authorization": f"Bearer {token}"},
            )
            if resp.status_code != 200:
                log.warning("twitch_vod_videos_error", status=resp.status_code)
                return []
            vods = resp.json().get("data", [])
            _vod_cache[cache_key] = {"vods": vods, "ts": now}
            log.debug("twitch_vod_fetched", count=len(vods))
            return vods
    except Exception as e:  # noqa: BLE001
        log.warning("twitch_vod_videos_fetch_error", error=str(e))
        return []


# ── Formatierungs-Helpers ─────────────────────────────────────────────────────

def _parse_duration(duration_str: str) -> int:
    """Parst Twitch-Dauer-Format (z.B. "3h21m45s") in Sekunden."""
    total = 0
    for value, unit in re.findall(r"(\d+)([hms])", duration_str):
        if unit == "h":
            total += int(value) * 3600
        elif unit == "m":
            total += int(value) * 60
        elif unit == "s":
            total += int(value)
    return total


def _format_duration(seconds: int) -> str:
    """Formatiert Sekunden als 'Xh Ym' oder 'Ym' (für kurze Streams)."""
    h = seconds // 3600
    m = (seconds % 3600) // 60
    if h > 0:
        return f"{h}h {m}m" if m > 0 else f"{h}h"
    return f"{m}m"


def _format_date_de(iso_str: str) -> str:
    """Parst ISO-8601 und gibt ein deutsches Datum zurück (z.B. '06. Mai').

    Nutzt ZoneInfo("Europe/Berlin") für korrekte CET/CEST-Umrechnung — nicht
    hartkodiertes UTC+2, das im Winter (CET = UTC+1) falsche Daten liefert.
    """
    try:
        dt = datetime.fromisoformat(iso_str.replace("Z", "+00:00"))
        dt_berlin = dt.astimezone(ZoneInfo("Europe/Berlin"))
        months_de = [
            "Jan", "Feb", "Mär", "Apr", "Mai", "Jun",
            "Jul", "Aug", "Sep", "Okt", "Nov", "Dez",
        ]
        return f"{dt_berlin.day:02d}. {months_de[dt_berlin.month - 1]}"
    except Exception:  # noqa: BLE001
        return "?"


def _relative_age(iso_str: str) -> str:
    """Gibt relative Zeit zurück: 'heute', 'gestern', 'vor N Tagen', 'vor N Wochen'.

    Guard für negative Tage (Future-Timestamp / Clock-Skew) → 'gerade eben'.
    """
    try:
        dt = datetime.fromisoformat(iso_str.replace("Z", "+00:00"))
        now = datetime.now(timezone.utc)
        diff = now - dt
        days = diff.days
        if days < 0:
            return "gerade eben"  # Future-Timestamp / minimaler Clock-Skew
        if days == 0:
            return "heute"
        if days == 1:
            return "gestern"
        if days < 7:
            return f"vor {days} Tagen"
        weeks = days // 7
        if weeks == 1:
            return "vor einer Woche"
        if weeks < 5:
            return f"vor {weeks} Wochen"
        months = days // 30
        if months == 1:
            return "vor einem Monat"
        return f"vor {months} Monaten"
    except Exception:  # noqa: BLE001
        return ""


# ── Öffentliche API ───────────────────────────────────────────────────────────

async def fetch_last_vod(login: str = "your-twitch-channel") -> str | None:
    """Gibt eine kompakte Beschreibung des letzten Streams zurück.

    Format für Twitch-Chat (unter 400 Zeichen):
    "Letzter Stream: 'Titel' — 06. Mai, 3h 21m | twitch.tv/videos/12345"

    Returns None bei API-Fehler oder keine VODs.
    """
    client_id, token = await _get_credentials()
    if not client_id or not token:
        log.warning("twitch_vod_no_credentials")
        return None

    broadcaster_id = await _get_broadcaster_id(login, client_id, token)
    if not broadcaster_id:
        return None

    vods = await _fetch_vods(broadcaster_id, client_id, token, first=1)
    if not vods:
        log.info("twitch_vod_no_vods", login=login)
        return None

    vod = vods[0]
    title = (vod.get("title") or "Unbekannt")[:60]
    duration_raw = vod.get("duration", "")
    duration_secs = _parse_duration(duration_raw)
    # Live-VOD: duration="" → Stream läuft noch; sonst normale Länge
    if not duration_raw:
        duration_str = "läuft noch"
    elif duration_secs > 0:
        duration_str = _format_duration(duration_secs)
    else:
        duration_str = "?"
    date_str = _format_date_de(vod.get("created_at", ""))
    age_str = _relative_age(vod.get("created_at", ""))
    url = vod.get("url", "")

    # Kompakt aber informativ — passt in Twitch-Chat
    age_part = f" ({age_str})" if age_str else ""
    url_part = f" | {url}" if url else ""
    return f"Letzter Stream{age_part}: \"{title}\" — {date_str}, {duration_str}{url_part}"


async def fetch_recent_vods(login: str = "your-twitch-channel", count: int = 3) -> str | None:
    """Gibt eine Liste der letzten N Streams zurück (für Mehrfach-Abfragen).

    Format:
    "Letzte Streams:
    • 'Titel' — 06. Mai, 3h 21m
    • 'Titel' — 05. Mai, 2h 45m
    • 'Titel' — 03. Mai, 4h 10m"
    """
    client_id, token = await _get_credentials()
    if not client_id or not token:
        return None

    broadcaster_id = await _get_broadcaster_id(login, client_id, token)
    if not broadcaster_id:
        return None

    vods = await _fetch_vods(broadcaster_id, client_id, token, first=min(count, 10))
    if not vods:
        return None

    lines = []
    for vod in vods[:count]:
        title = (vod.get("title") or "Unbekannt")[:50]
        duration_secs = _parse_duration(vod.get("duration", ""))
        duration_str = _format_duration(duration_secs) if duration_secs else "?"
        date_str = _format_date_de(vod.get("created_at", ""))
        lines.append(f"• \"{title}\" — {date_str}, {duration_str}")

    if not lines:
        return None

    return "Letzte Streams:\n" + "\n".join(lines)

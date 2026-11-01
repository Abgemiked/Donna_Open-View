"""DONNA-241: Valorant Stats Service.

Primärquelle: Henrik Dev API (api.henrikdev.xyz) — benötigt HENRIKDEV_API_KEY in .env
Fallback:     StreamElements/Hexe-Bot Widget (commands.manuel-hexe.de) wenn API Key
              fehlt oder HenrikDev nicht erreichbar ist.

Env:
    HENRIKDEV_API_KEY  — API Key für api.henrikdev.xyz (z.B. HDEV-xxxx-xxxx-...)
"""
from __future__ import annotations

import asyncio
import datetime
import os
import zoneinfo

import httpx

from app.core.logger import get_logger

log = get_logger("service.valorant")

# ---------------------------------------------------------------------------
# Konfiguration
# ---------------------------------------------------------------------------

# API Key aus Umgebungsvariable — NIEMALS hardcoden
_HENRIK_API_KEY: str = os.environ.get("HENRIKDEV_API_KEY", "")

_HENRIK_BASE = "https://api.henrikdev.xyz"
_HENRIK_IGN = "Abgemiked"
_HENRIK_TAG = "Tier1"
_HENRIK_REGION = "eu"

_HENRIK_MMR_URL = (
    f"{_HENRIK_BASE}/valorant/v3/mmr/{_HENRIK_REGION}/pc"
    f"/{_HENRIK_IGN}/{_HENRIK_TAG}"
)
_HENRIK_MATCHES_URL = (
    f"{_HENRIK_BASE}/valorant/v1/stored-matches/{_HENRIK_REGION}"
    f"/{_HENRIK_IGN}/{_HENRIK_TAG}"
)

# Fallback: Hexe-Widget
_HEXE_ENDPOINT = (
    "https://commands.manuel-hexe.de/widgets/simple/477269490/data?index=0"
)

_TIMEOUT_S = 6.0


# ---------------------------------------------------------------------------
# HenrikDev API (primäre Quelle)
# ---------------------------------------------------------------------------

async def _fetch_henrikdev(session_start: datetime.datetime | None = None) -> dict:
    """Fetcht Rang + Session-Stats über die Henrik Dev API.

    Gibt ein normalisiertes Stats-Dict zurück oder {"error": "..."} bei Fehler.
    Setzt `source` auf "henrikdev".

    Args:
        session_start: Wenn gesetzt, werden nur Matches ab diesem UTC-Zeitpunkt
                       gezählt (z.B. Stream-Start). Sonst werden alle heutigen
                       Matches (Berliner Datum) gezählt.
    """
    if not _HENRIK_API_KEY:
        return {"error": "no_api_key"}

    headers = {"Authorization": _HENRIK_API_KEY}

    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT_S) as client:
            mmr_resp, matches_resp = await asyncio.gather(
                client.get(_HENRIK_MMR_URL, headers=headers),
                client.get(_HENRIK_MATCHES_URL, headers=headers),
            )
            mmr_resp.raise_for_status()
            matches_resp.raise_for_status()

            mmr_data = mmr_resp.json()
            matches_data = matches_resp.json()

    except httpx.HTTPStatusError as exc:
        log.warning(
            "valorant_henrikdev_http_error",
            status=exc.response.status_code,
            url=str(exc.request.url),
        )
        return {"error": f"http_{exc.response.status_code}"}
    except httpx.TimeoutException:
        log.warning("valorant_henrikdev_timeout")
        return {"error": "timeout"}
    except Exception as exc:  # noqa: BLE001
        log.warning("valorant_henrikdev_failed", error=str(exc))
        return {"error": str(exc)}

    # --- MMR-Daten extrahieren ---
    current = mmr_data.get("data", {}).get("current", {})
    tier_info = current.get("tier", {})
    rank_name: str = tier_info.get("name", "unbekannt")
    rr: int = int(current.get("rr", 0))
    last_change: int = int(current.get("last_change", 0))

    # --- Session-Stats aus heutigen Matches berechnen ---
    # DONNA-250: Timezone-Fix — stored-matches Timestamps sind UTC (Z-Suffix).
    # Aber Mike spielt in Europe/Berlin (UTC+2). Ein Match um 23:30 UTC
    # ist 01:30 Berliner Zeit am nächsten Tag. Wir nutzen das Berliner Datum
    # damit Matches die nach Mitternacht Berlin-Zeit gespielt wurden korrekt
    # dem aktuellen Tag zugeordnet werden.
    try:
        _berlin = zoneinfo.ZoneInfo("Europe/Berlin")
    except Exception:  # noqa: BLE001
        _berlin = datetime.timezone(datetime.timedelta(hours=2))  # Fallback: UTC+2

    today_berlin = datetime.datetime.now(_berlin).date()
    wins = 0
    losses = 0
    session_rr = 0

    # DONNA-264: session_start UTC-aware machen für Vergleich
    _session_start_utc: datetime.datetime | None = None
    if session_start is not None:
        if session_start.tzinfo is None:
            _session_start_utc = session_start.replace(tzinfo=datetime.timezone.utc)
        else:
            _session_start_utc = session_start.astimezone(datetime.timezone.utc)

    raw_matches = matches_data.get("data", [])
    if isinstance(raw_matches, list):
        for match in raw_matches:
            # Timestamp aus meta.started_at (ISO-UTC, z.B. "2026-05-24T01:38:23.217Z")
            started_at = match.get("meta", {}).get("started_at") or match.get(
                "started_at"
            )
            match_dt_utc: datetime.datetime | None = None
            match_date_berlin: datetime.date | None = None
            if isinstance(started_at, str):
                try:
                    # Z → +00:00 für fromisoformat-Kompatibilität (Python < 3.11)
                    match_dt_utc = datetime.datetime.fromisoformat(
                        started_at.replace("Z", "+00:00")
                    )
                    match_date_berlin = match_dt_utc.astimezone(_berlin).date()
                except ValueError:
                    pass
            elif isinstance(started_at, (int, float)):
                match_dt_utc = datetime.datetime.fromtimestamp(
                    started_at, tz=datetime.timezone.utc
                )
                match_date_berlin = match_dt_utc.astimezone(_berlin).date()

            # DONNA-264: Session-Start-Filter hat Vorrang vor Tages-Filter
            if _session_start_utc is not None:
                if match_dt_utc is None or match_dt_utc < _session_start_utc:
                    continue  # Match vor Stream-Start → überspringen
            else:
                if match_date_berlin != today_berlin:
                    continue  # nur heutige Matches (Berliner Datum) zählen

            # DONNA-250: Win/Loss aus teams-Scores berechnen (stored-matches v1 hat kein outcome-Feld)
            stats = match.get("stats", {})
            teams = match.get("teams", {})
            player_team = stats.get("team", "")  # "Blue" oder "Red"

            blue_score = teams.get("blue", 0) or 0
            red_score = teams.get("red", 0) or 0

            if player_team == "Blue":
                won = blue_score > red_score
                wins += 1 if won else 0
                losses += 0 if won else 1
            elif player_team == "Red":
                won = red_score > blue_score
                wins += 1 if won else 0
                losses += 0 if won else 1
            # Kein team-Feld → ignorieren

            # RR-Delta: stored-matches v1 hat kein rr_change-Feld.
            # session_rr bleibt 0 (wird durch last_change aus MMR-Endpoint ersetzt)
            rr_change = stats.get("rr_change") or match.get("mmr_change_to_last_game")
            if isinstance(rr_change, (int, float)):
                session_rr += int(rr_change)

    log.info(
        "valorant_henrikdev_fetched",
        rank=rank_name,
        rr=rr,
        session_rr=session_rr,
        wins=wins,
        losses=losses,
    )

    return {
        "rank": rank_name,
        "rr": rr,
        "rr_max": 100,
        "session_rr": session_rr,
        "wins": wins,
        "losses": losses,
        "last_match_rr": last_change,
        "source": "henrikdev",
    }


# ---------------------------------------------------------------------------
# Hexe-Widget (Fallback)
# ---------------------------------------------------------------------------

async def _fetch_hexe_fallback(reason: str) -> dict:
    """Fetcht Stats über den Hexe-Bot-Widget als Fallback.

    Gibt normalisiertes Stats-Dict zurück oder {"error": "..."}.
    Setzt `source` auf "hexe_widget_fallback".
    """
    log.info("valorant_fallback_hexe", reason=reason)
    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT_S) as client:
            resp = await client.get(_HEXE_ENDPOINT)
            resp.raise_for_status()
            data = resp.json()

        rank_name = data.get("rankName", "unbekannt")
        rr = data.get("rankRating", 0)
        rr_max = data.get("rankRatingMax", 100)
        session_rr = data.get("rankRatingToday", 0)
        wins = data.get("wins", 0)
        losses = data.get("losses", 0)
        last_match = data.get("lastMatchRRChange", 0)

        log.info(
            "valorant_hexe_fetched",
            rank=rank_name,
            rr=rr,
            session_rr=session_rr,
        )

        return {
            "rank": rank_name,
            "rr": rr,
            "rr_max": rr_max,
            "session_rr": session_rr,
            "wins": wins,
            "losses": losses,
            "last_match_rr": last_match,
            "source": "hexe_widget_fallback",
        }

    except httpx.HTTPStatusError as exc:
        log.warning("valorant_hexe_http_error", status=exc.response.status_code)
        return {"error": f"HTTP {exc.response.status_code}"}
    except httpx.TimeoutException:
        log.warning("valorant_hexe_timeout")
        return {"error": "timeout"}
    except Exception as exc:  # noqa: BLE001
        log.warning("valorant_hexe_failed", error=str(exc))
        return {"error": str(exc)}


# ---------------------------------------------------------------------------
# Öffentliche API
# ---------------------------------------------------------------------------

async def get_valorant_session_stats(
    session_start: datetime.datetime | None = None,
) -> dict:
    """Fetcht Mikes aktuellen Valorant Rang und Session-Stats.

    Versucht zuerst die Henrik Dev API. Fällt auf das Hexe-Widget zurück wenn:
    - HENRIKDEV_API_KEY nicht gesetzt
    - HenrikDev antwortet mit HTTP-Fehler oder Timeout

    Args:
        session_start: Optional — Stream-Start-Timestamp (UTC). Wenn gesetzt,
                       werden nur Matches ab diesem Zeitpunkt gezählt (DONNA-264).
                       Sonst Tages-Filter (Berliner Datum).

    Returns dict mit:
      rank        — z.B. "Platinum 2"
      rr          — aktuelle RR-Zahl (int)
      rr_max      — max RR (immer 100)
      session_rr  — RR-Delta seit session_start / heute (int, positiv = Gewinn)
      wins        — Session-Wins (int)
      losses      — Session-Losses (int)
      last_match_rr — RR-Delta letztes Spiel (int)
      source      — "henrikdev" | "hexe_widget_fallback"
    Oder bei Fehler beider Quellen: {"error": "..."}
    """
    result = await _fetch_henrikdev(session_start=session_start)

    if "error" in result:
        reason = result["error"]
        # Fallback: kein session_start — Hexe kennt nur Tages-Stats
        result = await _fetch_hexe_fallback(reason=reason)

    return result


# ---------------------------------------------------------------------------
# Prompt-Formatierung (unverändert — funktioniert mit beiden Quellen)
# ---------------------------------------------------------------------------

def format_valorant_for_prompt(stats: dict) -> str:
    """Formatiert Valorant-Stats als lesbaren Prompt-Einzeiler für Donna."""
    if "error" in stats:
        return f"[Valorant] Daten gerade nicht verfügbar ({stats['error']})"

    rank = stats.get("rank", "unbekannt")
    rr = stats.get("rr", 0)
    rr_max = stats.get("rr_max", 100)
    session_rr = stats.get("session_rr", 0)
    wins = stats.get("wins", 0)
    losses = stats.get("losses", 0)
    last_match = stats.get("last_match_rr", 0)

    rr_display = f"{rr}/{rr_max}" if rr_max != 100 else str(rr)
    session_sign = "+" if session_rr >= 0 else ""
    last_sign = "+" if last_match >= 0 else ""

    parts = [
        "[Valorant — Live-Stats]",
        f"Rang: {rank} ({rr_display} RR)",
        f"Session: {wins}W / {losses}L  (heute {session_sign}{session_rr} RR)",
        f"Letztes Spiel: {last_sign}{last_match} RR",
        "Mike spielt als Initiator Breach.",
    ]
    return "\n".join(parts)

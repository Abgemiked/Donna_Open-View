"""DONNA-243: Megabonk Stats Service — Steam API Integration.

Megabonk ist ein Steam-Speedrun-/Highscore-Spiel (Plattformer/Bonk-Mechanik).
Rekorde werden auf megabonk.leaderboard.gg geführt (früher megabonk.fun).

Spielzeit + Achievements → Steam Web API (öffentlich, API Key erforderlich).
Leaderboard → megabonk.leaderboard.gg ist Cloudflare-geschützt, kein direktes
Scraping möglich. Ranking wird manuell im LTM gespeichert (seed_games_ltm.py).

Mike's Steam ID: 76561199018091867
Megabonk App ID: 3405340
"""
from __future__ import annotations

import os

import httpx

from app.core.logger import get_logger

log = get_logger("service.megabonk")

STEAM_API_KEY = os.environ.get("STEAM_API_KEY", "")
MIKE_STEAM_ID = "76561199018091867"
MIKE_STEAM_NAME = "your-twitch-channel"
MEGABONK_APP_ID = 3405340

_LEADERBOARD_URL = "https://megabonk.leaderboard.gg/"
_TIMEOUT_S = 8.0


async def get_megabonk_stats() -> dict:
    """Fetcht Mike's Megabonk-Stats via Steam Web API.

    Liefert Spielzeit + Achievements aus der Steam API.
    Leaderboard-Ranking ist nicht automatisch abrufbar (Cloudflare-Schutz)
    und wird manuell via LTM gepflegt.

    Returns dict mit:
      available           — bool
      playtime_hours      — float (Gesamtspielzeit in Stunden)
      achievements_unlocked — int
      achievements_total  — int
      game_name           — str
      leaderboard_note    — str
      source              — str
    """
    if not STEAM_API_KEY:
        log.warning("megabonk_no_api_key")
        return {
            "available": False,
            "message": "Steam API Key nicht konfiguriert",
            "leaderboard_url": _LEADERBOARD_URL,
        }

    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT_S) as client:
            # Spielzeit aus GetOwnedGames
            owned_resp = await client.get(
                "https://api.steampowered.com/IPlayerService/GetOwnedGames/v1/",
                params={
                    "key": STEAM_API_KEY,
                    "steamid": MIKE_STEAM_ID,
                    "include_appinfo": True,
                    "format": "json",
                },
            )
            games = owned_resp.json().get("response", {}).get("games", [])
            megabonk = next(
                (g for g in games if g.get("appid") == MEGABONK_APP_ID), None
            )

            # Achievements aus GetPlayerAchievements
            ach_resp = await client.get(
                "https://api.steampowered.com/ISteamUserStats/GetPlayerAchievements/v1/",
                params={
                    "key": STEAM_API_KEY,
                    "steamid": MIKE_STEAM_ID,
                    "appid": MEGABONK_APP_ID,
                    "format": "json",
                },
            )
            ach_data = ach_resp.json().get("playerstats", {})
            achievements = ach_data.get("achievements", [])
            unlocked = [a for a in achievements if a.get("achieved") == 1]

        playtime_hours = (
            round(megabonk.get("playtime_forever", 0) / 60, 1) if megabonk else 0.0
        )
        game_name = megabonk.get("name", "Megabonk") if megabonk else "Megabonk"

        log.info(
            "megabonk_stats_fetched",
            playtime_hours=playtime_hours,
            achievements_unlocked=len(unlocked),
            achievements_total=len(achievements),
        )

        return {
            "available": True,
            "playtime_hours": playtime_hours,
            "achievements_unlocked": len(unlocked),
            "achievements_total": len(achievements),
            "game_name": game_name,
            "leaderboard_note": (
                "megabonk.leaderboard.gg hat keine öffentliche API — "
                "Ranking wird manuell im LTM gespeichert"
            ),
            "leaderboard_url": _LEADERBOARD_URL,
            "steam_id": MIKE_STEAM_ID,
            "source": "steam_api",
        }

    except httpx.TimeoutException:
        log.warning("megabonk_fetch_timeout")
        return {
            "available": False,
            "error": "Timeout beim Abrufen der Steam-Daten",
            "leaderboard_url": _LEADERBOARD_URL,
        }
    except Exception as exc:  # noqa: BLE001
        log.warning("megabonk_fetch_failed", error=str(exc))
        return {
            "available": False,
            "error": str(exc),
            "leaderboard_url": _LEADERBOARD_URL,
        }


def format_megabonk_for_prompt(stats: dict) -> str:
    """Formatiert Megabonk-Stats als lesbaren Prompt-Einzeiler für Donna."""
    if not stats.get("available"):
        reason = stats.get("error") or stats.get("message", "Daten nicht verfügbar")
        return f"Megabonk-Stats aktuell nicht verfügbar: {reason}"

    playtime = stats.get("playtime_hours", 0)
    unlocked = stats.get("achievements_unlocked", 0)
    total = stats.get("achievements_total", 0)
    game = stats.get("game_name", "Megabonk")
    note = stats.get(
        "leaderboard_note",
        "Leaderboard-Ranking: Mike auf megabonk.leaderboard.gg fragen",
    )

    return (
        f"Mike spielt {game} (Steam). "
        f"Spielzeit: {playtime}h. "
        f"Achievements: {unlocked}/{total} freigeschaltet. "
        f"{note}."
    )

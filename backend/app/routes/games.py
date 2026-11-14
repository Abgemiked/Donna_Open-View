"""DONNA-241/242/243: Games-Endpoints.

Routen:
  GET /games/valorant    — Valorant Rang + Session-Stats (Live vom Hexe-Widget)
  GET /games/hearthstone — Hearthstone Battlegrounds MMR (Firestone-Sync)
  GET /games/megabonk    — Megabonk Leaderboard-Stats

Auth: Bearer-token (require_admin).
"""
from __future__ import annotations

from fastapi import APIRouter, Depends

from app.core.auth import require_admin
from app.core.logger import get_logger
from app.services.valorant_service import get_valorant_session_stats
from app.services.hearthstone_service import get_hearthstone_mmr
from app.services.megabonk_service import get_megabonk_stats

log = get_logger("route.games")

router = APIRouter(prefix="/games", tags=["games"])


@router.get("/valorant")
async def valorant_stats(
    _: str = Depends(require_admin),
) -> dict:
    """Gibt Mikes aktuellen Valorant Rang und Session-Stats zurück.

    Datenquelle: Hexe-Bot StreamElements-Widget (JSON-Endpoint).
    Felder: rank, rr, rr_max, session_rr, wins, losses, last_match_rr, source
    """
    stats = await get_valorant_session_stats()
    log.info("games_valorant_requested", has_error="error" in stats)
    return stats


@router.get("/hearthstone")
async def hearthstone_stats(
    _: str = Depends(require_admin),
) -> dict:
    """Gibt Mikes Hearthstone Battlegrounds MMR zurück.

    Datenquelle: Firestone-Sync via Syncthing (/root/syncthing_data/firestone/latest.json).
    Felder: available, mmr, session_delta, last_updated (oder message/error)
    """
    stats = await get_hearthstone_mmr()
    log.info("games_hearthstone_requested", available=stats.get("available", False))
    return stats


@router.get("/megabonk")
async def megabonk_stats(
    _: str = Depends(require_admin),
) -> dict:
    """Gibt Mikes Megabonk Leaderboard-Stats zurück.

    Datenquelle: megabonk.leaderboard.gg (Cloudflare-geschützt — meist nicht verfügbar).
    Felder: available, note, leaderboard_url, steam_id (und rank/score wenn verfügbar)
    """
    stats = await get_megabonk_stats()
    log.info("games_megabonk_requested", available=stats.get("available", False))
    return stats

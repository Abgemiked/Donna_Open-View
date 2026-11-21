"""DONNA-241/242/243: Games-spezifisches Static Knowledge in LTM injizieren.

Injiziert Mikes Spiele-Facts als user_fact-Memories in den LTM-Endpoint.
Nutzt POST http://YOUR_SERVER_IP:8000/admin/memories (oder /ltm direkt).

Usage:
    python seed_games_ltm.py

Requires:
    ADMIN_TOKEN env-Variable oder hardcoded (nur für Seeding-Skripte).
"""
from __future__ import annotations

import asyncio
import os

import httpx

ADMIN_TOKEN = os.environ.get(
    "ADMIN_TOKEN",
    "YOUR_ADMIN_TOKEN_HERE",
)
BASE_URL = os.environ.get("DONNA_BASE_URL", "http://YOUR_SERVER_IP:8000")

GAMES_MEMORIES = [
    # ── Valorant (DONNA-241) ────────────────────────────────────────────────
    {
        "content": "Mike spielt Valorant als Initiator-Agent Breach.",
        "category": "user_fact",
        "session_id": "seed_games_241",
    },
    {
        "content": (
            "Breach ist ein schwedischer Initiator-Agent in Valorant mit Flash-, "
            "Stun- und Earthquake-Fähigkeiten. Mike gilt als aggressiver Initiator-Spieler."
        ),
        "category": "user_fact",
        "session_id": "seed_games_241",
    },
    {
        "content": (
            "Mikes Valorant-Rang lässt sich live über den Hexe-Bot-Widget abrufen "
            "(Endpunkt: /games/valorant). Zeigt Rank, RR, Session-Wins/Losses und letztes Spiel."
        ),
        "category": "user_fact",
        "session_id": "seed_games_241",
    },
    # ── Hearthstone Battlegrounds (DONNA-242) ───────────────────────────────
    {
        "content": (
            "Mike spielt ausschließlich Hearthstone Battlegrounds — KEINE anderen "
            "Hearthstone-Modi wie Ranked, Arena oder Tavern Brawl."
        ),
        "category": "user_fact",
        "session_id": "seed_games_242",
    },
    {
        "content": (
            "Hearthstone Battlegrounds ist ein Auto-Battler-Modus für 8 Spieler. "
            "Spieler kaufen Minions, stellen sie auf und kämpfen automatisch. "
            "MMR = Matchmaking Rating, zeigt Mikes Spielstärke."
        ),
        "category": "user_fact",
        "session_id": "seed_games_242",
    },
    {
        "content": (
            "Firestone (Overwolf-App) trackt Mikes Hearthstone-Battlegrounds-MMR automatisch "
            "wenn Hearthstone offen ist. Sync via Syncthing nach /root/syncthing_data/firestone/."
        ),
        "category": "user_fact",
        "session_id": "seed_games_242",
    },
    # ── Megabonk (DONNA-243) ─────────────────────────────────────────────────
    {
        "content": (
            "Megabonk ist ein PC-Speedrun- und Highscore-Spiel auf Steam "
            "(Plattformer mit Bonk-Mechanik). Mike (Steam: abgemiked, "
            "ID: 76561199018091867) ist aktiver Megabonk-Spieler."
        ),
        "category": "user_fact",
        "session_id": "seed_games_243",
    },
    {
        "content": (
            "Megabonk-Rekorde und Rankings werden auf megabonk.leaderboard.gg geführt "
            "(früher megabonk.fun). Das Leaderboard ist Cloudflare-geschützt — "
            "Mikes Rang lässt sich dort manuell einsehen."
        ),
        "category": "user_fact",
        "session_id": "seed_games_243",
    },
]


async def seed() -> None:
    headers = {
        "Authorization": f"Bearer {ADMIN_TOKEN}",
        "Content-Type": "application/json",
    }

    # Prüfen welcher Endpoint existiert: /ltm/store oder via store_memory direkt
    # Das Backend nutzt POST /ltm — aber der öffentliche Endpoint ist GET (nur lesen).
    # Wir nutzen POST /stm als Write-Endpoint und speichern mit role=assistant + session games_seed
    # → Alternativ: direkt über python auf dem Server (ltm_service.store_memory).
    # Sauberste Methode: admin/memories falls vorhanden, sonst direkter Python-Call.

    async with httpx.AsyncClient(timeout=15.0, headers=headers) as client:
        # Prüfe ob /admin/memories existiert
        probe = await client.get(f"{BASE_URL}/admin/memories")
        has_admin_memories = probe.status_code not in (404, 405)

        results = []
        for mem in GAMES_MEMORIES:
            if has_admin_memories and probe.status_code == 200:
                # Admin-Endpoint vorhanden
                resp = await client.post(
                    f"{BASE_URL}/admin/memories",
                    json=mem,
                )
            else:
                # Fallback: /ltm/seed (kann nicht existieren → wir loggen es)
                resp = await client.post(
                    f"{BASE_URL}/ltm/seed",
                    json=mem,
                )

            results.append({
                "content_preview": mem["content"][:60],
                "status": resp.status_code,
                "ok": resp.status_code in (200, 201),
            })
            print(
                f"  {'✓' if resp.status_code in (200, 201) else '✗'} "
                f"[{resp.status_code}] {mem['content'][:60]}..."
            )

    ok_count = sum(1 for r in results if r["ok"])
    print(f"\n{ok_count}/{len(results)} Memories erfolgreich gespeichert.")

    if ok_count < len(results):
        print(
            "\nHINWEIS: Fehlschläge bedeuten oft dass der /admin/memories-Endpoint "
            "noch nicht existiert. In diesem Fall direkt auf dem Server via:\n"
            "  ssh your-server\n"
            "  cd /opt/donna && python seed_games_ltm_direct.py"
        )


if __name__ == "__main__":
    asyncio.run(seed())

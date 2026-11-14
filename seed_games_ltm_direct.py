"""DONNA-241/242/243: Games LTM — direktes Seeding auf dem Server.

Läuft direkt auf dem Hetzner-Server im assistent-Verzeichnis.
Nutzt den LTMService direkt (kein HTTP-Roundtrip).

Usage (auf dem Server):
    cd /opt/donna
    python seed_games_ltm_direct.py
"""
from __future__ import annotations

import sys
import os

# Backend-Pfad hinzufügen
sys.path.insert(0, "/opt/donna/backend")
os.environ.setdefault("DONNA_MEM0", "true")
os.environ.setdefault("QDRANT_URL", "http://qdrant:6333")

from app.services.ltm_service import LTMService  # noqa: E402

GAMES_MEMORIES = [
    # ── Valorant (DONNA-241) ────────────────────────────────────────────────
    ("Mike spielt Valorant als Initiator-Agent Breach.", "user_fact", "seed_games_241"),
    (
        "Breach ist ein schwedischer Initiator-Agent in Valorant mit Flash-, "
        "Stun- und Earthquake-Fähigkeiten. Mike gilt als aggressiver Initiator-Spieler.",
        "user_fact",
        "seed_games_241",
    ),
    (
        "Mikes Valorant-Rang lässt sich live über den Donna-Games-Endpoint abrufen. "
        "Zeigt Rank, RR, Session-Wins/Losses und letztes Spiel.",
        "user_fact",
        "seed_games_241",
    ),
    # ── Hearthstone Battlegrounds (DONNA-242) ───────────────────────────────
    (
        "Mike spielt ausschließlich Hearthstone Battlegrounds — KEINE anderen "
        "Hearthstone-Modi wie Ranked, Arena oder Tavern Brawl.",
        "user_fact",
        "seed_games_242",
    ),
    (
        "Hearthstone Battlegrounds ist ein Auto-Battler-Modus für 8 Spieler. "
        "Spieler kaufen Minions, stellen sie auf und kämpfen automatisch. "
        "MMR zeigt Mikes Spielstärke im Battlegrounds-Modus.",
        "user_fact",
        "seed_games_242",
    ),
    (
        "Firestone (Overwolf-App) trackt Mikes Hearthstone-Battlegrounds-MMR automatisch "
        "wenn Hearthstone offen ist.",
        "user_fact",
        "seed_games_242",
    ),
    # ── Megabonk (DONNA-243) ─────────────────────────────────────────────────
    (
        "Megabonk ist ein PC-Speedrun- und Highscore-Spiel auf Steam "
        "(Plattformer mit Bonk-Mechanik). Mike — Steam: abgemiked, "
        "Steam-ID: 76561199018091867 — ist aktiver Megabonk-Spieler.",
        "user_fact",
        "seed_games_243",
    ),
    (
        "Megabonk-Rekorde und Rankings werden auf megabonk.leaderboard.gg geführt. "
        "Mikes Rang lässt sich dort manuell einsehen.",
        "user_fact",
        "seed_games_243",
    ),
]


def main() -> None:
    print("Games-LTM Seeding gestartet...")
    ltm = LTMService(db_path="/data/chroma/ltm")

    ok = 0
    fail = 0
    for content, category, session_id in GAMES_MEMORIES:
        try:
            mem_id = ltm.store_memory(
                session_id=session_id,
                content=content,
                category=category,
            )
            if mem_id:
                print(f"  ✓ [{category}] {content[:70]}...")
                ok += 1
            else:
                print(f"  ⚠ SKIP (dedup/pii) [{category}] {content[:70]}...")
                ok += 1  # Dedup = nicht nötig, kein Fehler
        except Exception as exc:  # noqa: BLE001
            print(f"  ✗ FEHLER [{category}] {content[:70]}... → {exc}")
            fail += 1

    print(f"\nErgebnis: {ok}/{len(GAMES_MEMORIES)} Memories gespeichert.")
    if fail:
        print(f"  {fail} Fehler — bitte Logs prüfen.")


if __name__ == "__main__":
    main()

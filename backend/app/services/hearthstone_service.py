"""DONNA-242 / DONNA-249 / DONNA-253: Hearthstone Battlegrounds MMR Service.

Liest Sync-Daten aus dem Syncthing-Verzeichnis.

Datenfluss (DONNA-253 — nativer Log-Parser, kein Overwolf/Firestone nötig):
  1. scripts/hs_log_watcher.py (Windows Startup, läuft dauerhaft) liest:
     - Hearthstone BnetBar.log → MMR-Änderungen (OnBaconRatingUpdated/OnBaconRatingDuoUpdated)
     - Hearthstone Power.log   → Placement + Game-Typ
     Schreibt:
     - C:\\Sync\\firestone-mmr\\latest.json  (source: "hs_log_parser")
     - C:\\Sync\\firestone-mmr\\history.json (Langzeit-Verlauf, max 200 Matches)
  2. Fallback: scripts/firestone_export.py (wenn source != "hs_log_parser")
  3. Syncthing (lokal) synchronisiert C:\\Sync\\firestone-mmr → /var/syncthing/firestone (Server)
  4. Syncthing-Volume ist im assistent-api Container als /syncthing gemountet (read-only)
  5. Dieser Service liest /syncthing/firestone/latest.json

JSON-Format latest.json:
  {
    "last_updated": "2026-05-24T20:00:00+00:00",
    "source": "hs_log_parser",  -- DONNA-253: gesetzt vom Log-Watcher (oder fehlt bei Firestone)
    "solo": {"player_rank": 5800, "new_player_rank": 5905, "result": "won", "game_mode": "battlegrounds", ...},
    "duo":  {"player_rank": 5905, "new_player_rank": 5989, "result": "won", "game_mode": "battlegrounds-duo", ...},
    "recent_matches": [<letzte 10 Matches, alle Modi>]
  }

Neue Felder in Matches (DONNA-253, nur bei source="hs_log_parser"):
  - "placement": int (1-8 Solo, 1-4 Duo) — echtes Placement aus Power.log
  - "timestamp":  ISO8601 (echte Zeit, UTC) — Firestone-Daten haben timestamp=null

Wenn Firestone-Sync noch nicht eingerichtet ist (Pfad fehlt):
  → {"available": False, "message": "Firestone-Sync noch nicht eingerichtet"}
"""
from __future__ import annotations

import json
import os
from datetime import date, datetime, timezone

from app.core.logger import get_logger

log = get_logger("service.hearthstone")

# Pfad im Container: syncthing_data-Volume ist als /syncthing gemountet (ro)
# Auf dem Host: /var/lib/docker/volumes/assistent_syncthing_data/_data/firestone/latest.json
FIRESTONE_SYNC_PATH = "/syncthing/firestone/latest.json"


def _compute_session_delta(recent_matches: list[dict]) -> int | None:
    """DONNA-249: Berechnet MMR-Delta aus den heutigen Matches in recent_matches.

    Nimmt alle Matches von heute (UTC-Datum), berechnet Differenz zwischen
    erstem und letztem heutigen Match (new_player_rank).
    Gibt None zurück wenn weniger als 2 heutige Matches vorhanden.
    """
    today = date.today()
    today_matches = []
    for m in recent_matches:
        ts = m.get("timestamp")
        if not ts:
            continue
        try:
            dt = datetime.fromisoformat(ts.replace("Z", "+00:00"))
            if dt.astimezone(timezone.utc).date() == today:
                today_matches.append(m)
        except (ValueError, TypeError):
            pass

    if len(today_matches) < 2:
        return None

    # Sortiere nach timestamp aufsteigend
    today_matches.sort(key=lambda m: m.get("timestamp") or "")
    first_mmr = today_matches[0].get("player_rank")  # MMR vor erstem Match
    last_mmr = today_matches[-1].get("new_player_rank")  # MMR nach letztem Match
    if first_mmr is None or last_mmr is None:
        return None
    return int(last_mmr) - int(first_mmr)


async def get_hearthstone_mmr() -> dict:
    """Liest Firestone MMR-Daten aus dem Syncthing-Sync.

    DONNA-249: Liest die neue latest.json-Struktur mit solo/duo/recent_matches.

    Returns dict mit:
      available      — bool
      solo           — dict mit last_mmr + last_result (oder None)
      duo            — dict mit last_mmr + last_result (oder None)
      session_delta  — int, +/- MMR-Veränderung heute aus recent_matches (oder None)
      last_updated   — ISO8601-Timestamp (nur wenn available=True)
      message        — str (nur wenn available=False)
    Oder bei Parse-Fehler: {"available": False, "error": "..."}
    """
    if not os.path.exists(FIRESTONE_SYNC_PATH):
        log.info("hearthstone_firestone_not_synced", path=FIRESTONE_SYNC_PATH)
        return {
            "available": False,
            "message": "Firestone-Sync noch nicht eingerichtet",
        }

    try:
        with open(FIRESTONE_SYNC_PATH, encoding="utf-8") as f:
            data = json.load(f)

        # DONNA-253: Quelle der Daten — "hs_log_parser" (neuer Log-Watcher) oder None (Firestone)
        source = data.get("source")  # "hs_log_parser" | None

        # DONNA-249: Neue latest.json-Struktur mit solo/duo-Keys
        last_updated = data.get("last_updated")
        recent_matches = data.get("recent_matches", [])

        # solo/duo direkt aus der neuen Struktur lesen
        raw_solo = data.get("solo")  # letztes Solo-Match oder None
        raw_duo = data.get("duo")    # letztes Duo-Match oder None

        # Fallback: alte Struktur (vor DONNA-249) — recent_matches nach game_mode trennen
        if raw_solo is None and raw_duo is None and recent_matches:
            solo_list = [
                m for m in recent_matches
                if "solo" in (m.get("game_mode") or "").lower()
                and "duo" not in (m.get("game_mode") or "").lower()
            ]
            duo_list = [
                m for m in recent_matches
                if "duo" in (m.get("game_mode") or "").lower()
            ]
            raw_solo = solo_list[-1] if solo_list else None
            raw_duo = duo_list[-1] if duo_list else None

            # Altes Format: last_updated war "timestamp"
            if not last_updated:
                last_updated = data.get("timestamp")

        solo_mmr = None
        solo_result = None
        if raw_solo:
            solo_mmr = raw_solo.get("new_player_rank") or raw_solo.get("newPlayerRank")
            solo_result = raw_solo.get("result")

        duo_mmr = None
        duo_result = None
        if raw_duo:
            duo_mmr = raw_duo.get("new_player_rank") or raw_duo.get("newPlayerRank")
            duo_result = raw_duo.get("result")

        # Session-Delta aus recent_matches berechnen
        session_delta = _compute_session_delta(recent_matches)

        log.info(
            "hearthstone_mmr_fetched",
            solo_mmr=solo_mmr,
            duo_mmr=duo_mmr,
            session_delta=session_delta,
            recent_count=len(recent_matches),
            source=source,
        )

        # DONNA-253: Placement aus letztem Match (nur bei hs_log_parser verfügbar)
        solo_placement = raw_solo.get("placement") if raw_solo else None
        duo_placement = raw_duo.get("placement") if raw_duo else None

        return {
            "available": True,
            "source": source,  # "hs_log_parser" | None (Firestone-Fallback)
            "solo": {
                "last_mmr": solo_mmr,
                "last_result": solo_result,
                "last_placement": solo_placement,
            },
            "duo": {
                "last_mmr": duo_mmr,
                "last_result": duo_result,
                "last_placement": duo_placement,
            },
            "session_delta": session_delta,
            "last_updated": last_updated,
            "recent_matches": recent_matches,
        }

    except json.JSONDecodeError as exc:
        log.warning("hearthstone_json_parse_failed", error=str(exc))
        return {"available": False, "error": f"JSON-Parse-Fehler: {exc}"}
    except OSError as exc:
        log.warning("hearthstone_read_failed", error=str(exc))
        return {"available": False, "error": str(exc)}
    except Exception as exc:  # noqa: BLE001
        log.warning("hearthstone_unexpected_error", error=str(exc))
        return {"available": False, "error": str(exc)}


def format_hearthstone_for_prompt(stats: dict) -> str:
    """Formatiert Hearthstone-Stats als lesbaren Prompt-Block für Donna.

    DONNA-249: Nutzt die neue latest.json-Struktur (solo/duo direkt, kein generischer mmr).
    DONNA-253: Zeigt Placement wenn verfügbar (source="hs_log_parser").
    Beide Modi werden separat angezeigt. Session-Delta aus heutigen Matches.
    """
    if not stats.get("available"):
        msg = stats.get("message") or stats.get("error", "Unbekannter Fehler")
        return f"[Hearthstone Battlegrounds] MMR: nicht verfügbar — {msg}"

    delta = stats.get("session_delta")
    updated = stats.get("last_updated", "")
    solo = stats.get("solo") or {}
    duo = stats.get("duo") or {}
    source = stats.get("source")  # "hs_log_parser" | None

    lines = ["[Hearthstone Battlegrounds — Mike spielt Solo UND Duo]"]

    # DONNA-253: Quelle der Daten anzeigen
    if source == "hs_log_parser":
        lines.append("(Quelle: HS-Log-Watcher — Echtzeit-Daten mit Timestamps)")

    solo_mmr = solo.get("last_mmr")
    duo_mmr = duo.get("last_mmr")

    def _format_mode(mmr, res, placement) -> str:
        """Formatiert MMR + optionales Ergebnis + Placement."""
        parts = []
        if res:
            parts.append(f"letztes Match: {res}")
        if placement:
            parts.append(f"Platz {placement}")
        suffix = f" ({', '.join(parts)})" if parts else ""
        return str(mmr) + suffix

    if solo_mmr and duo_mmr:
        lines.append(f"Solo MMR: {_format_mode(solo_mmr, solo.get('last_result'), solo.get('last_placement'))}")
        lines.append(f"Duo MMR: {_format_mode(duo_mmr, duo.get('last_result'), duo.get('last_placement'))}")
    elif duo_mmr and not solo_mmr:
        lines.append(f"Duo MMR: {_format_mode(duo_mmr, duo.get('last_result'), duo.get('last_placement'))}")
        lines.append("Solo MMR: keine Daten in History")
    elif solo_mmr and not duo_mmr:
        lines.append(f"Solo MMR: {_format_mode(solo_mmr, solo.get('last_result'), solo.get('last_placement'))}")
        lines.append("Duo MMR: keine Daten in History")
    else:
        lines.append("Solo MMR: nicht verfügbar")
        lines.append("Duo MMR: nicht verfügbar")
        if source != "hs_log_parser":
            lines.append("(Firestone-Export liefert noch keine Modus-Trennung)")

    if delta is not None:
        sign = "+" if delta >= 0 else ""
        lines.append(f"Session-Delta heute: {sign}{delta} MMR")

    if updated:
        lines.append(f"Stand: {updated}")

    return "\n".join(lines)

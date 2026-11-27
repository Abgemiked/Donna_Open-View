"""suggestion_feedback.py — Feedback-Schleife: Annahme/Ablehnung von Karten/Vorschlaegen (#182, Phase 4a).

Erfasst, ob Mike eine Karte (Wecker, Timer, Termin, Navigation, ...) erledigt (``done`` = angenommen) oder
abgelehnt hat, und verdichtet das zu einer TENDENZ je Kartenart — ausdruecklich keine harte Regel.

* Gespeichert werden nur Kartenart + Ergebnis + Zeitpunkt (keine Inhalte, keine Texte, keine Namen); je Karten-Kennung
  genau eine Zeile (Idempotenz, spaetere Aenderung ueberschreibt).
* Tendenz erst ab ``_MIN_PER_KIND`` Rueckmeldungen je Art; Text mit "wahrscheinlich"/Konfidenz/Datenbasis/Stand.
* Veroeffentlichung als ein ``user_habit`` (``feedback.vorschlaege``) ueber rhythm_service.publish_habits
  (ersetzen statt anhaengen, Live-Sperre per session_id + Textvokabular).
* ``expired`` (nie angetippt) wird hier nicht gezaehlt: Ignorieren ist keine Ablehnung.
"""
from __future__ import annotations

import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from app.core.logger import get_logger
from app.services import rhythm_service as rs

log = get_logger("service.suggestion_feedback")

HABIT_VORSCHLAEGE = "feedback.vorschlaege"
_MIN_PER_KIND = 5            # Rueckmeldungen je Kartenart, ab denen eine Tendenz genannt wird
_MIN_TOTAL = 8               # insgesamt, sonst "noch zu wenig Daten"
_ACCEPT_HI = 0.75
_ACCEPT_LO = 0.25
_WINDOW_DAYS = 90
_OUTCOME = {"done": "accepted", "rejected": "rejected"}

KIND_LABELS = {
    "set_alarm": "Wecker", "set_timer": "Timer", "create_event": "neue Kalendertermine",
    "calendar_change": "Terminaenderungen", "tesla_navigate": "Navigation", "navigate": "Navigation",
    "play_music": "Musik", "open_url": "Links oeffnen", "note": "Notizen", "save_note": "Notizen",
    "home_control": "Smart-Home-Karten", "idea_confirm": "Ideen speichern", "email_draft": "E-Mail-Entwuerfe",
    "contact_person": "Kontakt-Aktionen",
}


class SuggestionFeedbackService:
    """Eigene kleine SQLite neben feedback.db: ``suggestion_feedback(card_id PK, kind, outcome, ts)``."""

    def __init__(self, db_path: str) -> None:
        self._db_path = db_path
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        with self._conn() as c:
            c.execute("""CREATE TABLE IF NOT EXISTS suggestion_feedback (
                card_id TEXT PRIMARY KEY, kind TEXT NOT NULL, outcome TEXT NOT NULL, ts TEXT NOT NULL)""")

    def _conn(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self._db_path, check_same_thread=False, timeout=10.0)
        conn.row_factory = sqlite3.Row
        return conn

    # -- Schreiben ----------------------------------------------------------------------------------------
    def record(self, card_id: str, kind: str, state: str, now: datetime | None = None) -> bool:
        """Vermerkt Annahme (done) / Ablehnung (rejected). Andere Zustaende/Arten werden ignoriert. Idempotent je card_id."""
        outcome = _OUTCOME.get(state)
        kind = str(kind or "")[:40]
        if outcome is None or not kind or not card_id:
            return False
        ts = (now or datetime.now(timezone.utc)).isoformat()
        with self._conn() as c:
            c.execute("INSERT INTO suggestion_feedback (card_id, kind, outcome, ts) VALUES (?,?,?,?) "
                      "ON CONFLICT(card_id) DO UPDATE SET kind=excluded.kind, outcome=excluded.outcome, ts=excluded.ts",
                      (str(card_id)[:64], kind, outcome, ts))
        return True

    # -- Lesen ---------------------------------------------------------------------------------------------
    def counts(self, days: int = _WINDOW_DAYS, now: datetime | None = None) -> dict[str, dict[str, int]]:
        since = ((now or datetime.now(timezone.utc)) - timedelta(days=days)).isoformat()
        with self._conn() as c:
            rows = c.execute("SELECT kind, outcome, COUNT(*) n FROM suggestion_feedback WHERE ts >= ? GROUP BY kind, outcome",
                             (since,)).fetchall()
        out: dict[str, dict[str, int]] = {}
        for r in rows:
            out.setdefault(r["kind"], {"accepted": 0, "rejected": 0})[r["outcome"]] = r["n"]
        return out


# =============================================================================================
# Reine Logik
# =============================================================================================

def tendencies(counts: dict[str, dict[str, int]]) -> dict[str, Any]:
    """Je Kartenart mit genug Rueckmeldungen: 'meist angenommen' / 'meist abgelehnt'; sonst keine Aussage."""
    total = sum(v.get("accepted", 0) + v.get("rejected", 0) for v in counts.values())
    liked, disliked = [], []
    for kind, v in sorted(counts.items()):
        a, r = v.get("accepted", 0), v.get("rejected", 0)
        n = a + r
        if n < _MIN_PER_KIND:
            continue
        share = a / n
        row = (KIND_LABELS.get(kind, kind), a, n)
        if share >= _ACCEPT_HI:
            liked.append(row)
        elif share <= _ACCEPT_LO:
            disliked.append((row[0], r, n))
    return {"total": total, "liked": liked, "disliked": disliked}


def habit_text(t: dict[str, Any], now: datetime | None = None) -> str | None:
    """Text fuer den LTM-Eintrag; None bei zu wenig Daten oder ohne erkennbare Tendenz."""
    if t["total"] < _MIN_TOTAL or not (t["liked"] or t["disliked"]):
        return None
    stand = (now or datetime.now(timezone.utc)).astimezone(rs.TZ).strftime("%Y-%m")
    parts = []
    if t["liked"]:
        parts.append("nimmt " + ", ".join(f"{k} ({a} von {n})" for k, a, n in t["liked"]) + " meist an")
    if t["disliked"]:
        parts.append("lehnt " + ", ".join(f"{k} ({r} von {n})" for k, r, n in t["disliked"]) + " meist ab")
    conf = "mittel" if t["total"] >= 30 else "niedrig"
    return (f"(Muster {HABIT_VORSCHLAEGE}) Bei Vorschlaegen/Karten: Mike {' und '.join(parts)}. "
            f"Nur eine Tendenz, keine feste Regel — im Zweifel weiter anbieten. Konfidenz {conf}, "
            f"Datenbasis {t['total']} Rueckmeldungen in {_WINDOW_DAYS} Tagen, Stand {stand}.")


_HINT_MAX_KINDS = 3
_KNOWN_LABELS = frozenset(KIND_LABELS.values())
_HINT_TTL_S = 600.0
_hint_cache: dict[int, tuple[float, str | None]] = {}


def prompt_hint(t: dict[str, Any]) -> str | None:
    """Kurzer fester Prompt-Block (2 Saetze) aus der Tendenz: NUR 'zurueckhaltender anbieten' fuer meist abgelehnte,
    bekannte Kartenarten — nie 'nicht anbieten', ohne Zahlen/Rohdaten. None ohne Tendenz oder bei zu wenig Daten
    (gleiche Mindestdaten wie habit_text). Unbekannte Arten werden nie in den Prompt uebernommen."""
    if t["total"] < _MIN_TOTAL:
        return None
    names = sorted({label for label, _r, _n in t["disliked"] if label in _KNOWN_LABELS})[:_HINT_MAX_KINDS]
    if not names:
        return None
    return (f"Mike lehnt Vorschlaege zu {', '.join(names)} meist ab: biete sie zurueckhaltender an (selten, kurz) und "
            "gelegentlich trotzdem. Das ist nur eine Tendenz, keine Regel.")


def prompt_hint_cached(service: Any, now: float | None = None) -> str | None:
    """Wie prompt_hint(service.counts()), kurz zwischengespeichert (nicht bei jeder Chat-Anfrage neu rechnen). Wirft nie."""
    import time
    now = time.monotonic() if now is None else now
    hit = _hint_cache.get(id(service))
    if hit and now - hit[0] < _HINT_TTL_S:
        return hit[1]
    try:
        text = prompt_hint(tendencies(service.counts()))
    except Exception:  # noqa: BLE001
        text = None
    _hint_cache[id(service)] = (now, text)
    return text


def _remove_stale(ltm: Any) -> dict[str, str]:
    """Eine Tendenz, die nicht mehr belegt ist (zu wenig Daten im Fenster), wird entfernt — sie koennte sonst Angebote
    dauerhaft daempfen. (Anders als Rhythmus-Muster: hier ist ein veralteter Wert schaedlicher als keiner.)"""
    out: dict[str, str] = {}
    for e in ltm.list_by_category("user_habit", limit=2000):
        if str(e.get("content", "")).startswith(f"(Muster {HABIT_VORSCHLAEGE})") and ltm.delete_memory(e["id"]):
            out[HABIT_VORSCHLAEGE] = "removed"
    return out


def refresh_sync(service: SuggestionFeedbackService, ltm: Any, now: datetime | None = None) -> dict[str, str]:
    """Verdichtet die Rueckmeldungen und veroeffentlicht genau einen Eintrag (ersetzen statt anhaengen)."""
    text = habit_text(tendencies(service.counts(now=now)), now)
    res = rs.publish_habits(ltm, {}, texts={HABIT_VORSCHLAEGE: text} if text else {})
    if not text:
        res.update(_remove_stale(ltm))
    log.info("suggestion_feedback_refresh", published=len(res))
    return res


async def refresh(service: SuggestionFeedbackService, ltm: Any) -> dict[str, str]:
    import asyncio
    return await asyncio.to_thread(refresh_sync, service, ltm)

"""person_patterns.py — Personen-/Termin-Muster aus dem Kalender, NUR als Vorschlag (#182, Phase 4b).

Leitplanken (Mikes Vorgabe + DSGVO-Auflagen des Kalenders):
  * Kalender-Inhalte werden NIE automatisch persistiert. Vorschlaege entstehen bei jeder Abfrage frisch im Speicher
    (``derive_candidates``) und werden NICHT gespeichert.
  * Ein Fakt ueber Dritte (z. B. "Geburtstag von X") oder ein Termin-Muster (Arzt/Amt/Schule) kommt erst nach Mikes
    AUSDRUECKLICHER Bestaetigung (``confirm``) als ``user_fact`` ins LTM; vorher gibt es keinen Schreibzugriff.
  * Gespeichert wird je Entscheidung nur ein Hash + Status (``confirmed``/``rejected``) — keine Namen/Titel.
    Abgelehnte Vorschlaege tauchen nicht mehr auf.
  * Muster-Texte enthalten nie Terminnamen, nur Art + Anzahl + Tageszeit.
  * Der Kalender liefert heute hoechstens 50 Termine ab jetzt: das ist die ehrliche Datenbasis, kein Jahresrueckblick.
"""
from __future__ import annotations

import hashlib
import hmac
import re
import unicodedata
import sqlite3
from collections import Counter
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any, Callable

from app.core.logger import get_logger
from app.services import calendar_service as cal

log = get_logger("service.person_patterns")

CONFIRMED_SESSION = "person_fact_confirmed"      # LTM-session_id bestaetigter Fakten (live gesperrt)
_MIN_PATTERN_EVENTS = 3
_LOOKAHEAD_DAYS = 60
_NAME_TOKEN_RE = re.compile(r"^[A-Za-zÄÖÜäöüß][A-Za-zÄÖÜäöüß\-]{0,19}$")   # 1-2 Woerter, je <= 20 Zeichen
_OWN_TOKENS = frozenset({"mein", "meine", "meiner", "mike", "ich", "fabisch", "your-twitch-channel"})

_BDAY_PATTERNS = [
    re.compile(r"(?i)^\s*geburtstag\s+von\s+(?P<n>.+?)\s*$"),
    re.compile(r"(?i)^\s*(?P<n>.+?)(?:['’]s?)?\s+geburtstag\s*$"),
    re.compile(r"(?i)^\s*geburtstag\s+(?P<n>.+?)\s*$"),
    re.compile(r"(?i)^\s*(?P<n>.+?)['’]s\s+birthday\s*$"),
    re.compile(r"(?i)^\s*(?:bday|birthday)\s+(?P<n>.+?)\s*$"),
]
_KIND_RES = {
    "arzt": re.compile(r"(?i)\b(?!tier)\w*arzt\w*|\b[äa]rztin\b|\bpraxis\s+dr\b|\bphysiotherap\w*|\btherapeut\w*|\borthop[aä]d\w*"),
    "amt": re.compile(r"(?i)\b(amt|jobcenter|finanzamt|buergeramt|bürgeramt|standesamt|zulassungsstelle|krankenkasse|rentenversicherung|"
                      r"auslaenderbehoerde|ausländerbehörde|amtsgericht)\b"),
    "schule": re.compile(r"(?i)\b(schule|kita|kindergarten|elternabend|einschulung|zeugnis\w*|elterngespr[äa]ch\w*|schulfest)\b"),
}
_KIND_TEXT = {"arzt": "Arzt-/Therapietermine", "amt": "Termine bei Aemtern/Behoerden/Versicherungen",
              "schule": "Schul-/Kita-Termine"}


def _norm(label: str) -> str:
    return re.sub(r"\s+", " ", unicodedata.normalize("NFC", label).strip().lower())


_SECRET: bytes = b""


def set_secret(secret: str) -> None:
    """Geheimer Schluessel fuer die Entscheidungs-Hashes (aus den Settings, NICHT in der Datenbank): ohne ihn lassen sich
    die gespeicherten Hashes nicht per Woerterbuch auf Namen zurueckrechnen. Wechselt der Schluessel, tauchen abgelehnte
    Vorschlaege einmal wieder auf (keine Datenverluste)."""
    global _SECRET
    _SECRET = (secret or "").encode("utf-8")


def cid_for(kind: str, key: str) -> str:
    msg = f"{kind}|{_norm(key)}".encode("utf-8")
    if _SECRET:
        return hmac.new(_SECRET, msg, hashlib.sha256).hexdigest()[:12]
    return hashlib.sha1(msg).hexdigest()[:12]            # nur Tests/ohne Schluessel


def extract_birthday(event: dict) -> tuple[str, date] | None:
    """(Name, Datum) aus einem ganztaegigen Geburtstags-Termin; None sonst. Name nur aus Buchstaben (<= 40)."""
    title = str(event.get("summary") or "")
    if not re.search(r"(?i)geburtstag|birthday|bday", title):
        return None
    start = event.get("start") if isinstance(event.get("start"), dict) else {}
    if not start.get("date"):                       # nur ganztaegige Termine
        return None
    try:
        d = date.fromisoformat(str(start["date"]))
    except ValueError:
        return None
    for pat in _BDAY_PATTERNS:
        m = pat.match(title)
        if m:
            name = m.group("n").strip(" '’\"")
            name = re.sub(r"(?i)\b(geburtstag|birthday|bday)\b", "", name).strip(" '’\"-")
            tokens = name.split()
            if not (1 <= len(tokens) <= 2) or not all(_NAME_TOKEN_RE.match(t) for t in tokens):
                return None                              # Saetze/Anweisungen im Titel sind kein Name
            if any(t.lower() in _OWN_TOKENS for t in tokens):
                return None                              # Mikes eigener Geburtstag ist kein Fakt ueber Dritte
            return name, d
    return None


def appointment_kind(event: dict) -> str | None:
    title = str(event.get("summary") or "")
    for kind, rx in _KIND_RES.items():
        if rx.search(title):
            return kind
    return None


def _daypart(event: dict) -> str | None:
    _, hhmm = cal.event_start_local(event)
    if not hhmm:
        return None
    h = int(hhmm[:2])
    return "vormittags" if h < 12 else ("nachmittags" if h < 18 else "abends")


def derive_candidates(events: list[dict], now: datetime | None = None, days: int = _LOOKAHEAD_DAYS) -> list[dict]:
    """Vorschlaege aus Kalendertexten. Reine Funktion; nichts wird gespeichert."""
    now = now or datetime.now(timezone.utc)
    stand = now.astimezone(cal._BERLIN).strftime("%Y-%m")
    out: list[dict] = []
    seen: set[str] = set()
    for ev in events:
        b = extract_birthday(ev)
        if b:
            name, d = b
            cid = cid_for("geburtstag", f"{name}|{d.month:02d}-{d.day:02d}")
            if cid not in seen:
                seen.add(cid)
                out.append({"id": cid, "kind": "geburtstag", "third_party": True,
                            "frage": f'Soll ich mir merken: Geburtstag von "{name}" am {d.day:02d}.{d.month:02d}.? (Name aus dem Kalender)',
                            "text": f'Geburtstag von "{name}" ist am {d.day:02d}.{d.month:02d}. (Name aus dem Kalender, von Mike bestaetigt)'})
    by_kind: dict[str, list[dict]] = {}
    uniq: dict[tuple, dict] = {}
    for ev in events:
        uniq.setdefault((str(ev.get("summary") or "").strip().lower(), repr(ev.get("start"))), ev)
    for ev in uniq.values():
        if extract_birthday(ev):
            continue
        k = appointment_kind(ev)
        if k:
            by_kind.setdefault(k, []).append(ev)
    for kind, evs in by_kind.items():
        if len(evs) < _MIN_PATTERN_EVENTS:
            continue
        parts = Counter(p for p in (_daypart(e) for e in evs) if p)
        part = f", meist {parts.most_common(1)[0][0]}" if parts else ""
        cid = cid_for("muster", kind)
        out.append({"id": cid, "kind": "muster", "third_party": False,
                    "frage": f"Soll ich mir merken: Mike hat aktuell mehrere {_KIND_TEXT[kind]} ({len(evs)} Termine{part})?",
                    "text": (f"Mike hat aktuell mehrere {_KIND_TEXT[kind]} (Datenbasis: {len(evs)} der abgerufenen Kalendertermine, hoechstens 50 "
                             f"in den naechsten {days} Tagen{part}; Konfidenz niedrig, Stand {stand}; von Mike bestaetigt)")})
    return out


class CalendarUnavailable(RuntimeError):
    pass


class DecisionStore:
    """Nur Hash + Status der Entscheidungen — keine Namen, keine Texte."""

    def __init__(self, db_path: str) -> None:
        self._db_path = db_path
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        with self._conn() as c:
            c.execute("CREATE TABLE IF NOT EXISTS person_decisions (cid TEXT PRIMARY KEY, status TEXT NOT NULL, ts TEXT NOT NULL)")

    def _conn(self) -> sqlite3.Connection:
        return sqlite3.connect(self._db_path, check_same_thread=False, timeout=10.0)

    def decided(self) -> dict[str, str]:
        with self._conn() as c:
            return {r[0]: r[1] for r in c.execute("SELECT cid, status FROM person_decisions")}

    def set(self, cid: str, status: str) -> None:
        with self._conn() as c:
            c.execute("INSERT INTO person_decisions (cid, status, ts) VALUES (?,?,?) "
                      "ON CONFLICT(cid) DO UPDATE SET status=excluded.status, ts=excluded.ts",
                      (cid, status, datetime.now(timezone.utc).isoformat()))


class PersonCandidateService:
    """Vorschlaege listen / bestaetigen / ablehnen. Schreibt NUR in confirm() und NUR nach ausdruecklichem Aufruf."""

    def __init__(self, events_fn: Callable[[], list[dict] | None], ltm: Any, store: DecisionStore) -> None:
        # events_fn liefert None (oder wirft), wenn der Kalender NICHT verfuegbar ist — das ist etwas anderes als "keine Termine"
        self._events_fn, self._ltm, self._store = events_fn, ltm, store

    def _events(self) -> list[dict] | None:
        try:
            return self._events_fn()
        except Exception:  # noqa: BLE001
            return None

    def pending(self, now: datetime | None = None) -> list[dict]:
        decided = self._store.decided()
        return [c for c in derive_candidates(self._events() or [], now) if c["id"] not in decided]

    def pending_strict(self, now: datetime | None = None) -> list[dict]:
        """Wie pending(), meldet aber einen Kalenderausfall als Fehler (statt 'keine Vorschlaege')."""
        events = self._events()
        if events is None:
            raise CalendarUnavailable()
        decided = self._store.decided()
        return [c for c in derive_candidates(events, now) if c["id"] not in decided]

    def confirm(self, cid: str, now: datetime | None = None) -> str:
        """'ok' | 'not_found' | 'failed' | 'already' | 'unavailable'. Fakt wird erst gespeichert, dann gegengelesen, dann als entschieden vermerkt."""
        status = self._store.decided().get(cid)
        if status == "confirmed":
            return "already"
        if status == "rejected":
            return "not_found"
        events = self._events()
        if events is None:
            return "unavailable"                         # Kalender-Ausfall ist keine Entscheidung
        cand = next((c for c in derive_candidates(events, now) if c["id"] == cid), None)
        if cand is None:
            return "not_found"
        for e in self._ltm.list_by_category("user_fact", limit=10000):
            if e.get("content") == cand["text"]:
                self._store.set(cid, "confirmed")
                return "already"
        new_id = self._ltm.store_memory(session_id=CONFIRMED_SESSION, content=cand["text"], category="user_fact", infer=False)
        if not new_id or not any(e.get("id") == new_id and e.get("content") == cand["text"]
                                 for e in self._ltm.list_by_category("user_fact", limit=10000)):
            return "failed"
        self._store.set(cid, "confirmed")
        return "ok"

    def reject(self, cid: str) -> str:
        if self._store.decided().get(cid) == "confirmed":
            return "conflict"
        self._store.set(cid, "rejected")
        return "ok"

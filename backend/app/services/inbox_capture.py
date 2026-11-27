"""inbox_capture.py — Ideen-Schnellerfassung vom Homescreen-Widget direkt in die donna-inbox (#233).

Mike tippt (oder diktiert) eine Idee in die kleine native Erfassungs-Activity, sieht die Vorschau und tippt
"Speichern" — dieser Tipp IST die Bestaetigung (Mike-Entscheidung 27.11., kein LLM-Rueckfrage-Fluss). Die App
sendet dann EINMAL `POST /inbox/capture`; hier liegt die reine Logik dazu:

  * Projekt NUR aus der festen Liste (KNOWN_PROJECT_SLUGS, exakter Treffer) — kein Alias, kein Freitext-Slug.
  * Titel wird aus dem (sanitisierten) Text abgeleitet; Text und Titel laufen durch sanitize_issue_field.
  * Idempotenz: die App schickt je Idee eine Nonce. Ein zweiter Versand derselben Nonce (Offline-Warteschlange,
    verlorene Antwort) liefert das schon angelegte Issue zurueck statt ein zweites anzulegen. Laeuft die erste
    Anfrage noch, kommt "in_flight" (409) — die App versucht es spaeter erneut.
  * Rate-Limit (Sliding Window, nur fuer echte Anlage-Versuche; Wiederholungen bekannter Nonces zaehlen nicht).

Bewusst im Speicher (ein uvicorn-Worker, single-threaded asyncio): nach einem Server-Neustart ist das Nonce-
Gedaechtnis leer. Restrisiko: Antwort verloren UND Neustart dazwischen -> ein Duplikat (im PR dokumentiert).
"""
from __future__ import annotations

import re
import time
from collections import deque
from dataclasses import dataclass, field
from datetime import datetime
from typing import Callable

from app.services.github_inbox_service import KNOWN_PROJECT_SLUGS, compose_issue, sanitize_issue_field

TEXT_MAX = 2000
TITLE_MAX = 80
NONCE_RE = re.compile(r"^[A-Za-z0-9_-]{16,64}$")
KANAL_LABELS: dict[str, str] = {"widget": "Widget", "shortcut": "App-Shortcut"}
DEFAULT_PROJEKT = "uebergreifend"

NONCE_TTL_S = 24 * 3600.0
NONCE_MAX = 1000
RATE_MAX = 20          # Anlage-Versuche ...
RATE_WINDOW_S = 300.0  # ... je 5 Minuten (Warteschlange mit vielen Ideen laeuft in Etappen nach)
IN_FLIGHT_TTL_S = 120.0  # GitHub-Timeout ist 6 s; laenger reserviert = haengengeblieben


def is_allowed_projekt(slug: object) -> bool:
    """Nur exakte kanonische Slugs (keine Alias-Aufloesung, kein Freitext)."""
    return isinstance(slug, str) and slug in KNOWN_PROJECT_SLUGS


def derive_title(clean_text: str) -> str:
    """Erste nicht-leere Zeile, auf TITLE_MAX an einer Wortgrenze gekuerzt (mit …)."""
    first = next((ln.strip() for ln in clean_text.splitlines() if ln.strip()), "")
    if len(first) <= TITLE_MAX:
        return first or "Idee"
    cut = first[: TITLE_MAX - 1]
    space = cut.rfind(" ")
    if space >= TITLE_MAX // 2:
        cut = cut[:space]
    return cut.rstrip(" ,;.-") + "…"


@dataclass
class CaptureStore:
    """Nonce-Gedaechtnis + Rate-Limit fuer /inbox/capture (Uhr injizierbar fuer Tests)."""

    clock: Callable[[], float] = time.monotonic
    _done: dict[str, tuple[float, dict]] = field(default_factory=dict)
    _in_flight_since: dict[str, float] = field(default_factory=dict)
    _attempts: deque[float] = field(default_factory=deque)

    def _prune(self, now: float) -> None:
        for k in [k for k, (ts, _) in self._done.items() if now - ts > NONCE_TTL_S]:
            self._done.pop(k, None)
        if len(self._done) > NONCE_MAX:
            for k in sorted(self._done, key=lambda k: self._done[k][0])[: len(self._done) - NONCE_MAX]:
                self._done.pop(k, None)
        while self._attempts and now - self._attempts[0] > RATE_WINDOW_S:
            self._attempts.popleft()
        # Sicherheitsnetz: eine Reservierung, die nie abgeschlossen wurde, verfaellt nach IN_FLIGHT_TTL_S
        for k in [k for k, ts in self._in_flight_since.items() if now - ts > IN_FLIGHT_TTL_S]:
            self._in_flight_since.pop(k, None)

    def begin(self, nonce: str) -> tuple[str, dict | None]:
        """Vor dem Anlegen: ("done", ergebnis) | ("in_flight", None) | ("rate_limited", None) | ("go", None).

        "go" reserviert die Nonce (in_flight) und zaehlt einen Anlage-Versuch. Danach MUSS finish() oder
        release() folgen.
        """
        now = self.clock()
        self._prune(now)
        if nonce in self._done:
            return "done", dict(self._done[nonce][1])
        if nonce in self._in_flight_since:
            return "in_flight", None
        if len(self._attempts) >= RATE_MAX:
            return "rate_limited", None
        self._attempts.append(now)
        self._in_flight_since[nonce] = now
        return "go", None

    def finish(self, nonce: str, result: dict) -> None:
        self._in_flight_since.pop(nonce, None)
        self._done[nonce] = (self.clock(), dict(result))
        self._prune(self.clock())

    def release(self, nonce: str) -> None:
        """Anlegen gescheitert: Nonce freigeben, damit die Warteschlange es erneut versuchen kann."""
        self._in_flight_since.pop(nonce, None)


def compose_capture(text: str, projekt: str, kanal: str, *, now: datetime | None = None) -> dict | None:
    """Baut das Issue (title/body/labels) oder None, wenn nach dem Sanitisieren nichts uebrig bleibt."""
    clean = sanitize_issue_field(text, maxlen=TEXT_MAX)
    if not clean:
        return None
    return compose_issue(projekt, derive_title(clean), clean, now=now, kanal=KANAL_LABELS.get(kanal))

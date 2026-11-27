"""#183 Schritt 0 (Refs #147) — Rueckkanal: Ergebnisse von Geraete-Aktionen (App -> Server).

Jede vom Server angestossene Geraete-Aktion bekommt eine Aktionskennung (`action_id`, optional `group_id`).
Die App meldet danach nur einen Ergebniscode (`ok | braucht_berechtigung | nicht_unterstuetzt | fehler`) und
eine Ursache aus einer festen Liste. Hier liegt der kleine Speicher mit Ablauf; keine Inhalte, kein Freitext,
kein PII (nur Aktionsname, Codes, Zeitpunkt). Vertrag: backend/tests/contracts/device_action_result_contract.json.

Bewusst im Speicher (nicht auf Platte): die Meldung ist nur kurz nach der Aktion relevant (Chat-Antwort,
Karte); nach Neustart oder Ablauf gilt wieder ehrlich „angestossen". Fehlt die Meldung (alte App, kein
Netz), aendert sich nichts am bisherigen Verhalten.
"""
from __future__ import annotations

import re
import secrets
import threading
import time
from typing import Any, Callable

RESULT_CODES: tuple[str, ...] = ("ok", "braucht_berechtigung", "nicht_unterstuetzt", "fehler")
REASONS: tuple[str, ...] = (
    "schalter_aus", "gesperrt", "mehrdeutig", "abgelehnt", "berechtigung", "nicht_verfuegbar",
    "fehlgeschlagen", "sonstige",
)
ACTION_ID_RE = re.compile(r"^da_[0-9a-f]{16}$")
GROUP_ID_RE = re.compile(r"^g_[0-9a-f]{16}$")

TTL_SECONDS = 15 * 60
MAX_ENTRIES = 200
SUMMARY_MAX_AGE = 10 * 60
SUMMARY_MAX_ITEMS = 5
REPORT_LIMIT = 60          # Meldungen je Fenster (global; die App ist ein einziger Nutzer)
REPORT_WINDOW = 60.0

_ACTION_LABEL: dict[str, str] = {
    "flashlight": "Taschenlampe",
    "volume": "Lautstärke",
    "open_app": "App öffnen",
    "open_settings": "Einstellungen öffnen",
    "set_timer": "Timer",
    "set_alarm": "Wecker",
    "set_brightness": "Helligkeit",
    "dnd": "Nicht stören",
    "lock_screen": "Bildschirm sperren",
    "screenshot": "Screenshot",
    "open_camera": "Kamera",
    "email_draft": "E-Mail-Entwurf öffnen",   # #147 Rest (Karte auf dem Geraet, oeffnet nur die Mail-App)
    "calendar_change": "Termin ändern",   # #147 H (Karte auf dem Gerät, Ergebnis ueber den Rueckkanal)
}
_STATUS_TEXT: dict[str, str] = {
    "ok": "hat geklappt",
    "braucht_berechtigung": "braucht eine Berechtigung",
    "nicht_unterstuetzt": "nicht unterstützt",
    "fehler": "hat nicht geklappt",
    "pending": "keine Rückmeldung (nur angestoßen)",
    "held": "wartet auf deine Bestätigung",
}
# Aeltere Meldung mit abweichendem Code = Konflikt; gleicher Code = idempotent
_PENDING = "pending"
_HELD = "held"          # #186: Schritt einer Sammelkarte, noch nicht angetippt (laeuft erst danach)
_OPEN = (_PENDING, _HELD)


def new_action_id() -> str:
    return "da_" + secrets.token_hex(8)


def new_group_id() -> str:
    return "g_" + secrets.token_hex(8)


def build_context_block(summary: str) -> str | None:
    """Kontextblock fuer den Prompt (nur feste Texte aus recent_summary; leer -> None)."""
    if not summary:
        return None
    return ("[Geraete-Rueckmeldungen] Rueckmeldungen des Handys zu frueheren Geraete-Aktionen (aeltestes zuerst). "
            "Nur diese Ergebnisse darfst du als geklappt oder nicht geklappt benennen; 'keine Rueckmeldung' "
            "bleibt 'angestossen': " + summary + ".")


class DeviceActionResultStore:
    """Thread-sicher; Zeit injizierbar (Tests)."""

    def __init__(self, *, ttl_seconds: float = TTL_SECONDS, max_entries: int = MAX_ENTRIES,
                 report_limit: int = REPORT_LIMIT, report_window: float = REPORT_WINDOW,
                 now: Callable[[], float] = time.monotonic) -> None:
        self._ttl = ttl_seconds
        self._max = max_entries
        self._limit = report_limit
        self._window = report_window
        self._now = now
        self._lock = threading.Lock()
        self._items: dict[str, dict[str, Any]] = {}
        self._report_times: list[float] = []

    def __len__(self) -> int:
        with self._lock:
            self._purge()
            return len(self._items)

    # -- intern (Lock gehalten) --------------------------------------------------
    def _purge(self) -> None:
        cutoff = self._now() - self._ttl
        for k in [k for k, v in self._items.items() if v["created"] < cutoff]:
            del self._items[k]

    # -- Schreiben ---------------------------------------------------------------
    def register(self, action_id: str, action: str, group_id: str | None = None, held: bool = False) -> bool:
        """Merkt eine angestossene Aktion (nur Name, keine Werte). Ungueltige Kennung -> False."""
        if not isinstance(action_id, str) or not ACTION_ID_RE.fullmatch(action_id):
            return False
        gid = group_id if isinstance(group_id, str) and GROUP_ID_RE.fullmatch(group_id) else None
        with self._lock:
            self._purge()
            if action_id in self._items:
                return False
            while len(self._items) >= self._max:
                oldest = min(self._items, key=lambda k: self._items[k]["created"])
                del self._items[oldest]
            self._items[action_id] = {
                "action_id": action_id,
                "action": action if action in _ACTION_LABEL else "sonstige",
                "group_id": gid,
                "status": _HELD if held else _PENDING,
                "reason": None,
                "created": self._now(),
                "reported": None,
            }
            return True

    def report(self, action_id: Any, result: Any, reason: Any = None) -> str:
        """-> recorded | duplicate | conflict | unknown | invalid | rate_limited."""
        if (not isinstance(action_id, str) or not ACTION_ID_RE.fullmatch(action_id)
                or not isinstance(result, str) or result not in RESULT_CODES):
            return "invalid"
        why = None
        if reason is not None:
            why = reason if isinstance(reason, str) and reason in REASONS else "sonstige"
        with self._lock:
            now = self._now()
            self._report_times = [t for t in self._report_times if t > now - self._window]
            if len(self._report_times) >= self._limit:
                return "rate_limited"
            self._report_times.append(now)
            self._purge()
            entry = self._items.get(action_id)
            if entry is None:
                return "unknown"
            if entry["status"] not in _OPEN:
                return "duplicate" if entry["status"] == result else "conflict"
            entry["status"] = result
            entry["reason"] = why
            entry["reported"] = now
            return "recorded"

    # -- Lesen -------------------------------------------------------------------
    @staticmethod
    def _public(e: dict[str, Any]) -> dict[str, Any]:
        return {k: e[k] for k in ("action_id", "action", "group_id", "status", "reason")}

    def get(self, action_id: str) -> dict[str, Any] | None:
        with self._lock:
            self._purge()
            e = self._items.get(action_id)
            return self._public(e) if e else None

    def group(self, group_id: str) -> list[dict[str, Any]]:
        with self._lock:
            self._purge()
            rows = [e for e in self._items.values() if e["group_id"] == group_id]
            rows.sort(key=lambda e: e["created"])
            return [self._public(e) for e in rows]

    def group_summary(self, group_id: str) -> dict[str, Any] | None:
        """#186: Stand einer Sammelaktion fuer die Ergebnis-Sammelkarte: Zeile je Schritt (nur Aktionsname,
        Status, Ursache). `angestossen` = Rueckmeldung ok oder noch keine; Ergebnisse zaehlen nur, wenn gemeldet."""
        if not isinstance(group_id, str) or not GROUP_ID_RE.fullmatch(group_id):
            return None
        rows = self.group(group_id)
        if not rows:
            return None
        return {
            "group_id": group_id,
            "total": len(rows),
            "ok": sum(1 for r in rows if r["status"] == "ok"),
            "failed": sum(1 for r in rows if r["status"] in ("braucht_berechtigung", "nicht_unterstuetzt", "fehler")),
            "pending": sum(1 for r in rows if r["status"] == _PENDING),
            "held": sum(1 for r in rows if r["status"] == _HELD),
            "steps": [{"action": r["action"], "label": _ACTION_LABEL.get(r["action"], "Aktion"),
                       "status": r["status"], "reason": r["reason"]} for r in rows],
        }

    def recent_summary(self, max_age: float = SUMMARY_MAX_AGE) -> str:
        """Kurzer deutscher Text fuer die Chat-Logik: Stand der letzten Aktionen, ehrlich getrennt in
        'hat geklappt' / 'hat nicht geklappt' / 'keine Rueckmeldung (nur angestossen)'. Keine Werte, keine Zeiten."""
        with self._lock:
            self._purge()
            cutoff = self._now() - max_age
            rows = [e for e in self._items.values() if e["created"] >= cutoff]
            rows.sort(key=lambda e: e["created"], reverse=True)
            rows = rows[:SUMMARY_MAX_ITEMS]
        if not rows:
            return ""
        parts = [f"{_ACTION_LABEL.get(e['action'], 'Aktion')}: {_STATUS_TEXT[e['status']]}" for e in reversed(rows)]
        return "; ".join(parts)

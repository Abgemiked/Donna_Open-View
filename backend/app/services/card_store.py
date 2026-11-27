"""#155 Karten im Verlauf: welche Aktionen als Karte gespeichert werden und wann eine offene Karte ablaeuft.

Reine Logik ohne Ein-/Ausgabe: fuehrt nichts aus, ruft nichts auf. Der Zustand `expired` wird nie gespeichert,
sondern beim Ausliefern aus Alter und Inhalt der Karte berechnet (Uhr einsetzbar fuer Tests).

Format je Antwort in `stm_messages.meta` (JSON):
    {"actions": [{"id": "<Kennung>", "state": "open|done|rejected", "action": {...Aktion...}}]}
"""
from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

_BERLIN = ZoneInfo("Europe/Berlin")

STATES_SETTABLE: frozenset[str] = frozenset({"done", "rejected"})
TIMER_MAX_AGE_SEC = 10 * 60
MAX_AGE_SEC = 24 * 3600
CALENDAR_CHANGE_MAX_AGE_SEC = 30 * 60

# Aktionen, die nie als antippbare Karte im Verlauf stehen: das Geraet fuehrt sie selbst aus, sie werden still
# verworfen, laufen serverseitig, oder es gibt nichts zu bestaetigen (Ziel nicht gefunden).
_NOT_CARDS: frozenset[str] = frozenset({
    "device_action", "save_memory", "set_stream_title", "set_stream_game", "navigate_not_found",
    "routine_confirm",
})


def is_storable(action: dict) -> bool:
    t = action.get("type") if isinstance(action, dict) else None
    return isinstance(t, str) and bool(t) and t not in _NOT_CARDS


def new_card(action: dict) -> dict:
    """Neue offene Karte; die Kennung steht auch in der Aktion selbst (`card_id`), damit die App sie melden kann."""
    cid = uuid.uuid4().hex[:12]
    return {"id": cid, "state": "open", "action": {**action, "card_id": cid}}


def _alarm_target(time_text: object, created: datetime) -> datetime | None:
    """Naechstes Auftreten von HH:MM (Berlin) nach dem Erstellen der Karte."""
    try:
        hh, mm = str(time_text).split(":")[:2]
        h, m = int(hh), int(mm)
        if not (0 <= h <= 23 and 0 <= m <= 59):
            return None
    except (ValueError, TypeError):
        return None
    local = created.astimezone(_BERLIN)
    target = local.replace(hour=h, minute=m, second=0, microsecond=0)
    if target <= local:
        target += timedelta(days=1)
    return target


def _event_start(value: object) -> datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        dt = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    except ValueError:
        return None
    return dt if dt.tzinfo else dt.replace(tzinfo=_BERLIN)


def effective_state(card: dict, created_at: float, now: float) -> str:
    """Zustand fuer die Auslieferung. done/rejected bleiben; eine offene Karte wird `expired`, wenn
    - sie aelter als 24 Stunden ist, oder
    - ein Timer aelter als 10 Minuten ist, oder
    - ein Wecker (naechstes Auftreten der Uhrzeit nach Erstellung) oder ein Termin-Beginn in der Vergangenheit liegt."""
    state = card.get("state") if card.get("state") in ("open", "done", "rejected") else "open"
    if state != "open":
        return state
    age = now - created_at
    if age > MAX_AGE_SEC:
        return "expired"
    action = card.get("action") if isinstance(card.get("action"), dict) else {}
    kind = action.get("type")
    now_dt = datetime.fromtimestamp(now, tz=timezone.utc)
    if kind == "set_timer" and age > TIMER_MAX_AGE_SEC:
        return "expired"
    if kind == "set_alarm":
        target = _alarm_target(action.get("time"), datetime.fromtimestamp(created_at, tz=timezone.utc))
        if target is not None and now_dt > target:
            return "expired"
    if kind in ("calendar_change", "email_draft") and age > CALENDAR_CHANGE_MAX_AGE_SEC:   # #147 H: Aenderungskarten verfallen frueh
        return "expired"
    if kind == "create_event":
        start = _event_start(action.get("start"))
        if start is not None and now_dt > start:
            return "expired"
    return "open"


def present_actions(meta: object, created_at: float, now: float) -> list[dict]:
    """Karten einer Nachricht fuer die App: Aktion flach, plus `card_id` und `card_state`."""
    if not isinstance(meta, dict):
        return []
    out: list[dict] = []
    for card in meta.get("actions") or []:
        if not isinstance(card, dict) or not isinstance(card.get("action"), dict) or not card.get("id"):
            continue
        out.append({**card["action"], "card_id": card["id"], "card_state": effective_state(card, created_at, now)})
    return out

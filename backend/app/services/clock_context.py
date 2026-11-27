"""clock_context.py — aktuelle Zeit fuer den System-Prompt (Europe/Berlin, mit gueltigem Offset).

Das Modell kennt weder Wochentag noch Uhrzeit. Eine EINZIGE Zeile am Ende des dynamischen Prompt-Teils
nennt Datum, Wochentag und Uhrzeit in Berlin samt aktuellem Offset (Tests mit einsetzbarer Uhr).
Auf die Minute genau (keine Sekunden), damit der vordere, feste Teil des Prompts zwischenspeicherbar bleibt (#152).
"""
from __future__ import annotations

from datetime import datetime, timezone
from zoneinfo import ZoneInfo

_BERLIN = ZoneInfo("Europe/Berlin")
_WEEKDAYS = ("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")


def berlin_now(now: datetime | None = None) -> datetime:
    """Die (einsetzbare) Uhr in Berlin-Zeit."""
    return (now or datetime.now(timezone.utc)).astimezone(_BERLIN)


def berlin_now_line(now: datetime | None = None) -> str:
    """'[Jetzt]: Sonntag, 2026-11-22, 14:07 Uhr Europe/Berlin (Offset jetzt +01:00; ...)'."""
    local = berlin_now(now)
    raw = local.strftime("%z")                       # +0100
    offset = f"{raw[:3]}:{raw[3:]}"
    return (
        f"[Jetzt]: {_WEEKDAYS[local.weekday()]}, {local:%Y-%m-%d}, {local:%H:%M} Uhr Europe/Berlin "
        f"(Offset jetzt {offset}; Winterzeit +01:00, Sommerzeit +02:00). Rechne relative Angaben "
        f"(heute, morgen, Freitag, in 3 Stunden) von hier aus um."
    )

"""CalendarService — Google Calendar OAuth2 Integration (DONNA-107).

DSGVO-Hinweis: Kalender-PII nur In-Memory, keine LTM-Persistenz.
(Art. 5(2) DSGVO — Rechenschaftspflicht)

Scope: ausschließlich calendar.readonly (DSGVO-Gutachten Auflage 2).
Kein Logging von Kalender-Rohdaten (DSGVO-Gutachten Auflage 3).
In-Memory-Only — kein Disk-Write, keine temporären Dateien (DSGVO-Gutachten Auflage 4).
"""
from __future__ import annotations

import copy
import difflib
import os
import re
import socket
import threading
import time
import unicodedata
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from typing import Any, NamedTuple
from zoneinfo import ZoneInfo

from app.core.logger import get_logger
from app.services import calendar_roles, calendar_token_store

log = get_logger("service.calendar")

# DSGVO-Gutachten Auflage 2: ausschließlich read-only Scope
_CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar.readonly"

# #99: Obergrenzen je Abruf. _MAX_EVENTS = bisheriges maxResults (50) je Abruf — Aufrufer
# bekommen dieselbe Größenordnung wie vorher. _MAX_CALENDARS begrenzt die Zahl der
# Google-Aufrufe (1 + Seiten + n) und damit die Latenz des synchronen Kontextblocks.
_MAX_EVENTS = 50
_MAX_CALENDARS = 15
_MAX_CALENDAR_PAGES = 5
_CALENDAR_LIST_PAGE_SIZE = 250
# Zeitbudget der Kalender-Schleife (Event-Loop-Blockade begrenzen); der Hauptkalender wird immer abgefragt.
_TIME_BUDGET_S = 8.0
# Kalendernamen aus Freigaben können von Dritten stammen und landen im Prompt → kürzen/entschärfen.
_CALENDAR_NAME_MAX = 60

# Zwischenspeicher: der Kontextblock lädt den Kalender bei fast jeder Chat-Anfrage synchron.
# Kalenderliste ändert sich selten (10 min); Termine dürfen kurz veraltet sein (2 min).
_CALENDAR_LIST_CACHE_TTL_S = 600.0
_EVENTS_CACHE_TTL_S = 120.0
_EVENTS_CACHE_MAX_ENTRIES = 8  # verschiedene (days, topic)-Schlüssel; ältester fliegt raus
_MAX_ABSENCES = 5
_DEFAULT_ABSENCE_LABEL = "jemand"
_ABSENCE_PREFIX = "Nicht da (Info, nur bei Bedarf erwähnen): "  # nicht wie die Abwesenheit des Nutzers klingen


class _Planned(NamedTuple):
    """Ein abzufragender Kalender samt Rolle (aus Zuordnungsdatei + Kalenderliste)."""

    id: str
    name: str | None
    role: str
    label: str | None


def _copy_events(events: "list[dict]") -> "CalendarEvents":
    """Tiefe Kopie (Termine, Abwesenheiten), damit Aufrufer den Speicher nicht verändern."""
    out = CalendarEvents()
    out.extend(copy.deepcopy(e) for e in events)
    out.absences = copy.deepcopy(list(getattr(events, "absences", None) or []))
    out.topic_configured = getattr(events, "topic_configured", True)
    return out


def _clean_calendar_name(name: Any) -> str | None:
    """Steuerzeichen/Zeilenumbrüche -> Leerzeichen, auf _CALENDAR_NAME_MAX kürzen; leer -> None."""
    if not name:
        return None
    cleaned = "".join(
        " " if (unicodedata.category(c) in ("Cc", "Zl", "Zp")) else c for c in str(name)
    ).strip()
    return cleaned[:_CALENDAR_NAME_MAX].rstrip() or None


def _default_roles_path() -> str:
    try:
        from app.config import get_settings
        return get_settings().calendar_roles_path
    except Exception:  # noqa: BLE001 — Settings nicht ladbar → vorsichtiger Standardpfad
        return "/data/appdata/calendar_roles.json"


def _creator_label(item: dict, people: "dict[str, calendar_roles.Person]") -> str | None:
    """Label des Termin-Erstellers (#111) aus der Server-Zuordnung, sonst None.

    Maßgeblich ist ``creator``; fehlt er (oder ist eine Kalender-Adresse), gilt ``organizer``. Ist der Ersteller
    bekannt, wird nicht auf den Organisator zurückgefallen. ``self`` -> None (kein "von ..." am eigenen Termin).
    Die Adresse verlässt diese Funktion nie: Rückgabe ist nur das Label.
    """
    for key in ("creator", "organizer"):
        raw = item.get(key)
        email = calendar_roles.normalize_email(raw.get("email")) if isinstance(raw, dict) else None
        if email is None or not calendar_roles.is_person_address(email):
            continue
        person = people.get(email)
        if person is None or person.is_self:
            return None
        return person.label
    return None


def _is_auth_error(exc: Exception) -> bool:
    exc_str = str(exc)
    return "invalid_grant" in exc_str or "Token has been expired" in exc_str


def _start_sort_key(event: dict) -> datetime:
    """Sortierschlüssel (nur zum Sortieren, Feldwerte bleiben unverändert).

    Ganztägig (date) = Mitternacht UTC, dateTime mit Offset wird korrekt verglichen,
    ohne Offset = UTC. Fehlende/kaputte Werte sortieren ans Ende.
    """
    last = datetime.max.replace(tzinfo=timezone.utc)
    try:
        start = event.get("start") or {}
        dt_str = start.get("dateTime")
        if dt_str:
            dt = datetime.fromisoformat(str(dt_str).replace("Z", "+00:00"))
            return dt.astimezone(timezone.utc) if dt.tzinfo else dt.replace(tzinfo=timezone.utc)
        d_str = start.get("date")
        if d_str:
            dt = datetime.fromisoformat(str(d_str))
            return dt.replace(tzinfo=timezone.utc)
    except (ValueError, TypeError, AttributeError, OverflowError):
        pass
    return last


def _merge_absences(raw: list[tuple[dict, dict, str]]) -> list[dict]:
    """Fasst überlappende/aneinandergrenzende Zeiträume desselben Labels zusammen (max. _MAX_ABSENCES).

    Ergebnis je Eintrag NUR start/end/label — nie Titel, Ort oder Kalendername.
    Ganztägige Enden sind (wie bei Google) exklusiv: Ende == nächster Start grenzt an → zusammenfassen.
    """
    items: list[tuple[str, datetime, datetime, dict, dict]] = []
    for start, end, label in raw:
        s = _start_sort_key({"start": start})
        if s == datetime.max.replace(tzinfo=timezone.utc):
            continue  # kaputter/fehlender Start → verwerfen
        e = _start_sort_key({"start": end})
        if e < s or e == datetime.max.replace(tzinfo=timezone.utc):
            e, end = s, start
        items.append((label, s, e, dict(start), dict(end)))

    merged: list[list] = []
    for label in dict.fromkeys(i[0] for i in items):
        cur: list | None = None
        for _l, s, e, sd, ed in sorted((i for i in items if i[0] == label), key=lambda i: i[1]):
            if cur is not None and s <= cur[2]:
                if e > cur[2]:
                    cur[2], cur[4] = e, ed
                continue
            cur = [label, s, e, sd, ed]
            merged.append(cur)
    merged.sort(key=lambda m: m[1])
    return [{"start": m[3], "end": m[4], "label": m[0]} for m in merged[:_MAX_ABSENCES]]


def _local_date(d: dict) -> tuple[date | None, bool]:
    """(Datum, ganztägig) eines start/end-Dicts; dateTime behält das Datum der angegebenen Zone."""
    try:
        if d.get("dateTime"):
            return datetime.fromisoformat(str(d["dateTime"]).replace("Z", "+00:00")).date(), False
        if d.get("date"):
            return date.fromisoformat(str(d["date"])), True
    except (ValueError, TypeError, AttributeError):
        pass
    return None, False


def _absence_dates(a: dict) -> tuple[date, date] | None:
    """(erster, letzter Tag) einer Abwesenheit, beide inklusive; None bei fehlendem Start."""
    if not isinstance(a, dict):
        return None
    s, _ = _local_date(a.get("start") or {})
    if s is None:
        return None
    e, all_day = _local_date(a.get("end") or {})
    if e is not None and all_day:
        e = e - timedelta(days=1)  # Ende ganztägiger Termine ist exklusiv
    if e is None or e < s:
        e = s
    return s, e


def absence_period_text(a: dict) -> tuple[str, str, bool] | None:
    """(Label, Zeitraum "01.06." bzw. "01.06.–03.06.", eintägig?) einer Abwesenheit; None bei fehlendem Start."""
    dates = _absence_dates(a)
    if dates is None:
        return None
    s, e = dates
    period = f"{s:%d.%m.}" if e == s else f"{s:%d.%m.}–{e:%d.%m.}"
    return (a.get("label") or _DEFAULT_ABSENCE_LABEL, period, e == s)


def _format_absence_line(absences: list[dict], max_periods: int = 3) -> str | None:
    """'Nicht da (Info, nur bei Bedarf erwähnen): Person B 01.06.–03.06.; …' — höchstens max_periods Zeiträume."""
    parts: list[str] = []
    for a in absences:
        if len(parts) >= max_periods:
            break
        info = absence_period_text(a)
        if info is None:
            continue
        parts.append(f"{info[0]} {info[1]}")
    if not parts:
        return None
    return _ABSENCE_PREFIX + "; ".join(parts)


_BERLIN = ZoneInfo("Europe/Berlin")


def event_start_local(event: dict) -> tuple[date | None, str | None]:
    """(Berliner Datum, "HH:MM" | None bei ganztägig) des Terminstarts; (None, None) wenn nicht lesbar."""
    start = event.get("start") if isinstance(event, dict) else None
    if not isinstance(start, dict):
        return None, None
    try:
        if start.get("dateTime"):
            dt = datetime.fromisoformat(str(start["dateTime"]).replace("Z", "+00:00"))
            dt = dt.astimezone(_BERLIN) if dt.tzinfo else dt.replace(tzinfo=_BERLIN)
            return dt.date(), dt.strftime("%H:%M")
        if start.get("date"):
            return date.fromisoformat(str(start["date"])), None
    except (ValueError, TypeError, OverflowError):
        pass
    return None, None


@dataclass
class PersonGroups:
    """Termine nach Person: eigene (ohne created_by), Termine anderer je Label, Abwesenheiten."""

    own: list[dict] = field(default_factory=list)
    others: dict[str, list[dict]] = field(default_factory=dict)  # Label -> Termine, Reihenfolge des Erstauftretens
    absences: list[dict] = field(default_factory=list)


def group_events_by_person(
    events: "CalendarEvents | list[dict]",
    today: date | None = None,
    absences: list[dict] | None = None,
) -> PersonGroups:
    """Teilt Termine in eigene / je Label anderer Personen und filtert Abwesenheiten auf den Bezugstag (reine Funktion).

    events: Terminliste, i. d. R. ein CalendarEvents (dessen ``.absences`` ist der Standard für ``absences``).
    today: Bezugstag (Berliner Datum), Standard: heute in Berlin. Es zählen nur Termine, deren Berliner
        Starttag dieser Tag ist (nicht lesbarer Start bleibt drin, konservativ), und nur Abwesenheiten,
        die diesen Tag berühren (laufen oder beginnen). Eine erst später beginnende Abwesenheit zählt nicht.
    absences: optional eigene Abwesenheitsliste statt ``events.absences``.
    """
    if today is None:
        today = datetime.now(_BERLIN).date()
    raw_absences = absences if absences is not None else (getattr(events, "absences", None) or [])
    groups = PersonGroups()
    for a in raw_absences:
        dates = _absence_dates(a)
        if dates is not None and dates[0] <= today <= dates[1]:
            groups.absences.append(a)
    for ev in events:
        if not isinstance(ev, dict):
            continue
        d, _ = event_start_local(ev)
        if d is not None and d != today:
            continue
        label = ev.get("created_by")
        if isinstance(label, str) and label.strip():
            groups.others.setdefault(label.strip(), []).append(ev)
        else:
            groups.own.append(ev)
    return groups


# --- #114: Kurzzusammenfassung je Tag und Person (deterministisch, ohne Titel) -----------------------------

SUMMARY_INSTRUCTION = (
    "Nenne nur diese Zusammenfassung, kurz: bei einem Tag ein bis zwei Sätze, bei mehreren Tagen ein kurzer "
    "Satz je Tag. Zu anderen Personen liegen dir nur diese Angaben vor, ergänze nichts. "
    "Eigene Termine von Mike darfst du konkret nennen. Keine Hinweise oder Ratschläge."
)
DETAILS_INSTRUCTION = (
    "Mike fragt ausdrücklich nach Einzelheiten: nenne die passenden Einträge aus den Details, kurz. "
    "Keine Hinweise oder Ratschläge."
)
_SUMMARY_MAX_LINES = 7
_WEEKDAYS_DE = ("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")


def _to_berlin(value: Any) -> datetime:
    dt = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    return dt.astimezone(_BERLIN) if dt.tzinfo else dt.replace(tzinfo=_BERLIN)


def _day_start(day: date) -> datetime:
    return datetime.combine(day, datetime.min.time(), tzinfo=_BERLIN)


def _event_span(event: dict) -> tuple[datetime, datetime, bool] | None:
    """(Beginn, Ende, ganztägig) als Zeitpunkte in Berlin; None bei nicht lesbarem Start.

    Ganztägig: Beginn 00:00 des ersten Tages, Ende (exklusiv) 00:00 nach dem letzten Tag.
    Fehlendes/kaputtes/früheres Ende bei Terminen mit Uhrzeit: Ende = Beginn.
    """
    start = event.get("start") if isinstance(event, dict) else None
    end = event.get("end") if isinstance(event, dict) else None
    if not isinstance(start, dict):
        return None
    end = end if isinstance(end, dict) else {}
    try:
        if start.get("dateTime"):
            s = _to_berlin(start["dateTime"])
            try:
                e = _to_berlin(end["dateTime"]) if end.get("dateTime") else s
            except (ValueError, TypeError, OverflowError):
                e = s
            return s, (e if e >= s else s), False
        if start.get("date"):
            s = _day_start(date.fromisoformat(str(start["date"])))
            try:
                e = _day_start(date.fromisoformat(str(end["date"]))) if end.get("date") else s
            except (ValueError, TypeError):
                e = s
            return s, (e if e > s else s + timedelta(days=1)), True
    except (ValueError, TypeError, OverflowError):
        pass
    return None


def _touches(span: tuple[datetime, datetime, bool], day: date) -> bool:
    s, e, _all = span
    d0, d1 = _day_start(day), _day_start(day + timedelta(days=1))
    return s < d1 and (e > d0 or s >= d0)


_NUM_WORDS = ("keine", "einen", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun", "zehn", "elf", "zwölf")


def _count_phrase(n: int, spoken: bool = False) -> str:
    if n == 1:
        return "einen Termin"
    return f"{_NUM_WORDS[n]} Termine" if spoken and 0 < n < len(_NUM_WORDS) else f"{n} Termine"


def _spoken_time(dt: datetime) -> str:
    """Sprechfreundlich kurz: 9 / 9:30 / 17 (ohne führende Null, ohne ':00')."""
    return str(dt.hour) if dt.minute == 0 else f"{dt.hour}:{dt.minute:02d}"


# --- #114: "außer Haus"-Ableitung (reine Funktion, nur aus Ortsangaben/Fahrten) -----------------------------
_HOME_RE = re.compile(r"\b(zuhause|zu hause|daheim|home|home ?office|heimarbeit|mobiles? arbeiten|mobil arbeiten)\b")
_ONLINE_RE = re.compile(
    r"(https?://|www\.|\b(teams|zoom|meet|webex|skype|online|remote|virtuell\w*|telefon\w*|call|videocall|"
    r"video call|videokonferenz|hangouts?)\b)")
_TRAVEL_RE = re.compile(
    r"\b((hin|rück|rueck|heim|bahn|zug)?fahrt (nach|zu|zum|zur|ins)|zugfahrt|flug|reise nach|anreise|abreise|"
    r"unterwegs nach)\b")
_AWAY_EXTEND = timedelta(hours=3)  # unbelegte Termine verlängern die Spanne nur bis zu 3 h davor/danach


def _location_kind(ev: dict) -> str | None:
    """"online" (URL/Teams/Zoom/Telefon …), "home" (Zuhause/Home-Office …), "away" oder None ohne Ortsangabe."""
    loc = ev.get("location") if isinstance(ev, dict) else None
    if not isinstance(loc, str) or not loc.strip():
        return None
    low = loc.lower().replace("-", " ")
    if _ONLINE_RE.search(low):
        return "online"
    return "home" if _HOME_RE.search(low) else "away"


def _is_away_event(ev: dict) -> bool:
    """Beleg für "außer Haus": Ort außer Zuhause/online, oder (ohne Ortsangabe) ein enger Fahrt-/Reise-Titel."""
    kind = _location_kind(ev)
    if kind is not None:
        return kind == "away"
    title = ev.get("summary") if isinstance(ev, dict) else None
    return isinstance(title, str) and _TRAVEL_RE.search(title.lower()) is not None


def _round_half_hour(dt: datetime) -> datetime:
    minutes = int((dt.hour * 60 + dt.minute) / 30 + 0.5) * 30
    return dt.replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(minutes=minutes)


def away_phrase(items: "list[tuple[dict, tuple[datetime, datetime, bool]]]", day: date) -> str | None:
    """Ist die Person an diesem Tag außer Haus? EINE Spanne aus (Termin, Zeitspanne)-Paaren; None ohne Beleg.

    Beleg: mindestens ein Termin mit Ortsangabe (nicht Zuhause, nicht online) oder ein enger Fahrt-Titel. Die Spanne
    läuft vom ersten bis zum letzten BELEGTEN Termin und wird nicht geteilt. Unbelegte Termine verlängern sie nur,
    wenn sie höchstens 3 h vor dem ersten bzw. nach dem letzten belegten Termin beginnen (dann gelten ihr Beginn/Ende).
    Ende bekannt und volle/halbe Stunde: "bis 17"; sonst "bis etwa 17" (gerundet auf halbe Stunde; fehlt das Ende, gilt
    der Beginn des letzten Termins). Ende ab 23:30: "bis in die Nacht". Einzelner Termin ohne Ende: "gegen 10 Uhr
    unterwegs". Reine Vor-/Nachmittagsspanne ab 2 h: "vormittags/nachmittags unterwegs".
    """
    d0 = _day_start(day)
    timed = [(s, e, ev) for ev, (s, e, all_day) in items if not all_day and s >= d0]
    proven = [t for t in timed if _is_away_event(t[2])]
    if not proven:
        return None
    first_start = min(t[0] for t in proven)
    last_end = max(t[1] for t in proven)
    members = list(proven)
    for t in timed:
        if any(t is q for q in proven):
            continue
        if t[0] < first_start and first_start - t[0] <= _AWAY_EXTEND:
            members.append(t)
        elif t[0] >= last_end and t[0] - last_end <= _AWAY_EXTEND:
            members.append(t)
    start = min(t[0] for t in members)
    end = max(t[1] for t in members)
    known = any(e == end and e > s for s, e, _ev in members)
    if end == start:
        return f"gegen {_spoken_time(start)} Uhr unterwegs"
    if end >= _day_start(day + timedelta(days=1)) or end.hour * 60 + end.minute >= 23 * 60 + 30:
        return f"von {_spoken_time(start)} bis in die Nacht außer Haus"
    if known and end - start >= timedelta(hours=2):
        if start.hour >= 5 and end <= start.replace(hour=12, minute=30):
            return "vormittags unterwegs"
        if start.hour >= 12 and end <= start.replace(hour=18, minute=0, second=0):
            return "nachmittags unterwegs"
    if known and end.minute in (0, 30):
        end_txt = _spoken_time(end)
    else:
        end_txt = "etwa " + _spoken_time(_round_half_hour(end))
    return f"von {_spoken_time(start)} bis {end_txt} Uhr außer Haus"


def _person_part(
    subject: str,
    spans: list[tuple[datetime, datetime, bool]],
    day: date,
    spoken: bool = False,
    away: str | None = None,
) -> str:
    """"Du hast 3 Termine, zwischen 10:00 und 16:30 Uhr." — Zählung und Zeitspanne, nie Titel.

    spoken=True (andere Personen): Zahlwörter und kurze Uhrzeiten ("drei Termine, zwischen 9 und 17 Uhr");
    away: fertige "außer Haus"-Angabe, ersetzt dann die Zeitspanne ("… und ist von 9 bis 17 Uhr außer Haus").
    """
    verb = "hast" if subject == "Du" else "hat"
    if not spans:
        return f"{subject} {verb} keine Termine."
    d0, d1 = _day_start(day), _day_start(day + timedelta(days=1))
    timed = [(s, e) for s, e, all_day in spans if not all_day and s >= d0]
    running = sum(1 for s, _e, all_day in spans if not all_day and s < d0)  # früher begonnen, läuft noch
    all_day = len(spans) - len(timed) - running
    text = f"{subject} {verb} {_count_phrase(len(spans), spoken)}"
    if not timed:
        if not running:
            return text + ", ganztägig."
        if not all_day:
            return text + ", schon laufend."
    extras = []
    if all_day:
        extras.append(("einer" if all_day == 1 else str(all_day)) + " ganztägig")
    if running:
        extras.append(("einer" if running == 1 else str(running)) + " schon laufend")
    if extras:
        text += ", davon " + " und ".join(extras)
    if not timed:
        return text + "."
    if away:
        return text + (", und ist " if extras else " und ist ") + away + "."
    first = min(s for s, _e in timed)
    last = max(e for _s, e in timed)
    sep = " " if len(spans) == 1 else ", "
    fmt = _spoken_time if spoken else (lambda d: d.strftime("%H:%M"))
    f, lt = fmt(first), fmt(last)
    if last > d1:  # endet nach Mitternacht: nur den Beginn nennen
        return text + f"{sep}ab {f} Uhr."
    if first == last:
        return text + f"{sep}um {f} Uhr."
    if len(timed) == 1:
        return text + f"{sep}von {f} bis {lt} Uhr."
    return text + f"{sep}zwischen {f} und {lt} Uhr."


def _day_name(day: date, today: date) -> str:
    if day == today:
        return "Heute"
    if day == today + timedelta(days=1):
        return "Morgen"
    return f"{_WEEKDAYS_DE[day.weekday()]}, {day:%d.%m.}"


def _absence_part(a: dict) -> str | None:
    dates = _absence_dates(a)
    if dates is None:
        return None
    s, e = dates
    label = a.get("label") or _DEFAULT_ABSENCE_LABEL
    return f"{label} ist am {s:%d.%m.} weg." if s == e else f"{label} ist vom {s:%d.%m.} bis {e:%d.%m.} weg."


def format_person_summary(
    events: "CalendarEvents | list[dict]",
    days: int,
    now: datetime | None = None,
    include_absences: bool = True,
) -> str | None:
    """Eine Zeile je Berliner Tag (höchstens 7), nach Person getrennt, ohne Titel (reine Funktion).

    "Morgen: Du hast 3 Termine, zwischen 10:00 und 16:30 Uhr. Person A hat keine Termine. Person B ist am 21.11. weg."
    Betrachtet werden heute bis heute+days (Berlin). Heute und morgen (soweit im Zeitraum) bekommen immer eine Zeile,
    weitere Tage nur mit Terminen. Es zählen alle Termine, die den Tag berühren (auch früher begonnene).
    Ganz leer (keine Termine, keine Abwesenheit): "Keine Termine.". Termine mit nicht lesbarem Start zählen nicht;
    gibt es solche, entstehen keine "keine Termine"-Zeilen, und ohne jede Zeile ist das Ergebnis None
    (der Aufrufer lässt dann alles wie es war).
    """
    now = now or datetime.now(_BERLIN)
    today = (now.astimezone(_BERLIN) if now.tzinfo else now.replace(tzinfo=_BERLIN)).date()
    raw_absences = getattr(events, "absences", None) or []
    if not events and not raw_absences:
        return "Keine Termine."
    absences = raw_absences if include_absences else []
    spans: list[tuple[dict, tuple[datetime, datetime, bool]]] = []
    unreadable = False
    for ev in events:
        sp = _event_span(ev)
        if sp is None:
            unreadable = True
        else:
            spans.append((ev, sp))
    lines: list[str] = []
    for i in range(max(int(days), 0) + 1):
        day = today + timedelta(days=i)
        own: list = []
        others: dict[str, list] = {}
        for ev, sp in spans:
            if not _touches(sp, day):
                continue
            label = ev.get("created_by")
            if isinstance(label, str) and label.strip():
                others.setdefault(label.strip(), []).append((ev, sp))
            else:
                own.append(sp)
        must = i <= 1 and not unreadable
        if not (own or others or must):
            continue
        parts = [_person_part("Du", own, day)]
        parts += [_person_part(label, [sp for _ev, sp in its], day, spoken=True, away=away_phrase(its, day))
                  for label, its in others.items()]
        parts += [p for p in (_absence_part(a) for a in group_events_by_person([], today=day, absences=absences).absences) if p]
        lines.append(f"{_day_name(day, today)}: " + " ".join(parts))
        if len(lines) >= _SUMMARY_MAX_LINES:
            break
    return "\n".join(lines) if lines else None


def context_with_summary(text: str | None, events: "CalendarEvents | list[dict]", now: datetime | None = None) -> str | None:
    """Setzt die Kurzzeilen für heute und morgen hinter die Überschrift des Kontextblocks; sonst unverändert.

    Nur wenn der Abruf überhaupt Termine oder Abwesenheiten enthält (ganz leer: Block wie bisher).
    Abwesenheiten stehen schon als eigene Zeile im Block und kommen darum nicht noch einmal in die Kurzzeilen.
    """
    try:
        if not (events or getattr(events, "absences", None)):
            return text
        header = "[Kalender-Kontext]\n"
        # Nur Termine anderer Personen (Einzeltermine bewusst nicht im Block): trotzdem ein Block mit der Zusammenfassung
        body = text if text else header
        summary = format_person_summary(events, 1, now=now, include_absences=False)
        if not summary or summary == "Keine Termine.":
            return text
        if not body.startswith(header):
            return text
        rest = body[len(header):]
        return header + "Zusammenfassung:\n" + summary + ("\n" + rest if rest else "")
    except Exception:  # noqa: BLE001 — Kontext darf nie an der Zusammenfassung scheitern (kein Inhalts-Logging)
        return text


def tool_summary_text(
    events: "CalendarEvents | list[dict]",
    days: int,
    details: str,
    now: datetime | None = None,
    detail_mode: bool = False,
) -> str:
    """Tool-Ergebnis: Zusammenfassung + Anweisungssatz zuerst, darunter die Liste.

    detail_mode=False (allgemeine Frage): ``details`` enthält nur die eigenen Termine ("Deine Termine");
    Einzeltermine anderer Personen gehen gar nicht erst ans Modell. detail_mode=True: alle Details.
    """
    try:
        summary = format_person_summary(events, days, now=now)
    except Exception:  # noqa: BLE001
        return details
    if not summary:
        return details
    if detail_mode:
        return f"Zusammenfassung:\n{summary}\n{DETAILS_INSTRUCTION}\n\nDetails:\n{details}"
    head = f"Zusammenfassung:\n{summary}\n{SUMMARY_INSTRUCTION}"
    return f"{head}\n\nDeine Termine:\n{details}" if details else head


def without_other_persons(events: "CalendarEvents | list[dict]", keep: Any = ()) -> "CalendarEvents":
    """Kopie nur mit den eigenen Terminen (ohne ``created_by``) und denen der Personen in ``keep``.

    Abwesenheiten bleiben. Original unverändert."""
    kept = {str(k).strip() for k in (keep or ()) if isinstance(k, str)}
    out = CalendarEvents(
        e for e in events
        if isinstance(e, dict)
        and (not (isinstance(e.get("created_by"), str) and e["created_by"].strip()) or e["created_by"].strip() in kept)
    )
    out.absences = list(getattr(events, "absences", None) or [])
    out.topic_configured = getattr(events, "topic_configured", True)
    return out


def other_person_labels(events: "CalendarEvents | list[dict]") -> list[str]:
    """Labels der anderen Personen (``created_by``) in Reihenfolge des Erstauftretens."""
    labels: dict[str, None] = {}
    for e in events:
        label = e.get("created_by") if isinstance(e, dict) else None
        if isinstance(label, str) and label.strip():
            labels.setdefault(label.strip(), None)
    return list(labels)


# --- #114: Erkennung "Einzelheiten gewünscht?" (reine Funktion) ------------------------------------------------
_UMLAUTS = str.maketrans({"ä": "ae", "ö": "oe", "ü": "ue", "ß": "ss"})
_DETAIL_WORDS = frozenset({
    "genau", "genauer", "genaue", "details", "detail", "einzelheiten", "einzeln", "einzelne", "einzelnen",
    "konkret", "konkrete", "uhrzeit", "uhrzeiten", "aufzaehlen",
})
_WELCH_WORDS = frozenset({"welche", "welchen", "welcher", "welches"})
_TERMIN_WORDS = frozenset({
    "termin", "termine", "termins", "terminen", "verabredung", "verabredungen", "meeting", "meetings",
    "besprechung", "besprechungen",
})
# Frage nach einer genannten Person: Fragewort, Termin-/Detailwort oder Verbleib (Wiederkehr/Zuhause)
_ASK_ABOUT_PERSON = _WELCH_WORDS | _TERMIN_WORDS | _DETAIL_WORDS | {"was", "wann", "wo", "wohin", "womit"}
# zusätzlich nötig: Bezug auf Termine/Verbleib ("Schreib Person B, wann ich komme" ist keine Terminfrage)
_WHEREABOUTS = frozenset({"hause", "zuhause", "daheim", "zurueck"})  # "Ist Person B zu Hause?" reicht auch ohne Fragewort
_APPOINTMENT_REF = _TERMIN_WORDS | {
    "kalender", "vorhat", "macht", "unterwegs", "hause", "zuhause", "daheim", "da", "weg", "zurueck", "wieder",
    "kommt", "ist", "faehrt", "geht",
}
_FOLLOWUP_START = _WELCH_WORDS | {"wann", "wo", "wohin", "womit", "wer", "mehr", "danach"}
_FOLLOWUP_VERBS = frozenset({
    "zeig", "zeige", "nenn", "nenne", "erzaehl", "erzaehle", "sag", "sage", "zaehl", "zaehle", "zaehlt",
})
_FILLER = frozenset({"und", "aber", "ok", "okay", "na", "hm", "also", "ja"})
_CONFIRM = frozenset({"ja", "jap", "gerne", "gern", "bitte", "klar", "mach", "ok", "okay", "doch", "mal", "gut", "sicher"})
_SELF_WORDS = frozenset({"mein", "meine", "meinen", "meiner", "meinem", "meines", "ich"})
_VOCAB = tuple(sorted(_DETAIL_WORDS | _WELCH_WORDS | _TERMIN_WORDS | {"fuer", "wohin"}))


def _norm_text(value: Any) -> str:
    s = unicodedata.normalize("NFKC", value if isinstance(value, str) else "").lower().translate(_UMLAUTS)
    return " ".join(re.sub(r"[^a-z0-9 ]+", " ", s).split())


def _canon(tok: str) -> str:
    """Tippfehler-tolerant (nur längere Wörter): nächstes Wort des Erkennungswortschatzes, sonst unverändert."""
    if len(tok) < 5 or tok in _VOCAB:
        return tok
    m = difflib.get_close_matches(tok, _VOCAB, n=1, cutoff=0.84)
    return m[0] if m else tok


def _tok_like(tok: str, ref: str) -> bool:
    if tok == ref or tok in (ref + "s", ref + "es"):  # auch Genitiv ("Person Bs")
        return True
    return len(ref) >= 4 and len(tok) >= 4 and difflib.SequenceMatcher(None, tok, ref).ratio() >= 0.85


def _names_person(toks: list[str], label: Any) -> bool:
    parts = _norm_text(label).split()
    if not parts or len("".join(parts)) < 2:
        return False
    n = len(parts)
    return any(all(_tok_like(toks[i + j], parts[j]) for j in range(n)) for i in range(len(toks) - n + 1))


def released_labels(text: Any, labels: Any = (), previous_assistant: Any = None) -> set[str]:
    """Labels anderer Personen, zu denen Einzelheiten freigegeben sind. Eng gefasst, im Zweifel leer.

    (a) Eine genannte bekannte Person (``labels``, auch Genitiv/Tippfehler) zusammen mit einem Fragewort
    (welche/was/wann/wo/wohin/womit, Termin- oder Detailwort) UND einem Termin-/Verbleib-Bezug (Termin, Kalender,
    macht, unterwegs, zu Hause, da, weg, wieder, ist …): genau diese Person.
    (b) Eine kurze Nachfrage (höchstens 4 Wörter, ohne "mein/ich") unmittelbar nach einer Antwort, die eine Person aus
    ``labels`` samt Termin-/Außer-Haus-Formulierung nannte ("Welche?", "Und danach?", "Nenn sie mir", "Was macht er?",
    "Ja, bitte" nach einem Angebot): genau die in dieser Antwort genannten Personen.
    Alles andere ist eine allgemeine oder eigene Frage. Fehler/Unsinn: leere Menge.
    """
    try:
        norm = _norm_text(text)
        if not norm:
            return set()
        known = [lb for lb in (labels or ()) if isinstance(lb, str)]
        raw_toks = norm.split()
        toks = [_canon(t) for t in raw_toks]
        tset = set(toks)
        if (tset & _ASK_ABOUT_PERSON and tset & _APPOINTMENT_REF) or tset & _WHEREABOUTS:
            named = {lb for lb in known if _names_person(raw_toks, lb)}
            if named:
                return named
        prev = _norm_text(previous_assistant)
        if prev and ("termin" in prev or "ausser haus" in prev or "unterwegs" in prev) and len(toks) <= 4 \
                and not (set(raw_toks) & _SELF_WORDS):
            prev_toks = prev.split()
            earlier = {lb for lb in known if _names_person(prev_toks, lb)}
            if not earlier:
                return set()
            if all(t in _CONFIRM for t in toks):
                return earlier if re.search(r"einzelheit|detail|genauer|soll ich", prev) else set()
            words = [t for t in toks if t not in _FILLER]
            if words:
                first = words[0]
                if first in _FOLLOWUP_START or first in _FOLLOWUP_VERBS:
                    return earlier
                if first == "was" and len(words) > 1 and (words[1] in {"fuer", "denn", "genau"} or set(words) & {"er", "sie"}):
                    return earlier
        return set()
    except Exception:  # noqa: BLE001 — Erkennung darf nie scheitern; im Zweifel nur die Zusammenfassung
        return set()


def wants_event_details(text: Any, labels: Any = (), previous_assistant: Any = None) -> bool:
    """True, wenn zu mindestens einer anderen Person Einzelheiten freigegeben sind (siehe released_labels)."""
    return bool(released_labels(text, labels, previous_assistant))


class CalendarEvents(list):
    """Terminliste, die einen Abruffehler mit dem Rueckgabewert transportiert.

    error: None = ok/nicht aktiv, "auth" = Token ungueltig, "fetch" = sonstiger Abruffehler.
    absences: knappe Abwesenheits-Info (nur start/end/label) aus Kalendern mit Rolle absence_info.
    topic_configured: False nur, wenn ein Thema angefragt wurde, fuer das kein Kalender eingerichtet ist.
    Verhaelt sich fuer alle Aufrufer wie eine normale list; Instanzzustand je Objekt (nichts Geteiltes).
    """

    error: str | None = None
    topic_configured: bool = True

    def __init__(self, *args: Any) -> None:
        super().__init__(*args)
        self.absences: list[dict] = []  # je Instanz, NIE eine geteilte Liste auf Klassenebene


class CalendarService:
    """Google Calendar Integration via OAuth2 Refresh-Token.

    Kalender-PII nur In-Memory, keine LTM-Persistenz.  # Art. 5(2) DSGVO
    """

    def __init__(
        self,
        client_id: str | None = None,
        client_secret: str | None = None,
        refresh_token: str | None = None,
        roles_path: str | None = None,
        person_id: str = calendar_roles.DEFAULT_PERSON,
        source: str | None = None,
        updated_at: str | None = None,
    ) -> None:
        # Zugangsdaten als EIN Tupel: _build_service (läuft auch in Threads) liest es mit einem einzigen
        # Zugriff und sieht so nie eine Mischung aus altem und neuem Wert; reload_credentials ersetzt es atomar.
        self._creds: tuple[str | None, str | None, str | None] = (
            client_id or os.environ.get("GOOGLE_CLIENT_ID"),
            client_secret or os.environ.get("GOOGLE_CLIENT_SECRET"),
            refresh_token or os.environ.get("GOOGLE_REFRESH_TOKEN"),
        )
        self._enabled = all(self._creds)
        # Quelle für die Statusabfrage: "store" (Ablage) | "env" (Umgebung) | "none"
        self._source = source or ("env" if self._enabled else "none")
        self._updated_at = updated_at
        self._auth_failed = False  # set on invalid_grant — disables further attempts
        # Schützt _creds/_enabled/_auth_failed/_source/_gen gegen gleichzeitiges Neuladen und Abrufe in Threads.
        self._state_lock = threading.Lock()
        # Generation der Zugangsdaten: ein Abruf, der vor einem Neuladen startete, darf danach weder
        # den Auth-Fehler-Merker setzen noch Zwischenspeicher füllen (sonst wäre der neue Zugang "vergiftet").
        self._gen = 0
        # Zwischenspeicher (nur RAM). Uhr injizierbar für Tests. Der Google-Abruf läuft NICHT unter dem Lock.
        self._now = time.monotonic
        self._cache_lock = threading.Lock()
        self._events_cache: dict[tuple[int, str | None], tuple[float, CalendarEvents]] = {}
        self._calendar_list_cache: tuple[float, list[_Planned]] | None = None  # Plan, 600 s
        self._people: dict[str, calendar_roles.Person] = {}  # Ersteller-Zuordnung (#111), nur RAM, wie der Plan
        self._person_id = person_id  # vorerst nur der Inhaber; Pro-Person-Dienst ist nicht vorgesehen
        self._roles_path = roles_path if roles_path is not None else _default_roles_path()
        if not self._enabled:
            log.info(
                "calendar_service_disabled",
                reason="GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET / GOOGLE_REFRESH_TOKEN nicht gesetzt",
            )

    @classmethod
    def from_settings(cls, settings: Any) -> "CalendarService":
        """Start: Ablage (falls eingerichtet und gültig) hat Vorrang vor der Umgebung, sonst wie bisher."""
        stored = calendar_token_store.load_for_settings(settings)
        if stored is not None:
            return cls(stored.client_id, stored.client_secret, stored.refresh_token,
                       source="store", updated_at=stored.updated_at)
        return cls(
            client_id=settings.google_client_id,
            client_secret=settings.google_client_secret,
            refresh_token=settings.google_refresh_token,
        )

    def ready(self) -> bool:
        """True wenn alle Credentials vorhanden sind (Abruffehler siehe CalendarEvents.error)."""
        return self._enabled

    def reload_credentials(
        self, client_id: str | None, client_secret: str | None, refresh_token: str | None,
        source: str = "store", updated_at: str | None = None,
    ) -> None:
        """Setzt neue Zugangsdaten zur Laufzeit (ohne Neustart): Auth-Fehler-Merker zurück, Zwischenspeicher leer.

        Fehlt ein Wert, ist der Dienst danach deaktiviert (wie beim Start). Loggt nie Werte.
        """
        creds = (client_id or None, client_secret or None, refresh_token or None)
        with self._state_lock:
            self._creds = creds
            self._enabled = all(creds)
            self._source = source if self._enabled else "none"
            self._updated_at = updated_at
            self._auth_failed = False
            self._gen += 1
        self.clear_cache()  # nach dem Hochzählen: später fertige alte Abrufe schreiben nichts mehr hinein
        log.info("calendar_credentials_reloaded", source=self._source, enabled=self._enabled)

    def reload_from_store(self, settings: Any) -> str:
        """Liest die Ablage neu ein. Rückgabe: "reloaded" oder der Grund des Scheiterns:
        "store_off" | "path_rejected" | "missing" | "permissions" | "invalid".

        Bei einem Scheitern bleibt der Zustand UNVERÄNDERT (ein funktionierender Zugang wird nie still abgeschaltet).
        """
        stored, reason = calendar_token_store.inspect_for_settings(settings, self._person_id)
        if stored is None:
            return reason  # path_rejected | missing | permissions | invalid (nie Inhalte)
        self.reload_credentials(stored.client_id, stored.client_secret, stored.refresh_token,
                                source="store", updated_at=stored.updated_at)
        return "reloaded"

    def auth_status(self) -> dict:
        """Status ohne Geheimnisse: aktiv, Quelle, Auth-Fehler, Zeitstempel der Ablage."""
        with self._state_lock:
            return {
                "enabled": self._enabled,
                "source": self._source,
                "auth_failed": self._auth_failed,
                "updated_at": self._updated_at,
            }

    def _build_service(self, creds: tuple[str | None, str | None, str | None] | None = None) -> Any:
        """Baut einen authentifizierten Google Calendar API-Client.

        Kalender-PII nur In-Memory, keine LTM-Persistenz.  # Art. 5(2) DSGVO
        Credentials werden NICHT auf Disk geschrieben (In-Memory-Only).
        """
        # Import erst hier — damit der Service bei fehlenden Libs nicht den Startup crasht
        from google.oauth2.credentials import Credentials  # type: ignore[import-untyped]
        from googleapiclient.discovery import build  # type: ignore[import-untyped]

        client_id, client_secret, refresh_token = creds if creds is not None else self._creds
        creds = Credentials(
            token=None,
            refresh_token=refresh_token,
            client_id=client_id,
            client_secret=client_secret,
            token_uri="https://oauth2.googleapis.com/token",
            scopes=[_CALENDAR_SCOPE],
        )
        # build() speichert NICHTS auf Disk — In-Memory-Only (DSGVO Auflage 4)
        return build("calendar", "v3", credentials=creds, cache_discovery=False)

    def get_upcoming_events(self, days: int = 7, topic: str | None = None) -> CalendarEvents:
        """Holt anstehende Kalender-Events für die nächsten N Tage.

        Kalender-PII nur In-Memory, keine LTM-Persistenz.  # Art. 5(2) DSGVO

        Kein Logging von Event-Details (nur Zähler + Fehlertyp) — DSGVO Auflage 3.
        In-Memory-Only: keine Datei-Writes, kein Disk-Cache — DSGVO Auflage 4.

        Welche Kalender abgefragt werden, bestimmt die Rollen-Zuordnungsdatei (calendar_roles):
          topic=None  Hauptkalender + Rolle ``general`` als Terminliste; Rolle ``absence_info`` getrennt als
                      ``result.absences`` (nur start/end/label, max. 5, zusammengefasst).
          topic="x"   NUR Kalender mit Rolle ``topic:x`` (Terminliste, absences leer). Ist keiner
                      eingerichtet: leer, error=None, ``topic_configured=False``.
        Ohne Zuordnungsdatei (oder defekt): nur der Hauptkalender. Nicht zugeordnete Kalender nie.
        Zusammengeführt, dedupliziert (iCalUID/id + normalisierter Startzeitpunkt, Hauptkalender gewinnt),
        nach Startzeit sortiert, max. _MAX_EVENTS Termine, höchstens _MAX_CALENDARS Kalender, nacheinander.

        Returns:
            CalendarEvents (Liste von dicts) mit keys: summary, start, end, location (optional),
            calendar (optional, Anzeigename, auf 60 Zeichen gekürzt, ohne Steuerzeichen),
            created_by (optional, #111: Label aus der Zuordnung ``people``; nie eine E-Mail-Adresse).
            Leere Liste bei fehlendem Token/Key oder API-Fehler (kein Crash);
            bei Abruffehler traegt das Ergebnis (CalendarEvents) .error "auth"/"fetch".
            Teilfehler (einzelne Kalender scheitern oder das Zeitbudget _TIME_BUDGET_S ist
            aufgebraucht): Termine der übrigen Kalender, error=None, Log calendar_partial_failure.
            Alle Kalender scheitern: error="fetch". Auth-Fehler irgendwo: error="auth".

        Zwischenspeicher (nur RAM): Ergebnis je (days, topic) 120 s, Kalenderliste/Zuordnung 600 s; nur
        vollständige Erfolge (kein Teilfehler, kein Rückfall auf den Hauptkalender); Rückgabe ist eine Kopie.
        """
        if not self._enabled:
            return CalendarEvents()
        if self._auth_failed:
            res = CalendarEvents()
            res.error = "auth"
            return res

        with self._state_lock:  # Generation + Zugangsdaten als EIN Snapshot (siehe reload_credentials)
            gen, creds = self._gen, self._creds
        key = (days, topic)
        cached = self._events_cache_get(key)
        if cached is not None:
            log.debug("calendar_events_cache_hit")  # ohne Daten
            return cached

        try:
            service = self._build_service(creds)
            now = datetime.now(tz=timezone.utc)
            time_max = now + timedelta(days=days)

            t0 = self._now()
            plan, fallback = self._plan_calendars(service, gen)
            with self._cache_lock:
                people = self._people
            event_fields = "items(id,iCalUID,summary,start,end,location)"
            if people:  # Ersteller nur anfordern, wenn eine Zuordnung eingerichtet ist (Datenminimierung)
                event_fields = "items(id,iCalUID,summary,start,end,location,creator(email),organizer(email))"
            if topic is not None:
                if fallback:
                    # Kalenderliste nicht abrufbar → "nicht eingerichtet" wäre eine falsche Aussage
                    log.warning("calendar_fetch_failed", error_type="CalendarListUnavailable", topic=topic)
                    res = CalendarEvents()
                    res.error = "fetch"
                    return res
                calendars = [c for c in plan if calendar_roles.topic_of(c.role) == topic]
                if not calendars:
                    res = CalendarEvents()
                    res.topic_configured = False
                    return res
            else:
                # general zuerst (Zeitbudget!), danach absence_info; Reihenfolge sonst stabil
                calendars = sorted(
                    (c for c in plan if c.role in (calendar_roles.ROLE_GENERAL, calendar_roles.ROLE_ABSENCE)),
                    key=lambda c: c.role != calendar_roles.ROLE_GENERAL,
                )
            if len(calendars) > _MAX_CALENDARS:
                log.info("calendar_count_capped", total=len(calendars), limit=_MAX_CALENDARS)
                calendars = calendars[:_MAX_CALENDARS]
            total = len(calendars)

            merged: list[dict] = []
            absence_raw: list[tuple[dict, dict, str]] = []
            seen: dict[tuple[str, str], dict] = {}
            failed = 0
            queried = 0
            last_exc: Exception | None = None
            for idx, cal in enumerate(calendars):
                if idx > 0 and self._now() - t0 >= _TIME_BUDGET_S:
                    skipped = total - idx
                    failed += skipped
                    log.warning(
                        "calendar_time_budget_exceeded",
                        skipped=skipped,
                        total=total,
                        ms=int((self._now() - t0) * 1000),
                    )
                    break
                queried += 1
                is_absence = cal.role == calendar_roles.ROLE_ABSENCE
                try:
                    result = (
                        service.events()
                        .list(
                            calendarId=cal.id,
                            timeMin=now.isoformat(),
                            timeMax=time_max.isoformat(),
                            maxResults=_MAX_EVENTS,
                            singleEvents=True,
                            orderBy="startTime",
                            # Nur benötigte Fields — minimiert PII-Transfer (DSGVO Minimierungsprinzip);
                            # Abwesenheitskalender liefern nur den Zeitraum, nie Titel/Ort.
                            fields="items(start,end)" if is_absence else event_fields,
                        )
                        .execute()
                    )
                except Exception as cal_exc:  # noqa: BLE001
                    if _is_auth_error(cal_exc):
                        raise  # Auth-Fehler eines Kalenders = Auth-Fehler des Abrufs
                    failed += 1
                    last_exc = cal_exc
                    if isinstance(cal_exc, (TimeoutError, socket.timeout)):
                        # fail-fast: restliche Kalender nicht mehr abfragen
                        skipped = total - idx - 1
                        if skipped:
                            failed += skipped
                            log.warning("calendar_fail_fast_timeout", skipped=skipped, total=total)
                        break
                    continue

                if is_absence:
                    for item in result.get("items", []):
                        absence_raw.append((item.get("start") or {}, item.get("end") or {},
                                            cal.label or _DEFAULT_ABSENCE_LABEL))
                    continue

                for item in result.get("items", []):
                    # Duplikate (z. B. Einladung in mehreren Kalendern): Hauptkalender wird zuerst
                    # abgefragt → der erste Eintrag gewinnt. Schlüssel: ID + normalisierter
                    # Startzeitpunkt (zeitzonenrobust, gleicher Schlüssel wie die Sortierung).
                    uid = item.get("iCalUID") or item.get("id")
                    if uid:
                        dkey = (str(uid), _start_sort_key(item).isoformat())
                        if dkey in seen:
                            first = seen[dkey]
                            if people and "created_by" not in first:  # Kopie in anderem Kalender kann den Ersteller tragen
                                label = _creator_label(item, people)
                                if label:
                                    first["created_by"] = label
                            continue
                    event: dict = {
                        "summary": item.get("summary", "(kein Titel)"),
                        "start": item.get("start", {}),
                        "end": item.get("end", {}),
                    }
                    # Location ist optional — nur wenn vorhanden
                    if item.get("location"):
                        event["location"] = item["location"]
                    if cal.name:
                        event["calendar"] = cal.name
                    if people:
                        label = _creator_label(item, people)  # Rohfelder creator/organizer bleiben hier liegen
                        if label:
                            event["created_by"] = label
                    if uid:
                        seen[dkey] = event
                    merged.append(event)

            if failed and failed == total:
                # DSGVO Auflage 3: nur Fehlertyp, kein exc_str
                log.warning(
                    "calendar_fetch_failed",
                    error_type=type(last_exc).__name__,
                    failed=failed,
                    total=total,
                )
                res = CalendarEvents()
                res.error = "fetch"
                return res
            if failed:
                log.warning("calendar_partial_failure", failed=failed, total=total)

            merged.sort(key=_start_sort_key)  # stabil: Kalenderreihenfolge bleibt bei Gleichstand
            events = CalendarEvents(merged[:_MAX_EVENTS])
            events.absences = _merge_absences(absence_raw)
            if not failed and not fallback:  # Teilfehler/Rückfall nie speichern: nächster Abruf versucht es erneut
                self._events_cache_put(key, events, gen)

            # DSGVO Auflage 3: Kein Logging von Event-Details — nur Zahlen (Thema "muell" ist kein Inhalt)
            log.info(
                "calendar_events_fetched",
                count=len(events),
                days=days,
                calendars=queried,
                failed=failed,
                ms=int((self._now() - t0) * 1000),
                topic=topic,
            )
            return events

        except Exception as exc:  # noqa: BLE001
            is_auth_error = _is_auth_error(exc)
            stale = False
            if is_auth_error:
                with self._state_lock:
                    stale = gen != self._gen  # inzwischen neue Zugangsdaten geladen
                    if not stale:
                        self._auth_failed = True
            if is_auth_error and stale:
                # Alter Zugang scheitert nach dem Neuverbinden: weder Merker noch "auth" (falscher Waechter-Push)
                log.info("calendar_auth_stale_ignored")
            elif is_auth_error:
                self.clear_cache()
                log.error(
                    "calendar_auth_failed",
                    detail="GOOGLE_REFRESH_TOKEN abgelaufen oder widerrufen — Re-Autorisierung erforderlich.",
                    action=(
                        "python backend/reauth_google_calendar.py lokal ausführen, die erzeugte Datei in die "
                        "Token-Ablage des Servers legen und POST /calendar/auth/reload aufrufen; alternativ "
                        "neuen Token in .env eintragen und den Dienst api neu starten."
                    ),
                )
            else:
                # DSGVO Auflage 3: Kein Logging von Kalender-Inhalt — nur Fehlertyp (kein exc_str)
                log.warning(
                    "calendar_fetch_failed",
                    error_type=type(exc).__name__,
                )
            res = CalendarEvents()
            res.error = "auth" if (is_auth_error and not stale) else "fetch"
            return res

    def _plan_calendars(self, service: Any, gen: int | None = None) -> tuple[list[_Planned], bool]:
        """Liefert (Plan, fallback): alle abfragbaren Kalender mit Rolle, Hauptkalender zuerst.

        Plan = Zuordnungsdatei (calendar_roles) aufgelöst gegen die Kalenderliste. Ohne Zuordnung nur der
        Hauptkalender (dann entfällt der Kalenderlisten-Aufruf). Scheitert die Kalenderliste (kein
        Auth-Fehler): Rückfall auf den Hauptkalender allein (fallback=True; nicht speichern).
        Auth-Fehler werden weitergereicht. Namen/IDs/Labels werden nie geloggt (DSGVO Auflage 3).
        Das Ergebnis (inkl. Zuordnungsdatei) wird _CALENDAR_LIST_CACHE_TTL_S gespeichert.
        """
        with self._cache_lock:
            lc = self._calendar_list_cache
            if lc is not None and self._now() < lc[0]:
                return list(lc[1]), False

        primary_only = [_Planned("primary", None, calendar_roles.ROLE_GENERAL, None)]
        cfg = calendar_roles.load_config(self._roles_path, self._person_id)
        rules, people = cfg.rules, cfg.people
        if not rules:
            self._plan_cache_put(primary_only, gen, people)
            return primary_only, False

        entries: list[dict] = []
        try:
            page_token: str | None = None
            for _ in range(_MAX_CALENDAR_PAGES):
                kwargs: dict = {
                    "maxResults": _CALENDAR_LIST_PAGE_SIZE,
                    "fields": "nextPageToken,items(id,summary,summaryOverride,primary)",
                }
                if page_token:
                    kwargs["pageToken"] = page_token
                resp = service.calendarList().list(**kwargs).execute()
                entries.extend(i for i in resp.get("items", []) if isinstance(i, dict) and i.get("id"))
                page_token = resp.get("nextPageToken")
                if not page_token:
                    break
        except Exception as exc:  # noqa: BLE001
            if _is_auth_error(exc):
                raise
            log.warning("calendar_list_failed", error_type=type(exc).__name__)
            with self._cache_lock:
                if gen is None or gen == self._gen:  # gleiche Generationsprüfung wie _plan_cache_put
                    self._people = dict(people)  # Rückfall wird nicht gespeichert, die Zuordnung gilt für diesen Abruf
            return primary_only, True

        primary_entry = next((e for e in entries if e.get("primary") is True), None)
        if primary_entry is None:
            primary_entry = {"id": "primary", "primary": True}
        ordered = [primary_entry] + [e for e in entries if e is not primary_entry]

        plan: list[_Planned] = []
        matched: set[int] = set()
        for e in ordered:
            rule = calendar_roles.resolve_rule(rules, e)
            if rule is not None:
                matched.add(id(rule))
            if e is primary_entry:
                role = rule.role if rule else calendar_roles.ROLE_GENERAL  # Hauptkalender: Standard general
            elif rule is None:
                continue  # nicht zugeordnet → nie abfragen (selected spielt keine Rolle)
            else:
                role = rule.role
            if role == calendar_roles.ROLE_IGNORE:
                continue
            plan.append(_Planned(
                e["id"],
                _clean_calendar_name(e.get("summaryOverride") or e.get("summary")),
                role,
                rule.label if rule else None,
            ))
        # nur Zahlen; unresolved = Regeln, die keinem Kalender dieses Kontos zugeordnet werden konnten
        log.info(
            "calendar_roles_resolved",
            general=sum(c.role == calendar_roles.ROLE_GENERAL for c in plan),
            absence=sum(c.role == calendar_roles.ROLE_ABSENCE for c in plan),
            topics=sum(calendar_roles.topic_of(c.role) is not None for c in plan),
            unresolved=sum(1 for r in rules if id(r) not in matched),
        )
        self._plan_cache_put(plan, gen, people)
        return plan, False

    # --- Kurzzeit-Zwischenspeicher: nur Arbeitsspeicher, nie Disk/Log (DSGVO Auflage 3/4) ---

    def _plan_cache_put(
        self, plan: list[_Planned], gen: int | None = None,
        people: "dict[str, calendar_roles.Person] | None" = None,
    ) -> None:
        with self._cache_lock:
            if gen is not None and gen != self._gen:
                return  # Abruf mit inzwischen ersetzten Zugangsdaten: nicht speichern
            self._calendar_list_cache = (self._now() + _CALENDAR_LIST_CACHE_TTL_S, list(plan))
            self._people = dict(people or {})

    def clear_cache(self) -> None:
        """Leert Kalenderliste/Zuordnung und Terminergebnisse (z. B. nach Auth-Fehler/Token-Neuladung)."""
        with self._cache_lock:
            self._events_cache.clear()
            self._calendar_list_cache = None
            self._people = {}

    def _events_cache_get(self, key: tuple[int, str | None]) -> "CalendarEvents | None":
        with self._cache_lock:
            entry = self._events_cache.get(key)
            if entry is None:
                return None
            if self._now() >= entry[0]:
                del self._events_cache[key]
                return None
            return _copy_events(entry[1])

    def _events_cache_put(self, key: tuple[int, str | None], events: "CalendarEvents", gen: int | None = None) -> None:
        with self._cache_lock:
            if gen is not None and gen != self._gen:
                return  # Abruf mit inzwischen ersetzten Zugangsdaten: nicht speichern
            self._events_cache.pop(key, None)
            self._events_cache[key] = (self._now() + _EVENTS_CACHE_TTL_S, _copy_events(events))
            while len(self._events_cache) > _EVENTS_CACHE_MAX_ENTRIES:
                del self._events_cache[next(iter(self._events_cache))]  # ältester Eintrag

    def format_for_prompt(self, events: list[dict], max_events: int = 3) -> str | None:
        """Formatiert Events als kompakten [Kalender-Kontext]-Block für den System-Prompt.

        Kalender-PII nur In-Memory, keine LTM-Persistenz.  # Art. 5(2) DSGVO

        Args:
            events: Liste von Event-dicts aus get_upcoming_events().
            max_events: Maximale Anzahl Events im Prompt (Standard: 3).

        Returns:
            Formatierter String oder None wenn weder Termine noch Abwesenheiten vorliegen.
            Abwesenheiten (``events.absences``) kommen als EINE Zusatzzeile außerhalb von max_events
            (höchstens 3 Zeiträume); ohne Abwesenheiten bleibt die Ausgabe unverändert.
        """
        absences = getattr(events, "absences", None) or []
        if not events and not absences:
            return None

        now = datetime.now(tz=timezone.utc)
        today_date = now.date()
        tomorrow_date = today_date + timedelta(days=1)

        # Kalendername nur, wenn Termine aus mehr als einem Kalender stammen (#99)
        multi_calendar = len({e.get("calendar") for e in events if e.get("calendar")}) > 1

        lines: list[str] = []
        for event in events[:max_events]:
            summary = event.get("summary", "(kein Titel)")
            if multi_calendar and event.get("calendar"):
                summary = f"{summary} (Kalender: {event['calendar']})"
            if event.get("created_by"):
                summary = f"{summary} (von {event['created_by']})"
            start_info = event.get("start", {})

            # Datum/Zeit aus start_info ermitteln
            start_str = start_info.get("dateTime") or start_info.get("date", "")
            label = _format_event_label(start_str, today_date, tomorrow_date)
            lines.append(f"{label}: {summary}")

        absence_line = _format_absence_line(absences)
        if absence_line:
            lines.append(absence_line)

        if not lines:
            return None

        return "[Kalender-Kontext]\n" + "\n".join(lines)


def _event_label_parts(
    start_str: str,
    today_date: "datetime.date",
    tomorrow_date: "datetime.date",
) -> tuple[str, str | None]:
    """Zerlegt ein Event-Start-Datum in (Tages-Label, Uhrzeit|None).

    Tages-Label: "Heute" / "Morgen" / Wochentags-Kürzel (Mo..So) / "Demnächst" (Parse-Fehler
    oder leerer Input). Uhrzeit: "HH:MM" bei dateTime-Events, None bei ganztägigen Events.

    Einzige Quelle der Datums-/Zeit-Logik — _format_event_label (System-Prompt, verhaltensneutral)
    UND build_calendar_card_data (Karte) bauen beide darauf auf, damit nichts auseinanderdriftet.
    """
    if not start_str:
        return ("Demnächst", None)

    try:
        if "T" in start_str:
            # dateTime: "2026-05-07T10:00:00+02:00"
            dt = datetime.fromisoformat(start_str)
            event_date = dt.date()
            time_str = dt.strftime("%H:%M")
        else:
            # date: "2026-05-07" (ganztägig)
            from datetime import date
            event_date = date.fromisoformat(start_str)
            time_str = None

        if event_date == today_date:
            prefix = "Heute"
        elif event_date == tomorrow_date:
            prefix = "Morgen"
        else:
            weekdays = ["Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"]
            prefix = weekdays[event_date.weekday()]

        return (prefix, time_str)

    except (ValueError, AttributeError):
        return ("Demnächst", None)


def _format_event_label(
    start_str: str,
    today_date: "datetime.date",
    tomorrow_date: "datetime.date",
) -> str:
    """Formatiert ein Event-Datum als lesbares Label (Heute/Morgen/Wochentag).

    Verhaltensneutral refaktoriert: baut auf _event_label_parts auf, Ausgabe unverändert
    (z.B. "Heute 10:00", "Morgen", "Mi 14:00", "Demnächst").
    """
    prefix, time_str = _event_label_parts(start_str, today_date, tomorrow_date)
    if time_str:
        return f"{prefix} {time_str}"
    return prefix


def build_calendar_card_data(events: list[dict], max_events: int = 5) -> dict | None:
    """Mappt Kalender-Events auf die CalendarCardData-Struktur für die read-only Karte (Stufe 1).

    Returns:
        {"events": [{"title", "when_label", "time"?, "location"?}, ...]}  oder
        None, wenn keine Events vorliegen (dann wird KEINE Karte gesendet).

    DSGVO: reine In-Memory-Transformation, kein Logging von Event-Inhalten, keine Persistenz.
    """
    if not events:
        return None

    now = datetime.now(tz=timezone.utc)
    today_date = now.date()
    tomorrow_date = today_date + timedelta(days=1)

    mapped: list[dict] = []
    for event in events[:max_events]:
        title = event.get("summary") or "(kein Titel)"
        start_info = event.get("start", {}) or {}
        start_str = start_info.get("dateTime") or start_info.get("date", "")
        when_label, time_str = _event_label_parts(start_str, today_date, tomorrow_date)

        entry: dict = {"title": title, "when_label": when_label}
        if time_str:
            entry["time"] = time_str
        location = event.get("location")
        if location:
            entry["location"] = location
        if event.get("created_by"):
            entry["created_by"] = event["created_by"]
        mapped.append(entry)

    if not mapped:
        return None
    return {"events": mapped}

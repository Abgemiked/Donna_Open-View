"""
schedule_service.py — DONNA-17 Fix
Fetcht den Stream-Plan von your-donna-instance.example.com/api/schedule (JSON-API).
Wird vom Twitch-Bot für Schedule-Fragen genutzt.
"""
from __future__ import annotations
import time
import unicodedata
import httpx
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo
from app.core.logger import get_logger

log = get_logger("service.schedule")

_SCHEDULE_API_URL = "https://your-donna-instance.example.com/api/schedule"
_TIMEOUT = 8.0

_DAYS_DE = ["Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"]

# DONNA-148: Modul-Level-Cache für get_schedule_for_prompt() — TTL 3600s
# tuple[timestamp_float, result_str] oder None wenn noch kein Cache vorhanden
_schedule_cache: tuple[float, str] | None = None
_SCHEDULE_CACHE_TTL = 3600.0

# #119: Notizen der Streamplan-API sind fremder Text → säubern + kürzen, nur als Datenzeile ausgeben.
_NOTE_MAX_LEN = 80
_STRIP_CHARS = frozenset("[]{}<>`")


def clean_note(text: object, limit: int = _NOTE_MAX_LEN) -> str:
    """Säubert fremden Text: Steuer-/Format-/Zeilentrenner-Zeichen (Unicode Cc, Cf, Zl, Zp) und Klammern/Backticks raus.

    Cc/Zl/Zp (Zeilenumbrüche, Tabs …) werden zu Leerzeichen, Cf (Zero-Width, Bidi, BOM) entfallen;
    danach Whitespace normalisiert und auf ``limit`` Zeichen gekürzt. Kein String → "".
    """
    if not isinstance(text, str):
        return ""
    out: list[str] = []
    for ch in text:
        cat = unicodedata.category(ch)
        if cat == "Cf" or ch in _STRIP_CHARS:
            continue
        out.append(" " if cat in ("Cc", "Zl", "Zp") else ch)
    return " ".join("".join(out).split())[:limit].rstrip()


def _day_range_label(idxs: list[int]) -> str:
    """[0,1,2,4] → "Mo–Mi, Fr" (aufeinanderfolgende Tage als Bereich)."""
    runs: list[list[int]] = []
    for i in sorted(set(idxs)):
        if runs and i == runs[-1][-1] + 1:
            runs[-1].append(i)
        else:
            runs.append([i])
    out = []
    for run in runs:
        first, last = _DAYS_DE[run[0]][:2], _DAYS_DE[run[-1]][:2]
        out.append(first if len(run) == 1 else f"{first}–{last}")
    return ", ".join(out)


def _format_ausfall(days: list[dict]) -> str | None:
    """Ausfall-Tage gleicher Notiz zu Bereichen: "Di–Do: Ausfall (Grund: Urlaub); Fr: Ausfall"."""
    groups: dict[str, list[int]] = {}
    for day in days:
        if not isinstance(day, dict) or day.get("status") != "ausfall":
            continue
        idx = day.get("day_of_week")  # kein Default: fehlender Tag darf nicht als Montag zählen
        if not isinstance(idx, int) or isinstance(idx, bool) or not 0 <= idx <= 6:
            continue
        groups.setdefault(clean_note(day.get("note")), []).append(idx)
    if not groups:
        return None
    parts = []
    for note, idxs in sorted(groups.items(), key=lambda kv: min(kv[1])):
        reason = f" (Grund: {note})" if note else ""
        parts.append(f"{_day_range_label(idxs)}: Ausfall{reason}")
    return "; ".join(parts)


def get_schedule_for_prompt() -> str | None:
    """Gibt den aktuellen Streamplan als kompakten Einzeiler für den System-Prompt zurück.

    DONNA-148: Sync-Implementierung (kein nested-asyncio-Problem in chat.py).
    Cache-TTL: 3600s — wird bei jedem Cache-Miss via httpx.get() aktualisiert.

    Beispiel-Output:
        "Fr: 11:30–13:00 Hearthstone SoloQ, 13:00–16:00 Valorant, 20:30–22:30 Spieleabend"
    Gibt None zurück bei Fehler oder leerem Plan.
    """
    global _schedule_cache

    # Cache-Check
    now_ts = time.time()
    if _schedule_cache is not None:
        cached_at, cached_result = _schedule_cache
        if now_ts - cached_at < _SCHEDULE_CACHE_TTL:
            return cached_result

    try:
        resp = httpx.get(_SCHEDULE_API_URL, timeout=_TIMEOUT)
        resp.raise_for_status()
        data = resp.json()

        days = data.get("days", [])
        if not days:
            return None

        # DONNA-224: Vergangene Stream-Tage als "abgelaufen" markieren, damit
        # der LLM keine vergangenen Streams als "kommend" präsentiert.
        berlin = ZoneInfo("Europe/Berlin")
        now_berlin = datetime.now(berlin)
        today_idx_plan = now_berlin.weekday()

        parts: list[str] = []
        for day in days:
            if day.get("status") != "stream":
                continue
            idx = day.get("day_of_week", 0)
            if idx > 6:
                continue
            entries = day.get("entries", [])
            if not entries:
                continue
            # DONNA-224: Prüfen ob mindestens ein Slot dieses Tages noch in der Zukunft liegt.
            # delta_days > 0 = nächste Woche (also in der Zukunft), delta_days == 0 = heute.
            delta_days_plan = (idx - today_idx_plan) % 7
            base_plan = now_berlin.replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(days=delta_days_plan)
            all_past = True
            for entry in entries:
                try:
                    to_h, to_m = map(int, entry.get("to", "00:00").split(":"))
                    slot_end = base_plan.replace(hour=to_h, minute=to_m)
                    if slot_end > now_berlin:
                        all_past = False
                        break
                except (ValueError, KeyError):
                    all_past = False  # Parsing-Fehler → konservativ: nicht als vergangen markieren
                    break

            # Tagesname abkürzen: "Montag" → "Mo", "Dienstag" → "Di" etc.
            short = _DAYS_DE[idx][:2]
            slot_parts: list[str] = []
            for e in entries:
                from_t = e.get("from", "")
                to_t = e.get("to", "")
                activity = e.get("activity", "").strip()
                slot = f"{from_t}–{to_t}"
                if activity:
                    slot += f" {activity}"
                slot_parts.append(slot)
            # Vergangene Tage mit "(vergangen)"-Hinweis — werden nicht als nächster Stream vorgeschlagen
            suffix = " (vergangen)" if all_past else ""
            parts.append(f"{short}{suffix}: " + ", ".join(slot_parts))

        full_plan = "; ".join(parts) if parts else None

        # DONNA-149: nächsten Stream-Tag berechnen und alle Slots voranstellen
        next_prefix: str | None = None
        if full_plan:
            berlin = ZoneInfo("Europe/Berlin")
            now_berlin = datetime.now(berlin)
            today_idx = now_berlin.weekday()  # Mo=0, So=6
            ordered_days = sorted(days, key=lambda d: (d.get("day_of_week", 0) - today_idx) % 7)
            for day in ordered_days:
                if day.get("status") != "stream":
                    continue
                day_idx = day.get("day_of_week", 0)
                entries = day.get("entries", [])
                if not entries:
                    continue
                delta_days = (day_idx - today_idx) % 7
                base = now_berlin.replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(days=delta_days)
                # Prüfen ob mindestens ein Slot dieses Tages in der Zukunft liegt
                has_future = False
                for entry in entries:
                    try:
                        from_h, from_m = map(int, entry["from"].split(":"))
                    except (KeyError, ValueError):
                        continue
                    if base.replace(hour=from_h, minute=from_m) > now_berlin:
                        has_future = True
                        break
                if not has_future:
                    continue
                # Alle Slots des Tages aufbauen: "HH:MM–HH:MM Spiel, dann HH:MM–HH:MM Spiel"
                short = _DAYS_DE[day_idx][:2]
                if delta_days == 0:
                    when = "heute"
                elif delta_days == 1:
                    when = "morgen"
                else:
                    when = _DAYS_DE[day_idx]
                slot_strs: list[str] = []
                for entry in entries:
                    from_t = entry.get("from", "")
                    to_t = entry.get("to", "")
                    activity = entry.get("activity", "").strip()
                    slot = f"{from_t}–{to_t}"
                    if activity:
                        slot += f" {activity}"
                    slot_strs.append(slot)
                slots_string = ", dann ".join(slot_strs)
                next_prefix = f"Nächster Stream-Tag: {short} ({when}): {slots_string}."
                break

        if full_plan and next_prefix:
            result = f"{next_prefix} Kompletter Plan: {full_plan}"
        else:
            result = full_plan

        # #119: Ausfall-Tage samt Grund (reine Stream-Wochen bleiben byte-gleich)
        ausfall = _format_ausfall(days)
        if ausfall:
            result = f"{result}; {ausfall}" if result else f"Kein Stream diese Woche. {ausfall}"

        _schedule_cache = (now_ts, result) if result is not None else (now_ts, "")
        return result

    except Exception as e:
        log.warning("schedule_for_prompt_failed", error=str(e))
        return None


async def fetch_last_stream() -> str | None:
    """Gibt den zuletzt vergangenen Stream-Slot aus dem aktuellen Wochenplan zurück.

    Format: "Laut Plan: Mike hat zuletzt [heute/gestern/Wochentag] von HH:MM bis HH:MM Uhr Spiel gestreamt."
    """
    berlin = ZoneInfo("Europe/Berlin")
    now = datetime.now(berlin)
    today_idx = now.weekday()

    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            resp = await client.get(_SCHEDULE_API_URL)
            resp.raise_for_status()
            data = resp.json()

        days = data.get("days", [])
        if not days:
            return "Kein Streamplan hinterlegt."

        # Alle Slots der Woche aufbauen, absteigende Reihenfolge (neueste zuerst)
        slots = []
        for day in days:
            day_idx = day.get("day_of_week", 0)
            if day.get("status") != "stream":
                continue
            delta_days = (day_idx - today_idx) % 7
            # Tage in der Vergangenheit: delta_days > 0 bedeutet NÄCHSTE Woche (Zukunft),
            # deshalb für Vergangenheit: wenn delta_days > 0 → letzte Woche (negativ)
            if delta_days > 0:
                delta_days -= 7  # letzte Woche
            base = now.replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(days=delta_days)
            for entry in day.get("entries", []):
                from_h, from_m = map(int, entry["from"].split(":"))
                to_h, to_m = map(int, entry["to"].split(":"))
                stream_start = base.replace(hour=from_h, minute=from_m)
                stream_end = base.replace(hour=to_h, minute=to_m)
                if stream_end <= stream_start:
                    stream_end += timedelta(days=1)
                slots.append((stream_end, stream_start, entry, day_idx, delta_days))

        # Letzter Slot der bereits beendet ist
        past_slots = [(end, start, entry, day_idx, dd) for end, start, entry, day_idx, dd in slots if end <= now]
        if not past_slots:
            return "Diese Woche war noch kein Stream geplant."

        past_slots.sort(key=lambda x: x[0], reverse=True)
        _, stream_start, entry, day_idx, delta_days = past_slots[0]

        activity = entry.get("activity", "").strip()
        if delta_days == 0:
            when = "heute"
        elif delta_days == -1:
            when = "gestern"
        else:
            when = f"am {_DAYS_DE[day_idx]}"
        return f"Laut Plan: Mike hat zuletzt {when} von {entry['from']} bis {entry['to']} Uhr {activity} gestreamt."

    except Exception as e:
        log.warning("schedule_fetch_last_failed", error=str(e))
        return None


async def fetch_next_stream() -> str | None:
    """Gibt den nächsten geplanten Stream kompakt zurück.

    Format: "Mike streamt [heute/morgen/Wochentag] um HH:MM bis HH:MM Uhr Spiel"
    Oder "Mike streamt gerade Spiel bis HH:MM Uhr" wenn live.
    """
    berlin = ZoneInfo("Europe/Berlin")
    now = datetime.now(berlin)
    today_idx = now.weekday()  # 0=Montag, 6=Sonntag

    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            resp = await client.get(_SCHEDULE_API_URL)
            resp.raise_for_status()
            data = resp.json()

        days = data.get("days", [])
        if not days:
            return "Kein Streamplan hinterlegt."

        ordered_days = sorted(days, key=lambda d: (d.get("day_of_week", 0) - today_idx) % 7)

        for day in ordered_days:
            day_idx = day.get("day_of_week", 0)
            if day.get("status") != "stream":
                continue
            entries = day.get("entries", [])
            if not entries:
                continue

            delta_days = (day_idx - today_idx) % 7
            base = now.replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(days=delta_days)

            for entry in entries:
                from_h, from_m = map(int, entry["from"].split(":"))
                to_h, to_m = map(int, entry["to"].split(":"))
                stream_start = base.replace(hour=from_h, minute=from_m)
                stream_end = base.replace(hour=to_h, minute=to_m)
                if stream_end <= stream_start:  # Mitternacht überschreitend
                    stream_end += timedelta(days=1)

                # Bereits gestartete Slots überspringen (egal ob gerade laufend oder vorbei)
                # → gibt immer den *nächsten* zukünftigen Stream zurück
                if stream_start <= now:
                    continue

                activity = entry.get("activity", "").strip()
                if delta_days == 0:
                    when = f"heute um {entry['from']}"
                elif delta_days == 1:
                    when = f"morgen um {entry['from']}"
                else:
                    when = f"{_DAYS_DE[day_idx]} um {entry['from']}"
                return f"Mike streamt {when} bis {entry['to']} Uhr {activity}".strip()

        return "Diese Woche ist kein weiterer Stream geplant."

    except Exception as e:
        log.warning("schedule_fetch_next_failed", error=str(e))
        return None


async def is_stream_scheduled_today(now_berlin: datetime | None = None) -> bool | None:
    """Prüft, ob HEUTE (Europe/Berlin) ein Stream im Wochenplan steht.

    Für die FAIL-CLOSED-Gate-Logik des proaktiven Samstags-DreamDuo-Pushes:

        True  = heute ist ein Stream geplant.
        False = Plan sicher geladen, heute KEIN Stream (streamfrei).
        None  = unbekannt (Fetch-Fehler / Plan nicht interpretierbar).

    Der Aufrufer behandelt None (und jede Exception) konservativ als „nicht pushen".
    """
    berlin = ZoneInfo("Europe/Berlin")
    now = now_berlin or datetime.now(berlin)
    today_idx = now.weekday()  # Mo=0 … So=6 — identisch zu day_of_week in der API

    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            resp = await client.get(_SCHEDULE_API_URL)
            resp.raise_for_status()
            data = resp.json()
    except Exception as e:  # noqa: BLE001
        log.warning("schedule_today_check_failed", error=str(e))
        return None

    days = data.get("days") if isinstance(data, dict) else None
    if not isinstance(days, list) or not days:
        # Kein/leerer Plan (oder malformed) → NICHT als „streamfrei" interpretieren.
        return None  # unbekannt → fail-closed beim Aufrufer

    # ALLE heutigen Tages-Einträge sammeln (nicht nur den ersten). day_of_week robust auf
    # int bringen, damit ein Schema-Drift (z.B. "5" statt 5) nicht fälschlich als „kein
    # Match → streamfrei" gilt. Bei doppelten Heute-Einträgen gewinnt „stream" (konservativ).
    today_statuses: list[str | None] = []
    for day in days:
        if not isinstance(day, dict):
            continue
        try:
            dow = int(day.get("day_of_week"))
        except (TypeError, ValueError):
            continue
        if dow == today_idx:
            raw_status = day.get("status")
            today_statuses.append(raw_status if isinstance(raw_status, str) else None)

    if not today_statuses:
        return None  # heute nicht im Plan → unbekannt (fail-closed)
    if any(status == "stream" for status in today_statuses):
        return True  # irgendein Heute-Eintrag ist Stream → konservativ kein Push
    if any(isinstance(status, str) and status.strip() for status in today_statuses):
        return False  # heute explizit kein Stream (z.B. "off") → sicher streamfrei
    # Status fehlt/unklar → unbekannt (fail-closed).
    return None


@dataclass(frozen=True)
class StreamToday:
    """Stream-Status für HEUTE (Berliner Kalendertag) fürs Morgen-Briefing (#119).

    kind: "stream" (times = "18:00–22:00, …", evtl. leer), "ausfall" (note = gesäubert, evtl. leer)
          oder "none" (heute explizit kein Stream, anderer Status).
    """

    kind: str
    times: str = ""
    note: str = ""


async def fetch_stream_today(now: datetime | None = None) -> StreamToday | None:
    """Stream-Status heute (Europe/Berlin) oder None = unbekannt (Fehler / leerer Plan / heute nicht im Plan).

    Eigene Funktion, damit is_stream_scheduled_today() und die übrigen Funktionen unverändert bleiben.
    """
    berlin = ZoneInfo("Europe/Berlin")
    today_idx = (now or datetime.now(berlin)).astimezone(berlin).weekday()  # Mo=0 … So=6

    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            resp = await client.get(_SCHEDULE_API_URL)
            resp.raise_for_status()
            data = resp.json()
    except Exception as e:  # noqa: BLE001
        log.warning("schedule_stream_today_failed", error=str(e))
        return None

    days = data.get("days") if isinstance(data, dict) else None
    if not isinstance(days, list) or not days:
        return None

    todays: list[dict] = []
    for day in days:
        if not isinstance(day, dict):
            continue
        try:
            dow = int(day.get("day_of_week"))
        except (TypeError, ValueError, OverflowError):
            continue
        if 0 <= dow <= 6 and dow == today_idx:
            todays.append(day)
    if not todays:
        return None

    # Bei doppelten Heute-Einträgen gewinnt "stream" (wie is_stream_scheduled_today)
    for day in todays:
        if day.get("status") == "stream":
            slots = []
            for e in day.get("entries") or []:
                if not isinstance(e, dict):
                    continue
                from_t, to_t = clean_note(e.get("from"), 5), clean_note(e.get("to"), 5)
                if from_t and to_t:  # keine Strings / leer → Slot überspringen statt "–"
                    slots.append(f"{from_t}–{to_t}")
            return StreamToday("stream", ", ".join(slots))
    for day in todays:
        if day.get("status") == "ausfall":
            return StreamToday("ausfall", note=clean_note(day.get("note")))
    return StreamToday("none")


async def fetch_schedule(for_today: bool = False) -> str | None:
    """Fetcht den aktuellen Streamplan von your-donna-instance.example.com/api/schedule.

    for_today: wenn True, gibt nur den heutigen Tag zurück.
    Returns: formatierter Text oder None bei Fehler.
    """
    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            resp = await client.get(_SCHEDULE_API_URL)
            resp.raise_for_status()
            data = resp.json()

        days = data.get("days", [])
        if not days:
            return "Kein Streamplan hinterlegt."

        if for_today:
            # Python weekday(): Montag=0, Sonntag=6 — gleich wie day_of_week in API
            today_idx = datetime.now().weekday()
            today_days = [d for d in days if d.get("day_of_week") == today_idx]
            if not today_days:
                return "Heute kein Stream geplant."
            day = today_days[0]
            if day.get("status") != "stream":
                return "Heute kein Stream geplant."
            entries = day.get("entries", [])
            if not entries:
                return f"Heute ({_DAYS_DE[today_idx]}) ist Stream geplant, aber keine genauen Zeiten hinterlegt."
            parts = []
            for e in entries:
                act = e.get("activity", "").strip()
                time_str = f"{e['from']}–{e['to']}"
                if act:
                    parts.append(f"{time_str} {act}")
                else:
                    parts.append(time_str)
            return f"Heute ({_DAYS_DE[today_idx]}): " + ", dann ".join(parts)

        # Ganzer Plan
        lines = []
        week_start = data.get("week_start", "")
        week_end = data.get("week_end", "")
        if week_start and week_end:
            lines.append(f"Streamplan ({week_start} – {week_end}):")
        for day in days:
            idx = day.get("day_of_week", 0)
            if idx > 6:
                continue
            day_name = _DAYS_DE[idx]
            if day.get("status") != "stream":
                lines.append(f"  {day_name}: kein Stream")
                continue
            entries = day.get("entries", [])
            if not entries:
                lines.append(f"  {day_name}: Stream (Zeiten offen)")
                continue
            parts = []
            for e in entries:
                act = e.get("activity", "").strip()
                time_str = f"{e['from']}–{e['to']}"
                parts.append(f"{time_str} {act}".strip())
            lines.append(f"  {day_name}: " + " | ".join(parts))

        return "\n".join(lines)

    except Exception as e:
        log.warning("schedule_fetch_failed", error=str(e), url=_SCHEDULE_API_URL)
        return None

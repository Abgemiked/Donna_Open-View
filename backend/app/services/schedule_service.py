"""
schedule_service.py — DONNA-17 Fix
Fetcht den Stream-Plan von your-donna-instance.example.com/api/schedule (JSON-API).
Wird vom Twitch-Bot für Schedule-Fragen genutzt.
"""
from __future__ import annotations
import time
import httpx
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
            parts.append(f"{short}: " + ", ".join(slot_parts))

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

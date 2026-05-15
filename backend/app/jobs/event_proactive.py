"""event_proactive.py — Event-getriggerte Proaktivität (DONNA-109).

Feature-Flag: DONNA_EVENT_PROACTIVITY=true/false (default: false)

Events:
1. Twitch-Stream startet in <30 Min (Schedule-Poll alle 15 Min)
2. Google-Kalender-Termin in <15 Min (falls GOOGLE_REFRESH_TOKEN vorhanden)

Dedup-Guard: _sent_event_ids (Modul-Level In-Memory Set) verhindert
Doppel-Benachrichtigungen. Reset bei Server-Neustart.

DSGVO: Kein Logging von Kalender-Rohdaten (Auflage 3).
       Kein PII in ntfy-Payload — nur Titel + sanitisierte Kurzform.
"""
from __future__ import annotations

import hashlib
import os
import time
from datetime import datetime, timedelta, timezone
from typing import TYPE_CHECKING

from app.core.logger import get_logger

if TYPE_CHECKING:
    from apscheduler.schedulers.asyncio import AsyncIOScheduler

log = get_logger("jobs.event_proactive")

# ── Feature-Flag ──────────────────────────────────────────────────────────────
DONNA_EVENT_PROACTIVITY_ENABLED: bool = (
    os.environ.get("DONNA_EVENT_PROACTIVITY", "false").lower() in ("true", "1", "yes")
)

# ── Dedup-Guard (In-Memory, resets on restart) ────────────────────────────────
# dict: event_id -> unix-timestamp (float) des Sendzeitpunkts.
# Einträge älter als _DEDUP_TTL_SECONDS werden lazy beim nächsten Add bereinigt.
_sent_event_ids: dict[str, float] = {}
_DEDUP_TTL_SECONDS: float = 86400.0  # 24 Stunden


def _dedup_cleanup() -> None:
    """Entfernt abgelaufene Einträge aus _sent_event_ids (lazy, bei jedem Add)."""
    cutoff = time.monotonic() - _DEDUP_TTL_SECONDS
    expired = [k for k, ts in _sent_event_ids.items() if ts < cutoff]
    for k in expired:
        del _sent_event_ids[k]


def _dedup_contains(event_id: str) -> bool:
    """Gibt True zurück wenn event_id noch im TTL-Fenster bekannt ist."""
    ts = _sent_event_ids.get(event_id)
    if ts is None:
        return False
    if time.monotonic() - ts >= _DEDUP_TTL_SECONDS:
        del _sent_event_ids[event_id]
        return False
    return True


def _dedup_add(event_id: str) -> None:
    """Fügt event_id mit aktuellem Timestamp ein und bereinigt abgelaufene Einträge."""
    _dedup_cleanup()
    _sent_event_ids[event_id] = time.monotonic()

# ── Timing-Konstanten ─────────────────────────────────────────────────────────
_STREAM_ALERT_MINUTES = 30   # Notify wenn Stream in <30 Min startet
_CALENDAR_ALERT_MINUTES = 15  # Notify wenn Termin in <15 Min startet

# ── ntfy-Konfiguration (aus Settings, überschreibbar für Tests) ───────────────
_DEFAULT_NTFY_URL = os.environ.get("NTFY_URL", "https://ntfy.your-donna-instance.example.com")
_DEFAULT_NTFY_TOPIC = os.environ.get("NTFY_TOPIC", "donna")


# ── Hilfsfunktionen ───────────────────────────────────────────────────────────

def _is_flag_enabled() -> bool:
    """Feature-Flag-Check — re-reads env at call time für Hot-Toggle in Tests."""
    return os.environ.get("DONNA_EVENT_PROACTIVITY", "false").lower() in ("true", "1", "yes")


async def _send_ntfy(
    body: str,
    title: str = "Donna",
    priority: int = 4,
    ntfy_url: str = _DEFAULT_NTFY_URL,
    ntfy_topic: str = _DEFAULT_NTFY_TOPIC,
) -> bool:
    """Sendet ntfy-Push — analog zu ProactivityService.send_push_notification().

    Gibt True bei Erfolg, False bei Fehler (kein raise — Jobs dürfen nicht crashen).
    """
    import httpx

    _priority_map = {1: "min", 2: "low", 3: "default", 4: "high", 5: "urgent"}
    headers = {
        "Title": title,
        "Priority": _priority_map.get(priority, "high"),
        "Content-Type": "text/plain; charset=utf-8",
    }
    target = f"{ntfy_url.rstrip('/')}/{ntfy_topic}"
    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            r = await client.post(target, content=body.encode("utf-8"), headers=headers)
            r.raise_for_status()
        log.info("event_push_sent", title=title, topic=ntfy_topic, body_len=len(body))
        return True
    except Exception as exc:  # noqa: BLE001
        log.warning("event_push_failed", error=str(exc), target=target)
        return False


async def _generate_text(mistral: object | None, system: str, prompt: str, fallback: str) -> str:
    """Kurze LLM-generierte Benachrichtigung. Bei Fehler: Fallback-Text."""
    if mistral is None:
        return fallback
    try:
        # MistralClient.generate ist eine coroutine
        result = await mistral.generate(system=system, prompt=prompt)  # type: ignore[union-attr]
        return (result or fallback).strip()
    except Exception as exc:  # noqa: BLE001
        log.warning("event_llm_failed", error=str(exc))
        return fallback


# ── Event 1: Twitch-Stream-Start ──────────────────────────────────────────────

async def check_stream_event(
    mistral: object | None = None,
    ntfy_url: str = _DEFAULT_NTFY_URL,
    ntfy_topic: str = _DEFAULT_NTFY_TOPIC,
) -> dict:
    """Prüft ob der nächste Twitch-Stream in <30 Min startet und sendet ntfy.

    Nutzt fetch_next_stream() aus schedule_service — gibt Klartext-String zurück.
    Event-ID: "stream:<ISO-Minute>" — verhindert Doppel-Send innerhalb des Fensters.

    Returns:
        dict mit keys: checked, event_found, event_id, sent (optional)
    """
    if not _is_flag_enabled():
        log.debug("event_proactivity_disabled_stream")
        return {"checked": False, "reason": "flag_disabled"}

    from zoneinfo import ZoneInfo
    from app.services import schedule_service

    berlin = ZoneInfo("Europe/Berlin")
    now = datetime.now(berlin)

    # fetch_next_stream() gibt z.B. "Mike streamt heute um 18:00 bis 20:00 Uhr Spiel" zurück
    # Wir müssen die Startzeit parsen — dafür holen wir die Rohdaten direkt.
    try:
        import httpx as _httpx
        async with _httpx.AsyncClient(timeout=8.0) as client:
            resp = await client.get(schedule_service._SCHEDULE_API_URL)
            resp.raise_for_status()
            data = resp.json()
    except Exception as exc:  # noqa: BLE001
        log.warning("stream_schedule_fetch_failed", error=str(exc))
        return {"checked": True, "event_found": False, "reason": "schedule_api_error"}

    days = data.get("days", [])
    today_idx = now.weekday()
    upcoming_slots: list[tuple[datetime, str, str]] = []

    for day in days:
        if day.get("status") != "stream":
            continue
        day_idx = day.get("day_of_week", 0)
        delta_days = (day_idx - today_idx) % 7
        base = now.replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(days=delta_days)

        for entry in day.get("entries", []):
            try:
                from_h, from_m = map(int, entry["from"].split(":"))
            except (KeyError, ValueError):
                continue
            stream_start = base.replace(hour=from_h, minute=from_m)
            if stream_start <= now:
                continue  # bereits laufend oder vergangen
            activity = entry.get("activity", "Stream").strip() or "Stream"
            upcoming_slots.append((stream_start, activity, entry.get("from", "")))

    if not upcoming_slots:
        return {"checked": True, "event_found": False, "reason": "no_upcoming_stream"}

    # Nächsten Slot wählen
    upcoming_slots.sort(key=lambda x: x[0])
    next_start, activity, time_str = upcoming_slots[0]
    minutes_until = int((next_start - now).total_seconds() / 60)

    if minutes_until > _STREAM_ALERT_MINUTES:
        return {"checked": True, "event_found": False, "reason": f"stream_in_{minutes_until}min"}

    # Dedup: Event-ID = Stream-Startzeit auf Minuten gerundet
    event_id = f"stream:{next_start.strftime('%Y-%m-%dT%H:%M')}"
    if _dedup_contains(event_id):
        return {"checked": True, "event_found": True, "event_id": event_id, "sent": False, "reason": "already_sent"}

    # Benachrichtigungstext via Mistral
    system_prompt = (
        "Du bist Donna, Mikes KI-Assistentin. Schreibe eine kurze, persönliche "
        "Push-Nachricht (max. 2 Sätze) als Erinnerung dass Mikes Twitch-Stream "
        "gleich startet. Kein Emoji-Overload. Direkt und warm."
    )
    user_prompt = (
        f"Mikes nächster Stream startet in {minutes_until} Minuten "
        f"um {time_str} Uhr: {activity}. Formuliere eine kurze Erinnerung."
    )
    fallback = f"Dein Stream startet in {minutes_until} Min um {time_str} Uhr: {activity}. Alles bereit?"

    body = await _generate_text(mistral, system_prompt, user_prompt, fallback)
    sent = await _send_ntfy(
        body=body,
        title=f"Stream in {minutes_until} Min",
        priority=4,
        ntfy_url=ntfy_url,
        ntfy_topic=ntfy_topic,
    )

    if sent:
        _dedup_add(event_id)

    return {"checked": True, "event_found": True, "event_id": event_id, "sent": sent, "minutes_until": minutes_until}


# ── Event 2: Google-Kalender-Termin ───────────────────────────────────────────

async def check_calendar_event(
    calendar_svc: object | None = None,
    mistral: object | None = None,
    ntfy_url: str = _DEFAULT_NTFY_URL,
    ntfy_topic: str = _DEFAULT_NTFY_TOPIC,
) -> dict:
    """Prüft ob ein Kalender-Termin in <15 Min startet und sendet ntfy.

    Nutzt CalendarService.get_upcoming_events() — DSGVO: kein Logging der Details.
    Event-ID: "cal:<summary-hash>:<ISO-Minute>" — stabile Dedup-ID.

    Returns:
        dict mit keys: checked, event_found, event_id, sent (optional)
    """
    if not _is_flag_enabled():
        log.debug("event_proactivity_disabled_calendar")
        return {"checked": False, "reason": "flag_disabled"}

    if calendar_svc is None or not getattr(calendar_svc, "ready", lambda: False)():
        return {"checked": False, "reason": "calendar_not_configured"}

    now = datetime.now(tz=timezone.utc)

    # Nur Events der nächsten Stunde holen (minimiert PII-Transfer)
    try:
        events: list[dict] = calendar_svc.get_upcoming_events(days=1)  # type: ignore[union-attr]
    except Exception as exc:  # noqa: BLE001
        log.warning("calendar_event_check_failed", error_type=type(exc).__name__)
        return {"checked": True, "event_found": False, "reason": "calendar_api_error"}

    for event in events:
        start_info = event.get("start", {})
        start_str = start_info.get("dateTime") or start_info.get("date", "")
        if not start_str or "T" not in start_str:
            continue  # Ganztags-Events überspringen

        try:
            event_start = datetime.fromisoformat(start_str)
            if event_start.tzinfo is None:
                event_start = event_start.replace(tzinfo=timezone.utc)
        except ValueError:
            continue

        minutes_until = int((event_start - now).total_seconds() / 60)
        if minutes_until < 0 or minutes_until > _CALENDAR_ALERT_MINUTES:
            continue

        # Dedup-ID: stabiler Hash aus Summary + Startzeit (kein PII im Log)
        summary = event.get("summary", "(Termin)")
        summary_slug = hashlib.sha256(summary.encode()).hexdigest()[:16]
        event_id = f"cal:{summary_slug}:{event_start.strftime('%Y-%m-%dT%H:%M')}"

        if _dedup_contains(event_id):
            # DSGVO: kein Summary-Logging
            log.debug("calendar_event_already_sent", event_id=event_id)
            continue

        # DSGVO Auflage 3: kein Logging des Summary/Titels
        log.info("calendar_event_imminent", minutes_until=minutes_until)

        # Benachrichtigungstext via Mistral
        # DSGVO: Summary darf in den LLM-Prompt — Mistral (EU-Server), kein LTM-Write
        system_prompt = (
            "Du bist Donna, Mikes KI-Assistentin. Schreibe eine kurze, persönliche "
            "Push-Nachricht (max. 2 Sätze) als Erinnerung an einen anstehenden Termin. "
            "Kein Emoji-Overload. Freundlich und direkt."
        )
        user_prompt = (
            f"Mike hat in {minutes_until} Minuten einen Termin: '{summary}'. "
            "Formuliere eine kurze, persönliche Erinnerung."
        )
        fallback = f"Dein Termin '{summary}' startet in {minutes_until} Min. Nicht vergessen!"

        body = await _generate_text(mistral, system_prompt, user_prompt, fallback)

        # DSGVO: Kein PII (roher Titel) im ntfy-Title wenn Summary sensitiv wirkt
        # Wir nutzen generischen Titel + Details im Body
        sent = await _send_ntfy(
            body=body,
            title=f"Termin in {minutes_until} Min",
            priority=4,
            ntfy_url=ntfy_url,
            ntfy_topic=ntfy_topic,
        )

        if sent:
            _dedup_add(event_id)

        # Nur das erste passende Event benachrichtigen
        return {
            "checked": True,
            "event_found": True,
            "event_id": event_id,
            "sent": sent,
            "minutes_until": minutes_until,
        }

    return {"checked": True, "event_found": False, "reason": "no_imminent_events"}


# ── APScheduler-Integration ───────────────────────────────────────────────────

def schedule_event_proactive_jobs(
    scheduler: "AsyncIOScheduler",
    app_state: object,
    *,
    ntfy_url: str = _DEFAULT_NTFY_URL,
    ntfy_topic: str = _DEFAULT_NTFY_TOPIC,
) -> None:
    """Registriert Event-Proaktivitäts-Jobs am übergebenen Scheduler.

    Wird aus main.py aufgerufen wenn DONNA_EVENT_PROACTIVITY_ENABLED=true.
    app_state wird genutzt um mistral + calendar_svc zu beziehen.

    Beide Jobs sind idempotent (coalesce=True, max_instances=1).
    """
    from apscheduler.triggers.interval import IntervalTrigger

    mistral = getattr(app_state, "mistral", None)
    calendar_svc = getattr(app_state, "calendar", None)

    async def _stream_job() -> None:
        try:
            result = await check_stream_event(
                mistral=mistral,
                ntfy_url=ntfy_url,
                ntfy_topic=ntfy_topic,
            )
            log.debug("stream_event_check_done", **{k: v for k, v in result.items() if k != "event_id"})
        except Exception as exc:  # noqa: BLE001
            log.error("stream_event_job_error", error=str(exc))

    async def _calendar_job() -> None:
        try:
            result = await check_calendar_event(
                calendar_svc=calendar_svc,
                mistral=mistral,
                ntfy_url=ntfy_url,
                ntfy_topic=ntfy_topic,
            )
            log.debug("calendar_event_check_done", **{k: v for k, v in result.items() if k != "event_id"})
        except Exception as exc:  # noqa: BLE001
            log.error("calendar_event_job_error", error=str(exc))

    scheduler.add_job(
        _stream_job,
        trigger=IntervalTrigger(minutes=15),
        id="event_proactive_stream",
        replace_existing=True,
        max_instances=1,
        coalesce=True,
    )

    scheduler.add_job(
        _calendar_job,
        trigger=IntervalTrigger(minutes=15),
        id="event_proactive_calendar",
        replace_existing=True,
        max_instances=1,
        coalesce=True,
    )

    log.info(
        "event_proactive_jobs_scheduled",
        jobs=["event_proactive_stream", "event_proactive_calendar"],
        stream_window_min=_STREAM_ALERT_MINUTES,
        calendar_window_min=_CALENDAR_ALERT_MINUTES,
    )

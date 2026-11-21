"""Shisha-Feature TEIL B: proaktive "Kohle fertig"-Meldung.

Pollt ``timer.shishatimer`` (Home Assistant) und erkennt den Übergang
``active → finished`` (ECHTER Ablauf — NICHT manueller Abbruch/Pause). Bei
echtem Ablauf: genau EIN proaktiver Push "Deine Shishakohle ist fertig 🔥"
plus STM-Write unter der stabilen Session ``proactive_shisha`` (damit ein Tap
auf die Push den Thread lädt).

Abgrenzung / Guardrails:
- REIN LESEND (``read_timers``) + Push. KEINE HA-Writes (Teil A schaltet
  ``script.shishakohle`` über den bestehenden Chat-Aktionspfad — nicht hier).
- Fire-and-forget: HA-/Push-/STM-Fehler werfen nie, sie werden nur geloggt.
- Watcher nur aktiv wenn HA enabled (Gate in ``check_and_notify`` + in main.py).
- KEIN Proaktivitäts-Level-Gate: Mike startet den Timer selbst, die "fertig"-
  Meldung ist angefordert, nicht unaufgefordert.

Transition-Logik (korrekte Diskriminierung Ablauf vs. Abbruch):
Der ``finishes_at`` wird beim ``active``-Poll gemerkt (``_last_finishes_at``), da
ein idle-Timer kein ``finishes_at`` trägt. Beim Übergang ``active → idle`` gilt es
nur als "fertig", wenn ``now >= finishes_at`` (ein manueller Abbruch passiert,
während ``finishes_at`` noch in der Zukunft liegt). ``paused`` wird nie als Finish
gewertet. Dedup über die Timer-Identität ``finishes_at`` — ein NEUER Lauf (neuer
``finishes_at``) löst wieder einen Push aus.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Optional

from app.core.logger import get_logger
from app.services.proactive_session import PROACTIVE_SESSION_SHISHA, sid_tag

log = get_logger("jobs.shisha_timer_watcher")

SHISHA_TIMER_ENTITY = "timer.shishatimer"
_PUSH_BODY = "Deine Shishakohle ist fertig 🔥"
_PUSH_TITLE = "Shisha 🔥"

# Clock-Skew-Toleranz: Ein echter Ablauf wird ohnehin erst erkannt, wenn HA den
# Timer bereits auf idle gesetzt hat (also nach finishes_at). Ein paar Sekunden
# Toleranz fangen minimale Uhren-Drift zwischen HA-Host und Backend ab, ohne einen
# echten Abbruch (finishes_at liegt Minuten in der Zukunft) falsch als Finish zu werten.
_FINISH_GRACE_S = 5

# Modul-State (Transition + Dedup).
_last_state: Optional[str] = None          # zuletzt gesehener Timer-Zustand
_last_finishes_at: Optional[str] = None    # finishes_at, gemerkt WÄHREND active
_pushed_finishes_at: Optional[str] = None  # Dedup-Identität: Lauf, für den schon gepusht wurde


def _parse_iso_utc(value) -> Optional[datetime]:
    """Parst ISO-UTC (``finishes_at``) robust nach aware-UTC. None bei Unparsbarem."""
    if not value or not isinstance(value, str):
        return None
    try:
        dt = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    except Exception:  # noqa: BLE001
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


async def check_and_notify(
    *,
    ha_service=None,
    proactivity_svc=None,
    stm=None,
    now_utc: Optional[datetime] = None,
    entity_id: str = SHISHA_TIMER_ENTITY,
    ntfy_url: Optional[str] = None,
    ntfy_topic: Optional[str] = None,
) -> None:
    """Haupt-Job: pollt den Shisha-Timer, erkennt echten Ablauf, pusht 1x.

    Wird per APScheduler (IntervalTrigger) aufgerufen. Kein Crash bei Fehlern
    (fire-and-forget). ``now_utc`` ist für Tests injizierbar.
    """
    global _last_state, _last_finishes_at, _pushed_finishes_at  # noqa: PLW0603

    # HA-Gate: Watcher nur aktiv wenn HA enabled.
    if ha_service is None or not getattr(ha_service, "enabled", False):
        return

    # Timer-Zustand frisch lesen — rein lesend, fail-safe (kein Crash).
    try:
        timers = await ha_service.read_timers()
    except Exception as exc:  # noqa: BLE001
        log.warning("shisha_watcher_read_failed", error=str(exc))
        return

    timer = None
    for t in timers or []:
        if isinstance(t, dict) and t.get("entity_id") == entity_id:
            timer = t
            break
    if timer is None:
        # Timer existiert (gerade) nicht — nicht bewertbar, State unberührt lassen.
        log.debug("shisha_watcher_timer_absent", entity_id=entity_id)
        return

    cur_state = timer.get("state")
    cur_fin = timer.get("finishes_at")
    now = now_utc or datetime.now(timezone.utc)

    # Erster Lauf: nur Zustand merken, NICHT pushen (verhindert Doppel-Push bei
    # Neustart während laufendem Timer).
    if _last_state is None:
        _last_state = cur_state
        _last_finishes_at = cur_fin if cur_state == "active" else None
        log.info("shisha_watcher_initialized", state=cur_state)
        return

    # Finish-Erkennung: active → idle.
    finished = False
    if _last_state == "active" and cur_state == "idle":
        fin_dt = _parse_iso_utc(_last_finishes_at)
        if fin_dt is not None and now >= (fin_dt - timedelta(seconds=_FINISH_GRACE_S)):
            # Echter Ablauf — nur feuern, wenn für diesen Lauf noch nicht gepusht.
            if _pushed_finishes_at != _last_finishes_at:
                finished = True
        else:
            # now < finishes_at (oder finishes_at fehlt) → manueller Abbruch → KEIN Push.
            log.info("shisha_watcher_cancelled", reason="idle_before_finish")

    # Identität des gerade (ggf.) ablaufenden Laufs, bevor der State aktualisiert wird.
    run_identity = _last_finishes_at

    # State aktualisieren (immer).
    _last_state = cur_state
    if cur_state == "active":
        _last_finishes_at = cur_fin
    # bei idle/paused bleibt _last_finishes_at der aktive Wert (schon ausgewertet)
    # und wird beim nächsten active-Poll überschrieben.

    if not finished:
        return

    # Push senden (fire-and-forget).
    if proactivity_svc is None:
        log.warning("shisha_watcher_no_proactivity")
        return

    push_kwargs = {
        "body": _PUSH_BODY,
        "title": _PUSH_TITLE,
        "priority": 4,
        "tags": [sid_tag(PROACTIVE_SESSION_SHISHA)],
    }
    if ntfy_url is not None:
        push_kwargs["ntfy_url"] = ntfy_url
    if ntfy_topic is not None:
        push_kwargs["ntfy_topic"] = ntfy_topic

    try:
        sent = await proactivity_svc.send_push_notification(**push_kwargs)
    except Exception as exc:  # noqa: BLE001
        log.warning("shisha_watcher_push_failed", error=str(exc))
        return

    if not sent:
        # ntfy down o.ä.: Dedup NICHT markieren. Der nächste Poll ist aber idle→idle
        # (keine Transition), daher kein Auto-Retry — der verlorene Push wird nur geloggt.
        log.warning("shisha_watcher_push_not_sent")
        return

    # Dedup markieren + STM-Write (damit ein Tap den Thread lädt).
    _pushed_finishes_at = run_identity
    log.info("shisha_watcher_pushed", entity_id=entity_id)
    if stm is not None:
        try:
            await stm.add_message(PROACTIVE_SESSION_SHISHA, "assistant", _PUSH_BODY)
        except Exception as exc:  # noqa: BLE001
            log.warning("shisha_watcher_stm_write_failed", error=str(exc))


def reset_state_for_testing() -> None:
    """Setzt den Modul-State zurück — nur für Unit-Tests."""
    global _last_state, _last_finishes_at, _pushed_finishes_at  # noqa: PLW0603
    _last_state = None
    _last_finishes_at = None
    _pushed_finishes_at = None

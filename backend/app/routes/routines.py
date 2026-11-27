"""routines.py — Routinen (#147 G2): Liste, Bestaetigung eines Vorschlags, Loeschen aus den Einstellungen.

Gespeichert wird NUR hier und NUR nach Mikes Tipp in der App (Bestaetigungskarte). Das Modell hat keinen Weg hierher:
es legt ueber seine Werkzeuge nur einen ausstehenden Vorschlag an. Ausgefuehrt wird nur ueber ``POST /{id}/run``
(#244: Routinen-Widget, erst nach Mikes "Ja" im Bestaetigungsfenster) — mit DERSELBEN Lauflogik wie im Chat.
Auth: Bearer-Token (wie die uebrigen App-Routen)."""
from __future__ import annotations

import asyncio
import time
from collections import deque
from types import SimpleNamespace
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Path, Request
from pydantic import BaseModel, Field, StringConstraints

from app.core.auth import require_admin
from app.core.logger import get_logger
from app.services import routines as _routines

log = get_logger("route.routines")

router = APIRouter(prefix="/routines", tags=["routines"])

_PendingId = Annotated[str, Path(pattern=_routines.PENDING_ID_RE.pattern, max_length=32)]
_Id = Annotated[int, Path(ge=1, le=2**31 - 1)]


def _store(request: Request) -> _routines.RoutineStore:
    store = getattr(request.app.state, "routines", None)
    if store is None:
        raise HTTPException(status_code=503, detail="routine_service_unavailable")
    return store


def _home_ok(request: Request):
    ha = getattr(request.app.state, "ha", None)

    def _ok(domain: str, service: str, entity_id: str) -> bool:
        allowlist = getattr(ha, "_allowlist", None)
        if allowlist is None or not getattr(ha, "enabled", False):
            return False
        try:
            allowlist.check(domain, service, entity_id)
            return True
        except Exception:  # noqa: BLE001 — jede Ablehnung = gesperrt
            return False

    return _ok


@router.get("")
async def list_routines(request: Request, _admin: str = Depends(require_admin)) -> dict:
    store = _store(request)
    items = await asyncio.to_thread(store.list)
    log_rows = await asyncio.to_thread(store.changelog)
    return {
        "max_routines": _routines.MAX_ROUTINES,
        "max_steps": _routines.MAX_STEPS,
        "routines": [{"id": r["id"], "name": r["name"], "updated": r["updated"],
                      "steps": [_routines.step_label(s) for s in r["steps"]]} for r in items],
        "changelog": log_rows,
    }


@router.get("/offline")
async def offline_snapshot(request: Request, _admin: str = Depends(require_admin)) -> dict:
    """#147 Paket D: Routinen fuer das Handy, wenn der Server nicht erreichbar ist (lokaler, zuletzt synchronisierter Stand)."""
    items = await asyncio.to_thread(_store(request).list)
    return {"routines": _routines.offline_snapshot(items)}


@router.post("/pending/{pending_id}/confirm")
async def confirm_pending(pending_id: _PendingId, request: Request, _admin: str = Depends(require_admin)) -> dict:
    """Mikes Tipp auf die Bestaetigungskarte: den ausstehenden Vorschlag EINMALIG anwenden."""
    try:
        res = await asyncio.to_thread(_store(request).confirm, pending_id, _home_ok(request))
    except _routines.RoutineError as exc:
        status = 404 if exc.code == "pending_unknown" else 409
        raise HTTPException(status_code=status, detail={"code": exc.code, "message": exc.message}) from exc
    return {"ok": True, **res}


@router.post("/pending/{pending_id}/reject")
async def reject_pending(pending_id: _PendingId, request: Request, _admin: str = Depends(require_admin)) -> dict:
    ok = await asyncio.to_thread(_store(request).reject, pending_id)
    return {"ok": ok}


@router.delete("/{routine_id}")
async def delete_routine(routine_id: _Id, request: Request, _admin: str = Depends(require_admin)) -> dict:
    """Loeschen aus den Einstellungen (Mikes eigener Tipp mit Rueckfrage in der App)."""
    if not await asyncio.to_thread(_store(request).delete_by_id, routine_id):
        raise HTTPException(status_code=404, detail="routine_not_found")
    return {"ok": True}


# ── #244: Routine aus dem Widget ausfuehren ─────────────────────────────────────────────────────────────────────

_RUN_MAX_PER_WINDOW = 6          # hoechstens 6 Laeufe je Minute (Widget-Knoepfe, alle Routinen zusammen)
_RUN_WINDOW_SEC = 60.0
_RUN_SAME_GAP_SEC = 3.0          # dieselbe Routine nicht doppelt (Doppeltipp)
_run_stamps: deque[tuple[float, int]] = deque()
_run_lock = asyncio.Lock()


class RunBody(BaseModel):
    """``confirmed`` muss True sein (Mikes "Ja" im Bestaetigungsfenster). ``device_actions`` = in der App
    eingeschaltete Aktionsarten (Schalter je Art); Unbekanntes faellt in ``parse_enabled_kinds`` weg."""
    confirmed: bool = False
    device_actions: list[Annotated[str, StringConstraints(max_length=32)]] = Field(default_factory=list, max_length=32)


def _rate_check(routine_id: int, now: float) -> str | None:
    while _run_stamps and now - _run_stamps[0][0] > _RUN_WINDOW_SEC:
        _run_stamps.popleft()
    if any(rid == routine_id and now - t < _RUN_SAME_GAP_SEC for t, rid in _run_stamps):
        return "routine_run_duplicate"
    if len(_run_stamps) >= _RUN_MAX_PER_WINDOW:
        return "routine_run_rate_limited"
    _run_stamps.append((now, routine_id))
    return None


@router.post("/{routine_id}/run")
async def run_routine(routine_id: _Id, body: RunBody, request: Request, _admin: str = Depends(require_admin)) -> dict:
    """#244: Routine aus dem Widget starten (nach Mikes "Ja"). Nutzt ``chat._run_routine`` unveraendert:
    Smart-Home-Schritte laufen hier ueber dieselben Sperren (HA-Allowlist, Live-Sperre per Twitch-Pruefung,
    Mengenlimit), Geraete-Schritte werden mit dem Schalter je Aktionsart NEU validiert (Positivliste) und als
    ``device`` an die App zurueckgegeben, die sie ein zweites Mal prueft und ausfuehrt. Keine Werte aus Text.
    Der Widget-Weg gilt als Mikes direkte App-Eingabe (Admin-Token + ausdrueckliche Bestaetigung) — wie der
    Chat-Pfad der Android-App; Smart-Home-Schritte pruefen die Live-Sperre trotzdem selbst (Twitch, fail-closed).
    Ohne ``action_id``: Sammel-Ergebnis/"Alles rueckgaengig" gibt es auf diesem Weg bewusst nicht.
    Das Mengenlimit gilt je Prozess (ein Worker)."""
    if not body.confirmed:
        raise HTTPException(status_code=400, detail="confirmation_required")
    store = _store(request)
    routine = await asyncio.to_thread(store.get, routine_id)
    if routine is None:
        raise HTTPException(status_code=404, detail="routine_not_found")
    async with _run_lock:
        reason = _rate_check(routine_id, time.monotonic())
    if reason is not None:
        raise HTTPException(status_code=429, detail=reason)

    from app.config import get_settings
    from app.routes import chat as _chat   # spaet importiert: chat.py ist gross, kein Import-Zyklus

    payload = SimpleNamespace(client="android", input_mode="text", device_actions=list(body.device_actions),
                              device_action_results=False)
    box: dict = {"out": [], "hc": []}
    text = await _chat._run_routine(
        store=store, name=routine["name"], payload=payload, box=box, injection_detected=False, is_live=False,
        live_silent=False, foreign_content=False, live_guard_active=lambda: False,
        ha_service=getattr(request.app.state, "ha", None), settings=get_settings(), results_store=None)
    device = [a for a in box["out"] if isinstance(a, dict) and a.get("type") == "device_action"]
    home = box["hc"]
    log.info("routine_widget_run", device=len(device), home=len(home), home_ok=sum(1 for o in home if o.get("ok")))
    home_ok = sum(1 for o in home if o.get("ok"))
    return {"ok": True, "started": bool(device) or home_ok > 0, "name": routine["name"], "text": text,
            "device": device, "home_total": len(home), "home_ok": home_ok}

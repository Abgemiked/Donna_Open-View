"""device_actions.py — Rueckmeldung der App zu Geraete-Aktionen (#183, Refs #147).

Die App meldet nach der Ausfuehrung nur einen Ergebniscode (+ Ursache aus fester Liste). Die Route aendert
nur den Zustand der Meldung — sie fuehrt nichts aus und nimmt keine Inhalte an (extra Felder verboten).
Auth: Bearer-Token wie die uebrigen App-Routen. Rate-Limit im Speicher (429)."""
from __future__ import annotations

from typing import Annotated, Literal

from fastapi import APIRouter, Depends, HTTPException, Path, Request
from pydantic import BaseModel, ConfigDict, Field

from app.core.auth import require_admin
from app.services.device_action_results import ACTION_ID_RE, GROUP_ID_RE, DeviceActionResultStore

router = APIRouter(prefix="/device-actions", tags=["device-actions"])

_ActionId = Annotated[str, Path(pattern=ACTION_ID_RE.pattern, max_length=32)]  # pydantic (Rust-Regex): $ = strenges Ende


class ResultIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    result: Literal["ok", "braucht_berechtigung", "nicht_unterstuetzt", "fehler"]
    reason: str | None = Field(default=None, max_length=32)   # unbekannt -> Speicher macht "sonstige" (kein Freitext gespeichert)


def _store(request: Request) -> DeviceActionResultStore:
    store = getattr(request.app.state, "device_action_results", None)
    if store is None:
        raise HTTPException(status_code=503, detail="device_action_results_unavailable")
    return store


@router.post("/{action_id}/result")
async def post_result(action_id: _ActionId, body: ResultIn, request: Request,
                      _admin: str = Depends(require_admin)) -> dict:
    outcome = _store(request).report(action_id, body.result, body.reason)
    if outcome == "unknown":
        raise HTTPException(status_code=404, detail="action_not_found")
    if outcome == "conflict":
        raise HTTPException(status_code=409, detail="result_already_reported")
    if outcome == "rate_limited":
        raise HTTPException(status_code=429, detail="rate_limited")
    if outcome == "invalid":   # durch die Schemata schon abgefangen; Sicherheitsnetz
        raise HTTPException(status_code=422, detail="invalid")
    return {"action_id": action_id, "status": body.result}


@router.get("/{action_id}")
async def get_result(action_id: _ActionId, request: Request, _admin: str = Depends(require_admin)) -> dict:
    entry = _store(request).get(action_id)
    if entry is None:
        raise HTTPException(status_code=404, detail="action_not_found")
    return entry


_GroupId = Annotated[str, Path(pattern=GROUP_ID_RE.pattern, max_length=32)]


@router.get("/groups/{group_id}")
async def get_group(group_id: _GroupId, request: Request, _admin: str = Depends(require_admin)) -> dict:
    """#186: Stand einer Sammelaktion (Zeile je Schritt) fuer die Ergebnis-Sammelkarte."""
    summary = _store(request).group_summary(group_id)
    if summary is None:
        raise HTTPException(status_code=404, detail="group_not_found")
    return summary

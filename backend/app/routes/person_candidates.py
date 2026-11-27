"""person_candidates.py — Vorschlaege zu Personen-/Termin-Mustern (#182, Phase 4b). Nur Mike (Admin).

GET  /person-candidates                 offene Vorschlaege (frisch aus dem Kalender, nichts gespeichert)
POST /person-candidates/{id}/confirm    ausdrueckliche Bestaetigung -> Fakt wird gespeichert (user_fact)
POST /person-candidates/{id}/reject     ablehnen -> nie wieder vorgeschlagen (nur Hash + Status)
"""
from __future__ import annotations

import asyncio

from fastapi import APIRouter, Depends, HTTPException, Request

from app.core.auth import require_admin
from app.core.logger import get_logger
from app.services.person_patterns import CalendarUnavailable

log = get_logger("route.person_candidates")
router = APIRouter(prefix="/person-candidates", tags=["person_candidates"])


def _svc(request: Request):
    svc = getattr(request.app.state, "person_candidates", None)
    if svc is None:
        raise HTTPException(status_code=503, detail="Personen-Vorschlaege nicht verfuegbar.")
    return svc


@router.get("")
async def list_candidates(request: Request, _admin: str = Depends(require_admin)) -> dict:
    try:
        items = await asyncio.to_thread(_svc(request).pending_strict)
    except CalendarUnavailable:
        raise HTTPException(status_code=503, detail="Kalender gerade nicht verfuegbar.")
    log.info("person_candidates_listed", count=len(items))            # nur Zahl, keine Namen
    # frage = Rueckfrage an Mike; wird_gespeichert = exakt der Text, der nach Bestaetigung im LTM steht
    return {"candidates": [{"id": c["id"], "kind": c["kind"], "frage": c["frage"], "wird_gespeichert": c["text"]} for c in items],
            "count": len(items)}


@router.post("/{cid}/confirm")
async def confirm(cid: str, request: Request, _admin: str = Depends(require_admin)) -> dict:
    result = await asyncio.to_thread(_svc(request).confirm, cid)
    if result == "unavailable":
        raise HTTPException(status_code=503, detail="Kalender gerade nicht verfuegbar; nichts vermerkt.")
    if result == "not_found":
        raise HTTPException(status_code=404, detail="Vorschlag nicht (mehr) vorhanden.")
    if result == "failed":
        raise HTTPException(status_code=502, detail="Speichern nicht verifizierbar; nichts vermerkt.")
    log.info("person_candidate_confirmed", result=result)
    return {"id": cid, "status": "confirmed", "result": result}


@router.post("/{cid}/reject")
async def reject(cid: str, request: Request, _admin: str = Depends(require_admin)) -> dict:
    result = await asyncio.to_thread(_svc(request).reject, cid)
    if result == "conflict":
        raise HTTPException(status_code=409, detail="Schon bestaetigt.")
    return {"id": cid, "status": "rejected"}

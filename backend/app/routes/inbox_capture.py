"""inbox_capture.py — POST /inbox/capture: Idee vom Homescreen-Widget als Issue in die donna-inbox (#233).

Mikes Tipp auf "Speichern" in der Vorschau der App IST die Bestaetigung; die Route legt genau EIN Issue an
(Label `from-donna` + `projekt:<slug>`), kein Gedaechtnis-Eintrag. Logik/Idempotenz/Rate-Limit:
app/services/inbox_capture.py. GitHub-Zugang: derselbe GithubInboxService wie Donnas Issue-Loop (Token nie geloggt).

Antworten (Vertrag: backend/tests/contracts/inbox_capture_contract.json):
  200 {number, url, duplicate}  angelegt (duplicate=True: Nonce war schon angelegt, nichts Neues)
  401 / 422                     Auth fehlt / Eingabe ungueltig (Projekt nicht in der Liste, Text leer, Nonce falsch)
  409 capture_in_progress       dieselbe Nonce laeuft gerade — spaeter erneut
  429 rate_limited              zu viele Anlagen in kurzer Zeit — spaeter erneut
  502 github_failed             GitHub hat nicht angelegt — spaeter erneut (Nonce wieder frei)
  503 inbox_unavailable         Server ohne GitHub-Token/Repo
"""
from __future__ import annotations

from typing import Annotated, Literal

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field, StringConstraints

from app.core.auth import require_admin
from app.core.logger import get_logger
from app.services import inbox_capture as ic

log = get_logger("route.inbox_capture")

router = APIRouter(prefix="/inbox", tags=["inbox"])


class CaptureIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    text: str = Field(min_length=1, max_length=ic.TEXT_MAX)
    projekt: str = Field(default=ic.DEFAULT_PROJEKT, max_length=40)
    nonce: Annotated[str, StringConstraints(pattern=ic.NONCE_RE.pattern)]
    kanal: Literal["widget", "shortcut"] = "widget"


def _store(request: Request) -> ic.CaptureStore:
    store = getattr(request.app.state, "inbox_capture", None)
    if store is None:
        store = ic.CaptureStore()
        request.app.state.inbox_capture = store
    return store


@router.post("/capture")
async def capture(body: CaptureIn, request: Request, _admin: str = Depends(require_admin)) -> dict:
    if not ic.is_allowed_projekt(body.projekt):
        raise HTTPException(status_code=422, detail="projekt_not_allowed")
    issue = ic.compose_capture(body.text, body.projekt, body.kanal)
    if issue is None:
        raise HTTPException(status_code=422, detail="text_empty")

    svc = getattr(request.app.state, "github_inbox", None)
    if svc is None or not svc.ready():
        log.warning("inbox_capture_unavailable", kanal=body.kanal)
        raise HTTPException(status_code=503, detail="inbox_unavailable")

    store = _store(request)
    state, cached = store.begin(body.nonce)
    if state == "done":
        log.info("inbox_capture_duplicate", number=(cached or {}).get("number"), kanal=body.kanal)
        return {**(cached or {}), "duplicate": True}
    if state == "in_flight":
        log.info("inbox_capture_in_flight", kanal=body.kanal)
        raise HTTPException(status_code=409, detail="capture_in_progress")
    if state == "rate_limited":
        log.warning("inbox_capture_rate_limited", kanal=body.kanal)
        raise HTTPException(status_code=429, detail="rate_limited", headers={"Retry-After": "60"})

    # Ab hier ist die Nonce reserviert: auf JEDEM Weg (auch Abbruch/CancelledError) wieder abschliessen
    # oder freigeben — sonst bekaeme die Warteschlange der App fuer diese Idee dauerhaft 409.
    out: dict | None = None
    error_type: str | None = None
    try:
        result = await svc.create_issue(title=issue["title"], body=issue["body"], labels=issue["labels"])
        if result and result.get("number") is not None:
            out = {"number": result["number"], "url": result.get("url")}
            store.finish(body.nonce, out)
    except Exception as exc:  # noqa: BLE001 — create_issue wirft laut Vertrag nie; Sicherheitsnetz
        error_type = type(exc).__name__
    finally:
        if out is None:
            store.release(body.nonce)
    if out is None:
        log.warning("inbox_capture_failed", projekt=body.projekt, kanal=body.kanal, error_type=error_type)
        raise HTTPException(status_code=502, detail="github_failed")
    # Nur Metadaten loggen — nie den Ideen-Text.
    log.info("inbox_capture_created", number=out["number"], projekt=body.projekt, kanal=body.kanal)
    return {**out, "duplicate": False}

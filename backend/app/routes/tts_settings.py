"""TTS-Settings-Endpoint (TB2) — App wählt Stimme (voice_id) + Modell (model_id).

GET  /settings/tts  → aktuelle Auswahl + Optionen (Stimmen, erlaubte Modelle)
POST /settings/tts  → validiert + persistiert (Admin-Auth wie /tts)

Nur die zwei Env-Stimmen (Eva/Elisabeth) und die Modell-Allowlist sind zulässig;
ungültige Werte → 400. Der Store liegt persistent unter /data/appdata.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field

from app.core.auth import require_admin
from app.core.logger import get_logger
from app.services import tts_settings_service as svc

router = APIRouter(prefix="/settings", tags=["settings"])
log = get_logger("route.tts_settings")


class TTSSettingsIn(BaseModel):
    voice_id: str = Field(..., min_length=1, max_length=64)
    model_id: str = Field(..., min_length=1, max_length=64)


def _payload(current: svc.TTSSettings) -> dict:
    return {
        "voice_id": current.voice_id,
        "model_id": current.model_id,
        "voices": svc.voices_meta(),
        "models": list(svc.ALLOWED_MODELS),
    }


@router.get("/tts")
async def get_tts_settings(_admin: str = Depends(require_admin)) -> dict:
    return _payload(svc.load_settings())


@router.post("/tts")
async def set_tts_settings(body: TTSSettingsIn, _admin: str = Depends(require_admin)) -> dict:
    try:
        current = svc.save_settings(body.voice_id, body.model_id)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc))
    log.info("tts_settings_updated", voice_id=current.voice_id, model_id=current.model_id)
    return _payload(current)

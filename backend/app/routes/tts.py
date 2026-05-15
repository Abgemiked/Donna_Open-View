"""
TTS-Endpoint — Server-seitige Synthese.

DONNA-191: Piper-TTS entfernt. Android nutzt Samsung Neural TTS direkt (On-Device).
Der /tts-Endpunkt gibt 501 zurück — kein Server-TTS mehr aktiv.

Migrations-Historie:
- Kokoro-82M (DONNA-39): kein Deutsch — entfernt
- Piper-TTS (DONNA-39/41/191): Funkrauschen / entfernt — On-Device-TTS übernimmt
- MeloTTS: kein Deutsch-Support — nicht deployed
- XTTS-v2: zu langsam auf CPU (5-15s/Satz) — verworfen
- Samsung Neural TTS (aktuell, Android): On-Device, keine Server-Latenz
"""
from __future__ import annotations

from fastapi import APIRouter, Depends
from fastapi.responses import Response
from pydantic import BaseModel, Field

from app.core.auth import require_admin
from app.core.logger import get_logger

router = APIRouter(prefix="/tts", tags=["tts"])
log = get_logger("route.tts")


# ---------------------------------------------------------------------------
# Schemas (erhalten für Backwards-Compat alter Clients)
# ---------------------------------------------------------------------------

class TTSRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=2000, description="Zu sprechender Text")
    voice: str = Field(default="de_DE-kerstin-low", description="Stimm-ID — nicht mehr genutzt (DONNA-191)")
    was_voice_input: bool = Field(
        default=False,
        description="True wenn der User per Sprache gefragt hat",
    )


# ---------------------------------------------------------------------------
# No-op pre_synthesize (DONNA-191: Piper entfernt — chat.py ruft dies noch auf)
# ---------------------------------------------------------------------------

async def pre_synthesize(text: str) -> None:
    """DONNA-191: Piper entfernt — kein Server-TTS mehr. No-op für Chat-Compat."""
    log.debug("tts_pre_synthesize_noop_piper_removed", chars=len(text))


# ---------------------------------------------------------------------------
# Endpoint
# ---------------------------------------------------------------------------

@router.post("", response_class=Response)
async def synthesize_tts(
    body: TTSRequest,
    _admin: str = Depends(require_admin),
) -> Response:
    """DONNA-191: Server-TTS (Piper) entfernt. Android nutzt Samsung Neural TTS On-Device.

    Gibt 501 zurück — kein Server-TTS mehr verfügbar.
    """
    log.info("tts_endpoint_disabled_piper_removed", was_voice_input=body.was_voice_input)
    return Response(
        status_code=501,
        content=b"Server-TTS nicht mehr verfuegbar (DONNA-191). Nutze On-Device Samsung Neural TTS.",
        media_type="text/plain",
    )

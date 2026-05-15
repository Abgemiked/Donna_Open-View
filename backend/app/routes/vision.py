"""vision.py — DONNA-130: Camera/Vision Analyse via Gemini Vision.

Endpunkt:
    POST /vision/analyze — Bild analysieren via Gemini 1.5 Flash

Auth: Bearer-Token (require_admin).

Datenschutz (DSGVO Art. 5):
- Bild wird NICHT gespeichert (weder auf Disk noch in DB)
- Einmalige In-Memory-Verarbeitung: Base64 → Gemini → Analyse-Text
- Maximale Bildgröße: 4MB Base64 (~3MB Original)
- Bild-Daten erscheinen nicht in Logs (nur Größe wird geloggt)

Sicherheit:
- Authentifizierung via require_admin (Bearer-Token)
- Bild-Größen-Limit serverseitig enforced
- Base64-Validierung vor Weitergabe an Gemini
"""
from __future__ import annotations

import base64
import binascii

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field, field_validator

from app.core.auth import require_admin
from app.core.logger import get_logger

log = get_logger("route.vision")

router = APIRouter(prefix="/vision", tags=["vision"])

# Maximale Base64-Länge: 4MB (entspricht ~3MB Original-Bild)
_MAX_BASE64_BYTES = 4 * 1024 * 1024
# Maximale Frage-Länge (Sicherheits-Limit)
_MAX_QUESTION_LEN = 500


# ── Pydantic Models ───────────────────────────────────────────────────────────


class VisionAnalyzeRequest(BaseModel):
    image_base64: str = Field(..., description="Base64-kodiertes Bild (JPEG/PNG, max 4MB)")
    question: str = Field(..., description="Frage zur Bildanalyse", max_length=_MAX_QUESTION_LEN)

    @field_validator("image_base64")
    @classmethod
    def validate_base64(cls, v: str) -> str:
        if len(v) > _MAX_BASE64_BYTES:
            raise ValueError(f"Bild zu groß: {len(v)} Bytes Base64 (Maximum: {_MAX_BASE64_BYTES})")
        # Prüfe ob valides Base64
        try:
            base64.b64decode(v, validate=True)
        except (binascii.Error, ValueError):
            raise ValueError("Ungültiges Base64-Format")
        return v

    @field_validator("question")
    @classmethod
    def validate_question(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("Frage darf nicht leer sein")
        return v


class VisionAnalyzeResponse(BaseModel):
    analysis: str = Field(..., description="Gemini-Analyse des Bildes")


# ── Route ─────────────────────────────────────────────────────────────────────


@router.post(
    "/analyze",
    response_model=VisionAnalyzeResponse,
    summary="Bild analysieren via Gemini Vision",
    description=(
        "Analysiert ein Base64-kodiertes Bild mit Gemini 1.5 Flash. "
        "Das Bild wird NICHT gespeichert — einmalige In-Memory-Verarbeitung. "
        "Auth: Bearer-Token erforderlich."
    ),
)
async def analyze_image(
    request: Request,
    body: VisionAnalyzeRequest,
    _: str = Depends(require_admin),
) -> VisionAnalyzeResponse:
    """Analysiert ein Kamerabild via Gemini Vision API.

    Sicherheits- und Datenschutz-Checks:
    1. Auth via Bearer-Token (require_admin)
    2. Base64-Validierung (Pydantic field_validator)
    3. Größenprüfung (4MB Limit)
    4. Bild-Daten werden nicht geloggt
    5. Kein Speichern auf Disk oder DB
    """
    gemini = getattr(request.app.state, "gemini", None)
    if gemini is None or not gemini.ready():
        log.error("vision_analyze_no_gemini")
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Gemini Vision nicht verfügbar — GEMINI_API_KEY nicht konfiguriert.",
        )

    image_bytes = base64.b64decode(body.image_base64)
    image_kb = len(image_bytes) // 1024
    log.info(
        "vision_analyze_start",
        image_kb=image_kb,
        question_len=len(body.question),
    )

    try:
        analysis = await _analyze_with_gemini(gemini, image_bytes, body.question)
    except Exception as exc:
        log.error("vision_analyze_failed", error=str(exc))
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"Gemini Vision Fehler: {exc}",
        ) from exc

    log.info("vision_analyze_done", analysis_len=len(analysis))
    return VisionAnalyzeResponse(analysis=analysis)


# ── Gemini Vision Helper ──────────────────────────────────────────────────────


async def _analyze_with_gemini(gemini, image_bytes: bytes, question: str) -> str:
    """Sendet Bild + Frage an Gemini 1.5 Flash und gibt Analyse-Text zurück.

    Nutzt google-generativeai inline_data für Bild-Upload (kein Cloud-Storage).
    Das Bild verlässt den Server als Gemini-API-Request und wird danach verworfen.
    """
    import google.generativeai as genai

    # Gemini 1.5 Flash — schnell + günstig für Vision-Tasks
    model = genai.GenerativeModel("gemini-1.5-flash")

    # Systemprompt: Donna-Kontext + Datenschutz-Hinweis
    system_prompt = (
        "Du bist Donna, ein persönlicher KI-Assistent. "
        "Analysiere das Bild und beantworte die Frage präzise und auf Deutsch. "
        "Beschreibe was du siehst kurz und sachlich."
    )

    prompt_parts = [
        system_prompt,
        "\n\nFrage: ",
        question,
        "\n\nBild:\n",
        {
            "inline_data": {
                "mime_type": "image/jpeg",
                "data": image_bytes,
            }
        },
    ]

    response = await model.generate_content_async(prompt_parts)

    if not response or not response.text:
        raise ValueError("Leere Antwort von Gemini Vision")

    return response.text.strip()

"""/wake-word/check — Wake-Word-Erkennung via faster-whisper.

Nimmt kurze Audio-Frames (2–3 s, WebM/WAV) entgegen und prüft ob "Hey Donna"
(oder phonetische Varianten) im transkribierten Text vorkommt.

Gibt {"match": true/false, "transcript": "..."} zurück.

Nutzt denselben Whisper-Modell-Cache wie /speech/transcribe
(speech.py — geteilter _model-Singleton via import).

DONNA-73 Fix: Chromium MediaRecorder WebM-Header sind unvollständig (kein
finales EBMLSize) → faster-whisper liefert "Invalid data found" → immer
match=false. Fix: ffmpeg-Preprocessing wie in speech.py (gleiche Flags:
-err_detect ignore_err, -fflags +genpts+discardcorrupt).
"""
from __future__ import annotations

import asyncio
import os
import re
import tempfile

from fastapi import APIRouter, Depends, HTTPException, UploadFile, status

from app.core.auth import require_admin
from app.core.logger import get_logger
from app.routes.speech import get_pool, _worker_transcribe_short

log = get_logger("route.wake_word")
router = APIRouter(prefix="/wake-word", tags=["wake-word"])

_FFMPEG_TIMEOUT = 10.0  # kurze Frames — 10 s reichen

# Max. 2 gleichzeitige Whisper-Inferenzen — verhindert Thread-Pool-Sättigung
# bei 2s-Polling vom WakeWordService (Android).
# Überlauf → sofortiges {"match": false} statt Request-Queue → keine Blockierung.
_WHISPER_SEM: asyncio.Semaphore | None = None


def _get_whisper_sem() -> asyncio.Semaphore:
    global _WHISPER_SEM
    if _WHISPER_SEM is None:
        _WHISPER_SEM = asyncio.Semaphore(2)
    return _WHISPER_SEM

# Phonetische Varianten die Whisper für "Hey Donna" produzieren kann
_WAKE_PATTERNS: list[re.Pattern[str]] = [
    re.compile(r"hey[,.]?\s+don+a", re.IGNORECASE),
    re.compile(r"hei[,.]?\s+don+a", re.IGNORECASE),
    re.compile(r"he[,.]?\s+don+a",  re.IGNORECASE),
    re.compile(r"hay[,.]?\s+don+a", re.IGNORECASE),
    # Whisper verschmilzt manchmal "Hey Donna" → "Heydonna" / "heidona"
    re.compile(r"hey\s*don+a",      re.IGNORECASE),
    # Seltener: Whisper transkribiert nur "Donna" wenn "Hey" nicht erkannt
    re.compile(r"^\s*don+a[,!.]?\s*$", re.IGNORECASE),
    # Whisper/de halluziniert "Donna" → "Dann" oder "Dan" (häufig bei deutschen Modellen)
    re.compile(r"hey[,.]?\s+dan+[,!.]?\s*$", re.IGNORECASE),
    re.compile(r"^\s*dan+[,!.]?\s*$",         re.IGNORECASE),
    # Whisper "dona" (einzel-n, spanische/italienische Variante)
    re.compile(r"hey[,.]?\s+dona\b",           re.IGNORECASE),
    re.compile(r"^\s*dona[,!.]?\s*$",          re.IGNORECASE),
]


def _is_wake_word(text: str) -> bool:
    """Prüft ob der Text eine Wake-Word-Variante enthält."""
    return any(pat.search(text) for pat in _WAKE_PATTERNS)


async def _to_wav_wake(src_path: str, dst_path: str) -> None:
    """Konvertiert WebM-Frame zu 16kHz-WAV via ffmpeg.

    Identische Flags wie speech.py._to_wav — toleriert unvollständige
    Chromium-MediaRecorder-WebM-Header (kein finales EBMLSize).
    """
    proc = await asyncio.create_subprocess_exec(
        "ffmpeg",
        "-hide_banner", "-loglevel", "warning",
        "-err_detect", "ignore_err",
        "-fflags", "+genpts+discardcorrupt",
        "-i", src_path,
        "-ar", "16000",
        "-ac", "1",
        "-c:a", "pcm_s16le",
        "-f", "wav",
        "-y",
        dst_path,
        stdout=asyncio.subprocess.DEVNULL,
        stderr=asyncio.subprocess.PIPE,
    )
    try:
        _, stderr = await asyncio.wait_for(proc.communicate(), timeout=_FFMPEG_TIMEOUT)
    except asyncio.TimeoutError as exc:
        proc.kill()
        log.warning("wake_word_ffmpeg_timeout")
        raise RuntimeError("ffmpeg timeout") from exc

    if proc.returncode != 0:
        err = (stderr or b"").decode(errors="replace")[:200]
        log.error("wake_word_ffmpeg_error", returncode=proc.returncode, stderr=err)
        raise RuntimeError(f"ffmpeg rc={proc.returncode}: {err}")


# _transcribe_short → ersetzt durch speech._worker_transcribe_short im
# ProcessPool — siehe DONNA-STT-Fix in routes/speech.py.


@router.post("/check")
async def check_wake_word(
    audio: UploadFile,
    _admin: str = Depends(require_admin),
) -> dict[str, object]:
    """Prüft einen kurzen Audio-Frame (2–4 s) auf Wake-Word 'Hey Donna'.

    Akzeptiert WebM/WAV. Gibt {"match": bool, "transcript": str} zurück.
    Optimiert für niedrige Latenz: beam_size=1, kein VAD-Filter.

    Pipeline: Upload → ffmpeg 16kHz WAV → faster-whisper
    (ffmpeg überbrückt unvollständige Chromium-MediaRecorder-WebM-Header)
    """
    if not audio.content_type or not any(
        t in (audio.content_type or "")
        for t in ("audio/", "video/webm", "application/octet-stream")
    ):
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail="Audiodatei erforderlich (WebM oder WAV)",
        )

    data = await audio.read()
    if len(data) > 500_000:
        raise HTTPException(status_code=413, detail="Audio too large")
    if len(data) < 200:
        return {"match": False, "transcript": ""}

    suffix = ".webm"
    if "wav" in (audio.content_type or ""):
        suffix = ".wav"

    # Schritt 1: Rohdaten auf Disk schreiben
    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
        tmp.write(data)
        raw_path = tmp.name

    wav_path = raw_path + "_16k.wav"
    try:
        # Schritt 2: ffmpeg → 16kHz WAV (toleriert unvollständige WebM-Header)
        try:
            await _to_wav_wake(raw_path, wav_path)
            transcribe_path = wav_path
        except RuntimeError as ffmpeg_err:
            # ffmpeg-Fehler: direkt auf Rohdatei zurückfallen (z.B. sauberes WAV)
            log.warning("wake_word_ffmpeg_fallback", error=str(ffmpeg_err))
            transcribe_path = raw_path

        # Schritt 3: faster-whisper im ProcessPool (DONNA-STT-Fix)
        # Concurrency-Limit weiterhin aktiv — Pool hat max_workers=1, deshalb
        # bei Last sofort reject statt endlos queuen, sonst staut sich die
        # 2s-Polling-Welle vom Android-WakeWordService an.
        sem = _get_whisper_sem()
        if sem.locked():
            log.debug("wake_word_throttled")
            return {"match": False, "transcript": ""}
        loop = asyncio.get_event_loop()
        pool = get_pool()
        try:
            async with sem:
                text = await loop.run_in_executor(pool, _worker_transcribe_short, transcribe_path)
        except Exception as exc:
            log.error("wake_word_transcribe_error", error=str(exc))
            return {"match": False, "transcript": ""}
    finally:
        for p in (raw_path, wav_path):
            try:
                os.unlink(p)
            except OSError:
                pass

    matched = _is_wake_word(text)
    if matched:
        log.info("wake_word_detected", transcript=text)
    else:
        log.info("wake_word_no_match", transcript=text[:80] if text else "")

    return {"match": matched, "transcript": text}

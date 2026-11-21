"""/speech/transcribe — Whisper-basierte Spracheingabe (faster-whisper base, CPU int8).

Modell wird im Worker-Process (ProcessPoolExecutor max_workers=1) gehalten —
NICHT im Haupt-Process. Grund: faster-whisper nutzt ctranslate2 (C-Extension),
die den Python-GIL bei Inferenz hält. ThreadPoolExecutor (run_in_executor mit
None) hilft NICHT — concurrent async Tasks (z. B. Chat-SSE) friert dann ein.

ProcessPool isoliert die Inferenz in einen separaten Process → Event-Loop
bleibt frei. Trade-off: 1 Worker = sequentieller STT (Queue), aber wir wollen
genau das (Modell hat eh nur 1 vCPU sinnvoll auf dem CCX23).

Akzeptiert WebM/Opus (MediaRecorder-Default aus Chromium) sowie WAV, MP3, OGG.

Audio-Pipeline:
  Upload (WebM/WAV/OGG/MP3) → ffmpeg → 16kHz Mono WAV → faster-whisper → Text
  ffmpeg vorgeschaltet weil Chromium MediaRecorder WebM-Header nicht vollständig
  abschließt → "Invalid data found" in faster-whispers internem Decoder.
  ffmpeg mit -err_detect ignore_err überbrückt das.

Gibt {"text": "..."} zurück.
"""
from __future__ import annotations

import asyncio
import os
import tempfile
from concurrent.futures import ProcessPoolExecutor
from typing import Any, Optional

from fastapi import APIRouter, Depends, HTTPException, UploadFile, status

from app.core.auth import require_admin
from app.core.logger import get_logger

log = get_logger("route.speech")
router = APIRouter(prefix="/speech", tags=["speech"])

_MODEL_SIZE = "base"
_FFMPEG_TIMEOUT = 30.0

# ---------------------------------------------------------------------------
# Worker-Process State (separater Memory-Space pro Worker)
# ---------------------------------------------------------------------------
# Diese Variable existiert im Worker-Prozess und wird vom initializer gesetzt.
# Im Haupt-Prozess bleibt sie None und wird nie verwendet.
_worker_model: Any = None


def _worker_init() -> None:
    """ProcessPool-initializer — lädt WhisperModel im Worker-Process.

    Wird genau einmal pro Worker-Process aufgerufen (max_workers=1 → einmal
    pro Pool-Lebenszeit). Modell bleibt danach im Worker-Memory.
    """
    global _worker_model
    from faster_whisper import WhisperModel  # type: ignore[import]
    _worker_model = WhisperModel(_MODEL_SIZE, device="cpu", compute_type="int8")


def _worker_transcribe(wav_path: str) -> str:
    """Wird im Worker-Process ausgeführt. Erwartet 16kHz Mono WAV.

    Standard-Modus (Speech-Endpoint): de, beam_size=1, vad_filter=True.
    """
    global _worker_model
    if _worker_model is None:
        # Defensiv: falls initializer nicht lief (sollte nie passieren).
        from faster_whisper import WhisperModel  # type: ignore[import]
        _worker_model = WhisperModel(_MODEL_SIZE, device="cpu", compute_type="int8")
    segments, _ = _worker_model.transcribe(wav_path, language="de", beam_size=1, vad_filter=True)
    return " ".join(s.text.strip() for s in segments).strip()


def _worker_transcribe_short(wav_path: str) -> str:
    """Wird im Worker-Process ausgeführt — Kurz-Frame-Mode für Wake-Word.

    language=None (auto-detect, vermeidet "Donna" → "Dann"-Halluzination im
    de-Modus), kein VAD-Filter (zu kurz für VAD), kein Context-Conditioning.
    """
    global _worker_model
    if _worker_model is None:
        from faster_whisper import WhisperModel  # type: ignore[import]
        _worker_model = WhisperModel(_MODEL_SIZE, device="cpu", compute_type="int8")
    segments, _ = _worker_model.transcribe(
        wav_path,
        language=None,
        beam_size=1,
        vad_filter=False,
        condition_on_previous_text=False,
    )
    return " ".join(s.text.strip() for s in segments).strip()


# ---------------------------------------------------------------------------
# Pool-Lifecycle (vom main.py lifespan gesteuert)
# ---------------------------------------------------------------------------

_process_pool: Optional[ProcessPoolExecutor] = None


def get_pool() -> ProcessPoolExecutor:
    """Gibt den globalen ProcessPool zurück oder erstellt ihn lazy.

    main.py ruft init_pool() im lifespan vor dem ersten Request auf —
    diese Lazy-Variante ist nur Fallback (z. B. Tests).
    """
    global _process_pool
    if _process_pool is None:
        _process_pool = ProcessPoolExecutor(max_workers=1, initializer=_worker_init)
        log.info("whisper_pool_lazy_init")
    return _process_pool


def init_pool() -> ProcessPoolExecutor:
    """Wird vom main.py lifespan beim Startup aufgerufen."""
    global _process_pool
    if _process_pool is not None:
        return _process_pool
    _process_pool = ProcessPoolExecutor(max_workers=1, initializer=_worker_init)
    log.info("whisper_pool_init", workers=1, model=_MODEL_SIZE)
    return _process_pool


def shutdown_pool() -> None:
    """Wird vom main.py lifespan beim Shutdown aufgerufen."""
    global _process_pool
    if _process_pool is not None:
        _process_pool.shutdown(wait=False, cancel_futures=True)
        _process_pool = None
        log.info("whisper_pool_shutdown")


async def _to_wav(src_path: str, dst_path: str) -> None:
    """Konvertiert beliebige Audiodatei zu 16kHz-Mono-WAV via ffmpeg.

    Robuste Flags für Chromium MediaRecorder:
    - -err_detect ignore_err: unvollständige WebM-Header (kein finales EBMLSize)
    - -fflags +genpts+discardcorrupt: fehlerhafte Pakete überspringen statt abbrechen
    - -c:a pcm_s16le: sauberes 16-bit PCM für Whisper
    """
    proc = await asyncio.create_subprocess_exec(
        "ffmpeg",
        "-hide_banner", "-loglevel", "warning",
        "-err_detect", "ignore_err",          # unvollständige WebM-Container tolerieren
        "-fflags", "+genpts+discardcorrupt",  # fehlerhafte Pakete überspringen
        "-i", src_path,
        "-ar", "16000",                       # Whisper-optimale Sample-Rate
        "-ac", "1",                           # Mono
        "-c:a", "pcm_s16le",                  # 16-bit PCM (Whisper-Standard)
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
        raise HTTPException(status_code=504, detail="ffmpeg Timeout") from exc

    if proc.returncode != 0:
        err = (stderr or b"").decode(errors="replace")[:300]
        log.error("speech_ffmpeg_error", returncode=proc.returncode, stderr=err)
        raise HTTPException(
            status_code=422,
            detail=f"Audio-Konvertierung fehlgeschlagen: {err}",
        )


@router.post("/transcribe")
async def transcribe(
    audio: UploadFile,
    _admin: str = Depends(require_admin),
) -> dict[str, str]:
    """Transkribiert eine Audiodatei (WebM/WAV/MP3/OGG) mit Whisper base (Deutsch).

    Pipeline: Upload → ffmpeg 16kHz WAV → faster-whisper (im Worker-Process) → Text
    ffmpeg überbrückt unvollständige Chromium-WebM-Header (-err_detect ignore_err).
    Whisper läuft im ProcessPool → blockiert NICHT den FastAPI Event-Loop.
    """
    ct = audio.content_type or ""
    if ct and not any(
        t in ct for t in ("audio/", "video/webm", "application/octet-stream")
    ):
        raise HTTPException(status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
                            detail="Audiodatei erforderlich")

    data = await audio.read()
    if len(data) < 500:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                            detail="Audiodatei zu kurz")

    # Suffix aus Content-Type ableiten
    suffix = ".webm"
    if "wav" in ct:
        suffix = ".wav"
    elif "ogg" in ct:
        suffix = ".ogg"
    elif "mp3" in ct or "mpeg" in ct:
        suffix = ".mp3"

    src_path = ""
    wav_path = ""
    try:
        # 1. Upload-Datei speichern
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
            tmp.write(data)
            src_path = tmp.name

        # 2. ffmpeg → 16kHz WAV (robust, toleriert unvollständige WebM-Header)
        wav_fd, wav_path = tempfile.mkstemp(suffix=".wav")
        os.close(wav_fd)
        await _to_wav(src_path, wav_path)

        log.info(
            "speech_ffmpeg_ok",
            src_bytes=len(data),
            src_suffix=suffix,
            wav_bytes=os.path.getsize(wav_path),
        )

        # 3. Whisper-Transkription im Worker-Process (NICHT im Event-Loop-Process!)
        #    run_in_executor mit ProcessPoolExecutor → echte OS-Process-Isolation,
        #    GIL ist getrennt → Event-Loop bleibt für andere Requests responsiv.
        loop = asyncio.get_event_loop()
        pool = get_pool()
        text = await loop.run_in_executor(pool, _worker_transcribe, wav_path)

    except HTTPException:
        raise
    except Exception as exc:
        log.error("whisper_transcribe_error", error=str(exc))
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Transkription fehlgeschlagen: {exc}",
        ) from exc
    finally:
        for p in (src_path, wav_path):
            if p:
                try:
                    os.unlink(p)
                except OSError:
                    pass

    if not text:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Kein Text erkannt — bitte deutlicher sprechen",
        )

    log.info("whisper_transcribed", chars=len(text), preview=text[:60])
    return {"text": text}

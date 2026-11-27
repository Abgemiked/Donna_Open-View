"""TTS-Settings-Store (TB2) — persistenter voice_id/model_id-Store.

Speichert die vom Nutzer gewählte ElevenLabs-Stimme + das Modell unter
``/data/appdata/tts_settings.json`` (persistenter Bind-Mount; ``/data`` selbst
ist flüchtig). Fail-safe: fehlende/korrupte Datei oder ungültige Werte fallen
auf die Defaults zurück — nie ein Crash, denn der /tts-Pfad hängt daran.

Erlaubte Stimmen: genau die zwei Env-IDs (Eva + Elisabeth).
Erlaubte Modelle: Allowlist (``ALLOWED_MODELS``) — gegen elevenlabs.io/docs/overview/models
verifiziert (2026-11-01). Default-Modell: ``eleven_v4_turbo`` (v4 Turbo, Realtime).
"""
from __future__ import annotations

import json
import os
from dataclasses import dataclass
from pathlib import Path

from app.config import get_settings
from app.core.logger import get_logger

log = get_logger("tts.settings")

# Erlaubte model_ids (verifiziert gegen elevenlabs.io/docs/overview/models, 2026-11-01):
#   eleven_v4_turbo        — v4 Turbo, Realtime (~100 ms) → Default
#   eleven_v4              — v4, höchste Qualität
#   eleven_multilingual_v2 — bewährte Vorgänger-Generation
ALLOWED_MODELS: tuple[str, ...] = (
    "eleven_v4_turbo",
    "eleven_v4",
    "eleven_multilingual_v2",
)
DEFAULT_MODEL = "eleven_v4_turbo"

# Fallback-Defaults, falls die Config-Felder leer wären (defensiv).
_FALLBACK_EVA = "R24r0IV80Iw9eyByq1ps"
_FALLBACK_ELISABETH = "3EKymYQPdS1Q9fxZBuq8"


@dataclass(frozen=True)
class TTSSettings:
    voice_id: str
    model_id: str


def allowed_voice_ids() -> tuple[str, str]:
    """Die genau zwei erlaubten ElevenLabs-Stimmen-IDs aus der Config (Eva, Elisabeth)."""
    s = get_settings()
    eva = (s.elevenlabs_voice_id or _FALLBACK_EVA).strip()
    elisabeth = (s.elevenlabs_voice_id_fallback or _FALLBACK_ELISABETH).strip()
    return eva, elisabeth


def voices_meta() -> list[dict]:
    """Stimmen-Metadaten für den GET-Endpoint (Name + ID)."""
    eva, elisabeth = allowed_voice_ids()
    return [{"name": "Eva", "id": eva}, {"name": "Elisabeth", "id": elisabeth}]


def default_settings() -> TTSSettings:
    eva, _ = allowed_voice_ids()
    return TTSSettings(voice_id=eva, model_id=DEFAULT_MODEL)


def _store_path() -> Path:
    return Path(get_settings().tts_settings_path)


def load_settings(path: Path | None = None) -> TTSSettings:
    """Liest den Store fail-safe. Fehlt die Datei / ist sie korrupt / enthält sie
    ungültige Werte → Default (je Feld). Wirft NIE."""
    p = path or _store_path()
    try:
        raw = json.loads(Path(p).read_text(encoding="utf-8"))
    except FileNotFoundError:
        return default_settings()
    except Exception as exc:  # noqa: BLE001 - korrupt/Permission/Encoding → Default
        log.warning("tts_settings_load_failed", error=type(exc).__name__)
        return default_settings()

    dflt = default_settings()
    voice_id = str((raw or {}).get("voice_id") or "").strip()
    model_id = str((raw or {}).get("model_id") or "").strip()
    if voice_id not in allowed_voice_ids():
        voice_id = dflt.voice_id
    if model_id not in ALLOWED_MODELS:
        model_id = dflt.model_id
    return TTSSettings(voice_id=voice_id, model_id=model_id)


def save_settings(voice_id: str, model_id: str, path: Path | None = None) -> TTSSettings:
    """Validiert voice_id ∈ {Eva, Elisabeth} und model_id ∈ ALLOWED_MODELS und
    persistiert atomar. Bei ungültigen Werten: ValueError (→ 400 im Endpoint),
    es wird nichts geschrieben."""
    voice_id = (voice_id or "").strip()
    model_id = (model_id or "").strip()
    if voice_id not in allowed_voice_ids():
        raise ValueError(f"voice_id nicht erlaubt: {voice_id!r}")
    if model_id not in ALLOWED_MODELS:
        raise ValueError(f"model_id nicht erlaubt: {model_id!r}")

    p = Path(path or _store_path())
    p.parent.mkdir(parents=True, exist_ok=True)
    data = {"voice_id": voice_id, "model_id": model_id}
    tmp = p.with_suffix(p.suffix + ".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    os.replace(tmp, p)  # atomar
    return TTSSettings(voice_id=voice_id, model_id=model_id)

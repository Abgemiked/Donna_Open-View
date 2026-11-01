"""
TTS-Endpoint — /tts liefert MP3 (audio/mpeg).

Provider (TB1): ElevenLabs ist die PRIMÄRE Cloud-Stimme; Edge-TTS (Microsoft Neural,
kostenlos, kein Key) ist der AUTOMATISCHE Fallback. Logik (synthesize_tts_audio):
- Key vorhanden + Stimme/Modell gesetzt + kein Backoff → ElevenLabs.
- Bei JEDEM EL-Fehler (401/403/429/Quota/Netz/Timeout/unerwartet) → Edge-Fallback;
  solange Edge geht, failt /tts nie hart. 401/403/429 lösen ~60s-Backoff aus (direkt Edge).
- IPv4-Zwang: der Server-Key ist auf IPv4 YOUR_SERVER_IP beschränkt → httpx mit
  local_address=YOUR_SERVER_IP und trust_env=False (kein Proxy-Egress).
- Credit-Schonung: ElevenLabs-Timeout 6s; Pre-Synthese bei EL nur der ERSTE Satz.

Stimme/Modell: Default aus dem TTS-Settings-Store (TB2, /settings/tts) — "Eva" + v4 Turbo.
Request-`voice` (Query `?voice=` oder Body) überschreibt die Stimme: eine der zwei erlaubten
ElevenLabs-IDs überschreibt die Cloud-Stimme, ein Edge-Stimmenname steuert den Fallback.
Unbekannte/alte Werte (z. B. Piper-IDs) → Edge-Default, kein 422.

Stimmen Edge (weiblich, Fallback): de-DE-KatjaNeural (Default), de-DE-SeraphinaMultilingualNeural.

Antworten:
- 200 audio/mpeg   — Synthese erfolgreich (Header/Log: provider=elevenlabs|edge)
- 204              — nichts Sprechbares im Text (nach Bereinigung leer)
- 503 / 504        — AUCH Edge nicht erreichbar / Timeout (Client faellt auf die On-Device-Engine zurueck)

Migrations-Historie:
- Kokoro-82M (DONNA-39): kein Deutsch — entfernt
- Piper-TTS server-seitig (DONNA-39/41/191): Funkrauschen / entfernt
- MeloTTS: kein Deutsch-Support — nicht deployed
- XTTS-v2: zu langsam auf CPU (5-15s/Satz) — verworfen
- Edge-TTS (Voice-Batch): Cloud-Neural, ~0,4-1,2 s pro Satz, kostenlos — jetzt Fallback
- ElevenLabs (TB1, aktuell primär): Cloud, v4 Turbo, mp3_44100_128
"""
from __future__ import annotations

import asyncio
import os
import re
import time
from collections import OrderedDict

import httpx
from fastapi import APIRouter, Depends, Query
from fastapi.responses import Response
from pydantic import BaseModel, Field

from app.config import get_settings
from app.core.auth import require_admin
from app.core.logger import get_logger
from app.services import tts_settings_service as _tts_settings

router = APIRouter(prefix="/tts", tags=["tts"])
log = get_logger("route.tts")

# ---------------------------------------------------------------------------
# Konfiguration
# ---------------------------------------------------------------------------

EDGE_DEFAULT_VOICE = "de-DE-KatjaNeural"
EDGE_ALLOWED_VOICES = frozenset({
    "de-DE-KatjaNeural",
    "de-DE-SeraphinaMultilingualNeural",
})

_RATE_RE = re.compile(r"[+-]\d{1,3}%")
_NEUTRAL_RATE = "+0%"
_SERAPHINA_DEFAULT_RATE = "+15%"


def _rate_from_env(name: str, default: str) -> str:
    """Sprechtempo aus ENV ('+15%'/'-10%'). Kaputter Wert -> default (edge-tts wirft sonst bei der Synthese)."""
    raw = os.getenv(name)
    if raw is None:
        return default
    raw = raw.strip()
    return raw if _RATE_RE.fullmatch(raw) else default


#: Sprechtempo je Stimme (edge-tts ``rate``). Nicht gelistete Stimmen (Katja) -> neutral "+0%".
#: Seraphina spricht von Haus aus langsam -> per ENV ``TTS_SERAPHINA_RATE`` einstellbar (Default +15%).
#: Wird beim Modulimport aus der ENV gelesen -> Aenderung wirkt nach Backend-Neustart.
_VOICE_RATE: dict[str, str] = {
    "de-DE-SeraphinaMultilingualNeural": _rate_from_env(
        "TTS_SERAPHINA_RATE", _SERAPHINA_DEFAULT_RATE
    ),
}

#: Obergrenze fuer EINE Synthese (Edge-Websocket). Danach 504 -> Client nutzt die andere Engine.
SYNTH_TIMEOUT_S = 10.0
#: Gleichzeitige Edge-Synthesen (Schutz vor Rate-Limit durch Pre-Synthese + Client-Requests).
MAX_CONCURRENT_SYNTH = 3
#: Pre-Synthese (DONNA-137): hoechstens so viele Saetze pro Antwort vorab synthetisieren.
PRE_SYNTH_MAX_SENTENCES = 6

_CACHE_MAX_ENTRIES = 64
_CACHE_TTL_S = 600.0

_cache: "OrderedDict[tuple[str, str], tuple[float, bytes]]" = OrderedDict()
_inflight: "dict[tuple[str, str], asyncio.Future[bytes]]" = {}
_semaphore: asyncio.Semaphore | None = None


class TTSUnavailable(Exception):
    """Edge-TTS nicht erreichbar oder lieferte kein Audio."""


# ---------------------------------------------------------------------------
# ElevenLabs (primäre Cloud-Stimme; Edge = automatischer Fallback)
# ---------------------------------------------------------------------------

_EL_API_BASE = "https://api.elevenlabs.io"
#: IPv4 erzwingen — der Server-Key ist auf IPv4 YOUR_SERVER_IP beschränkt; IPv6 würde
#: abgelehnt. httpx-Transport bindet lokal auf YOUR_SERVER_IP (= alle IPv4-Interfaces).
_EL_LOCAL_ADDRESS = "YOUR_SERVER_IP"
#: mp3 → Response ist audio/mpeg. 44.1 kHz / 128 kbps ist der ElevenLabs-Default.
_EL_OUTPUT_FORMAT = "mp3_44100_128"
#: Worst-Case-Deckel (Fix-Runde A #3): von 10s auf 6s gesenkt, damit EL-Hang → Edge
#: nicht ~20s dauert. Gilt sowohl als httpx-Timeout als auch als wait_for fuer den EL-Pfad.
_EL_TIMEOUT_S = 6.0
#: Cache-Key-Präfix: so landet ElevenLabs-Audio nicht im selben Slot wie Edge-Audio.
_EL_VOICE_PREFIX = "el:"

#: Negativ-Cache/Backoff (Fix-Runde A #4): nach 401/403/429 vom EL-API fuer diese
#: Dauer gar nicht erst wieder EL versuchen (direkt Edge) — spart Credits + Latenz bei
#: gesperrtem Key / Quota-Ende. In-Memory, prozesslokal.
_EL_BACKOFF_S = 60.0
_EL_BACKOFF_STATUSES = frozenset({401, 403, 429})
_el_backoff_until: float = 0.0


class ElevenLabsError(Exception):
    """ElevenLabs-Synthese fehlgeschlagen (HTTP-Status oder Netzfehler). Löst Edge-Fallback aus."""

    def __init__(self, message: str, *, status: int | None = None) -> None:
        super().__init__(message)
        self.status = status


def _el_in_backoff() -> bool:
    """True solange der EL-Backoff nach einem 401/403/429 aktiv ist (→ direkt Edge)."""
    return time.monotonic() < _el_backoff_until


def _el_note_failure(status: int | None) -> None:
    """Startet den Backoff NUR bei 401/403/429 (Key/Quota). Transiente Fehler
    (Timeout, 5xx, Netz) loesen KEINEN Backoff aus — die duerfen gleich wieder EL versuchen."""
    global _el_backoff_until
    if status in _EL_BACKOFF_STATUSES:
        _el_backoff_until = time.monotonic() + _EL_BACKOFF_S


def _elevenlabs_key() -> str | None:
    """API-Key aus Config/Env. Leerer/fehlender Key → None (→ Edge-Pfad). Wird NIE geloggt."""
    key = get_settings().elevenlabs_api_key
    key = (key or "").strip()
    return key or None


def _elevenlabs_transport() -> httpx.AsyncHTTPTransport:
    """httpx-Transport mit erzwungener IPv4-Quelladresse (Key ist IPv4-gebunden)."""
    return httpx.AsyncHTTPTransport(local_address=_EL_LOCAL_ADDRESS)


def _build_elevenlabs_client() -> httpx.AsyncClient:
    """httpx-Client für ElevenLabs. trust_env=False (Fix-Runde A #2): ignoriert
    HTTP(S)_PROXY/NO_PROXY aus der Umgebung — sonst könnte ein Proxy den IPv4-
    local_address-Zwang aushebeln und der Key von einer anderen (gesperrten) IP
    rausgehen. Zusammen mit dem IPv4-Transport bleibt der Egress damit fix."""
    return httpx.AsyncClient(
        transport=_elevenlabs_transport(),
        timeout=_EL_TIMEOUT_S,
        trust_env=False,
    )


def _el_voice_key(voice_id: str, model_id: str) -> str:
    """Cache-Key-„Stimme" für ElevenLabs (provider-qualifiziert): 'el:<voice_id>:<model_id>'."""
    return f"{_EL_VOICE_PREFIX}{voice_id}:{model_id}"


def _resolve_elevenlabs_voice(request_voice: str | None, store_voice_id: str) -> str:
    """Request-`voice` darf die Stimme überschreiben — aber nur wenn es eine der beiden
    erlaubten ElevenLabs-IDs ist (sonst sind es Edge-Stimmennamen). Sonst: Store-Default."""
    if request_voice and request_voice in _tts_settings.allowed_voice_ids():
        return request_voice
    return store_voice_id


async def _synthesize_elevenlabs(
    text: str, voice_id: str, model_id: str, *, client: httpx.AsyncClient | None = None
) -> bytes:
    """Eine ElevenLabs-Synthese → MP3-Bytes. Wirft ElevenLabsError bei Key-Fehlen,
    Nicht-200-Status oder Netzfehler (→ Edge-Fallback). Secrets/Key werden NIE geloggt."""
    key = _elevenlabs_key()
    if not key:
        raise ElevenLabsError("kein ElevenLabs-Key")

    url = f"{_EL_API_BASE}/v1/text-to-speech/{voice_id}"
    headers = {
        "xi-api-key": key,
        "Content-Type": "application/json",
        "Accept": "audio/mpeg",
    }
    params = {"output_format": _EL_OUTPUT_FORMAT}
    body = {"text": text, "model_id": model_id}

    own_client = client is None
    if own_client:
        client = _build_elevenlabs_client()
    try:
        resp = await client.post(url, headers=headers, params=params, json=body)
    except httpx.HTTPError as exc:
        # Nur Fehlertyp loggen — niemals Header/Key.
        raise ElevenLabsError(f"elevenlabs netzfehler: {type(exc).__name__}") from exc
    finally:
        if own_client:
            await client.aclose()

    if resp.status_code != 200:
        # Kein Response-Body/Key loggen — nur Status (Fallback-Entscheidung).
        raise ElevenLabsError(f"elevenlabs status {resp.status_code}", status=resp.status_code)
    audio = resp.content
    if not audio:
        raise ElevenLabsError("elevenlabs lieferte kein Audio")
    return bytes(audio)


# ---------------------------------------------------------------------------
# Schemas
# ---------------------------------------------------------------------------

class TTSRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=2000, description="Zu sprechender Text")
    voice: str | None = Field(
        default=None,
        description="Edge-Stimme (z. B. de-DE-KatjaNeural). Unbekannte/alte Werte -> Default.",
    )
    was_voice_input: bool = Field(
        default=False,
        description="True wenn der User per Sprache gefragt hat (nur Telemetrie)",
    )


# ---------------------------------------------------------------------------
# Hilfsfunktionen
# ---------------------------------------------------------------------------

_MD_LINK = re.compile(r"\[([^\]]+)\]\([^)]*\)")
_URL = re.compile(r"https?://\S+")
_MD_NOISE = re.compile(r"[*_`~#>|]+")
_BULLET = re.compile(r"^\s*[-•·]\s+", re.MULTILINE)
_WS = re.compile(r"\s+")
_HWS = re.compile(r"[^\S\n]+")      # Whitespace OHNE Zeilenumbruch
_MULTI_NL = re.compile(r"\s*\n\s*")
_ACTION_BLOCK = re.compile(r"DONNA_ACTION:.*", re.DOTALL)
_SENTENCE_SPLIT = re.compile(r"(?<=[.!?])\s+|\n+")
# Emoji/Pictogramme sprechen Neural-Stimmen nicht sinnvoll aus
_EMOJI = re.compile(
    "[\U0001F300-\U0001FAFF\U00002600-\U000027BF\U0001F000-\U0001F2FF️‍]+"
)


def clean_for_speech(text: str, keep_newlines: bool = False) -> str:
    """Markdown/URLs/Emoji entfernen, Masseinheiten ausschreiben — wie die On-Device-Engines.

    `keep_newlines=True` behaelt Zeilenumbrueche (je einer) — damit `split_sentences` zeilenweise Antworten ohne
    Satzzeichen (Listen, Briefings) korrekt in Saetze teilt. Der Endpunkt selbst kollabiert alles zu einer Zeile.
    """
    t = _ACTION_BLOCK.sub("", text or "")
    t = _MD_LINK.sub(r"\1", t)
    t = _URL.sub("", t)
    t = _BULLET.sub("", t)
    t = _MD_NOISE.sub("", t)
    t = _EMOJI.sub("", t)
    t = re.sub(r"(\d+)\s*°\s*C", r"\1 Grad", t)
    t = re.sub(r"(\d+)\s*°", r"\1 Grad", t)
    t = re.sub(r"(\d+)\s*%", r"\1 Prozent", t)
    t = t.replace("km/h", "Kilometer pro Stunde").replace("m/s", "Meter pro Sekunde")
    if keep_newlines:
        return _MULTI_NL.sub(chr(10), _HWS.sub(" ", t)).strip()
    return _WS.sub(" ", t).strip()


def resolve_voice(voice: str | None) -> str:
    """Nur bekannte weibliche Edge-Stimmen; alles andere (auch alte Piper-IDs) -> Default."""
    if voice and voice in EDGE_ALLOWED_VOICES:
        return voice
    return EDGE_DEFAULT_VOICE


def split_sentences(text: str) -> list[str]:
    return [s.strip() for s in _SENTENCE_SPLIT.split(text) if s and s.strip()]


def _get_semaphore() -> asyncio.Semaphore:
    global _semaphore
    if _semaphore is None:
        _semaphore = asyncio.Semaphore(MAX_CONCURRENT_SYNTH)
    return _semaphore


def _cache_get(key: tuple[str, str]) -> bytes | None:
    hit = _cache.get(key)
    if hit is None:
        return None
    ts, audio = hit
    if time.monotonic() - ts > _CACHE_TTL_S:
        _cache.pop(key, None)
        return None
    _cache.move_to_end(key)
    return audio


def _cache_put(key: tuple[str, str], audio: bytes) -> None:
    _cache[key] = (time.monotonic(), audio)
    _cache.move_to_end(key)
    while len(_cache) > _CACHE_MAX_ENTRIES:
        _cache.popitem(last=False)


async def _synthesize_edge(text: str, voice: str) -> bytes:
    """Eine Edge-TTS-Synthese -> MP3-Bytes. edge-tts wird lazy importiert (Tests mocken diese Funktion)."""
    try:
        import edge_tts  # noqa: PLC0415
    except ImportError as exc:  # pragma: no cover - nur ohne installiertes Paket
        raise TTSUnavailable("edge-tts nicht installiert") from exc

    audio = bytearray()
    try:
        rate = _VOICE_RATE.get(voice, _NEUTRAL_RATE)
        communicate = edge_tts.Communicate(text, voice, rate=rate)
        async for chunk in communicate.stream():
            if chunk.get("type") == "audio":
                audio.extend(chunk["data"])
    except Exception as exc:  # noqa: BLE001 - edge-tts wirft diverse Netz-/Protokollfehler
        raise TTSUnavailable(f"edge-tts Fehler: {type(exc).__name__}") from exc
    if not audio:
        raise TTSUnavailable("edge-tts lieferte kein Audio")
    return bytes(audio)


async def _dispatch_synthesis(voice: str, text: str) -> bytes:
    """Wählt die Engine anhand des (provider-qualifizierten) Cache-Key-„voice"-Werts:
    'el:<voice_id>:<model_id>' → ElevenLabs, sonst Edge-Stimmenname → Edge."""
    if voice.startswith(_EL_VOICE_PREFIX):
        _, voice_id, model_id = voice.split(":", 2)
        return await _synthesize_elevenlabs(text, voice_id, model_id)
    return await _synthesize_edge(text, voice)


async def _synthesize_and_store(key: tuple[str, str]) -> bytes:
    voice, text = key
    # EL-Pfad: 6s-Deckel (Fix-Runde A #3); Edge: unveraendert SYNTH_TIMEOUT_S.
    timeout = _EL_TIMEOUT_S if voice.startswith(_EL_VOICE_PREFIX) else SYNTH_TIMEOUT_S
    async with _get_semaphore():
        audio = await asyncio.wait_for(_dispatch_synthesis(voice, text), timeout=timeout)
    _cache_put(key, audio)
    return audio


async def synthesize_tts_audio(
    text: str,
    *,
    edge_voice: str,
    el_voice_id: str | None = None,
    model_id: str | None = None,
) -> tuple[bytes, str]:
    """Provider-Router. Rückgabe: (audio, provider) mit provider ∈ {"elevenlabs","edge"}
    — der TATSÄCHLICH liefernde Provider (für Observability/Fallback-Quote, Fix-Runde A #5).

    ElevenLabs primär, wenn Key + voice/model vorhanden UND kein aktiver Backoff. Bei
    JEDEM unerwarteten Fehler aus dem EL-Pfad (nicht nur ElevenLabsError/Timeout — auch
    z.B. ein ValueError beim Header-Encoding) → Edge-Fallback (Fix-Runde A #1). Nur
    BaseException (CancelledError/KeyboardInterrupt) wird NICHT geschluckt.
    Nach 401/403/429 startet ein ~60s-Backoff (Fix-Runde A #4) → direkt Edge.
    Solange Edge geht, failt /tts nie hart."""
    if _elevenlabs_key() and el_voice_id and model_id and not _el_in_backoff():
        try:
            audio = await synthesize_cached(text, _el_voice_key(el_voice_id, model_id))
            return audio, "elevenlabs"
        except BaseException as exc:  # noqa: BLE001
            if isinstance(exc, (asyncio.CancelledError, KeyboardInterrupt, SystemExit)):
                raise
            status = getattr(exc, "status", None)
            _el_note_failure(status)  # Backoff nur bei 401/403/429 (intern geprüft)
            # Nur Status/Fehlertyp loggen — nie Key/Secret.
            log.warning(
                "tts_elevenlabs_fallback_edge",
                status=status,
                error=type(exc).__name__,
            )
    return await synthesize_cached(text, edge_voice), "edge"


async def synthesize_cached(text: str, voice: str) -> bytes:
    """Audio fuer (voice, bereinigter Text) — Cache, dann laufende Synthese teilen, sonst neu."""
    key = (voice, text)
    hit = _cache_get(key)
    if hit is not None:
        return hit
    fut = _inflight.get(key)
    if fut is None:
        fut = asyncio.ensure_future(_synthesize_and_store(key))
        _inflight[key] = fut

        def _done(f: "asyncio.Future[bytes]", k: tuple[str, str] = key) -> None:
            _inflight.pop(k, None)
            if not f.cancelled():
                f.exception()  # Exception als "abgeholt" markieren (kein Task-Warning)

        fut.add_done_callback(_done)
    # shield: bricht ein Client ab, laeuft die Synthese fuer den Cache weiter
    return await asyncio.shield(fut)


# ---------------------------------------------------------------------------
# Pre-Synthese (DONNA-137): chat.py ruft dies nach der Antwort — Fire-and-Forget
# ---------------------------------------------------------------------------

async def pre_synthesize(text: str, voice: str | None = None) -> None:
    """Saetze der Antwort vorab synthetisieren und cachen (best effort, wirft nie).

    Aufrufer (chat.py) ruft das NUR, wenn der Client Edge gewaehlt hat — und uebergibt dessen Stimme, sonst
    gaebe es Cache-Misses (Seraphina) und unnoetige Edge-Last. Bei Piper-Wahl geht nichts an Microsoft.

    Der Android-Client ruft /tts satzweise auf; trifft er auf gecachtes Audio, entfaellt der
    Edge-Roundtrip (~0,4-1,2 s). Schluckt alle Fehler — Pre-Synthese darf den Chat nie stoeren.
    """
    try:
        # Zeilenumbrueche zuerst als Satzgrenzen nutzen, dann jeden Satz EXAKT so normalisieren wie der
        # Endpunkt (kollabierte Einzeile) — nur dann trifft der spaetere /tts-Request den Cache.
        all_sentences = [
            c for c in (clean_for_speech(x) for x in split_sentences(clean_for_speech(text, keep_newlines=True))) if c
        ]
        if not all_sentences:
            return

        # ElevenLabs aktiv? → credit-sparsam: NUR den ERSTEN Satz vorab synthetisieren,
        # der Rest wird on-demand geholt (6 Sätze blind vorab würden ungehörte Credits verbrennen).
        if _elevenlabs_key():
            store = _tts_settings.load_settings()
            el_voice = _resolve_elevenlabs_voice(voice, store.voice_id)
            el_key = _el_voice_key(el_voice, store.model_id)
            try:
                await synthesize_cached(all_sentences[0], el_key)
            except Exception:  # noqa: BLE001 - best effort; Client faellt sonst auf Live-Synthese zurueck
                log.debug("tts_pre_synthesize_elevenlabs_first_failed")
            log.debug("tts_pre_synthesize_done_elevenlabs", sentences_total=len(all_sentences), pre=1)
            return

        # Edge (kein Key): unveraendertes Verhalten — bis PRE_SYNTH_MAX_SENTENCES Saetze.
        resolved = resolve_voice(voice)
        sentences = all_sentences[:PRE_SYNTH_MAX_SENTENCES]
        # Latenz (DONNA-Voice): Der Client fragt /tts STRIKT der Reihe nach ab — der erste Satz zuerst, und genau
        # dessen Wartezeit entscheidet, wie schnell Donna zu sprechen beginnt. Darum den ERSTEN Satz zuerst
        # synthetisieren (await) und erst DANACH den Rest parallel: so ist der Cache fuer die erste Anfrage mit
        # Sicherheit warm, statt mit den uebrigen Saetzen um das Edge-Semaphor zu konkurrieren.
        first, rest = sentences[0], sentences[1:]
        failed = 0
        try:
            await synthesize_cached(first, resolved)
        except Exception:  # noqa: BLE001 - best effort, der Client faellt sonst auf Live-Synthese zurueck
            failed += 1
        if rest:
            results = await asyncio.gather(
                *(synthesize_cached(s, resolved) for s in rest), return_exceptions=True
            )
            failed += sum(1 for r in results if isinstance(r, BaseException))
        log.debug("tts_pre_synthesize_done", sentences=len(sentences), failed=failed)
    except Exception as exc:  # noqa: BLE001
        log.debug("tts_pre_synthesize_failed", error=type(exc).__name__)


# ---------------------------------------------------------------------------
# Endpoint
# ---------------------------------------------------------------------------

@router.post("", response_class=Response)
async def synthesize_tts(
    body: TTSRequest,
    voice: str | None = Query(default=None, description="Edge-Stimme; ueberschreibt das Body-Feld"),
    _admin: str = Depends(require_admin),
) -> Response:
    """Text -> MP3. Provider: ElevenLabs primär (wenn Key), Edge als Fallback.
    204 bei nichts Sprechbarem, 503/504 nur wenn AUCH Edge ausfaellt/timeoutet.

    Stimme/Modell: Default aus dem TTS-Settings-Store (Eva + v4 Turbo). Request-`voice`
    darf die Stimme weiter ueberschreiben (Edge-Stimmenname fuer den Fallback; eine der
    beiden ElevenLabs-IDs ueberschreibt auch die Cloud-Stimme). Das Modell kommt aus dem Store.
    """
    clean = clean_for_speech(body.text)
    if not clean:
        return Response(status_code=204)

    store = _tts_settings.load_settings()  # fail-safe (korrupt/fehlend → Default)
    req_voice = voice or body.voice
    edge_voice = resolve_voice(req_voice)
    el_voice_id = _resolve_elevenlabs_voice(req_voice, store.voice_id)

    started = time.monotonic()
    try:
        audio, provider = await synthesize_tts_audio(
            clean, edge_voice=edge_voice, el_voice_id=el_voice_id, model_id=store.model_id,
        )
    except asyncio.TimeoutError:
        log.warning("tts_timeout", edge_voice=edge_voice, chars=len(clean))
        return Response(
            status_code=504, content=b"TTS Timeout", media_type="text/plain",
        )
    except TTSUnavailable as exc:
        log.warning("tts_unavailable", edge_voice=edge_voice, error=str(exc))
        return Response(
            status_code=503, content=b"TTS nicht verfuegbar", media_type="text/plain",
        )

    log.info(
        "tts_ok",
        provider=provider,  # "elevenlabs" | "edge" — Fallback-Quote sichtbar (Fix-Runde A #5)
        edge_voice=edge_voice,
        chars=len(clean),
        bytes=len(audio),
        ms=int((time.monotonic() - started) * 1000),
        was_voice_input=body.was_voice_input,
    )
    return Response(
        content=audio,
        media_type="audio/mpeg",
        headers={"Cache-Control": "no-store"},
    )

"""Gemini API client — uses the modern google-genai SDK (v1.x).

Design: the app MUST boot without GEMINI_API_KEY — only warn. Calls that
actually need the API will raise at call-time, not at startup.

Search grounding: when enable_search=True, Gemini is given the Google Search
tool so it can fetch live data (weather, news, prices, etc.).

DONNA-214: Circuit-Breaker — nach einer 429-Antwort wird Gemini für
CIRCUIT_OPEN_SECONDS gesperrt. Callers können `is_circuit_open()` prüfen
und direkt auf Qwen (Ollama) ausweichen ohne einen kostspieligen API-Aufruf.
"""
from __future__ import annotations

import asyncio
import threading
import time
from typing import Any, Callable, Optional

from app.core.logger import get_logger

log = get_logger("gemini")

# Modelle in Fallback-Reihenfolge — wird bei 429 durchprobiert
# Hinweis: gemini-1.5-* nicht mehr in v1beta API verfügbar (404)
_FALLBACK_MODELS = [
    "gemini-2.5-flash-lite",
    "gemini-2.5-flash",
    "gemini-2.0-flash",
    "gemini-2.0-flash-lite",
]
DEFAULT_MODEL = "gemini-2.5-flash-lite"

# Vision/Multimodal: Default-Modell für Bild+Text. Alle 2.x-flash-Modelle sind multimodal;
# für Bildverständnis ist das volle flash dem -lite leicht überlegen, die Fallback-Kette
# (_FALLBACK_MODELS) wird bei 429 trotzdem durchprobiert.
VISION_MODEL = "gemini-2.5-flash"

# Circuit-Breaker: Nach 1x 429 → Gemini für diese viele Sekunden sperren
CIRCUIT_OPEN_SECONDS: int = 300  # 5 Minuten


def _sniff_image_mime(image_bytes: bytes) -> str:
    """Best-effort MIME-Erkennung aus Magic Bytes. Default image/jpeg (Live-Bubble sendet JPEG)."""
    if image_bytes[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if image_bytes[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if image_bytes[:6] in (b"GIF87a", b"GIF89a"):
        return "image/gif"
    if image_bytes[:4] == b"RIFF" and image_bytes[8:12] == b"WEBP":
        return "image/webp"
    return "image/jpeg"


class GeminiNotConfiguredError(RuntimeError):
    """Raised when a Gemini call is attempted without an API key."""


class GeminiResourceExhaustedError(RuntimeError):
    """Raised when ALL Gemini models respond with 429 RESOURCE_EXHAUSTED.

    Callers should catch this and route to Qwen/Ollama instead.
    """


class GeminiClient:
    """Lazy wrapper around google-genai (v1.x).

    - ready() is True only when a key is present AND the SDK is importable.
    - generate() raises GeminiNotConfiguredError if the key is missing.
    - generate() raises GeminiResourceExhaustedError if all models hit 429.
    - enable_search=True activates Google Search grounding for live data.
    - Circuit-Breaker: After first 429, blocks all calls for CIRCUIT_OPEN_SECONDS.
      Check is_circuit_open() before calling to route directly to Qwen fallback.
    """

    def __init__(self, api_key: Optional[str], model: str = DEFAULT_MODEL) -> None:
        self._api_key = api_key or None
        self._model_name = model
        self._client: Any | None = None
        self._types: Any | None = None

        # DONNA-214: Circuit-Breaker state — thread-safe via Lock
        self._circuit_lock = threading.Lock()
        self._circuit_open_until: float = 0.0  # epoch seconds; 0 = closed

        if not self._api_key:
            log.warning(
                "gemini_api_key_missing",
                detail="GEMINI_API_KEY is empty — Gemini features disabled.",
            )

    def ready(self) -> bool:
        return bool(self._api_key)

    # --- Circuit-Breaker helpers ---

    def is_circuit_open(self) -> bool:
        """True when Gemini is temporarily blocked after a 429 response."""
        with self._circuit_lock:
            if self._circuit_open_until == 0.0:
                return False
            if time.monotonic() >= self._circuit_open_until:
                # Auto-reset: Sperrzeit abgelaufen
                self._circuit_open_until = 0.0
                log.info("gemini_circuit_breaker_reset", after_s=CIRCUIT_OPEN_SECONDS)
                return False
            return True

    @property
    def circuit_open(self) -> bool:
        """Property-Alias für is_circuit_open() — für Router-Checks ohne Methodenaufruf."""
        return self.is_circuit_open()

    def _open_circuit(self) -> None:
        """Trip the circuit breaker — Gemini blocked for CIRCUIT_OPEN_SECONDS."""
        with self._circuit_lock:
            self._circuit_open_until = time.monotonic() + CIRCUIT_OPEN_SECONDS
        log.warning(
            "gemini_circuit_breaker_open",
            blocked_for_s=CIRCUIT_OPEN_SECONDS,
            detail="Alle Gemini-Modelle haben 429 — Fallback auf Qwen für 5 Minuten.",
        )

    def _ensure_client(self) -> tuple[Any, Any]:
        """Returns (client, types) — lazy init. Raises GeminiNotConfiguredError."""
        if self._client is not None and self._types is not None:
            return self._client, self._types
        if not self._api_key:
            raise GeminiNotConfiguredError(
                "GEMINI_API_KEY not set — cannot call Gemini API."
            )
        try:
            from google import genai  # type: ignore
            from google.genai import types  # type: ignore
        except ImportError as e:
            raise GeminiNotConfiguredError(
                "google-genai package not installed."
            ) from e

        self._client = genai.Client(api_key=self._api_key)
        self._types = types
        log.info("gemini_configured", model=self._model_name)
        return self._client, self._types

    def generate(
        self,
        prompt: str,
        *,
        model: str | None = None,
        enable_search: bool = False,
        on_rate_limited: Callable[[str, int], None] | None = None,
    ) -> str:
        """Synchronous text generation. Raises GeminiNotConfiguredError if no key.

        Raises:
            GeminiNotConfiguredError: API key missing or SDK not installed.
            GeminiResourceExhaustedError: All models returned 429 — circuit open.
                Caller should fall back to Qwen/Ollama.

        Args:
            prompt: The full prompt (system + user message).
            model: Override model name. Uses configured default if None.
            enable_search: If True, attaches the Google Search grounding tool
                so Gemini can fetch live data (weather, news, prices, …).
            on_rate_limited: Optional callback called when a model returns 429.
                Signature: (model_name: str, attempt_index: int) -> None.
                Called from the worker thread — must be thread-safe.
        """
        # DONNA-214: Circuit-Breaker check — skip expensive API call immediately
        if self.is_circuit_open():
            raise GeminiResourceExhaustedError(
                "Gemini Circuit-Breaker offen — Fallback auf Qwen aktiv."
            )

        client, types = self._ensure_client()
        model_name = model or self._model_name

        config_kwargs: dict[str, Any] = {}
        if enable_search:
            try:
                config_kwargs["tools"] = [
                    types.Tool(google_search=types.GoogleSearch())
                ]
                log.debug("gemini_search_grounding_enabled", model=model_name)
            except Exception as e:
                log.warning(
                    "gemini_search_grounding_unavailable",
                    model=model_name,
                    error=str(e),
                )

        # Modell-Fallback: bei 429 nächstes Modell probieren
        models_to_try = [model_name] + [m for m in _FALLBACK_MODELS if m != model_name]
        last_exc: Exception | None = None

        for attempt_idx, attempt_model in enumerate(models_to_try):
            try:
                config = types.GenerateContentConfig(**config_kwargs) if config_kwargs else None
                resp = client.models.generate_content(
                    model=attempt_model,
                    contents=prompt,
                    config=config,
                )
                if attempt_model != model_name:
                    log.info("gemini_fallback_model_used", model=attempt_model)
                return resp.text or ""
            except Exception as exc:
                err_str = str(exc)
                if "429" in err_str or "RESOURCE_EXHAUSTED" in err_str:
                    log.warning("gemini_quota_exhausted", model=attempt_model, attempt=attempt_idx)
                    if on_rate_limited is not None:
                        try:
                            on_rate_limited(attempt_model, attempt_idx)
                        except Exception:
                            pass
                    last_exc = exc
                    continue  # nächstes Modell
                # Kein 429 → wenn Search fehlschlug, einmal ohne Suche retry
                if enable_search and config_kwargs:
                    log.warning("gemini_search_failed_retry_without", error=err_str, model=attempt_model)
                    try:
                        resp = client.models.generate_content(model=attempt_model, contents=prompt)
                        return resp.text or ""
                    except Exception:
                        pass
                raise exc

        # Alle Modelle erschöpft → Circuit-Breaker auslösen
        self._open_circuit()
        log.error("gemini_all_models_quota_exhausted")
        raise GeminiResourceExhaustedError(
            "Alle Gemini-Modelle haben 429 geantwortet — Circuit offen, Fallback auf Qwen."
        ) from last_exc

    async def generate_async(
        self,
        prompt: str,
        *,
        model: str | None = None,
        enable_search: bool = False,
        timeout_s: int | None = None,
    ) -> str:
        """Async wrapper around generate() — runs in a thread executor.

        Allows async code to call Gemini without blocking the event loop.
        Same signature as generate() for drop-in use.

        Args:
            timeout_s: Timeout in Sekunden. Falls None, wird settings.gemini_timeout_s
                       verwendet (default 45s). Bei Überschreitung wird asyncio.TimeoutError
                       geraised → triggert Fallback in chat.py.
        """
        from app.config import get_settings

        effective_timeout = timeout_s if timeout_s is not None else get_settings().gemini_timeout_s
        loop = asyncio.get_event_loop()
        try:
            return await asyncio.wait_for(
                loop.run_in_executor(
                    None,
                    lambda: self.generate(prompt, model=model, enable_search=enable_search),
                ),
                timeout=effective_timeout,
            )
        except asyncio.TimeoutError:
            log.warning("gemini_timeout", timeout_s=effective_timeout, model=model or self._model_name)
            raise

    def generate_multimodal(
        self,
        prompt: str,
        image_bytes: bytes,
        *,
        mime_type: str | None = None,
        model: str | None = None,
        on_rate_limited: Callable[[str, int], None] | None = None,
    ) -> str:
        """Bild + Text → Text. Nutzt das neue google-genai SDK (types.Part.from_bytes).

        Gleiche Circuit-Breaker-/Modell-Fallback-Semantik wie generate(). Das Bild wird
        ausschließlich als Teil des API-Requests versendet und danach verworfen — es wird
        NIE gespeichert und hier NIE geloggt (der Aufrufer loggt höchstens die Größe).

        Raises:
            GeminiNotConfiguredError: API-Key fehlt oder SDK nicht installiert.
            GeminiResourceExhaustedError: Alle Modelle 429 (oder Circuit offen) → Caller-Fallback.
            ValueError: image_bytes leer.
        """
        if self.is_circuit_open():
            raise GeminiResourceExhaustedError(
                "Gemini Circuit-Breaker offen — Fallback auf Qwen aktiv."
            )
        if not image_bytes:
            raise ValueError("generate_multimodal: image_bytes ist leer")

        client, types = self._ensure_client()
        model_name = model or VISION_MODEL
        mime = mime_type or _sniff_image_mime(image_bytes)
        image_part = types.Part.from_bytes(data=image_bytes, mime_type=mime)

        # Reine Bildbeschreibung braucht kein 2.5-Thinking → spart Latenz/Tokens.
        # Defensiv: fehlt das SDK-Feld, läuft der Call ohne Config weiter.
        vision_config: Any | None = None
        try:
            vision_config = types.GenerateContentConfig(
                thinking_config=types.ThinkingConfig(thinking_budget=0)
            )
        except Exception:  # noqa: BLE001
            vision_config = None

        models_to_try = [model_name] + [m for m in _FALLBACK_MODELS if m != model_name]
        last_exc: Exception | None = None

        for attempt_idx, attempt_model in enumerate(models_to_try):
            try:
                resp = client.models.generate_content(
                    model=attempt_model,
                    contents=[image_part, prompt],
                    config=vision_config,
                )
                if attempt_model != model_name:
                    log.info("gemini_vision_fallback_model_used", model=attempt_model)
                return resp.text or ""
            except Exception as exc:
                err_str = str(exc)
                if "429" in err_str or "RESOURCE_EXHAUSTED" in err_str:
                    log.warning("gemini_vision_quota_exhausted", model=attempt_model, attempt=attempt_idx)
                    if on_rate_limited is not None:
                        try:
                            on_rate_limited(attempt_model, attempt_idx)
                        except Exception:
                            pass
                    last_exc = exc
                    continue  # nächstes Modell
                raise exc

        self._open_circuit()
        log.error("gemini_vision_all_models_quota_exhausted")
        raise GeminiResourceExhaustedError(
            "Alle Gemini-Modelle haben 429 geantwortet (Vision) — Circuit offen, Fallback auf Qwen."
        ) from last_exc

    async def generate_multimodal_async(
        self,
        prompt: str,
        image_bytes: bytes,
        *,
        mime_type: str | None = None,
        model: str | None = None,
        timeout_s: int | None = None,
    ) -> str:
        """Async-Wrapper um generate_multimodal() — läuft in einem Thread-Executor."""
        from app.config import get_settings

        effective_timeout = timeout_s if timeout_s is not None else get_settings().gemini_timeout_s
        loop = asyncio.get_event_loop()
        try:
            return await asyncio.wait_for(
                loop.run_in_executor(
                    None,
                    lambda: self.generate_multimodal(
                        prompt, image_bytes, mime_type=mime_type, model=model
                    ),
                ),
                timeout=effective_timeout,
            )
        except asyncio.TimeoutError:
            log.warning("gemini_vision_timeout", timeout_s=effective_timeout)
            raise

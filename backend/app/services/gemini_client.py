"""Gemini API client — uses the modern google-genai SDK (v1.x).

Design: the app MUST boot without GEMINI_API_KEY — only warn. Calls that
actually need the API will raise at call-time, not at startup.

Search grounding: when enable_search=True, Gemini is given the Google Search
tool so it can fetch live data (weather, news, prices, etc.).
"""
from __future__ import annotations

import asyncio
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


class GeminiNotConfiguredError(RuntimeError):
    """Raised when a Gemini call is attempted without an API key."""


class GeminiClient:
    """Lazy wrapper around google-genai (v1.x).

    - ready() is True only when a key is present AND the SDK is importable.
    - generate() raises GeminiNotConfiguredError if the key is missing.
    - enable_search=True activates Google Search grounding for live data.
    """

    def __init__(self, api_key: Optional[str], model: str = DEFAULT_MODEL) -> None:
        self._api_key = api_key or None
        self._model_name = model
        self._client: Any | None = None
        self._types: Any | None = None

        if not self._api_key:
            log.warning(
                "gemini_api_key_missing",
                detail="GEMINI_API_KEY is empty — Gemini features disabled.",
            )

    def ready(self) -> bool:
        return bool(self._api_key)

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

        Args:
            prompt: The full prompt (system + user message).
            model: Override model name. Uses configured default if None.
            enable_search: If True, attaches the Google Search grounding tool
                so Gemini can fetch live data (weather, news, prices, …).
            on_rate_limited: Optional callback called when a model returns 429.
                Signature: (model_name: str, attempt_index: int) -> None.
                Called from the worker thread — must be thread-safe.
        """
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

        # Alle Modelle erschöpft
        log.error("gemini_all_models_quota_exhausted")
        raise last_exc or RuntimeError("Alle Gemini-Modelle haben ihr Tageslimit erreicht.")

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

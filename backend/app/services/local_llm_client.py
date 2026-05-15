"""Ollama HTTP client with streaming support and explicit fallback warnings.

Contract: on failure, raise LocalLLMUnavailable. The caller (chat route) is
responsible for any Gemini fallback and MUST log + surface the fallback.
There is no silent degradation at this layer.
"""
from __future__ import annotations

import json
from typing import AsyncGenerator

import httpx

from app.core.logger import get_logger

log = get_logger("local_llm")


class LocalLLMUnavailable(RuntimeError):
    """Ollama responded with an error, timed out, or is unreachable."""


class LocalLLMClient:
    """Thin async client for Ollama's /api/chat endpoint."""

    def __init__(
        self,
        *,
        base_url: str,
        model: str,
        timeout_s: int = 60,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._model = model
        self._timeout = float(timeout_s)

    @property
    def model(self) -> str:
        return self._model

    async def health(self) -> bool:
        """True if Ollama responds to /api/tags within the timeout."""
        try:
            async with httpx.AsyncClient(timeout=5.0) as client:
                r = await client.get(f"{self._base_url}/api/tags")
                return r.status_code == 200
        except Exception as e:  # noqa: BLE001
            log.warning("local_llm_health_failed", error=str(e))
            return False

    async def generate(
        self,
        *,
        system: str,
        prompt: str,
        model: str | None = None,
        options: dict | None = None,
    ) -> str:
        """Non-streaming generation. Raises LocalLLMUnavailable on failure.

        Args:
            system: System-Prompt.
            prompt: User-Prompt.
            model: optionaler Override (z.B. 'mistral-nemo:12b' für Twitch).
            options: Ollama Decoding-Optionen wie {temperature, top_p, repeat_penalty}.
                     Beispiel: {"temperature": 0.4, "top_p": 0.85, "repeat_penalty": 1.1}.
        """
        payload: dict = {
            "model": model or self._model,
            "stream": False,
            # keep_alive=30m: Modell bleibt 30 Min nach letztem Request warm
            # im RAM. Verhindert Cold-Start (~10-15s Reload) bei seltenem Twitch-
            # Traffic in Pausen.
            "keep_alive": "30m",
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": prompt},
            ],
        }
        if options:
            payload["options"] = options
        try:
            async with httpx.AsyncClient(timeout=self._timeout) as client:
                r = await client.post(f"{self._base_url}/api/chat", json=payload)
                r.raise_for_status()
                data = r.json()
                return (data.get("message") or {}).get("content", "") or ""
        except httpx.HTTPError as e:
            log.error("local_llm_http_error", error=str(e))
            raise LocalLLMUnavailable(f"Ollama HTTP error: {e}") from e
        except Exception as e:  # noqa: BLE001
            log.error("local_llm_error", error=str(e))
            raise LocalLLMUnavailable(f"Ollama error: {e}") from e

    async def stream(
        self, *, system: str, prompt: str
    ) -> AsyncGenerator[str, None]:
        """Yield token chunks as they arrive. Raises LocalLLMUnavailable on failure."""
        payload = {
            "model": self._model,
            "stream": True,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": prompt},
            ],
        }
        try:
            async with httpx.AsyncClient(timeout=self._timeout) as client:
                async with client.stream(
                    "POST", f"{self._base_url}/api/chat", json=payload
                ) as r:
                    r.raise_for_status()
                    async for line in r.aiter_lines():
                        if not line:
                            continue
                        try:
                            obj = json.loads(line)
                        except json.JSONDecodeError:
                            continue
                        msg = obj.get("message") or {}
                        chunk = msg.get("content", "")
                        if chunk:
                            yield chunk
                        if obj.get("done"):
                            break
        except httpx.HTTPError as e:
            log.error("local_llm_stream_http_error", error=str(e))
            raise LocalLLMUnavailable(f"Ollama stream HTTP error: {e}") from e
        except Exception as e:  # noqa: BLE001
            log.error("local_llm_stream_error", error=str(e))
            raise LocalLLMUnavailable(f"Ollama stream error: {e}") from e

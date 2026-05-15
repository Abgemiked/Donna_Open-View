"""mistral_client.py — Async Mistral AI API client (streaming + non-streaming).

EU-basiert (api.mistral.ai), kein Google-Abhängigkeit.
Ersetzt Gemini als primären Cloud-LLM.
"""
from __future__ import annotations

import json
from typing import AsyncGenerator

import httpx

from app.core.logger import get_logger

log = get_logger("service.mistral")

_BASE_URL = "https://api.mistral.ai/v1"


class MistralNotConfiguredError(RuntimeError):
    """MISTRAL_API_KEY nicht gesetzt."""


class MistralClient:
    """Async Mistral AI client — streaming und non-streaming."""

    def __init__(self, api_key: str | None, model: str = "mistral-small-latest") -> None:
        self._api_key = (api_key or "").strip()
        self._model = model

    def ready(self) -> bool:
        return bool(self._api_key)

    @property
    def model(self) -> str:
        return self._model

    async def generate(
        self,
        *,
        system: str,
        prompt: str,
        history: list[dict[str, str]] | None = None,
    ) -> str:
        """Non-streaming generation. Für Twitch-Fallback und kurze Antworten.

        Wenn `history` übergeben wird, fließt sie als echte multi-turn messages
        in den Mistral-Call (role="user"/"assistant"). Das ist semantisch stärker
        als History als Textblock im User-Prompt zu formatieren.
        """
        if not self._api_key:
            raise MistralNotConfiguredError("MISTRAL_API_KEY nicht konfiguriert")
        messages: list[dict[str, str]] = []
        if system:
            messages.append({"role": "system", "content": system})
        for h in (history or []):
            role = h.get("role")
            content = h.get("content")
            if role in ("user", "assistant") and content:
                messages.append({"role": role, "content": content})
        messages.append({"role": "user", "content": prompt})
        payload = {
            "model": self._model,
            "messages": messages,
            "stream": False,
        }
        try:
            async with httpx.AsyncClient(timeout=30.0) as client:
                r = await client.post(
                    f"{_BASE_URL}/chat/completions",
                    json=payload,
                    headers={
                        "Authorization": f"Bearer {self._api_key}",
                        "Content-Type": "application/json",
                    },
                )
                r.raise_for_status()
                data = r.json()
                return (data["choices"][0]["message"]["content"] or "").strip()
        except MistralNotConfiguredError:
            raise
        except Exception as e:  # noqa: BLE001
            log.error("mistral_generate_error", error=str(e))
            raise

    async def stream(
        self,
        *,
        system: str,
        prompt: str,
        history: list[dict[str, str]] | None = None,
    ) -> AsyncGenerator[str, None]:
        """Streaming generation — liefert Token-Chunks als AsyncGenerator.

        Wenn `history` übergeben wird, wird sie als echte multi-turn messages
        an Mistral gegeben (statt sie textuell in den User-Prompt zu packen).
        """
        if not self._api_key:
            raise MistralNotConfiguredError("MISTRAL_API_KEY nicht konfiguriert")
        messages: list[dict[str, str]] = []
        if system:
            messages.append({"role": "system", "content": system})
        for h in (history or []):
            role = h.get("role")
            content = h.get("content")
            if role in ("user", "assistant") and content:
                messages.append({"role": role, "content": content})
        messages.append({"role": "user", "content": prompt})
        payload = {
            "model": self._model,
            "messages": messages,
            "stream": True,
        }
        try:
            async with httpx.AsyncClient(timeout=60.0) as client:
                async with client.stream(
                    "POST",
                    f"{_BASE_URL}/chat/completions",
                    json=payload,
                    headers={
                        "Authorization": f"Bearer {self._api_key}",
                        "Content-Type": "application/json",
                    },
                ) as r:
                    r.raise_for_status()
                    async for line in r.aiter_lines():
                        if not line or not line.startswith("data: "):
                            continue
                        data_str = line[6:].strip()
                        if data_str == "[DONE]":
                            break
                        try:
                            obj = json.loads(data_str)
                            delta = obj["choices"][0].get("delta", {})
                            content = delta.get("content", "")
                            if content:
                                yield content
                        except (json.JSONDecodeError, KeyError, IndexError):
                            continue
        except MistralNotConfiguredError:
            raise
        except Exception as e:  # noqa: BLE001
            log.error("mistral_stream_error", error=str(e))
            raise

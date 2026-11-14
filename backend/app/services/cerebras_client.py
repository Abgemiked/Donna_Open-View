"""Cerebras Cloud SDK client (Llama 3.3 70B) — Welle-2 Fallback bei Mistral-429.

Cerebras nutzt eine OpenAI-kompatible API — fast identisch zu Mistral.
Wird als sekundäre Cloud-LLM in der Fallback-Chain verwendet:
  Mistral → Cerebras → Gemini → Local
"""
from __future__ import annotations

import json
from typing import AsyncGenerator

import httpx

from app.core.logger import get_logger

log = get_logger("service.cerebras")

_BASE_URL = "https://api.cerebras.ai/v1"


class CerebrasNotConfiguredError(RuntimeError):
    """CEREBRAS_API_KEY nicht gesetzt."""


class CerebrasClient:
    """Async Cerebras Cloud client — OpenAI-compatible streaming + non-streaming."""

    def __init__(self, api_key: str | None, model: str = "llama-3.3-70b") -> None:
        self._api_key = (api_key or "").strip()
        self._model = model

    def ready(self) -> bool:
        return bool(self._api_key)

    @property
    def model(self) -> str:
        return self._model

    def _build_messages(
        self,
        system: str,
        prompt: str,
        history: list[dict[str, str]] | None,
    ) -> list[dict[str, str]]:
        messages: list[dict[str, str]] = []
        if system:
            messages.append({"role": "system", "content": system})
        for h in (history or []):
            role = h.get("role")
            content = h.get("content")
            if role in ("user", "assistant") and content:
                messages.append({"role": role, "content": content})
        messages.append({"role": "user", "content": prompt})
        return messages

    async def generate(
        self,
        *,
        system: str,
        prompt: str,
        history: list[dict[str, str]] | None = None,
        max_tokens: int = 1024,
    ) -> str:
        """Non-streaming generation."""
        if not self._api_key:
            raise CerebrasNotConfiguredError("CEREBRAS_API_KEY nicht konfiguriert")
        payload = {
            "model": self._model,
            "messages": self._build_messages(system, prompt, history),
            "stream": False,
            "max_tokens": max_tokens,
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
        except CerebrasNotConfiguredError:
            raise
        except Exception as e:  # noqa: BLE001
            log.error("cerebras_generate_error", error=str(e))
            raise

    async def stream(
        self,
        *,
        system: str,
        prompt: str,
        history: list[dict[str, str]] | None = None,
        max_tokens: int = 1024,
    ) -> AsyncGenerator[str, None]:
        """Streaming generation — liefert Token-Chunks als AsyncGenerator."""
        if not self._api_key:
            raise CerebrasNotConfiguredError("CEREBRAS_API_KEY nicht konfiguriert")
        payload = {
            "model": self._model,
            "messages": self._build_messages(system, prompt, history),
            "stream": True,
            "max_tokens": max_tokens,
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
        except CerebrasNotConfiguredError:
            raise
        except Exception as e:  # noqa: BLE001
            log.error("cerebras_stream_error", error=str(e))
            raise

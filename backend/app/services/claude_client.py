"""claude_client.py — Async Anthropic Claude client (streaming + non-streaming).

Alleinige Antwort-LLM-Stufe von Donna (Claude-only seit 2026-10-30; Mistral ist
aus dem Antwortpfad entfernt).
Nutzt das offizielle Anthropic Async-SDK (`anthropic.AsyncAnthropic`).

Wichtige Unterschiede zu Mistral:
  - `system` ist ein eigener Top-Level-Parameter (KEINE message mit role="system").
  - Token-Usage kommt aus der finalen Message (`input_tokens` / `output_tokens`).

Web-Suche (Server-Tool `web_search`, laeuft komplett bei Anthropic):
  - `tools=[build_web_search_tool(...)]` an stream()/generate() uebergeben.
  - Quellen (title + url) landen in `sources_out`; Anzahl Suchen in der Usage
    (`web_search_requests`, nur wenn > 0).
  - `stop_reason == "pause_turn"` (Server-Loop-Limit) wird intern fortgesetzt.
"""
from __future__ import annotations

from typing import Any, AsyncGenerator

from anthropic import AsyncAnthropic

from app.core.logger import get_logger

log = get_logger("claude_client")

_DEFAULT_MODEL = "claude-haiku-5-5"

# Basis-Variante der Web-Suche: KEIN Dynamic Filtering (das wuerde Code-Execution
# im Hintergrund starten -> mehr Latenz, eigene Result-Bloecke, nicht ZDR-faehig).
# `allowed_callers=["direct"]` ruft die Suche direkt auf (Pflicht fuer Modelle ohne
# Programmatic Tool Calling, sonst 400). Gilt fuer Haiku 5.5 UND Sonnet 5.5.
WEB_SEARCH_TOOL_TYPE = "web_search_20250305"

# pause_turn: Server-Loop hat sein Iterationslimit erreicht -> Turn unveraendert
# zurueckschicken. Hartes Limit gegen Endlosschleifen (Kosten!).
_MAX_PAUSE_CONTINUATIONS = 3
# Max. Quellen die an den Caller gehen.
_MAX_SOURCES = 5


class ClaudeUnavailable(Exception):
    """Claude-API nicht erreichbar / nicht konfiguriert / SDK-Fehler / Timeout."""


def build_web_search_tool(max_uses: int = 2) -> dict[str, Any]:
    """Tool-Definition fuer die Anthropic-Web-Suche ($10 / 1000 Suchen).

    `max_uses` begrenzt die Suchen pro Request (Kosten + Tempo). Ueberschreitet
    Claude das Limit, kommt ein `max_uses_exceeded`-Ergebnis (kein Exception).
    """
    return {
        "type": WEB_SEARCH_TOOL_TYPE,
        "name": "web_search",
        "max_uses": max(1, int(max_uses)),
        "allowed_callers": ["direct"],
    }


def _is_web_search_disabled_error(exc: BaseException) -> bool:
    """True bei HTTP 400 / invalid_request_error, der auf deaktivierte Web-Suche hindeutet."""
    text = str(exc).lower()
    is_400 = getattr(exc, "status_code", None) == 400 or "invalid_request_error" in text
    mentions_search = "web search" in text or "web_search" in text
    return bool(is_400 and mentions_search)


def _usage_dict(usage: Any) -> dict[str, int]:
    """Normalisiert SDK-Usage-Objekt zu {input_tokens, output_tokens[, web_search_requests]}.

    `web_search_requests` nur wenn > 0 (haelt das Dict fuer Nicht-Such-Calls
    unveraendert).
    """
    out = {
        "input_tokens": int(getattr(usage, "input_tokens", 0) or 0),
        "output_tokens": int(getattr(usage, "output_tokens", 0) or 0),
    }
    stu = getattr(usage, "server_tool_use", None)
    searches = getattr(stu, "web_search_requests", 0) if stu is not None else 0
    try:
        searches = int(searches or 0)
    except (TypeError, ValueError):
        searches = 0
    if searches > 0:
        out["web_search_requests"] = searches
    return out


def _add_usage(total: dict[str, int], part: dict[str, int]) -> None:
    for k, v in part.items():
        total[k] = total.get(k, 0) + v


class _SourceCollector:
    """Sammelt Web-Such-Quellen aus Message-Content-Bloecken (ueber pause_turn hinweg).

    - zitierte Quellen: `citations` (type web_search_result_location: url/title/cited_text)
      der Text-Bloecke -> das, was Claude tatsaechlich benutzt hat.
    - Suchergebnisse: `web_search_tool_result`-Bloecke (content = Liste von
      web_search_result mit url/title) als Fallback, falls nichts zitiert wurde.
    Fehler-Ergebnisse (`content` ist ein Objekt mit error_code) werden nur geloggt.
    """

    def __init__(self) -> None:
        self._cited: dict[str, str] = {}
        self._results: dict[str, str] = {}

    def feed(self, content: Any, model: str = "") -> None:
        for block in content or []:
            btype = getattr(block, "type", None)
            if btype == "text":
                for cit in getattr(block, "citations", None) or []:
                    url = getattr(cit, "url", None)
                    if url and url not in self._cited:
                        self._cited[url] = (getattr(cit, "title", None) or url).strip()
            elif btype == "web_search_tool_result":
                res = getattr(block, "content", None)
                if isinstance(res, list):
                    for item in res:
                        url = getattr(item, "url", None)
                        if url and url not in self._results:
                            self._results[url] = (getattr(item, "title", None) or url).strip()
                elif res is not None:
                    log.warning("claude_web_search_error", model=model,
                                error_code=str(getattr(res, "error_code", res)))

    def sources(self) -> list[dict[str, str]]:
        src = self._cited or dict(list(self._results.items())[:3])
        return [{"title": t, "url": u} for u, t in list(src.items())[:_MAX_SOURCES]]


class ClaudeClient:
    """Async Anthropic client — streaming und non-streaming."""

    def __init__(
        self,
        api_key: str | None,
        model: str = _DEFAULT_MODEL,
        timeout_s: float = 60.0,
        max_tokens: int = 1024,
    ) -> None:
        self._api_key = (api_key or "").strip()
        self._model = model
        self._timeout_s = float(timeout_s)
        self._max_tokens = int(max_tokens)
        self._client: AsyncAnthropic | None = None
        # Usage des zuletzt abgeschlossenen stream()-Calls (für Logging/Kosten).
        self.last_usage: dict[str, int] = {"input_tokens": 0, "output_tokens": 0}

    # ---- Status ----------------------------------------------------------------

    def ready(self) -> bool:
        return bool(self._api_key)

    @property
    def model(self) -> str:
        return self._model

    def _ensure_client(self) -> AsyncAnthropic:
        if not self._api_key:
            raise ClaudeUnavailable("ANTHROPIC_API_KEY nicht konfiguriert")
        if self._client is None:
            self._client = AsyncAnthropic(
                api_key=self._api_key,
                timeout=self._timeout_s,
                max_retries=1,
            )
        return self._client

    # ---- Request-Aufbau --------------------------------------------------------

    def _build_kwargs(
        self,
        *,
        system: str,
        prompt: str,
        history: list[dict[str, str]] | None,
        model: str | None,
        max_tokens: int | None,
        tools: list[dict[str, Any]] | None = None,
    ) -> dict[str, Any]:
        messages: list[dict[str, Any]] = []
        for h in (history or []):
            role = h.get("role")
            content = h.get("content")
            if role not in ("user", "assistant") or not content:
                continue
            # Anthropic verlangt, dass die erste Message role="user" hat.
            if not messages and role != "user":
                continue
            messages.append({"role": role, "content": content})
        messages.append({"role": "user", "content": prompt})

        kwargs: dict[str, Any] = {
            "model": model or self._model,
            "max_tokens": int(max_tokens or self._max_tokens),
            "messages": messages,
            # Thinking ist bei den 5.5-Modellen standardmaessig an und zaehlt gegen
            # max_tokens. Fuer schnelle Chat-/Sprachantworten: niedriger Effort
            # (minimales Thinking) -> mehr Budget fuer die eigentliche Antwort + Tempo.
            "output_config": {"effort": "low"},
        }
        if system:
            kwargs["system"] = system
        if tools:
            kwargs["tools"] = tools
        return kwargs

    # ---- Public API ------------------------------------------------------------

    async def generate(
        self,
        *,
        system: str,
        prompt: str,
        history: list[dict[str, str]] | None = None,
        model: str | None = None,
        max_tokens: int | None = None,
        tools: list[dict[str, Any]] | None = None,
        sources_out: list[dict[str, str]] | None = None,
    ) -> tuple[str, dict[str, int]]:
        """Non-streaming generation. Gibt (text, usage) zurück.

        usage = {"input_tokens": int, "output_tokens": int[, "web_search_requests": int]}
        Mit `tools` (z.B. Web-Suche) wird `pause_turn` intern fortgesetzt; Quellen
        werden in `sources_out` (in-place) abgelegt.
        """
        client = self._ensure_client()
        kwargs = self._build_kwargs(
            system=system, prompt=prompt, history=history, model=model,
            max_tokens=max_tokens, tools=tools,
        )
        collector = _SourceCollector()
        usage: dict[str, int] = {"input_tokens": 0, "output_tokens": 0}
        text_parts: list[str] = []
        stop = None
        retried_without_tools = False
        orig_messages = list(kwargs["messages"])
        for attempt in range(_MAX_PAUSE_CONTINUATIONS + 1):
            try:
                try:
                    msg = await client.messages.create(**kwargs)
                except Exception as e:  # noqa: BLE001
                    if (
                        kwargs.get("tools")
                        and not any(text_parts)
                        and not retried_without_tools
                        and _is_web_search_disabled_error(e)
                    ):
                        # Web-Suche in der Console deaktiviert -> EINMAL ohne Tools wiederholen.
                        log.warning("web_search_disabled_retry", model=kwargs["model"], error=str(e))
                        retried_without_tools = True
                        kwargs.pop("tools", None)
                        kwargs["messages"] = list(orig_messages)
                        tools = None
                        msg = await client.messages.create(**kwargs)
                    else:
                        raise
            except ClaudeUnavailable:
                raise
            except Exception as e:  # noqa: BLE001
                log.error("claude_generate_error", model=kwargs["model"], error=str(e))
                raise ClaudeUnavailable(str(e)) from e

            stop = getattr(msg, "stop_reason", None)
            if stop == "refusal":
                log.warning("claude_refusal", model=kwargs["model"],
                            detail=str(getattr(msg, "stop_details", None)))
                raise ClaudeUnavailable("claude_refusal")
            _add_usage(usage, _usage_dict(getattr(msg, "usage", None)))
            text_parts.append("".join(
                getattr(b, "text", "") for b in msg.content if getattr(b, "type", None) == "text"
            ))
            collector.feed(msg.content, kwargs["model"])
            if stop == "pause_turn" and tools and attempt < _MAX_PAUSE_CONTINUATIONS:
                # Server-Loop-Limit: Turn unveraendert zurueckschicken (KEIN extra "Weiter").
                kwargs["messages"] = [
                    *kwargs["messages"], {"role": "assistant", "content": msg.content}
                ]
                continue
            break
        if stop == "pause_turn":
            log.warning("claude_pause_turn_limit", model=kwargs["model"])
        text = "".join(text_parts).strip()
        if not text:
            # z.B. wenn Thinking das gesamte max_tokens-Budget verbraucht hat
            log.warning("claude_empty_response", model=kwargs["model"], stop_reason=stop)
            raise ClaudeUnavailable(f"claude_empty_response (stop={stop})")
        self.last_usage = usage
        if sources_out is not None:
            sources_out.extend(collector.sources())
        return text, usage

    async def stream(
        self,
        *,
        system: str,
        prompt: str,
        history: list[dict[str, str]] | None = None,
        model: str | None = None,
        max_tokens: int | None = None,
        usage_out: dict[str, int] | None = None,
        tools: list[dict[str, Any]] | None = None,
        sources_out: list[dict[str, str]] | None = None,
    ) -> AsyncGenerator[str, None]:
        """Streaming generation — liefert Text-Chunks.

        Nach Abschluss steht die Token-Usage in `self.last_usage`. Wer parallele
        Requests fahren kann, übergibt zusätzlich `usage_out` (wird in-place mit
        derselben Usage befüllt — race-frei, anders als das geteilte Attribut).
        Bei SDK-Fehlern/Timeout wird `ClaudeUnavailable` geworfen (auch mitten
        im Stream — der Caller entscheidet anhand bereits gesendeter Chunks,
        ob ein Fallback sinnvoll ist).

        `tools`: z.B. `[build_web_search_tool()]`. Server-Tools laufen bei Anthropic;
        im Stream erscheinen nur die Text-Deltas (`text_stream`). Nach dem Stream
        liegen die Quellen (`{"title","url"}`, zitierte zuerst) in `sources_out`
        und die Suchanzahl in der Usage (`web_search_requests`). Bei
        `stop_reason == "pause_turn"` wird der Turn automatisch fortgesetzt (max.
        `_MAX_PAUSE_CONTINUATIONS`), die Chunks laufen nahtlos weiter.
        """
        client = self._ensure_client()
        kwargs = self._build_kwargs(
            system=system, prompt=prompt, history=history, model=model,
            max_tokens=max_tokens, tools=tools,
        )
        self.last_usage = {"input_tokens": 0, "output_tokens": 0}
        collector = _SourceCollector()
        total: dict[str, int] = {"input_tokens": 0, "output_tokens": 0}
        yielded = False
        retried_without_tools = False
        orig_messages = list(kwargs["messages"])
        while True:
            try:
                for attempt in range(_MAX_PAUSE_CONTINUATIONS + 1):
                    async with client.messages.stream(**kwargs) as stream:
                        async for text in stream.text_stream:
                            if text:
                                yielded = True
                                yield text
                        final = await stream.get_final_message()
                    _add_usage(total, _usage_dict(getattr(final, "usage", None)))
                    self.last_usage = dict(total)
                    if usage_out is not None:
                        usage_out.update(self.last_usage)
                    collector.feed(getattr(final, "content", None), kwargs["model"])
                    stop = getattr(final, "stop_reason", None)
                    if stop == "refusal":
                        log.warning("claude_refusal", model=kwargs["model"],
                                    detail=str(getattr(final, "stop_details", None)))
                        raise ClaudeUnavailable("claude_refusal")
                    if stop == "max_tokens":
                        log.warning("claude_truncated_max_tokens", model=kwargs["model"])
                    if stop == "pause_turn":
                        if tools and attempt < _MAX_PAUSE_CONTINUATIONS:
                            # Server-Loop-Limit: Turn unveraendert anhaengen und weiterstreamen.
                            kwargs["messages"] = [
                                *kwargs["messages"],
                                {"role": "assistant", "content": final.content},
                            ]
                            continue
                        log.warning("claude_pause_turn_limit", model=kwargs["model"])
                    break
                if sources_out is not None:
                    sources_out.extend(collector.sources())
                return
            except ClaudeUnavailable:
                raise
            except Exception as e:  # noqa: BLE001
                if (
                    kwargs.get("tools")
                    and not yielded
                    and not retried_without_tools
                    and _is_web_search_disabled_error(e)
                ):
                    # Web-Suche in der Console deaktiviert -> EINMAL ohne Tools wiederholen,
                    # damit der Chat trotzdem antwortet.
                    log.warning("web_search_disabled_retry", model=kwargs["model"], error=str(e))
                    retried_without_tools = True
                    kwargs.pop("tools", None)
                    kwargs["messages"] = list(orig_messages)
                    tools = None
                    continue
                log.error("claude_stream_error", model=kwargs["model"], error=str(e))
                raise ClaudeUnavailable(str(e)) from e

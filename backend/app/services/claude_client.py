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

import time
from typing import Any, AsyncGenerator, Callable

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
# tool_use (Client-Tools, Ebene-2-Migration): Max. Round-Trips (assistant tool_use ->
# tool_result -> weiter) pro stream()-Call. Eigener Zaehler, getrennt von pause_turn.
# Hartes Limit gegen Tool-Schleifen (Kosten + Latenz).
_MAX_TOOL_ROUNDS = 6   # Phase D: list_smart_home + bis zu 5 sequenzielle control_home + Text
# #91: Mindestzahl Nicht-Leerraum-Zeichen, die in einer Runde gestreamt sein muessen, damit eine
# reine Karten-Tool-Runde (terminal_tool_names) ohne Folgerunde enden darf.
_TERMINAL_MIN_TEXT_CHARS = 12
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


class CachedSystem(str):
    """System-Prompt (normaler str) mit Markierung: die ersten `cache_prefix_chars` Zeichen sind ueber
    Anfragen hinweg gleich (#152, Prompt-Zwischenspeicher). Alle str-Operationen bleiben unveraendert."""

    cache_prefix_chars: int = 0

    def __new__(cls, text: str, cache_prefix_chars: int) -> "CachedSystem":
        obj = super().__new__(cls, text)
        obj.cache_prefix_chars = int(cache_prefix_chars)
        return obj


def _system_param(system: str) -> Any:
    """`system` fuer die API: bei CachedSystem zwei Bloecke, der feste vordere mit Cache-Breakpoint
    (deckt Werkzeuge + festen System-Teil ab, Reihenfolge tools -> system -> messages); sonst unveraendert."""
    n = getattr(system, "cache_prefix_chars", 0)
    if type(n) is int and 0 < n < len(system):
        return [
            {"type": "text", "text": str(system[:n]), "cache_control": {"type": "ephemeral"}},
            {"type": "text", "text": str(system[n:])},
        ]
    return system


def _is_bad_request_error(exc: BaseException) -> bool:
    """True bei HTTP 400 / invalid_request_error (unabhaengig vom Grund)."""
    text = str(exc).lower()
    return bool(getattr(exc, "status_code", None) == 400 or "invalid_request_error" in text)


def _is_web_search_disabled_error(exc: BaseException) -> bool:
    """True bei HTTP 400 / invalid_request_error, der auf deaktivierte Web-Suche hindeutet."""
    text = str(exc).lower()
    mentions_search = "web search" in text or "web_search" in text
    return bool(_is_bad_request_error(exc) and mentions_search)


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
    # Prompt-Cache-Felder (#113) nur bei echtem int > 0 — ohne sie bleibt das Dict wie bisher.
    for key in ("cache_read_input_tokens", "cache_creation_input_tokens"):
        val = getattr(usage, key, 0)
        if type(val) is int and val > 0:
            out[key] = val
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
        self._saw_web = False  # B13: True, sobald ein Web-(Server-)Tool-Block gesehen wurde

    def saw_web(self) -> bool:
        return self._saw_web

    def feed(self, content: Any, model: str = "") -> None:
        for block in content or []:
            btype = getattr(block, "type", None)
            # B13 (Defense-in-Depth): JEDER Web-Server-Tool-Block signalisiert eine Websuche —
            # unabhaengig vom usage-Zaehler (der bei 0/fehlend kein Signal gaebe).
            if btype in ("server_tool_use", "web_search_tool_result"):
                self._saw_web = True
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
            kwargs["system"] = _system_param(system)
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

        HINWEIS (Ebene-2-Tool-Use): generate() hat BEWUSST keinen `tool_executor`-
        Round-Trip (anders als stream()). Der /chat-Antwortpfad nutzt ausschliesslich
        stream(); generate() wird dort nicht aufgerufen. Ein `stop_reason=="tool_use"`
        wird hier wie heute als normaler Abschluss behandelt (nur Text zaehlt). Wird
        der non-streaming Pfad spaeter Client-Tools brauchen, ist die Schleife analog
        zu stream() nachzuruesten.
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
        tool_executor: Any = None,
        sources_out: list[dict[str, str]] | None = None,
        silent_tool_names: set[str] | frozenset[str] | None = None,
        terminal_tool_names: set[str] | frozenset[str] | None = None,
        terminal_text_ok: Callable[[str], bool] | None = None,
        terminal_shadow: bool = False,
        timing_out: list[dict[str, Any]] | None = None,
    ) -> AsyncGenerator[str, None]:
        """Streaming generation — liefert Text-Chunks.

        Nach Abschluss steht die Token-Usage in `self.last_usage`. Wer parallele
        Requests fahren kann, übergibt zusätzlich `usage_out` (wird in-place mit
        derselben Usage befüllt — race-frei, anders als das geteilte Attribut).
        Bei SDK-Fehlern/Timeout wird `ClaudeUnavailable` geworfen (auch mitten
        im Stream — der Caller entscheidet anhand bereits gesendeter Chunks,
        ob ein Fallback sinnvoll ist).

        `tools`: z.B. `[build_web_search_tool()]` (Server-Tool) UND/ODER Client-Tools
        (Ebene-2-Migration). Server-Tools laufen bei Anthropic; Client-Tools werden
        ueber `tool_executor` ausgefuehrt. Im Stream erscheinen NUR die Text-Deltas
        (content_block_delta/text_delta) — `tool_use`-Bloecke sind separate Content-
        Bloecke und landen NIE im gesprochenen Stream. Nach dem Stream liegen die
        Quellen (`{"title","url"}`, zitierte zuerst) in `sources_out` und die
        Suchanzahl in der Usage (`web_search_requests`).

        Round-Trip (EINE Schleife, getrennte Zaehler):
          * `pause_turn` (Server-Websuche): Turn unveraendert anhaengen, weiter
            (max. `_MAX_PAUSE_CONTINUATIONS`).
          * `tool_use` + gesetzter `tool_executor`: fuer jeden tool_use-Block
            `result = await tool_executor(name, input, tool_use_id)` (erwartet ein
            fertiges tool_result-Content-Block-Dict zurueck); assistant(content) +
            user(tool_results) anhaengen, weiter (max. `_MAX_TOOL_ROUNDS`).
            STUMME/terminale Tools (`silent_tool_names`): gehoeren in einer Runde ALLE
            tool_use-Bloecke zu stummen Tools (z.B. save_memory), werden die Ergebnisse
            ausgefuehrt + an die History angehaengt (Validitaet), aber die Fortsetzung wird
            NICHT weiter gestreamt (break) — so entsteht KEIN sichtbarer Begleittext
            ("gespeichert"). Gemischte Runden (mind. ein nicht-stummes, result-tragendes
            Tool wie Inbox) laufen normal weiter. Default (None/leer) = heutiges Verhalten.
            KARTEN-Tools (`terminal_tool_names`, #91): die Folgerunde entfaellt NUR, wenn JEDES
            Tool der Runde in (terminal ∪ silent) liegt (mind. eines in terminal), JEDES Ergebnis
            ein dict ohne `is_error` ist (Nicht-dict = Fehler), in der Runde >=
            `_TERMINAL_MIN_TEXT_CHARS` Nicht-Leerraum-Zeichen gestreamt wurden, der Text der Runde
            `terminal_text_ok(text)` erfuellt (Antipp-Hinweis; None/Exception = nie ueberspringen)
            und im Request keine Websuche lief. History wird wie beim stummen Pfad angehaengt.
            `terminal_shadow=True`: Schattenmessung — NICHT abbrechen, nur loggen
            (`claude_terminal_tool_would_skip`, nach der Folgerunde
            `claude_terminal_shadow_next_round`). `claude_terminal_tool_no_tap_hint` wird geloggt,
            wenn alles ausser dem Hinweis erfuellt waere (beide Modi). Default (None) = heute.
          * `refusal`: raise ClaudeUnavailable (wie bisher).
          * `max_tokens`: Abbruch — ein offener/trunkierter tool_use wird NICHT
            ausgefuehrt (fail-closed).
          * sonst (end_turn / nur Text / tool_use ohne Executor): fertig.
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
        retried_without_client_tools = False   # B1
        had_client_tools = tool_executor is not None  # B1: gab es ueberhaupt Client-Tools?
        orig_messages = list(kwargs["messages"])
        pause_count = 0   # Zaehler fuer pause_turn (Server-Websuche)
        tool_rounds = 0   # Zaehler fuer tool_use-Round-Trips (Client-Tools)
        shadow_pending: list[str] | None = None   # #91 Schattenmessung: Tools der Skip-Runde
        need_sep = False  # B10: vor der naechsten Runde ggf. ein Leerzeichen einschieben
        last_char = ""    # letztes geyieldetes Zeichen (fuer den Trenner)
        while True:
            try:
                while True:
                    # --- Streamen: nur Text-Deltas yielden, tool_use-Bloecke sammeln ---
                    # `text_stream` liefert AUSSCHLIESSLICH Text-Deltas (die gesprochenen
                    # Tokens) — tool_use-Input (input_json_delta) ist NICHT enthalten und
                    # landet damit NIE im TTS-Stream. Die vollstaendig zusammengesetzten
                    # tool_use-Bloecke (mit komplettem Input) stehen danach in `final.content`
                    # (get_final_message), genau wie die pause_turn-Fortsetzung sie nutzt.
                    first_text_in_round = True
                    round_text_chars = 0   # #91: Nicht-Leerraum-Zeichen dieser Runde
                    round_text_parts: list[str] = []   # nur fuer die Hinweis-Pruefung, nie geloggt
                    _t_round, _ttft, _ttfb = time.perf_counter(), None, None   # #113 Zeitmessung (nur Zahlen)
                    async with client.messages.stream(**kwargs) as stream:
                        _ttfb = int((time.perf_counter() - _t_round) * 1000)  # Antwortbeginn (Stream geoeffnet)
                        async for text in stream.text_stream:
                            if text:
                                round_text_chars += len("".join(text.split()))
                                if _ttft is None:
                                    _ttft = int((time.perf_counter() - _t_round) * 1000)
                                round_text_parts.append(text)
                                # B10: Zwischen Text aus verschiedenen Runden einen Trenner, damit
                                # nicht „…mir.Soll ich…" zusammenklebt — nur wenn die vorige Runde
                                # ohne abschliessenden Whitespace endete und dieser Text nicht mit
                                # Whitespace beginnt.
                                if (first_text_in_round and need_sep and last_char
                                        and not last_char.isspace() and not text[:1].isspace()):
                                    yield " "
                                first_text_in_round = False
                                need_sep = False
                                yielded = True
                                last_char = text[-1]
                                yield text
                        final = await stream.get_final_message()
                    _add_usage(total, _usage_dict(getattr(final, "usage", None)))
                    self.last_usage = dict(total)
                    if usage_out is not None:
                        usage_out.update(self.last_usage)
                    collector.feed(getattr(final, "content", None), kwargs["model"])
                    # B13/T3 (Defense-in-Depth): sah der Content einen Web-Server-Tool-Block, OHNE
                    # dass der echte Zaehler ihn erfasst hat, signalisiere die Websuche SEPARAT ueber
                    # usage_out["_web_seen"] — NICHT auf den realen web_search_requests-Zaehler
                    # addieren (sonst zaehlt die Kosten-Metrik doppelt). Gibt es bereits einen echten
                    # Zaehler (>0), ist das Flag redundant (das Gate greift ohnehin) → nicht setzen,
                    # damit die Usage sauber bleibt. Das Live-/Web-Gate (searched_web_fn) wertet beide aus.
                    if (collector.saw_web() and usage_out is not None
                            and not int(total.get("web_search_requests", 0) or 0)):
                        usage_out["_web_seen"] = True
                    stop = getattr(final, "stop_reason", None)
                    if timing_out is not None:
                        _rec = {"round": sum(1 for e in timing_out if "round" in e) + 1,
                                "round_ms": int((time.perf_counter() - _t_round) * 1000), "stop": str(stop)}
                        if _ttfb is not None:
                            _rec["ttfb_ms"] = _ttfb
                        if _ttft is not None:
                            _rec["ttft_ms"] = _ttft
                        timing_out.append(_rec)
                    if shadow_pending is not None:
                        log.info("claude_terminal_shadow_next_round",
                                 tools=[(getattr(b, "name", "") or "")
                                        for b in (getattr(final, "content", None) or [])
                                        if getattr(b, "type", None) == "tool_use"],
                                 text_chars=round_text_chars, stop_reason=stop)
                        shadow_pending = None
                    if stop == "refusal":
                        log.warning("claude_refusal", model=kwargs["model"],
                                    detail=str(getattr(final, "stop_details", None)))
                        raise ClaudeUnavailable("claude_refusal")
                    if stop == "max_tokens":
                        # fail-closed: ein offener/trunkierter tool_use wird NICHT
                        # ausgefuehrt — kein Write auf halbem/invalidem Input.
                        log.warning("claude_truncated_max_tokens", model=kwargs["model"])
                        break
                    if stop == "pause_turn":
                        if tools and pause_count < _MAX_PAUSE_CONTINUATIONS:
                            # Server-Loop-Limit: Turn unveraendert anhaengen und weiterstreamen.
                            # KEIN B10-Trenner: die Websuche-Fortsetzung ist derselbe Satz-Fluss
                            # (ein Leerzeichen koennte mitten im Wort landen) — nur tool_use-Runden
                            # (getrennte Sprech-Abschnitte) bekommen den Trenner.
                            pause_count += 1
                            kwargs["messages"] = [
                                *kwargs["messages"],
                                {"role": "assistant", "content": final.content},
                            ]
                            continue
                        log.warning("claude_pause_turn_limit", model=kwargs["model"])
                        break
                    if stop == "tool_use" and tool_executor is not None:
                        tool_use_blocks = [
                            b for b in (getattr(final, "content", None) or [])
                            if getattr(b, "type", None) == "tool_use"
                        ]
                        if not tool_use_blocks:
                            break
                        if tool_rounds >= _MAX_TOOL_ROUNDS:
                            log.warning("claude_tool_rounds_limit", model=kwargs["model"])
                            # Honesty-Signal an den Aufrufer: das Modell wollte weitere Tools, das
                            # Rundenlimit hat es gestoppt (kein stilles „Erledigt.“ in chat()).
                            if usage_out is not None:
                                usage_out["_tool_rounds_exhausted"] = True
                            break
                        tool_rounds += 1
                        results: list[dict[str, Any]] = []
                        for b in tool_use_blocks:
                            name = getattr(b, "name", "") or ""
                            tool_use_id = getattr(b, "id", "") or ""
                            _t_tool = time.perf_counter()
                            try:
                                res = await tool_executor(name, getattr(b, "input", None), tool_use_id)
                            except Exception as te:  # noqa: BLE001 — Executor-Fehler NIE als Erfolg
                                log.warning("claude_tool_executor_error", name=name,
                                            error_type=type(te).__name__)
                                res = {
                                    "type": "tool_result", "tool_use_id": tool_use_id,
                                    "content": "Tool-Ausfuehrung fehlgeschlagen.", "is_error": True,
                                }
                            if timing_out is not None and name:
                                timing_out.append({"tool": name, "ms": int((time.perf_counter() - _t_tool) * 1000)})
                            results.append(res)
                        # Phase B (STUMM-Garantie): assistant(content) + user(results) IMMER an
                        # die History anhaengen (gueltige Abfolge). Gehoeren ALLE tool_use-Bloecke
                        # dieser Runde zu stummen Tools (z.B. save_memory):
                        #   * kam in DIESER Runde bereits Text (not first_text_in_round) -> die
                        #     eigentliche Nutzer-Antwort ist schon gestreamt, also stumm beenden
                        #     (break, keine Fortsetzung = kein "gespeichert"-Begleittext).
                        #   * kam KEIN Text (Modell rief nur das Tool, ohne vorab zu antworten) ->
                        #     die Antwort steckt erst in der Folgerunde; NICHT abschneiden, sonst
                        #     sieht der Nutzer eine Leer-Antwort trotz erfolgtem Write (B-F1). Das
                        #     tool_result weist das Modell an, jetzt normal (ohne Speicher-Floskel)
                        #     zu antworten -> STUMM bleibt gewahrt.
                        # PER-RUNDE-Tracking (first_text_in_round), NICHT der session-weite `yielded`-
                        # Flag: so laufen auch gemischte Sequenzen (Runde 1 Text, Runde 2 nur
                        # save_memory ohne Text) korrekt weiter.
                        _silent = silent_tool_names or frozenset()
                        _all_silent = bool(_silent) and all(
                            (getattr(b, "name", "") or "") in _silent for b in tool_use_blocks
                        )
                        kwargs["messages"] = [
                            *kwargs["messages"],
                            {"role": "assistant", "content": final.content},
                            {"role": "user", "content": results},
                        ]
                        if _all_silent and not first_text_in_round:
                            # Antwort kam schon in dieser Runde -> terminal, stumm beenden.
                            break
                        # #91: reine Karten-Runde (ggf. + stumme Tools) mit echtem Vorab-Text, ohne
                        # Fehler und ohne Websuche -> Folgerunde entfaellt (Karte kommt frueher).
                        _term = terminal_tool_names or frozenset()
                        _names = [(getattr(b, "name", "") or "") for b in tool_use_blocks]
                        _base = bool(
                            _term
                            and any(n in _term for n in _names)
                            and all(n in _term or n in _silent for n in _names)
                            and round_text_chars >= _TERMINAL_MIN_TEXT_CHARS
                            and not collector.saw_web()
                            and not int(total.get("web_search_requests", 0) or 0)
                            and len(results) == len(_names)
                            and all(isinstance(r, dict) and not r.get("is_error")
                                    for r in results)
                        )
                        if _base:
                            try:
                                _tap = bool(terminal_text_ok is not None
                                            and terminal_text_ok("".join(round_text_parts)))
                            except Exception as te:  # noqa: BLE001 — fail-closed: kein Skip
                                log.warning("claude_terminal_text_ok_error",
                                            error_type=type(te).__name__)
                                _tap = False
                            if not _tap:
                                log.info("claude_terminal_tool_no_tap_hint", tools=_names,
                                         text_chars=round_text_chars)
                            elif terminal_shadow:
                                log.info("claude_terminal_tool_would_skip", model=kwargs["model"],
                                         tools=_names, text_chars=round_text_chars)
                                shadow_pending = _names
                            else:
                                log.info("claude_terminal_tool_skip", model=kwargs["model"],
                                         tools=_names, text_chars=round_text_chars)
                                break
                        need_sep = True  # B10: Trenner vor der naechsten (sichtbaren) Runde
                        continue
                    # end_turn / nur Text / tool_use ohne Executor -> fertig
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
                    and tool_rounds == 0   # B12: nicht neu starten, wenn schon ein Tool lief
                    and _is_web_search_disabled_error(e)
                ):
                    # Web-Suche in der Console deaktiviert -> EINMAL ohne Tools wiederholen,
                    # damit der Chat trotzdem antwortet.
                    log.warning("web_search_disabled_retry", model=kwargs["model"], error=str(e))
                    retried_without_tools = True
                    kwargs.pop("tools", None)
                    kwargs["messages"] = list(orig_messages)
                    tools = None
                    tool_executor = None
                    pause_count = 0
                    tool_rounds = 0
                    need_sep = False
                    continue
                if (
                    kwargs.get("tools")
                    and had_client_tools
                    and not yielded
                    and not retried_without_client_tools
                    and tool_rounds == 0   # noch kein Tool-Ergebnis in der Historie
                    and _is_bad_request_error(e)
                ):
                    # B1 (Hardening): Ein 400, der NICHT web-search-disabled ist, mit gesetzten
                    # Client-Tools -> wahrscheinlich lehnt die API das strict-Tool-Schema ab.
                    # EINMAL ohne die Client-/Inbox-Tools (nur ggf. Web-Server-Tools) neu streamen
                    # (graceful degradation statt ClaudeUnavailable/Offline).
                    log.warning("client_tools_disabled_retry", model=kwargs["model"], error=str(e))
                    retried_without_client_tools = True
                    server_tools = [t for t in (kwargs.get("tools") or [])
                                    if isinstance(t, dict) and t.get("type")]
                    if server_tools:
                        kwargs["tools"] = server_tools
                    else:
                        kwargs.pop("tools", None)
                        tools = None
                    kwargs["messages"] = list(orig_messages)
                    tool_executor = None
                    pause_count = 0
                    tool_rounds = 0
                    need_sep = False
                    continue
                log.error("claude_stream_error", model=kwargs["model"], error=str(e))
                raise ClaudeUnavailable(str(e)) from e

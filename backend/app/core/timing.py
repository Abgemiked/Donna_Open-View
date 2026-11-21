"""Zeitmessung je Chat-Request (#113) — nur Zahlen, Konstanten und Tool-Namen im Log.

Verhaltensneutral: kein Fehler in Timer oder Log darf den Request beeinflussen.
"""
from __future__ import annotations

import json
import re
import time
import uuid
from typing import Any, Callable

_SAFE_NAME = re.compile(r"[A-Za-z0-9_.\-]{1,48}")
_STOPS = frozenset({"end_turn", "tool_use", "max_tokens", "pause_turn", "refusal", "stop_sequence"})


def new_request_id() -> str:
    return uuid.uuid4().hex[:12]


def _safe_name(value: Any) -> str:
    return value if isinstance(value, str) and _SAFE_NAME.fullmatch(value) else "other"


def _int(value: Any) -> int:
    return value if type(value) is int and value > 0 else 0


class PhaseTimer:
    """Misst Abschnitte per Marken: `mark(name)` = Millisekunden seit der letzten Marke."""

    def __init__(self, clock: Callable[[], float] = time.perf_counter) -> None:
        self._clock = clock
        self._ms: dict[str, int] = {}
        self._chars: dict[str, int] = {}
        try:
            self._last = clock()
        except Exception:  # noqa: BLE001 — Timer darf nie werfen
            self._last = 0.0

    def mark(self, name: str) -> int:
        try:
            now = self._clock()
            delta = max(0, round((now - self._last) * 1000))
            self._last = now
            self._ms[name] = self._ms.get(name, 0) + delta
            return delta
        except Exception:  # noqa: BLE001
            return 0

    def add(self, name: str, ms: Any) -> None:
        try:
            self._ms[name] = self._ms.get(name, 0) + max(0, int(ms))
        except Exception:  # noqa: BLE001
            pass

    def note_blocks(self, *, system: Any, prompt: Any, history: Any, tools: Any) -> None:
        """Merkt nur die Zeichenzahl je Prompt-Block (Grundlage fuer spaeteres Caching)."""
        try:
            self._chars = {
                "system": len(system or ""),
                "prompt": len(prompt or ""),
                "history": sum(len(str(m.get("content", ""))) for m in (history or [])),
                "tools": len(json.dumps(tools or [], default=str)),
            }
        except Exception:  # noqa: BLE001
            self._chars = {}

    def summary(self) -> dict[str, int]:
        return dict(self._ms)

    def chars(self) -> dict[str, int]:
        return dict(self._chars)


def emit_chat_timing(log: Any, timer: Any, request_id: str, rounds: Any, *,
                     route: Any, model: Any, total_ms: Any, usage: Any) -> None:
    """Ein Event `chat_timing` je Request. Wirft nie; bei Fehler nur `chat_timing_failed`."""
    try:
        round_rows = [
            {**{k: _int(e.get(k)) for k in ("ttfb_ms", "ttft_ms") if k in e},
             "round_ms": _int(e.get("round_ms")),
             "stop": e.get("stop") if e.get("stop") in _STOPS else "other"}
            for e in (rounds or []) if isinstance(e, dict) and "round" in e
        ]
        tool_rows = [
            {"tool": _safe_name(e.get("tool")), "ms": _int(e.get("ms"))}
            for e in (rounds or []) if isinstance(e, dict) and "tool" in e
        ]
        usage = usage if isinstance(usage, dict) else {}
        log.info(
            "chat_timing",
            request_id=_safe_name(request_id),
            route=_safe_name(route),
            model=_safe_name(model),
            total_ms=_int(total_ms),
            phases_ms=timer.summary(),
            rounds=round_rows,
            tools=tool_rows,
            chars=timer.chars(),
            cache_read_tokens=_int(usage.get("cache_read_input_tokens")),
            cache_write_tokens=_int(usage.get("cache_creation_input_tokens")),
        )
    except Exception as exc:  # noqa: BLE001 — Messung darf den Request nie kippen
        try:
            log.warning("chat_timing_failed", error_type=type(exc).__name__)
        except Exception:  # noqa: BLE001
            pass

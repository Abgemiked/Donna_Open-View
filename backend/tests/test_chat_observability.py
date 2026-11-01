"""Welle 1 / W1-B — Observability: Pro /chat-Request Token- & Kosten-Logging via structlog.

Der aktive Chat-Router (routes/chat.py) loggt im `finally`-Block des Streaming-
Generators ein strukturiertes `chat_done`-Event mit Modell, Token-Zählern und einer
groben Kostenschätzung (`_claude_cost_usd`). structlog ist projektweit in Benutzung.

  Teil A (Werte): `_claude_cost_usd` rechnet korrekt — inkl. ASYMMETRISCHer Fälle
          (nur input- bzw. nur output-Tokens), die ein vertauschtes p_in/p_out-Tupel
          aufdecken; kombinierter Tokens+Websuche-Fall; None bei unbekanntem Modell.

  Teil B (Felder + Datensparsamkeit, AST — SCOPE: ausschließlich das `chat_done`-
          Event): prüft ALLE chat_done-Aufrufe. Pflichtfelder
          {model, input_tokens, output_tokens, cost_usd} vorhanden; cost_usd ist an
          das Gate `_provider_used == "claude"` gebunden; **kwargs-Splat und
          Positionsargumente jenseits des Event-Namens werden abgelehnt; jeder
          Log-Wert muss einer ALLOWLIST sicherer Formen entsprechen (Zähler/Metadaten),
          damit keine vollständigen Nutzerinhalte/Secrets geloggt werden können.
          Andere Log-Events im Pfad sind NICHT Gegenstand dieses Guards.
"""
from __future__ import annotations

import ast
from pathlib import Path

import pytest

import app.routes.chat as chat_module
from app.routes.chat import _claude_cost_usd


# ══ Teil A: Kostenschätzung (echte Funktion, echte Werte) ═══════════════════════

@pytest.mark.unit
def test_cost_haiku_input_only() -> None:
    # Nur Input: Haiku $0.10 / Mtok in — deckt vertauschtes p_in/p_out auf.
    assert _claude_cost_usd("claude-haiku-5-5", {"input_tokens": 1_000_000, "output_tokens": 0}) \
        == pytest.approx(0.10, abs=1e-6)


@pytest.mark.unit
def test_cost_haiku_output_only() -> None:
    assert _claude_cost_usd("claude-haiku-5-5", {"input_tokens": 0, "output_tokens": 1_000_000}) \
        == pytest.approx(0.50, abs=1e-6)


@pytest.mark.unit
def test_cost_sonnet_input_only() -> None:
    assert _claude_cost_usd("claude-sonnet-5-5", {"input_tokens": 1_000_000, "output_tokens": 0}) \
        == pytest.approx(2.00, abs=1e-6)


@pytest.mark.unit
def test_cost_sonnet_output_only() -> None:
    assert _claude_cost_usd("claude-sonnet-5-5", {"input_tokens": 0, "output_tokens": 1_000_000}) \
        == pytest.approx(10.00, abs=1e-6)


@pytest.mark.unit
def test_cost_symmetric_sums_both_sides() -> None:
    assert _claude_cost_usd("claude-haiku-5-5", {"input_tokens": 1_000_000, "output_tokens": 1_000_000}) \
        == pytest.approx(0.60, abs=1e-6)
    assert _claude_cost_usd("claude-sonnet-5-5", {"input_tokens": 1_000_000, "output_tokens": 1_000_000}) \
        == pytest.approx(12.00, abs=1e-6)


@pytest.mark.unit
def test_cost_tokens_plus_web_search_combined() -> None:
    # Haiku: 500k in ($0.05) + 200k out ($0.10) + 2 Suchen ($0.02) = $0.17
    usage = {"input_tokens": 500_000, "output_tokens": 200_000, "web_search_requests": 2}
    assert _claude_cost_usd("claude-haiku-5-5", usage) == pytest.approx(0.17, abs=1e-6)


@pytest.mark.unit
def test_cost_unknown_model_returns_none() -> None:
    assert _claude_cost_usd("gpt-4o", {"input_tokens": 10, "output_tokens": 10}) is None
    assert _claude_cost_usd("", {"input_tokens": 10, "output_tokens": 10}) is None


@pytest.mark.unit
def test_cost_missing_usage_keys_are_safe() -> None:
    assert _claude_cost_usd("claude-haiku-5-5", {}) == pytest.approx(0.0, abs=1e-9)


# ══ Teil B: chat_done-Log via AST ═══════════════════════════════════════════════

_REQUIRED_FIELDS = {"model", "input_tokens", "output_tokens", "cost_usd"}

# Bezeichner, die auf vollständige Nutzerinhalte / Secrets hindeuten.
_FORBIDDEN = {
    "payload", "message", "messages", "content", "prompt", "system_prompt",
    "response_chunks", "full_response", "clean_response", "text",
    "anthropic_api_key", "admin_token", "ha_token", "api_key", "token", "secret",
}

# Objekte, deren .get(...) nur Zähler liefert (sicher).
_SAFE_COUNTER_OBJECTS = {"_usage"}
# Call-Funktionen, die einen abgeleiteten Skalar liefern (kein Rohinhalt).
_SAFE_CALL_FUNCS = {"len", "_claude_cost_usd", "int", "float", "round", "bool"}


def _chat_done_calls(tree: ast.Module) -> list[ast.Call]:
    out: list[ast.Call] = []
    for node in ast.walk(tree):
        if (
            isinstance(node, ast.Call)
            and isinstance(node.func, ast.Attribute)
            and node.func.attr in ("info", "warning", "error", "debug")
            and node.args
            and isinstance(node.args[0], ast.Constant)
            and node.args[0].value == "chat_done"
        ):
            out.append(node)
    return out


def _is_safe_value(node: ast.AST) -> bool:
    """Allowlist: nur Konstanten, unverfängliche Namen/Attribute, Zähl-/Skalar-Calls,
    _usage.get(...), bool-Operatoren und If-Ausdrücke daraus."""
    if isinstance(node, ast.Constant):
        return True
    if isinstance(node, ast.Name):
        return node.id not in _FORBIDDEN
    if isinstance(node, ast.Attribute):
        root = node
        while isinstance(root, ast.Attribute):
            root = root.value
        if isinstance(root, ast.Name) and root.id in _FORBIDDEN:
            return False
        return node.attr not in _FORBIDDEN
    if isinstance(node, ast.BoolOp):  # z.B. (_model_used or "")
        return all(_is_safe_value(v) for v in node.values)
    if isinstance(node, ast.IfExp):   # cost_usd = (... if ... else None)
        return _is_safe_value(node.body) and _is_safe_value(node.orelse)
    if isinstance(node, ast.Call):
        f = node.func
        if isinstance(f, ast.Name) and f.id in _SAFE_CALL_FUNCS:
            return True
        if isinstance(f, ast.Attribute) and f.attr == "get" \
                and isinstance(f.value, ast.Name) and f.value.id in _SAFE_COUNTER_OBJECTS:
            return True
        return False
    return False


@pytest.mark.unit
def test_chat_done_logs_exist() -> None:
    calls = _chat_done_calls(ast.parse(Path(chat_module.__file__).read_text(encoding="utf-8")))
    assert calls, "Kein `chat_done`-Log-Event in routes/chat.py gefunden."


@pytest.mark.unit
def test_all_chat_done_logs_have_required_fields() -> None:
    tree = ast.parse(Path(chat_module.__file__).read_text(encoding="utf-8"))
    for call in _chat_done_calls(tree):
        kwargs = {kw.arg for kw in call.keywords if kw.arg}
        missing = _REQUIRED_FIELDS - kwargs
        assert not missing, f"chat_done-Log fehlen Observability-Felder: {sorted(missing)}"


@pytest.mark.unit
def test_cost_usd_is_gated_on_provider_claude() -> None:
    """cost_usd muss None sein, wenn _provider_used != 'claude' — als IfExp mit
    Compare(_provider_used == 'claude') und else-Zweig None."""
    tree = ast.parse(Path(chat_module.__file__).read_text(encoding="utf-8"))
    for call in _chat_done_calls(tree):
        cost_kw = next((kw for kw in call.keywords if kw.arg == "cost_usd"), None)
        assert cost_kw is not None
        val = cost_kw.value
        assert isinstance(val, ast.IfExp), "cost_usd ist nicht an ein Provider-Gate gebunden."
        assert isinstance(val.orelse, ast.Constant) and val.orelse.value is None, \
            "cost_usd else-Zweig ist nicht None."
        test = val.test
        assert isinstance(test, ast.Compare), "cost_usd-Gate ist kein Vergleich."
        names = {n.id for n in ast.walk(test) if isinstance(n, ast.Name)}
        consts = {c.value for c in ast.walk(test) if isinstance(c, ast.Constant)}
        assert "_provider_used" in names, "Gate prüft nicht _provider_used."
        assert "claude" in consts, "Gate vergleicht nicht gegen 'claude'."


@pytest.mark.unit
def test_chat_done_rejects_kwargs_splat_and_positional_payload() -> None:
    """Kein **kwargs-Splat (kw.arg is None) und keine Positionsargumente jenseits
    des Event-Namens — beides könnte ungefiltert Inhalte durchreichen."""
    tree = ast.parse(Path(chat_module.__file__).read_text(encoding="utf-8"))
    for call in _chat_done_calls(tree):
        assert call.args[1:] == [], "chat_done hat unerwartete Positionsargumente."
        for kw in call.keywords:
            assert kw.arg is not None, "chat_done nutzt **kwargs-Splat — nicht prüfbar/erlaubt."


@pytest.mark.unit
def test_chat_done_values_match_safe_allowlist() -> None:
    """Jeder geloggte Wert muss der Allowlist sicherer Formen entsprechen."""
    tree = ast.parse(Path(chat_module.__file__).read_text(encoding="utf-8"))
    for call in _chat_done_calls(tree):
        for kw in call.keywords:
            assert _is_safe_value(kw.value), (
                f"chat_done-Feld '{kw.arg}' hat eine nicht-allowlistete Wertform "
                f"({ast.dump(kw.value)}) — mögliches Leak von Inhalt/Secret."
            )

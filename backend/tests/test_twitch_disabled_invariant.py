"""Welle 1 / W1-C — Invariante: Twitch-Bot / EventSub / Proaktiv starten NICHT bei Defaults.

Donnas Twitch-Funktionen sind bewusst AUS (Mike will Twitch nur in Streamer.bot).
Künftige Deploys dürfen Twitch nicht versehentlich reaktivieren.

SCOPE dieses Tests: Code-Defaults (config.py Field-Defaults) + Verdrahtung der Gates
in app/main.py. NICHT abgedeckt ist die Server-Laufzeit-Umgebung (.env /
docker-compose). EventSub besitzt KEINEN eigenen Kill-Switch, sondern ist nur durch
Abwesenheit der Credentials aus — EMPFEHLUNG für den Deploy: Smoke-Check
`docker exec <api> env | grep -E '^(TWITCH_|DONNA_TWITCH)'` (keine neuen Flags/Variablen
hier, das wäre out of scope).

Bindung an den ECHTEN Code (kein roher String-Grep, kein voller App-Boot): die
Gate-Bedingungen werden per AST aus app/main.py extrahiert, strukturell geprüft
(AND über die erwarteten Felder) und als Ausdruck gegen echte Settings/Flag-Werte
ausgewertet (Negativ- UND Positiv-Kontrolle). Entfernt jemand z.B.
`twitch_bot_enabled` aus dem Gate, wird dieser Test ROT.
"""
from __future__ import annotations

import ast
import importlib
import os
from pathlib import Path

import pytest

import app.config as _config_mod
from app.config import Settings

# main.py wird NUR als Quelltext (AST) geprüft — kein Import nötig (und lokal wegen
# schwerer Laufzeit-Abhängigkeiten gar nicht importierbar). Pfad aus dem app-Paket.
_MAIN_PY = Path(_config_mod.__file__).with_name("main.py")


_TWITCH_ENV_VARS = [
    "TWITCH_BOT_ENABLED", "TWITCH_BOT_TOKEN", "TWITCH_CHANNEL",
    "TWITCH_EVENTSUB_CLIENT_ID", "TWITCH_BOT_USER_TOKEN",
    "TWITCH_BROADCASTER_ID", "TWITCH_BOT_USER_ID", "DONNA_TWITCH_PROACTIVE",
]


# ── AST-Helfer: Gate-If aus main.py ziehen ──────────────────────────────────────

def _main_tree() -> ast.Module:
    return ast.parse(_MAIN_PY.read_text(encoding="utf-8"))


def _body_invokes(if_node: ast.If, callee: str) -> bool:
    """True, wenn im Then-Body ein Call auf einen Namen/ein Attribut `callee` steht."""
    for stmt in if_node.body:
        for sub in ast.walk(stmt):
            if isinstance(sub, ast.Call):
                f = sub.func
                if isinstance(f, ast.Name) and f.id == callee:
                    return True
                if isinstance(f, ast.Attribute) and f.attr == callee:
                    return True
    return False


def _body_mentions(if_node: ast.If, token: str) -> bool:
    for stmt in if_node.body:
        for sub in ast.walk(stmt):
            if isinstance(sub, ast.Name) and sub.id == token:
                return True
            if isinstance(sub, ast.Constant) and sub.value == token:
                return True
    return False


def _find_if(tree: ast.Module, predicate) -> ast.If:
    for node in ast.walk(tree):
        if isinstance(node, ast.If) and predicate(node):
            return node
    raise AssertionError("Erwartetes Gate-If in app/main.py nicht gefunden.")


def _and_identifiers(test: ast.AST) -> set[str]:
    assert isinstance(test, ast.BoolOp) and isinstance(test.op, ast.And), \
        f"Gate ist kein AND-Ausdruck, sondern {ast.dump(test)}"
    ids: set[str] = set()
    for sub in ast.walk(test):
        if isinstance(sub, ast.Attribute):
            ids.add(sub.attr)
        elif isinstance(sub, ast.Name):
            ids.add(sub.id)
    return ids


def _eval_test(test: ast.AST, namespace: dict) -> bool:
    expr = ast.Expression(body=test)
    ast.fix_missing_locations(expr)
    return bool(eval(compile(expr, "<main_gate>", "eval"), {}, namespace))  # noqa: S307


def _clean_settings(monkeypatch, **overrides) -> Settings:
    for var in _TWITCH_ENV_VARS:
        monkeypatch.delenv(var, raising=False)
    return Settings(_env_file=None, **overrides)


_FULL_TWITCH = dict(
    twitch_bot_enabled=True, twitch_bot_token="oauth:xxxx", twitch_channel="your-twitch-channel",
    twitch_eventsub_client_id="cid", twitch_bot_user_token="utok",
    twitch_broadcaster_id="bcast", twitch_bot_user_id="buid",
)


# ── 1. Chat-Bot-Gate (vor TwitchBotService(...)) ────────────────────────────────

@pytest.mark.unit
def test_chat_bot_gate_structure_and_defaults(monkeypatch) -> None:
    tree = _main_tree()
    gate = _find_if(tree, lambda n: _body_invokes(n, "TwitchBotService"))
    ids = _and_identifiers(gate.test)
    assert {"twitch_bot_enabled", "twitch_bot_token", "twitch_channel"} <= ids, (
        f"Chat-Bot-Gate hat nicht alle erwarteten Felder: {sorted(ids)}"
    )
    # Negativ-Kontrolle: Default-Settings -> Gate aus.
    s_default = _clean_settings(monkeypatch)
    assert s_default.twitch_bot_enabled is False
    assert _eval_test(gate.test, {"settings": s_default}) is False
    # Positiv-Kontrolle: voll konfiguriert -> Gate an (keine Tautologie).
    s_full = Settings(_env_file=None, **_FULL_TWITCH)
    assert _eval_test(gate.test, {"settings": s_full}) is True


# ── 2. EventSub-Gate (vor TwitchEventSubService(...)) ───────────────────────────

@pytest.mark.unit
def test_eventsub_gate_structure_and_defaults(monkeypatch) -> None:
    tree = _main_tree()
    gate = _find_if(tree, lambda n: _body_invokes(n, "TwitchEventSubService"))
    ids = _and_identifiers(gate.test)
    assert {"twitch_eventsub_client_id", "twitch_bot_user_token",
            "twitch_broadcaster_id", "twitch_bot_user_id"} <= ids, (
        f"EventSub-Gate hat nicht alle vier erwarteten Felder: {sorted(ids)}"
    )
    s_default = _clean_settings(monkeypatch)
    assert s_default.twitch_eventsub_client_id is None
    assert _eval_test(gate.test, {"settings": s_default}) is False
    s_full = Settings(_env_file=None, **_FULL_TWITCH)
    assert _eval_test(gate.test, {"settings": s_full}) is True


@pytest.mark.unit
def test_eventsub_gate_requires_all_four_fields(monkeypatch) -> None:
    """Jedes einzeln fehlende Credential hält das AND-Gate aus (Teil-Konfig)."""
    tree = _main_tree()
    gate = _find_if(tree, lambda n: _body_invokes(n, "TwitchEventSubService"))
    four = ("twitch_eventsub_client_id", "twitch_bot_user_token",
            "twitch_broadcaster_id", "twitch_bot_user_id")
    for drop in four:
        overrides = {k: ("x" if k != drop else None) for k in four}
        s = Settings(_env_file=None, **overrides)
        assert _eval_test(gate.test, {"settings": s}) is False, \
            f"EventSub-Gate sollte ohne {drop} aus sein"


# ── 3. Proaktiv-Gate (vor dem stream_live_watcher-Job) ──────────────────────────

@pytest.mark.unit
def test_proactive_gate_structure_and_control() -> None:
    tree = _main_tree()
    gate = _find_if(
        tree,
        lambda n: isinstance(n.test, ast.Name)
        and n.test.id == "DONNA_TWITCH_PROACTIVE_ENABLED"
        and _body_mentions(n, "stream_live_watcher"),
    )
    # Negativ-/Positiv-Kontrolle über den echten extrahierten Ausdruck.
    assert _eval_test(gate.test, {"DONNA_TWITCH_PROACTIVE_ENABLED": False}) is False
    assert _eval_test(gate.test, {"DONNA_TWITCH_PROACTIVE_ENABLED": True}) is True


# ── 4. Proaktiv-Flag-Parsing im Modul (mit sauberem Teardown) ───────────────────

@pytest.fixture
def slw_module():
    """Liefert das stream_live_watcher-Modul und setzt es im Teardown IMMER auf den
    Default-Zustand (ENV bereinigt + reload) zurück — auch bei Testfehlschlag."""
    import app.jobs.stream_live_watcher as slw
    try:
        yield slw
    finally:
        os.environ.pop("DONNA_TWITCH_PROACTIVE", None)
        importlib.reload(slw)


@pytest.mark.unit
def test_proactive_flag_false_when_env_unset(monkeypatch, slw_module) -> None:
    monkeypatch.delenv("DONNA_TWITCH_PROACTIVE", raising=False)
    slw = importlib.reload(slw_module)
    assert slw.DONNA_TWITCH_PROACTIVE_ENABLED is False


@pytest.mark.unit
def test_proactive_flag_false_when_env_false(monkeypatch, slw_module) -> None:
    monkeypatch.setenv("DONNA_TWITCH_PROACTIVE", "false")
    slw = importlib.reload(slw_module)
    assert slw.DONNA_TWITCH_PROACTIVE_ENABLED is False


@pytest.mark.unit
def test_proactive_flag_true_only_when_env_true(monkeypatch, slw_module) -> None:
    monkeypatch.setenv("DONNA_TWITCH_PROACTIVE", "true")
    slw = importlib.reload(slw_module)
    assert slw.DONNA_TWITCH_PROACTIVE_ENABLED is True

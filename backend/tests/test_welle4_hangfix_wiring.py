"""FIX 1 Produktions-Guard (Review-Rat R6 #1): AST-Wiring gegen stille Regression
des Hang-Fixes.

Stellt sicher, dass im Hot-Path der async `chat()`-Funktion von routes/chat.py die
Retrieval-Calls `ltm_service.get_all`, `ltm_service.recall_relevant` und `_retrieve`
AUSSCHLIESSLICH als Argument von `_ltm_call_bounded(...)` auftreten — nie als direkter
synchroner/awaited Call auf dem Event-Loop. Ein direkter Call würde den Loop blockieren
(→ /health-Hänger + serielle Chat-Verarbeitung) und wieder zur funktionstest-Regression
führen.

Robustheit (nicht tautologisch):
  - Scope = NUR der Body der `async def chat(...)`. Das legitime `recall_relevant`
    INNERHALB des modul-level `_retrieve` (selbst gebounded) liegt ausserhalb dieses
    Scopes und wird korrekt ignoriert; ebenso `stream_ltm.recall_relevant` im Twitch-Pfad.
  - Die Detektor-Logik wird zusätzlich gegen synthetischen GUTEN und BÖSEN Code geprüft
    (RED-Gegenprobe), damit der Guard nicht nur "immer grün" ist.
"""
from __future__ import annotations

import ast
from pathlib import Path

import pytest

import app.routes.chat as chat_module

_BOUNDED = "_ltm_call_bounded"
_HOTPATH_ATTRS = {"get_all", "recall_relevant"}  # auf ltm_service
_HOTPATH_FUNC = "_retrieve"
_LTM_VAR = "ltm_service"


def _parse(src: str | None = None) -> ast.Module:
    text = src if src is not None else Path(chat_module.__file__).read_text(encoding="utf-8")
    return ast.parse(text)


def _find_chat_func(tree: ast.AST) -> ast.AsyncFunctionDef | None:
    for n in ast.walk(tree):
        if isinstance(n, ast.AsyncFunctionDef) and n.name == "chat":
            return n
    return None


def _is_bounded_call(node: ast.AST) -> bool:
    if not isinstance(node, ast.Call):
        return False
    f = node.func
    return (isinstance(f, ast.Name) and f.id == _BOUNDED) or (
        isinstance(f, ast.Attribute) and f.attr == _BOUNDED
    )


def _bounded_arg_ids(scope: ast.AST) -> set[int]:
    """IDs aller Knoten, die als Argument eines _ltm_call_bounded(...)-Calls stehen."""
    ids: set[int] = set()
    for n in ast.walk(scope):
        if _is_bounded_call(n):
            for a in list(n.args) + [kw.value for kw in n.keywords]:
                for sub in ast.walk(a):
                    ids.add(id(sub))
    return ids


def _hotpath_refs(scope: ast.AST) -> list[ast.AST]:
    """Referenzen auf ltm_service.get_all / .recall_relevant sowie _retrieve im Scope."""
    refs: list[ast.AST] = []
    for n in ast.walk(scope):
        if isinstance(n, ast.Attribute) and n.attr in _HOTPATH_ATTRS:
            if isinstance(n.value, ast.Name) and n.value.id == _LTM_VAR:
                refs.append(n)
        elif isinstance(n, ast.Name) and n.id == _HOTPATH_FUNC:
            refs.append(n)
    return refs


def _stray_hotpath_refs(scope: ast.AST) -> list[ast.AST]:
    allowed = _bounded_arg_ids(scope)
    return [r for r in _hotpath_refs(scope) if id(r) not in allowed]


# ══ Produktions-Guard gegen routes/chat.py ══════════════════════════════════

@pytest.mark.unit
def test_hotpath_retrieval_calls_are_all_bounded() -> None:
    chatf = _find_chat_func(_parse())
    assert chatf is not None, "async def chat(...) nicht gefunden (umbenannt?)."
    refs = _hotpath_refs(chatf)
    assert refs, "Keine Hot-Path-Retrieval-Referenz in chat() gefunden (Pfad entfernt?)."
    stray = _stray_hotpath_refs(chatf)
    assert not stray, (
        f"{len(stray)} ungebundene Hot-Path-Retrieval-Referenz(en) in chat() — "
        "Direktaufruf blockiert den Event-Loop (FIX-1-Regression)."
    )


@pytest.mark.unit
def test_bounded_hotpath_calls_are_awaited() -> None:
    """Die gebundenen Retrieval-Calls müssen awaited sein (sonst 'never awaited')."""
    tree = _parse()
    chatf = _find_chat_func(tree)
    parents: dict[int, ast.AST] = {}
    for node in ast.walk(chatf):
        for child in ast.iter_child_nodes(node):
            parents[id(child)] = node

    hot_ids = {id(r) for r in _hotpath_refs(chatf)}
    bounded_with_hot = [
        c for c in ast.walk(chatf)
        if _is_bounded_call(c) and (
            {id(sub) for a in list(c.args) + [kw.value for kw in c.keywords] for sub in ast.walk(a)}
            & hot_ids
        )
    ]
    assert bounded_with_hot, "Kein _ltm_call_bounded-Call referenziert einen Hot-Path-Call."

    def _awaited(call: ast.AST) -> bool:
        cur = parents.get(id(call))
        while cur is not None:
            if isinstance(cur, ast.Await):
                return True
            cur = parents.get(id(cur))
        return False

    assert all(_awaited(c) for c in bounded_with_hot), (
        "Ein _ltm_call_bounded-Hot-Path-Call ist nicht awaited (Coroutine liefe nie)."
    )


# ══ Gegenproben: Detektor ist nicht tautologisch ════════════════════════════

_GOOD_SRC = """
async def chat(payload, request):
    ltm_service = request.app.state.ltm
    m = await _ltm_call_bounded(ltm_service.recall_relevant, payload.message)
    n = await _ltm_call_bounded(ltm_service.get_all, user_id="x")
    hits = await _ltm_call_bounded(_retrieve, vector, payload.message, ltm_service=ltm_service)
    return hits
"""

_BAD_SRC = """
async def chat(payload, request):
    ltm_service = request.app.state.ltm
    m = ltm_service.recall_relevant(payload.message)          # BUG: direkter Call
    n = await _ltm_call_bounded(ltm_service.get_all, user_id="x")
    hits = await _ltm_call_bounded(_retrieve, vector, payload.message, ltm_service=ltm_service)
    return hits
"""


@pytest.mark.unit
def test_detector_green_on_fully_bounded_source() -> None:
    chatf = _find_chat_func(_parse(_GOOD_SRC))
    assert _hotpath_refs(chatf)                 # Referenzen vorhanden
    assert _stray_hotpath_refs(chatf) == []     # aber keine ungebunden


@pytest.mark.unit
def test_detector_red_on_direct_hotpath_call() -> None:
    """RED-Gegenprobe: ein direkter ltm_service.recall_relevant(...) auf dem Hot-Path
    MUSS vom Guard erkannt werden."""
    chatf = _find_chat_func(_parse(_BAD_SRC))
    stray = _stray_hotpath_refs(chatf)
    assert stray, "RED-Gegenprobe versagt — direkter Call wurde NICHT erkannt."
    assert any(isinstance(r, ast.Attribute) and r.attr == "recall_relevant" for r in stray)

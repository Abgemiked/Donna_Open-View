"""DONNA-231 (Welle 1 / W1-A) — Regressions-Guard: LTM-Writes blockieren den Event-Loop nicht.

Hintergrund: Das Backend wurde nach LTM-Writes `unhealthy`, weil ein synchroner
`ltm_service.store_memory(...)`-Aufruf (ChromaDB/mem0-Netzwerk-I/O) direkt im async
Streaming-Generator des Chat-Pfads lief und damit den Event-Loop blockierte.

Fix (bereits im aktiven Code, routes/chat.py): Der Write wird per
`asyncio.to_thread(...)` aus dem Event-Loop ausgelagert.

Dieser Test sichert das dauerhaft ab:

  Teil A (Muster-DOKUMENTATION, nicht an chat.py gebunden): beweist das physikalische
          Verhalten des `asyncio.to_thread`-Musters — ein blockierender Call hält den
          Event-Loop frei, ein Direktaufruf blockiert ihn. Dient als lesbarer Beleg,
          WARUM der Fix wirkt; der eigentliche Produktions-Guard ist Teil B.

  Teil B (Bindung an routes/chat.py via AST — kein roher String-Grep): stellt sicher,
          dass im aktiven Chat-Router JEDE store_memory-Referenz ausschließlich
          innerhalb der Argumente eines Offload-Aufrufs (asyncio.to_thread /
          loop.run_in_executor) steht — robust gegen Aliasing (`fn = ltm.store_memory`)
          und gegen `getattr(ltm, "store_memory")` — UND dass dieser Offload awaited
          ist und in einer async-Funktion steht (sonst „nie awaited" = still broken).
"""
from __future__ import annotations

import ast
import asyncio
import time
from pathlib import Path

import pytest

import app.routes.chat as chat_module


# ══ Teil A: Muster-Dokumentation (Verhalten von asyncio.to_thread) ══════════════
# Hinweis: Diese beiden Tests binden NICHT an chat.py — sie dokumentieren, dass das
# Offload-Muster den Loop freihält (und ein Direktaufruf ihn blockiert). Die
# Produktions-Zusicherung leistet Teil B.

class _BlockingLTM:
    BLOCK_SECONDS = 0.2

    def __init__(self) -> None:
        self.calls: list[dict] = []

    def store_memory(self, *, session_id: str, content: str, category: str,
                     user_id: str | None = None) -> str:
        time.sleep(self.BLOCK_SECONDS)  # simuliert ChromaDB/mem0-Netzwerk-Latenz
        self.calls.append({"session_id": session_id, "content": content,
                           "category": category, "user_id": user_id})
        return "mem-id-123"


async def _count_heartbeats_during(awaitable_factory) -> tuple[int, object]:
    ticks = 0
    stop = asyncio.Event()

    async def _heartbeat() -> None:
        nonlocal ticks
        while not stop.is_set():
            ticks += 1
            await asyncio.sleep(0.005)

    hb = asyncio.create_task(_heartbeat())
    await asyncio.sleep(0.01)
    before = ticks
    result = await awaitable_factory()
    after = ticks
    stop.set()
    await hb
    return after - before, result


@pytest.mark.unit
@pytest.mark.asyncio
async def test_pattern_offloaded_write_keeps_loop_responsive() -> None:
    ltm = _BlockingLTM()

    async def _offloaded():
        return await asyncio.to_thread(
            ltm.store_memory, session_id="s", content="c", category="user_preference",
        )

    heartbeats, result = await _count_heartbeats_during(_offloaded)
    assert heartbeats >= 5, f"Loop blockiert (nur {heartbeats} Heartbeats) — Offload wirkt nicht"
    assert result == "mem-id-123"


@pytest.mark.unit
@pytest.mark.asyncio
async def test_pattern_direct_call_blocks_loop_control() -> None:
    ltm = _BlockingLTM()

    async def _blocking():
        return ltm.store_memory(session_id="s", content="x", category="user_fact")

    heartbeats, _ = await _count_heartbeats_during(_blocking)
    assert heartbeats <= 2, f"Erwartet Loop-Starvation, aber {heartbeats} Heartbeats"


# ══ Teil B: Produktions-Guard gegen routes/chat.py (AST) ════════════════════════

_OFFLOAD_NAMES = {"to_thread", "run_in_executor"}


def _chat_tree() -> ast.Module:
    return ast.parse(Path(chat_module.__file__).read_text(encoding="utf-8"))


def _parent_map(tree: ast.AST) -> dict[int, ast.AST]:
    parents: dict[int, ast.AST] = {}
    for node in ast.walk(tree):
        for child in ast.iter_child_nodes(node):
            parents[id(child)] = node
    return parents


def _is_offload_call(node: ast.AST) -> bool:
    """asyncio.to_thread(...), loop.run_in_executor(...) ODER bare to_thread(...)."""
    if not isinstance(node, ast.Call):
        return False
    f = node.func
    if isinstance(f, ast.Attribute) and f.attr in _OFFLOAD_NAMES:
        return True
    if isinstance(f, ast.Name) and f.id in _OFFLOAD_NAMES:  # from asyncio import to_thread
        return True
    return False


def _offload_calls(tree: ast.AST) -> list[ast.Call]:
    return [n for n in ast.walk(tree) if _is_offload_call(n)]


def _arg_node_ids(call: ast.Call) -> set[int]:
    ids: set[int] = set()
    for a in list(call.args) + [kw.value for kw in call.keywords]:
        for sub in ast.walk(a):
            ids.add(id(sub))
    return ids


def _store_memory_refs(tree: ast.AST) -> list[ast.AST]:
    """Jede Erwähnung von store_memory: als Attribut ODER via getattr(x,'store_memory')."""
    refs: list[ast.AST] = []
    for n in ast.walk(tree):
        if isinstance(n, ast.Attribute) and n.attr == "store_memory":
            refs.append(n)
        elif (
            isinstance(n, ast.Call)
            and isinstance(n.func, ast.Name)
            and n.func.id == "getattr"
            and len(n.args) >= 2
            and isinstance(n.args[1], ast.Constant)
            and n.args[1].value == "store_memory"
        ):
            refs.append(n)
    return refs


@pytest.mark.unit
def test_every_store_memory_ref_is_inside_an_offload_call() -> None:
    """store_memory darf im Chat-Router NUR innerhalb von to_thread/run_in_executor-
    Argumenten auftauchen — fängt Direktaufruf, Aliasing und getattr-Umgehung."""
    tree = _chat_tree()
    refs = _store_memory_refs(tree)
    assert refs, "Keine store_memory-Referenz in routes/chat.py gefunden (Pfad entfernt?)."

    allowed_ids: set[int] = set()
    for call in _offload_calls(tree):
        allowed_ids |= _arg_node_ids(call)

    stray = [r for r in refs if id(r) not in allowed_ids]
    assert not stray, (
        f"{len(stray)} store_memory-Referenz(en) außerhalb eines Offload-Aufrufs "
        "(Direktaufruf/Aliasing/getattr) — Event-Loop-Blockade-Risiko (DONNA-231)."
    )


@pytest.mark.unit
def test_store_memory_offload_is_awaited_in_async_function() -> None:
    """Der store_memory-Offload muss awaited sein UND in einer async-Funktion stehen —
    sonst läuft die Coroutine nie an ('never awaited') und bliebe still broken."""
    tree = _chat_tree()
    parents = _parent_map(tree)

    def _ancestors(node: ast.AST):
        cur = parents.get(id(node))
        while cur is not None:
            yield cur
            cur = parents.get(id(cur))

    sm_ids = {id(r) for r in _store_memory_refs(tree)}
    offloads_with_sm = [
        c for c in _offload_calls(tree)
        if _arg_node_ids(c) & sm_ids
    ]
    assert offloads_with_sm, "Kein Offload-Aufruf referenziert store_memory (Fix fehlt)."

    def _is_awaited_in_async(call: ast.Call) -> bool:
        ancestors = list(_ancestors(call))
        awaited = any(isinstance(a, ast.Await) for a in ancestors)
        in_async = any(isinstance(a, ast.AsyncFunctionDef) for a in ancestors)
        return awaited and in_async

    assert any(_is_awaited_in_async(c) for c in offloads_with_sm), (
        "store_memory-Offload ist nicht in `await ...` innerhalb einer async-Funktion "
        "eingebettet — Coroutine würde nie laufen (still broken, aber grün)."
    )

"""A5 — LTM-Cross-Chat-Kontinuität (#7, Stufe 3) + Review-Rat-Fixes (R2/R3/R5).

Donna soll auch in einem NEUEN Chat einen möglicherweise verwandten früheren Stand als
ANGEBOT (nicht als Fakt) einblenden: beim STM→LTM-Übergang wird eine Session-Zusammenfassung
"Stand/offene Punkte" als LTM-Kategorie `thread_state` abgelegt; beim Chatstart baut chat.py
daraus — relevanz-gegatet über recall_relevant(category="thread_state", min_score=…) — einen
[Möglicherweise verwandter früherer Stand]-Block mit NUR dem besten Treffer und Vorrang-Regel
(bei Widerspruch gilt Mikes aktuelle Aussage).

Live-Sicherheit (vom Review-Rat BESTÄTIGT, nicht lockern): thread_state ∈ _LIVE_BLOCKED_CATEGORIES
+ doppeltes Gate (dedizierter Block wird live nicht gebaut; der dedizierte Recall läuft live nicht).

Die Wiring-Tests nutzen AST (nicht Substring), damit die 5 R5-Mutationen garantiert rot werden:
Live-Gate raus · min_score=0 · session_start hart True · continuity aus beiden Prompt-Aufrufen ·
Formatter→Dummy.
"""
from __future__ import annotations

import ast
import inspect
import textwrap
from pathlib import Path

import pytest

from app.routes import chat as chat_mod
from app.routes.chat import (
    _LIVE_BLOCKED_CATEGORIES,
    _GENERAL_RECALL_EXCLUDED_CATEGORIES,
    _filter_live_ltm_memories,
    _filter_general_recall,
    _format_continuity_context,
    _build_prompt_context,
)
from app.jobs import stm_to_ltm as job_mod
from app.jobs.stm_to_ltm import (
    run_stm_to_ltm,
    _summarize_thread_state,
    _MIN_THREAD_STATE_LEN,
    _MAX_CONTENT_LEN,
)
from app.services.ltm_service import LTMService, _VALID_CATEGORIES
from app.services.prompt_builder import PromptBuilder

_CONTINUITY_HEADER = "[Möglicherweise verwandter früherer Stand]"
_THREAD_STATE_CONTENT = (
    "Thema Steuererklärung 2025: Mike hat die Belege sortiert, offen ist noch die Anlage EÜR."
)


def _hit(content: str = _THREAD_STATE_CONTENT, score: float = 0.7, **extra) -> dict:
    h = {"content": content, "category": "thread_state", "score": score, "session_id": "_stm_to_ltm_job"}
    h.update(extra)
    return h


def _build_both_prompts(continuity_context: str | None) -> tuple[str, str]:
    """Baut beide Prompt-Pfade exakt wie chat.py (_ctx_shared / _ctx_no_history)."""
    builder = PromptBuilder()
    ctx = _build_prompt_context("steuererklärung", [], [], continuity_context=continuity_context)
    p_user = builder.build_user_prompt(ctx, include_history=True)
    msgs = builder.build_messages(ctx, system_prompt="Du bist Donna.")
    p_messages = "\n\n".join(m["content"] for m in msgs)
    return p_user, p_messages


# ── A5.2: thread_state ist gültige LTM-Kategorie (wird nicht auf user_fact gemappt) ──

def test_thread_state_is_valid_ltm_category():
    assert "thread_state" in _VALID_CATEGORIES


def test_store_thread_state_keeps_category(tmp_path: Path):
    ltm = LTMService(db_path=str(tmp_path / "ltm_ts"))
    ltm.store_memory("sess1", _THREAD_STATE_CONTENT, "thread_state")
    all_mem = ltm.get_all()
    assert any(m["category"] == "thread_state" for m in all_mem)


def test_store_memory_accepts_infer_param(tmp_path: Path):
    # F4: store_memory muss einen infer-Param akzeptieren (ChromaDB ignoriert ihn,
    # mem0-Pfad reicht ihn an mem0.add weiter → verbatim, kein Haiku-Rewrite).
    ltm = LTMService(db_path=str(tmp_path / "ltm_infer"))
    mid = ltm.store_memory("sess1", _THREAD_STATE_CONTENT, "thread_state", infer=False)
    assert mid  # echte ID
    sig = inspect.signature(LTMService.store_memory)
    assert "infer" in sig.parameters


# ── (a) Relevanz-Gate: score>min_score → Block (beide Pfade); score<min_score → kein Block ──

def test_relevance_gate_hit_above_threshold_renders_block(tmp_path: Path):
    ltm = LTMService(db_path=str(tmp_path / "ltm_gate"))
    ltm.store_memory("sess1", _THREAD_STATE_CONTENT, "thread_state")
    query = "wie war der stand bei der steuererklärung"

    base = ltm.recall_relevant(query, category="thread_state", min_score=0.0)
    assert base, "thread_state-Treffer sollte ohne Gate gefunden werden"
    score = base[0]["score"]

    above = ltm.recall_relevant(query, category="thread_state", min_score=max(0.0, score - 0.01))
    block = _format_continuity_context(
        above, is_smalltalk=False, is_session_start=True, is_live=False, live_silent=False,
    )
    assert block is not None and _CONTINUITY_HEADER in block
    p_user, p_messages = _build_both_prompts(block)
    for prompt in (p_user, p_messages):
        assert _CONTINUITY_HEADER in prompt
        assert "Anlage EÜR" in prompt


def test_relevance_gate_hit_below_threshold_no_block(tmp_path: Path):
    ltm = LTMService(db_path=str(tmp_path / "ltm_gate2"))
    ltm.store_memory("sess1", _THREAD_STATE_CONTENT, "thread_state")
    query = "wie war der stand bei der steuererklärung"
    base = ltm.recall_relevant(query, category="thread_state", min_score=0.0)
    score = base[0]["score"]

    below = ltm.recall_relevant(query, category="thread_state", min_score=score + 0.01)
    assert below == []
    block = _format_continuity_context(
        below, is_smalltalk=False, is_session_start=True, is_live=False, live_silent=False,
    )
    assert block is None
    p_user, p_messages = _build_both_prompts(block)
    for prompt in (p_user, p_messages):
        assert _CONTINUITY_HEADER not in prompt


# ── (b) LIVE: thread_state NIE im Prompt — dedizierter Block UND allgemeiner Recall ──

def test_thread_state_in_live_blocked_categories():
    # RED wenn thread_state aus _LIVE_BLOCKED_CATEGORIES genommen wird.
    assert "thread_state" in _LIVE_BLOCKED_CATEGORIES


def test_live_dedicated_block_not_built():
    assert _format_continuity_context(
        [_hit()], is_smalltalk=False, is_session_start=True, is_live=True, live_silent=False,
    ) is None
    assert _format_continuity_context(
        [_hit()], is_smalltalk=False, is_session_start=True, is_live=False, live_silent=True,
    ) is None
    p_user, p_messages = _build_both_prompts(None)
    for prompt in (p_user, p_messages):
        assert _CONTINUITY_HEADER not in prompt


def test_live_thread_state_general_recall_dropped():
    # Zweiter Leak-Pfad: thread_state auch über den ALLGEMEINEN Recall (_filter_live_ltm_memories)
    # im Stream raus — selbst mit benignem Text, der das PII-Prädikat passiert.
    benign = [{"category": "thread_state", "content": "Mike plant einen Valorant-Stream nächste Woche."}]
    assert _filter_live_ltm_memories(benign) == []


# ── (c) Nur die ERSTE Nachricht der Session triggert den Block ──

def test_only_first_message_triggers_block():
    assert _format_continuity_context(
        [_hit()], is_smalltalk=False, is_session_start=True, is_live=False, live_silent=False,
    ) is not None
    assert _format_continuity_context(
        [_hit()], is_smalltalk=False, is_session_start=False, is_live=False, live_silent=False,
    ) is None


def test_smalltalk_no_block():
    assert _format_continuity_context(
        [_hit()], is_smalltalk=True, is_session_start=True, is_live=False, live_silent=False,
    ) is None


def test_empty_hits_no_block():
    assert _format_continuity_context(
        [], is_smalltalk=False, is_session_start=True, is_live=False, live_silent=False,
    ) is None


# ── R2 #5: Prompt neutral — kein vorgegebenes "stehengeblieben", kein Spekulieren, Vorrang-Regel ──

def test_prompt_is_neutral_offer_with_precedence_rule():
    block = _format_continuity_context(
        [_hit()], is_smalltalk=False, is_session_start=True, is_live=False, live_silent=False,
    )
    assert block is not None
    low = block.lower()
    # Nicht als Fakt/Phrase vorgeben, nicht zum Spekulieren auffordern:
    assert "stehengeblieben" not in low
    assert "fehlen könnte" not in low
    # Angebot + Vorrang-Regel + Veraltbarkeits-Hinweis:
    assert "veraltet" in low
    assert "aktuelle aussage" in low or "aktuellen aussage" in low


def test_prompt_only_best_hit():
    # R2: nur der BESTE (höchster Score) Treffer, nicht alle drei.
    hits = [
        _hit("Thema Alpha: offen ist Punkt A.", score=0.40),
        _hit("Thema Beta: offen ist Punkt B.", score=0.80),
        _hit("Thema Gamma: offen ist Punkt C.", score=0.50),
    ]
    block = _format_continuity_context(
        hits, is_smalltalk=False, is_session_start=True, is_live=False, live_silent=False,
    )
    assert block is not None
    assert "Beta" in block
    assert "Alpha" not in block and "Gamma" not in block


# ── R4/R1 #8: Defense-in-Depth — Koordinaten-Scrub + Infra-Filter auf Kontinuitätsinhalt ──

def test_continuity_content_coords_scrubbed():
    hits = [_hit("Treffpunkt bei YOUR_LAT,YOUR_LON noch offen.")]
    block = _format_continuity_context(
        hits, is_smalltalk=False, is_session_start=True, is_live=False, live_silent=False,
    )
    assert block is not None
    assert "YOUR_LAT" not in block
    assert "[Standort]" in block


def test_continuity_content_infra_dropped():
    hits = [_hit("Mike will den docker-container neu bauen.")]
    block = _format_continuity_context(
        hits, is_smalltalk=False, is_session_start=True, is_live=False, live_silent=False,
    )
    assert block is None


# ── R5 #11: Wiring per AST (RED-fähig für alle 5 Mutationen) ──

def _chat_tree() -> ast.AST:
    return ast.parse(textwrap.dedent(inspect.getsource(chat_mod.chat)))


def _is_str_const(node: ast.AST, value: str) -> bool:
    return isinstance(node, ast.Constant) and node.value == value


def _find_continuity_recall_call(tree: ast.AST) -> ast.Call | None:
    """Der (einzige) Call mit kwarg category="thread_state" — der dedizierte Kontinuitäts-Recall."""
    for n in ast.walk(tree):
        if isinstance(n, ast.Call) and any(
            kw.arg == "category" and _is_str_const(kw.value, "thread_state") for kw in n.keywords
        ):
            return n
    return None


def _find_continuity_gate_if(tree: ast.AST) -> ast.If | None:
    for node in ast.walk(tree):
        if isinstance(node, ast.If) and _find_continuity_recall_call(node) is not None:
            return node
    return None


def test_wiring_recall_gate_has_all_four_conditions():
    # (a) Gate = not is_smalltalk AND not _is_live AND not _live_silent AND _is_session_start.
    gate = _find_continuity_gate_if(_chat_tree())
    assert gate is not None, "Kontinuitäts-Gate-If nicht gefunden"
    names = {n.id for n in ast.walk(gate.test) if isinstance(n, ast.Name)}
    assert {"is_smalltalk", "_is_live", "_live_silent", "_is_session_start"} <= names
    negated = {
        n.operand.id
        for n in ast.walk(gate.test)
        if isinstance(n, ast.UnaryOp) and isinstance(n.op, ast.Not) and isinstance(n.operand, ast.Name)
    }
    assert {"is_smalltalk", "_is_live", "_live_silent"} <= negated, (
        "Live-/Smalltalk-Gate muss negiert sein (RED wenn not _is_live entfernt)"
    )


def test_wiring_recall_uses_continuity_min_score_and_category():
    # (b) min_score=settings.ltm_continuity_min_score + category="thread_state".
    call = _find_continuity_recall_call(_chat_tree())
    assert call is not None
    ms = next((kw.value for kw in call.keywords if kw.arg == "min_score"), None)
    assert isinstance(ms, ast.Attribute) and ms.attr == "ltm_continuity_min_score", (
        "min_score muss settings.ltm_continuity_min_score sein (RED bei min_score=0)"
    )


def test_wiring_continuity_passed_to_both_prompt_contexts():
    # (c) continuity_context=continuity_context an BEIDEN _build_prompt_context-Aufrufen.
    tree = _chat_tree()
    calls = [
        n for n in ast.walk(tree)
        if isinstance(n, ast.Call) and isinstance(n.func, ast.Name) and n.func.id == "_build_prompt_context"
    ]
    assert len(calls) >= 2
    for c in calls:
        assert any(
            kw.arg == "continuity_context"
            and isinstance(kw.value, ast.Name)
            and kw.value.id == "continuity_context"
            for kw in c.keywords
        ), "continuity_context fehlt an einem _build_prompt_context-Aufruf"


def test_wiring_session_start_from_len_history_zero():
    # (d) _is_session_start wird aus len(history) == 0 abgeleitet (RED bei Hardcode True).
    tree = _chat_tree()
    assigns = [
        n for n in ast.walk(tree)
        if isinstance(n, ast.Assign)
        and any(isinstance(t, ast.Name) and t.id == "_is_session_start" for t in n.targets)
    ]
    assert assigns, "_is_session_start-Zuweisung nicht gefunden"
    val = assigns[0].value
    has_len_history = any(
        isinstance(n, ast.Call) and isinstance(n.func, ast.Name) and n.func.id == "len"
        and any(isinstance(a, ast.Name) and a.id == "history" for a in n.args)
        for n in ast.walk(val)
    )
    has_zero = any(isinstance(n, ast.Constant) and n.value == 0 for n in ast.walk(val))
    assert has_len_history and has_zero


def test_wiring_session_start_hardened_with_stm_ok_and_provided():
    # R2 #6: Härtung — _is_session_start hängt zusätzlich an STM-Erfolg + mitgegebener session_id.
    tree = _chat_tree()
    assigns = [
        n for n in ast.walk(tree)
        if isinstance(n, ast.Assign)
        and any(isinstance(t, ast.Name) and t.id == "_is_session_start" for t in n.targets)
    ]
    val_names = {n.id for n in ast.walk(assigns[0].value) if isinstance(n, ast.Name)}
    assert "_stm_ok" in val_names
    assert "_session_id_provided" in val_names


def test_wiring_continuity_context_assigned_from_formatter():
    # R5' Mutation #5: continuity_context MUSS aus _format_continuity_context(...) stammen,
    # mit den vier Gate-Flags korrekt verdrahtet. Fängt:
    #   5a continuity_context = (lambda *a,**k: None)(...)  → func ist kein Name('_format_…')
    #   5b _dummy_ctx = _format_continuity_context(...)     → Target nicht continuity_context
    #   5c continuity_context = None                        → Value kein Call
    tree = _chat_tree()
    match = None
    for node in ast.walk(tree):
        if (isinstance(node, ast.Assign)
                and any(isinstance(t, ast.Name) and t.id == "continuity_context" for t in node.targets)
                and isinstance(node.value, ast.Call)
                and isinstance(node.value.func, ast.Name)
                and node.value.func.id == "_format_continuity_context"):
            match = node.value
    assert match is not None, (
        "continuity_context = _format_continuity_context(...) nicht gefunden "
        "(RED bei Mutation 5a/5b/5c)"
    )
    kw = {k.arg: k.value for k in match.keywords}
    expected = {
        "is_smalltalk": "is_smalltalk",
        "is_session_start": "_is_session_start",
        "is_live": "_is_live",
        "live_silent": "_live_silent",
    }
    for arg, name in expected.items():
        assert arg in kw, f"kwarg {arg} fehlt am Formatter-Aufruf"
        assert isinstance(kw[arg], ast.Name) and kw[arg].id == name, (
            f"{arg} muss an {name} gebunden sein (Gate-Verdrahtung)"
        )


def test_general_recall_exclusion_set_contents():
    # Single Source of Truth: der zentrale Ausschluss-Satz enthält thread_state UND media_seen.
    # RED, wenn media_seen (oder thread_state) aus dem Set genommen wird.
    assert "thread_state" in _GENERAL_RECALL_EXCLUDED_CATEGORIES
    assert "media_seen" in _GENERAL_RECALL_EXCLUDED_CATEGORIES


def test_general_recall_filter_drops_thread_state_and_media_seen():
    # SOLL #9 + #8 (Verhalten, offline): der zentrale Filter entfernt thread_state UND
    # media_seen aus dem allgemeinen Recall/Meta-Dump; harmlose Kategorien bleiben erhalten.
    mems = [
        {"category": "thread_state", "content": "Stand: Steuer offen Anlage EÜR."},
        {"category": "media_seen", "content": "Mike hat Breaking Bad gesehen"},
        {"category": "user_fact", "content": "Mike mag Pizza"},
        {"category": "user_preference", "content": "mag kurze Antworten"},
    ]
    out = _filter_general_recall(mems)
    cats = [m["category"] for m in out]
    assert "thread_state" not in cats
    assert "media_seen" not in cats
    assert all("Breaking Bad" not in m.get("content", "") for m in out)
    assert {"user_fact", "user_preference"} <= set(cats)   # harmlose Kategorien bleiben


def test_wiring_general_recall_uses_central_filter():
    # SOLL #9 (AST, nicht Substring): `ltm_memories` wird aus `_filter_general_recall(...)`
    # neu zugewiesen (EIN zentraler Filter, keine Divergenz). RED, wenn der Filteraufruf
    # entfernt oder durch eine abweichende Inline-Logik ersetzt wird.
    tree = _chat_tree()
    found = False
    for node in ast.walk(tree):
        if (isinstance(node, ast.Assign)
                and any(isinstance(t, ast.Name) and t.id == "ltm_memories" for t in node.targets)
                and isinstance(node.value, ast.Call)
                and isinstance(node.value.func, ast.Name)
                and node.value.func.id == "_filter_general_recall"):
            found = True
    assert found, "ltm_memories muss aus _filter_general_recall(...) stammen"


def test_wiring_general_recall_filter_covers_meta_and_is_unconditional():
    # F1 (R4): der _filter_general_recall-Ausschluss muss BEIDE Recall-Pfade abdecken
    # (get_all-Meta-Query UND recall_relevant) und darf NICHT hinter `if False:` oder nur
    # im is_meta_query-Zweig hängen. Sonst leaken bei "was weißt du über mich" (get_all)
    # offline die ~3028 media_seen-Einträge. RED gegen M2 (get_all ungefiltert) + M2c (if False).
    tree = _chat_tree()

    filter_assigns = [
        n for n in ast.walk(tree)
        if isinstance(n, ast.Assign)
        and any(isinstance(t, ast.Name) and t.id == "ltm_memories" for t in n.targets)
        and isinstance(n.value, ast.Call)
        and isinstance(n.value.func, ast.Name)
        and n.value.func.id == "_filter_general_recall"
    ]
    assert len(filter_assigns) == 1, "genau EINE zentrale _filter_general_recall-Zuweisung erwartet"
    filt = filter_assigns[0]

    # Eltern-Map für die Ancestor-Analyse
    parents: dict[int, ast.AST] = {}
    for node in ast.walk(tree):
        for child in ast.iter_child_nodes(node):
            parents[id(child)] = node

    cur = parents.get(id(filt))
    while cur is not None:
        if isinstance(cur, ast.If):
            # kein `if False:` um den Filter (M2c)
            assert not (isinstance(cur.test, ast.Constant) and cur.test.value is False), \
                "Filter steht unter `if False:` (M2c) — media_seen leakt wieder"
            # Filter darf nicht von is_meta_query abhängen (sonst nur ein Pfad gefiltert → M2)
            test_names = {n.id for n in ast.walk(cur.test) if isinstance(n, ast.Name)}
            assert "is_meta_query" not in test_names, \
                "Filter hängt an is_meta_query — get_all- ODER recall_relevant-Pfad ungefiltert (M2)"
        cur = parents.get(id(cur))

    # Der Filter läuft textuell NACH dem get_all-Recall (Meta-Query); sonst bliebe get_all
    # ungefiltert. get_all steht als Attribut-Argument von _ltm_call_bounded (kein eigener Call).
    getall_lines = [
        n.lineno for n in ast.walk(tree)
        if isinstance(n, ast.Attribute) and n.attr == "get_all"
    ]
    assert getall_lines, "get_all-Recall im Meta-Pfad nicht gefunden"
    assert filt.lineno > max(getall_lines), \
        "Filter läuft vor get_all → Meta-Query (get_all) ungefiltert (M2)"


# ── R3 F5: stm_to_ltm — _summarize_thread_state + Job-Loop ──

class _FakeMistral:
    def __init__(self, *, facts="[]", thread="Stand: Mike plant Steuer, offen Anlage EÜR.", raise_on=None):
        self.prompts: list[str] = []
        self._facts = facts
        self._thread = thread
        self._raise_on = set(raise_on or ())

    def ready(self) -> bool:
        return True

    async def generate(self, system: str = "", prompt: str = "") -> str:
        self.prompts.append(prompt)
        kind = "thread" if "Fasse den STAND" in prompt else "facts"
        if kind in self._raise_on:
            raise RuntimeError("boom")
        return self._thread if kind == "thread" else self._facts

    def thread_prompts(self) -> list[str]:
        return [p for p in self.prompts if "Fasse den STAND" in p]


class _FakeLTM:
    def __init__(self, store_return: str = "id-1"):
        self.calls: list[dict] = []
        self._ret = store_return

    def store_memory(self, session_id: str, content: str, category: str, infer: bool = True, user_id=None) -> str:
        self.calls.append({"session_id": session_id, "content": content, "category": category, "infer": infer})
        return self._ret

    def thread_state_calls(self) -> list[dict]:
        return [c for c in self.calls if c["category"] == "thread_state"]


class _FakeSTM:
    def __init__(self, sessions: dict):
        self._sessions = sessions

    async def get_all_sessions(self, hours: int = 24) -> dict:
        return self._sessions


def _msgs(n: int = 8) -> list[dict]:
    return [{"role": "user" if i % 2 == 0 else "assistant", "content": f"Nachricht {i}"} for i in range(n)]


# -- _summarize_thread_state --

async def test_summarize_none_returns_none():
    # Text ist ABSICHTLICH ≥ _MIN_THREAD_STATE_LEN, damit das Entfernen des NONE-Guards
    # (und nicht nur des Längen-Checks) rot wird — reiner Längen-Check würde "NONE" sonst
    # ohnehin verwerfen und den fehlenden NONE-Guard verdecken.
    m = _FakeMistral(thread="NONE, kein fortlaufendes Thema vorhanden")
    assert len("NONE, kein fortlaufendes Thema vorhanden") >= _MIN_THREAD_STATE_LEN
    assert await _summarize_thread_state("irgendein gespräch", m) is None


async def test_summarize_too_short_returns_none():
    m = _FakeMistral(thread="ok")  # < _MIN_THREAD_STATE_LEN
    assert len("ok") < _MIN_THREAD_STATE_LEN
    assert await _summarize_thread_state("x", m) is None


async def test_summarize_strips_fences():
    m = _FakeMistral(thread="```\nStand: Thema offen Punkt Z.\n```")
    out = await _summarize_thread_state("x", m)
    assert out == "Stand: Thema offen Punkt Z."


async def test_summarize_exception_returns_none():
    m = _FakeMistral(raise_on={"thread"})
    assert await _summarize_thread_state("x", m) is None


async def test_summarize_uses_conversation_tail_f1():
    # F1: [-3000:] → der STAND am ENDE landet im Prompt, der Anfang wird abgeschnitten.
    conv = "HEADMARKER " + ("füllwort " * 2000) + "TAILMARKER"
    assert len(conv) > 3000
    m = _FakeMistral()
    await _summarize_thread_state(conv, m)
    prompt = m.thread_prompts()[0]
    assert "TAILMARKER" in prompt
    assert "HEADMARKER" not in prompt


async def test_summarize_caps_length():
    m = _FakeMistral(thread="x" * 2000)
    out = await _summarize_thread_state("y", m)
    assert out is not None and len(out) == _MAX_CONTENT_LEN


# -- Job-Loop --

async def test_job_stores_thread_state_verbatim_infer_false():
    stm = _FakeSTM({"s1": _msgs(8)})
    ltm = _FakeLTM()
    m = _FakeMistral(thread="Stand: Steuer offen Anlage EÜR.")
    res = await run_stm_to_ltm(stm, ltm, m)
    ts = ltm.thread_state_calls()
    assert len(ts) == 1
    assert ts[0]["content"] == "Stand: Steuer offen Anlage EÜR."  # verbatim
    assert ts[0]["infer"] is False  # F4: kein Haiku-Rewrite
    assert res["thread_states"] == 1


async def test_job_no_thread_state_below_min_messages():
    stm = _FakeSTM({"s1": _msgs(7)})
    ltm = _FakeLTM()
    m = _FakeMistral()
    await run_stm_to_ltm(stm, ltm, m)
    assert ltm.thread_state_calls() == []
    assert m.thread_prompts() == []  # gar kein Summary-Call


async def test_job_thread_state_runs_despite_facts_error():
    # Isolation: Fakten-Extraktion wirft, thread_state läuft trotzdem.
    stm = _FakeSTM({"s1": _msgs(8)})
    ltm = _FakeLTM()
    m = _FakeMistral(raise_on={"facts"})
    await run_stm_to_ltm(stm, ltm, m)
    assert len(ltm.thread_state_calls()) == 1


async def test_job_thread_state_runs_on_non_list_json_f2():
    # F2: Fakten-JSON ist Dict (kein list) → thread_state MUSS trotzdem laufen.
    # RED mit altem `continue` (übersprang den thread_state-Block).
    stm = _FakeSTM({"s1": _msgs(8)})
    ltm = _FakeLTM()
    m = _FakeMistral(facts="{}")
    await run_stm_to_ltm(stm, ltm, m)
    assert len(ltm.thread_state_calls()) == 1


async def test_job_counter_only_on_real_write_f3():
    # F3: store_memory liefert "" (z.B. PII-Filter) → Counter NICHT erhöhen.
    stm = _FakeSTM({"s1": _msgs(8)})
    ltm = _FakeLTM(store_return="")
    m = _FakeMistral(thread="Stand: Thema offen Punkt.")
    res = await run_stm_to_ltm(stm, ltm, m)
    assert len(ltm.thread_state_calls()) == 1  # Schreibversuch fand statt
    assert res["thread_states"] == 0  # aber nicht gezählt


async def test_job_one_thread_state_per_qualified_session():
    stm = _FakeSTM({"s1": _msgs(8), "s2": _msgs(10)})
    ltm = _FakeLTM()
    m = _FakeMistral(thread="Stand: Thema offen Punkt.")
    res = await run_stm_to_ltm(stm, ltm, m)
    assert len(ltm.thread_state_calls()) == 2
    assert len(m.thread_prompts()) == 2
    assert res["thread_states"] == 2

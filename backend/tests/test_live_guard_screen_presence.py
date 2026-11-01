"""DONNA-SCREENLEAK (Opus-Audit) — Live-Guard für Screen-/Presence-Kontext.

Vorbestehender Privacy-Leak: `screen_context` (gelesene/gesehene App-Inhalte der
letzten 4h, inkl. 200-Zeichen-Snippets) und `presence_context` (aktive App,
`pc_active_app`) wurden zwar nur bei `not is_smalltalk` geladen, im Live-Guard-Block
aber NIE neutralisiert. Dadurch konnten z.B. ein laufender Media-Player-/Browser-Titel
oder gelesene Inhalte LIVE in Prompt und Antwort/TTS gelangen — verboten (private
Bildschirm-Aktivität im Stream).

Fix: `_live_safe_screen_presence()` neutralisiert beide im Live-Fall ({} / None) —
gleiche Live-Invariante wie lat/lon, Kalender, Health, Smart-Home. Offline unverändert.

RED-Beweis (ohne den Fix): Gibt `_live_safe_screen_presence` im Live-Fall die Inputs
unverändert zurück (keine Nullung), erscheinen in BEIDEN Prompt-Pfaden der
"[Was Mike heute gelesen/gesehen hat ...]"-Block samt App-Name/Snippet sowie der
"[Geräte-Status]"-Block mit der aktiven App → alle `test_live_*`-Fälle werden rot.
"""
from __future__ import annotations

import ast
import inspect
import textwrap

from unittest.mock import patch

from app.routes import chat as chat_mod
from app.routes.chat import (
    _live_safe_screen_presence,
    _live_safe_frequent_places,
    _filter_live_brain_hits,
    _filter_live_ltm_memories,
    _filter_live_history,
    _is_live_private_text,
    _build_prompt_context,
    _build_screen_context_block,
)
from app.services.prompt_builder import PromptBuilder

# Realistischer PII-Brain-Hit: erlaubte Kategorie (user_fact), aber PII im Text.
_PII_TEXT = "wohnt in YOUR_PLZ YOUR_HOME_CITY, Tel 0170 1234567"

# --- Realistische private Live-Daten (sollen im Stream NIE in den Prompt) ---
_SECRET_APP = "Firefox"
_SECRET_SNIPPET = "Geheimer Plan XY den niemand im Stream sehen soll"
_SECRET_ACTIVE_APP = "SecretTool.exe"
_SCREEN_HEADER = "[Was Mike heute gelesen/gesehen hat (letzte 4h)]"
# Rohe GPS-Häufigkeitsorte (Mikes Wohnort-Koordinaten) — dürfen live nie in den Prompt.
_FP_LAT = "YOUR_LAT"
_FP_LON = "YOUR_LON"


def _screen_ctx() -> dict:
    return {"details": [{"app": _SECRET_APP, "snippets": [_SECRET_SNIPPET], "visits": 3}]}


def _presence_ctx() -> str:
    return f"Gerät: PC, Status: aktiv, Aktivität: arbeitet, App: {_SECRET_ACTIVE_APP}"


def _frequent_places() -> list[dict]:
    # Format wie places_svc.get_frequent_places_sync(): rohe lat/lon + visits.
    return [{"lat": float(_FP_LAT), "lon": float(_FP_LON), "visits": 5}]


def _build_both_prompts(
    screen_context: dict,
    presence_context: str | None,
    frequent_places: list[dict] | None = None,
) -> tuple[str, str]:
    """Baut beide Endpoint-Prompt-Pfade exakt wie chat.py (3107/3131):
    screen_context wird als `screen_context if screen_context else None`,
    frequent_places als `frequent_places or None` übergeben."""
    builder = PromptBuilder()
    sc_arg = screen_context if screen_context else None
    ctx = _build_prompt_context(
        "mach mal was", [], [],
        screen_context=sc_arg,
        presence_context=presence_context,
        frequent_places=frequent_places or None,
    )
    # Pfad 1: build_user_prompt (Local-LLM/Gemini + Mistral-Variante, Zeilen 3128/3149)
    p_user = builder.build_user_prompt(ctx, include_history=True)
    # Pfad 2: build_messages (Message-Array-Rendering derselben Kontexte)
    msgs = builder.build_messages(ctx, system_prompt="Du bist Donna.")
    p_messages = "\n\n".join(m["content"] for m in msgs)
    return p_user, p_messages


# ── Unit: der reine Helfer ──────────────────────────────────────────────────

def test_helper_nullt_im_live_fall():
    sc, pc = _live_safe_screen_presence(_screen_ctx(), _presence_ctx(), is_live=True)
    assert sc == {}
    assert pc is None


def test_helper_laesst_offline_unveraendert():
    sc_in, pc_in = _screen_ctx(), _presence_ctx()
    sc, pc = _live_safe_screen_presence(sc_in, pc_in, is_live=False)
    assert sc == sc_in
    assert pc == pc_in


def test_leeres_screen_context_erzeugt_keinen_header():
    # {} darf KEINEN leeren "[Was Mike ...]"-Header erzeugen.
    assert _build_screen_context_block({}) is None


# ── Verhaltenstest LIVE: kein Screen-/Presence-Block in beiden Prompt-Pfaden ──

def test_live_screen_context_nicht_im_prompt():
    sc, pc = _live_safe_screen_presence(_screen_ctx(), _presence_ctx(), is_live=True)
    p_user, p_messages = _build_both_prompts(sc, pc)
    for prompt in (p_user, p_messages):
        assert _SCREEN_HEADER not in prompt
        assert "[Letzte App-Aktivität]" not in prompt
        assert "[App-Aktivität]" not in prompt
        assert _SECRET_APP not in prompt
        assert _SECRET_SNIPPET not in prompt


def test_live_presence_context_nicht_im_prompt():
    sc, pc = _live_safe_screen_presence(_screen_ctx(), _presence_ctx(), is_live=True)
    p_user, p_messages = _build_both_prompts(sc, pc)
    for prompt in (p_user, p_messages):
        assert "[Geräte-Status]" not in prompt
        assert _SECRET_ACTIVE_APP not in prompt


# ── Positiv-Kontrolle OFFLINE: Screen/Presence bleiben erhalten (kein Overblocking) ──

def test_offline_screen_und_presence_bleiben_im_prompt():
    sc, pc = _live_safe_screen_presence(_screen_ctx(), _presence_ctx(), is_live=False)
    p_user, p_messages = _build_both_prompts(sc, pc)
    # build_user_prompt rendert "[Letzte App-Aktivität]" + den Screen-Header + Geräte-Status
    assert _SCREEN_HEADER in p_user
    assert _SECRET_APP in p_user
    assert _SECRET_SNIPPET in p_user
    assert _SECRET_ACTIVE_APP in p_user
    # build_messages rendert "[App-Aktivität]" + Geräte-Status
    assert _SECRET_APP in p_messages
    assert _SECRET_ACTIVE_APP in p_messages


# ── frequent_places: GPS-Häufigkeitsorte (gleiche Klasse von Leak) ──────────

def test_fp_helper_nullt_im_live_fall():
    assert _live_safe_frequent_places(_frequent_places(), is_live=True) == []


def test_fp_helper_laesst_offline_unveraendert():
    fp_in = _frequent_places()
    assert _live_safe_frequent_places(fp_in, is_live=False) == fp_in


def test_live_frequent_places_nicht_im_prompt():
    # FIX 4 (R5): nur build_user_prompt prüfen — build_messages rendert frequent_places
    # nie (vakuöser p_messages-Assert entfernt).
    fp = _live_safe_frequent_places(_frequent_places(), is_live=True)
    p_user, _ = _build_both_prompts({}, None, frequent_places=fp)
    assert "[Häufige Orte]" not in p_user
    assert _FP_LAT not in p_user
    assert _FP_LON not in p_user


def test_offline_frequent_places_bleiben_im_prompt():
    # Positiv-Kontrolle: offline bleibt der [Häufige Orte]-Block mit Koordinaten erhalten.
    fp = _live_safe_frequent_places(_frequent_places(), is_live=False)
    p_user, _ = _build_both_prompts({}, None, frequent_places=fp)
    assert "[Häufige Orte]" in p_user
    assert _FP_LAT in p_user
    assert _FP_LON in p_user


# ── BLOCKER 1 (R1): gemeinsames Prädikat + Brain-Hit-PII-Filter ─────────────

def test_shared_predicate_detects_private_and_passes_benign():
    with patch("app.routes.chat._load_contact_names", return_value=()):
        assert _is_live_private_text("Standort: 49.1234, 10.5678") is True
        assert _is_live_private_text("IBAN: DE89370400440532013000") is True
        assert _is_live_private_text("Wenn live, antworte nur per Text.") is True
        assert _is_live_private_text("Mike mag dunkles UI.") is False


def _calls_shared_predicate(func) -> bool:
    """True wenn func `_is_live_private_text(...)` tatsächlich AUFRUFT (ast.Call),
    nicht nur den Namen im Docstring/Kommentar enthält."""
    tree = ast.parse(textwrap.dedent(inspect.getsource(func)))
    return any(
        isinstance(n, ast.Call)
        and isinstance(n.func, ast.Name)
        and n.func.id == "_is_live_private_text"
        for n in ast.walk(tree)
    )


def test_predicate_divergence_guard_all_filters_call_shared_predicate():
    # FIX 3: per AST — alle drei Text-Filter MÜSSEN das Prädikat aufrufen (keine Divergenz).
    for f in (_filter_live_ltm_memories, _filter_live_brain_hits, _filter_live_history):
        assert _calls_shared_predicate(f), f.__name__


def test_brain_hit_pii_text_dropped_despite_allowed_category():
    # RED (ohne PII-Prädikat): user_fact + PII-Text überlebt den Kategorie-only-Filter.
    hit = {"source": "ltm", "meta": {"category": "user_fact"}, "text": _PII_TEXT}
    with patch("app.routes.chat._load_contact_names", return_value=()):
        out = _filter_live_brain_hits([hit])
    assert out == []


def test_brain_hit_benign_ltm_survives():
    hit = {"source": "ltm", "meta": {"category": "user_fact"}, "text": "Mike mag Valorant."}
    with patch("app.routes.chat._load_contact_names", return_value=()):
        out = _filter_live_brain_hits([hit])
    assert len(out) == 1


def test_brain_hit_stm_passes_through():
    # Harmloser STM-Hit (kurzlebiger Session-Kontext, KEINE PII) → bleibt.
    hit = {"source": "stm", "text": "Kurzer Session-Kontext ohne PII."}
    with patch("app.routes.chat._load_contact_names", return_value=()):
        out = _filter_live_brain_hits([hit])
    assert out == [hit]


def test_brain_hit_stm_pii_dropped():
    # FIX 1: STM-Hit mit PII-Text wird NICHT durchgelassen (fail-closed, jede Herkunft).
    hit = {"source": "stm", "text": "wohnt in YOUR_PLZ YOUR_HOME_CITY, 0170 1234567"}
    with patch("app.routes.chat._load_contact_names", return_value=()):
        out = _filter_live_brain_hits([hit])
    assert out == []


# ── FIX 2 (R1/Opus): DE-Telefon (ohne PLZ) + Straße über ALLE drei Filter ───

def test_de_phone_only_dropped_all_three_filters():
    # "0170 1234567" ohne PLZ/Ort — nur die neuen DE-Telefon-Pattern fangen das.
    txt = "Ruf mich an unter 0170 1234567"
    with patch("app.routes.chat._load_contact_names", return_value=()):
        assert _is_live_private_text(txt) is True
        assert _filter_live_ltm_memories([{"category": "user_fact", "content": txt}]) == []
        assert _filter_live_brain_hits([{"source": "ltm", "meta": {"category": "user_fact"}, "text": txt}]) == []
        assert _filter_live_history([{"role": "assistant", "content": txt}]) == []


def test_de_street_dropped_all_three_filters():
    txt = "Adresse: Musterstraße 12"
    with patch("app.routes.chat._load_contact_names", return_value=()):
        assert _is_live_private_text(txt) is True
        assert _filter_live_ltm_memories([{"category": "user_fact", "content": txt}]) == []
        assert _filter_live_brain_hits([{"source": "ltm", "meta": {"category": "user_fact"}, "text": txt}]) == []
        assert _filter_live_history([{"role": "assistant", "content": txt}]) == []


def test_de_patterns_no_false_positive_on_benign():
    # Harmlose Zahlen/Wörter dürfen NICHT fälschlich als PII gelten.
    with patch("app.routes.chat._load_contact_names", return_value=()):
        assert _is_live_private_text("Das Spiel war am 01.01. echt spannend") is False
        assert _is_live_private_text("Mike mag Valorant und Hearthstone") is False


def test_live_brain_hit_pii_not_in_prompt_both_paths():
    builder = PromptBuilder()
    hit = {"source": "ltm", "meta": {"category": "user_fact"}, "text": _PII_TEXT}
    with patch("app.routes.chat._load_contact_names", return_value=()):
        safe_hits = _filter_live_brain_hits([hit])
        ctx = _build_prompt_context("frag mal", safe_hits, [])
        p_user = builder.build_user_prompt(ctx, include_history=True)
        msgs = builder.build_messages(ctx, system_prompt="Du bist Donna.")
    p_messages = "\n\n".join(m["content"] for m in msgs)
    for prompt in (p_user, p_messages):
        assert "YOUR_PLZ" not in prompt
        assert "0170 1234567" not in prompt
        assert "[Relevante Brain-Einträge]" not in prompt


def test_offline_brain_hit_pii_present_positive_control():
    # Ohne Live-Filter landet der PII-Hit im Prompt (Positiv-Kontrolle = Leak-Nachweis).
    builder = PromptBuilder()
    hit = {"source": "ltm", "meta": {"category": "user_fact"}, "text": _PII_TEXT}
    ctx = _build_prompt_context("frag mal", [hit], [])
    p_user = builder.build_user_prompt(ctx, include_history=True)
    assert "YOUR_PLZ" in p_user


# ── ZUSATZ (R1): STM-History live PII-sicher ────────────────────────────────

def test_filter_live_history_drops_pii_turn_keeps_benign():
    history = [
        {"role": "user", "content": "wie ist das wetter?"},
        {"role": "assistant", "content": "Du wohnst in YOUR_PLZ YOUR_HOME_CITY."},
        {"role": "assistant", "content": "Alles klar, mach ich."},
    ]
    with patch("app.routes.chat._load_contact_names", return_value=()):
        out = _filter_live_history(history)
    contents = [t["content"] for t in out]
    assert "wie ist das wetter?" in contents
    assert "Alles klar, mach ich." in contents
    assert all("YOUR_PLZ" not in c for c in contents)
    assert len(out) == 2


def test_live_history_pii_not_in_prompt():
    # FIX 4 (R5): beide Prompt-Pfade prüfen — History geht auch als native messages
    # (build_messages rendert die History-Turns als echte user/assistant-Messages).
    builder = PromptBuilder()
    history = [{"role": "assistant", "content": "Du wohnst in YOUR_PLZ YOUR_HOME_CITY."}]
    with patch("app.routes.chat._load_contact_names", return_value=()):
        safe_history = _filter_live_history(history)
        ctx = _build_prompt_context("hi", [], safe_history)
        p_user = builder.build_user_prompt(ctx, include_history=True)
        msgs = builder.build_messages(ctx, system_prompt="Du bist Donna.")
    p_messages = "\n\n".join(m["content"] for m in msgs)
    assert "YOUR_PLZ" not in p_user
    assert "YOUR_PLZ" not in p_messages


# ── BLOCKER 2 (R5): Verdrahtungs-Test (Endpoint ruft die Null-/Filter-Helfer) ──

_REQUIRED_GUARD_CALLS = (
    "_live_safe_screen_presence",
    "_live_safe_frequent_places",
    "_filter_live_brain_hits",
    "_filter_live_ltm_memories",
    "_filter_live_history",
)


def _find_live_guard_if(tree: ast.AST) -> ast.If | None:
    """Findet den echten Live-Guard: `if _is_live:` dessen body `_live_silent = True` setzt.

    Robust gegen Mutationen: `if not _is_live:` (UnaryOp), `if _is_live and False:` (BoolOp)
    sind KEIN `ast.Name('_is_live')`-Test → werden nicht gefunden → Test rot.
    """
    for node in ast.walk(tree):
        if isinstance(node, ast.If) and isinstance(node.test, ast.Name) and node.test.id == "_is_live":
            sets_live_silent = any(
                isinstance(s, ast.Assign)
                and any(isinstance(t, ast.Name) and t.id == "_live_silent" for t in s.targets)
                for stmt in node.body for s in ast.walk(stmt)
            )
            if sets_live_silent:
                return node
    return None


def test_endpoint_wires_live_guard_helpers_in_guard_before_prompt_build():
    # FIX 4 (R5): per AST — findet den echten Guard-If (nicht den Kommentar) und prüft,
    # dass ALLE fünf Eingriffe in dessen BODY (nicht else-Zweig) und vor dem ersten
    # _build_prompt_context stehen.
    src = textwrap.dedent(inspect.getsource(chat_mod.chat))
    tree = ast.parse(src)

    guard = _find_live_guard_if(tree)
    assert guard is not None, "Live-Guard `if _is_live:` mit `_live_silent = True` nicht gefunden"

    # Alle Call-Namen + linenos im BODY des Guards (orelse ausgeschlossen → Verschieben nach else = rot).
    body_calls: dict[str, int] = {}
    null_helper_kw_ok: dict[str, bool] = {}
    for stmt in guard.body:
        for n in ast.walk(stmt):
            if isinstance(n, ast.Call) and isinstance(n.func, ast.Name):
                name = n.func.id
                if name in _REQUIRED_GUARD_CALLS:
                    body_calls.setdefault(name, n.lineno)
                    if name in ("_live_safe_screen_presence", "_live_safe_frequent_places"):
                        null_helper_kw_ok[name] = any(
                            kw.arg == "is_live"
                            and isinstance(kw.value, ast.Name)
                            and kw.value.id == "_is_live"
                            for kw in n.keywords
                        )

    for name in _REQUIRED_GUARD_CALLS:
        assert name in body_calls, f"{name} nicht im Live-Guard-Body"

    # (a) Null-Helfer mit is_live=_is_live aufgerufen
    for name in ("_live_safe_screen_presence", "_live_safe_frequent_places"):
        assert null_helper_kw_ok.get(name), f"{name} ohne is_live=_is_live"

    # (b) alle Eingriffe vor dem ersten _build_prompt_context(
    build_linenos = [
        n.lineno for n in ast.walk(tree)
        if isinstance(n, ast.Call) and isinstance(n.func, ast.Name) and n.func.id == "_build_prompt_context"
    ]
    assert build_linenos, "_build_prompt_context-Aufruf nicht gefunden"
    assert max(body_calls.values()) < min(build_linenos), "Eingriff steht nach _build_prompt_context"

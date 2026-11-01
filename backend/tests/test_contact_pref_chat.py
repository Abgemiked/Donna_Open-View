"""chat.py-Integrationstests für #1b (inkl. FIX-RUNDE 1 + 2).

Schwerpunkt FIX-RUNDE 2: ECHTE Verhaltenstests statt AST/Substring für den Kernpfad.
  * _record_contact_pref_actions: patch(record_contact) — Args/Reihenfolge/topic/tz-aware/
    Gate (web+injection)/null-Name/nicht-contact/Alias.
  * _maybe_contact_pref_block: Call-Site-Live-Gate — is_live/live_silent/smalltalk →
    KEIN Store-Read (Spy) + kein Block; offline → Read + Block.
  * _build_contact_pref_context: Rendering (now_slot/overall/topic/timeslot), Marker ä,
    fail-closed (RED-Beweis dokumentiert), M4-Sentinel.
  * _contact_pref_record_args: null-Name isinstance-Fix.
  * Read-Block im eigenen contact_pref_context-Feld (build_user_prompt).
  * System-Prompt-Doku: topic-Enum + [Kontakt-Präferenz] (ä) + Bestätigungspflicht.
"""
from __future__ import annotations

from datetime import datetime, timezone
from unittest.mock import MagicMock, patch

import pytest

from app.routes import chat as chat_mod
from app.routes.chat import (
    _build_contact_pref_context,
    _build_prompt_context,
    _contact_pref_record_args,
    _maybe_contact_pref_block,
    _record_contact_pref_actions,
)
from app.services import contact_pref_service as cps
from app.services.prompt_builder import PromptBuilder

_NOW = datetime(2026, 1, 15, 9, 0, tzinfo=timezone.utc)  # 10:00 Berlin = morning


def _summary(name="Ämi", overall="whatsapp", by_topic=None, by_timeslot=None, now_slot=None):
    d = {"name": name, "overall": overall, "by_topic": by_topic or {}, "by_timeslot": by_timeslot or {}}
    if now_slot:
        d["now_slot"] = now_slot
    return d


# ── _record_contact_pref_actions: echter Verhaltenstest ──────────────────────

@pytest.mark.asyncio
async def test_record_actions_ruft_record_mit_richtigen_args():
    rec = MagicMock(return_value=True)
    with patch.object(cps, "record_contact", rec):
        await _record_contact_pref_actions(
            [{"type": "whatsapp", "name": "Ämi", "message": "hi", "topic": "arbeit"}],
            now=_NOW, searched_web=False, injection_detected=False,
        )
    rec.assert_called_once()
    args, kwargs = rec.call_args
    assert args[0] == "Ämi"
    assert args[1] == "whatsapp"
    assert args[2] is _NOW and args[2].tzinfo is not None  # tz-aware durchgereicht
    assert kwargs == {"topic": "arbeit"}


@pytest.mark.asyncio
async def test_record_actions_null_name_kein_record():
    rec = MagicMock(return_value=True)
    with patch.object(cps, "record_contact", rec):
        await _record_contact_pref_actions(
            [{"type": "whatsapp", "name": None},       # echtes JSON null → skip
             {"type": "call", "name": "   "},          # leer → skip
             {"type": "set_alarm", "time": "08:00"}],  # nicht-contact → skip
            now=_NOW, searched_web=False, injection_detected=False,
        )
    rec.assert_not_called()


@pytest.mark.asyncio
async def test_record_actions_gate_web_und_injection():
    rec = MagicMock(return_value=True)
    acts = [{"type": "whatsapp", "name": "Ämi"}]
    with patch.object(cps, "record_contact", rec):
        await _record_contact_pref_actions(acts, now=_NOW, searched_web=True, injection_detected=False)
        await _record_contact_pref_actions(acts, now=_NOW, searched_web=False, injection_detected=True)
    rec.assert_not_called()


@pytest.mark.asyncio
async def test_record_actions_alias_wird_kanonisch():
    rec = MagicMock(return_value=True)
    with patch.object(cps, "record_contact", rec):
        await _record_contact_pref_actions(
            [{"type": "whatsapp", "name": "schatz"}],
            now=_NOW, searched_web=False, injection_detected=False,
        )
    assert rec.call_args[0][0] == "Ämi"  # Alias → kanonisch (via _normalize_action)


@pytest.mark.asyncio
async def test_record_actions_fail_safe_bei_record_exception():
    rec = MagicMock(side_effect=RuntimeError("boom"))
    with patch.object(cps, "record_contact", rec):
        # Darf NICHT propagieren.
        await _record_contact_pref_actions(
            [{"type": "whatsapp", "name": "Ämi"}],
            now=_NOW, searched_web=False, injection_detected=False,
        )


# ── _contact_pref_record_args: null-Name isinstance-Fix ──────────────────────

def test_record_args_null_name_isinstance():
    # JSON null → None → NICHT str("None")!
    assert _contact_pref_record_args(
        {"type": "whatsapp", "name": None}, searched_web=False, injection_detected=False,
    ) is None


def test_record_args_mit_topic():
    assert _contact_pref_record_args(
        {"type": "call", "name": " Bob ", "topic": "arbeit"},
        searched_web=False, injection_detected=False,
    ) == ("Bob", "call", "arbeit")


# ── _maybe_contact_pref_block: Call-Site-Live-Gate (echtes Verhalten) ────────

@pytest.mark.asyncio
async def test_maybe_block_live_kein_store_read_kein_block():
    spy = MagicMock(return_value=_summary())
    with patch.object(cps, "contact_pref_block_for", spy):
        out = await _maybe_contact_pref_block(
            "schreib Ämi", is_smalltalk=False, is_live=True, live_silent=False, now=_NOW,
        )
    assert out is None
    spy.assert_not_called()  # FAIL-CLOSED: kein Store-Read im Live-Fall


@pytest.mark.asyncio
async def test_maybe_block_live_silent_kein_read():
    spy = MagicMock(return_value=_summary())
    with patch.object(cps, "contact_pref_block_for", spy):
        out = await _maybe_contact_pref_block(
            "schreib Ämi", is_smalltalk=False, is_live=False, live_silent=True, now=_NOW,
        )
    assert out is None
    spy.assert_not_called()


@pytest.mark.asyncio
async def test_maybe_block_smalltalk_kein_read():
    spy = MagicMock(return_value=_summary())
    with patch.object(cps, "contact_pref_block_for", spy):
        out = await _maybe_contact_pref_block(
            "hallo", is_smalltalk=True, is_live=False, live_silent=False, now=_NOW,
        )
    assert out is None
    spy.assert_not_called()


@pytest.mark.asyncio
async def test_maybe_block_offline_liest_und_baut():
    spy = MagicMock(return_value=_summary(overall="call", now_slot="morning"))
    with patch.object(cps, "contact_pref_block_for", spy):
        out = await _maybe_contact_pref_block(
            "ruf Ämi an", is_smalltalk=False, is_live=False, live_silent=False,
            now=_NOW, aliases={"schatz": "Ämi"},
        )
    spy.assert_called_once()
    assert out is not None
    assert "[Kontakt-Präferenz]" in out
    assert "Anruf" in out


# ── _build_contact_pref_context: Rendering + fail-closed + Sentinel ──────────

def test_block_render_now_slot_overall_topic_timeslot():
    block = _build_contact_pref_context(
        "egal", is_live=False, live_silent=False,
        block_data=_summary(overall="whatsapp", by_topic={"arbeit": "call"},
                            by_timeslot={"evening": "sms"}, now_slot="morning"),
    )
    assert "[Kontakt-Präferenz]" in block        # Marker mit ä
    assert "Jetzt: morgens" in block             # F1: aktueller Slot
    assert "Insgesamt meist: WhatsApp" in block
    assert "arbeit→Anruf" in block
    assert "abends→SMS" in block
    assert "bestätigungspflichtig" in block


def test_block_fail_closed_live():
    assert _build_contact_pref_context(
        "schreib Ämi", is_live=True, live_silent=False, block_data=_summary(),
    ) is None


def test_block_sentinel_none_bedeutet_kein_block_kein_synchroner_recall():
    # block_data=None (explizit) → kein Block, KEIN synchroner contact_pref_block_for-Call.
    spy = MagicMock(return_value=_summary())
    with patch.object(cps, "contact_pref_block_for", spy):
        out = _build_contact_pref_context(
            "schreib Ämi", is_live=False, live_silent=False, block_data=None,
        )
    assert out is None
    spy.assert_not_called()  # M4: None heißt „geladen, nichts gefunden", kein Re-Call


def test_block_sentinel_unset_laedt_synchron_nur_fuer_tests():
    spy = MagicMock(return_value=_summary(overall="sms"))
    with patch.object(cps, "contact_pref_block_for", spy):
        out = _build_contact_pref_context("schreib Ämi", is_live=False, live_silent=False)
    spy.assert_called_once()
    assert "SMS" in out


# ── Read-Block im eigenen Feld (build_user_prompt) ───────────────────────────

def test_block_im_eigenen_feld_nicht_continuity():
    block = _build_contact_pref_context(
        "egal", is_live=False, live_silent=False, block_data=_summary(overall="call"),
    )
    builder = PromptBuilder()
    ctx = _build_prompt_context("frag", [], [], contact_pref_context=block)
    assert ctx.contact_pref_context == block
    assert ctx.continuity_context is None
    p_user = builder.build_user_prompt(ctx, include_history=True)
    assert "[Kontakt-Präferenz]" in p_user and "Anruf" in p_user


def test_live_block_nicht_im_prompt_positiv_kontrolle():
    block = _build_contact_pref_context(
        "schreib Ämi", is_live=True, live_silent=False, block_data=_summary(),
    )
    builder = PromptBuilder()
    ctx = _build_prompt_context("frag", [], [], contact_pref_context=block)
    assert "[Kontakt-Präferenz]" not in builder.build_user_prompt(ctx)


# ── System-Prompt-Doku ───────────────────────────────────────────────────────

def test_system_prompt_doku_topic_enum_marker_ae():
    instr = chat_mod._ACTION_INSTRUCTIONS
    assert "topic(optional)" in instr
    for t in cps.KNOWN_TOPICS:
        assert t in instr
    assert "[Kontakt-Präferenz]" in instr   # Marker mit ä, konsistent zum Block
    assert "bestaetigungspflichtig" in instr.lower()

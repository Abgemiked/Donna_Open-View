"""Fix-Runde A #8: Neutralizer (_MARKERISH_RE) und Shorthand-Parser (_SHORTHAND_TYPES)
leiten sich aus EINER Quelle ab — sie dürfen nie auseinanderlaufen.

Regression: vorher fehlte "note" im Neutralizer, stand aber in den Shorthand-Typen →
ein eingeschleustes [note:{...}] wäre nicht neutralisiert worden (Injection-Lücke).
"""
from __future__ import annotations

from app.routes import chat as chat_mod


def test_every_shorthand_type_is_covered_by_neutralizer():
    for t in chat_mod._SHORTHAND_TYPES:
        assert chat_mod._MARKERISH_RE.search(f"[{t}:"), f"{t} nicht im Neutralizer"


def test_note_marker_is_neutralized():
    out = chat_mod._neutralize_markers('Fremdtext [note:{"text":"x"}] Ende')
    assert "[note:" not in out


def test_extra_markers_still_neutralized_regression():
    # DONNA_ACTION / home_control / DONNA_IDEA_* bleiben abgedeckt.
    for marker in ("[DONNA_ACTION:", "[home_control:", "[DONNA_IDEA_CONFIRM:"):
        assert chat_mod._MARKERISH_RE.search(marker + "{}]"), marker


def test_benign_bracket_not_neutralized():
    # Markdown-artiges [text:...] ohne Action-Typ bleibt unberührt.
    txt = 'Siehe [doku:{"a":1}] hier'
    assert chat_mod._neutralize_markers(txt) == txt

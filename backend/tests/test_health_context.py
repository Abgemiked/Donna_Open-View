"""Tests für den Gesundheits-Kontext (Samsung Health / Health Connect) im Chat.

Deckt _format_health_context (Prompt-Block-Bau) und _HEALTH_RE (Keyword-Gate) ab.
Gesundheitsdaten sind privat: der Block wird NUR bei echtem Keyword-Treffer injiziert.
"""
from app.routes.chat import _format_health_context, _HEALTH_RE


# --- _format_health_context ---

def test_format_full():
    out = _format_health_context({
        "sleep_hours": 6.5, "sleep_stage": "rem", "steps_today": 1200,
        "resting_hr": 54, "stress_score": 30, "spo2": 97,
    })
    assert out is not None
    assert "Schlaf (letzte 24h): 6.5 h, Phase rem" in out
    assert "Schritte heute: 1200" in out
    assert "Ruhepuls: 54 bpm" in out
    assert "Stress-Score: 30/100" in out
    assert "SpO2: 97 %" in out


def test_format_partial_only_sleep():
    out = _format_health_context({"sleep_hours": 7.0})
    assert out is not None
    assert "Schlaf (letzte 24h): 7.0 h" in out
    assert "Phase" not in out       # keine Phase angegeben
    assert "Schritte" not in out    # kein Schritt-Wert → nicht erfunden


def test_format_none_input():
    assert _format_health_context(None) is None


def test_format_empty_dict():
    assert _format_health_context({}) is None


def test_format_dict_without_known_keys():
    # Daten da, aber keine bekannten Felder → kein leerer Block
    assert _format_health_context({"foo": "bar"}) is None


def test_format_zero_values_shown():
    # 0 Schritte ist ein gültiger Wert (is not None), nicht weglassen
    out = _format_health_context({"steps_today": 0})
    assert out is not None
    assert "Schritte heute: 0" in out


# --- _HEALTH_RE: Keyword-Gate ---

import pytest


@pytest.mark.parametrize("msg", [
    "wie hab ich geschlafen",
    "war mein Schlaf gut?",
    "bin ich ausgeschlafen",
    "wie viele Schritte heute",
    "wie ist mein Ruhepuls",
    "wie ist meine Herzfrequenz",
    "wie fit bin ich",
    "mein Stress heute",
    "wie ist mein SpO2",
    "bin ich erholt",
])
def test_health_re_matches(msg):
    assert _HEALTH_RE.search(msg) is not None


@pytest.mark.parametrize("msg", [
    "mach das Licht an",
    "wie ist das Wetter",
    "navigiere nach Hause",
    "spiel Musik",
    "wie spät ist es",
])
def test_health_re_no_false_positive(msg):
    assert _HEALTH_RE.search(msg) is None

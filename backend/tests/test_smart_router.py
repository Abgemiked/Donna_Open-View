"""Unit tests for SmartRouter — 15+ cases across all heuristics."""
from __future__ import annotations

import pytest

from app.services.smart_router import SmartRouter


@pytest.fixture
def router() -> SmartRouter:
    return SmartRouter(length_limit=6000, crm_allowlist=("Max Mustermann", "Firma XY GmbH"))


# ---- PII cases ---------------------------------------------------------------

def test_iban_de_routes_local(router):
    d = router.decide(prompt="Meine IBAN ist DE89 3704 0044 0532 0130 00, bitte merken.")
    assert d.route == "local"
    assert d.reason == "pii_detected"
    assert "iban_de" in d.matched


def test_steuer_id_routes_local(router):
    d = router.decide(prompt="Steuer-ID: 12345678901")
    assert d.route == "local"
    assert "steuer_id" in d.matched


def test_steuernummer_routes_local(router):
    d = router.decide(prompt="Meine Steuernummer lautet 21/815/08150.")
    assert d.route == "local"
    assert "steuernummer" in d.matched


def test_phone_de_routes_local(router):
    d = router.decide(prompt="Ruf mich unter +49 30 12345678 an.")
    assert d.route == "local"
    assert "phone_de" in d.matched


def test_address_routes_local(router):
    d = router.decide(prompt="Adresse: 10115 Berlin")
    assert d.route == "local"
    assert "address_plz_city" in d.matched


def test_email_routes_local(router):
    d = router.decide(prompt="Mail an your-email@example.com")
    assert d.route == "local"
    assert "email" in d.matched


# ---- CRM allowlist -----------------------------------------------------------

def test_crm_name_routes_local(router):
    d = router.decide(prompt="Was war nochmal der Termin mit Max Mustermann?")
    assert d.route == "local"
    assert d.reason == "crm_person_match"


def test_crm_company_routes_local(router):
    d = router.decide(prompt="Offene Rechnung bei firma xy gmbh pruefen")
    # keyword 'rechnung' triggers first (pii? no. keyword? yes)
    assert d.route == "local"


# ---- Tags / keywords ---------------------------------------------------------

def test_privat_tag_routes_local(router):
    d = router.decide(prompt="Note #privat: heute war anstrengend")
    assert d.route == "local"
    assert d.reason == "sensitive_tag"


def test_intern_tag_routes_local(router):
    d = router.decide(prompt="#intern — geplante Umstrukturierung")
    assert d.route == "local"


def test_passwort_keyword_routes_local(router):
    d = router.decide(prompt="Wo speichere ich mein Passwort am besten?")
    assert d.route == "local"
    assert d.reason == "sensitive_keyword"


def test_gehalt_keyword_routes_local(router):
    d = router.decide(prompt="Wie hoch war mein Gehalt letzten Monat?")
    assert d.route == "local"


# ---- Length heuristic --------------------------------------------------------

def test_long_prompt_routes_gemini(router):
    big = "a" * 8000
    d = router.decide(prompt=big)
    assert d.route == "gemini"
    assert d.reason == "length_exceeds_local_context"


def test_long_context_routes_gemini(router):
    d = router.decide(prompt="Zusammenfassung bitte", context="b" * 7000)
    assert d.route == "gemini"


# ---- Default -----------------------------------------------------------------

def test_generic_question_routes_gemini(router):
    d = router.decide(prompt="Wie schreibt man eine gute E-Mail?")
    assert d.route == "gemini"
    assert d.reason == "default"


def test_wetter_routes_gemini_with_search(router):
    d = router.decide(prompt="Wie wird das Wetter morgen in Berlin?")
    # address-regex would catch "10115 Berlin" but plain "Berlin" alone is fine
    # "wetter" → realtime_search (Stufe 5)
    assert d.route == "gemini"
    assert d.reason == "realtime_search"
    assert d.enable_search is True
    assert "wetter" in d.matched


def test_code_question_routes_gemini(router):
    d = router.decide(prompt="Wie schreibe ich eine List Comprehension in Python?")
    assert d.route == "gemini"


def test_empty_prompt_routes_gemini_default(router):
    d = router.decide(prompt="hi")
    assert d.route == "gemini"


# ---- Ordering / priority ----------------------------------------------------

def test_pii_beats_length(router):
    long_with_iban = "a" * 7000 + " IBAN: DE89370400440532013000 " + "b" * 100
    d = router.decide(prompt=long_with_iban)
    assert d.route == "local"
    assert d.reason == "pii_detected"


def test_word_boundary_keyword_does_not_match_substring(router):
    # 'steuer' is a sensitive keyword — must not fire on 'abgesteuert'
    d = router.decide(prompt="Das Auto wurde abgesteuert in die Kurve.")
    assert d.route == "gemini"


# ---- Alternative A: Memory/Recall → local ------------------------------------

def test_memory_recall_routes_local(router):
    d = router.decide(prompt="Erinnerst du dich noch an das Gespräch letzte Woche?")
    assert d.route == "local"
    assert d.reason == "memory_recall"
    assert "erinnerst" in d.matched


def test_du_hast_gesagt_routes_local(router):
    d = router.decide(prompt="Du hast gesagt ich soll das erledigen.")
    assert d.route == "local"
    assert d.reason == "memory_recall"


# ---- Alternative B: Realtime/Stream → gemini + search -----------------------

def test_stream_routes_gemini_with_search(router):
    d = router.decide(prompt="Bist du live auf stream heute?")
    assert d.route == "gemini"
    assert d.reason == "realtime_search"
    assert d.enable_search is True


def test_twitch_routes_gemini_with_search(router):
    d = router.decide(prompt="Was passiert gerade auf Twitch?")
    assert d.route == "gemini"
    assert d.reason == "realtime_search"
    assert d.enable_search is True


def test_default_has_enable_search_false(router):
    d = router.decide(prompt="Erkläre mir Photosynthese.")
    assert d.route == "gemini"
    assert d.enable_search is False

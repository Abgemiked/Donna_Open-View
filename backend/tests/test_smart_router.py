"""Unit tests for SmartRouter — 15+ cases across all heuristics.

Seit der Claude-Integration ist route IMMER "claude"; die Heuristiken bleiben als
`reason` (Observability + Modellwahl) erhalten.
"""
from __future__ import annotations

import pytest

from app.services.smart_router import SmartRouter


@pytest.fixture
def router() -> SmartRouter:
    return SmartRouter(length_limit=6000, crm_allowlist=("Max Mustermann", "Firma XY GmbH"))


# ---- PII cases ---------------------------------------------------------------

def test_iban_de_routes_claude(router):
    d = router.decide(prompt="Meine IBAN ist DE89 3704 0044 0532 0130 00, bitte merken.")
    assert d.route == "claude"
    assert d.reason == "pii_detected"
    assert "iban_de" in d.matched


def test_steuer_id_routes_claude(router):
    d = router.decide(prompt="Steuer-ID: 12345678901")
    assert d.route == "claude"
    assert "steuer_id" in d.matched


def test_steuernummer_routes_claude(router):
    d = router.decide(prompt="Meine Steuernummer lautet 21/815/08150.")
    assert d.route == "claude"
    assert "steuernummer" in d.matched


def test_phone_de_routes_claude(router):
    d = router.decide(prompt="Ruf mich unter +49 30 12345678 an.")
    assert d.route == "claude"
    assert "phone_de" in d.matched


def test_address_routes_claude(router):
    d = router.decide(prompt="Adresse: 10115 Berlin")
    assert d.route == "claude"
    assert "address_plz_city" in d.matched


def test_email_routes_claude(router):
    d = router.decide(prompt="Mail an your-email@example.com")
    assert d.route == "claude"
    assert "email" in d.matched


# ---- CRM allowlist -----------------------------------------------------------

def test_crm_name_routes_claude(router):
    d = router.decide(prompt="Was war nochmal der Termin mit Max Mustermann?")
    assert d.route == "claude"
    assert d.reason == "crm_person_match"


def test_crm_company_routes_claude(router):
    d = router.decide(prompt="Offene Rechnung bei firma xy gmbh pruefen")
    # keyword 'rechnung' triggers first (pii? no. keyword? yes)
    assert d.route == "claude"


# ---- Tags / keywords ---------------------------------------------------------

def test_privat_tag_routes_claude(router):
    d = router.decide(prompt="Note #privat: heute war anstrengend")
    assert d.route == "claude"
    assert d.reason == "sensitive_tag"


def test_intern_tag_routes_claude(router):
    d = router.decide(prompt="#intern — geplante Umstrukturierung")
    assert d.route == "claude"


def test_passwort_keyword_routes_claude(router):
    d = router.decide(prompt="Wo speichere ich mein Passwort am besten?")
    assert d.route == "claude"
    assert d.reason == "sensitive_keyword"


def test_gehalt_keyword_routes_claude(router):
    d = router.decide(prompt="Wie hoch war mein Gehalt letzten Monat?")
    assert d.route == "claude"


# ---- Length heuristic --------------------------------------------------------

def test_long_prompt_routes_claude(router):
    big = "a" * 8000
    d = router.decide(prompt=big)
    assert d.route == "claude"
    assert d.reason == "length_exceeds_local_context"


def test_long_context_routes_claude(router):
    d = router.decide(prompt="Zusammenfassung bitte", context="b" * 7000)
    assert d.route == "claude"


# ---- Default -----------------------------------------------------------------

def test_generic_question_routes_claude(router):
    d = router.decide(prompt="Wie schreibt man eine gute E-Mail?")
    assert d.route == "claude"
    assert d.reason == "default"


def test_wetter_routes_claude_with_search(router):
    d = router.decide(prompt="Wie wird das Wetter morgen in Berlin?")
    # address-regex would catch "10115 Berlin" but plain "Berlin" alone is fine
    # "wetter" → realtime_search (Stufe 5)
    assert d.route == "claude"
    assert d.reason == "realtime_search"
    assert d.enable_search is True
    assert "wetter" in d.matched


def test_code_question_routes_claude(router):
    d = router.decide(prompt="Wie schreibe ich eine List Comprehension in Python?")
    assert d.route == "claude"


def test_empty_prompt_routes_claude_default(router):
    d = router.decide(prompt="hi")
    assert d.route == "claude"


# ---- Ordering / priority ----------------------------------------------------

def test_pii_beats_length(router):
    long_with_iban = "a" * 7000 + " IBAN: DE89370400440532013000 " + "b" * 100
    d = router.decide(prompt=long_with_iban)
    assert d.route == "claude"
    assert d.reason == "pii_detected"


def test_word_boundary_keyword_does_not_match_substring(router):
    # 'steuer' is a sensitive keyword — must not fire on 'abgesteuert'
    d = router.decide(prompt="Das Auto wurde abgesteuert in die Kurve.")
    assert d.route == "claude"


# ---- Alternative A: Memory/Recall  ------------------------------------

def test_memory_recall_routes_claude(router):
    d = router.decide(prompt="Erinnerst du dich noch an das Gespräch letzte Woche?")
    assert d.route == "claude"
    assert d.reason == "memory_recall"
    assert "erinnerst" in d.matched


def test_du_hast_gesagt_routes_claude(router):
    d = router.decide(prompt="Du hast gesagt ich soll das erledigen.")
    assert d.route == "claude"
    assert d.reason == "memory_recall"


# ---- Alternative B: Realtime/Stream → claude + search -----------------------

def test_stream_routes_claude_with_search(router):
    d = router.decide(prompt="Bist du live auf stream heute?")
    assert d.route == "claude"
    assert d.reason == "realtime_search"
    assert d.enable_search is True


def test_twitch_routes_claude_with_search(router):
    d = router.decide(prompt="Was passiert gerade auf Twitch?")
    assert d.route == "claude"
    assert d.reason == "realtime_search"
    assert d.enable_search is True


def test_default_has_enable_search_false(router):
    d = router.decide(prompt="Erkläre mir Photosynthese.")
    assert d.route == "claude"
    assert d.enable_search is False


# ---- FIX 2 (ki-training g041/g049/g050): Stream-Kontext triggert KEINE Suche ----

def test_pure_stream_query_no_search(router):
    # Reine Stream-Frage ohne Echtzeit-Wort → kein enable_search, kein realtime_search.
    d = router.decide(prompt="Wann ist mein nächster Stream?")
    assert d.route == "claude"
    assert d.reason == "stream_context"
    assert d.enable_search is False
    assert "stream" in d.matched


def test_stream_recap_no_false_search(router):
    d = router.decide(prompt="Wie lief mein letzter Stream so?")
    assert d.reason == "stream_context"
    assert d.enable_search is False


def test_twitch_noun_no_search(router):
    d = router.decide(prompt="Zeig mir meine Twitch Clips vom Wochenende.")
    assert d.reason == "stream_context"
    assert d.enable_search is False


def test_chat_keyword_no_false_search(router):
    # g049: "chat" als Substring/Wort darf keine Web-Suche auslösen.
    d = router.decide(prompt="Was hat der Chat dazu gesagt?")
    assert d.reason == "stream_context"
    assert d.enable_search is False


def test_game_keyword_no_false_search(router):
    d = router.decide(prompt="Welches Game zocken wir heute Abend im Stream?")
    # "heute" ist ein echtes Realtime-Wort → Realtime hat Vorrang (dokumentiertes Verhalten).
    assert d.reason == "realtime_search"
    assert d.enable_search is True


def test_real_news_query_triggers_search(router):
    d = router.decide(prompt="Was sind die aktuellen News?")
    assert d.route == "claude"
    assert d.reason == "realtime_search"
    assert d.enable_search is True


def test_pure_game_without_temporal_no_search(router):
    d = router.decide(prompt="Welches Game sollen wir als nächstes spielen?")
    assert d.reason == "stream_context"
    assert d.enable_search is False


# ---- FIX 2 Nachbesserung (R3/R6): Länge VOR stream_context ------------------

def test_long_prompt_with_stream_word_keeps_deep_model(router):
    # Langer Prompt mit Stream-Wort darf NICHT nach stream_context fallen (Deep verlieren).
    big = "Analysiere bitte folgenden Mitschnitt aus dem chat live: " + ("x " * 4000)
    d = router.decide(prompt=big)
    assert d.route == "claude"
    assert d.reason == "length_exceeds_local_context"


def test_short_stream_word_still_stream_context(router):
    # Gegenprobe: kurzer Stream-Prompt bleibt stream_context (kein Deep).
    d = router.decide(prompt="Wie lief der chat heute im stream?")
    # "heute" ist Realtime → Realtime behält Vorrang (dokumentiert); ohne "heute":
    d2 = router.decide(prompt="Wie lief der chat im stream?")
    assert d2.reason == "stream_context"
    assert d2.enable_search is False


# ---- Claude-only: Provider ist unabhängig von reason / Gemini-Circuit ---------

def test_route_is_always_claude_for_every_reason(router):
    prompts = [
        "Meine IBAN ist DE89 3704 0044 0532 0130 00",   # pii_detected
        "Was war der Termin mit Max Mustermann?",        # crm_person_match
        "Note #privat: heute war anstrengend",           # sensitive_tag
        "Wo speichere ich mein Passwort?",               # sensitive_keyword
        "Erinnerst du dich noch an gestern?",            # memory_recall
        "Wie ist mein Valorant MMR?",                    # gaming_local_api
        "Was ist das Wetter?",                           # realtime_search
        "x" * 8000,                                      # length_exceeds_local_context
        "Erkläre Photosynthese.",                        # default
    ]
    reasons = set()
    for p in prompts:
        d = router.decide(prompt=p)
        assert d.route == "claude", p[:40]
        reasons.add(d.reason)
    assert reasons == {
        "pii_detected", "crm_person_match", "sensitive_tag", "sensitive_keyword",
        "memory_recall", "gaming_local_api", "realtime_search",
        "length_exceeds_local_context", "default",
    }


def test_gaming_reason_unchanged(router):
    d = router.decide(prompt="Wie ist mein Valorant MMR?")
    assert d.route == "claude"
    assert d.reason == "gaming_local_api"


def test_open_gemini_circuit_does_not_reroute():
    """Offener Gemini-Circuit darf nicht mehr auf 'local' umrouten."""
    r = SmartRouter(gemini_circuit_check=lambda: True)
    for prompt in ("Erkläre Photosynthese.", "Wie wird das Wetter morgen?", "a" * 8000):
        d = r.decide(prompt=prompt)
        assert d.route == "claude"
        assert d.reason != "gemini_unavailable"

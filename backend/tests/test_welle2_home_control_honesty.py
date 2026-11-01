"""DONNA Welle 2 — Smart-Home-Ehrlichkeit (FU-4 + FU-3).

FU-4: zentrale HCOutcome-Konstanten (verhaltensneutrales Refactoring) — Drift-Schutz
      zwischen Producer-Returns und _HC_FAIL_REASONS + Beweis der Stringgleichheit.
FU-3: No-Marker-False-Success — Donna behauptet einen Schalt-Vollzug, obwohl in der
      Antwort KEINE home_control-Aktion erfolgreich war. Korrektur NUR bei echtem
      Schaltbefehl (keine Status-Frage) + unbestätigter Vollzugs-Behauptung.

Der physische Schaltpfad (_execute_single_home_control, Gates, Allowlist, Tesla-Deny)
wird von FU-3 NICHT berührt — rein textuelle Post-Korrektur.
"""
from __future__ import annotations

import pytest

from app.routes.chat import (
    HCOutcome,
    _HC_FAIL_REASONS,
    _FALSE_SUCCESS_CORRECTION_TEXT,
    _home_control_block_reason,
    _execute_single_home_control,
    _is_switch_command,
    _claims_unbacked_switch,
    _false_success_correction,
    _should_append_false_success,
    _smarthome_intent,
    _live_output_filter,
)


# ══════════════════════════ FU-4 — Konstanten/Drift ══════════════════════════

def test_fu4_fail_reasons_keys_match_enum():
    # Drift-Schutz: jede Reason-Map-Key ist ein gültiger HCOutcome-Code und umgekehrt.
    assert set(_HC_FAIL_REASONS.keys()) == HCOutcome.ALL


def test_fu4_outcome_values_are_exact_strings():
    # Beweis Verhaltensneutralität: die Codes sind WORTGLEICH zu den früheren Literalen.
    assert HCOutcome.BLOCKED_INJECTION == "blocked_injection"
    assert HCOutcome.BLOCKED_WEB == "blocked_web"
    assert HCOutcome.BLOCKED_PII == "blocked_pii"
    assert HCOutcome.BLOCKED_LIVE == "blocked_live"
    assert HCOutcome.BLOCKED_LIVE_LOCKED == "blocked_live_locked"
    assert HCOutcome.NO_USER_INTENT == "no_user_intent"
    assert HCOutcome.TOO_MANY == "too_many"
    assert HCOutcome.HA_DISABLED == "ha_disabled"
    assert HCOutcome.NOT_ALLOWED == "not_allowed"
    assert HCOutcome.HA_ERROR == "ha_error"


def test_fu4_constants_are_plain_str_not_enum():
    # Kein Enum-Wrapper: type IS str → JSON-Serialisierung, str()-Lookup in
    # _HC_FAIL_REASONS und structlog-Ausgabe bleiben exakt wie vor dem Refactor.
    for code in HCOutcome.ALL:
        assert type(code) is str


def test_fu4_block_reason_returns_exact_literals():
    # _home_control_block_reason liefert weiterhin die identischen Strings.
    assert _home_control_block_reason(
        is_live=True, trusted_direct=False, domain="light", entity_id="light.x",
        live_guard_active=False, searched_web=False, injection_detected=False,
    ) == "blocked_live"
    assert _home_control_block_reason(
        is_live=False, trusted_direct=True, domain="light", entity_id="light.x",
        live_guard_active=False, searched_web=False, injection_detected=True,
    ) == "blocked_injection"
    assert _home_control_block_reason(
        is_live=True, trusted_direct=True, domain="script", entity_id="script.x",
        live_guard_active=False, searched_web=False, injection_detected=False,
    ) == "blocked_live_locked"


class _FakeHA:
    def __init__(self, enabled: bool = True, raise_exc: Exception | None = None):
        self.enabled = enabled
        self.calls: list[tuple] = []
        self._raise = raise_exc

    async def call_service(self, domain, service, entity_id, data):
        self.calls.append((domain, service, entity_id, data))
        if self._raise is not None:
            raise self._raise


async def test_fu4_execute_returns_exact_error_literals():
    # Kein-Intent-Pfad liefert weiterhin exakt "no_user_intent" (plain str).
    ha = _FakeHA()
    outcome = await _execute_single_home_control(
        {"type": "home_control", "domain": "light", "service": "turn_off",
         "entity_id": "light.x"},
        hc_live=False, trusted_direct=True, live_guard_active=False,
        searched_web=False, injection_detected=False, user_wants_smarthome=False,
        rate_limited=False, ha_service=ha,
    )
    assert outcome["error"] == "no_user_intent"
    assert type(outcome["error"]) is str


# Auflage 6: Drift-Test parametrisiert über ALLE Producer-Literale — echter Rückgabewert
# der echten Funktionen == exakter String == HCOutcome.*.
async def test_fu4_drift_all_execute_literals():
    from app.services.homeassistant_service import HANotAllowed, HAError
    base = {"type": "home_control", "domain": "light", "service": "turn_on",
            "entity_id": "light.x"}
    kw = dict(hc_live=False, trusted_direct=True, live_guard_active=False,
              searched_web=False, injection_detected=False)

    # no_user_intent
    oc = await _execute_single_home_control(
        base, **kw, user_wants_smarthome=False, rate_limited=False, ha_service=_FakeHA())
    assert oc["error"] == "no_user_intent" == HCOutcome.NO_USER_INTENT

    # too_many
    oc = await _execute_single_home_control(
        base, **kw, user_wants_smarthome=True, rate_limited=True, ha_service=_FakeHA())
    assert oc["error"] == "too_many" == HCOutcome.TOO_MANY

    # ha_disabled (ha_service None)
    oc = await _execute_single_home_control(
        base, **kw, user_wants_smarthome=True, rate_limited=False, ha_service=None)
    assert oc["error"] == "ha_disabled" == HCOutcome.HA_DISABLED

    # not_allowed
    oc = await _execute_single_home_control(
        base, **kw, user_wants_smarthome=True, rate_limited=False,
        ha_service=_FakeHA(raise_exc=HANotAllowed("nope")))
    assert oc["error"] == "not_allowed" == HCOutcome.NOT_ALLOWED

    # ha_error
    oc = await _execute_single_home_control(
        base, **kw, user_wants_smarthome=True, rate_limited=False,
        ha_service=_FakeHA(raise_exc=HAError("boom")))
    assert oc["error"] == "ha_error" == HCOutcome.HA_ERROR

    # alle Codes sind plain str (JSON/str()/Logging verhaltensneutral)
    for code in (HCOutcome.NO_USER_INTENT, HCOutcome.TOO_MANY, HCOutcome.HA_DISABLED,
                 HCOutcome.NOT_ALLOWED, HCOutcome.HA_ERROR):
        assert type(code) is str


@pytest.mark.parametrize("block_kw,expected", [
    (dict(is_live=True, trusted_direct=False, domain="light", entity_id="light.x",
          live_guard_active=False, searched_web=False, injection_detected=False),
     HCOutcome.BLOCKED_LIVE),
    (dict(is_live=True, trusted_direct=True, domain="script", entity_id="script.x",
          live_guard_active=False, searched_web=False, injection_detected=False),
     HCOutcome.BLOCKED_LIVE_LOCKED),
    (dict(is_live=False, trusted_direct=True, domain="light", entity_id="light.x",
          live_guard_active=False, searched_web=False, injection_detected=True),
     HCOutcome.BLOCKED_INJECTION),
    (dict(is_live=False, trusted_direct=True, domain="light", entity_id="light.x",
          live_guard_active=False, searched_web=True, injection_detected=False),
     HCOutcome.BLOCKED_WEB),
    (dict(is_live=False, trusted_direct=True, domain="light", entity_id="light.x",
          live_guard_active=True, searched_web=False, injection_detected=False),
     HCOutcome.BLOCKED_PII),
])
def test_fu4_drift_all_block_reason_literals(block_kw, expected):
    assert _home_control_block_reason(**block_kw) == expected
    assert type(expected) is str


# ══════════════════════════ FU-3 — Command vs. Query ══════════════════════════

def test_fu3_is_switch_command_imperatives_true():
    for msg in [
        "mach die Softboxen aus",
        "schalt das Licht an",
        "Licht im Streamingzimmer aus",
        "alles aus im Wohnzimmer",
        "dimm das Licht",
        "Regallicht einschalten",
        "mach das Licht aus",            # ohne Satzzeichen (Voice)
    ]:
        assert _is_switch_command(msg) is True, msg


def test_fu3_is_switch_command_status_queries_false():
    # Kritischer FP-Pfad: Status-Fragen dürfen NIE als Befehl gelten.
    for msg in [
        "ist das Licht aus?",
        "ist das licht aus",             # ohne Fragezeichen (Voice)
        "wie warm ist es im Bad?",
        "welche Lampen sind an?",
        "status vom Wohnzimmer",
        "kannst du sehen ob das Licht an ist",
    ]:
        assert _is_switch_command(msg) is False, msg


def test_fu3_is_switch_command_empty_false():
    assert _is_switch_command(None) is False
    assert _is_switch_command("   ") is False


# ══════════════════════════ FU-3 — Claim-Erkennung ══════════════════════════

def test_fu3_claims_unbacked_switch_positives():
    for txt in [
        "Die Softboxen sind aus.",
        "Das Licht ist aus.",
        "Das Licht ist jetzt an.",
        "Ich hab die Lampe ausgeschaltet.",
        "Das Licht ist wieder an.",
    ]:
        assert _claims_unbacked_switch(txt) is True, txt


def test_fu3_claims_unbacked_switch_past_and_bestand_false():
    # FP-Guard (c): Bestand/Vergangenheit ist KEIN frischer Vollzug.
    for txt in [
        "Das Licht war schon aus.",
        "Die Softboxen waren bereits aus.",
        "Das Licht ist immer noch an.",
    ]:
        assert _claims_unbacked_switch(txt) is False, txt


def test_fu3_claims_unbacked_switch_future_and_negation_false():
    for txt in [
        "Ich schalte das Licht gleich aus.",
        "Ich werde die Softboxen ausmachen.",
        "Ich habe das Licht nicht ausgeschaltet.",
        "Ich konnte dazu nichts schalten.",
    ]:
        assert _claims_unbacked_switch(txt) is False, txt


def test_fu3_claims_unbacked_switch_preposition_floskeln_false():
    # FP-Guard: Präpositional-/Prädikativ-Floskeln dürfen NICHT als Zustand matchen.
    for txt in [
        "Das ist an sich eine gute Idee.",
        "Das ist aus Versehen passiert.",
        "Das ist ein echtes Problem.",
    ]:
        assert _claims_unbacked_switch(txt) is False, txt


def test_fu3_claims_unbacked_switch_legit_device_talk_false():
    # FP-Guard (d): legitime Geräte-Rede ohne Vollzugsbehauptung.
    for txt in [
        "Im Wohnzimmer hängt eine Hue-Lampe.",
        "Die Softboxen sind von Elgato.",
        "Smart-Home kann ich über Home Assistant steuern.",
    ]:
        assert _claims_unbacked_switch(txt) is False, txt


# ══════════════════════════ FU-3 — Gesamt-Korrektur ══════════════════════════

def test_fu3_positive_correction_appended():
    # Schaltbefehl + 0 erfolgreiche Aktionen + Vollzugsbehauptung → Korrektur.
    corr = _false_success_correction(
        "Die Softboxen sind aus.",
        user_switch_command=_is_switch_command("mach die Softboxen aus"),
        had_hc_success=False,
    )
    assert corr is not None
    low = corr.lower()
    assert "keinen schaltbefehl ausgeführt" in low
    # Erfindet KEINEN Gegen-Zustand:
    assert "ist noch an" not in low and "ist an" not in low


def test_fu3_fp_guard_a_status_question_no_correction():
    # (a) Status-FRAGE + Donna liest Status vor → KEINE Korrektur.
    corr = _false_success_correction(
        "Ja, das Licht ist aus.",
        user_switch_command=_is_switch_command("ist das Licht aus?"),
        had_hc_success=False,
    )
    assert corr is None


def test_fu3_fp_guard_b_successful_switch_no_correction():
    # (b) erfolgreiche Schaltung + "ist aus" → KEINE Korrektur.
    corr = _false_success_correction(
        "Das Licht ist aus.",
        user_switch_command=_is_switch_command("mach das Licht aus"),
        had_hc_success=True,
    )
    assert corr is None


def test_fu3_fp_guard_c_bestand_no_correction():
    # (c) Bestand/Vergangenheit ("war schon aus") → KEINE Korrektur.
    corr = _false_success_correction(
        "Das Licht war schon aus.",
        user_switch_command=_is_switch_command("mach das Licht aus"),
        had_hc_success=False,
    )
    assert corr is None


def test_fu3_fp_guard_d_legit_device_talk_no_correction():
    # (d) legitime Geräte-Rede ohne Vollzugsbehauptung → KEINE Korrektur.
    corr = _false_success_correction(
        "Im Streamingzimmer hast du Softboxen und ein Regallicht.",
        user_switch_command=_is_switch_command("welche Geräte hab ich im Streamingzimmer?"),
        had_hc_success=False,
    )
    assert corr is None


# ══════════ R2-BLOCKER-Fix — Nicht-Smart-Home-Aktionen (Timer/Wecker/Musik) ══════════

def _gate_for(msg, resp, *, honest_added=False, hc_had_success=False, other=False):
    """Hilfs-Wrapper: Gate-Entscheidung mit ECHT berechnetem user_wants_smarthome /
    user_switch_command aus der Nachricht (so wie das Wiring sie bildet)."""
    return _should_append_false_success(
        honest_added=honest_added,
        hc_had_success=hc_had_success,
        user_wants_smarthome=_smarthome_intent(msg, [])[0],
        user_switch_command=_is_switch_command(msg),
        other_action_dispatched=other,
        response_text=resp,
    )


@pytest.mark.parametrize("msg,resp", [
    ("Stell einen Timer auf 5 Minuten ein", "Timer läuft. Erledigt!"),
    ("Stell mir einen Wecker für 7 Uhr ein", "Erledigt, der Wecker ist gestellt."),
    ("Mach die Musik an", "Musik ist an."),
    ("Schalt meinen Wecker aus", "Wecker ist aus."),
])
def test_fu3_blocker_non_smarthome_actions_no_correction(msg, resp):
    # Pflicht-Regression: diese 4 reproduzierten FP dürfen NIE korrigiert werden.
    assert _gate_for(msg, resp) is False


def test_fu3_blocker_positive_control_still_corrects():
    # Positiv-Kontrolle: echter Smart-Home-Befehl ohne Marker → Korrektur bleibt.
    assert _gate_for("mach die Softboxen aus", "Softboxen sind aus.") is True


def test_fu3_blocker_other_action_dispatched_suppresses():
    # R2-Fix (2): selbst bei Smart-Home-Befehl → lief eine ANDERE Aktion, keine Korrektur.
    assert _should_append_false_success(
        honest_added=False, hc_had_success=False, user_wants_smarthome=True,
        user_switch_command=True, other_action_dispatched=True,
        response_text="Die Softboxen sind aus.") is False


def test_fu3_blocker_done_word_needs_device_reference():
    # R2-Fix (3): "erledigt"/"gemacht" allein ist universell → kein Claim ohne Gerätebezug.
    assert _claims_unbacked_switch("Timer läuft. Erledigt!") is False
    assert _claims_unbacked_switch("Erledigt, der Wecker ist gestellt.") is False
    assert _claims_unbacked_switch("Softboxen? Erledigt!") is True


# ══════════ Auflage 4 — Wiring-Gate als reine Funktion, Szenarien (a)–(d) ══════════

def test_fu3_gate_a_mixed_failure_defers_to_honest():
    # (a) Mischfall/Teilfehler: _honest greift (honest_added=True) → FU-3 NICHT.
    assert _should_append_false_success(
        honest_added=True, hc_had_success=False, user_wants_smarthome=True,
        user_switch_command=True, other_action_dispatched=False,
        response_text="Die Softboxen sind aus.") is False


def test_fu3_gate_b_single_decision_idempotent():
    # (b) Einmaligkeit: genau eine boolesche Entscheidung (→ Wiring yieldet 0 oder 1×).
    kw = dict(honest_added=False, hc_had_success=False, user_wants_smarthome=True,
              user_switch_command=True, other_action_dispatched=False,
              response_text="Die Softboxen sind aus.")
    r1 = _should_append_false_success(**kw)
    r2 = _should_append_false_success(**kw)
    assert r1 is True and r2 is True and isinstance(r1, bool)


def test_fu3_gate_b_wiring_has_single_emission_site():
    # (b) Einmaligkeit strukturell: im Generator genau EINE Emissions-/Log-Stelle.
    import inspect
    from app.routes import chat as chat_mod
    src = inspect.getsource(chat_mod.chat)
    assert src.count("home_control_false_success_correction") == 1
    assert src.count("_FALSE_SUCCESS_CORRECTION_TEXT") == 1


def test_fu3_gate_c_empty_response_no_correction():
    # (c) leerer full_resp → keine Vollzugsbehauptung → keine Korrektur.
    assert _should_append_false_success(
        honest_added=False, hc_had_success=False, user_wants_smarthome=True,
        user_switch_command=True, other_action_dispatched=False,
        response_text="") is False


def test_fu3_gate_d_live_filter_passthrough():
    # (d) Live-Mode: die Korrektur läuft durch _live_output_filter (kein Crash, bleibt erhalten,
    # enthält keine PII zum Maskieren).
    out = _live_output_filter(_FALSE_SUCCESS_CORRECTION_TEXT, "München")
    assert "keinen Schaltbefehl ausgeführt" in out


# ══════════════════════════ Auflage 5 — echte RED-Gegenprobe ══════════════════════════

def test_fu3_red_smarthome_gate_is_load_bearing():
    """Nicht-tautologische RED-Gegenprobe (Mutation auf Gate-Ebene).

    Der Wecker-Fall behauptet "ist aus" (claim=True) und ist ein Befehl (cmd=True) —
    NUR der user_wants_smarthome-Guard verhindert die falsche Korrektur. Mutation des
    Guards auf True reproduziert den FP (rot); der echte, aus der Nachricht berechnete
    Wert (False) liefert keine Korrektur (grün). Beweist, dass der Guard load-bearing ist.
    """
    msg, resp = "Schalt meinen Wecker aus", "Der Wecker ist aus."
    assert _is_switch_command(msg) is True              # Befehl
    assert _claims_unbacked_switch(resp) is True        # Vollzugs-Claim ("ist aus")
    assert _smarthome_intent(msg, [])[0] is False       # echter Wert: kein Smart-Home-Keyword

    mutated = _should_append_false_success(             # Guard deaktiviert → ROT (FP)
        honest_added=False, hc_had_success=False, user_wants_smarthome=True,
        user_switch_command=True, other_action_dispatched=False, response_text=resp)
    assert mutated is True

    real = _should_append_false_success(                # echtes Gate → GRÜN (keine Korrektur)
        honest_added=False, hc_had_success=False,
        user_wants_smarthome=_smarthome_intent(msg, [])[0],
        user_switch_command=_is_switch_command(msg),
        other_action_dispatched=False, response_text=resp)
    assert real is False

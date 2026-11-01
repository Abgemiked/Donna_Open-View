"""A1 (Batch A1.2/A1.3) — Leitplanken für Smart-Home-Routinen + Prompt-Logik.

Diese Tests sichern die HARTEN LEITPLANKEN ab, die bei der Freigabe von
script/scene/automation (5b, Variante a) nachweislich UNVERÄNDERT bleiben müssen:

  * script/scene/automation dürfen NIE live schaltbar sein
    (nicht in ``_HC_LIVE_ALLOWED_DOMAINS``; ``_hc_live_action_allowed`` → False).
  * lock/cover/alarm (+ siren/valve) bleiben über die Allowlist direkt gesperrt.
  * Der Tesla-Lade-Switch bleibt live gesperrt (``_HC_LIVE_DENY_ENTITIES``).

Zusätzlich:
  * Prompt-Logik (A1.2): "erst Routine prüfen → sonst Gerät raumbasiert" steht
    im Steuer-Prompt.
  * Config-Toggle kuratierte Liste (A1.3): Default = Variante a (alle Routinen
    erlaubt); ``explicit_entity_domains`` schaltet pro Domain auf "nur gelistete
    allow_entities".

Alles gemockt — KEIN echter HA-Call.
"""
from __future__ import annotations

from pathlib import Path

import pytest

from app.routes.chat import (
    _HC_LIVE_ALLOWED_DOMAINS,
    _HC_LIVE_DENY_ENTITIES,
    _hc_live_action_allowed,
    _HOME_CONTROL_INSTRUCTIONS,
)
from app.services.homeassistant_service import HAAllowlist, HANotAllowed

ALLOWLIST_PATH = str(
    Path(__file__).resolve().parents[1] / "app" / "data" / "ha_entities.yaml"
)


def _allowlist() -> HAAllowlist:
    return HAAllowlist.from_yaml(ALLOWLIST_PATH)


# ───────────────────────── LIVE-GUARD: Routinen NIE live ─────────────────────

@pytest.mark.parametrize("domain", ["script", "scene", "automation"])
def test_routine_domains_not_in_live_allowed(domain):
    # Routinen dürfen NIE in der Live-Allowlist stehen → im Stream gesperrt.
    assert domain not in _HC_LIVE_ALLOWED_DOMAINS


@pytest.mark.parametrize(
    "domain,entity_id",
    [
        ("script", "script.shishakohle"),
        ("scene", "scene.streaming_setup"),
        ("automation", "automation.guten_morgen"),
    ],
)
def test_routine_action_blocked_live(domain, entity_id):
    # Selbst der vertrauenswürdige Direkt-Pfad darf Routinen live NICHT auslösen.
    assert _hc_live_action_allowed(domain, entity_id) is False


def test_domain_mismatch_script_entity_blocked_live():
    # H3: domain=light + entity=script.x (Mismatch) → live gesperrt.
    assert _hc_live_action_allowed("light", "script.boese") is False


# ───────────────────────── LIVE-GUARD: Tesla-Lade-Switch ────────────────────

def test_tesla_charge_switch_in_live_deny_set():
    assert "switch.tesla_model_y_aufladung" in _HC_LIVE_DENY_ENTITIES


def test_tesla_charge_switch_blocked_live_despite_allowed_domain():
    # switch-Domain IST live erlaubt, der Tesla-Lade-Switch aber explizit nicht.
    assert "switch" in _HC_LIVE_ALLOWED_DOMAINS
    assert _hc_live_action_allowed("switch", "switch.tesla_model_y_aufladung") is False


# ───────────────────────── LIVE-GUARD: Positiv-Kontrolle ────────────────────

@pytest.mark.parametrize(
    "domain,entity_id",
    [
        ("light", "light.softboxen"),
        ("switch", "switch.regallicht"),
        ("climate", "climate.heizung"),
        ("media_player", "media_player.tv"),
    ],
)
def test_normal_devices_allowed_live(domain, entity_id):
    # Mikes normale Fälle müssen live weiterhin durchgehen (keine Überblockung).
    assert _hc_live_action_allowed(domain, entity_id) is True


# ───────────────────── ALLOWLIST: Direkt-Sperren unverändert ─────────────────

@pytest.mark.parametrize(
    "domain,service,entity_id",
    [
        ("lock", "unlock", "lock.tesla_model_y_schloss"),
        ("cover", "open_cover", "cover.tesla_model_y_fenster"),
        ("alarm_control_panel", "alarm_disarm", "alarm_control_panel.haus"),
        ("siren", "turn_on", "siren.hof"),
        ("valve", "open_valve", "valve.garten"),
    ],
)
def test_dangerous_domains_blocked_direct(domain, service, entity_id):
    # lock/cover/alarm/siren/valve bleiben über die Allowlist hart gesperrt.
    with pytest.raises(HANotAllowed):
        _allowlist().check(domain, service, entity_id)


def test_tesla_charge_switch_allowed_by_allowlist_offline():
    # Offline (nicht live) darf Mike den Tesla-Lade-Switch über die App schalten —
    # die Allowlist erlaubt ihn (nur der Live-Guard sperrt ihn im Stream).
    _allowlist().check("switch", "turn_on", "switch.tesla_model_y_aufladung")


# ───────────── A1.3 Config-Toggle: Default = Variante a (alle Routinen) ──────

def test_default_mode_allows_all_scripts():
    # Default (explicit_entity_domains == []) → jede script-Entity turn_on erlaubt.
    al = _allowlist()
    assert "script" not in al.explicit_entity_domains
    al.check("script", "turn_on", "script.irgendein_nicht_gelistetes_skript")


def test_default_mode_allows_scene_and_automation():
    al = _allowlist()
    al.check("scene", "turn_on", "scene.beliebige_szene")
    al.check("automation", "trigger", "automation.beliebige_automation")


# ───────────── A1.3 Config-Toggle: kuratierter Modus (explicit-only) ─────────

def _curated_allowlist() -> HAAllowlist:
    """Allowlist mit script als explicit-only-Domain (kuratierte Routinen-Liste)."""
    return HAAllowlist(
        allow_domains={"script", "light"},
        allow_services={
            "script": {"turn_on", "turn_off"},
            "light": {"turn_on", "turn_off", "toggle"},
        },
        allow_entities={"script.guten_morgen"},
        explicit_entity_domains={"script"},
    )


def test_curated_mode_allows_listed_routine():
    _curated_allowlist().check("script", "turn_on", "script.guten_morgen")


def test_curated_mode_blocks_unlisted_routine():
    with pytest.raises(HANotAllowed):
        _curated_allowlist().check("script", "turn_on", "script.shishakohle")


def _curated_mixed_allowlist() -> HAAllowlist:
    """script explicit-only; scene/automation/light in DERSELBEN Allowlist NICHT."""
    return HAAllowlist(
        allow_domains={"script", "scene", "automation", "light"},
        allow_services={
            "script": {"turn_on", "turn_off"},
            "scene": {"turn_on"},
            "automation": {"trigger"},
            "light": {"turn_on", "turn_off", "toggle"},
        },
        allow_entities={"script.guten_morgen"},
        explicit_entity_domains={"script"},
    )


def test_curated_mode_is_per_domain():
    # Der Toggle wirkt NUR auf die gelistete(n) Domain(s), nicht domainübergreifend.
    al = _curated_mixed_allowlist()
    # script ist explicit-only → nicht gelistete Entity gesperrt, gelistete erlaubt.
    with pytest.raises(HANotAllowed):
        al.check("script", "turn_on", "script.shishakohle")
    al.check("script", "turn_on", "script.guten_morgen")
    # scene/automation sind NICHT explicit-only → beliebige Entity bleibt offen
    # (echter Gegenfall: gleiche Routinen-Familie, aber andere Domain).
    al.check("scene", "turn_on", "scene.beliebige_szene")
    al.check("automation", "trigger", "automation.beliebige_automation")
    # light ebenfalls offen.
    al.check("light", "turn_on", "light.beliebig")


# ───────────── Routinen-Service-Deny (echte ha_entities.yaml) ─────────────────

@pytest.mark.parametrize(
    "domain,service,entity_id",
    [
        # automation: NUR trigger, NICHT aktivieren/deaktivieren/umschalten.
        ("automation", "turn_on", "automation.guten_morgen"),
        ("automation", "turn_off", "automation.guten_morgen"),
        ("automation", "toggle", "automation.guten_morgen"),
        # scene: NUR turn_on, kein turn_off/toggle.
        ("scene", "turn_off", "scene.streaming_setup"),
        ("scene", "toggle", "scene.streaming_setup"),
    ],
)
def test_routine_forbidden_services_blocked(domain, service, entity_id):
    with pytest.raises(HANotAllowed):
        _allowlist().check(domain, service, entity_id)


def test_routine_permitted_services_still_allowed():
    # Positiv-Kontrolle: die erlaubten Routinen-Services gehen durch.
    al = _allowlist()
    al.check("automation", "trigger", "automation.guten_morgen")
    al.check("scene", "turn_on", "scene.streaming_setup")
    al.check("script", "turn_on", "script.shishakohle")


# ───────────────────────── A1.2 Prompt-Logik: Routine first ─────────────────

def test_prompt_has_routine_section_rule():
    # Der Steuer-Prompt muss die Routinen-Regel enthalten.
    assert "ROUTINEN" in _HOME_CONTROL_INSTRUCTIONS


def test_prompt_prioritizes_routine_before_single_device():
    # A1.2: erst Routine prüfen → NUR wenn keine passt, Einzelgeräte raumbasiert.
    # Reihenfolge per Index prüfen (nicht nur Vorhandensein der Wörter).
    txt = _HOME_CONTROL_INSTRUCTIONS
    routine_rule = txt.index("ROUTINEN (PRIORITAET)")
    fallback_rule = txt.index("NUR wenn KEINE Routine passt")
    assert routine_rule < fallback_rule
    # Der Einzelgeräte-Fallback gehört zum Satz NACH der Routinen-Regel.
    assert txt.index("Einzelgeraete direkt", fallback_rule) > fallback_rule
    # Die Routine-Regel nennt die Services, die dafür genutzt werden sollen.
    rule_block = txt[routine_rule:fallback_rule]
    assert "script.turn_on" in rule_block
    assert "scene.turn_on" in rule_block
    assert "automation.trigger" in rule_block


def test_prompt_has_safety_block_against_routine_bypass():
    # Einzige Gegenmaßnahme zum akzeptierten Restrisiko (5b): Donna darf keine
    # Routine auslösen, um Schloss/Alarm/Tür/Fenster/... zu bedienen.
    txt = _HOME_CONTROL_INSTRUCTIONS
    assert "SICHERHEIT" in txt
    safety = txt[txt.index("SICHERHEIT"):]
    for word in ("NIEMALS", "Routine", "Schloss", "Alarm", "Garagentor", "Fenster", "ablehnen"):
        assert word in safety, f"{word!r} fehlt im SICHERHEIT-Block"
    # Der Block steht NACH der Routinen-Regel (Gegenmaßnahme zur Freigabe).
    assert txt.index("ROUTINEN (PRIORITAET)") < txt.index("SICHERHEIT")

"""Unit-Tests für HomeAssistantService — komplett gemockt, KEIN echter HA-Call.

Fokus: harte Allow-/Deny-Durchsetzung (fail-closed) + Status-Prefetch ohne Standort.
"""
from __future__ import annotations

from pathlib import Path

import pytest

from app.services.homeassistant_service import (
    HAAllowlist,
    HADisabled,
    HANotAllowed,
    HomeAssistantService,
)

ALLOWLIST_PATH = str(
    Path(__file__).resolve().parents[1] / "app" / "data" / "ha_entities.yaml"
)


def _allowlist() -> HAAllowlist:
    return HAAllowlist.from_yaml(ALLOWLIST_PATH)


def _service(enabled: bool = True) -> HomeAssistantService:
    return HomeAssistantService(
        url="http://ha.test:8123",
        token="dummy-token",
        allowlist_path=ALLOWLIST_PATH,
        enabled=enabled,
    )


# --- Allowlist.check: erlaubte Fälle ---

def test_allow_light_turn_on():
    _allowlist().check("light", "turn_on", "light.wohnzimmer")  # darf NICHT werfen


def test_allow_tesla_climate():
    _allowlist().check("climate", "set_temperature", "climate.tesla_model_y_klima")


def test_allow_tesla_charge_limit():
    _allowlist().check("number", "set_value", "number.tesla_model_y_ladelimit")


# --- Allowlist.check: Policy-Sperren (müssen werfen) ---

def test_deny_lock_domain():
    with pytest.raises(HANotAllowed):
        _allowlist().check("lock", "unlock", "lock.tesla_model_y_schloss")


def test_deny_cover_domain_windows():
    with pytest.raises(HANotAllowed):
        _allowlist().check("cover", "open_cover", "cover.tesla_model_y_fenster")


def test_deny_sentry_entity():
    with pytest.raises(HANotAllowed):
        _allowlist().check("switch", "turn_on", "switch.tesla_model_y_wachter_modus")


def test_deny_keyless_driving_button():
    with pytest.raises(HANotAllowed):
        _allowlist().check("button", "press", "button.tesla_model_y_schlusselloses_fahren")


def test_deny_unknown_domain_fail_closed():
    with pytest.raises(HANotAllowed):
        _allowlist().check("alarm_control_panel", "alarm_disarm", "alarm_control_panel.haus")


def test_deny_domain_mismatch():
    # Service-Domain light, Entity-Domain lock → Mismatch, Block.
    with pytest.raises(HANotAllowed):
        _allowlist().check("light", "turn_on", "lock.tesla_model_y_schloss")


def test_deny_service_not_in_allowlist():
    # light-Domain erlaubt, aber 'explode' ist kein erlaubter Service.
    with pytest.raises(HANotAllowed):
        _allowlist().check("light", "explode", "light.wohnzimmer")


def test_deny_invalid_entity_id():
    with pytest.raises(HANotAllowed):
        _allowlist().check("light", "turn_on", "kaputt")


# --- call_service: Enforcement VOR HTTP ---

async def test_call_service_blocks_before_http(monkeypatch):
    svc = _service()
    called = {"http": False}

    async def _fake_request(*a, **k):
        called["http"] = True
        return None

    monkeypatch.setattr(svc, "_request", _fake_request)
    with pytest.raises(HANotAllowed):
        await svc.call_service("lock", "unlock", "lock.tesla_model_y_schloss")
    assert called["http"] is False  # KEIN HTTP-Call bei gesperrtem Service


async def test_call_service_allows_and_calls_http(monkeypatch):
    svc = _service()
    seen = {}

    async def _fake_request(method, path, json=None):
        seen["method"] = method
        seen["path"] = path
        seen["json"] = json
        return {"ok": True}

    monkeypatch.setattr(svc, "_request", _fake_request)
    await svc.call_service("light", "turn_on", "light.wohnzimmer")
    assert seen["method"] == "POST"
    assert seen["path"] == "/api/services/light/turn_on"
    assert seen["json"]["entity_id"] == "light.wohnzimmer"


# --- disabled service ---

def test_disabled_without_token():
    svc = HomeAssistantService(
        url="http://ha.test:8123", token=None,
        allowlist_path=ALLOWLIST_PATH, enabled=True,
    )
    assert svc.enabled is False


async def test_call_service_raises_when_disabled():
    svc = _service(enabled=False)
    with pytest.raises(HADisabled):
        await svc.call_service("light", "turn_on", "light.wohnzimmer")


# --- tesla_status: Standort nur bei include_location ---

async def test_tesla_status_excludes_location(monkeypatch):
    svc = _service()

    async def _fake_states():
        return [
            {"entity_id": "sensor.tesla_model_y_batteriestand",
             "state": "72", "attributes": {"unit_of_measurement": "%", "friendly_name": "Akku"}},
            {"entity_id": "device_tracker.tesla_model_y_standort",
             "state": "home", "attributes": {"friendly_name": "Standort"}},
        ]

    monkeypatch.setattr(svc, "list_states", _fake_states)
    out = await svc.tesla_status(include_location=False)
    assert "sensor.tesla_model_y_batteriestand" in out
    assert "device_tracker.tesla_model_y_standort" not in out  # Standort NIE ohne Flag


async def test_tesla_status_includes_location_when_flagged(monkeypatch):
    svc = _service()

    async def _fake_states():
        return [
            {"entity_id": "device_tracker.tesla_model_y_standort",
             "state": "home", "attributes": {"friendly_name": "Standort"}},
        ]

    monkeypatch.setattr(svc, "list_states", _fake_states)
    out = await svc.tesla_status(include_location=True)
    assert "device_tracker.tesla_model_y_standort" in out


# --- controllable_devices: nur erlaubte Domains, ohne Deny-Entities ---

# --- BLOCKER-1: data darf die geprüfte entity_id / Ziel-Selektoren NICHT überschreiben ---

async def test_call_service_rejects_entity_id_in_data(monkeypatch):
    svc = _service()
    called = {"http": False}

    async def _fake_request(*a, **k):
        called["http"] = True
        return None

    monkeypatch.setattr(svc, "_request", _fake_request)
    with pytest.raises(HANotAllowed):
        await svc.call_service(
            "switch", "turn_on", "switch.lampe",
            data={"entity_id": "switch.tesla_model_y_wachter_modus"},
        )
    assert called["http"] is False


@pytest.mark.parametrize("key", ["device_id", "area_id", "label_id", "floor_id", "target", "entity"])
async def test_call_service_rejects_target_selectors_in_data(monkeypatch, key):
    svc = _service()
    monkeypatch.setattr(svc, "_request", lambda *a, **k: None)
    with pytest.raises(HANotAllowed):
        await svc.call_service("switch", "turn_on", "switch.lampe", data={key: "garage"})


@pytest.mark.parametrize("key", ["Entity_Id", "AREA_ID", "Device_Id"])
async def test_call_service_rejects_target_selectors_case_insensitive(monkeypatch, key):
    svc = _service()
    monkeypatch.setattr(svc, "_request", lambda *a, **k: None)
    with pytest.raises(HANotAllowed):
        await svc.call_service("switch", "turn_on", "switch.lampe", data={key: "x"})


async def test_call_service_checked_entity_wins(monkeypatch):
    svc = _service()
    seen = {}

    async def _fake_request(method, path, json=None):
        seen["json"] = json
        return None

    monkeypatch.setattr(svc, "_request", _fake_request)
    await svc.call_service("climate", "set_temperature", "climate.tesla_model_y_klima",
                           data={"temperature": 21})
    assert seen["json"]["entity_id"] == "climate.tesla_model_y_klima"
    assert seen["json"]["temperature"] == 21


# --- BLOCKER-2: entity_id strikt validiert ---

@pytest.mark.parametrize("bad", [
    "switch.lampe,switch.tesla_model_y_wachter_modus",  # Komma-Liste
    "switch.tesla_model_y_wachter_modus ",              # Trailing-Space
    "switch.lampe\n",                                   # Trailing-Newline (fullmatch/\Z)
    "Switch.Lampe",                                      # Großschreibung
    "switch.lampe.extra",                               # zu viele Teile → Mismatch/Format
    "switch",                                            # kein Punkt
])
def test_check_rejects_malformed_entity_id(bad):
    with pytest.raises(HANotAllowed):
        _allowlist().check("switch", "turn_on", bad)


def test_check_rejects_non_string_entity_id():
    with pytest.raises(HANotAllowed):
        _allowlist().check("switch", "turn_on", ["switch.lampe"])  # type: ignore[arg-type]


def test_check_rejects_bad_service_slug():
    with pytest.raises(HANotAllowed):
        _allowlist().check("light", "turn_on; rm -rf", "light.wohnzimmer")


# --- MAJOR: scene/script nur explizit, Fahrzeug-Entities nur explizit ---

def test_allow_scene_turn_on():
    # 5b: scene/script sind jetzt ALLE frei (nicht mehr explicit-only).
    _allowlist().check("scene", "turn_on", "scene.irgendwas")  # darf NICHT werfen


def test_allow_script_turn_on():
    _allowlist().check("script", "turn_on", "script.beliebig")


def test_allow_automation_trigger_but_not_turn_on():
    _allowlist().check("automation", "trigger", "automation.shisha")  # erlaubt
    with pytest.raises(HANotAllowed):
        _allowlist().check("automation", "turn_on", "automation.shisha")  # aktivieren gesperrt


def test_deny_tesla_named_routine_without_allow():
    # Fahrzeug-Regel gilt weiter: eine Routine mit "tesla" im Namen braucht explizite Freigabe.
    with pytest.raises(HANotAllowed):
        _allowlist().check("script", "turn_on", "script.tesla_entriegeln")


def test_deny_unlisted_tesla_entity():
    # tesla-Entity in erlaubter Domain, aber NICHT in allow_entities → gesperrt.
    with pytest.raises(HANotAllowed):
        _allowlist().check("switch", "turn_on", "switch.tesla_internetzugang")


def test_allow_listed_tesla_charge_switch():
    _allowlist().check("switch", "turn_on", "switch.tesla_model_y_aufladung")  # darf NICHT werfen


# --- MAJOR: Service fail-closed bei fehlendem allow_services-Eintrag ---

def test_check_fail_closed_without_service_entry():
    al = HAAllowlist(
        allow_domains={"light"},
        allow_services={},  # kein Eintrag für light
    )
    with pytest.raises(HANotAllowed):
        al.check("light", "turn_on", "light.x")


# --- Loader-Validierung (fail-closed) ---

def test_from_yaml_rejects_domain_without_services(tmp_path):
    p = tmp_path / "bad.yaml"
    p.write_text("allow_domains: [light]\nallow_services: {}\n", encoding="utf-8")
    with pytest.raises(ValueError):
        HAAllowlist.from_yaml(p)


def test_from_yaml_rejects_allow_deny_overlap(tmp_path):
    p = tmp_path / "bad.yaml"
    p.write_text(
        "allow_domains: [light]\ndeny_domains: [light]\nallow_services:\n  light: [turn_on]\n",
        encoding="utf-8",
    )
    with pytest.raises(ValueError):
        HAAllowlist.from_yaml(p)


def test_service_disabled_when_allowlist_invalid(tmp_path):
    p = tmp_path / "bad.yaml"
    p.write_text("allow_domains: []\n", encoding="utf-8")
    svc = HomeAssistantService(
        url="http://ha.test:8123", token="t", allowlist_path=str(p), enabled=True,
    )
    assert svc.enabled is False  # kaputte Allowlist → Service aus


async def test_fetch_area_map_parses_and_skips_none(monkeypatch):
    svc = _service()

    async def _fake_request(method, path, json=None, expect_text=False):
        assert path == "/api/template"
        assert expect_text is True
        return (
            "light.licht_schlafen|Schlafzimmer\n"
            "light.softboxen|Streamingzimmer\n"
            "light.erstes_licht|None\n"
            "switch.x| \n"
        )

    monkeypatch.setattr(svc, "_request", _fake_request)
    areas = await svc.fetch_area_map()
    assert areas["light.licht_schlafen"] == "Schlafzimmer"
    assert areas["light.softboxen"] == "Streamingzimmer"
    assert "light.erstes_licht" not in areas  # None weggelassen
    assert "switch.x" not in areas  # leer weggelassen


async def test_fetch_area_map_returns_empty_on_error(monkeypatch):
    svc = _service()

    async def _boom(*a, **k):
        from app.services.homeassistant_service import HAError
        raise HAError("down")

    monkeypatch.setattr(svc, "_request", _boom)
    assert await svc.fetch_area_map() == {}


async def test_controllable_devices_attaches_area(monkeypatch):
    svc = _service()

    async def _fake_states():
        return [{"entity_id": "light.licht_schlafen", "state": "on",
                 "attributes": {"friendly_name": "Licht schlafen"}}]

    monkeypatch.setattr(svc, "list_states", _fake_states)
    devices = await svc.controllable_devices(area_map={"light.licht_schlafen": "Schlafzimmer"})
    assert devices[0]["area"] == "Schlafzimmer"


async def test_controllable_devices_excludes_tesla_from_area(monkeypatch):
    # Tesla-Entity mit Area in HA darf NICHT per Raum-Sammelbefehl erfassbar sein → area=None.
    svc = _service()

    async def _fake_states():
        return [
            {"entity_id": "switch.tesla_model_y_aufladung", "state": "off",
             "attributes": {"friendly_name": "Laden"}},
            {"entity_id": "light.licht_schlafen", "state": "on",
             "attributes": {"friendly_name": "Licht schlafen"}},
        ]

    monkeypatch.setattr(svc, "list_states", _fake_states)
    devices = await svc.controllable_devices(area_map={
        "switch.tesla_model_y_aufladung": "Garage",
        "light.licht_schlafen": "Schlafzimmer",
    })
    by_id = {d["entity_id"]: d for d in devices}
    assert by_id["switch.tesla_model_y_aufladung"]["area"] is None  # Tesla raus aus Raum
    assert by_id["light.licht_schlafen"]["area"] == "Schlafzimmer"


async def test_cached_states_uses_cache(monkeypatch):
    svc = _service()
    calls = {"n": 0}

    async def _fake_states():
        calls["n"] += 1
        return [{"entity_id": "light.x", "state": "on", "attributes": {}}]

    monkeypatch.setattr(svc, "list_states", _fake_states)
    a = await svc.cached_states()
    b = await svc.cached_states()
    assert a == b
    assert calls["n"] == 1  # zweiter Aufruf aus dem Cache, kein zweiter HA-Request


async def test_call_service_invalidates_states_cache(monkeypatch):
    svc = _service()
    calls = {"n": 0}

    async def _fake_states():
        calls["n"] += 1
        return [{"entity_id": "light.x", "state": "on", "attributes": {}}]

    async def _fake_request(*a, **k):
        return None

    monkeypatch.setattr(svc, "list_states", _fake_states)
    monkeypatch.setattr(svc, "_request", _fake_request)
    await svc.cached_states()            # füllt Cache (n=1)
    await svc.call_service("light", "turn_on", "light.x")  # invalidiert Cache
    await svc.cached_states()            # muss neu laden (n=2)
    assert calls["n"] == 2


async def test_cached_states_does_not_cache_empty(monkeypatch):
    svc = _service()
    calls = {"n": 0}

    async def _empty():
        calls["n"] += 1
        return []

    monkeypatch.setattr(svc, "list_states", _empty)
    await svc.cached_states()
    await svc.cached_states()
    assert calls["n"] == 2  # leeres Ergebnis wird nicht gecacht


async def test_fetch_area_map_rejects_bad_domain(monkeypatch):
    svc = _service()
    # domains mit ungültigem Slug → leeres Template-Set → {} ohne Request.
    called = {"req": False}

    async def _req(*a, **k):
        called["req"] = True
        return ""

    monkeypatch.setattr(svc, "_request", _req)
    assert await svc.fetch_area_map(domains={"light'; evil"}) == {}
    assert called["req"] is False


async def test_controllable_devices_cap_keeps_allowed_tesla(monkeypatch):
    # 80 Switches + die freigegebenen Tesla-Steuer-Entities. Trotz limit=60 müssen
    # Klima + Ladelimit im Ergebnis bleiben (allow_entities zuerst sortiert).
    svc = _service()

    async def _fake_states():
        flood = [
            {"entity_id": f"switch.plug_{i}", "state": "off",
             "attributes": {"friendly_name": f"Plug {i}"}}
            for i in range(80)
        ]
        tesla = [
            {"entity_id": "climate.tesla_model_y_klima", "state": "off",
             "attributes": {"friendly_name": "Klima"}},
            {"entity_id": "number.tesla_model_y_ladelimit", "state": "80",
             "attributes": {"friendly_name": "Ladelimit"}},
            {"entity_id": "switch.tesla_model_y_aufladung", "state": "off",
             "attributes": {"friendly_name": "Laden"}},
        ]
        return flood + tesla  # Tesla ans Ende → nur Priorisierung rettet sie

    monkeypatch.setattr(svc, "list_states", _fake_states)
    devices = await svc.controllable_devices(limit=60)
    ids = {d["entity_id"] for d in devices}
    assert len(devices) == 60
    assert "climate.tesla_model_y_klima" in ids
    assert "number.tesla_model_y_ladelimit" in ids
    assert "switch.tesla_model_y_aufladung" in ids


async def test_controllable_devices_filters(monkeypatch):
    svc = _service()

    async def _fake_states():
        return [
            {"entity_id": "light.wohnzimmer", "state": "on",
             "attributes": {"friendly_name": "Wohnzimmer"}},
            {"entity_id": "lock.tesla_model_y_schloss", "state": "locked",
             "attributes": {"friendly_name": "Schloss"}},
            {"entity_id": "switch.tesla_model_y_wachter_modus", "state": "off",
             "attributes": {"friendly_name": "Wächter"}},
            {"entity_id": "cover.tesla_model_y_fenster", "state": "closed",
             "attributes": {"friendly_name": "Fenster"}},
        ]

    monkeypatch.setattr(svc, "list_states", _fake_states)
    devices = await svc.controllable_devices()
    ids = {d["entity_id"] for d in devices}
    assert "light.wohnzimmer" in ids
    assert "lock.tesla_model_y_schloss" not in ids        # Domain gesperrt
    assert "switch.tesla_model_y_wachter_modus" not in ids  # Entity gesperrt
    assert "cover.tesla_model_y_fenster" not in ids        # Domain gesperrt


# --- 5b: Routinen (script/scene/automation) ---

@pytest.mark.parametrize("domain,service,eid", [
    ("script", "turn_on", "script.shishakohle"),
    ("scene", "turn_on", "scene.filmabend"),
    ("automation", "trigger", "automation.guten_morgen"),
])
async def test_call_service_rejects_data_for_routines(monkeypatch, domain, service, eid):
    # Routinen dürfen NIE parametrisiert werden (Security-M1): jegliches data wird abgelehnt,
    # kein HTTP-Call. Sonst ließe sich ein Skript über variables auf ein beliebiges Ziel lenken.
    svc = _service()
    called = {"http": False}

    async def _fake_request(*a, **k):
        called["http"] = True
        return None

    monkeypatch.setattr(svc, "_request", _fake_request)
    with pytest.raises(HANotAllowed):
        await svc.call_service(domain, service, eid, data={"variables": {"x": 1}})
    assert called["http"] is False


async def test_call_service_allows_routine_without_data(monkeypatch):
    # Ohne data läuft die Routine normal durch (entity_id wird gesetzt).
    svc = _service()
    seen = {}

    async def _fake_request(method, path, json=None):
        seen["json"] = json
        return None

    monkeypatch.setattr(svc, "_request", _fake_request)
    await svc.call_service("script", "turn_on", "script.shishakohle")
    assert seen["json"] == {"entity_id": "script.shishakohle"}


async def test_controllable_devices_routine_subcap(monkeypatch):
    # 40 Skripte + 40 Switches. Routinen stehen in der Priorität vorne, dürfen aber die
    # Geräte nicht aus dem limit verdrängen: höchstens _ROUTINE_SUBCAP Routinen, der Rest Geräte.
    svc = _service()

    async def _fake_states():
        scripts = [
            {"entity_id": f"script.routine_{i:02d}", "state": "off",
             "attributes": {"friendly_name": f"Routine {i}"}}
            for i in range(40)
        ]
        switches = [
            {"entity_id": f"switch.plug_{i:02d}", "state": "off",
             "attributes": {"friendly_name": f"Plug {i}"}}
            for i in range(40)
        ]
        return scripts + switches

    monkeypatch.setattr(svc, "list_states", _fake_states)
    devices = await svc.controllable_devices(limit=60)
    routines = [d for d in devices if d["is_routine"]]
    switches = [d for d in devices if d["domain"] == "switch"]
    assert len(routines) == svc._ROUTINE_SUBCAP            # Routinen hart gedeckelt
    assert all(r["domain"] == "script" for r in routines)
    assert all(r["is_routine"] for r in routines)
    assert len(switches) >= 60 - svc._ROUTINE_SUBCAP       # Geräte bekommen die restlichen Slots
    assert len(devices) == 60

"""A1.1 (#5) — Raum-Auflösung im [Smart-Home]-Kontext.

Verifiziert, dass ``_format_smart_home_context`` Geräte generisch nach ``area``
gruppiert, den Routinen-Abschnitt getrennt führt und den Fallback-Header nutzt,
wenn keine Raum-Zuordnung vorliegt. Charakterisierungs-Tests: sie sichern das
bereits gebaute Verhalten (#5) ab, ohne Neubau.

Reiner Formatter-Test — KEIN HA-Call.
"""
from __future__ import annotations

from app.routes.chat import _format_smart_home_context, _HOME_CONTROL_INSTRUCTIONS


# Echter Abschnitts-Header der Routinen-Liste (NICHT das Wort "Routinen" allein —
# das steht auch im Steuer-Prompt: "Abschnitt 'Routinen' unten").
ROUTINES_HEADER = "Routinen (Skripte"


def _dev(entity_id, name, domain, state="on", area=None, is_routine=False):
    return {
        "entity_id": entity_id, "name": name, "domain": domain,
        "state": state, "area": area, "is_routine": is_routine,
    }


def test_empty_returns_none():
    assert _format_smart_home_context([], {}) is None


def test_groups_devices_by_distinct_area():
    devices = [
        _dev("light.schlaf", "Schlaflicht", "light", area="Schlafzimmer"),
        _dev("light.wand", "Wandlampe", "light", area="Schlafzimmer"),
        _dev("light.softbox", "Softbox", "light", area="Streamingzimmer"),
        _dev("switch.regal", "Regallicht", "switch", area="Streamingzimmer"),
    ]
    out = _format_smart_home_context(devices, {})
    assert out is not None
    # Jeder Raum als eigener Abschnitt.
    assert "Raum Schlafzimmer:" in out
    assert "Raum Streamingzimmer:" in out
    # Geräte stehen unter ihrem jeweiligen Raum.
    sz = out.split("Raum Schlafzimmer:")[1].split("Raum")[0]
    assert "light.schlaf" in sz and "light.wand" in sz
    st = out.split("Raum Streamingzimmer:")[1]
    assert "light.softbox" in st and "switch.regal" in st


def test_device_without_area_goes_to_ohne_raum():
    # "Küche" (k) UND "Wohnzimmer" (w) – "Ohne Raum" (o) läge alphabetisch MITTEN
    # dazwischen. Nur der Sort-Key (r == "Ohne Raum", …) schiebt es ans Ende.
    devices = [
        _dev("light.a", "A", "light", area="Küche"),
        _dev("switch.b", "B", "switch", area=None),
        _dev("light.w", "W", "light", area="Wohnzimmer"),
    ]
    out = _format_smart_home_context(devices, {})
    assert "Raum Küche:" in out
    assert "Raum Wohnzimmer:" in out
    assert "Raum Ohne Raum:" in out
    # Echte Räume alphabetisch, "Ohne Raum" zuletzt (auch nach "Wohnzimmer").
    assert out.index("Raum Küche:") < out.index("Raum Wohnzimmer:")
    assert out.index("Raum Wohnzimmer:") < out.index("Raum Ohne Raum:")
    # Das Gerät ohne Raum steht wirklich unter "Ohne Raum".
    assert "switch.b" in out.split("Raum Ohne Raum:")[1]


def test_grouped_header_when_rooms_present():
    devices = [_dev("light.a", "A", "light", area="Büro")]
    out = _format_smart_home_context(devices, {})
    assert "gruppiert nach Raum" in out


def test_fallback_header_when_no_rooms():
    # Keine Area → nicht fälschlich "nach Raum gruppiert" behaupten.
    devices = [_dev("light.a", "A", "light", area=None)]
    out = _format_smart_home_context(devices, {})
    assert "gruppiert nach Raum" not in out
    assert "Raumzuordnung gerade nicht verfügbar" in out


def test_routines_section_appears_and_is_separate():
    devices = [
        _dev("light.wz", "WZ", "light", area="Wohnzimmer"),
        _dev("script.shishakohle", "Shishakohle", "script", state="off", is_routine=True),
        _dev("scene.stream", "Stream-Setup", "scene", state="off", is_routine=True),
        _dev("automation.morgen", "Guten Morgen", "automation", is_routine=True),
    ]
    out = _format_smart_home_context(devices, {})
    # Gegen den ECHTEN Abschnitts-Header splitten (nicht gegen das Wort "Routinen",
    # das bereits im Steuer-Prompt steht → wäre wirkungslos).
    assert ROUTINES_HEADER in out
    before, after = out.split(ROUTINES_HEADER, 1)
    # Routinen stehen NICHT im Raum-/Geräte-Teil (also nicht unter "Raum X:") …
    for eid in ("script.shishakohle", "scene.stream", "automation.morgen"):
        assert eid not in before, f"{eid} fälschlich vor dem Routinen-Header"
        assert eid in after, f"{eid} fehlt im Routinen-Abschnitt"
    # … und das echte Gerät steht im Raum-Teil, nicht im Routinen-Abschnitt.
    assert "light.wz" in before and "light.wz" not in after
    # Im Routinen-Abschnitt gibt es keine Raum-Überschrift mehr.
    assert "Raum " not in after


def test_routines_not_counted_as_rooms():
    # Nur Routinen (mit gesetzter Area!), keine echten Geräte → Fallback-Header,
    # keine Raum-Gruppe. Mit area gesetzt würde eine fälschlich als Gerät
    # behandelte Routine "Raum Wohnzimmer:" erzeugen und "gruppiert nach Raum" triggern.
    devices = [
        _dev("script.x", "X", "script", state="off", area="Wohnzimmer", is_routine=True),
    ]
    out = _format_smart_home_context(devices, {})
    assert ROUTINES_HEADER in out
    assert "script.x" in out.split(ROUTINES_HEADER, 1)[1]
    assert "gruppiert nach Raum" not in out
    assert "Raum Wohnzimmer:" not in out
    assert "Raumzuordnung gerade nicht verfügbar" in out


def test_no_routines_no_routines_header():
    # Raumgeräte, aber KEINE Routinen → kein leerer/falscher Routinen-Abschnitt.
    devices = [
        _dev("light.a", "A", "light", area="Büro"),
        _dev("switch.b", "B", "switch", area=None),
    ]
    out = _format_smart_home_context(devices, {})
    assert ROUTINES_HEADER not in out
    # Auch im nicht-ausführbaren Modus kein Routinen-Header.
    out2 = _format_smart_home_context(devices, {}, executable=False)
    assert ROUTINES_HEADER not in out2


def test_executable_includes_control_instructions():
    devices = [_dev("light.a", "A", "light", area="Büro")]
    out = _format_smart_home_context(devices, {}, executable=True)
    assert _HOME_CONTROL_INSTRUCTIONS.split(".")[0] in out


def test_non_executable_omits_control_instructions():
    devices = [_dev("light.a", "A", "light", area="Büro")]
    out = _format_smart_home_context(devices, {}, executable=False)
    # Kein Schalt-Intro, stattdessen Nenn-/Erklär-Hinweis.
    assert "home_control" not in out
    assert "nur NENNEN/erklaeren" in out


def test_tesla_status_block_rendered():
    tesla = {"sensor.tesla_model_y_batteriestand": {"state": "72", "unit": "%", "name": "Akku"}}
    out = _format_smart_home_context([_dev("light.a", "A", "light", area="Büro")], tesla)
    assert "Tesla-Status:" in out
    assert "Akku" in out and "72" in out

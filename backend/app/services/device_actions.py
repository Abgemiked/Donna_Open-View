"""#147 Paket A — Geraete-Aktionen: reine Pruefung fuer das Werkzeug `device_action`.

Positivliste, Schema, Grenzen, Aktionsarten. Fail-closed: Unbekanntes (Aktion, Parameter, Typ, Wert) wird
abgelehnt. Die Freigabe je Aktionsart kommt vom Geraet mit der Anfrage (`device_actions`); hier steht nur die
Pruefung, nichts wird ausgefuehrt (die App fuehrt aus und prueft ein zweites Mal). Nichts in diesem Modul
beruehrt Nachrichten, Anrufe, Zahlung, Smart Home oder Apps von der Sperrliste.
"""
from __future__ import annotations

import math
import re
from typing import Any

from app.services import device_intent as _intent
from app.services.device_intent import fold as _fold

# Aktionsarten = Schalter in den Einstellungen der App (Standard je Art: aus).
KINDS: tuple[str, ...] = ("flashlight", "volume", "open_app", "open_settings", "timer_alarm",
                          "brightness", "dnd", "lock_screen", "screenshot", "open_camera")   # F (#147): System-Aktionen

# Aktion -> Aktionsart
_ACTION_KIND: dict[str, str] = {
    "flashlight": "flashlight",
    "volume": "volume",
    "open_app": "open_app",
    "open_settings": "open_settings",
    "set_timer": "timer_alarm",
    "set_alarm": "timer_alarm",
    "set_brightness": "brightness",
    "dnd": "dnd",
    "lock_screen": "lock_screen",
    "screenshot": "screenshot",
    "open_camera": "open_camera",
}
ACTIONS: frozenset[str] = frozenset(_ACTION_KIND)

# Hoechstens so viele Geraete-Aktionen je Antwort.
MAX_PER_REQUEST = 3
_MAX_KINDS_IN_REQUEST = 16

CHANNELS: tuple[str, ...] = ("media", "ring", "alarm", "call")
MODES: tuple[str, ...] = ("set", "up", "down", "mute", "unmute")
PAGES: tuple[str, ...] = (
    "wifi", "bluetooth", "mobile_data", "display", "sound", "battery", "location",
    "notifications", "app_info", "accessibility", "airplane", "internet_panel", "volume_panel",
)
CAPTURES: tuple[str, ...] = ("photo", "video")
MIN_BRIGHTNESS_PERCENT = 5   # Mike 23.11.: nie so dunkel, dass das Display im Freien unlesbar wird
MAX_BRIGHTNESS_PERCENT = 100
MIN_ALARM_VOLUME_PERCENT = 10
MAX_TIMER_MINUTES = 24 * 60
_MAX_APP_LEN = 60
_MAX_LABEL_LEN = 100
_TIME_RE = re.compile(r"^(\d{1,2}):(\d{2})$")

_PARAMS: dict[str, frozenset[str]] = {
    "flashlight": frozenset({"state"}),
    "volume": frozenset({"channel", "mode", "percent"}),
    "open_app": frozenset({"app"}),
    "open_settings": frozenset({"page"}),
    "set_timer": frozenset({"minutes", "label"}),
    "set_alarm": frozenset({"time", "label"}),
    "set_brightness": frozenset({"mode", "percent"}),
    "dnd": frozenset({"state"}),
    "lock_screen": frozenset(),
    "screenshot": frozenset(),
    "open_camera": frozenset({"capture"}),
}


def kind_of(action: Any) -> str | None:
    return _ACTION_KIND.get(action) if isinstance(action, str) else None


def parse_enabled_kinds(raw: Any) -> frozenset[str]:
    """Rohfeld der Anfrage -> bekannte Aktionsarten. Alles andere (Typ, Unbekanntes) faellt weg."""
    if not isinstance(raw, (list, tuple)):
        return frozenset()
    return frozenset(k for k in raw[:_MAX_KINDS_IN_REQUEST] if isinstance(k, str) and k in KINDS)


def dedupe_key(act: dict) -> str:
    """Dieselbe Aktion MIT denselben Parametern nicht zweimal je Antwort. Zwei verschiedene Timer, Wecker, Apps
    oder Seiten sind erlaubt; Lautstaerke je Kanal und Taschenlampe insgesamt nur einmal (widerspruechliche Werte)."""
    name = act.get("action")
    if name == "volume":
        return f"volume:{act.get('channel')}"
    if name == "open_app":
        return f"open_app:{_fold(str(act.get('app', '')))}"
    if name == "open_settings":
        return f"open_settings:{act.get('page')}"
    if name == "set_timer":
        return f"set_timer:{act.get('minutes')}:{_fold(str(act.get('label', '')))}"
    if name == "set_alarm":
        return f"set_alarm:{act.get('time')}:{_fold(str(act.get('label', '')))}"
    if name == "set_brightness":
        return f"set_brightness:{act.get('mode')}:{act.get('percent')}"   # eine Helligkeit je Antwort: im Executor
    if name == "open_camera":
        return "open_camera"   # hoechstens einmal je Antwort (Foto ODER Video)
    return str(name)


def requested_kinds(user_text: Any) -> frozenset[str]:
    """Aktionsarten, zu denen die Nutzernachricht einen BEFEHL enthaelt (Grundlage fuer das Angebot)."""
    return _intent.requested_kinds(user_text)


def user_requested(act: Any, user_text: Any) -> bool:
    """Geht genau DIESE Aktion MIT IHREN WERTEN aus der Nutzernachricht hervor? Fail-closed (False bei allem
    Unklaren). Fremdtext (Kalender, Bildschirm, Gedaechtnis) kann weder die Aktion noch ihre Werte lenken."""
    if not isinstance(act, dict) or not isinstance(user_text, str) or not user_text.strip():
        return False
    name = act.get("action")
    if name == "flashlight":
        return _intent.flashlight_ok(act.get("state"), user_text)
    if name == "volume":
        return _intent.volume_params_ok(act, user_text)
    if name == "open_app":
        return _intent.app_named(act.get("app"), user_text)
    if name == "open_settings":
        return _intent.page_named(act.get("page"), user_text)
    if name == "set_timer":
        return _intent.timer_ok(act.get("minutes"), user_text)
    if name == "set_alarm":
        return _intent.alarm_ok(act.get("time"), user_text)
    if name == "set_brightness":
        return _intent.brightness_ok(act, user_text)
    if name == "dnd":
        return _intent.dnd_ok(act.get("state"), user_text)
    if name == "lock_screen":
        return _intent.lock_ok(user_text)
    if name == "screenshot":
        return _intent.screenshot_ok(user_text)
    if name == "open_camera":
        return _intent.camera_ok(act.get("capture"), user_text)
    return False


def _int_of(v: Any) -> int | None:
    if isinstance(v, bool) or not isinstance(v, int):
        if isinstance(v, float) and not math.isnan(v) and not math.isinf(v) and v == int(v):
            return int(v)   # JSON 30.0 des Modells ist ein ganzzahliger Wert
        return None
    return v


def _label(v: Any) -> str | None:
    if not isinstance(v, str):
        return None
    cleaned = "".join(ch for ch in v if ch.isprintable() or ch == " ").strip()
    return cleaned[:_MAX_LABEL_LEN]


def validate(inp: Any, enabled: frozenset[str] | set[str]) -> tuple[dict | None, str | None]:
    """Prueft die Werkzeug-Eingabe. -> (Aktion fuer die App, None) oder (None, Fehlertext fuers Modell)."""
    if not isinstance(inp, dict):
        return None, "Die Angaben waren ungueltig."
    name = inp.get("action")
    kind = kind_of(name)
    if kind is None:
        return None, "Diese Aktion gibt es nicht."
    if not set(inp) <= _PARAMS[name] | {"action"}:
        return None, "Unbekannte Angaben — es wurde nichts ausgefuehrt."
    if kind not in enabled:
        return None, "Diese Art von Aktion ist nicht eingeschaltet — nichts ausgefuehrt."

    act: dict[str, Any] = {"type": "device_action", "action": name}

    if name == "flashlight":
        if inp.get("state") not in ("on", "off"):
            return None, "state muss on oder off sein."
        act["state"] = inp["state"]

    elif name == "volume":
        channel, mode = inp.get("channel"), inp.get("mode")
        if channel not in CHANNELS:
            return None, "Der Kanal war unklar (media, ring, alarm, call)."
        if mode not in MODES:
            return None, "Der Modus war unklar (set, up, down, mute, unmute)."
        act.update(channel=channel, mode=mode)
        if mode == "set":
            percent = _int_of(inp.get("percent")) if not isinstance(inp.get("percent"), bool) else None
            if percent is None or not 0 <= percent <= 100:
                return None, "percent muss eine ganze Zahl von 0 bis 100 sein."
            act["percent"] = percent
        elif "percent" in inp:
            return None, "percent gehoert nur zu mode=set."
        if channel == "alarm" and (mode == "mute" or (mode == "set" and act["percent"] < MIN_ALARM_VOLUME_PERCENT)):
            return None, "Den Wecker-Ton stelle ich nicht stumm."

    elif name == "open_app":
        app = inp.get("app")
        app = app.strip() if isinstance(app, str) else ""
        if not app or len(app) > _MAX_APP_LEN:
            return None, "Der App-Name fehlte — frag kurz nach."
        act["app"] = app

    elif name == "open_settings":
        if inp.get("page") not in PAGES:
            return None, "Diese Einstellungsseite gibt es nicht in der Liste."
        act["page"] = inp["page"]

    elif name == "set_timer":
        raw = inp.get("minutes")
        minutes = None if isinstance(raw, bool) else _int_of(raw)
        if minutes is None or not 1 <= minutes <= MAX_TIMER_MINUTES:
            return None, "Die Dauer war unklar (1 Minute bis 24 Stunden) — frag kurz nach."
        act["minutes"] = minutes
        if "label" in inp:
            label = _label(inp["label"])
            if label is None:
                return None, "label muss ein Text sein."
            if label:
                act["label"] = label

    elif name == "set_brightness":
        mode = inp.get("mode")
        if mode not in ("set", "up", "down"):
            return None, "Der Modus war unklar (set, up, down)."
        act["mode"] = mode
        if mode == "set":
            raw = inp.get("percent")
            percent = None if isinstance(raw, bool) else _int_of(raw)
            if percent is None or not MIN_BRIGHTNESS_PERCENT <= percent <= MAX_BRIGHTNESS_PERCENT:
                return None, "percent muss eine ganze Zahl von 5 bis 100 sein."
            act["percent"] = percent
        elif "percent" in inp:
            return None, "percent gehoert nur zu mode=set."

    elif name == "dnd":
        if inp.get("state") not in ("on", "off"):
            return None, "state muss on oder off sein."
        act["state"] = inp["state"]

    elif name in ("lock_screen", "screenshot"):
        pass   # keine Parameter

    elif name == "open_camera":
        if inp.get("capture") not in CAPTURES:
            return None, "capture muss photo oder video sein — frag kurz nach, falls unklar."
        act["capture"] = inp["capture"]

    else:  # set_alarm
        t = inp.get("time")
        m = _TIME_RE.match(t) if isinstance(t, str) else None
        if not m or int(m.group(1)) > 23 or int(m.group(2)) > 59:
            return None, "Die Uhrzeit war unklar (HH:MM) — frag kurz nach."
        act["time"] = f"{int(m.group(1)):02d}:{m.group(2)}"
        if "label" in inp:
            label = _label(inp["label"])
            if label is None:
                return None, "label muss ein Text sein."
            if label:
                act["label"] = label

    return act, None

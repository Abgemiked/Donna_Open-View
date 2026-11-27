"""#187-Folgepaket G2 (Refs #147) — Routinen: Donnas eigene, feste Abfolge geprueften Schritte ("Streammodus").

Grundsaetze (Plan "Bixby-Restpakete", Paket G2):
  * Eine Routine ist eine gespeicherte, FESTE Liste von hoechstens 8 Schritten (Aktionsart + feste Werte) mit
    einem Namen; hoechstens 20 Routinen. Speicher: eigene SQLite-Datei (ueberlebt Neustarts).
  * Anlegen, Aendern, Loeschen nur auf Mikes eigene Worte UND mit Bestaetigung durch seinen Tipp (Stufe 2):
    der Server haelt nur einen AUSSTEHENDEN Vorschlag (Speicher mit Ablauf, einmalig); gespeichert wird erst,
    wenn die App nach dem Tipp bestaetigt. Das Modell kann nichts still anlegen oder aendern.
  * Ausloesen OHNE Modell: der Server erkennt den Namen in Mikes Worten (ganzer Satz = Befehlsform + Name) und
    fuehrt die gespeicherten Schritte deterministisch aus. Fremder Text kann weder Schritte noch Werte lenken.
  * Jeder Schritt wird zur Laufzeit NEU geprueft (Schalter der Aktionsart, Sperrlisten, Live-Sperre) — hier nur
    die reine Vorbereitung; die Ausfuehrung und alle Sperren liegen im Chat-Pfad.
  * Nur Positivliste: Geraete-Aktionen (device_actions.validate) und Smart Home (feste entity_id). Keine
    Nachrichten, Anrufe, Termine: sie bleiben Einzelbefehle mit Karte (nie unbeaufsichtigt in einer Routine).

Nichts hier ruft ein Modell, das Netz oder das Handy auf. Keine Nutzertexte im Log.
"""
from __future__ import annotations

import json
import re
import secrets
import sqlite3
import threading
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

from app.core.logger import get_logger
from app.services import device_actions as _da
from app.services import device_intent as _i
from app.services import multi_command as _multi
from app.services.homeassistant_service import _FORBIDDEN_DATA_KEYS

log = get_logger("service.routines")

MAX_ROUTINES = 20
MAX_STEPS = 8
MAX_NAME_LEN = 40
MAX_NAME_WORDS = 3
MIN_NAME_CHARS = 4
MIN_NAME_SYLLABLES = 2
PENDING_TTL = 10 * 60
MAX_PENDING = 3
LOG_KEEP = 100
LOG_SHOW = 10
MAX_INPUT = 1500

PENDING_ID_RE = re.compile(r"^rp_[0-9a-f]{16}$")

_SLUG = re.compile(r"^[a-z_]{1,32}$")
_ENTITY = re.compile(r"^[a-z_]+\.[a-z0-9_]{1,64}$")
_DATA_KEY = re.compile(r"^[a-z_]{1,32}$")
# Routine-Domains (script/scene/automation): in Schritten nur ausloesbar, nie parametrisiert
_ROUTINE_DOMAINS = frozenset({"script", "scene", "automation"})
_HOME_SERVICE_LABEL = {"turn_on": "an", "turn_off": "aus", "toggle": "umschalten"}
_HOME_SERVICES = frozenset(_HOME_SERVICE_LABEL)
_ON_WORDS = frozenset({"an", "ein", "einschalten", "anschalten", "anmachen", "aktivieren", "aktiviere", "starten",
                       "einmachen"})
_OFF_WORDS = frozenset({"aus", "ausschalten", "ausmachen", "abschalten", "deaktivieren", "deaktiviere", "ab",
                        "abstellen"})
_TOGGLE_WORDS = frozenset({"um", "umschalten", "wechseln"})

_UTC = timezone.utc


class RoutineError(ValueError):
    """Ungueltige Eingabe. ``message`` ist als ehrliche Rueckmeldung (Modell/App) gedacht, nie mit Nutzertext."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


# ---------------------------------------------------------------------------------------------------------------
# Namen
# ---------------------------------------------------------------------------------------------------------------

# Woerter, die kein Routinename sein duerfen: Alltags-/Aktions-/Verbinderwoerter und alles, was in Befehlssaetzen
# vorkommt. Ein Name soll im normalen Gespraech nie als ganzer Satz fallen und nie mit einer Aktion kollidieren.
_GENERIC = frozenset({
    "routine", "routinen", "modus", "modi", "programm", "szene", "szenen", "aktion", "aktionen", "befehl", "befehle",
    "an", "aus", "ein", "auf", "zu", "ab", "ja", "nein", "nee", "ok", "okay", "gut", "los", "stopp", "stop", "weiter",
    "start", "starte", "starten", "ende", "fertig", "alles", "nichts", "das", "die", "der", "den", "dem", "mal",
    "bitte", "donna", "hey", "hallo", "danke", "jetzt", "gleich", "heute", "morgen", "gestern", "abend", "nacht",
    "tag", "licht", "lampe", "musik", "ton", "lauter", "leiser", "stumm", "timer", "wecker", "handy", "telefon",
    "internet", "wlan", "bluetooth", "helligkeit", "ruhe", "pause", "hilfe", "test", "neu", "neue", "alle", "fuer",
    "mit", "und", "oder", "dann", "danach", "aber", "nicht", "kein", "keine", "doch", "noch", "auch", "nur",
})
_FORBIDDEN_NAME_WORDS = (_GENERIC | _i._ALL_IMP | _multi._HEAD_VERBS | _multi._ACTION_NOUNS | _multi._FILLERS
                         | _multi._VALUEISH | _multi._STOP)
_VOWEL_GROUP = re.compile(r"[aeiouy]+")
_NAME_LEAD_BAD = frozenset({"bis", "gute", "guten", "gut", "ich", "du", "wir", "mach", "bitte", "danke", "kannst", "kann"})
_NAME_CHARS = re.compile(r"^[A-Za-zÄÖÜäöüß0-9][A-Za-zÄÖÜäöüß0-9 \-]*$")


def display_name(raw: Any) -> str:
    """Anzeigename: getrimmt, Leerraum zusammengezogen. Ungueltige Zeichen werden NICHT repariert (-> Fehler)."""
    if not isinstance(raw, str):
        return ""
    return " ".join(raw.split())


def name_key(raw: Any) -> str:
    """Vergleichsschluessel: gefaltet (Umlaute, Gross/Klein), ohne Leer- und Bindezeichen: 'Stream-Modus' == 'streammodus'."""
    return "".join(_i.fold(raw if isinstance(raw, str) else "").split())


def syllables(key: str) -> int:
    return len(_VOWEL_GROUP.findall(key))


def validate_name(raw: Any) -> tuple[str, str]:
    """-> (Anzeigename, Schluessel) oder RoutineError. Regeln: 4 bis 40 Zeichen, hoechstens 3 Woerter, mindestens zwei
    Silben, nur Buchstaben/Ziffern/Leerzeichen/Bindestrich, kein Alltags-/Aktions-/Verbinderwort, keine reine Zahl."""
    name = display_name(raw)
    if not name or len(name) > MAX_NAME_LEN or not _NAME_CHARS.match(name):
        raise RoutineError("name_invalid", "Der Name ist ungueltig (Buchstaben und Zahlen, hoechstens 40 Zeichen).")
    toks = _i.fold(name).split()
    if not toks or len(toks) > MAX_NAME_WORDS:
        raise RoutineError("name_invalid", "Der Name darf hoechstens drei Woerter haben.")
    key = "".join(toks)
    if len(key) < MIN_NAME_CHARS or syllables(key) < MIN_NAME_SYLLABLES:
        raise RoutineError("name_short", "Der Name ist zu kurz — nimm ein eigenes Wort mit mindestens zwei Silben.")
    if toks[0] in _i._W_WORDS or toks[0] in _i._STATE_VERBS or toks[0] in _NAME_LEAD_BAD:
        raise RoutineError("name_generic", "Der Name klingt wie eine Frage oder Redewendung — nimm einen eigenen Namen.")
    for t in toks:
        if t in _FORBIDDEN_NAME_WORDS or t.isdigit() or _i.to_int(t) is not None or _multi._valueish([t]):
            raise RoutineError("name_generic", "Der Name ist ein Alltags- oder Befehlswort — nimm einen eigenen Namen.")
    return name, key


# ---------------------------------------------------------------------------------------------------------------
# Schritte
# ---------------------------------------------------------------------------------------------------------------


def _clean_data(domain: str, data: Any) -> dict | None:
    """Smart-Home-Schritte tragen in Routinen KEINE Parameter (data): die Karte zeigt nur Geraet und an/aus, also darf
    nichts Verstecktes mitlaufen (Helligkeit u. ae. bleiben Einzelbefehle)."""
    if data in (None, {}):
        return None
    raise RoutineError("step_invalid", "Parameter wie Helligkeit gibt es in Routinen nicht — nur an oder aus.")


def validate_step(raw: Any, home_ok: Callable[[str, str, str], bool] | None = None) -> dict:
    """Ein Schritt aus Werkzeug-Eingabe -> kanonisch gespeicherte Form; sonst RoutineError (fail-closed).

    ``{"kind": "device", "action": ..., <Werte>}`` ueber ``device_actions.validate`` (alle Aktionsarten gelten zum
    Anlegen als moeglich; zur LAUFZEIT zaehlt der Schalter der Art) oder
    ``{"kind": "home", "domain", "service", "entity_id", "data"?}`` (``home_ok`` = Sperrliste des Smart-Home-Dienstes)."""
    if not isinstance(raw, dict):
        raise RoutineError("step_invalid", "Ein Schritt war ungueltig.")
    kind = raw.get("kind")
    body = {k: v for k, v in raw.items() if k != "kind"}
    if kind == "device":
        act, err = _da.validate(body, frozenset(_da.KINDS))
        if act is None:
            raise RoutineError("step_invalid", err or "Ein Schritt war ungueltig.")
        act.pop("type", None)
        return {"kind": "device", **act}
    if kind == "home":
        if not set(body) <= {"domain", "service", "entity_id", "data"}:
            raise RoutineError("step_invalid", "Unbekannte Angaben im Schritt.")
        d, s, e = body.get("domain"), body.get("service"), body.get("entity_id")
        if s not in _HOME_SERVICES:
            raise RoutineError("step_invalid", "In Routinen gibt es beim Schalten nur an, aus oder umschalten.")
        if not (isinstance(d, str) and isinstance(s, str) and isinstance(e, str)
                and _SLUG.match(d) and _SLUG.match(s) and _ENTITY.match(e) and e.split(".", 1)[0] == d):
            raise RoutineError("step_invalid", "Die Smart-Home-Angaben waren ungueltig.")
        if home_ok is None or not home_ok(d, s, e):
            raise RoutineError("step_blocked", "Dieses Geraet oder diese Aktion ist fuer Donna gesperrt.")
        step: dict[str, Any] = {"kind": "home", "domain": d, "service": s, "entity_id": e}
        data = _clean_data(d, body.get("data"))
        if data:
            step["data"] = data
        return step
    raise RoutineError("step_invalid", "Diese Schrittart gibt es nicht (nur Geraete-Aktionen und Smart Home).")


def validate_steps(raw: Any, home_ok: Callable[[str, str, str], bool] | None = None) -> list[dict]:
    if not isinstance(raw, list) or not raw:
        raise RoutineError("steps_missing", "Es fehlten die Schritte.")
    if len(raw) > MAX_STEPS:
        raise RoutineError("steps_many", f"Hoechstens {MAX_STEPS} Schritte je Routine.")
    steps = [validate_step(s, home_ok) for s in raw]
    for i, st in enumerate(steps):
        if st.get("kind") != "device":
            continue
        if st.get("action") == "screenshot":
            raise RoutineError("step_invalid", "Einen Screenshot gibt es in Routinen nicht — der bleibt ein Einzelbefehl.")
        if st.get("action") == "lock_screen" and i != len(steps) - 1:
            raise RoutineError("step_invalid", "Sperren geht nur als letzter Schritt.")
    seen: set[str] = set()
    for s in steps:
        key = _step_key(s)
        if key in seen:
            raise RoutineError("step_duplicate", "Derselbe Schritt steht doppelt in der Routine.")
        seen.add(key)
    return steps


def _step_key(step: dict) -> str:
    if step["kind"] == "home":
        return f"home:{step['entity_id']}:{step['service']}:{json.dumps(step.get('data'), sort_keys=True)}"
    return "device:" + _da.dedupe_key(step)


def step_label(step: dict) -> str:
    """Kurze deutsche Beschreibung eines Schritts fuer Karte, Einstellungen und Antworttext (nie Rohwerte der Modelle
    ausser den validierten Feldern)."""
    if step.get("kind") == "home":
        name = str(step.get("entity_id", "")).split(".", 1)[-1].replace("_", " ")
        svc = _HOME_SERVICE_LABEL.get(str(step.get("service")), str(step.get("service", "")).replace("_", " "))
        return f"Schalten: {name} {svc}"
    a = step.get("action")
    if a == "flashlight":
        return "Taschenlampe an" if step.get("state") == "on" else "Taschenlampe aus"
    if a == "volume":
        ch = {"media": "Medien", "ring": "Klingelton", "alarm": "Wecker", "call": "Anruf"}.get(step.get("channel"), "Lautstärke")
        mode = step.get("mode")
        if mode == "up":
            return f"{ch} lauter"
        if mode == "down":
            return f"{ch} leiser"
        if mode == "mute":
            return f"{ch} stumm"
        if mode == "unmute":
            return f"{ch} wieder an"
        return f"{ch} auf {step.get('percent')} Prozent"
    if a == "open_app":
        return f"App öffnen: {step.get('app')}"
    if a == "open_settings":
        return f"Einstellungen öffnen: {step.get('page')}"
    if a == "set_timer":
        return f"Timer {step.get('minutes')} Minuten"
    if a == "set_alarm":
        return f"Wecker {step.get('time')} Uhr"
    if a == "set_brightness":
        mode = step.get("mode")
        if mode == "up":
            return "Helligkeit heller"
        if mode == "down":
            return "Helligkeit dunkler"
        return f"Helligkeit auf {step.get('percent')} Prozent"
    if a == "dnd":
        return "Nicht stören an" if step.get("state") == "on" else "Nicht stören aus"
    if a == "lock_screen":
        return "Bildschirm sperren"
    if a == "screenshot":
        return "Screenshot"
    if a == "open_camera":
        return "Kamera öffnen (Video)" if step.get("capture") == "video" else "Kamera öffnen (Foto)"
    return "Aktion"


# ---------------------------------------------------------------------------------------------------------------
# Absichten und Ausloeser (rein, ohne Modell)
# ---------------------------------------------------------------------------------------------------------------

_CREATE_VERBS = frozenset({"leg", "lege", "erstell", "erstelle", "richte", "speicher", "speichere", "erzeuge", "erzeug",
                           "mach", "mache", "baue", "bau", "definiere", "definier", "stell", "stelle", "fueg", "fuege"})
_UPDATE_VERBS = frozenset({"aender", "aendere", "bearbeite", "bearbeit", "erweitere", "erweiter", "ergaenze",
                           "ergaenz", "ersetze", "ersetz", "passe", "pass", "update", "korrigiere", "korrigier"})
_DELETE_VERBS = frozenset({"loesch", "loesche", "entferne", "entfern", "verwirf", "verwerfe", "streich", "streiche",
                           "vergiss"})
_FILL = frozenset({"bitte", "mal", "doch", "jetzt", "donna", "hey", "ok", "okay", "also", "und", "dann", "kannst",
                   "du", "koenntest", "wuerdest", "ich", "moechte", "will", "brauche", "haette", "gern", "gerne"})
_TRIGGER_VERBS = frozenset({"starte", "start", "aktiviere", "aktivier", "fuehre", "fuehr", "schalte", "schalt",
                            "mach", "mache", "lass", "lasse", "los"})
_DETERMINERS = frozenset({"die", "den", "das", "meine", "meinen", "mein", "der", "eine", "einen"})
_TRIG_TRAIL = frozenset({"starten", "an", "ein", "los", "laufen", "bitte"})   # NIE "aus": "Streammodus aus" = beenden


_FORBIDDEN_NAME_WORDS = _FORBIDDEN_NAME_WORDS | _FILL | _TRIGGER_VERBS | _DETERMINERS | _TRIG_TRAIL | _CREATE_VERBS     | _UPDATE_VERBS | _DELETE_VERBS


def intent_of(raw: Any) -> str | None:
    """Verwaltungsabsicht aus Mikes Worten: 'create' | 'update' | 'delete' | None. Verlangt das Wort "Routine", ein
    passendes Befehlsverb als ERSTES Wort (nach Fuellwoertern) und keine Frage. Reine Funktion auf der Nachricht."""
    if not isinstance(raw, str) or "?" in raw[:MAX_INPUT]:
        return None
    toks = _i.fold(raw[:MAX_INPUT]).split()
    if "routine" not in toks or set(toks) & (_i._NEG | _i._ABORT):
        return None            # Verneinung ("loesch nicht ...") oder Abbruch: nie eine Verwaltungsabsicht
    core = list(toks)
    while core and core[0] in _FILL:
        core.pop(0)
    if not core:
        return None
    head = core[0]
    if head in _DELETE_VERBS:
        return "delete"
    if head in _UPDATE_VERBS:
        return "update"
    if head in _CREATE_VERBS:
        return "create"
    return None


def match_trigger(raw: Any, names: dict[str, str]) -> str | None:
    """Ruft Mikes GANZER Satz genau eine Routine auf? ``names``: Schluessel -> Anzeigename. -> Anzeigename oder None.

    Erlaubt: "<Name>", "starte/aktiviere/fuehre ... <Name> (aus/an/ein/starten)", mit "die/meine/Routine/Modus"
    dazwischen und Fuellwoertern (bitte, mal, Donna ...). Alles andere (Fragen, Verneinung, weitere Woerter,
    Mehrfachsatz, Verwaltungsabsicht) -> None: im Zweifel der normale Weg, nie ein stiller Start."""
    if not isinstance(raw, str) or not names or "?" in raw[:MAX_INPUT]:
        return None
    if intent_of(raw) is not None:
        return None
    toks = _i.fold(raw[:MAX_INPUT]).split()
    lead = (_FILL - {"und", "dann"}) | _TRIGGER_VERBS | _DETERMINERS | {"routine", "modus"}
    run_aus = False
    while toks and toks[0] in lead:
        if toks[0] in ("fuehre", "fuehr"):
            run_aus = True               # "fuehre X aus" = starten; "schalte/mach X aus" = beenden -> kein Treffer
        toks.pop(0)
    if toks and toks[-1] == "aus" and run_aus:
        toks.pop()
    while toks and toks[-1] in _TRIG_TRAIL:
        toks.pop()
    if not toks or len(toks) > MAX_NAME_WORDS + 1:
        return None
    keys = {"".join(toks)}
    if toks[-1] in ("routine", "modus"):      # "Stream Modus" kann der Name "Streammodus" sein (Spracherkennung trennt)
        keys.add("".join(toks[:-1]))
    hits = {n for k, n in names.items() if k in keys}
    return next(iter(hits)) if len(hits) == 1 else None


def name_in_text(name: str, raw: Any) -> re.Match | None:
    """Steht der Name (Anzeige) in Mikes Nachricht? Gefaltet, Leer-/Bindezeichen zwischen den Zeichen toleriert
    ('Stream Modus' = 'Streammodus'). -> Treffer in ``lower_keep(raw)`` oder None."""
    key = name_key(name)
    if not key or not isinstance(raw, str):
        return None
    pat = r"(?<![a-z0-9])" + r"[\s\-]*".join(re.escape(c) for c in key) + r"(?![a-z0-9])"
    return re.search(pat, _i.lower_keep(raw[:MAX_INPUT]))


_REST_LEAD = frozenset({"an", "ein", "neu", "mit", "so", "folgenden", "folgende", "schritten", "schritt", "dass",
                        "soll", "sie", "es", "enthaelt", "naemlich", "also", "um", "bestehend", "und"})


def steps_text_after_name(name: str, raw: Any) -> str | None:
    """Der Teil von Mikes Nachricht NACH dem Namen = die gesprochenen Schritte (Satzzeichen bleiben fuer die Zerlegung)."""
    m = name_in_text(name, raw)
    if m is None:
        return None
    rest = _i.lower_keep(raw[:MAX_INPUT])[m.end():]
    rest = re.sub(r"^[\s:,;.\-]+", "", rest)
    while True:
        mm = re.match(r"^([a-z]+)\b[\s:,;.\-]*", rest)
        if mm and mm.group(1) in _REST_LEAD and not (mm.group(1) == "und"):
            rest = rest[mm.end():]
            continue
        break
    return rest.strip()


def home_spoken(step: dict, clause_text: str) -> bool:
    """Passt ein Smart-Home-Schritt zu GENAU diesem gesprochenen Teilsatz? Richtung (an/aus/umschalten) UND Geraet
    (Name der Entity oder Geraeteart-Wort) muessen im Teilsatz stehen. Fail-closed."""
    toks = _i.fold(clause_text).split()
    if not toks:
        return False
    tokset = set(toks)
    svc = step.get("service")
    on, off, tog = bool(tokset & _ON_WORDS), bool(tokset & _OFF_WORDS), bool(tokset & _TOGGLE_WORDS)
    if svc == "turn_on" and not (on and not off):
        return False
    if svc == "turn_off" and not (off and not on):
        return False
    if svc == "toggle" and not tog:
        return False
    obj = [w for w in str(step.get("entity_id", "")).split(".", 1)[-1].split("_") if len(w) >= 3]
    for t in toks:
        if len(t) < 3:
            continue
        if any(t == w or (min(len(t), len(w)) >= 4 and (t.startswith(w) or w.startswith(t))) for w in obj):
            return True
    return False


def bind_spoken_steps(name: str, raw_message: str, steps: list[dict]) -> list[int]:
    """Ordnet JEDEN vorgeschlagenen Schritt genau einem eigenen Teilsatz der Nachricht NACH dem Namen zu (dieselbe
    Pruefung wie G1: Befehlsform + Werte je Teilsatz) und verlangt, dass kein gesprochener Teilsatz ohne Schritt
    bleibt. -> Teilsatz-Indizes in Schrittreihenfolge. Sonst RoutineError (fail-closed, nichts wird gemerkt)."""
    rest = steps_text_after_name(name, raw_message)
    if not rest:
        raise RoutineError("steps_unspoken", "Die Schritte stehen nicht in deiner Nachricht — nenne sie mit.")
    plan = _multi.analyze(rest)
    if plan.withdrawn:
        raise RoutineError("withdrawn", "Du hast den Satz am Ende zurueckgenommen — nichts gemerkt.")
    if any(c.cls == "stage2" and not c.struck for c in plan.clauses):
        raise RoutineError("stage2_step", "Nachrichten, Anrufe und Termine gibt es in Routinen nicht — die bleiben "
                           "Einzelbefehle mit Bestaetigung.")
    if len(plan.steps) != len(steps):
        raise RoutineError("steps_mismatch", f"Ich habe {len(plan.steps)} gesprochene Schritte erkannt, aber "
                           f"{len(steps)} vorgeschlagen — nichts gemerkt. Sag die Schritte bitte noch einmal klar.")
    binder = _multi.Binder(plan)
    idxs: list[int] = []
    for st in steps:
        if st["kind"] == "home":
            idx = None
            for c in plan.clauses:
                if c.cls == "home" and not c.struck and c.idx not in binder.used and home_spoken(st, c.text):
                    binder.used[c.idx] = "home"
                    idx = c.idx
                    break
        else:
            act = {k: v for k, v in st.items() if k != "kind"}
            idx = binder.bind_device(act)
            if idx is None and binder.reject_reason in _multi.REJECT_MESSAGES:
                raise RoutineError("step_invalid", _multi.REJECT_MESSAGES[binder.reject_reason] + " — nichts gemerkt.")
        if idx is None:
            raise RoutineError("step_unbound", "Ein Schritt laesst sich keinem eigenen Teilsatz von dir zuordnen — "
                               "nichts gemerkt.")
        idxs.append(idx)
    if len(set(idxs)) != len(idxs):
        raise RoutineError("step_unbound", "Zwei Schritte gehoeren zum selben Teilsatz — nichts gemerkt.")
    return idxs


# ---------------------------------------------------------------------------------------------------------------
# Speicher
# ---------------------------------------------------------------------------------------------------------------


def _now() -> str:
    return datetime.now(_UTC).replace(microsecond=0).strftime("%Y-%m-%dT%H:%M:%SZ")


@dataclass(frozen=True)
class Pending:
    pending_id: str
    mode: str            # create | update | delete
    name: str
    key: str
    steps: tuple          # neue Schritte (leer bei delete)
    old_steps: tuple      # bisherige Schritte (bei update/delete)
    created: float


class RoutineStore:
    """Routinen (SQLite) + ausstehende Bestaetigungen (Speicher, mit Ablauf) + Aenderungsprotokoll."""

    def __init__(self, db_path: str, clock: Callable[[], float] = time.time) -> None:
        self._path = db_path
        self._clock = clock
        self._lock = threading.RLock()
        self._pending: dict[str, Pending] = {}
        if db_path != ":memory:":
            Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(db_path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        with self._lock:
            self._conn.executescript(
                "CREATE TABLE IF NOT EXISTS routines ("
                " id INTEGER PRIMARY KEY AUTOINCREMENT, key TEXT NOT NULL UNIQUE, name TEXT NOT NULL,"
                " steps TEXT NOT NULL, created TEXT NOT NULL, updated TEXT NOT NULL);"
                "CREATE TABLE IF NOT EXISTS routine_log ("
                " id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT NOT NULL, op TEXT NOT NULL,"
                " name TEXT NOT NULL, steps INTEGER NOT NULL);")
            self._conn.commit()

    # -- Lesen ---------------------------------------------------------------------
    @staticmethod
    def _row(r: sqlite3.Row) -> dict:
        try:
            steps = json.loads(r["steps"])
        except (TypeError, ValueError):
            steps = []
        return {"id": r["id"], "name": r["name"], "key": r["key"], "steps": steps if isinstance(steps, list) else [],
                "updated": r["updated"]}

    def list(self) -> list[dict]:
        with self._lock:
            rows = self._conn.execute("SELECT * FROM routines ORDER BY name COLLATE NOCASE").fetchall()
        return [self._row(r) for r in rows]

    def get(self, routine_id: int) -> dict | None:
        with self._lock:
            r = self._conn.execute("SELECT * FROM routines WHERE id=?", (routine_id,)).fetchone()
        return self._row(r) if r else None

    def by_key(self, key: str) -> dict | None:
        with self._lock:
            r = self._conn.execute("SELECT * FROM routines WHERE key=?", (key,)).fetchone()
        return self._row(r) if r else None

    def names(self) -> dict[str, str]:
        """Schluessel -> Anzeigename (fuer die Ausloeser-Erkennung)."""
        return {r["key"]: r["name"] for r in self.list()}

    def count(self) -> int:
        with self._lock:
            return int(self._conn.execute("SELECT COUNT(*) FROM routines").fetchone()[0])

    def changelog(self, limit: int = LOG_SHOW) -> list[dict]:
        with self._lock:
            rows = self._conn.execute("SELECT ts, op, name, steps FROM routine_log ORDER BY id DESC LIMIT ?",
                                      (max(1, min(limit, LOG_KEEP)),)).fetchall()
        return [{"ts": r["ts"], "op": r["op"], "name": r["name"], "steps": r["steps"]} for r in rows]

    # -- Ausstehende Vorschlaege ---------------------------------------------------
    def _prune(self) -> None:
        now = self._clock()
        for pid in [p for p, v in self._pending.items() if now - v.created > PENDING_TTL]:
            del self._pending[pid]

    def propose(self, mode: str, name: str, steps: list[dict]) -> Pending:
        """Haelt einen Vorschlag zur Bestaetigung. Prueft alles NOCHMAL (Grenzen, Namen, Kollisionen). Nichts wird
        gespeichert. -> Pending oder RoutineError."""
        if mode not in ("create", "update", "delete"):
            raise RoutineError("mode", "Unbekannte Aktion.")
        disp, key = validate_name(name) if mode == "create" else (display_name(name), name_key(name))
        with self._lock:
            self._prune()
            cur = self.by_key(key)
            if mode == "create":
                if cur is not None:
                    raise RoutineError("exists", "Eine Routine mit diesem Namen gibt es schon — zum Aendern sag "
                                       "'aendere die Routine ...'.")
                if self.count() >= MAX_ROUTINES:
                    raise RoutineError("full", f"Es gibt schon {MAX_ROUTINES} Routinen — loesch zuerst eine.")
            else:
                if cur is None:
                    raise RoutineError("unknown", "Diese Routine gibt es nicht.")
                disp = cur["name"]
            if mode != "delete" and (not steps or len(steps) > MAX_STEPS):
                raise RoutineError("steps_many", f"Eine Routine hat 1 bis {MAX_STEPS} Schritte.")
            for p in [p for p in self._pending.values() if p.key == key]:
                del self._pending[p.pending_id]            # je Name hoechstens ein offener Vorschlag
            if len(self._pending) >= MAX_PENDING:
                oldest = min(self._pending.values(), key=lambda p: p.created)
                del self._pending[oldest.pending_id]
            pend = Pending("rp_" + secrets.token_hex(8), mode, disp, key, tuple(steps),
                           tuple(cur["steps"]) if cur else (), self._clock())
            self._pending[pend.pending_id] = pend
        log.info("routine_proposed", mode=mode, steps=len(steps))
        return pend

    @staticmethod
    def _valid_pid(pending_id: Any) -> bool:
        return isinstance(pending_id, str) and bool(PENDING_ID_RE.fullmatch(pending_id))

    def get_pending(self, pending_id: str) -> Pending | None:
        with self._lock:
            self._prune()
            return self._pending.get(pending_id) if self._valid_pid(pending_id) else None

    def reject(self, pending_id: str) -> bool:
        with self._lock:
            return self._pending.pop(pending_id, None) is not None if self._valid_pid(pending_id) else False

    def confirm(self, pending_id: str, home_ok: Callable[[str, str, str], bool] | None = None) -> dict:
        """Mikes Tipp: Vorschlag EINMALIG anwenden. Schritte werden erneut geprueft (Sperrlisten koennen sich
        geaendert haben). -> {"mode", "name", "steps"}; sonst RoutineError (Vorschlag bleibt dann verworfen)."""
        with self._lock:
            self._prune()
            pend = self._pending.pop(pending_id, None) if self._valid_pid(pending_id) else None
            if pend is None:
                raise RoutineError("pending_unknown", "Der Vorschlag ist abgelaufen oder schon erledigt.")
            now = _now()
            if pend.mode == "delete":
                cur = self.by_key(pend.key)
                if cur is None:
                    raise RoutineError("unknown", "Diese Routine gibt es nicht mehr.")
                self._conn.execute("DELETE FROM routines WHERE key=?", (pend.key,))
                self._log("delete", cur["name"], len(cur["steps"]))
                return {"mode": "delete", "name": cur["name"], "steps": 0}
            steps = validate_steps([dict(s) for s in pend.steps], home_ok)
            try:
                if pend.mode == "create":
                    if self.by_key(pend.key) is not None:
                        raise RoutineError("exists", "Eine Routine mit diesem Namen gibt es schon.")
                    if self.count() >= MAX_ROUTINES:
                        raise RoutineError("full", f"Es gibt schon {MAX_ROUTINES} Routinen.")
                    self._conn.execute("INSERT INTO routines (key, name, steps, created, updated) VALUES (?,?,?,?,?)",
                                       (pend.key, pend.name, json.dumps(steps), now, now))
                else:
                    if self.by_key(pend.key) is None:
                        raise RoutineError("unknown", "Diese Routine gibt es nicht mehr.")
                    self._conn.execute("UPDATE routines SET steps=?, updated=? WHERE key=?",
                                       (json.dumps(steps), now, pend.key))
                self._log(pend.mode, pend.name, len(steps))
            except sqlite3.Error as exc:
                self._conn.rollback()
                log.error("routine_db_error", error_type=type(exc).__name__)
                raise RoutineError("db", "Das Speichern hat nicht geklappt — schau in den Einstellungen nach.") from exc
            return {"mode": pend.mode, "name": pend.name, "steps": len(steps)}

    def delete_by_id(self, routine_id: int) -> bool:
        """Loeschen aus den Einstellungen (Mikes eigener Tipp samt Rueckfrage in der App; nicht ueber das Modell)."""
        with self._lock:
            cur = self.get(routine_id)
            if cur is None:
                return False
            self._conn.execute("DELETE FROM routines WHERE id=?", (routine_id,))
            self._log("delete", cur["name"], len(cur["steps"]))
            return True

    def _log(self, op: str, name: str, n: int) -> None:
        self._conn.execute("INSERT INTO routine_log (ts, op, name, steps) VALUES (?,?,?,?)", (_now(), op, name, n))
        self._conn.execute("DELETE FROM routine_log WHERE id NOT IN (SELECT id FROM routine_log ORDER BY id DESC LIMIT ?)",
                           (LOG_KEEP,))
        self._conn.commit()


# ---------------------------------------------------------------------------------------------------------------
# Lauf (Vorbereitung, rein)
# ---------------------------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class RunStep:
    step: dict
    label: str
    act: dict | None = None          # fuer die App validierte Geraete-Aktion (type=device_action)
    skip: str | None = None          # Grund (feste Formulierung), wenn der Schritt nicht laeuft


def prepare_run(steps: list[dict], enabled_kinds: frozenset[str] | set[str]) -> list[RunStep]:
    """Bereitet einen Lauf vor, rein deterministisch: Geraete-Schritte werden mit dem aktuellen Schalter je
    Aktionsart NEU validiert (Schalter aus -> ausgelassen, nie still ausgefuehrt). Smart-Home-Schritte bleiben
    unveraendert und werden im Chat-Pfad ueber die bestehende Pruefung (Sperrliste, Live-Sperre) ausgefuehrt."""
    out: list[RunStep] = []
    window = steps[:MAX_STEPS]
    for i, raw in enumerate(window):
        label = step_label(raw)
        if raw.get("kind") == "home":
            out.append(RunStep(raw, label))
            continue
        if raw.get("action") == "screenshot" or (raw.get("action") == "lock_screen" and i != len(window) - 1):
            out.append(RunStep(raw, label, None, "ungueltig"))   # nie aus einer (manipulierten) Datei ausfuehren
            continue
        body = {k: v for k, v in raw.items() if k != "kind"}
        act, err = _da.validate(body, frozenset(enabled_kinds))
        if act is None:
            skip = "Schalter aus" if "nicht eingeschaltet" in (err or "") else "ungueltig"
            out.append(RunStep(raw, label, None, skip))
        else:
            out.append(RunStep(raw, label, act))
    return out


def offline_snapshot(items: list[dict]) -> list[dict]:
    """#147 Paket D: Stand fuer das Handy ohne Server (``GET /routines/offline``). Geraete-Schritte tragen ihre
    gespeicherten Werte (das Handy prueft sie beim Lauf erneut und braucht den Schalter je Aktionsart); Smart-Home-Schritte
    nur die Beschriftung (offline nie ausfuehrbar, keine Geraetekennungen auf dem Handy)."""
    out: list[dict] = []
    for r in items[:MAX_ROUTINES]:
        steps: list[dict] = []
        for st in list(r["steps"])[:MAX_STEPS]:
            label = step_label(st)
            if st.get("kind") == "device":
                steps.append({**st, "label": label})
            else:
                steps.append({"kind": "home", "label": label})
        out.append({"name": r["name"], "steps": steps})
    return out

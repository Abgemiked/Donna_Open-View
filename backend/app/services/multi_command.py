"""#186 G1 (Refs #147) — mehrere Befehle in einem Satz: deterministische Zerlegung und Bindung.

Alles hier ist reine Funktion auf dem Text, den Mike in DIESER Anfrage geschickt hat (nie Kalender, Bildschirm,
Gedaechtnis, Verlauf oder Tool-Ergebnisse). Das Modell schlaegt Aktionen vor; der Server laesst nur zu, was sich
GENAU EINEM Teilsatz zuordnen laesst, der fuer sich Befehlsform und Werte traegt (dieselbe Pruefung wie Paket A,
je Teilsatz: ``device_actions.user_requested(act, teilsatz)``). Nichts wird ausgefuehrt, nichts vorgezogen.

Grundsatz wie in Paket A: lieber einmal zu wenig ausloesen (Rueckfrage / Karte mit Tipp) als einmal zu viel.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any, Callable

from app.services import device_actions as _da
from app.services import device_intent as _i
from app.services import self_correction as _sc

MAX_STEPS = 5
LOCK_NOT_LAST_MESSAGE = "Sperren geht nur als letzter Schritt"   # feste Meldung, nie mit Nutzertext
SCREENSHOT_NOT_LAST_MESSAGE = "Ein Screenshot geht nur als letzter Schritt"
LAST_ONLY_STAGE2_MESSAGE = "Sperren und Screenshot gehen nicht zusammen mit Nachrichten, Anrufen oder Terminen"
# Aktionen, die nur als LETZTER Schritt eines Mehrfachsatzes laufen (sie unterbrechen alles danach)
LAST_ONLY_ACTIONS = frozenset({"lock_screen", "screenshot"})
REJECT_MESSAGES = {
    "lock_not_last": LOCK_NOT_LAST_MESSAGE,
    "screenshot_not_last": SCREENSHOT_NOT_LAST_MESSAGE,
    "last_only_stage2": LAST_ONLY_STAGE2_MESSAGE,
}
# Karten mit externer App: ihre Intents ueberlagern sich -> hoechstens EINE je Sammelkarte
EXTERNAL_CARD_TYPES = frozenset({"call", "sms", "whatsapp", "create_event"})
STAGE2_CARD_TYPES = EXTERNAL_CARD_TYPES

# ---------------------------------------------------------------------------------------------------------------
# Zerlegung
# ---------------------------------------------------------------------------------------------------------------

_CONNECTOR_RE = re.compile(
    r"\b(und dann|und danach|und anschliessend|und ausserdem|und|dann|danach|ausserdem|anschliessend|sowie|"
    r"aber(?= nicht\b)|ausser)\b")
_VALUEISH = frozenset({
    "und", "oder", "uhr", "prozent", "halb", "viertel", "dreiviertel", "nach", "vor", "um", "ein", "eine", "einen",
    "einem", "einer", "minute", "minuten", "min", "stunde", "stunden", "std", "sekunde", "sekunden", "sek", "halbe",
    "halben", "morgens", "abends", "nachts", "frueh", "mittags", "nachmittags", "vormittags", "bitte", "noch",
    "anderthalb", "eineinhalb", "halbstunde", "viertelstunde", "dreiviertelstunde", "h", "s",
})
_HEAD_VERBS = _sc.HEAD_VERBS   # #221: Definition liegt im Selbstkorrektur-Detektor (gleiche Liste)
_ACTION_NOUNS = frozenset({
    "taschenlampe", "handylicht", "handylampe", "taschenleuchte", "timer", "wecker", "stoppuhr", "eieruhr",
    "countdown", "lautstaerke", "licht", "lampe", "lampen", "leuchte", "steckdose", "heizung", "ventilator",
    "fernseher", "tv", "klima", "klimaanlage", "tesla", "rollo", "rolladen", "rollladen", "stumm", "lautlos",
    "lauter", "leiser", "nachricht", "sms", "whatsapp", "termin",
    "helligkeit", "screenshot", "bildschirmfoto", "kamera", "fotokamera", "videokamera", "dnd", "sperren",
})   # Paket F: "und" trennt auch vor "Helligkeit auf 30", "Screenshot", "Kamera", "nicht stoeren", "Handy sperren"
_FILLERS = frozenset({"bitte", "mal", "doch", "jetzt", "noch", "auch", "dann", "danach", "und", "ausserdem",
                      "anschliessend", "sowie", "donna", "hey", "ok", "okay", "also", "aber"})
# Satzende-Formeln, die als Nachrichtentext praktisch nie vorkommen (sonst nur, wenn der LETZTE Teilsatz reine
# Ruecknahme ist: "doch nicht", "lass es" in "Schreib Anna, ich komme doch nicht" bleibt Nachrichtentext)
_WITHDRAW = (
    "ach nee", "ach nein", "vergiss es", "vergiss das", "vergiss alles", "lass gut sein", "nee lass", "nein lass",
    "ach lass", "ach vergiss es", "vergiss es wieder", "nee lieber nicht", "nein lieber nicht",
)
_ABORT_WORDS = frozenset({"ach", "nee", "ne", "nein", "doch", "nicht", "lass", "lasse", "es", "das", "gut", "sein",
                          "vergiss", "vergessen", "stopp", "stop", "lieber", "egal", "alles", "wieder", "bitte",
                          "mal", "jetzt"})
_ABORT_KEY = frozenset({"nee", "ne", "nein", "nicht", "vergiss", "stopp", "stop", "lass", "lasse"})
_CORRECTION_LEADS = frozenset({"nee", "ne", "nein"})
_SUBORDINATE = frozenset({"dass", "weil", "damit", "ob"})
_EXCLUSION_LEADS = frozenset({"nicht", "ausser", "ohne", "aber"})
_STOP = frozenset({"aber", "nicht", "nur", "das", "die", "der", "den", "dem", "des", "ausser", "bitte", "mal", "ohne",
                   "ein", "eine", "einen", "mir", "mich", "und", "dann", "auch", "noch", "an", "aus", "ein"})

_STAGE2_RE = re.compile(
    r"\b(?:schreib\w*|schick\w*|send\w*|nachricht\w*|sms|whatsapp\w*|anruf\w*|telefonier\w*|termin\w*|"
    r"loesch\w*|kalender\w*|bescheid|melde)\b|\bruf\w*\b(?:\s+\w+){0,3}\s+an\b(?!\s+\d)|"
    r"\bsag\w*\b(?=.*\b(?:dass|das)\b)|\btrag\w*\b(?=.*\bein\b)")
_HOME_NOUN = frozenset({"licht", "lampe", "lampen", "leuchte", "steckdose", "heizung", "ventilator", "fernseher",
                        "tv", "klima", "klimaanlage", "tesla", "rollo", "rolladen", "rollladen", "licht"})


def _toks(text: str) -> list[str]:
    return _i.fold(text).split()


def _core(tokens: list[str]) -> list[str]:
    out = list(tokens)
    while out and out[0] in _FILLERS:
        out.pop(0)
    return out


def _valueish(tokens: list[str]) -> bool:
    return all(t in _VALUEISH or t.isdigit() or _i.to_int(t) is not None or bool(_i._ATTACHED.match(t))
               for t in tokens)


def _command_head(right: str) -> bool:
    """Beginnt der rechte Teil EINEN neuen Befehl? Nur dann trennt ein Verbinder (nie in Werten/Aufzaehlungen)."""
    core = _core(_toks(right))
    if not core or _valueish(core):
        return False
    return core[0] in _HEAD_VERBS or any(t in _ACTION_NOUNS or _i.fuzzy_ok(t, "taschenlampe") for t in core)


def flatten(actions: list[dict]) -> list[dict]:
    """Aktionen inkl. der Schritte einer Sammelkarte (fuer Normalisierung und Kontaktlernen)."""
    out: list[dict] = []
    for a in actions:
        if isinstance(a, dict) and a.get("type") == "step_group":
            out.extend(s for s in a.get("steps", []) if isinstance(s, dict))
        else:
            out.append(a)
    return out


RESTART_LEAD = "~restart"   # #221: Teilsatz entstand durch einen Neustart-Schnitt (Verb-Wiederholung/abgebrochener Befehl)
_LEAD_CONN_RE = re.compile(r"^\s*(?:(?:und|dann|danach|ausserdem|anschliessend|sowie)\b[\s,]*)+")
MAX_INPUT = 1500   # laengere Eingaben sind keine Sprachbefehle; Rest wird nicht gebunden (fail-closed)


def _split_restarts(parts: list[tuple[str, str]]) -> list[tuple[str, str]]:
    """#221: "stelle einen Timer auf mach das mach den Bildschirm hell" faellt NICHT in einen Teilsatz, sondern an den
    Neustart-Stellen auseinander (unvollstaendiger Befehl bzw. Stottern + neues Befehlsverb). Nur Schnitt, kein
    Verwerfen: der Text bleibt vollstaendig erhalten; die Teilsaetze ab dem Neustart gelten als unsicher (``Clause.suspect``)."""
    out: list[tuple[str, str]] = []
    for text, lead in parts:
        cuts = _sc.restart_points(text)
        if not cuts:
            out.append((text, lead))
            continue
        bounds = [0, *cuts, len(text)]
        for k in range(len(bounds) - 1):
            out.append((text[bounds[k]:bounds[k + 1]], lead if k == 0 else RESTART_LEAD))
    return out


def split_clauses(raw: Any) -> list[tuple[str, str]]:
    """Mikes Satz -> [(Teilsatz-Text, fuehrender Verbinder)] in gesprochener Reihenfolge. Trennung an Satzzeichen
    (Paket A) und an Verbindern ('und', 'dann', 'danach', 'ausserdem' ...) — Letztere NUR vor einem neuen Befehl."""
    out: list[tuple[str, str]] = []
    for s in _i.sentences(raw[:MAX_INPUT] if isinstance(raw, str) else raw):
        low = s.low
        start, lead, parts = 0, "", []
        for m in _CONNECTOR_RE.finditer(low):
            left, right = low[start:m.start()], low[m.end():]
            # nur bis zum NAECHSTEN Verbinder pruefen: "5 Minuten und 30 Sekunden und mach ..." bleibt zusammen
            nxt = next((n for n in _CONNECTOR_RE.finditer(right) if n.start() > 0), None)
            if nxt is not None:
                right = right[:nxt.start()]
            if not _toks(left) or not _command_head(right):
                continue
            parts.append((low[start:m.start()], lead))
            start, lead = m.end(), m.group(1).split()[-1] if m.group(1).startswith("und ") else m.group(1)
        parts.append((low[start:], lead))
        parts = _split_restarts(parts)
        out.extend((_LEAD_CONN_RE.sub("", t).strip(), ld) for t, ld in parts if _toks(_LEAD_CONN_RE.sub("", t)))
    # Nebensatz mit "dass/weil/damit/ob" gehoert zum vorigen Teilsatz (Nachrichtentext), ist kein eigener Befehl
    merged: list[tuple[str, str]] = []
    for text, ld in out:
        core = _core(_toks(text))
        if merged and core and core[0] in _SUBORDINATE:
            merged[-1] = (merged[-1][0] + ", " + text, merged[-1][1])
        else:
            merged.append((text, ld))
    return merged


# ---------------------------------------------------------------------------------------------------------------
# Plan
# ---------------------------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class Clause:
    idx: int
    text: str
    cls: str                       # stage2 | device | home | other
    kinds: frozenset = frozenset()
    struck: bool = False           # durch Ausnahme-Teilsatz gestrichen ("..., aber nicht das Licht")
    neg: bool = False
    exclusion: bool = False
    cmd: bool = False              # befehlsartig (auch ein verneinter/gestrichener Befehl: er zaehlt als Teilsatz)
    suspect: bool = False          # #221: ab einem Neustart/Satzabbruch -> nie binden/ausfuehren, nachfragen


@dataclass(frozen=True)
class Plan:
    clauses: tuple[Clause, ...] = ()
    withdrawn: bool = False
    over_limit: bool = False
    marker_suspect: bool = False   # #221: Korrekturmarker im Satz ("nee", "ich meine" ...) -> ganzer Satz unsicher
    suggestion: str = ""          # #221: Mikes eigene Worte nach der Korrektur (nur fuer die Rueckfrage)

    @property
    def steps(self) -> tuple[Clause, ...]:
        return tuple(c for c in self.clauses if c.cls != "other" and not c.struck)

    @property
    def multi(self) -> bool:
        """Mehrteiliger Satz (mindestens zwei Teilsaetze, davon einer ein Befehl): Bindung und Verneinung gelten je
        Teilsatz. Ein einzelner Teilsatz bleibt exakt wie in Paket A."""
        return sum(1 for c in self.clauses if c.cmd) >= 2 or any(c.suspect for c in self.clauses)

    @property
    def suspect(self) -> bool:
        """#221: Selbstkorrektur erkannt UND kein sauberer Befehl uebrig -> NICHTS ausfuehren, kurz nachfragen. Bleibt
        vor einem Neustart ein sauberer Teilsatz ("Mach Softboxen an und stelle einen Timer auf mach ..."), gilt nur
        der Teil ab dem Neustart als unsicher (Bindung ueberspringt ihn, siehe ``Binder``)."""
        if self.marker_suspect:
            return True
        cmds = [c for c in self.clauses if c.cmd or c.suspect]
        return bool(cmds) and all(c.suspect for c in cmds) and any(c.suspect for c in cmds)

    @property
    def has_command(self) -> bool:
        return self.marker_suspect or any(c.cmd for c in self.clauses)

    def is_last_step(self, idx: int) -> bool:
        """Folgt nach diesem Teilsatz noch ein (nicht gestrichener) Schritt (Geraet, Smart Home, Stufe 2)?"""
        return not any(c.idx > idx and c.cls != "other" and not c.struck for c in self.clauses)

    @property
    def first_stage2(self) -> int | None:
        for c in self.clauses:
            if c.cls == "stage2" and not c.struck:
                return c.idx
        return None

    def holds(self, idx: int | None) -> bool:
        """Gehoert ein Schritt in die Sammelkarte? Ab dem ersten Stufe-2-Teilsatz (gesprochene Reihenfolge)."""
        f = self.first_stage2
        return f is not None and (idx is None or idx >= f)

    def blocked_reason(self) -> str | None:
        if self.suspect and not self.withdrawn:
            return _sc.question_hint(self.suggestion)
        if self.withdrawn:
            return "Mike hat den Satz am Ende zurueckgenommen — nichts ausgefuehrt."
        if self.over_limit:
            return (f"Mehr als {MAX_STEPS} Befehle in einem Satz — nichts ausgefuehrt. Frag Mike kurz, was er "
                    "davon zuerst moechte.")
        return None


def suspect_message(plan: Plan) -> str:
    """#221: feste Rueckfrage-Anweisung (plus Mikes eigene Worte als Vorschlag) fuer einen unsicheren Teilsatz."""
    sug = plan.suggestion
    if not sug:
        last = next((c for c in reversed(plan.clauses) if c.suspect), None)
        sug = _sc.quote(_toks(last.text)) if last else ""
    return _sc.question_hint(sug)


def _is_withdrawn(raw: Any, parts: list[tuple[str, str]] | None = None) -> bool:
    if not isinstance(raw, str):
        return False
    if parts:
        last = _toks(parts[-1][0])
        if last and set(last) <= _ABORT_WORDS and set(last) & _ABORT_KEY:
            return True                                    # letzter Teilsatz = reine Ruecknahme
        # "..., nee auf 10 Minuten": Korrektur nach einem Befehl -> unklar, nichts ausfuehren, nachfragen
        if any((_core(_toks(t)) or [""])[0] in _CORRECTION_LEADS for t, _ in parts[1:]):
            return True
    toks = _toks(raw[:MAX_INPUT])
    while toks and toks[-1] in ("bitte", "mal", "doch", "wieder"):
        toks.pop()
    text = " ".join(toks)
    return any(text == w or text.endswith(" " + w) for w in _WITHDRAW)


def _classify(text: str) -> tuple[str, frozenset, bool]:
    sents = _i.sentences(text)
    neg = any(s.neg for s in sents)
    if _STAGE2_RE.search(_i.lower_keep(text)) and not any(s.question for s in sents):
        return "stage2", frozenset(), neg
    kinds = _da.requested_kinds(text)
    if kinds:
        return "device", kinds, neg
    for s in sents:
        if s.question or s.neg:
            continue
        has_noun = bool(set(s.tokens) & _HOME_NOUN)
        # Smart Home hatte in Paket A kein Textgate: jeder befehlsfoermige Teilsatz oder ein Geraetewort genuegt
        if (s.imp in _i._ALL_IMP) or s.polite or has_noun:   # Paket F: sperr/nimm sind kein Smart-Home-Befehl
            return "home", frozenset(), neg
    return "other", frozenset(), neg


def _shared(a: list[str], b: list[str]) -> bool:
    a = [t for t in a if t not in _VALUEISH and not t.isdigit()]
    b = [t for t in b if t not in _VALUEISH and not t.isdigit()]
    for x in a:
        for y in b:
            if min(len(x), len(y)) >= 4 and (x == y or x.startswith(y) or y.startswith(x)):
                return True
    return False


def analyze(raw: Any) -> Plan:
    """Zerlegt Mikes Satz und klassifiziert die Teilsaetze. Rein, ohne Modell."""
    if not isinstance(raw, str) or not raw.strip():
        return Plan()
    parts = split_clauses(raw)
    withdrawn = _is_withdrawn(raw, parts)
    det = _sc.inspect(raw)
    # #221: erster Neustart-Schnitt -> der unvollstaendige Teilsatz davor und alles danach ist unsicher
    first_restart = next((i for i, (_t, ld) in enumerate(parts) if ld == RESTART_LEAD), None)
    suspect_from = first_restart - 1 if first_restart is not None else None
    clauses: list[Clause] = []
    for idx, (text, lead) in enumerate(parts):
        core = _core(_toks(text))
        exclusion = bool(core) and (core[0] in _EXCLUSION_LEADS or lead in ("aber", "ausser")) \
            and (core[0] in ("nicht", "ausser", "ohne") or lead in ("aber", "ausser"))
        cls, kinds, neg = _classify(text)
        if exclusion and "dnd" in kinds and core[:2] == ["nicht", "stoeren"]:
            exclusion = False          # Paket F: "Nicht stoeren an" ist ein Befehl, kein Ausnahme-Teilsatz
        if exclusion:
            cls = "other"
        looks_cmd = any((s.imp in _i._ALL_IMP or s.polite) and not s.question for s in _i.sentences(text))
        clauses.append(Clause(idx, text, cls, kinds, False, neg, exclusion,
                              cmd=cls != "other" or (neg and looks_cmd and not exclusion),
                              suspect=suspect_from is not None and idx >= suspect_from))
    # Ausnahme-Teilsaetze streichen fruehere Teilsaetze mit demselben Inhaltswort
    struck = {c.idx: False for c in clauses}
    for c in clauses:
        if not c.exclusion:
            continue
        words = [t for t in _toks(c.text) if t not in _STOP and len(t) >= 4]
        for earlier in clauses[:c.idx]:
            if _shared(words, [t for t in _toks(earlier.text) if t not in _STOP]):
                struck[earlier.idx] = True
    clauses = [Clause(c.idx, c.text, c.cls, c.kinds, struck[c.idx], c.neg, c.exclusion, c.cmd, c.suspect)
               for c in clauses]
    n = sum(1 for c in clauses if c.cls != "other" and not c.struck)
    return Plan(tuple(clauses), withdrawn, n > MAX_STEPS, det.suspect and det.kind == "marker", det.suggestion)


# ---------------------------------------------------------------------------------------------------------------
# Bindung
# ---------------------------------------------------------------------------------------------------------------


@dataclass
class Binder:
    """Ordnet Aktionen genau einem Teilsatz zu; ein Teilsatz traegt hoechstens EINE Aktion (Smart Home: mehrere
    Geraete desselben Teilsatzes, zaehlt als ein Schritt)."""
    plan: Plan
    used: dict[int, str] = field(default_factory=dict)
    discarded: list[str] = field(default_factory=list)
    reject_reason: str | None = None   # Paket F: "lock_not_last" (feste Meldung im Chat-Pfad, nie Nutzertext)

    def bind_device(self, act: dict, requested: Callable[[dict, str], bool] | None = None) -> int | None:
        check = requested or _da.user_requested
        self.reject_reason = None
        for c in self.plan.clauses:
            if c.cls != "device" or c.struck or c.idx in self.used:
                continue
            if c.suspect:
                # #221: unsicherer Teilsatz (Neustart/Satzabbruch) bindet NIE; passt die Aktion dorthin -> Rueckfrage
                if check(act, c.text):
                    self.reject_reason = "suspect"
                continue
            if check(act, c.text):
                if act.get("action") in LAST_ONLY_ACTIONS:
                    # Sperren/Screenshot unterbrechen alles danach (und Stufe-2-Karten): nur als LETZTER Schritt,
                    # nie in einem Satz mit Nachricht/Anruf/Termin
                    if any(o.cls == "stage2" and not o.struck for o in self.plan.clauses):
                        self.reject_reason = "last_only_stage2"
                        break
                    if not self.plan.is_last_step(c.idx):
                        self.reject_reason = "lock_not_last" if act.get("action") == "lock_screen"                             else "screenshot_not_last"
                        break
                self.used[c.idx] = f"device:{act.get('action')}"
                return c.idx
        self.discarded.append(str(act.get("action", "")))   # nur der Aktionsname, nie Werte/Text
        return None

    def bind_home(self) -> int | None:
        for c in self.plan.clauses:
            if c.cls == "home" and not c.struck and not c.suspect and self.used.get(c.idx, "home") == "home":
                self.used[c.idx] = "home"
                return c.idx
        return None

    def bind_stage2(self, entity: str = "") -> int | None:
        """Teilsatz fuer eine Stufe-2-Karte (Nachricht/Anruf/Termin) oder None: dann bleibt die Karte ungebunden
        (Paket-A-Verhalten, der Tipp ist die Bestaetigung) und kommt in der Sammelkarte ans Ende."""
        free = [c for c in self.plan.clauses
                if c.cls == "stage2" and not c.struck and not c.suspect and c.idx not in self.used]
        if not free:
            return None
        words = [t for t in _toks(entity) if len(t) >= 3]
        pick = next((c for c in free if words and all(w in _toks(c.text) for w in words)), free[0])
        self.used[pick.idx] = "stage2"
        return pick.idx

    def steps_used(self) -> int:
        return len(self.used)


# ---------------------------------------------------------------------------------------------------------------
# Sammelkarte
# ---------------------------------------------------------------------------------------------------------------


class GroupAssembler:
    """Haelt die Schritte EINER Sammelkarte in gesprochener Reihenfolge. ``action`` ist das (live nachgefuehrte)
    Ereignis fuer die App: ``{"type": "step_group", "group_id", "steps": [...]}``."""

    def __init__(self, group_id: str) -> None:
        self._items: list[tuple[int, int, dict]] = []
        self.action: dict = {"type": "step_group", "group_id": group_id, "steps": []}

    def add(self, clause_idx: int | None, act: dict) -> None:
        order = clause_idx if isinstance(clause_idx, int) else 10_000
        self._items.append((order, len(self._items), act))
        self._items.sort(key=lambda t: (t[0], t[1]))
        self.action["steps"][:] = [a for _o, _n, a in self._items]

    def __len__(self) -> int:
        return len(self._items)

    def external_cards(self) -> int:
        return sum(1 for _o, _n, a in self._items if a.get("type") in EXTERNAL_CARD_TYPES)

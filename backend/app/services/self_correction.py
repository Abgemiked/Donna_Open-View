"""#221 Selbstkorrektur in der Spracheingabe — Stufe 1, Option B1 (konservativ, deterministisch, ohne Modell).

Mike spricht Saetze oft abgebrochen oder korrigiert ("stelle einen Timer auf mach das mach den Bildschirm hell",
"Licht an aeh nein Fernseher an"). Der Spracherkenner liefert dazu Rohtext ohne jede Korrektur-Logik.

Grundsaetze (nicht verhandelbar):
  * Es wird NICHTS geglaettet und nie Text erzeugt, der Mike nicht gesagt hat. Der Server bindet Aktionen weiter nur
    am ORIGINALTEXT (``device_actions.user_requested`` je Teilsatz).
  * Der Detektor kann nur in Richtung "weniger tun" wirken: ``suspect`` => nichts ausfuehren, kurz nachfragen.
    Er fuehrt nie etwas aus und verwirft nie Text, den er dann als Befehl bindet.
  * Das ``suggestion``-Feld ist ein Zitat aus Mikes eigenen Worten (nur fuer die Rueckfrage), nie eine Bindungsgrundlage.

``detect(text)`` -> ``"clean"`` | ``"suspect"``. ``inspect(text)`` liefert zusaetzlich die Art (Marker / Neustart)
und den Vorschlag. ``restart_points(text)`` liefert die Schnittstellen fuer ``multi_command.split_clauses``.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

from app.services import device_intent as _i

CLEAN = "clean"
SUSPECT = "suspect"
MAX_INPUT = 1500

# Befehlsverben am Satzanfang (frueher in multi_command; hier, damit multi_command den Detektor nutzen kann)
HEAD_VERBS = _i._ALL_IMP | frozenset({
    "schreib", "schreibe", "schick", "schicke", "sende", "ruf", "rufe", "spiel", "spiele", "such", "suche",
    "navigiere", "fahr", "fahre", "loesche", "loesch", "trag", "trage", "leg", "lege", "oeffne", "schalte",
    "erinnere", "erinner", "verschiebe", "verschieb", "stoppe", "heiz", "heize", "mach", "zeig", "zeige",
    "sperr", "sperre", "nimm", "nehme", "nehm",   # Paket F: Bildschirm sperren, Screenshot/Video aufnehmen
})

# Nachrichtentext-Verben: was danach kommt, ist INHALT ("Schreib Anna nein danke"), kein Korrekturmarker
_MESSAGE_VERBS = frozenset({"schreib", "schreibe", "schick", "schicke", "sende", "sag", "sage", "antworte",
                            "antwort", "diktiere", "notiere", "notier", "merk", "merke"})
_FILLERS = frozenset({"aeh", "aehm", "oeh", "oehm", "ehm", "hm", "hmm", "mhm"})
_MARKER_TOKENS = frozenset({"nee", "nein", "sondern", "warte", "noe", "quatsch", "ups", "stopp", "falsch"})
_STATE = frozenset({"an", "aus", "ein", "auf", "zu", "lauter", "leiser", "hell", "dunkel"})
_MARKER_PHRASES = (("ich", "meine"), ("ich", "meinte"), ("oder", "besser"), ("ach", "ne"), ("ne", "ne"))
_CONNECTORS = frozenset({"und", "dann", "danach", "ausserdem", "anschliessend", "sowie", "oder"})
_DANGLING = frozenset({"auf", "von", "um", "fuer", "in", "mit", "zu", "bis", "nach", "bei", "ueber", "unter",
                       "den", "die", "das", "dem", "der", "einen", "eine", "einem", "einer", "ein", "mein", "meine",
                       "meinen", "meinem"})
# Nur eindeutige Befehlsverben gelten nach einem abgebrochenen Befehl als Neustart; Verb-Nomen-Doppelgaenger
# ("das Spiel", "der Start", "die Suche") bleiben Teil des Satzes ("Stell einen Timer fuer das Spiel")
_RESTART_VERBS = frozenset({"mach", "mache", "stell", "stelle", "schalt", "schalte", "ruf", "rufe", "schreib",
                            "schreibe", "schick", "schicke", "zeig", "zeige", "setz", "setze", "dreh", "drehe",
                            "oeffne", "nimm", "nehme", "sperr", "sperre", "erinnere", "weck", "wecke"})
_ARTICLES = frozenset({"ein", "eine", "einen", "einem", "einer", "eines"})
_WORD_RE = re.compile(r"[a-z0-9äöüß]+")
_ALSO_MAX_TAIL = 4


@dataclass(frozen=True)
class Detection:
    verdict: str = CLEAN
    kind: str = ""            # "marker" (ganzer Satz unsicher) | "restart" (ab dem Neustart unsicher)
    suggestion: str = ""      # Mikes eigene Worte NACH der Korrektur (nur fuer die Rueckfrage)

    @property
    def suspect(self) -> bool:
        return self.verdict == SUSPECT


def _words(text: str) -> list[tuple[str, int]]:
    """(gefaltetes Wort, Startposition im ``lower_keep``-Text)."""
    low = _i.lower_keep(text)
    return [(_i.fold(m.group()), m.start()) for m in _WORD_RE.finditer(low)]


def _numeric(tok: str) -> bool:
    return tok not in _ARTICLES and (tok.isdigit() or _i.to_int(tok) is not None or bool(_i._ATTACHED.match(tok)))


def _is_message_text(toks: list[str]) -> bool:
    core = list(toks)
    while core and core[0] in ("bitte", "mal", "donna", "hey", "ok", "okay", "und", "dann"):
        core.pop(0)
    return bool(core) and core[0] in _MESSAGE_VERBS


def _marker_end(toks: list[str], i: int) -> int | None:
    """Endindex (exklusiv) eines Korrekturmarkers an Position i, sonst None."""
    t = toks[i]
    if t in _MARKER_TOKENS:
        return i + 1
    for ph in _MARKER_PHRASES:
        if tuple(toks[i:i + len(ph)]) == ph:
            return i + len(ph)
    if t == "also":
        tail = toks[i + 1:]
        # nur wenn der Nachsatz selbst einen Wert/Zustand/Befehl traegt ("also leiser", "also zehn"); eine blosse
        # Praezisierung ("also im Wohnzimmer", "also Spotify") ist KEINE Korrektur
        if 0 < len(tail) <= _ALSO_MAX_TAIL and any(w in _STATE or w in HEAD_VERBS or _numeric(w) for w in tail):
            return i + 1
    return None


def _has_command_before(toks: list[str]) -> bool:
    return any(t in HEAD_VERBS for t in toks)


def restart_points(text: Any) -> list[int]:
    """Zeichenpositionen (im ``lower_keep``-Text) eines Neustarts: unvollstaendiger Befehl (endet auf Praeposition/
    Artikel) oder Stottern ("mach das mach ...") gefolgt von einem NEUEN Befehlsverb im selben Teilsatz ohne
    Satzzeichen/Verbinder dazwischen. Der Schnitt liegt vor dem neuen Verb."""
    if not isinstance(text, str):
        return []
    text = text[:MAX_INPUT]
    pts: list[int] = []
    # je Teilsatz (Komma/Punkt/...; nicht in Dezimalzahlen) und je Verbinder getrennt pruefen
    for seg_start, seg in _segments(text):
        ws = _words(seg)
        toks = [w for w, _p in ws]
        if _is_message_text(toks):
            continue
        verbs = [k for k, w in enumerate(toks) if w in HEAD_VERBS]
        for a, b in zip(verbs, verbs[1:]):
            left = toks[a:b]
            if any(t in _CONNECTORS for t in left):
                continue
            stutter = toks[a] == toks[b] and len(left) == 2   # "mach das mach ..." (nicht "mach mach das ...")
            if stutter or (left[-1] in _DANGLING and toks[b] in _RESTART_VERBS and b < len(toks) - 1):
                pts.append(seg_start + ws[b][1])
    return pts


def _segments(text: str) -> list[tuple[int, str]]:
    """Teilsaetze (Startposition, Text) an Satzzeichen und Verbindern; Positionen beziehen sich auf ``lower_keep``."""
    low = _i.lower_keep(text)
    cuts: list[tuple[int, int]] = []        # (Start, Ende) der Trenner
    for m in re.finditer(r"(?<!\d)[.,]|[.,](?!\d)|[;!?\n]+", low):
        cuts.append((m.start(), m.end()))
    for m in re.finditer(r"\b(?:und|dann|danach|ausserdem|anschliessend|sowie|oder)\b", low):
        cuts.append((m.start(), m.end()))
    cuts.sort()
    out: list[tuple[int, str]] = []
    pos = 0
    for s, e in cuts:
        if s >= pos:
            out.append((pos, low[pos:s]))
            pos = e
    out.append((pos, low[pos:]))
    return [(p, t) for p, t in out if t.strip()]


def inspect(text: Any) -> Detection:
    """Erkennt Selbstkorrektur/Satzabbruch. Rein, ohne Modell, nur auf Mikes Originaltext."""
    if not isinstance(text, str) or not text.strip():
        return Detection()
    text = text[:MAX_INPUT]
    toks = [w for w, _p in _words(text)]
    if not toks:
        return Detection()
    # Nachrichtentext ist Inhalt — aber steht danach noch ein BEFEHL im Satz, bleibt alles pruefpflichtig
    msg = _is_message_text(toks) and not any(w in HEAD_VERBS for w in toks[1:])
    # 1) Korrekturmarker mitten im Satz (vorher ein Befehl, nachher noch etwas)
    last_marker_end: int | None = None
    first_marker: int | None = None
    for i in range(1, len(toks)):
        end = _marker_end(toks, i)
        if end is None or end >= len(toks):
            continue
        if msg:
            continue
        if not _has_command_before(toks[:i]):
            continue
        if first_marker is None:
            first_marker = i
        last_marker_end = end
    if first_marker is not None and last_marker_end is not None:
        return Detection(SUSPECT, "marker", quote(toks[last_marker_end:]))
    # 2) Fuellwort mit Wertwiederholung ("sechs Uhr aeh sieben Uhr") oder Neustart ("... aeh spiel ...")
    for f, t in enumerate(toks):
        if t not in _FILLERS or f == 0:
            continue
        before, after = toks[:f], toks[f + 1:f + 4]
        if msg:
            continue
        if any(_numeric(x) for x in before) and any(_numeric(x) for x in after):
            return Detection(SUSPECT, "marker", quote(toks[f + 1:]))
        last_verb = max((k for k, w in enumerate(before) if w in HEAD_VERBS), default=None)
        if last_verb is not None and not any(w in _CONNECTORS for w in before[last_verb:]) \
                and any(w in HEAD_VERBS for w in toks[f + 1:f + 3]):
            return Detection(SUSPECT, "marker", quote(toks[f + 1:]))   # ganzer Satz unsicher
        last_state = max((k for k, w in enumerate(before) if w in _STATE), default=None)
        if last_state is not None and not any(w in _CONNECTORS for w in before[last_state:]) \
                and any(w in _STATE for w in after):
            return Detection(SUSPECT, "marker", quote(toks[f + 1:]))   # "Lampe an, aeh, aus"
    # 3) Neustart / Stottern ohne Marker
    pts = restart_points(text)
    if pts:
        low = _i.lower_keep(text)
        return Detection(SUSPECT, "restart", quote(_i.fold(low[pts[-1]:]).split()))
    return Detection()


def detect(text: Any) -> str:
    """``"clean"`` oder ``"suspect"`` (siehe Modul-Docstring)."""
    return inspect(text).verdict


def quote(tokens: list[str]) -> str:
    """Mikes eigene Worte (gefaltet, nur Buchstaben/Ziffern/Leerzeichen, <= 80 Zeichen) — nie als Befehl gebunden."""
    return " ".join(tokens)[:80].strip()


def question_hint(suggestion: str) -> str:
    """Anweisung an das Modell fuer die Rueckfrage (feste Formulierung + Mikes eigene Worte, keine Bindung)."""
    sug = f" Vorschlag aus Mikes eigenen Worten: „{suggestion}“." if suggestion else ""
    return ("Mike hat sich im Satz korrigiert oder neu angesetzt — nichts ausgefuehrt." + sug +
            " Frag in EINEM kurzen Satz nach (z.B. „Meinst du: …?“) und bitte ihn, es kurz noch einmal zu sagen; "
            "fuehre nichts aus, solange er es nicht klar gesagt hat.")

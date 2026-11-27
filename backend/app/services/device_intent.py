"""#147 Paket A — strenge, deterministische Auswertung der NUTZERNACHRICHT fuer Geraete-Aktionen.

Zwei Fragen, beide nur aus dem Text, den der Nutzer in DIESER Anfrage geschickt hat (nie aus Kalender, Bildschirm,
Gedaechtnis, Verlauf oder Tool-Ergebnissen):
  1. Enthaelt die Nachricht einen BEFEHL zu einer Aktionsart? (``requested_kinds``)
  2. Stammen die konkreten Werte der Aktion (Dauer, Uhrzeit, Kanal, Modus, Prozent, Richtung, App, Seite) aus
     der Nachricht? (``*_ok``-Funktionen, zusammengesetzt in ``device_actions.user_requested``)

Grundsatz: lieber einmal zu wenig ausloesen (dann bleibt die Karte mit Tipp bzw. eine Rueckfrage) als einmal zu viel.
Keine unscharfe Erkennung fuer kurze Woerter; Tippfehler nur bei langen, eindeutigen Woertern (>= 9 Zeichen,
Distanz 1). Fragesaetze (W-Frage, Fragezeichen) sind keine Befehle, hoefliche Bitten ("Kannst du ...?") schon.
"""
from __future__ import annotations

import math
import re
from typing import Any, NamedTuple

# ---------------------------------------------------------------------------------------------------------------
# Grundlagen
# ---------------------------------------------------------------------------------------------------------------


def lower_keep(text: str) -> str:
    """Kleinschreibung, Umlaute gefaltet, '%' als Wort; Satzzeichen bleiben."""
    return (text.lower().replace("%", " prozent ").replace("ä", "ae").replace("ö", "oe")
            .replace("ü", "ue").replace("ß", "ss"))


def fold(text: str) -> str:
    """Kleinschreibung, Umlaute gefaltet, alles ausser Buchstaben/Ziffern -> Leerzeichen (zusammengezogen)."""
    return " ".join(re.sub(r"[^a-z0-9]+", " ", lower_keep(text)).split())


def _osa(a: str, b: str, limit: int) -> bool:
    """Editierdistanz (inkl. Vertauschung benachbarter Zeichen) <= limit."""
    if abs(len(a) - len(b)) > limit:
        return False
    prev2: list[int] | None = None
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        for j in range(1, len(b) + 1):
            cost = 0 if a[i - 1] == b[j - 1] else 1
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            if prev2 is not None and i > 1 and j > 1 and a[i - 1] == b[j - 2] and a[i - 2] == b[j - 1]:
                cur[j] = min(cur[j], prev2[j - 2] + 1)
        prev2, prev = prev, cur
    return prev[len(b)] <= limit


def fuzzy_ok(token: str, word: str) -> bool:
    """Exakt, oder ein Tippfehler (Distanz 1) NUR bei langen, eindeutigen Woertern (>= 9 Zeichen)."""
    if token == word:
        return True
    return len(word) >= 9 and _osa(token, word, 1)


_W_WORDS = frozenset({
    "wie", "wann", "was", "wo", "wer", "wem", "wen", "wessen", "warum", "wieso", "weshalb", "welche", "welcher",
    "welches", "welchen", "welchem", "wohin", "woher", "wozu", "womit", "wofuer", "wieviel", "wieviele",
})
_STATE_VERBS = frozenset({"ist", "sind", "hat", "haben", "war", "waren", "bin", "bist", "seid", "habe", "hast",
                          "hatte", "hatten", "wird", "werden"})
_EXPLAIN = frozenset({"erklaer", "erklaere", "erklaeren", "sag", "sage", "sagen"})
# Verneinung (OHNE "ohne": "Timer ohne Ton" verneint die Aktion nicht) und Abbruch/Korrektur im SELBEN Teilsatz
_NEG = frozenset({"nicht", "kein", "keine", "keinen", "keinem", "keiner", "keines", "nie", "niemals"})
_ABORT = frozenset({"vergiss", "vergessen", "vergesse", "stopp", "stop", "nein", "nee"})
# Loeschwoerter: kehren Timer/Wecker um ("Wecker um 7 ausschalten", "Timer auf 5 Minuten ab")
_DELETE = frozenset({"aus", "ab", "ausschalten", "abstellen", "ausmachen", "loeschen", "loesche", "weg", "stoppen",
                     "beenden", "abbrechen", "deaktivieren", "deaktiviere", "entfernen", "verwerfen", "absagen",
                     "abschalten"})
# Woerter, die einen Erklaer-/Hilfe-/Wissensauftrag anzeigen -> nie ein Geraetebefehl
_REJECT = frozenset({"wie", "ob", "man", "zeigen", "erklaeren", "sagen", "helfen", "wissen"})
_LEAD_FILLERS = frozenset({"bitte", "donna", "hey", "ok", "okay", "und", "dann", "jetzt", "also", "mal"})

# (a) Imperativ: feste Befehlsverbformen je Aktionsart (der Teilsatz BEGINNT damit)
_KIND_IMP: dict[str, frozenset[str]] = {
    "flashlight": frozenset({"mach", "mache", "schalt", "schalte", "knips", "knipse", "aktivier", "aktiviere",
                             "deaktivier", "deaktiviere", "leucht", "leuchte"}),
    "volume": frozenset({"mach", "mache", "stell", "stelle", "dreh", "drehe", "schalt", "schalte", "setz", "setze",
                         "reg", "regle", "bring", "sei"}),
    "open": frozenset({"oeffne", "starte", "mach", "mache", "ruf", "rufe"}),
    "settings": frozenset({"oeffne", "zeig", "zeige", "starte", "mach", "mache", "ruf", "rufe"}),
    "timer": frozenset({"stell", "stelle", "setz", "setze", "mach", "mache", "starte", "start", "erstell", "erstelle",
                        "richte", "aktivier", "aktiviere", "weck", "wecke", "erinner", "erinnere"}),
    "alarm": frozenset({"stell", "stelle", "setz", "setze", "mach", "mache", "erstell", "erstelle", "richte",
                        "aktivier", "aktiviere", "weck", "wecke"}),
}
_ALL_IMP = frozenset().union(*_KIND_IMP.values())
# F (#147): System-Aktionen. Ihre Befehlsverben stehen NICHT in _ALL_IMP (Zerlegung, Routinennamen und
# Smart-Home-Erkennung bleiben unveraendert); nur ``sentences`` kennt sie als Befehlsverb (``Sent.imp``).
_KIND_IMP_SYS: dict[str, frozenset[str]] = {
    "brightness": frozenset({"mach", "mache", "stell", "stelle", "dreh", "drehe", "setz", "setze", "reg", "regle",
                             "schalt", "schalte"}),
    "dnd": frozenset({"schalt", "schalte", "mach", "mache", "stell", "stelle", "setz", "setze", "aktivier",
                      "aktiviere", "deaktivier", "deaktiviere"}),
    "lock": frozenset({"sperr", "sperre"}),
    "screenshot": frozenset({"mach", "mache", "nimm", "nehm", "nehme", "erstell", "erstelle"}),
    "camera": frozenset({"oeffne", "starte", "mach", "mache", "ruf", "rufe", "nimm", "nehm", "nehme"}),
}
_ANY_IMP = _ALL_IMP | frozenset().union(*_KIND_IMP_SYS.values())

# (c) Kurzform: nur Aktionswort + Wert/Richtung (+ bitte), hoechstens 6 Woerter
_VOCAB = frozenset({
    "taschenlampe", "handylicht", "handylampe", "taschenleuchte", "licht", "lampe", "am", "handy", "telefon",
    "timer", "stoppuhr", "countdown", "eieruhr", "wecker", "alarm", "musik", "medien", "media", "video", "ton",
    "klingelton", "klingeln", "anruf", "lautstaerke", "volume", "sound",
    "an", "aus", "ein", "stumm", "lautlos", "lauter", "leiser", "leise", "anmachen", "ausmachen", "einschalten",
    "ausschalten", "anschalten", "starten", "schalten", "um", "auf", "fuer", "in", "uhr", "halb", "viertel",
    "dreiviertel", "vor", "nach", "prozent", "minute", "minuten", "min", "stunde", "stunden", "std", "sekunde",
    "sekunden", "sek", "morgens", "abends", "nachts", "frueh", "mittags", "nachmittags", "vormittags", "die", "den",
    "das", "der", "dem", "einen", "eine", "bitte", "etwas", "bisschen", "ohne", "mir", "anderthalb",
    "eineinhalb", "halbe", "halben", "halbstunde", "viertelstunde", "dreiviertelstunde",
})
_ATTACHED = re.compile(r"^(\d+)(sek|min|std|h|s)$")


def _vocab(tok: str) -> bool:
    return (tok in _VOCAB or tok.endswith("einhalb") or tok.isdigit() or to_int(tok) is not None or bool(_ATTACHED.match(tok))
            or fuzzy_ok(tok, "taschenlampe"))


class Sent(NamedTuple):
    text: str
    tokens: list[str]
    question: bool
    low: str = ""                 # Teilsatz mit Satzzeichen (Dezimalzahlen) fuer die Wert-Auswertung
    neg: bool = False             # Verneinung oder Abbruch/Korrektur im Teilsatz
    imp: str | None = None        # Befehlsverb am Anfang (a)
    polite: bool = False          # hoefliche Bitte (b)
    short: bool = False           # Kurzform (c)


def sentences(raw: Any) -> list[Sent]:
    """Teilsaetze der Nachricht: Trennung an . ! ? ; Zeilenumbruch UND Komma (nicht in Dezimalzahlen wie 7.30 oder
    1,5), NICHT am Doppelpunkt ("Er hat gesagt: stell ..." bleibt ein Teilsatz und scheitert an der Befehlsform)."""
    if not isinstance(raw, str):
        return []
    low = lower_keep(raw)
    segs: list[tuple[str, bool]] = []
    buf: list[str] = []
    for i, ch in enumerate(low):
        between_digits = i > 0 and low[i - 1].isdigit() and i + 1 < len(low) and low[i + 1].isdigit()
        if ch in "!?;\n" or (ch in ".," and not between_digits):
            segs.append(("".join(buf), ch == "?"))
            buf = []
        else:
            buf.append(ch)
    if buf:
        segs.append(("".join(buf), False))
    out: list[Sent] = []
    for seg, ended_q in segs:
        text = fold(seg)
        if not text:
            continue
        toks = text.split()
        core = list(toks)
        while core and core[0] in _LEAD_FILLERS:
            core.pop(0)
        if not core:
            continue
        tokset = set(core)
        rejects = bool(tokset & _REJECT)
        imp = core[0] if core[0] in _ANY_IMP and not rejects and not (
            len(core) > 2 and core[1] == "mir" and core[2] in ("wie", "was", "ob", "warum", "wann", "wo")) else None
        polite = not rejects and (
            (core[0] in ("kannst", "koenntest", "wuerdest") and len(core) > 1 and core[1] == "du")
            or (core[0] == "ich" and len(core) > 1 and core[1] in ("moechte", "will", "brauche", "haette")))
        short_toks = [t for t in core if t != "bitte"]
        short = bool(short_toks) and len(short_toks) <= 6 and all(_vocab(t) for t in short_toks)
        polite_q = core[0] in ("kannst", "koenntest", "wuerdest")
        asks = (core[0] in _W_WORDS or core[0] in _STATE_VERBS or core[0] in _EXPLAIN or "ob" in tokset
                or (ended_q and not polite_q))
        neg = bool(tokset & (_NEG | _ABORT))
        out.append(Sent(text, toks, asks, seg, neg, imp, polite, short))
    return out


_NOT_ANYMORE_MUTE = re.compile(r"\bnicht mehr stumm\w*")


def _commands(raw: Any, allow_unmute: bool = False) -> list[Sent]:
    """Befehls-Teilsaetze: Imperativ, hoefliche Bitte oder Kurzform; keine Fragen, KEINE Verneinung/Abbruch (einzige
    Ausnahme Lautstaerke: 'nicht mehr stumm' = nur unmute). Im Zweifel nicht: dann bleibt die Karte mit Tipp."""
    out = []
    for c in sentences(raw):
        if c.question or not (c.imp or c.polite or c.short):
            continue
        if c.neg:
            hits = [t for t in c.tokens if t in _NEG or t in _ABORT]
            if not (allow_unmute and _NOT_ANYMORE_MUTE.search(c.text) and hits == ["nicht"]):
                continue
        out.append(c)
    return out


def _form(kind: str, c: Sent) -> bool:
    """Hat dieser Teilsatz fuer die Aktionsart eine zulaessige Befehlsform? (Imperativ mit PASSENDEM Verb, hoefliche
    Bitte oder Kurzform.)"""
    return (c.imp is not None and c.imp in _KIND_IMP[kind]) or c.polite or c.short


def _delete_word(c: Sent) -> bool:
    return bool(set(c.tokens) & _DELETE)


# ---------------------------------------------------------------------------------------------------------------
# Zahlwoerter
# ---------------------------------------------------------------------------------------------------------------

_UNITS = {
    "null": 0, "ein": 1, "eine": 1, "einen": 1, "einem": 1, "einer": 1, "eins": 1, "zwei": 2, "drei": 3, "vier": 4,
    "fuenf": 5, "sechs": 6, "sieben": 7, "acht": 8, "neun": 9, "zehn": 10, "elf": 11, "zwoelf": 12, "dreizehn": 13,
    "vierzehn": 14, "fuenfzehn": 15, "sechzehn": 16, "siebzehn": 17, "achtzehn": 18, "neunzehn": 19,
}
_TENS = {"zwanzig": 20, "dreissig": 30, "vierzig": 40, "fuenfzig": 50, "sechzig": 60, "siebzig": 70,
         "achtzig": 80, "neunzig": 90}
_ARTICLES = frozenset({"ein", "eine", "einen", "einem", "einer"})


def to_int(tok: str) -> int | None:
    """'30' / 'dreissig' / 'einundzwanzig' -> Zahl (0..99), sonst None."""
    if tok.isdigit():
        return int(tok) if len(tok) <= 4 else None
    if tok in _UNITS:
        return _UNITS[tok]
    if tok in _TENS:
        return _TENS[tok]
    if "und" in tok:
        a, _, b = tok.partition("und")
        if a in _UNITS and 1 <= _UNITS[a] <= 9 and b in _TENS:
            return _UNITS[a] + _TENS[b]
    return None


# ---------------------------------------------------------------------------------------------------------------
# Dauer (Timer)
# ---------------------------------------------------------------------------------------------------------------

_SEC = frozenset({"sekunde", "sekunden", "sek", "sec"})
_MIN = frozenset({"minute", "minuten", "min"})
_HOUR = frozenset({"stunde", "stunden", "std", "h"})

_NOT_AFTER_IN = frozenset({"uhr", "prozent", "tagen", "tage", "tag", "wochen", "woche", "monaten", "monate",
                           "monat", "jahren", "jahre", "jahr"})


def _clause_seconds(clause: str) -> int:
    low = lower_keep(clause)
    seconds = 0
    bad = False

    # Dezimalzahlen mit Einheit: nur sauber erkennbare Werte (x,5 / x,25 / x,75 Stunden); alles andere -> KEIN Wert
    def dec(m: re.Match) -> str:
        nonlocal seconds, bad
        whole, frac, unit = int(m.group(1)), m.group(2), m.group(3)
        if unit.startswith(("stund", "std", "h")):
            extra = {"5": 1800, "50": 1800, "25": 900, "75": 2700, "0": 0, "00": 0}.get(frac)
            if extra is None:
                bad = True
            else:
                seconds += whole * 3600 + extra
        elif frac.strip("0") == "":
            seconds += whole * 60
        else:
            bad = True
        return " "

    low = re.sub(r"(\d+)[.,](\d+)\s*(stunden|stunde|std|h|minuten|minute|min)\b", dec, low)
    if bad:
        return 0
    toks = fold(low).split()
    i = 0
    while i < len(toks):
        t = toks[i]
        nxt = toks[i + 1] if i + 1 < len(toks) else ""
        if t in ("anderthalb", "eineinhalb") and nxt in _HOUR:
            seconds += 5400
            i += 2
            continue
        if t.endswith("einhalb") and nxt in _HOUR and to_int(t[: -len("einhalb")]) is not None:
            seconds += (to_int(t[: -len("einhalb")]) or 0) * 3600 + 1800
            i += 2
            continue
        if t in ("halbe", "halben", "halbstunde") and (t == "halbstunde" or nxt in _HOUR):
            seconds += 1800
            i += 1 if t == "halbstunde" else 2
            continue
        if t in ("viertelstunde",) or (t == "viertel" and nxt in _HOUR):
            seconds += 900
            i += 1 if t == "viertelstunde" else 2
            continue
        if t in ("dreiviertelstunde",) or (t == "dreiviertel" and nxt in _HOUR):
            seconds += 2700
            i += 1 if t == "dreiviertelstunde" else 2
            continue
        m = _ATTACHED.match(t)
        if m:
            n, u = int(m.group(1)), m.group(2)
            seconds += n * (3600 if u in ("std", "h") else 60 if u == "min" else 1)
            i += 1
            continue
        n = to_int(t)
        if n is not None:
            if nxt in _SEC:
                seconds += n
                i += 2
                continue
            if nxt in _MIN:
                seconds += n * 60
                i += 2
                continue
            if nxt in _HOUR:
                seconds += n * 3600
                i += 2
                # "1 Stunde 30" -> 90 (bare Zahl direkt nach der Stunde ohne Einheit = Minuten)
                v = to_int(toks[i]) if i < len(toks) else None
                after = toks[i + 1] if i + 1 < len(toks) else ""
                if v is not None and 1 <= v <= 59 and after not in (_SEC | _MIN | _HOUR | {"uhr", "prozent"}):
                    seconds += v * 60
                    i += 1
                continue
        i += 1
    if seconds == 0:
        # "erinnere mich in 20" / "in 20" mit Timer-Bezug: Minuten (nicht Tage/Wochen/Monate/Jahre)
        toks_set = set(toks)
        if toks_set & {"timer"} or any(x.startswith(("erinner", "weck")) for x in toks):
            for j, t in enumerate(toks[:-1]):
                n = to_int(toks[j + 1]) if t == "in" else None
                after = toks[j + 2] if j + 2 < len(toks) else ""
                if n is not None and n > 0 and after in ("", "minuten", "minute", "min", "bitte") \
                        and toks[j + 1] not in _ARTICLES:
                    seconds = n * 60
                    break
    return seconds


def _sent_durations(low: str) -> set[int]:
    """Dauern EINES Satzes: je Teilsatz (Komma) und die Summe dieses Satzes - nie ueber Satzgrenzen."""
    clauses = [c for c in re.split(r"(?<!\d),|,(?!\d)", low) if c.strip()]
    sums = [_clause_seconds(c) for c in clauses]
    out = {math.ceil(x / 60) for x in sums if x > 0}
    total = sum(sums)
    if total > 0:
        out.add(math.ceil(total / 60))
    return out


def parse_durations(raw: Any) -> set[int]:
    """Moegliche Timer-Dauern (volle Minuten, Sekunden nach OBEN gerundet): je Satz, nie ueber Satzgrenzen."""
    out: set[int] = set()
    for sent in sentences(raw):
        out |= _sent_durations(sent.low)
    return out


# ---------------------------------------------------------------------------------------------------------------
# Uhrzeit (Wecker)
# ---------------------------------------------------------------------------------------------------------------

_AM = frozenset({"morgens", "frueh", "vormittags"})
_NIGHT = frozenset({"nachts"})
_PM = frozenset({"abends", "nachmittags", "mittags"})
_NOT_HOUR_AFTER = frozenset({"minuten", "minute", "min", "stunden", "stunde", "std", "prozent", "sekunden", "sek",
                             "tage", "tagen", "tag", "wochen", "woche", "mal"})


def _expand(h: int, m: int, part: str | None) -> set[tuple[int, int]]:
    if not (0 <= h <= 24 and 0 <= m <= 59):
        return set()
    if h == 24:
        h = 0
    if h > 12 or h == 0:
        return {(h, m)}
    if part == "night":   # "11 Uhr nachts" = 23:00, "3 Uhr nachts" = 3:00, "12 Uhr nachts" = 0:00
        return {(0 if h == 12 else h + 12 if h >= 7 else h, m)}
    if part == "am":
        return {(0 if h == 12 else h, m)}
    if part == "pm":
        return {(12 if h == 12 else h + 12, m)}
    return {(h, m), (0 if h == 12 else h + 12 if h < 12 else h, m)} if h != 12 else {(12, m), (0, m)}


def parse_times(raw: Any) -> set[tuple[int, int]]:
    """Moegliche Weckzeiten (24h): je Satz, nie ueber Satzgrenzen."""
    out: set[tuple[int, int]] = set()
    for sent in sentences(raw):
        out |= _sent_times(sent.low)
    return out


def _sent_times(raw: str) -> set[tuple[int, int]]:
    """Moegliche Weckzeiten (24h) EINES Satzes. Ohne Tageshaelfte (morgens/abends ...) sind beide Lesarten
    zulaessig."""
    if not isinstance(raw, str) or not raw.strip():
        return set()
    low = lower_keep(raw)
    cands: list[tuple[int, int]] = []

    def clock(m: re.Match) -> str:
        cands.append((int(m.group(1)), int(m.group(2))))
        return " "

    low = re.sub(r"\b(\d{1,2}):(\d{2})\b", clock, low)
    low = re.sub(r"\b(\d{1,2})\.(\d{2})\s*uhr\b", clock, low)
    raw_toks = fold(low).split()
    toks: list[str] = []
    for i, t in enumerate(raw_toks):
        n = to_int(t)
        nxt = raw_toks[i + 1] if i + 1 < len(raw_toks) else ""
        if n is not None and (t not in _ARTICLES or nxt == "uhr"):
            toks.append(str(n))
        else:
            toks.append(t)
    part: str | None = None
    if set(toks) & _AM:
        part = "am"
    if set(toks) & _PM:
        part = None if part == "am" else "pm"
    if set(toks) & _NIGHT:
        part = "night"
    used = [False] * len(toks)

    def num(i: int) -> int | None:
        return int(toks[i]) if 0 <= i < len(toks) and toks[i].isdigit() else None

    explicit: list[tuple[int, int]] = []
    for i, t in enumerate(toks):
        if used[i]:
            continue
        if t == "halb" and num(i + 1) is not None and 1 <= num(i + 1) <= 12:
            explicit.append((num(i + 1) - 1 or 12, 30))
            used[i] = used[i + 1] = True
        elif t in ("viertel", "dreiviertel") and num(i + 1) is not None and 1 <= num(i + 1) <= 12:
            explicit.append((num(i + 1) - 1 or 12, 15 if t == "viertel" else 45))
            used[i] = used[i + 1] = True
        elif t == "viertel" and i + 2 < len(toks) and toks[i + 1] in ("nach", "vor") and num(i + 2) is not None:
            h = num(i + 2)
            explicit.append((h, 15) if toks[i + 1] == "nach" else (h - 1 or 12, 45))
            used[i] = used[i + 1] = used[i + 2] = True
        elif t.isdigit() and i + 2 < len(toks) and toks[i + 1] in ("nach", "vor") and num(i + 2) is not None \
                and 1 <= int(t) <= 59:
            x, h = int(t), num(i + 2)
            explicit.append((h, x) if toks[i + 1] == "nach" else (h - 1 or 12, 60 - x))
            used[i] = used[i + 1] = used[i + 2] = True
    for i, t in enumerate(toks):
        if used[i] or not t.isdigit():
            continue
        if i + 1 < len(toks) and toks[i + 1] == "uhr":
            m = num(i + 2)
            minutes = m if (m is not None and 0 <= m <= 59 and (i + 3 >= len(toks) or toks[i + 3] not in _NOT_HOUR_AFTER)) else 0
            explicit.append((int(t), minutes))
            used[i] = used[i + 1] = True
            if minutes and m is not None:
                used[i + 2] = True
        elif i > 0 and toks[i - 1] in ("um", "fuer", "auf", "gegen", "ab", "wecker", "alarm") \
                and (i + 1 >= len(toks) or toks[i + 1] not in _NOT_HOUR_AFTER) and int(t) <= 24:
            explicit.append((int(t), 0))
            used[i] = True
    out: set[tuple[int, int]] = set()
    for h, m in cands + explicit:
        out |= _expand(h, m, part)
    return out


# ---------------------------------------------------------------------------------------------------------------
# Ausloeser je Aktionsart (Satzebene)
# ---------------------------------------------------------------------------------------------------------------

_ON_PREFIX = ("anmach", "einschalt", "anschalt", "anknips", "aktivier", "einmach")
_OFF_PREFIX = ("ausmach", "ausschalt", "abschalt", "ausknips", "deaktivier")
_PHONE_LIGHT = re.compile(r"\b(?:licht|lampe|leuchte)\b.{0,12}\b(?:am|vom|auf dem|an meinem|auf meinem|von meinem)\b"
                          r".{0,10}\b(?:handy|telefon|smartphone)\b")


def _flash_key(s: Sent) -> bool:
    return (any(fuzzy_ok(t, "taschenlampe") or t in ("handylicht", "handylampe", "taschenleuchte") for t in s.tokens)
            or bool(_PHONE_LIGHT.search(s.text)) or bool(re.search(r"\bhandy (?:licht|lampe)\b", s.text)))


def _flash_dir(s: Sent) -> str | None:
    on = any(t in ("an", "ein") or t.startswith(_ON_PREFIX) for t in s.tokens)
    off = any(t == "aus" or t.startswith(_OFF_PREFIX) for t in s.tokens)
    if on == off:
        return None   # keine oder widerspruechliche Richtung -> nie raten
    return "on" if on else "off"


def flashlight_directions(raw: Any) -> set[str]:
    return {d for s in _commands(raw) if _flash_key(s) and _form("flashlight", s)
            for d in [_flash_dir(s)] if d}


_CH_MEDIA = frozenset({"musik", "medien", "media", "video", "videos", "lied", "lieder", "song", "songs"})
_MUTE_RE = re.compile(r"\b(?:ton|klingelton|klingeln|musik|medien|anruf|anrufe|gespraech)\s+aus\b")
_UNMUTE_RE = re.compile(r"\bton an\b|\bnicht mehr stumm\b|\bwieder laut\b|\bstummschaltung (?:auf|aus)\b")


def _channels(tokens: list[str]) -> set[str]:
    ch: set[str] = set()
    for t in tokens:
        if t in _CH_MEDIA:
            ch.add("media")
        elif t.startswith("klingel"):
            ch.add("ring")
        elif t.startswith("wecker") or t == "alarm":
            ch.add("alarm")
        elif t.startswith("anruf") or t in ("gespraech", "telefonat", "hoerer"):
            ch.add("call")
    return ch


def _percents(raw: Any) -> set[int]:
    toks = fold(raw if isinstance(raw, str) else "").split()
    out: set[int] = set()
    for i, t in enumerate(toks[1:], start=1):
        if t == "prozent":
            n = to_int(toks[i - 1])
            if n is not None and n <= 100:
                out.add(n)
            m = re.match(r"^(\d{1,3})$", toks[i - 1])
            if m and int(m.group(1)) <= 100:
                out.add(int(m.group(1)))
    return out


def _vol_words(s: Sent) -> dict[str, bool]:
    t = s.tokens
    return {
        "up": "lauter" in t,
        "down": "leiser" in t or "leise" in t,
        "mute": (any(x.startswith("stumm") or x == "lautlos" for x in t) or bool(_MUTE_RE.search(s.text)))
        and not _NOT_ANYMORE_MUTE.search(s.text),
        "unmute": bool(_UNMUTE_RE.search(s.text)),
        "volume": any(fuzzy_ok(x, "lautstaerke") or x == "volume" for x in t),
        "percent": bool(_percents(s.text)),
    }


def _trig_volume(s: Sent) -> bool:
    if not _form("volume", s):
        return False
    w = _vol_words(s)
    chan = bool(_channels(s.tokens)) or "ton" in s.tokens or "sound" in s.tokens
    if w["percent"] and re.search(r"\b(?:lautstaerke|volume)\s+auf\b", s.text):
        return True   # "Lautstärke auf 30 Prozent"
    if w["up"] or w["mute"] or w["unmute"] or w["volume"] or "leiser" in s.tokens or "leise" in s.tokens:
        return True
    return w["percent"] and chan


def volume_params_ok(act: dict, raw: Any) -> bool:
    chan, mode = act.get("channel"), act.get("mode")
    sents = [s for s in _commands(raw, allow_unmute=True) if _trig_volume(s)]
    if not sents:
        return False
    text_tokens = [t for s in sents for t in s.tokens]
    named = _channels(text_tokens)
    if chan not in (named if named else {"media"}):
        return False
    words = {k: any(_vol_words(s)[k] for s in sents) for k in ("up", "down", "mute", "unmute")}
    if mode in ("up", "down", "mute", "unmute"):
        return words[mode]
    if mode == "set":
        return any(act.get("percent") in _percents(s.text) for s in sents)   # Wert aus dem Ausloesersatz
    return False


_APP_FILLER = frozenset({"app", "apps", "die", "das", "der", "den", "dem", "des", "mal", "und", "oder", "ein", "eine",
                         "einen", "einem", "mir", "mich", "uns", "bitte", "auf", "mit", "fuer", "zu", "im", "in", "an",
                         "es", "ich", "du", "doch", "noch", "mach", "stell"})
_OPEN_RE = re.compile(r"\b(?:mach\w*|ruf\w*)\b(?:\s+\w+){1,4}\s+auf\b(?!\s+\d)")


def _open_verb(s: Sent) -> bool:
    if not _form("open", s):
        return False
    if s.imp in ("oeffne", "starte"):
        return True
    if "prozent" in s.tokens:   # "Mach die Helligkeit auf dreissig Prozent" ist kein Oeffnen (Paket F)
        return any(t in ("oeffnen", "starten", "oeffne", "starte") for t in s.tokens)
    return bool(_OPEN_RE.search(s.text)) or any(t in ("oeffnen", "starten", "oeffne", "starte") for t in s.tokens)


def app_named(app: Any, raw: Any) -> bool:
    """Der App-Name steht (alle Woerter, ohne Fuellwort 'App') als GANZE Woerter in einem Befehlssatz mit
    Oeffnen-Verb; Mindestlaenge 3."""
    if not isinstance(app, str):
        return False
    words = [w for w in fold(app).split() if w not in _APP_FILLER]
    comp = "".join(words)
    if len(comp) < 3:
        return False
    for s in _commands(raw):
        if not _open_verb(s):
            continue
        toks = [t for t in s.tokens if t not in _APP_FILLER]
        n = len(words)
        for i in range(len(toks)):
            if toks[i:i + n] == words:
                return True
            for k in range(1, 5):   # "you tube" == "youtube"
                if "".join(toks[i:i + k]) == comp:
                    return True
    return False


_PAGE_TERMS: dict[str, str] = {
    "wifi": r"\b(?:wlan\w*|wifi\w*|wi fi|w lan)",
    "bluetooth": r"\bbluetooth\w*",
    "mobile_data": r"\b(?:mobile daten|mobilfunk\w*|datenverbindung|mobiles internet)",
    "display": r"\b(?:display\w*|bildschirm\w*|helligkeit)",
    "sound": r"\b(?:ton|toene|sound\w*|klingelton\w*|klingeltoene)\b",
    "battery": r"\b(?:akku\w*|batterie\w*|energiespar\w*)",
    "location": r"\b(?:standort\w*|ortung\w*|gps)\b",
    "notifications": r"\bbenachrichtigung\w*",
    "app_info": r"\b(?:app info\w*|appinfo\w*|app informationen)",
    "accessibility": r"\b(?:bedienungshilfe\w*|barrierefreiheit)",
    "airplane": r"\b(?:flugmodus|flugzeugmodus)",
    "internet_panel": r"\b(?:internet|netzwerk\w*)\b",
    "volume_panel": r"\blautstaerke\w*",
}
def _settings_verb(s: Sent) -> bool:
    if not _form("settings", s):
        return False
    if s.imp in ("oeffne", "zeig", "zeige", "starte"):
        return True
    if "prozent" in s.tokens:
        return any(t in ("oeffnen", "oeffne", "starte") for t in s.tokens)
    return bool(_OPEN_RE.search(s.text)) or any(t in ("oeffnen", "oeffne", "starte") for t in s.tokens)


def page_named(page: Any, raw: Any) -> bool:
    pat = _PAGE_TERMS.get(page) if isinstance(page, str) else None
    return bool(pat) and any(_settings_verb(s) and re.search(pat, s.text) for s in _commands(raw))


def _any_page(s: Sent) -> bool:
    return _settings_verb(s) and any(re.search(p, s.text) for p in _PAGE_TERMS.values())


def _timer_trig(s: Sent) -> bool:
    if not _form("timer", s) or _delete_word(s):
        return False
    m = re.search(r"\b(erinner\w*|weck\w*)\s+(?:mich|uns)\s+in\b", s.text)
    if m:
        # #153: "erinner mich in 20 Minuten" ohne Inhalt = Timer; mit Inhalt ("an ...", "dass ...", "ans/zum/zur ...",
        # "daran") ist es eine Erinnerung (create_reminder) und KEIN Timer.
        if not (m.group(1).startswith("erinner") and re.search(r"\b(?:an|dass|ans|zum|zur|daran)\b", s.text[m.end():])):
            return True
    return any(x in ("timer", "stoppuhr", "countdown", "eieruhr") for x in s.tokens)


def _alarm_trig(s: Sent) -> bool:
    if not _form("alarm", s) or _delete_word(s):
        return False
    if re.search(r"\bweck\w*\s+(?:mich|uns)\b", s.text):
        return True
    return any(x in ("wecker", "alarm") for x in s.tokens)


# ---------------------------------------------------------------------------------------------------------------
# Paket F — System-Aktionen: Helligkeit, Nicht stoeren, Bildschirm sperren, Screenshot, Kamera oeffnen
# ---------------------------------------------------------------------------------------------------------------
# Auswertung je ABSCHNITT eines Satzes (getrennt an und/dann/danach ...): jeder Abschnitt muss fuer sich Befehlsform,
# Aktionswort und (wo noetig) Wert tragen. Sperren, Screenshot und Kamera arbeiten mit einer engen Wortliste: jedes
# fremde Wort im Abschnitt ("Karte", "in 5 Minuten", "fuer Anrufe", "von Anna") macht ihn zu keinem Befehl.

_SEG_SPLIT = frozenset({"und", "dann", "danach", "ausserdem", "anschliessend", "sowie", "aber"})
_SEG_NOISE = frozenset({"bitte", "mal", "doch", "donna", "hey", "ok", "okay", "also", "jetzt", "gleich", "sofort",
                        "schnell", "kurz", "noch"})
_POLITE_TOKS = frozenset({"kannst", "koenntest", "wuerdest", "du", "ich", "moechte", "will", "brauche", "haette",
                          "mir", "uns"})
_SYS_VOCAB = frozenset({
    "helligkeit", "bildschirm", "display", "heller", "dunkler", "aufhellen", "abdunkeln", "bildschirmhelligkeit",
    "displayhelligkeit", "hell", "dunkel",
})
_LIGHT_WORDS = frozenset({
    "licht", "lichter", "lampe", "lampen", "leuchte", "leuchten", "steckdose", "heizung", "ventilator", "fernseher",
    "tv", "klima", "klimaanlage", "rollo", "rolladen", "rollladen", "tesla", "taschenlampe", "handylicht",
    "handylampe", "taschenleuchte", "lichterkette", "stehlampe", "deckenlampe", "wohnzimmer", "schlafzimmer",
    "kueche", "bad", "flur",
})
# Bedingungs-/Zeit-/Fremdobjekt-Woerter im SATZ: ein solcher Satz ist nie ein Sofort-Befehl (nichts wird dauerhaft
# oder zu frueh geschaltet)
_COND_WORDS = frozenset({
    "wenn", "falls", "sobald", "solange", "nachdem", "bevor", "waehrend", "bis", "fuer", "erst", "spaeter", "nachher",
    "morgen", "uebermorgen", "abend", "abends", "nachts", "mittags", "vormittags", "nachmittags", "frueh",
    "morgens", "wieder", "immer", "automatisch", "jedes", "jeden", "taeglich", "uhr", "minute", "minuten", "min",
    "stunde", "stunden", "std", "sekunde", "sekunden", "sek", "tag", "tage", "woche", "wochen", "anruf", "anrufe",
    "anrufen", "anrufer", "nachricht", "nachrichten", "sms", "whatsapp", "mails", "mail", "bei", "in", "um",
    "nach", "vor", "zu", "sonst", "ausser", "ohne",
})
_COND_LEAD = frozenset({"wenn", "falls", "sobald", "solange", "nachdem", "bevor", "waehrend", "bis", "erst", "spaeter",
                        "nachher", "fuer", "in", "um", "nach", "sonst"})
_COND_OPENERS = frozenset({"wenn", "falls", "sobald", "solange", "nachdem", "bevor", "waehrend"})
_LOCK_FOREIGN = frozenset({"karte", "karten", "konto", "konten", "sim", "simkarte", "pin", "kreditkarte", "bankkarte",
                           "nummer", "nummern", "kontakt", "kontakte", "ec", "account", "zugang"})
_BR_NOUN = frozenset({"helligkeit", "bildschirm", "display", "displays", "bildschirme"})
_WORD_NUM = {"hundert": 100, "einhundert": 100}


class _Seg(NamedTuple):
    toks: list[str]        # ohne Fuell-/Hoeflichkeitswoerter am Anfang und ohne Rauschwoerter
    lead: str              # erstes Wort des Abschnitts (Befehlsverb-Kandidat); leer bei Erklaer-/Hilfe-Saetzen
    polite: bool           # "Kannst du ..." / "Ich moechte ..." im Satz
    text: str              # Abschnitt als Text
    sent_toks: tuple = ()  # alle Woerter des ganzen Satzes (fuer satzweite Pruefungen)
    cond: bool = False     # Bedingung/Zeit/Fremdobjekt im Satz oder im Nachbarsatz -> kein Sofort-Befehl


def _segs(s: Sent, cond: bool = False) -> list[_Seg]:
    rejects = bool(set(s.tokens) & _REJECT)
    plain = (" ".join(s.tokens).replace("guten morgen", " ").replace("gute nacht", " ")
             .replace("guten abend", " ").split())   # Gruss, keine Zeitangabe
    cond = cond or bool(set(plain) & _COND_WORDS)
    whole = tuple(plain)
    out: list[_Seg] = []
    cur: list[str] = []

    def flush() -> None:
        core = list(cur)
        while core and core[0] in _LEAD_FILLERS:
            core.pop(0)
        core = [t for t in core if t not in _SEG_NOISE]
        if core:
            out.append(_Seg(core, "" if rejects else core[0], s.polite and not rejects, " ".join(core), whole, cond))

    for t in plain:
        if t in _SEG_SPLIT:
            flush()
            cur = []
        else:
            cur.append(t)
    flush()
    return out


def _cmd_segs(raw: Any, *, dnd: bool = False) -> list[_Seg]:
    """Abschnitte aller Nicht-Frage-Saetze. Verneinte Saetze zaehlen nicht (fail-closed); nur fuer 'nicht stoeren'
    (``dnd=True``) bleibt der Satz erhalten, die Verneinung wird dort je Abschnitt geprueft."""
    out: list[_Seg] = []
    sents = sentences(raw)
    for i, s in enumerate(sents):
        if s.question or (s.neg and not dnd):
            continue
        # Bedingung im Nachbarsatz: "Sperr das Handy, wenn ich schlafe" / "Wenn ich schlafe, sperr das Handy"
        nxt = next((t for t in sents[i + 1].tokens if t not in _LEAD_FILLERS), "") if i + 1 < len(sents) else ""
        prev = next((t for t in sents[i - 1].tokens if t not in _LEAD_FILLERS), "") if i > 0 else ""
        out.extend(_segs(s, cond=nxt in _COND_LEAD or prev in _COND_OPENERS))
    return out


def _sys_short(seg: _Seg, extra: frozenset[str] = frozenset()) -> bool:
    return len(seg.toks) <= 6 and all(_vocab(t) or t in _SYS_VOCAB or t in extra for t in seg.toks)


# -- Helligkeit -------------------------------------------------------------------------------------------------

def _br_noun(t: str) -> bool:
    return t in _BR_NOUN or (t.endswith("helligkeit") and len(t) > 10)


def _br_dir(toks: list[str]) -> str | None:
    up = any(t in ("heller", "aufhellen") or t.startswith("aufhell") for t in toks)
    down = any(t in ("dunkler", "abdunkeln") or t.startswith("abdunkel") for t in toks)
    if up == down:
        return None   # keine oder widerspruechliche Richtung
    return "up" if up else "down"


def _br_percents(toks: Any, extreme: bool = False) -> set[int]:
    found: set[int] = set()
    for i, t in enumerate(toks[:-1]):
        if t != "auf":
            continue
        n = _WORD_NUM.get(toks[i + 1], to_int(toks[i + 1]))
        nxt = toks[i + 2] if i + 2 < len(toks) else ""
        if n is not None and 1 <= n <= 100 and nxt in ("", "prozent"):
            found.add(n)
    if extreme:   # nur im Helligkeits-Abschnitt selbst ("Lautstaerke maximal" im Satz zaehlt nicht)
        ext = _br_extreme(toks)
        if ext is not None:
            found.add(ext)
    return found


_BR_MAX_WORDS = frozenset({"maximal", "maximale", "maximalen", "volle", "vollen", "hoechste", "hoechsten", "hellste"})
_BR_MIN_WORDS = frozenset({"minimal", "minimale", "minimalen", "niedrigste", "niedrigsten", "dunkelste"})


def _br_extreme_flags(toks: Any) -> tuple[bool, bool]:
    toks = list(toks)
    hi = lo = False
    for i, t in enumerate(toks):
        nxt = toks[i + 1] if i + 1 < len(toks) else ""
        if t == "ganz" and nxt == "hell":
            hi = True
        elif t == "ganz" and nxt == "dunkel":
            lo = True
        elif t in _BR_MAX_WORDS:
            hi = True
        elif t in _BR_MIN_WORDS:
            lo = True
    return hi, lo


def _br_extreme(toks: Any) -> int | None:
    """'ganz hell' / 'maximal' -> 100; 'ganz dunkel' / 'minimal' -> 5 (Untergrenze, nie dunkler). Sonst None."""
    hi, lo = _br_extreme_flags(toks)
    if hi == lo:
        return None
    return 100 if hi else 5


_BR_ALLOWED = frozenset({
    "mach", "mache", "machen", "stell", "stelle", "stellen", "dreh", "drehe", "drehen", "setz", "setze", "setzen",
    "reg", "regle", "schalt", "schalte", "den", "die", "das", "der", "dem", "mein", "meine", "meinen", "meines",
    "meinem", "dein", "deine", "deinen", "handy", "handys", "telefon", "smartphone", "auf", "prozent", "etwas",
    "bisschen", "ein", "eine", "einen", "bitte", "heller", "dunkler", "aufhellen", "abdunkeln", "hell", "dunkel",
    "ganz",
}) | _BR_NOUN | _BR_MAX_WORDS | _BR_MIN_WORDS


def _br_allowed(t: str) -> bool:
    return t in _BR_ALLOWED or _br_noun(t) or t.isdigit() or to_int(t) is not None or t in _WORD_NUM


def _br_dir_whole(toks: Any) -> str | None:
    up = any(t in ("heller", "aufhellen") or t.startswith("aufhell") for t in toks)
    down = any(t in ("dunkler", "abdunkeln") or t.startswith("abdunkel") for t in toks)
    return None if up == down else ("up" if up else "down")


def _br_seg(seg: _Seg) -> tuple[str | None, int | None] | None:
    """(Richtung, Prozent) wenn der Abschnitt einen Helligkeits-BEFEHL traegt, sonst None. 'Licht heller' (Smart Home)
    und jeder Abschnitt mit Geraete-/Lichtwort gehoert nie dazu."""
    toks = seg.toks
    if seg.cond or not any(_br_noun(t) for t in toks):
        return None
    if set(toks) & _LIGHT_WORDS or any(fuzzy_ok(t, "taschenlampe") for t in toks):
        return None
    # Wortliste: jedes fremde Wort (Ort "im Kinderzimmer", "vom Nachtlicht", "im Buero") -> kein Befehl (Smart Home)
    allowed = _POLITE_TOKS if seg.polite else frozenset()
    if not all(_br_allowed(t) or t in allowed for t in toks):
        return None
    if not (seg.lead in _KIND_IMP_SYS["brightness"] or seg.polite or _sys_short(seg)):
        return None
    # satzweit: zwei verschiedene Prozentwerte oder widerspruechliche Richtungen -> nichts
    if len(_br_percents(seg.sent_toks)) > 1:
        return None
    if all(_br_extreme_flags(seg.sent_toks)):   # "ganz hell und ganz dunkel": widerspruechlich, nichts ausloesen
        return None
    if any(t in ("heller", "aufhellen", "dunkler", "abdunkeln") for t in seg.sent_toks)             and _br_dir_whole(seg.sent_toks) is None:
        return None
    d, ps = _br_dir(toks), _br_percents(toks, extreme=True)
    p = next(iter(ps)) if len(ps) == 1 else None
    if d is None and p is None:
        return None
    return d, p


def brightness_ok(act: dict, raw: Any) -> bool:
    mode = act.get("mode")
    for seg in _cmd_segs(raw):
        r = _br_seg(seg)
        if r is None:
            continue
        d, p = r
        if d is not None and p is not None:
            continue   # Richtung UND Prozentwert im selben Abschnitt: mehrdeutig, nie ausloesen
        if mode in ("up", "down") and d == mode:
            return True
        if mode == "set" and p is not None and act.get("percent") == p:
            return True
    return False


# -- Nicht stoeren ----------------------------------------------------------------------------------------------

_DND_PHRASE = re.compile(r"\bnicht stoeren\b|\bdnd\b")
_DND_ON = ("einschalt", "anschalt", "anmach", "aktivier", "einmach")
_DND_OFF = ("ausschalt", "abschalt", "ausmach", "deaktivier")
_DND_VOCAB = frozenset({"nicht", "stoeren", "modus", "dnd"})
_DND_ALLOWED = _DND_VOCAB | frozenset({
    "an", "ein", "aus", "ab", "auf", "den", "die", "das", "der", "mein", "meinen", "handy", "telefon", "smartphone",
    "schalt", "schalte", "mach", "mache", "stell", "stelle", "setz", "setze", "aktivier", "aktiviere", "deaktivier",
    "deaktiviere", "einschalten", "ausschalten", "anschalten", "abschalten", "anmachen", "ausmachen", "aktivieren",
    "deaktivieren", "schalten", "machen", "stellen"})


def _dnd_dir(toks: list[str]) -> str | None:
    on = any(t in ("an", "ein") or t.startswith(_DND_ON) for t in toks)
    off = any(t in ("aus", "ab") or t.startswith(_DND_OFF) for t in toks)
    if on == off:
        return None
    return "on" if on else "off"


def _dnd_seg(seg: _Seg) -> str | None:
    """Richtung, wenn der Abschnitt 'nicht stoeren' MIT Richtung als Befehl traegt. Die Verneinung greift hier nur fuer
    das eine 'nicht' der Phrase: jede weitere Verneinung/Abbruchwort -> kein Befehl."""
    if seg.cond or not _DND_PHRASE.search(seg.text):
        return None
    # Wortliste: nur Befehlsverb + Phrase + Richtung ("um 22 Uhr", "fuer eine Stunde", "fuer Anrufe von Anna" -> nein)
    allowed = _DND_ALLOWED | (_POLITE_TOKS if seg.polite else frozenset())
    if not all(t in allowed for t in seg.toks):
        return None
    hits = [t for t in seg.toks if t in _NEG or t in _ABORT]
    if hits not in ([], ["nicht"]):
        return None
    if not (seg.lead in _KIND_IMP_SYS["dnd"] or seg.polite or _sys_short(seg, _DND_VOCAB)):
        return None
    return _dnd_dir(seg.toks)


def dnd_ok(state: Any, raw: Any) -> bool:
    return state in {d for seg in _cmd_segs(raw, dnd=True) for d in [_dnd_seg(seg)] if d}


# -- Bildschirm sperren -----------------------------------------------------------------------------------------

_LOCK_VERBS = frozenset({"sperr", "sperre", "sperren"})
_LOCK_OBJ = frozenset({"bildschirm", "display", "handy", "telefon", "smartphone"})
_LOCK_ALLOWED = _LOCK_VERBS | _LOCK_OBJ | frozenset({"das", "den", "dein", "mein", "meine", "meinen", "deinen", "ein"})


def _lock_seg(seg: _Seg) -> bool:
    toks = seg.toks
    if seg.cond or not (set(toks) & _LOCK_VERBS and set(toks) & _LOCK_OBJ):
        return False
    if set(seg.sent_toks) & _LOCK_FOREIGN:
        return False   # satzweit: "Sperre den Bildschirm und die Karte" -> nie
    allowed = _LOCK_ALLOWED | (_POLITE_TOKS if seg.polite else frozenset())
    return all(t in allowed for t in toks)


def lock_ok(raw: Any) -> bool:
    return any(_lock_seg(seg) for seg in _cmd_segs(raw))


# -- Screenshot -------------------------------------------------------------------------------------------------

_SHOT_NOUNS = frozenset({"screenshot", "screenshots", "bildschirmfoto", "bildschirmfotos"})
_SHOT_VERBS = frozenset({"mach", "mache", "machen", "nimm", "nehm", "nehme", "nehmen", "erstell", "erstelle",
                         "erstellen", "aufnehmen"})
_SHOT_ALLOWED = _SHOT_NOUNS | _SHOT_VERBS | frozenset({"auf", "ein", "eine", "einen", "bildschirm", "vom", "von",
                                                        "dem", "das", "den", "der", "die", "hier", "davon"})
_DET = frozenset({"ein", "eine", "einen"})


def _shot_toks(toks: list[str]) -> list[str]:
    """'screen shot' / 'bildschirm foto' (Spracherkennung trennt) zu einem Wort zusammenziehen."""
    out: list[str] = []
    i = 0
    while i < len(toks):
        if toks[i:i + 2] == ["screen", "shot"]:
            out.append("screenshot")
            i += 2
        elif toks[i:i + 2] == ["bildschirm", "foto"]:
            out.append("bildschirmfoto")
            i += 2
        else:
            out.append(toks[i])
            i += 1
    return out


def _shot_seg(seg: _Seg) -> bool:
    toks = _shot_toks(seg.toks)
    if seg.cond or not set(toks) & _SHOT_NOUNS:
        return False
    allowed = _SHOT_ALLOWED | (_POLITE_TOKS if seg.polite else frozenset())
    if not all(t in allowed for t in toks):
        return False
    rest = [t for t in toks if t not in _DET]
    if len(rest) == 1:
        return True   # nur das Wort ("Screenshot", "ein Screenshot")
    verbs = set(toks) & _SHOT_VERBS
    if not verbs:
        return False
    if verbs & {"nimm", "nehm", "nehme", "nehmen"} and "auf" not in toks:
        return False   # "nimm einen Screenshot auf"
    return True


def screenshot_ok(raw: Any) -> bool:
    return any(_shot_seg(seg) for seg in _cmd_segs(raw))


# -- Kamera oeffnen ---------------------------------------------------------------------------------------------

_CAM_NOUNS = frozenset({"kamera", "fotokamera", "videokamera", "handykamera", "kameraapp"})
_CAM_PHOTO = frozenset({"foto", "fotos", "fotokamera", "fotomodus", "selfie", "selfies"})
_CAM_VIDEO = frozenset({"video", "videos", "videokamera", "videomodus"})
_CAM_OPEN_ALLOWED = _CAM_NOUNS | _CAM_PHOTO | _CAM_VIDEO | frozenset({
    "oeffne", "oeffnen", "starte", "starten", "mach", "mache", "machen", "ruf", "rufe", "auf", "app", "die", "das",
    "den", "meine", "meinen", "im", "modus", "hier"})
_CAM_SHOOT_VERBS = frozenset({"mach", "mache", "machen", "nimm", "nehm", "nehme", "nehmen", "aufnehmen"})
_CAM_SHOOT_ALLOWED = _CAM_NOUNS | _CAM_PHOTO | _CAM_VIDEO | _CAM_SHOOT_VERBS | frozenset({
    "auf", "ein", "eine", "einen", "mit", "der", "dem", "handy"})


def _cam_seg(seg: _Seg) -> set[str] | None:
    """Erlaubte Aufnahmeart ({'photo'} bzw. {'video'}), wenn der Abschnitt 'Kamera oeffnen' bzw. 'Foto/Video machen' als
    Befehl traegt, sonst None. Foto UND Video zugleich oder Fremdwoerter ('Video-Call', 'von Anna') -> None."""
    toks = seg.toks
    if seg.cond:
        return None
    tokset = set(toks)
    has_photo, has_video = bool(tokset & _CAM_PHOTO), bool(tokset & _CAM_VIDEO)
    if has_photo and has_video:
        return None
    caps = {"video"} if has_video else {"photo"}
    # (a) Kamera oeffnen
    if tokset & _CAM_NOUNS:
        allowed = _CAM_OPEN_ALLOWED | (_POLITE_TOKS if seg.polite else frozenset())
        if all(t in allowed for t in toks):
            if tokset & {"oeffne", "oeffnen", "starte", "starten"}:
                return caps
            if tokset & {"mach", "mache", "machen", "ruf", "rufe"} and "auf" in toks:
                return caps
    # (b) Foto/Video machen oder aufnehmen
    if tokset & (_CAM_PHOTO | _CAM_VIDEO):
        allowed = _CAM_SHOOT_ALLOWED | (_POLITE_TOKS if seg.polite else frozenset())
        verbs = tokset & _CAM_SHOOT_VERBS
        if verbs and all(t in allowed for t in toks):
            if verbs & {"nimm", "nehm", "nehme", "nehmen"} and "auf" not in toks:
                return None
            if verbs & {"machen", "aufnehmen", "nehmen"} or tokset & _DET:
                return caps
    return None


def camera_ok(capture: Any, raw: Any) -> bool:
    return any(capture in (_cam_seg(seg) or ()) for seg in _cmd_segs(raw))


def _sys_kinds(raw: Any) -> set[str]:
    kinds: set[str] = set()
    for seg in _cmd_segs(raw):
        if _br_seg(seg) is not None:
            kinds.add("brightness")
        if _lock_seg(seg):
            kinds.add("lock_screen")
        if _shot_seg(seg):
            kinds.add("screenshot")
        if _cam_seg(seg) is not None:
            kinds.add("open_camera")
    if any(_dnd_seg(seg) for seg in _cmd_segs(raw, dnd=True)):
        kinds.add("dnd")
    return kinds


# ---------------------------------------------------------------------------------------------------------------
# Oeffentliche Pruefungen
# ---------------------------------------------------------------------------------------------------------------


def requested_kinds(user_text: Any) -> frozenset[str]:
    """Aktionsarten, zu denen die Nutzernachricht einen BEFEHL enthaelt (Grundlage fuer das Angebot)."""
    kinds: set[str] = set()
    for s in _commands(user_text, allow_unmute=True):
        if _flash_key(s) and _flash_dir(s) and _form("flashlight", s):
            kinds.add("flashlight")
        if _trig_volume(s):
            kinds.add("volume")
        if _open_verb(s):
            kinds.add("open_app")
        if _any_page(s):
            kinds.add("open_settings")
        if _timer_trig(s) or _alarm_trig(s):
            kinds.add("timer_alarm")
    kinds |= _sys_kinds(user_text)
    return frozenset(kinds)


def timer_ok(minutes: Any, raw: Any) -> bool:
    if not isinstance(minutes, int) or isinstance(minutes, bool):
        return False
    return any(_timer_trig(s) and minutes in _sent_durations(s.low) for s in _commands(raw))


def alarm_ok(time_hhmm: Any, raw: Any) -> bool:
    m = re.match(r"^(\d{2}):(\d{2})$", time_hhmm) if isinstance(time_hhmm, str) else None
    return bool(m) and any(_alarm_trig(s) and (int(m.group(1)), int(m.group(2))) in _sent_times(s.low)
                           for s in _commands(raw))


def flashlight_ok(state: Any, raw: Any) -> bool:
    return state in flashlight_directions(raw)

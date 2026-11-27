"""#147 Paket H — Termine aendern und loeschen: strenge, deterministische Auswertung der NUTZERNACHRICHT.

Variante (b) (Mike 22.11.): gesucht, geprueft und geschrieben wird AUF DEM GERAET (Laufzeitberechtigung "Kalender
schreiben"); der Server hat KEINEN Schreibzugriff auf den Kalender. Der Server entscheidet nur, ob Mikes eigener Satz
ein Aendern/Loeschen/Umbenennen verlangt und welche Werte er nennt, und legt dann eine Karte ab (Stufe 2: erst
Mikes Tipp fuehrt aus).

Grundsatz: Aktionsart UND Werte (Suchbegriffe, neues Datum, neue Uhrzeit, neuer Titel) kommen ausschliesslich aus
dem Text, den Mike in DIESER Anfrage geschickt hat — nie aus Kalender, Terminbeschreibungen, Web, Gedaechtnis oder
Bildschirm. Das Modell loest nur den Aufruf aus; die Karte wird aus ``parse(user_text)`` gebaut, Modellangaben, die
davon abweichen, werden abgelehnt (``bind``). Fail-closed: lieber Rueckfrage als raten.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta
from typing import Any

from app.services.device_intent import fold, lower_keep

KIND = "calendar_edit"           # Schalter in den Einstellungen der App (Standard aus), Pref `device_action_calendar_edit`
CARD_TYPE = "calendar_change"
OPS: tuple[str, ...] = ("move", "rename", "delete")
CARD_MAX_AGE_SEC = 30 * 60       # eine offene Aenderungskarte verfaellt nach 30 Minuten (nicht erst nach 24 h)

_MAX_TEXT = 400
_MAX_TITLE = 100
_MAX_QUERY_TOKENS = 6

_W_WORDS = frozenset({"wie", "wann", "was", "wo", "wer", "wem", "wen", "warum", "wieso", "weshalb", "welche",
                      "welcher", "welches", "welchen", "wohin", "woher", "wozu", "womit", "wofuer"})
_NEG = frozenset({"nicht", "kein", "keine", "keinen", "keinem", "keiner", "nie", "niemals", "ohne",
                  "vergiss", "vergessen", "stopp", "stop", "nein", "nee", "lieber"})
_EXPLAIN = frozenset({"man", "erklaeren", "erklaere", "zeigen", "wissen", "helfen"})
_COND = frozenset({"wenn", "falls", "ob", "sobald", "solange", "angenommen", "faellt", "fallen"})
_FILLERS = frozenset({"bitte", "donna", "hey", "ok", "okay", "und", "dann", "jetzt", "also", "mal", "mir", "doch",
                      "einfach", "kannst", "koenntest", "wuerdest", "magst", "du", "noch"})
_POLITE = frozenset({"kannst", "koenntest", "wuerdest", "magst", "sollst", "musst"})
# Aussagesatz statt Befehl ("der Termin muss geaendert werden auf 13 Uhr", "soll verschoben werden") — Mikes Sprechweise
_MODAL = frozenset({"muss", "muessen", "soll", "sollen", "sollte", "sollten", "muesste", "muessten", "gehoert", "gehoeren"})
_PASSIVE: dict[str, str] = {
    "geaendert": "change", "verschoben": "move", "verlegt": "move", "umbenannt": "rename",
    "geloescht": "delete", "entfernt": "delete", "gestrichen": "delete",
}
_CREATE_WORDS = frozenset({"anlegen", "lege", "leg", "eintragen", "trag", "erstelle", "erstellen", "hinzufuegen", "fuege", "speichere"})
_ALLDAY_WORDS = frozenset({"ganztaegig", "ganztaegige", "ganztaegigen", "ganztaegiger", "ganztaegiges", "ganztags",
                           "ganztagstermin"})
_SAMEDAY_WORDS = frozenset({"selben", "gleichen", "derselbe"})

_MOVE_IMP = frozenset({"verschieb", "verschiebe", "verleg", "verlege"})
_MOVE_INF = frozenset({"verschieben", "verlegen"})
_RENAME_IMP = frozenset({"benenne", "benenn", "nenne", "nenn"})
_RENAME_INF = frozenset({"umbenennen"})
_CHANGE_IMP = frozenset({"aender", "aendere"})
_CHANGE_INF = frozenset({"aendern"})
_DELETE_IMP = frozenset({"loesch", "loesche", "entferne", "entfern", "streich", "streiche"})
_DELETE_INF = frozenset({"loeschen", "entfernen", "streichen"})
_ALL_IMP = _MOVE_IMP | _RENAME_IMP | _CHANGE_IMP | _DELETE_IMP
_ALL_INF = _MOVE_INF | _RENAME_INF | _CHANGE_INF | _DELETE_INF
_TITLE_WORDS = frozenset({"titel", "namen", "name", "bezeichnung", "betreff", "beschriftung"})
_SERIES_WORDS = frozenset({"serie", "serientermin", "serientermine", "wiederholung", "wiederholungen", "reihe"})
_SERIES_ALL = frozenset({"ganze", "ganzen", "komplette", "kompletten", "gesamte", "gesamten", "alle", "saemtliche"})

_WEEKDAYS: dict[str, int] = {
    "montag": 0, "dienstag": 1, "mittwoch": 2, "donnerstag": 3, "freitag": 4, "samstag": 5, "sonnabend": 5,
    "sonntag": 6,
}
_MONTHS: dict[str, int] = {
    "januar": 1, "februar": 2, "maerz": 3, "april": 4, "mai": 5, "juni": 6, "juli": 7, "august": 8,
    "september": 9, "oktober": 10, "november": 11, "dezember": 12,
}
_DAYPART_PM = frozenset({"nachmittags", "abends", "nachmittag", "abend"})
_DAYPART_AM = frozenset({"morgens", "vormittags", "frueh", "vormittag", "nachts"})
_NOISE = frozenset({
    "den", "die", "das", "dem", "der", "des", "ein", "einen", "eine", "einem", "meinen", "meine", "mein", "meinem",
    "meiner", "im", "in", "am", "an", "auf", "um", "zu", "zum", "zur", "von", "vom", "mit", "bei", "nach", "vor",
    "termin", "termine", "ereignis", "eintrag", "kalender", "kalendereintrag", "naechsten", "naechste", "naechster",
    "kommenden", "kommende", "kommender", "diese", "diesen", "dieses", "dieser", "uhr", "fuer", "aus", "ab", "ich",
    "es", "auch", "spaeter", "frueher", "heute", "morgen", "uebermorgen", "den", "dir", "sich", "bis", "und",
    "als", "so", "dass", "da", "dort", "hier", "gerade", "naechsten", "folgenden", "halb", "viertel",
    "ist", "sind", "war", "wird", "werden", "drin", "drinnen", "tag", "tags", "ganzen", "gleichen", "selben",
    "derselbe", "muss", "muessen", "soll", "sollen", "sollte", "sollten", "muesste", "muessten", "sollst", "musst",
    "gehoert", "gehoeren", "besteht", "schon", "bereits", "existiert", "eingetragen", "steht",
}) | _ALLDAY_WORDS | frozenset(_PASSIVE) | _FILLERS | _SERIES_WORDS | _SERIES_ALL | _DAYPART_PM | _DAYPART_AM | frozenset(_WEEKDAYS) | frozenset(_MONTHS)

_DATE_NUM_RE = re.compile(r"(?<![\d.])(\d{1,2})\.\s?(\d{1,2})\.(?:\s?(\d{4}|\d{2})(?![\d:.])(?!\s*uhr))?")
_DATE_MONTH_RE = re.compile(r"(?<!\d)(\d{1,2})\.?\s*(" + "|".join(_MONTHS) + r")(?:\s+(\d{4}))?\b")
_TIME_COLON_RE = re.compile(r"(?<![\d.:])([01]?\d|2[0-3]):([0-5]\d)(?![\d:])(?:\s*uhr)?")
_TIME_DOT_RE = re.compile(r"(?<![\d.:])([01]?\d|2[0-3])\.([0-5]\d)\s*uhr\b")   # 15.30 Uhr; "5.12" allein ist ein Datum
_TIME_UHR_RE = re.compile(r"(?<![\d.:])([01]?\d|2[0-3])\s*uhr\b")
_TIME_HALB_RE = re.compile(r"\bhalb\s+(\d{1,2}|ein[es]?|zwei|drei|vier|fuenf|sechs|sieben|acht|neun|zehn|elf|zwoelf)\b")
_TIME_BARE_RE = re.compile(r"\bum\s+(\d{1,2})\b(?!\s*[.:\d])")
_NUM_WORDS = {"ein": 1, "eins": 1, "eine": 1, "zwei": 2, "drei": 3, "vier": 4, "fuenf": 5, "sechs": 6, "sieben": 7,
              "acht": 8, "neun": 9, "zehn": 10, "elf": 11, "zwoelf": 12}
_QUOTE_RE = re.compile(r"[\"„“”‚‘’'«»]([^\"„“”‚‘’'«»]{1,200})[\"„“”‚‘’'«»]")


@dataclass
class Parsed:
    op: str | None = None                   # move | rename | delete; None = kein Befehl dieser Art
    error: str | None = None                # feste deutsche Rueckfrage fuers Modell (Befehl erkannt, aber unklar)
    query: list[str] = field(default_factory=list)   # Suchbegriffe (gefaltet) aus Mikes Satz
    on_date: str | None = None              # Filter: Termin liegt an diesem Tag (YYYY-MM-DD)
    on_time: str | None = None              # Filter: Termin beginnt um diese Uhrzeit (HH:MM)
    new_date: str | None = None             # nur move
    new_time: str | None = None             # nur move
    new_title: str | None = None            # nur rename (Originalschreibung aus Mikes Satz)
    series_mentioned: bool = False          # Mike sprach von der ganzen Serie (braucht trotzdem 2. Bestaetigung)
    from_all_day: bool = False              # Mike nannte den Termin "ganztaegig" (Filter: nur ganztaegige Termine)

    @property
    def ok(self) -> bool:
        return self.op is not None and self.error is None


# ---------------------------------------------------------------------------------------------------------------
# Befehlserkennung
# ---------------------------------------------------------------------------------------------------------------

def _tokens(text: str) -> list[str]:
    return fold(text).split()


def detect_op(user_text: Any) -> str | None:
    """Welche Aenderungsart verlangt Mikes Satz (Befehlsform: Imperativ oder hoefliche Bitte)? None = keine.
    Grundlage fuer das Angebot des Tools; Fehler in den Werten kommen erst aus ``parse``."""
    if not isinstance(user_text, str) or not user_text.strip() or len(user_text) > _MAX_TEXT:
        return None
    toks = _tokens(user_text)
    if not toks or any(t in _W_WORDS or t in _EXPLAIN for t in toks) or any(t in _COND for t in toks):
        return None
    if any(t in _NEG for t in toks):
        return None
    lead = [t for t in toks if t not in _FILLERS]
    if not lead:
        return None
    # Aussagesatz: "... muss/soll geaendert|verschoben|... werden" (kein Imperativ, aber unmissverstaendlich)
    passive = [t for t in toks if t in _PASSIVE]
    if passive and "werden" in toks and any(t in _MODAL for t in toks) and toks[0] not in _MODAL and lead[0] not in _MODAL:
        kinds = {_PASSIVE[t] for t in passive}
        if len(kinds) != 1 or any(t in _ALL_IMP or t in _ALL_INF for t in toks):
            return None
        return _resolve_change(next(iter(kinds)), toks)
    polite = any(t in _POLITE for t in toks[:3])
    verb: str | None = None
    if lead[0] in _ALL_IMP:
        verb = lead[0]
    elif polite and any(t in _ALL_INF for t in lead):
        verb = next(t for t in lead if t in _ALL_INF)
    elif polite and any(t in _ALL_IMP for t in lead[:1]):
        verb = lead[0]
    if verb is None:
        return None
    # genau EINE Aenderungsart im Satz (sonst: kein Befehl dieser Art -> Rueckfrage/Mehrfachsatz-Logik)
    ops = {_op_of(t) for t in toks if t in _ALL_IMP or t in _ALL_INF}
    if len(ops) != 1:
        return None
    return _resolve_change(_op_of(verb), toks)


def _resolve_change(op: str, toks: list[str]) -> str:
    if op == "change":
        # "aendere ..." ist Titel (rename) oder Zeit (move): am Satz erkennbar
        return "rename" if any(t in _TITLE_WORDS for t in toks) else "move"
    return op


def wants_change(user_text: Any) -> bool:
    """Grob: spricht Mikes Satz davon, einen BESTEHENDEN Termin zu aendern/verschieben/loeschen/umbenennen (auch
    verneint oder unvollstaendig)? Dann darf in derselben Antwort KEIN neuer Termin angelegt werden."""
    if not isinstance(user_text, str) or not user_text.strip() or len(user_text) > _MAX_TEXT:
        return False
    toks = _tokens(user_text)
    if any(t in _CREATE_WORDS for t in toks):
        return False          # "lege Termin Umzug verlegen an": Aenderungsverb im Titel, Mike will anlegen
    return any(t in _ALL_IMP or t in _ALL_INF or t in _PASSIVE for t in toks)


def _op_of(verb: str) -> str:
    if verb in _MOVE_IMP or verb in _MOVE_INF:
        return "move"
    if verb in _RENAME_IMP or verb in _RENAME_INF:
        return "rename"
    if verb in _DELETE_IMP or verb in _DELETE_INF:
        return "delete"
    return "change"


# ---------------------------------------------------------------------------------------------------------------
# Datum und Uhrzeit (nur aus dem Satz)
# ---------------------------------------------------------------------------------------------------------------

@dataclass
class _Span:
    start: int
    end: int
    value: Any


def _resolve_date(day: int, month: int, year: int | None, today: date, jump: bool = True) -> date | None:
    """Datum ohne Jahr: liegt es schon hinter uns, gilt das naechste Jahr NUR fuer ein Verschiebe-Ziel (die Karte zeigt
    das Jahr). Als Filter (loeschen/umbenennen) wird nie still ins naechste Jahr gesprungen -> None = Rueckfrage."""
    try:
        if year is None:
            d = date(today.year, month, day)
            if d < today:
                if not jump:
                    return None
                d = date(today.year + 1, month, day)
            return d
        return date(year + 2000 if year < 100 else year, month, day)
    except ValueError:
        return None


def _next_weekday(wd: int, today: date, today_ok: bool = False) -> date:
    delta = (wd - today.weekday()) % 7
    return today + timedelta(days=delta if (delta or today_ok) else 7)   # Ziel: "Donnerstag" am Donnerstag = naechster


def _find_dates(low: str, today: date, today_ok: bool = False, jump: bool = True) -> tuple[list[_Span], bool]:
    """Datumsangaben mit Position. Zweiter Rueckgabewert: eine Angabe war ungueltig (z. B. 31.02.)."""
    spans: list[_Span] = []
    bad = False
    taken: list[tuple[int, int]] = []

    def free(a: int, b: int) -> bool:
        return all(b <= x or a >= y for x, y in taken)

    for m in _DATE_NUM_RE.finditer(low):
        yr = int(m.group(3)) if m.group(3) else None
        d = _resolve_date(int(m.group(1)), int(m.group(2)), yr, today, jump)
        if d is None:
            bad = True
            continue
        spans.append(_Span(m.start(), m.end(), d))
        taken.append((m.start(), m.end()))
    for m in _DATE_MONTH_RE.finditer(low):
        if not free(m.start(), m.end()):
            continue
        yr = int(m.group(3)) if m.group(3) else None
        d = _resolve_date(int(m.group(1)), _MONTHS[m.group(2)], yr, today, jump)
        if d is None:
            bad = True
            continue
        spans.append(_Span(m.start(), m.end(), d))
        taken.append((m.start(), m.end()))
    for m in re.finditer(r"\b(uebermorgen|morgen|heute|" + "|".join(_WEEKDAYS) + r")\b", low):
        if not free(m.start(), m.end()):
            continue
        w = m.group(1)
        if w == "heute":
            d = today
        elif w == "morgen":
            # "morgen frueh/abend" = der Tag; "heute morgen" ist keine Datumsangabe fuer morgen
            if low[max(0, m.start() - 6):m.start()].strip().endswith("heute"):
                continue
            d = today + timedelta(days=1)
        elif w == "uebermorgen":
            d = today + timedelta(days=2)
        else:
            d = _next_weekday(_WEEKDAYS[w], today, today_ok)
        spans.append(_Span(m.start(), m.end(), d))
        taken.append((m.start(), m.end()))
    spans.sort(key=lambda s: s.start)
    return spans, bad


def _daypart(tokens: list[str]) -> str | None:
    if any(t in _DAYPART_PM for t in tokens):
        return "pm"
    if any(t in _DAYPART_AM for t in tokens):
        return "am"
    return None


def _find_times(low: str, tokens: list[str]) -> tuple[list[_Span], str | None]:
    """Uhrzeiten mit Position. Zweiter Rueckgabewert: Fehlertext, wenn eine Angabe mehrdeutig ist (halb vier,
    um 3 ohne Tageszeit)."""
    spans: list[_Span] = []
    part = _daypart(tokens)
    err: str | None = None
    taken: list[tuple[int, int]] = []

    def free(a: int, b: int) -> bool:
        return all(b <= x or a >= y for x, y in taken)

    def adjust(h: int, minute: int) -> time | None:
        if part == "pm" and h < 12:
            h += 12
        if part == "am" and h == 12:
            h = 0
        return time(h, minute) if 0 <= h <= 23 else None

    for rx in (_TIME_COLON_RE, _TIME_DOT_RE):
        for m in rx.finditer(low):
            if not free(m.start(), m.end()):
                continue
            t = adjust(int(m.group(1)), int(m.group(2)))
            if t:
                spans.append(_Span(m.start(), m.end(), t))
                taken.append((m.start(), m.end()))
    for m in _TIME_UHR_RE.finditer(low):
        if not free(m.start(), m.end()):
            continue
        t = adjust(int(m.group(1)), 0)
        if t:
            spans.append(_Span(m.start(), m.end(), t))
            taken.append((m.start(), m.end()))
    for m in _TIME_HALB_RE.finditer(low):
        if not free(m.start(), m.end()):
            continue
        raw = m.group(1)
        n = int(raw) if raw.isdigit() else _NUM_WORDS.get(raw)
        if n is None or not 1 <= n <= 12:
            err = "Die Uhrzeit war unklar."
            continue
        if part is None:
            err = "Die Uhrzeit ist mehrdeutig (vormittags oder nachmittags?)."
            continue
        h = 12 if n == 1 else n - 1          # halb eins = 12:30, halb vier = 3:30
        t = time(h, 30) if n == 1 else adjust(h, 30)
        if t:
            spans.append(_Span(m.start(), m.end(), t))
            taken.append((m.start(), m.end()))
    for m in _TIME_BARE_RE.finditer(low):
        if not free(m.start(), m.end()):
            continue
        n = int(m.group(1))
        if n >= 13 and n <= 23:
            spans.append(_Span(m.start(), m.end(), time(n, 0)))
        elif part is not None and 1 <= n <= 12:
            t = adjust(n, 0)
            if t:
                spans.append(_Span(m.start(), m.end(), t))
        else:
            err = "Die Uhrzeit ist mehrdeutig (vormittags oder nachmittags?)."
    spans.sort(key=lambda s: s.start)
    return spans, err


def _blank(text: str, spans: list[_Span]) -> str:
    out = list(text)
    for s in spans:
        for i in range(s.start, s.end):
            out[i] = " "
    return "".join(out)


# ---------------------------------------------------------------------------------------------------------------
# Auswertung
# ---------------------------------------------------------------------------------------------------------------

_SPLIT_RE = re.compile(r"\s+(?:in|zu|auf)\s+(?!den\s+\d|dem\s+\d)", re.IGNORECASE)


def _clean_title(raw: str) -> str | None:
    raw = re.sub(r"\s+um\s*$", "", raw.strip(), flags=re.IGNORECASE)   # "... in X um": das "um" gehoert zum Verb
    t = "".join(ch for ch in raw if ch.isprintable()).strip(" \t\"„“”'.,;:!?")
    t = " ".join(t.split())
    return t[:_MAX_TITLE] if len(t) >= 1 else None


def _query_tokens(low_wo_values: str) -> list[str]:
    out: list[str] = []
    for tok in fold(low_wo_values).split():
        if tok in _NOISE or tok in _ALL_IMP or tok in _ALL_INF or tok in _TITLE_WORDS or tok.isdigit():
            continue
        if tok.endswith("termin") and len(tok) - 6 >= 3:
            tok = tok[:-6]
        if len(tok) >= 3 and tok not in out:
            out.append(tok)
    return out


def parse(user_text: Any, now: datetime) -> Parsed:
    """Mikes Satz -> geplante Aenderung. ``op`` None = kein Befehl dieser Art. Unklarheit -> ``error`` (feste
    Rueckfrage), nie ein geratener Wert."""
    op = detect_op(user_text)
    if op is None:
        return Parsed()
    text = str(user_text)
    p = Parsed(op=op)
    today = now.date()
    low = lower_keep(text)
    toks = _tokens(text)
    p.from_all_day = any(t in _ALLDAY_WORDS for t in toks)
    same_day = any(t in _SAMEDAY_WORDS for t in toks) and "tag" in toks
    p.series_mentioned = any(t in _SERIES_WORDS for t in toks) or (
        any(t in _SERIES_ALL for t in toks) and any(t in ("termine", "serie", "reihe") for t in toks))

    new_title: str | None = None
    query_source = low
    if op == "rename":
        quotes = _QUOTE_RE.findall(text)
        if len(quotes) >= 2:
            new_title = _clean_title(quotes[-1])
            query_source = lower_keep(text.replace(quotes[-1], " "))
        elif len(quotes) == 1 and not _SPLIT_RE.search(text):
            new_title = _clean_title(quotes[0])
            query_source = lower_keep(text.replace(quotes[0], " "))
        else:
            parts = list(_SPLIT_RE.finditer(text))   # auf dem Originaltext (lower_keep verschiebt Positionen)
            if parts:
                cut = parts[-1].end()
                new_title = _clean_title(text[cut:])
                query_source = lower_keep(text[:parts[-1].start()])
        if not new_title:
            p.error = "Der neue Titel fehlte — frag Mike, wie der Termin heissen soll."
            return p
        # der neue Titel ist ein Wert aus Mikes Satz: Datum/Uhrzeit darin sind keine Filter
        p.new_title = new_title

    spans_d, bad_date = _find_dates(query_source, today, today_ok=(op != "move"), jump=(op == "move"))   # Filter: auch heute, nie ins Folgejahr
    spans_t, time_err = _find_times(_blank(query_source, spans_d), _tokens(query_source))   # "3.12." ist keine Uhrzeit
    if bad_date:
        p.error = "Das Datum war unklar oder liegt schon hinter uns — frag Mike kurz, am besten mit Jahr."
        return p
    if time_err:
        p.error = time_err + " Frag Mike kurz."
        return p

    if op == "move":
        # Ziel: letzte Datumsangabe nach "auf/nach/zum/zu/um"; bei "von X auf Y" ist X der Filter
        if len(spans_d) > 2 or len(spans_t) > 2:
            p.error = "Es waren mehrere Zeitangaben — frag Mike, auf wann genau."
            return p
        if len(spans_d) == 2:
            # "von <Quelle> auf/nach/zu <Ziel>": die Reihenfolge ist fest, sonst Rueckfrage (nie vertauschen)
            ok_order = (re.search(r"\b(?:von|vom)\s+$", query_source[:spans_d[0].start])
                        and re.search(r"\b(?:auf|nach|zu|zum)\s+$", query_source[:spans_d[1].start]))
            if not ok_order:
                p.error = "Es waren zwei Daten — frag Mike, welcher Termin und auf wann."
                return p
            old_spans, _bad = _find_dates(query_source, today, today_ok=True)   # Quelle darf heute sein
            p.on_date = old_spans[0].value.isoformat() if len(old_spans) == 2 else spans_d[0].value.isoformat()
            p.new_date = spans_d[1].value.isoformat()
        elif len(spans_d) == 1:
            pre_d = query_source[:spans_d[0].start]
            pre_t = query_source[:spans_t[0].start] if spans_t else ""
            # "den Termin AM Freitag (von ganztaegig) AUF 13 Uhr": Freitag ist der Tag des Termins, nicht das Ziel
            if (len(spans_t) == 1 and re.search(r"\b(?:am|vom)\s+$", pre_d)
                    and (re.search(r"\bauf\s+$", pre_t) or p.from_all_day or same_day)):
                p.on_date = spans_d[0].value.isoformat()
            else:
                p.new_date = spans_d[0].value.isoformat()
        if len(spans_t) == 2:
            if not re.search(r"\bvon\b", query_source[:spans_t[0].start]):
                p.error = "Es waren zwei Uhrzeiten — frag Mike, auf wann genau."
                return p
            p.on_time = spans_t[0].value.strftime("%H:%M")
            p.new_time = spans_t[1].value.strftime("%H:%M")
        elif len(spans_t) == 1:
            p.new_time = spans_t[0].value.strftime("%H:%M")
        if p.new_date is None and p.new_time is None:
            p.error = "Es fehlte, auf wann der Termin soll — frag Mike (Tag und/oder Uhrzeit)."
            return p
        if p.new_date:
            nd = date.fromisoformat(p.new_date)
            if nd < today or (nd == today and p.new_time and p.new_time <= now.strftime("%H:%M")):
                p.error = "Das Ziel liegt in der Vergangenheit — frag Mike, auf wann er den Termin wirklich legen will."
                return p
    else:
        if len(spans_d) > 1 or len(spans_t) > 1:
            p.error = "Es waren mehrere Zeitangaben — frag Mike, welchen Termin er meint."
            return p
        if spans_d:
            p.on_date = spans_d[0].value.isoformat()
        if spans_t:
            p.on_time = spans_t[0].value.strftime("%H:%M")

    value_spans = spans_d + spans_t
    p.query = _query_tokens(_blank(query_source, value_spans))[:_MAX_QUERY_TOKENS]
    if not p.query:
        p.error = "Es fehlte, welcher Termin gemeint ist — frag Mike nach dem Titel."
        return p
    return p


# ---------------------------------------------------------------------------------------------------------------
# Bindung des Modellaufrufs an Mikes Satz
# ---------------------------------------------------------------------------------------------------------------

def bind(tool_input: Any, user_text: Any, now: datetime) -> tuple[Parsed | None, str | None]:
    """Prueft den Werkzeugaufruf des Modells gegen Mikes Satz. -> (Auswertung, None) oder (None, Fehlertext).
    Massgeblich ist IMMER die Auswertung des Satzes; Modellangaben duerfen sie nur bestaetigen, nie lenken."""
    if not isinstance(tool_input, dict):
        return None, "Die Angaben waren ungueltig — es wurde nichts vorbereitet."
    allowed = {"op", "query", "on_date", "on_time", "new_date", "new_time", "new_title", "from_all_day"}
    if not set(tool_input) <= allowed:
        return None, "Unbekannte Angaben — es wurde nichts vorbereitet."
    p = parse(user_text, now)
    if p.op is None:
        return None, "Mike hat das nicht ausdruecklich verlangt — es wurde nichts vorbereitet."
    if tool_input.get("op") != p.op:
        return None, "Die Aenderungsart passt nicht zu Mikes Satz — es wurde nichts vorbereitet."
    if p.error:
        return None, p.error
    # Werte des Modells: muessen aus Mikes Satz stammen und mit der Auswertung uebereinstimmen
    for key in ("on_date", "on_time", "new_date", "new_time"):
        given = tool_input.get(key)
        if given not in (None, "") and given != getattr(p, key):
            return None, "Datum oder Uhrzeit stammen nicht aus Mikes Satz — frag Mike nach dem genauen Wert."
    nt = tool_input.get("new_title")
    if nt not in (None, ""):
        if not isinstance(nt, str) or p.new_title is None or nt.strip() != p.new_title:
            return None, "Der neue Titel stammt nicht aus Mikes Satz — frag Mike, wie der Termin heissen soll."
    q = tool_input.get("query")
    if q not in (None, ""):
        if not isinstance(q, str):
            return None, "Die Suchbegriffe waren ungueltig."
        folded_msg = fold(str(user_text))
        qt = [t for t in fold(q).split() if t]
        if not qt or any(t not in folded_msg for t in qt):
            return None, "Der Terminname stammt nicht aus Mikes Satz — frag Mike, welchen Termin er meint."
    return p, None


def card_of(p: Parsed) -> dict:
    """Karte (Stufe 2) fuer die App. Keine Termin-ID, kein Kalenderinhalt: das Geraet sucht selbst und zeigt
    Vorher/Nachher bzw. den zu loeschenden Termin; erst der Tipp fuehrt aus."""
    card: dict[str, Any] = {"type": CARD_TYPE, "op": p.op, "query": list(p.query)}
    for key in ("on_date", "on_time", "new_date", "new_time", "new_title"):
        v = getattr(p, key)
        if v:
            card[key] = v
    if p.series_mentioned:
        card["series_mentioned"] = True
    if p.from_all_day:
        card["from_all_day"] = True
    return card

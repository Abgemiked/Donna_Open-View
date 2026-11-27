"""#147 Rest — E-Mail-Entwurf in der Mail-App oeffnen: strenge, deterministische Auswertung der NUTZERNACHRICHT.

Der Server schickt NIE eine E-Mail und liest kein Postfach. Er entscheidet nur, ob Mikes eigener Satz ausdruecklich
einen Mail-ENTWURF verlangt, und legt dann eine Karte ab (Stufe 2): erst Mikes Tipp laesst die App die Mail-App mit
einem vorbefuellten Entwurf oeffnen. Gesendet wird dort von Mike, nie von Donna.

Grundsatz (wie Paket H): Empfaenger, Betreff und Text kommen ausschliesslich aus dem Text, den Mike in DIESER Anfrage
geschickt hat — nie aus Kalender, Web, Gedaechtnis, Bildschirm oder Benachrichtigungen, nie vom Modell erfunden oder
"verbessert". Die Karte wird aus ``parse(user_text)`` gebaut; Modellangaben, die davon abweichen, werden abgelehnt
(``bind``). Fail-closed: lieber Rueckfrage als raten.

Nicht Teil: Postfach lesen, Anhaenge, cc/bcc, Kontakte aufloesen (ein Name ohne Adresse laesst den Empfaenger leer —
den waehlt Mike in der Mail-App), Mehrfachsatz und Routinen (ein Freitext laesst sich nicht sicher je Teilsatz binden).
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

from app.services.device_intent import fold

KIND = "email_draft"             # Schalter in den Einstellungen der App (Standard aus), Pref `device_action_email_draft`
CARD_TYPE = "email_draft"
CARD_MAX_AGE_SEC = 30 * 60       # eine offene Entwurfskarte verfaellt nach 30 Minuten

MAX_TEXT = 4600                  # ganze Nutzernachricht
MAX_SUBJECT = 200
MAX_BODY = 4000
MAX_ADDRESS = 254

_W_WORDS = frozenset({"wie", "wann", "was", "wo", "wer", "wem", "wen", "warum", "wieso", "weshalb", "welche",
                      "welcher", "welches", "welchen", "wohin", "woher", "wozu", "womit", "wofuer"})
_NEG = frozenset({"nicht", "kein", "keine", "keinen", "keinem", "keiner", "nie", "niemals",
                  "vergiss", "vergessen", "stopp", "stop", "nein", "nee", "lieber"})
_EXPLAIN = frozenset({"man", "erklaeren", "erklaere", "zeigen", "wissen", "helfen"})
_COND = frozenset({"wenn", "falls", "ob", "sobald", "solange", "angenommen"})
_FILLERS = frozenset({"bitte", "donna", "hey", "ok", "okay", "und", "dann", "jetzt", "also", "mal", "mir", "doch",
                      "einfach", "kannst", "koenntest", "wuerdest", "magst", "du", "noch"})
_POLITE = frozenset({"kannst", "koenntest", "wuerdest", "magst"})
_IMP = frozenset({"schreib", "schreibe", "entwirf", "entwerfe", "verfass", "verfasse", "erstell", "erstelle",
                  "mach", "mache", "bereite", "bereit", "oeffne", "oeffn", "setz", "setze", "formulier", "formuliere"})
_INF = frozenset({"schreiben", "entwerfen", "verfassen", "erstellen", "machen", "vorbereiten", "oeffnen",
                  "aufsetzen", "formulieren"})
_MAIL = frozenset({"mail", "email", "mails", "emails", "mailentwurf", "emailentwurf", "gmail"})
_HEAD_NOISE = frozenset({"eine", "ein", "einen", "mir", "mal", "kurze", "kurzen", "neue", "neuen", "an", "den",
                         "die", "das", "dem", "e", "app", "mit", "entwurf", "vor", "auf", "in", "der", "per"})

_ADDR_RE = re.compile(r"(?<![\w.+\-%])[A-Za-z0-9][A-Za-z0-9._+\-]{0,63}@[A-Za-z0-9](?:[A-Za-z0-9\-]{0,62}[A-Za-z0-9])?"
                      r"(?:\.[A-Za-z0-9](?:[A-Za-z0-9\-]{0,62}[A-Za-z0-9])?)*\.[A-Za-z]{2,24}(?![\w\-@])")
_SUBJ_RE = re.compile(r"\bbetreff\b[\s:,\-–]*", re.IGNORECASE)
_BODY_RE = re.compile(r"\b(?:text|inhalt|nachricht)\b[\s:,\-–]*", re.IGNORECASE)
_COLON_RE = re.compile(r":\s*")
_TRAIL_CONNECT = frozenset({"und", "dem", "der", "den", "mit", "sowie", "dazu", "ausserdem", "als", "im"})
_QUOTES = "\"'„“”‚‘’«»"


@dataclass
class Parsed:
    to: str = ""                  # "" = kein Empfaenger genannt (Mike waehlt ihn in der Mail-App)
    subject: str = ""
    body: str = ""
    error: str | None = None      # feste deutsche Rueckfrage fuers Modell (Befehl erkannt, aber unklar)
    command: bool = False         # Mikes Satz war ein Entwurfsbefehl

    @property
    def ok(self) -> bool:
        return self.command and self.error is None


def _clean(text: str, limit: int, *, multiline: bool) -> str:
    """Steuerzeichen raus (Zeilenumbruch nur im Text), aussen umschliessende Anfuehrungszeichen und Leerraum weg."""
    out = []
    for ch in text:
        if ch in "\r\n\t":
            out.append("\n" if multiline and ch == "\n" else " ")
        elif ch.isprintable():
            out.append(ch)
    s = "".join(out).strip()
    if len(s) >= 2 and s[0] in _QUOTES and s[-1] in _QUOTES:
        s = s[1:-1].strip()
    return s


def _split(text: str) -> tuple[str, str | None, str | None, str | None]:
    """-> (Kopf, Betreff, Text, Fehler). Kopf = Mikes Befehlsteil vor den Markern (Adresse nur dort); Betreff/Text None =
    nicht genannt. Marker: 'Betreff' / 'Text|Inhalt|Nachricht' (jeweils erstes Vorkommen) bzw. ein Doppelpunkt."""
    t = text.strip()
    s = _SUBJ_RE.search(t)
    # Textmarker nur HINTER einem Betreff-Marker ODER im Kopf-Rest suchen; erstes Vorkommen gilt
    b = _BODY_RE.search(t, s.end() if s else 0) if s else _BODY_RE.search(t)
    colon = None
    if b is None and s is None:
        for m in _COLON_RE.finditer(t, s.end() if s else 0):
            i = m.start()
            if 0 < i < len(t) - 1 and t[i - 1].isdigit() and t[i + 1].isdigit():
                continue   # Uhrzeit 10:30 ist kein Marker
            colon = m
            break
    body_start = b.end() if b else (colon.end() if colon else None)
    body_mark = b.start() if b else (colon.start() if colon else None)
    subj_start = s.end() if s else None
    marks = [p for p in (s.start() if s else None, body_mark) if p is not None]
    head = t[: min(marks)] if marks else t
    subject = body = None
    if s:
        end = body_mark if (body_mark is not None and body_mark >= s.end()) else len(t)
        raw = t[subj_start:end]
        toks = raw.rstrip(" ,;.").split(" ")
        while toks and toks[-1].lower().strip(",;.") in _TRAIL_CONNECT:
            toks.pop()
        subject = " ".join(toks)
    if body_start is not None:
        body = t[body_start:]
    err = None
    if s is not None:
        early = _BODY_RE.search(t)
        if early is not None and early.start() < s.start():
            err = "Die Reihenfolge war unklar. Frag Mike: erst Betreff, dann Text."
        elif subject == "" and b is not None:
            err = "Der Betreff war unklar (ein Wort wie Text oder Nachricht kommt zu frueh). Frag Mike nach dem Betreff."
    return head, subject, body, err


def _head_ok(head: str) -> bool:
    """Ist der Kopf ein Entwurfsbefehl (Befehlsform/hoefliche Bitte + Mail-Wort, keine Frage/Bedingung/Verneinung)?"""
    toks = fold(head).split()
    if not toks or len(toks) > 24:
        return False
    if any(t in _W_WORDS or t in _EXPLAIN or t in _COND or t in _NEG for t in toks):
        return False
    lead = [t for t in toks if t not in _FILLERS]
    if not lead:
        return False
    if not any(t in _MAIL for t in toks[:12]):
        return False
    polite = any(t in _POLITE for t in toks[:3])
    if lead[0] in _IMP:
        return True
    if polite and any(t in _INF for t in lead[-3:]):
        return True
    return False


def parse(user_text: Any) -> Parsed:
    """Mikes Satz -> Empfaenger/Betreff/Text. ``command`` False = kein Entwurfsbefehl (dann nichts anbieten)."""
    if not isinstance(user_text, str) or not user_text.strip() or len(user_text) > MAX_TEXT:
        return Parsed()
    head, subject, body, split_err = _split(user_text)
    if not _head_ok(head):
        return Parsed()
    p = Parsed(command=True)
    if split_err:
        p.error = split_err
        return p
    addrs = {a.lower(): a for a in _ADDR_RE.findall(head)}
    if len(addrs) > 1:
        p.error = "Mike nannte mehrere Empfaenger — ein Entwurf hat hier genau einen. Frag, wen er meint."
        return p
    if addrs:
        a = next(iter(addrs.values()))
        if len(a) > MAX_ADDRESS:
            p.error = "Die Adresse war ungueltig — frag Mike danach."
            return p
        p.to = a
    p.subject = _clean(subject, MAX_SUBJECT, multiline=False) if subject is not None else ""
    p.body = _clean(body, MAX_BODY, multiline=True) if body is not None else ""
    if len(p.subject) > MAX_SUBJECT:
        p.error = "Der Betreff ist zu lang — frag Mike nach einer kuerzeren Fassung."
    elif len(p.body) > MAX_BODY or (body is not None and len(body.strip()) > MAX_BODY):
        p.error = "Der Text ist zu lang fuer einen Entwurf — frag Mike nach einer kuerzeren Fassung."
    elif not p.subject and not p.body:
        p.error = ("Mike hat weder Betreff noch Text genannt. Frag kurz: Was soll in der Mail stehen? Er soll es "
                   "mit 'Betreff ...' und 'Text ...' sagen.")
    return p


def command_part(user_text: Any) -> str:
    """Nur Mikes BEFEHLSTEIL (vor Betreff/Text), Adressen durch ein Platzhalterwort ersetzt (der Punkt in 'x@y.de' ist
    kein Satzende). Grundlage fuer die Mehrfachsatz-Pruefung: der freie Mailtext darf Woerter wie 'und' oder 'schalte'
    enthalten, ohne dass der Satz als mehrteiliger Befehl gilt."""
    if not isinstance(user_text, str):
        return ""
    head, _s, _b, _e = _split(user_text)
    return _ADDR_RE.sub("adresse", head)


def detect(user_text: Any) -> bool:
    """Verlangt Mikes Satz einen Mail-Entwurf (Befehlsform)? Grundlage fuer das Angebot des Tools."""
    return parse(user_text).command


def _same(given: Any, expected: str) -> bool:
    if given in (None, ""):
        return True
    return isinstance(given, str) and fold(given) == fold(expected) and bool(fold(expected))


def bind(tool_input: Any, user_text: Any) -> tuple[Parsed | None, str | None]:
    """Prueft den Werkzeugaufruf des Modells gegen Mikes Satz. -> (Auswertung, None) oder (None, Fehlertext).
    Massgeblich ist IMMER die Auswertung des Satzes; Modellangaben duerfen sie nur bestaetigen, nie lenken oder
    umformulieren."""
    if not isinstance(tool_input, dict):
        return None, "Die Angaben waren ungueltig — es wurde nichts vorbereitet."
    if not set(tool_input) <= {"to", "subject", "body"}:
        return None, "Unbekannte Angaben — es wurde nichts vorbereitet."
    p = parse(user_text)
    if not p.command:
        return None, "Mike hat keinen E-Mail-Entwurf ausdruecklich verlangt — es wurde nichts vorbereitet."
    if p.error:
        return None, p.error
    given_to = tool_input.get("to")
    if given_to not in (None, "") and (not isinstance(given_to, str) or given_to.strip().lower() != p.to.lower()):
        return None, "Der Empfaenger stammt nicht aus Mikes Satz — es wurde nichts vorbereitet. Frag Mike nach der Adresse."
    if not _same(tool_input.get("subject"), p.subject):
        return None, "Der Betreff stammt nicht aus Mikes Satz — nur Mikes eigene Worte, nichts umformulieren."
    if not _same(tool_input.get("body"), p.body):
        return None, "Der Text stammt nicht aus Mikes Satz — nur Mikes eigene Worte, nichts umformulieren."
    return p, None


def card_of(p: Parsed) -> dict:
    """Karte (Stufe 2) fuer die App: nur diese vier Felder. Kein Senden-Feld, kein Anhang, kein cc/bcc."""
    return {"type": CARD_TYPE, "to": p.to, "subject": p.subject, "body": p.body}

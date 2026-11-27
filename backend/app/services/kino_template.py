"""Kino-Schnellweg ohne Modell (analog Wetter-Vorlage): "Was laeuft heute/morgen im Kino" -> Titelliste aus dem
Zwischenspeicher des KinoService, in ein bis zwei natuerlichen Saetzen.

Erkennung (`detect_kino_list_intent`) ist bewusst eng und nach dem Ausschlussprinzip gebaut: die Nachricht darf
NUR aus einer kleinen Wortliste bestehen (Frageformeln, Tagesangabe, Kino-Wort). Jedes weitere Wort (ein Filmtitel,
ein Ortsname wie "Fuerth", "Abend", "wann", "um", "und") -> None -> normaler Weg ueber Modell + Werkzeug.
Reine Funktionen ohne Netz; die Satzauswahl ist seedbar und wiederholt nie direkt dieselbe Variante.
"""
from __future__ import annotations

import random
import re
from dataclasses import dataclass

from app.services.kino_service import Overview

_MAX_CHARS = 100
_MAX_TOKENS = 12
_ALLOWED_CHARS = re.compile(r"^[A-Za-zÄÖÜäöüß\s\-?!.,']+$")
_PUNCT = re.compile(r"[?!.,]")

_DAY_WORDS = {"heute": 0, "morgen": 1, "übermorgen": 2, "uebermorgen": 2}
_CINEMA_WORDS = frozenset({"kino", "kinos", "kinoprogramm", "kinofilme", "kinofilm"})
_CUE_WORDS = frozenset({"läuft", "laeuft", "laufen", "programm", "kinoprogramm",
                        "filme", "film", "kinofilme", "kinofilm"})
_FILLER = frozenset({
    "was", "welche", "welcher", "welches", "wie", "es", "so", "denn", "eigentlich", "gerade", "aktuell",
    "aktuelle", "jetzt", "alles", "an", "für", "fuer", "bitte", "mal", "kurz", "donna", "hey", "hallo",
    "sag", "sage", "mir", "zeig", "zeige", "ich", "will", "möchte", "moechte", "wissen", "im", "in", "den",
    "die", "das", "dem", "am", "ins", "vier", "ist", "los", "noch", "gibt", "gibts",
})
_VOCAB = _CINEMA_WORDS | _CUE_WORDS | _FILLER | frozenset(_DAY_WORDS)


@dataclass(frozen=True)
class KinoListIntent:
    day_offset: int          # 0 heute, 1 morgen, 2 uebermorgen


def detect_kino_list_intent(message: str) -> KinoListIntent | None:
    """Eindeutige Frage nach dem allgemeinen Kinoprogramm (kein Film, kein Ort, keine Uhrzeit), sonst None."""
    if not isinstance(message, str):
        return None
    text = message.strip()
    if not text or len(text) > _MAX_CHARS or not _ALLOWED_CHARS.match(text):
        return None
    tokens = [t.lower() for t in _PUNCT.sub(" ", text.replace("'", "")).split()]
    if not tokens or len(tokens) > _MAX_TOKENS:
        return None
    days: set[int] = set()
    for t in tokens:
        if t not in _VOCAB:
            return None                                          # unbekanntes Wort -> mehrdeutig -> Modell
        if t in _DAY_WORDS:
            days.add(_DAY_WORDS[t])
    if len(days) > 1:
        return None
    has_cue = any(t in _CUE_WORDS for t in tokens) or (("gibt" in tokens or "gibts" in tokens) and "was" in tokens)
    if not any(t in _CINEMA_WORDS for t in tokens) or not has_cue:
        return None
    return KinoListIntent(day_offset=next(iter(days), 0))


# ── Bausteine ──────────────────────────────────────────────────────────────────────
_DAY_TEXT = {0: "heute", 1: "morgen", 2: "übermorgen"}

# {tag} = heute/morgen/uebermorgen, {Tag} = gross, {titel} = "A, B und C"
_ONE = (
    "{Tag} läuft {titel}.",
    "Im Kino gibt es {tag} {titel}.",
    "{Tag} steht {titel} auf dem Programm.",
    "Zu sehen gibt es {tag} {titel}.",
    "{Tag} läuft in den Kinos {titel}.",
)
_ALL = (
    "{Tag} laufen {titel}.",
    "Im Kino laufen {tag} {titel}.",
    "{Tag} stehen {titel} auf dem Programm.",
    "Zu sehen gibt es {tag} {titel}.",
    "In den Kinos laufen {tag} {titel}.",
    "{Tag} gibt es {titel}.",
)
_SOME = (
    "{Tag} laufen unter anderem {titel}.",
    "Im Kino laufen {tag} unter anderem {titel}.",
    "{Tag} stehen unter anderem {titel} auf dem Programm.",
    "In den Kinos laufen {tag} zum Beispiel {titel}.",
    "{Tag} gibt es unter anderem {titel}.",
    "Zu sehen gibt es {tag} unter anderem {titel}.",
)
_NONE = (
    "{Tag} läuft in den vier Kinos nichts mehr.",
    "{Tag} gibt es in den vier Kinos keine Vorstellungen mehr.",
    "In den vier Kinos läuft {tag} nichts mehr.",
)
_NONE_DAY = (
    "{Tag} läuft in den vier Kinos nichts.",
    "{Tag} gibt es in den vier Kinos keine Vorstellungen.",
)
_NONE_PART = (
    "{Tag} läuft in den abrufbaren Kinos nichts mehr.",
    "In den abrufbaren Kinos läuft {tag} nichts mehr.",
)
_NONE_PART_DAY = (
    "{Tag} läuft in den abrufbaren Kinos nichts.",
    "In den abrufbaren Kinos läuft {tag} nichts.",
)
_MORE = (
    "Noch {n} weitere auf Nachfrage.",
    "Dazu kommen noch {n} weitere, sag Bescheid, wenn du mehr willst.",
    "Es gibt noch {n} weitere, frag einfach nach.",
    "{n} weitere Filme nenne ich dir auf Nachfrage.",
)
_MORE_ONE = (
    "Noch einen weiteren auf Nachfrage.",
    "Einen weiteren Film nenne ich dir auf Nachfrage.",
)
_MISSING = (
    "{orte} konnte ich gerade nicht abrufen.",
    "Bei {orte} klappte der Abruf gerade nicht.",
    "Nur {orte} {war} gerade nicht erreichbar.",
)
_MORE_MISSING = (
    "Noch {n} weitere auf Nachfrage, nur {orte} konnte ich gerade nicht abrufen.",
    "{n} weitere nenne ich dir auf Nachfrage, bei {orte} klappte der Abruf gerade nicht.",
)
_MORE_ONE_MISSING = (
    "Einen weiteren nenne ich dir auf Nachfrage, nur {orte} konnte ich gerade nicht abrufen.",
)
_PLACEHOLDER = re.compile(r"\{(\w+)\}")


def _join(items: tuple[str, ...]) -> str:
    items = tuple(items)
    if len(items) <= 1:
        return "".join(items)
    return ", ".join(items[:-1]) + " und " + items[-1]


def _fill(template: str, values: dict[str, str]) -> str:
    """Einmaliges Ersetzen; eingesetzte Werte (Filmtitel!) werden NICHT erneut ausgewertet."""
    return _PLACEHOLDER.sub(lambda m: values.get(m.group(1), m.group(0)), template)


class KinoTemplate:
    def __init__(self, rng: random.Random | None = None) -> None:
        self._rng = rng or random.Random()
        self._last: dict[str, int] = {}

    def _pick(self, key: str, seq: tuple[str, ...]) -> str:
        idx = self._rng.randrange(len(seq))
        if len(seq) > 1 and self._last.get(key) == idx:
            idx = (idx + 1 + self._rng.randrange(len(seq) - 1)) % len(seq)
        self._last[key] = idx
        return seq[idx]

    def render(self, ov: Overview) -> str | None:
        """Ein bis zwei Saetze, ohne Quelle/Stand/Floskeln; None = kaputte Daten -> Modell."""
        try:
            tag = _DAY_TEXT.get(ov.day_offset, "heute")
            vals = {"tag": tag, "Tag": tag[:1].upper() + tag[1:], "orte": _join(tuple(ov.missing)),
                    "n": str(ov.more),
                    "war": "war" if len(ov.missing) == 1 else "waren"}
            if not ov.titles:
                pool = (_NONE_PART if ov.day_offset == 0 else _NONE_PART_DAY) if ov.missing else (
                    _NONE if ov.day_offset == 0 else _NONE_DAY)
                head = self._pick(f"none:{ov.day_offset}:{int(bool(ov.missing))}", pool)
                text = _fill(head, vals)
                return text + (" " + _fill(self._pick("missing", _MISSING), vals) if ov.missing else "")
            vals["titel"] = _join(tuple(ov.titles))
            if ov.more:
                head = self._pick("some", _SOME)
            elif len(ov.titles) == 1:
                head = self._pick("one", _ONE)
            else:
                head = self._pick("all", _ALL)
            text = _fill(head, vals)
            one = ov.more == 1
            if ov.more and ov.missing:
                tail = self._pick("more_missing1" if one else "more_missing",
                                  _MORE_ONE_MISSING if one else _MORE_MISSING)
            elif ov.more:
                tail = self._pick("more1" if one else "more", _MORE_ONE if one else _MORE)
            elif ov.missing:
                tail = self._pick("missing", _MISSING)
            else:
                tail = ""
            return (text + (" " + _fill(tail, vals) if tail else "")).strip()
        except Exception:  # noqa: BLE001 — Vorlage ist best-effort, sonst Modell
            return None


_DEFAULT = KinoTemplate()


def render_kino_answer(ov: Overview) -> str | None:
    return _DEFAULT.render(ov)

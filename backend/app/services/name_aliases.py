"""Zentrale Namens-Normalisierung fuer Spracheingaben (Abnahme #155).

Die Spracherkennung macht aus Familiennamen Varianten ("Amy" statt "Ämi", "Yannick" statt "Yannik"). Eine
deterministische Tabelle (app/data/name_alias_table.py, optional zusaetzlich die Datei aus DONNA_NAME_ALIASES_EXTRA)
ersetzt sie durch die richtige Schreibweise — nur ganze Woerter (kein Treffer in "Familie", "Chemie", "Amiens"),
mit Genitiv-s ("Amys" -> "Ämis"). Reine Funktionen, keine Netzwerk-/Modellaufrufe.
"""
from __future__ import annotations

import json
import os
import re
from pathlib import Path

_ACTION_TEXT_KEYS = ("title", "label", "name", "message", "location", "text")

_regex: re.Pattern[str] | None = None
_canon: dict[str, str] = {}


def _read(path: Path) -> dict[str, list[str]]:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    if not isinstance(raw, dict):
        return {}
    out: dict[str, list[str]] = {}
    for canonical, variants in raw.items():
        if canonical.startswith("_") or not isinstance(variants, list):
            continue
        out[canonical] = [v for v in variants if isinstance(v, str) and v.strip()]
    return out


def reload() -> None:
    """Tabelle neu einlesen (Datei im Image + optionale Zusatzdatei); defekte Dateien werden ignoriert."""
    global _regex, _canon
    from app.data.name_alias_table import ALIASES
    table = {k: list(v) for k, v in ALIASES.items()}
    extra = os.environ.get("DONNA_NAME_ALIASES_EXTRA", "").strip()
    if extra:
        for canonical, variants in _read(Path(extra)).items():
            table[canonical] = list(dict.fromkeys(table.get(canonical, []) + variants))
    _canon = {}
    for canonical, variants in table.items():
        for v in [canonical, *variants]:
            _canon[v.lower()] = canonical
    if not _canon:
        _regex = None
        return
    alts = "|".join(re.escape(k) for k in sorted(_canon, key=len, reverse=True))   # laengste zuerst: Amy-Li vor Amy
    _regex = re.compile(
        r"(?<![\w-])(" + alts + r")(?P<s>(?:'|’)?s)?(?![\w]|-\w)", re.IGNORECASE,
    )


def normalize_names(text: str) -> str:
    """Ersetzt bekannte Falschschreibungen durch die richtige Schreibweise; sonst unveraendert."""
    if not text or _regex is None:
        return text

    def _sub(m: re.Match[str]) -> str:
        canon = _canon[m.group(1).lower()]
        return canon + ("s" if m.group("s") else "")

    return _regex.sub(_sub, text)


def normalize_action(action: dict) -> dict:
    """Namen in den Textfeldern einer Karte/Aktion korrigieren (neue Kopie, wenn sich etwas aendert)."""
    if not isinstance(action, dict):
        return action
    fixed = {k: normalize_names(v) if k in _ACTION_TEXT_KEYS and isinstance(v, str) else v for k, v in action.items()}
    return fixed if fixed != action else action


reload()

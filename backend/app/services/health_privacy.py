"""health_privacy.py — gemeinsames Vokabular fuer Schlaf-/Rhythmus-Inhalte (#182).

Texte mit diesen Begriffen (Habit-Eintraege, Antworten zu Ruhefenster/Schlaf) duerfen weder live
(Stream) in Prompts noch an die STM->LTM-Extraktion (externes LLM) gehen.
"""
from __future__ import annotations

import re

RHYTHM_PRIVATE_RE = re.compile(
    r"\(Muster (?:rhythm|media|feedback)\.|von Mike bestaetigt\)|Wochenmuster:|Sehverlauf \(Datenbasis|Ruhefenster|Schlafdauer|geschlafen|gemessener Schlaf|Aktivzeiten|Handy meist aktiv"
    r"|Handy ist meist aktiv|Stream-Aufnahmen|Streams \(Twitch|streamt laut|Konfidenz (?:hoch|mittel|niedrig)"
    r"|Datenbasis \d+|Muedigkeit/Energie",
    re.IGNORECASE,
)

"""project_registry.py — kanonische Projekt-Slugs + Alias-Auflösung (dependency-free).

Single Source of Truth für die Projekt-Slugs (= `projekt:<slug>`-Labels in donna-inbox,
aus der Projektübersicht). Genutzt von:
  * github_inbox_service (Issue-Label-Validierung, Phase 1),
  * scripts/sync_project_status.py (Status-Dateinamen, Phase 2 Git-Bridge),
damit beide Seiten EXAKT dieselbe Auflösung verwenden (kein Drift zwischen Labels und Status).

Nur stdlib (re) — bewusst ohne httpx/structlog, damit die lokale Git-Bridge das Modul ohne
Backend-Abhängigkeiten importieren kann.
"""
from __future__ import annotations

import re

# Kanonische Projekt-Slugs (deckungsgleich mit den projekt:<slug>-Labels + Projektübersicht).
KNOWN_PROJECT_SLUGS: frozenset[str] = frozenset({
    "donna-assistentin", "streampost", "streamtool", "steuern-crm", "chat-tool", "learning",
    "website-abgemiked", "soullink", "verwaltung", "nicebot", "turnier-abgemiked", "guess-rank",
    "central-auth", "twitch-eventsub-hub", "irl-streaming", "minecraft-challenge", "palworld",
    "cr-stats", "akquise-ewv", "stoersender", "legal-skill", "homeassistent", "rototech-cli",
    "weiterbildung", "dragonball", "dj", "ausschreibungsscouting", "haushaltskonto",
    # Projektübergreifend: EIN Issue, das ausdrücklich für ALLE/mehrere Projekte gilt
    # (Mike-Entscheidung 2026-11-07: nie mehrere Repos, nie nachfragen welches Projekt bei "alle").
    "uebergreifend",
})

# Aliase: gesprochene/informelle Bezeichnungen UND Projektübersicht-Header-Tokens, die vom
# kanonischen Slug abweichen (z.B. Header "unKladde" → Slug "weiterbildung").
# INVARIANTE (per Test gesichert): jeder Wert MUSS in KNOWN_PROJECT_SLUGS liegen.
PROJECT_ALIASES: dict[str, str] = {
    "donna": "donna-assistentin", "assistentin": "donna-assistentin", "assistent": "donna-assistentin",
    "steuer": "steuern-crm", "steuern": "steuern-crm", "crm": "steuern-crm", "steuer-crm": "steuern-crm",
    "chattool": "chat-tool", "chat": "chat-tool",
    "homeassistant": "homeassistent", "home-assistant": "homeassistent",
    "smarthome": "homeassistent", "smart-home": "homeassistent",
    "turnier": "turnier-abgemiked", "valorant-turnier": "turnier-abgemiked",
    "guess": "guess-rank", "guessrank": "guess-rank", "guess-my-rank": "guess-rank",
    "akquise": "akquise-ewv", "leads": "akquise-ewv",
    "eventsub": "twitch-eventsub-hub", "twitch-eventsub": "twitch-eventsub-hub",
    "irl": "irl-streaming",
    "minecraft": "minecraft-challenge", "mc": "minecraft-challenge",
    "website": "website-abgemiked", "homepage": "website-abgemiked",
    "cr": "cr-stats", "clash-royale": "cr-stats", "clashroyale": "cr-stats",
    "rototech": "rototech-cli", "roto-tech": "rototech-cli",
    "unkladde": "weiterbildung", "fachwirt": "weiterbildung",
    "dragon-ball": "dragonball",
    "ausschreibung": "ausschreibungsscouting", "ausschreibungen": "ausschreibungsscouting",
    "scouting": "ausschreibungsscouting",
    "störsender": "stoersender", "party": "stoersender",
    "legal": "legal-skill", "legalskill": "legal-skill",
    "soul-link": "soullink",
    "haushalt": "haushaltskonto", "haushaltsbuch": "haushaltskonto",
    # Projektübergreifend — falls die KI es mit Umlaut/Langform schreibt (Slug bleibt "uebergreifend").
    "übergreifend": "uebergreifend", "projektübergreifend": "uebergreifend",
    "projektuebergreifend": "uebergreifend",
}


def _norm(s: str | None) -> str:
    return (s or "").strip().lower()


def resolve_project_label(hint: str | None) -> str | None:
    """Mappt eine (gesprochene) Projekt-Angabe / einen Header-Token auf einen gültigen Slug.

    Gibt den kanonischen Slug zurück (ohne 'projekt:'-Präfix) oder None, wenn unbekannt.
    Nur Slugs aus KNOWN_PROJECT_SLUGS sind gültig (keine erfundenen Labels).
    """
    h = _norm(hint)
    if not h:
        return None
    h = re.sub(r"^projekt[:\s]+", "", h).strip()
    h = h.replace(" ", "-")
    if h in KNOWN_PROJECT_SLUGS:
        return h
    if h in PROJECT_ALIASES:
        return PROJECT_ALIASES[h]
    # Alias-Vergleich ohne Bindestriche (z.B. "steuerncrm" → "steuern-crm")
    h2 = h.replace("-", "")
    for slug in KNOWN_PROJECT_SLUGS:
        if slug.replace("-", "") == h2:
            return slug
    return None


def find_project_in_text(text: str | None) -> str | None:
    """Sucht in einem Freitext (z.B. 'wie ist der Stand bei steuern-crm?') eine Projekt-
    Erwähnung und gibt den kanonischen Slug zurück, sonst None.

    Nur volle Slug-Treffer und Alias-Keys ab 4 Zeichen (als ganze Wörter), damit kurze/
    generische Aliase wie 'cr'/'mc'/'dj' im Fließtext keine Fehltreffer erzeugen.
    """
    t = _norm(text)
    if not t:
        return None
    # Alle Kandidaten mit ihrer Fundstelle sammeln und den FRÜHESTEN nehmen — deterministisch
    # (KNOWN_PROJECT_SLUGS ist ein frozenset → Iterationsreihenfolge sonst zufällig).
    best_pos: int | None = None
    best_slug: str | None = None

    def _consider(pattern: str, slug: str) -> None:
        nonlocal best_pos, best_slug
        m = re.search(pattern, t)
        if m and (best_pos is None or m.start() < best_pos):
            best_pos, best_slug = m.start(), slug

    # 1. Volle Slug-Treffer (mit Bindestrich oder als Wortfolge mit Leerzeichen)
    for slug in KNOWN_PROJECT_SLUGS:
        _consider(r"\b" + re.escape(slug) + r"\b", slug)
        spaced = slug.replace("-", " ")
        if spaced != slug:
            _consider(r"\b" + re.escape(spaced) + r"\b", slug)
    # 2. Alias-Treffer (nur ab 4 Zeichen, als ganzes Wort — gegen Fehltreffer)
    for alias, slug in PROJECT_ALIASES.items():
        if len(alias) >= 4:
            _consider(r"\b" + re.escape(alias) + r"\b", slug)
    return best_slug

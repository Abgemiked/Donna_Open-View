"""Smart Router — klassifiziert Prompts (reason) und wählt den Provider (route).

Seit der Claude-Integration (Phase 1) ist Anthropic Claude die primäre Cloud-LLM-
Stufe. Es gibt keinen Server-lokalen Provider und kein Gemini mehr als Ziel:

  Erlaubte `route`-Werte:
    "claude"  — IMMER der Wert, den decide() zurückgibt (Single-User, Claude primär).
    "local" / "gemini" — Legacy-Werte; werden vom Router nicht mehr erzeugt, bleiben
                 nur als Typ-Kommentar für alte Aufrufer/Logs.
  Fallback bei Claude-Ausfall (Mistral) entscheidet NICHT der Router, sondern der
  Provider-Dispatch in routes/chat.py.

`reason` bleibt unverändert für Observability / Modellwahl (fast vs. deep):
  1. PII-Detector (IBAN DE, Steuer-ID, Adressen, Telefon, E-Mail)  reason=pii_detected
  2. CRM-Personen-Allowlist                                        reason=crm_person_match
  3. Sensitive Tags / Keywords (#privat, "sensibel", ...)          reason=sensitive_tag / sensitive_keyword
  4. Memory/Recall-Keywords ("erinnerst", "weißt du noch", ...)    reason=memory_recall
  4.5 Gaming-Keywords (hearthstone, valorant, ...)                 reason=gaming_local_api
  5. Realtime/Current-Events-Keywords ("wetter", "aktuell", "news", ...) reason=realtime_search, enable_search=True
       (enable_search wird in Phase 2 auf das Claude-Web-Search-Tool gemappt)
  6. Längen-Heuristik: prompt + context > length_limit             reason=length_exceeds_local_context
  5.5 Stream/Twitch-Kontext ("stream", "game", "chat", ...)        reason=stream_context, enable_search=False
       (geprüft NACH Realtime UND Länge → langer Stream-Prompt behält Deep-Modell; g041/g049/g050)
  7. Default                                                       reason=default

The route decision is NEVER silent — it's logged and surfaced in API responses
so Mike can audit why a model tier was chosen.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Callable, Iterable, Optional

from app.core.logger import get_logger

log = get_logger("smart_router")

PROVIDER_CLAUDE = "claude"


# ---- Regex: German PII -------------------------------------------------------
# IBAN DE (22 chars), tolerant to spaces
_IBAN_DE = re.compile(r"\bDE\d{2}[\s]?(?:\d{4}[\s]?){4}\d{2}\b", re.IGNORECASE)
# German Steuer-ID (11 digits) and Steuernummer (10-13 digits with / separators)
_STEUER_ID = re.compile(r"\b\d{11}\b")
_STEUERNR = re.compile(r"\b\d{2,3}[\s/]\d{3}[\s/]\d{4,5}\b")
# German phone (various forms starting with 0 or +49)
_PHONE_DE = re.compile(r"(?:(?:\+49|0049|0)[\s\-/()]*\d(?:[\s\-/()]*\d){6,12})")
# Postal-address-ish: 5-digit PLZ + capitalised city word
_ADDRESS = re.compile(r"\b\d{5}\s+[A-ZÄÖÜ][a-zäöüß]+\b")
# Email
_EMAIL = re.compile(r"\b[\w.+-]+@[\w-]+\.[\w.-]+\b")

SENSITIVE_TAGS = ("#privat", "#intern", "#sensibel", "#nsfw", "#geheim")
SENSITIVE_KEYWORDS = (
    "sensibel",
    "vertraulich",
    "passwort",
    "passwörter",
    "steuer",
    "rechnung",
    "ausweis",
    "personalausweis",
    "kontonummer",
    "kreditkarte",
    "gehalt",
    "einkommen",
)

# CRM-person allowlist — deliberately empty by default.
# Populate via dependency injection / settings to match actual CRM contacts.
DEFAULT_CRM_ALLOWLIST: tuple[str, ...] = ()

DEFAULT_LENGTH_LIMIT = 6000

# ---- Alternative A: Memory/Recall keywords (reason=memory_recall) -----------
# Triggers when the user refers to Donna's past knowledge / long-term memory.
# (Früher → local/Qwen; jetzt nur noch reason-Label, Provider ist immer Claude.)
MEMORY_RECALL_KEYWORDS = (
    "erinnerst",
    "weißt du noch",
    "letzte woche",
    "du weißt",
    "ich hab dir",
    "ich habe dir",
    "letztes mal",
    "du kennst",
    "wir haben",
    "du hast gesagt",
)

# ---- Alternative B.1: Gaming keywords (reason=gaming_local_api) -------------
# Hearthstone-Daten kommen via Firestone-Sync (Syncthing), Valorant via Henrik-API.
# Diese Fragen sollen NICHT per Web-Search beantwortet werden — lokale Intent-Detection
# in chat.py (_is_games_query) liefert die Daten aus den dedizierten Quellen.
GAMING_LOCAL_KEYWORDS = (
    "hearthstone",
    "battlegrounds",
    "hs bg",
    "firestone",
    "mmr",
    "valorant",
    "valo",
    "val rank",
)

# ---- Alternative B.2: Realtime/current-events keywords → enable_search=True --
# Triggers when the prompt needs live/real-time *external* facts (Wetter, News,
# aktuelle Ereignisse). Uses word-boundary check for single-word entries to avoid
# false positives (e.g. "Spielzeug" ≠ "spiel").
REALTIME_SEARCH_KEYWORDS = (
    "wetter",
    "heute",
    "gerade",
    "aktuell",
    "news",
)

# ---- Alternative B.3: Stream/Twitch-Kontext → KEIN Web-Search ---------------
# ki-training g041/g049/g050: Begriffe wie "stream", "game", "chat" sind KEIN
# Signal für eine Web-Suche. Mikes Stream-/Twitch-Daten kommen aus lokalen Quellen
# (Firestone/Henrik/Schedule-Cache); Donnas Twitch-Bot ist bewusst AUS. Diese
# Begriffe dürfen daher weder enable_search=True setzen (sonst nudged der Prompt
# fälschlich eine Suche) noch das teure Deep-Modell erzwingen. Eigener reason-Wert
# für Observability, enable_search bleibt False.
STREAM_CONTEXT_KEYWORDS = (
    "stream",
    "streamer",
    "twitch",
    "raid",
    "viewer",
    "clip",
    "game",
    "spiel",
    "spielen",
    "chat",
    "live",
    "online",
    "läuft",
)


@dataclass(frozen=True)
class RouteDecision:
    """Result of a routing call."""

    route: str  # "claude" (einziger vom Router erzeugter Wert; siehe Modul-Docstring)
    reason: str
    matched: tuple[str, ...] = ()
    enable_search: bool = False

    def as_dict(self) -> dict:
        return {
            "route": self.route,
            "reason": self.reason,
            "matched": list(self.matched),
            "enable_search": self.enable_search,
        }


class SmartRouter:
    """Heuristic router. Pure function — no side-effects beyond logging."""

    def __init__(
        self,
        *,
        length_limit: int = DEFAULT_LENGTH_LIMIT,
        crm_allowlist: Iterable[str] = DEFAULT_CRM_ALLOWLIST,
        gemini_circuit_check: Optional[Callable[[], bool]] = None,
    ) -> None:
        self._length_limit = int(length_limit)
        self._crm_allowlist = tuple(n.lower() for n in crm_allowlist if n)
        # Legacy: wird nicht mehr zum Umrouten genutzt (kein Server-local mehr;
        # Claude bleibt Ziel). Parameter bleibt für API-Kompatibilität.
        self._gemini_circuit_check = gemini_circuit_check

    # --- public API ---

    def decide(self, *, prompt: str, context: str = "") -> RouteDecision:
        """Return the routing decision for a given prompt + optional context.

        route ist immer "claude"; `reason` dokumentiert den Auslöser.
        """
        text = f"{prompt}\n{context}"
        text_lower = text.lower()

        def _done(reason: str, matched: tuple[str, ...] = (), enable_search: bool = False) -> RouteDecision:
            decision = RouteDecision(
                route=PROVIDER_CLAUDE,
                reason=reason,
                matched=matched,
                enable_search=enable_search,
            )
            log.info("route_decision", **decision.as_dict())
            return decision

        # 1. PII detection
        pii_hits = self._detect_pii(text)
        if pii_hits:
            return _done("pii_detected", tuple(pii_hits))

        # 2. CRM-person allowlist (case-insensitive substring match)
        crm_hits = [
            name for name in self._crm_allowlist if name and name in text_lower
        ]
        if crm_hits:
            return _done("crm_person_match", tuple(crm_hits))

        # 3. Sensitive tags / keywords
        tag_hits = [tag for tag in SENSITIVE_TAGS if tag in text_lower]
        if tag_hits:
            return _done("sensitive_tag", tuple(tag_hits))

        kw_hits = [kw for kw in SENSITIVE_KEYWORDS if _word_in(text_lower, kw)]
        if kw_hits:
            return _done("sensitive_keyword", tuple(kw_hits))

        # 4. Memory/Recall keywords
        recall_hits = [kw for kw in MEMORY_RECALL_KEYWORDS if kw in text_lower]
        if recall_hits:
            return _done("memory_recall", tuple(recall_hits))

        # 4.5 Gaming keywords (Firestone-Sync + Valorant-Tracker sind lokal verfügbar;
        # die lokale Intent-Detection in chat.py greift unabhängig vom Provider)
        gaming_hits = [kw for kw in GAMING_LOCAL_KEYWORDS if kw in text_lower]
        if gaming_hits:
            return _done("gaming_local_api", tuple(gaming_hits))

        # 5. Realtime/current-events keywords → enable_search=True (Phase 2: Claude-Web-Search)
        realtime_hits = [kw for kw in REALTIME_SEARCH_KEYWORDS if _word_in(text_lower, kw)]
        if realtime_hits:
            return _done("realtime_search", tuple(realtime_hits), enable_search=True)

        # 6. Length heuristic — BEWUSST vor stream_context: ein langer Prompt (z.B.
        #    6000-8000 Zeichen) mit einem Stream-Wort darf das Deep-Modell nicht
        #    verlieren, nur weil zufällig "chat"/"live" vorkommt.
        total_len = len(text)
        if total_len > self._length_limit:
            return _done("length_exceeds_local_context", (str(total_len),))

        # 5.5 Stream/Twitch-Kontext → KEIN Web-Search (lokale Daten, Twitch bewusst aus).
        #     Verhindert Fehl-Trigger von enable_search/Deep-Modell durch "stream"/"game"/
        #     "chat" u.ä. (ki-training g041/g049/g050). Wird NACH Realtime UND Länge geprüft:
        #     echte Echtzeit-Frage bzw. langer Prompt behalten Vorrang (Deep-Modell).
        stream_hits = [kw for kw in STREAM_CONTEXT_KEYWORDS if _word_in(text_lower, kw)]
        if stream_hits:
            return _done("stream_context", tuple(stream_hits), enable_search=False)

        # 7. Default
        return _done("default")

    # --- internals ---

    @staticmethod
    def _detect_pii(text: str) -> list[str]:
        hits: list[str] = []
        if _IBAN_DE.search(text):
            hits.append("iban_de")
        if _STEUER_ID.search(text):
            hits.append("steuer_id")
        if _STEUERNR.search(text):
            hits.append("steuernummer")
        if _PHONE_DE.search(text):
            hits.append("phone_de")
        if _ADDRESS.search(text):
            hits.append("address_plz_city")
        if _EMAIL.search(text):
            hits.append("email")
        return hits


def _word_in(haystack_lower: str, needle_lower: str) -> bool:
    """Word-boundary-ish check (avoids matching 'steuer' inside 'abgesteuert')."""
    pattern = rf"\b{re.escape(needle_lower)}\b"
    return re.search(pattern, haystack_lower) is not None

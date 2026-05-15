"""Smart Router — decides between local LLM (Ollama) and Gemini.

Heuristics (evaluated in order):
  1. PII-Detector (IBAN DE, German Tax-ID, addresses, phones, CRM allowlist names) → local
  2. CRM-person allowlist match                                               → local
  3. Sensitive keyword tags (#privat, #intern, "sensibel", ...)               → local
  4. Memory/Recall keywords ("erinnerst", "weißt du noch", …)                → local (Qwen)
  5. Realtime/Stream keywords ("stream", "live", "wetter", "aktuell", …)     → gemini + enable_search=True
  6. Length heuristic: prompt + context > 6000 chars                         → gemini
  7. Default                                                                  → gemini

The route decision is NEVER silent — it's logged and surfaced in API responses
so Mike can audit why an LLM was chosen.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Iterable

from app.core.logger import get_logger

log = get_logger("smart_router")


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

# ---- Alternative A: Memory/Recall keywords → local (Qwen) -------------------
# Triggers when the user refers to Donna's past knowledge / long-term memory.
# Qwen hallucinates less on personal/vault context than Gemini.
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

# ---- Alternative B: Realtime/Stream keywords → gemini + enable_search=True --
# Triggers when the prompt needs live/real-time facts. Uses word-boundary check
# for single-word entries to avoid false positives (e.g. "Spielzeug" ≠ "spiel").
REALTIME_SEARCH_KEYWORDS = (
    # Stream / Twitch context
    "stream",
    "live",
    "raid",
    "game",
    "spiel",
    "spielen",
    "twitch",
    "viewer",
    "chat",
    "clip",
    "streamer",
    # Real-time / current events
    "wetter",
    "heute",
    "gerade",
    "aktuell",
    "news",
    "läuft",
    "online",
)


@dataclass(frozen=True)
class RouteDecision:
    """Result of a routing call."""

    route: str  # "local" | "gemini"
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
    ) -> None:
        self._length_limit = int(length_limit)
        self._crm_allowlist = tuple(n.lower() for n in crm_allowlist if n)

    # --- public API ---

    def decide(self, *, prompt: str, context: str = "") -> RouteDecision:
        """Return the routing decision for a given prompt + optional context."""
        text = f"{prompt}\n{context}"
        text_lower = text.lower()

        # 1. PII detection
        pii_hits = self._detect_pii(text)
        if pii_hits:
            decision = RouteDecision(
                route="local",
                reason="pii_detected",
                matched=tuple(pii_hits),
            )
            log.info("route_decision", **decision.as_dict())
            return decision

        # 2. CRM-person allowlist (case-insensitive substring match)
        crm_hits = [
            name for name in self._crm_allowlist if name and name in text_lower
        ]
        if crm_hits:
            decision = RouteDecision(
                route="local",
                reason="crm_person_match",
                matched=tuple(crm_hits),
            )
            log.info("route_decision", **decision.as_dict())
            return decision

        # 3. Sensitive tags / keywords
        tag_hits = [tag for tag in SENSITIVE_TAGS if tag in text_lower]
        if tag_hits:
            decision = RouteDecision(
                route="local",
                reason="sensitive_tag",
                matched=tuple(tag_hits),
            )
            log.info("route_decision", **decision.as_dict())
            return decision

        kw_hits = [kw for kw in SENSITIVE_KEYWORDS if _word_in(text_lower, kw)]
        if kw_hits:
            decision = RouteDecision(
                route="local",
                reason="sensitive_keyword",
                matched=tuple(kw_hits),
            )
            log.info("route_decision", **decision.as_dict())
            return decision

        # 4. Memory/Recall keywords → local (Qwen knows the vault, hallucinates less)
        recall_hits = [kw for kw in MEMORY_RECALL_KEYWORDS if kw in text_lower]
        if recall_hits:
            decision = RouteDecision(
                route="local",
                reason="memory_recall",
                matched=tuple(recall_hits),
            )
            log.info("route_decision", **decision.as_dict())
            return decision

        # 5. Realtime/Stream keywords → gemini + enable_search=True
        realtime_hits = [kw for kw in REALTIME_SEARCH_KEYWORDS if _word_in(text_lower, kw)]
        if realtime_hits:
            decision = RouteDecision(
                route="gemini",
                reason="realtime_search",
                matched=tuple(realtime_hits),
                enable_search=True,
            )
            log.info("route_decision", **decision.as_dict())
            return decision

        # 6. Length heuristic — Gemini has larger context window
        total_len = len(text)
        if total_len > self._length_limit:
            decision = RouteDecision(
                route="gemini",
                reason="length_exceeds_local_context",
                matched=(str(total_len),),
            )
            log.info("route_decision", **decision.as_dict())
            return decision

        # 7. Default
        decision = RouteDecision(route="gemini", reason="default")
        log.info("route_decision", **decision.as_dict())
        return decision

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

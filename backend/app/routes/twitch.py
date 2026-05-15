"""twitch.py — Twitch-spezifische Backend-Routen.

Hauptfunktion: Chat → LocalLLM-Filter → LTM Brain-Ingest.

DONNA-92: Strukturierter Ingest mit VIEWER_FACT / STREAM_EVENT / COMMUNITY_LORE.
DONNA-65: Per-User Fakt-Extraktion (interests/traits/preferences) ab 3 Nachrichten.
"""
from __future__ import annotations

import asyncio
import json
import re
from collections import defaultdict
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel

from app.core.auth import require_admin
from app.core.logger import get_logger
from app.services.gemini_client import GeminiClient
from app.services.twitch_user_memory import TwitchUserMemory

router = APIRouter(prefix="/twitch", tags=["twitch"])
log = get_logger("route.twitch")

# Mindest-Nachrichten pro User für per-User LLM-Extraktion (DONNA-65)
_MIN_USER_MSGS_FOR_EXTRACTION = 3

# ──────────────────────────────────────────────────────────────────────────────
# LLM-Prompts
# ──────────────────────────────────────────────────────────────────────────────

_BRAIN_SYSTEM = (
    "Du bist ein Informations-Filter für den Twitch-Stream von abgemiked (Mike). "
    "Analysiere Chat-Nachrichten und klassifiziere relevante Informationen.\n\n"
    "Antwortformat — eine Zeile pro Fakt, NUR diese Kategorien:\n"
    "VIEWER_FACT: <username> | <Fakt über diesen Viewer> (nur wenn mehrfach bestätigt)\n"
    "STREAM_EVENT: <Was im Stream passiert ist — nur belegte Fakten>\n"
    "COMMUNITY_LORE: <Insider-Witz oder Running Gag der Community>\n"
    "NOTHING (wenn nichts Relevantes vorhanden)\n\n"
    "NICHT aufnehmen: Einzelmeinungen, Spam, Emote-Spam, private Infos über Dritte."
)

_USER_EXTRACT_SYSTEM = (
    "Du bist ein Fakten-Extraktor. Analysiere Twitch-Chat-Nachrichten eines Users "
    "und extrahiere dauerhaft relevante Eigenschaften als JSON.\n"
    'Antworte NUR mit: {"interests": [...], "traits": [...], "preferences": [...]}\n'
    "Leere Listen wenn nichts Relevantes. Keine Erklärungen."
)

# ──────────────────────────────────────────────────────────────────────────────
# Parse-Patterns für strukturierte LLM-Ausgabe
# ──────────────────────────────────────────────────────────────────────────────

_FACT_LINE_RE = re.compile(
    r"^(VIEWER_FACT|STREAM_EVENT|COMMUNITY_LORE):\s*(.+)$", re.IGNORECASE
)
_VIEWER_SPLIT_RE = re.compile(r"^([^\|]+)\|(.+)$")
_JSON_BLOCK_RE = re.compile(r"\{.*\}", re.DOTALL)  # greedy — erfasst vollständiges JSON inkl. Arrays

# Kategorie → LTM-Kategorie
_CATEGORY_MAP: dict[str, str] = {
    "VIEWER_FACT": "user_fact",
    "STREAM_EVENT": "user_fact",
    "COMMUNITY_LORE": "user_habit",
}


# ──────────────────────────────────────────────────────────────────────────────
# Pydantic-Modelle
# ──────────────────────────────────────────────────────────────────────────────

class ChatMessage(BaseModel):
    author: str
    content: str


class BrainIngestPayload(BaseModel):
    messages: list[ChatMessage]


# ──────────────────────────────────────────────────────────────────────────────
# Hilfsfunktionen
# ──────────────────────────────────────────────────────────────────────────────

def _parse_json_facts(raw: str) -> dict:
    """Extrahiert JSON-Dict aus LLM-Ausgabe — robust gegen Markdown-Wrapping."""
    raw = raw.strip()
    if raw.startswith("```"):
        raw = re.sub(r"^```[a-z]*\n?", "", raw, flags=re.IGNORECASE).rstrip("`").strip()
    m = _JSON_BLOCK_RE.search(raw)
    if m:
        raw = m.group(0)
    try:
        result = json.loads(raw)
        return result if isinstance(result, dict) else {}
    except (json.JSONDecodeError, ValueError):
        return {}


# ──────────────────────────────────────────────────────────────────────────────
# Route
# ──────────────────────────────────────────────────────────────────────────────

@router.post("/brain-ingest")
async def brain_ingest(
    body: BrainIngestPayload,
    request: Request,
    _admin: str = Depends(require_admin),
) -> dict:
    """Filtert akkumulierte Twitch-Chat-Nachrichten mit dem lokalen LLM.

    Stufe 1 (DONNA-92): Stream-Level strukturierter Ingest →
        VIEWER_FACT / STREAM_EVENT / COMMUNITY_LORE → stream_ltm.
    Stufe 2 (DONNA-65): Per-User Fakt-Extraktion ab _MIN_USER_MSGS_FOR_EXTRACTION →
        interests/traits/preferences → TwitchUserMemory.merge_facts().
    """
    local_llm = getattr(request.app.state, "local_llm", None)
    stream_ltm = getattr(request.app.state, "stream_ltm", None)
    gemini: GeminiClient = getattr(request.app.state, "gemini", None)
    # Fallback auf persönliches ltm falls stream_ltm noch nicht initialisiert
    ltm = stream_ltm or getattr(request.app.state, "ltm", None)

    if not local_llm or not ltm:
        return {"stored": False, "reason": "services_unavailable"}
    if not body.messages:
        return {"stored": False, "reason": "no_messages"}

    chat_text = "\n".join(f"{m.author}: {m.content}" for m in body.messages[-60:])
    today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    loop = asyncio.get_running_loop()

    # ── Stufe 1: Stream-Level Structured Ingest (DONNA-92) ────────────────────
    stored_count = 0
    stream_summary = ""

    try:
        raw = await local_llm.generate(
            system=_BRAIN_SYSTEM,
            prompt=f"Chat-Ausschnitt:\n{chat_text}",
            model="qwen2.5:7b",
        )
        stream_summary = raw.strip()
    except Exception as e:  # noqa: BLE001
        log.warning("twitch_brain_local_llm_error", error=str(e))
        # Gemini-Fallback wenn Ollama hängt (DONNA-102)
        if gemini is not None:
            try:
                raw = await loop.run_in_executor(
                    None,
                    lambda: gemini.generate(f"{_BRAIN_SYSTEM}\n\nChat-Ausschnitt:\n{chat_text}"),
                )
                stream_summary = raw.strip()
                log.info("twitch_brain_gemini_fallback_ok")
            except Exception as e2:  # noqa: BLE001
                log.error("twitch_brain_gemini_fallback_failed", error=str(e2))
                return {"stored": False, "reason": "llm_error"}
        else:
            return {"stored": False, "reason": "llm_error"}

    if stream_summary and not stream_summary.upper().startswith("NOTHING"):
        for line in stream_summary.splitlines():
            m = _FACT_LINE_RE.match(line.strip())
            if not m:
                continue
            fact_type = m.group(1).upper()
            fact_body = m.group(2).strip()

            if fact_type == "VIEWER_FACT":
                vm = _VIEWER_SPLIT_RE.match(fact_body)
                if vm:
                    viewer = vm.group(1).strip().lower()
                    fact = vm.group(2).strip()
                    tagged = f"[VIEWER_FACT:{viewer}] {fact}"
                else:
                    tagged = f"[VIEWER_FACT] {fact_body}"
            elif fact_type == "STREAM_EVENT":
                tagged = f"[STREAM_EVENT:{today}] {fact_body}"
            else:  # COMMUNITY_LORE
                tagged = f"[COMMUNITY_LORE] {fact_body}"

            category = _CATEGORY_MAP.get(fact_type, "user_fact")
            await loop.run_in_executor(
                None,
                lambda t=tagged, c=category: ltm.store_memory(
                    session_id="twitch_stream", content=t, category=c,
                ),
            )
            stored_count += 1

    # ── Stufe 2: Per-User Fakt-Extraktion (DONNA-65) ──────────────────────────
    user_msgs: dict[str, list[str]] = defaultdict(list)
    for msg in body.messages:
        if msg.author and msg.content.strip():
            user_msgs[msg.author.lower()].append(msg.content)

    user_memory = TwitchUserMemory()
    extracted_users = 0

    for user_login, msgs in user_msgs.items():
        if len(msgs) < _MIN_USER_MSGS_FOR_EXTRACTION:
            continue
        conversation = "\n".join(f"{user_login}: {m}" for m in msgs[:20])
        try:
            # Kein Gemini-Fallback hier: per-User-Profildaten (interests/traits/preferences)
            # sind PII — kein Cloud-Transfer ohne explizite Rechtsgrundlage (DONNA-102).
            facts_raw = await local_llm.generate(
                system=_USER_EXTRACT_SYSTEM,
                prompt=f"Nachrichten von {user_login}:\n{conversation}",
                model="qwen2.5:7b",
                options={"temperature": 0.2},
            )
            facts = _parse_json_facts(facts_raw)
            if facts:
                await loop.run_in_executor(
                    None,
                    lambda u=user_login, f=facts: user_memory.merge_facts(u, f),
                )
                extracted_users += 1
        except Exception as e:  # noqa: BLE001
            log.debug("twitch_user_extract_failed", user=user_login, error=str(e))

    log.info(
        "twitch_brain_ingest_done",
        stored_facts=stored_count,
        extracted_users=extracted_users,
    )
    return {
        "stored": stored_count > 0 or extracted_users > 0,
        "stored_facts": stored_count,
        "extracted_users": extracted_users,
        "summary": stream_summary[:200],
    }

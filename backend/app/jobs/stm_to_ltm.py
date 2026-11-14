"""STM→LTM Promotion — DONNA-14.

Täglich 01:00 UTC: analysiert STM-Konversationen der letzten 24h,
extrahiert dauerhafte Fakten/Präferenzen via Mistral und speichert in LTM.

Sicherheit:
  - Nur valide _VALID_CATEGORIES werden gespeichert.
  - Content-Length wird gekappt (max 500 Zeichen, Defense gegen Prompt-Injection
    via Konversation, die das LLM zu langen Outputs verleitet).
  - LLM-Output wird via json.loads validiert; alles andere wird verworfen.
"""
from __future__ import annotations

import json

from app.services.stm_service import STMService
from app.services.ltm_service import LTMService
from app.services.mistral_client import MistralClient
from app.core.logger import get_logger

log = get_logger("job.stm_to_ltm")

_MIN_MESSAGES = 8  # DONNA-110: auf 8 erhöht — mem0 übernimmt Kurzform-Sessions
_MAX_CONTENT_LEN = 500  # Sanitize: maximaler Fakt-Length
# A5 (#7): 'thread_state' — Session-Zusammenfassung "Stand/offene Punkte" (Cross-Chat-Kontinuität)
_VALID_CATEGORIES = {"user_preference", "user_fact", "user_habit", "media_seen", "thread_state"}
_PROMOTION_SESSION_ID = "_stm_to_ltm_job"
_MIN_THREAD_STATE_LEN = 10  # kürzere "Stand"-Summaries sind Rauschen → verwerfen

_ANALYSIS_PROMPT = """Analysiere das folgende Gespräch zwischen Mike und Donna.
Extrahiere NUR dauerhafte, langfristig relevante Fakten, Präferenzen oder Gewohnheiten über Mike.
Ignoriere kurzlebige Informationen (Stimmung, Tagesbefinden, einmalige Ereignisse, Termine, Wecker).

Gespräch:
{conversation}

Antworte NUR mit einer JSON-Liste, keine Erklärung, kein Markdown.
Format: [{{"content": "...", "category": "user_preference|user_fact|user_habit"}}]
Falls nichts dauerhaft Relevantes vorliegt: []
"""

# A5 (#7): separater "Stand/offene Punkte"-Zusammenfassungs-Prompt. Ziel ist genau EIN
# kompakter Satz-Block pro Session, damit Donna in einem NEUEN Chat den Faden wieder aufnehmen
# kann ("das Thema hatten wir schon, da waren wir stehengeblieben").
_THREAD_STATE_PROMPT = """Fasse den STAND dieses Gesprächs zwischen Mike und Donna in 1-3 Sätzen zusammen.
Nenne NUR das THEMA und die OFFENE AUFGABE — was noch zu tun, zu klären oder zu entscheiden ist.
Schreibe auf DEUTSCH, sachlich in der dritten Person über Mike. Keine Begrüßung, kein Markdown, keine Liste.
KEINE Gesundheits-, Finanz- oder Beziehungsdetails, keine Namen Dritter, keine Adressen/Nummern.
Wenn es kein fortlaufendes Thema mit einer offenen Aufgabe gibt, antworte NUR mit dem Wort: NONE

Gespräch:
{conversation}
"""


async def _summarize_thread_state(conversation: str, mistral: MistralClient) -> str | None:
    """Erzeugt eine "Stand/offene Punkte"-Zusammenfassung (A5 #7) — oder None.

    Minimal-invasiv: ein zusätzlicher LLM-Call pro qualifizierter Session. Fehler/Leeres/NONE
    → None (kein thread_state-Eintrag). Content wird wie Fakten auf _MAX_CONTENT_LEN gekappt.
    """
    try:
        # F1: der "Stand/offene Punkte" steht am ENDE des Gesprächs → letzte 3000 Zeichen
        # zusammenfassen (nicht den Anfang wie bei der Fakten-Extraktion).
        raw = await mistral.generate(
            system="", prompt=_THREAD_STATE_PROMPT.format(conversation=conversation[-3000:])
        )
    except Exception as e:  # noqa: BLE001
        log.warning("thread_state_summary_failed", error=str(e))
        return None
    text = (raw or "").strip()
    # Markdown-Fences ```...``` entfernen (analog Fakten-Extraktion)
    if text.startswith("```"):
        text = text.strip("`")
        if text.lower().startswith("json"):
            text = text[4:]
        text = text.strip()
    if not text or text.strip().upper().startswith("NONE") or len(text) < _MIN_THREAD_STATE_LEN:
        return None
    return text[:_MAX_CONTENT_LEN]


async def run_stm_to_ltm(
    stm: STMService,
    ltm: LTMService,
    mistral: MistralClient,
    dry_run: bool = False,
) -> dict:
    """Promoted wichtige STM-Inhalte in LTM.

    Returns:
      {"promoted": int, "sessions": int, "error"?: str}
    """
    if not mistral.ready():
        log.warning("stm_to_ltm_skipped", reason="mistral_not_ready")
        return {"promoted": 0, "sessions": 0}

    try:
        sessions = await stm.get_all_sessions(hours=24)
    except Exception as e:  # noqa: BLE001
        log.error("stm_to_ltm_get_sessions_failed", error=str(e))
        return {"promoted": 0, "sessions": 0, "error": str(e)}

    promoted = 0
    thread_states = 0
    for session_id, messages in sessions.items():
        if session_id == _PROMOTION_SESSION_ID:
            continue
        if len(messages) < _MIN_MESSAGES:
            continue
        conversation = "\n".join(
            f"{'Mike' if m['role'] == 'user' else 'Donna'}: {m['content']}"
            for m in messages
        )
        prompt = _ANALYSIS_PROMPT.format(conversation=conversation[:3000])
        try:
            result = await mistral.generate(system="", prompt=prompt)
            text = result.strip()
            # Schutz gegen Markdown-fences ```json ... ```
            if text.startswith("```"):
                text = text.strip("`")
                if text.lower().startswith("json"):
                    text = text[4:]
                text = text.strip()
            facts = json.loads(text)
            if not isinstance(facts, list):
                # F2: KEIN `continue` — das übersprang den thread_state-Block unten. Leere
                # Fakten-Liste, dann normal weiter → thread_state-Summary läuft trotzdem.
                facts = []
            for fact in facts:
                if not isinstance(fact, dict):
                    continue
                content = str(fact.get("content", "")).strip()[:_MAX_CONTENT_LEN]
                category = str(fact.get("category", "user_fact"))
                if not content or len(content) < 4:
                    continue
                if category not in _VALID_CATEGORIES:
                    continue
                if not dry_run:
                    try:
                        ltm.store_memory(
                            session_id=_PROMOTION_SESSION_ID,
                            content=content,
                            category=category,
                        )
                        promoted += 1
                        log.info(
                            "stm_to_ltm_promoted",
                            content=content[:80],
                            category=category,
                            source_session=session_id,
                        )
                    except Exception as e:  # noqa: BLE001
                        log.warning("stm_to_ltm_store_failed", error=str(e))
                else:
                    log.info("stm_to_ltm_dry_run", content=content[:80], category=category)
        except Exception as e:  # noqa: BLE001
            log.warning("stm_to_ltm_session_failed", session_id=session_id, error=str(e))

        # A5 (#7): zusätzlich EINE "Stand/offene Punkte"-Zusammenfassung je Session als
        # thread_state-Eintrag — ermöglicht Cross-Chat-Kontinuität beim nächsten Chatstart.
        # Eigener try-Block: scheitert die Summary, bleiben die oben promoteten Fakten erhalten.
        try:
            summary = await _summarize_thread_state(conversation, mistral)
            if summary:
                if not dry_run:
                    # F4: infer=False → Summary landet VERBATIM (kein Haiku-Rewrite, keine
                    # Zerlegung in Fantasie-Einträge). F3: Counter nur bei echter ID
                    # (store_memory gibt "" zurück bei PII-/Floskel-Filter oder Dedup).
                    _mid = ltm.store_memory(
                        session_id=_PROMOTION_SESSION_ID,
                        content=summary,
                        category="thread_state",
                        infer=False,
                    )
                    if _mid:
                        thread_states += 1
                        log.info(
                            "stm_to_ltm_thread_state",
                            content=summary[:80],
                            source_session=session_id,
                        )
                else:
                    log.info("stm_to_ltm_thread_state_dry_run", content=summary[:80])
        except Exception as e:  # noqa: BLE001
            log.warning("stm_to_ltm_thread_state_failed", session_id=session_id, error=str(e))

    log.info(
        "stm_to_ltm_done",
        promoted=promoted,
        thread_states=thread_states,
        sessions=len(sessions),
        dry_run=dry_run,
    )
    return {"promoted": promoted, "thread_states": thread_states, "sessions": len(sessions)}

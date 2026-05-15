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
_VALID_CATEGORIES = {"user_preference", "user_fact", "user_habit"}
_PROMOTION_SESSION_ID = "_stm_to_ltm_job"

_ANALYSIS_PROMPT = """Analysiere das folgende Gespräch zwischen Mike und Donna.
Extrahiere NUR dauerhafte, langfristig relevante Fakten, Präferenzen oder Gewohnheiten über Mike.
Ignoriere kurzlebige Informationen (Stimmung, Tagesbefinden, einmalige Ereignisse, Termine, Wecker).

Gespräch:
{conversation}

Antworte NUR mit einer JSON-Liste, keine Erklärung, kein Markdown.
Format: [{{"content": "...", "category": "user_preference|user_fact|user_habit"}}]
Falls nichts dauerhaft Relevantes vorliegt: []
"""


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
                continue
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

    log.info(
        "stm_to_ltm_done",
        promoted=promoted,
        sessions=len(sessions),
        dry_run=dry_run,
    )
    return {"promoted": promoted, "sessions": len(sessions)}

"""stream_ltm_to_personal.py — DONNA-102

Wöchentlich: destilliert relevante Insights aus stream_ltm → personal_ltm (Mike's Brain).
Filtert dauerhaft wertvolle Erkenntnisse über Community, Stammzuschauer und Stream-Kontext.
Nichts wird aus stream_ltm gelöscht — es dient als Rohdatenquelle.
"""
from __future__ import annotations

from app.services.ltm_service import LTMService
from app.services.local_llm_client import LocalLLMClient
from app.core.logger import get_logger

log = get_logger("job.stream_ltm_to_personal")

_MAX_ENTRIES = 200
_MAX_CONTENT_LEN = 500
_PROMOTION_SESSION_ID = "_stream_ltm_promotion"

_SYSTEM_PROMPT = (
    "Du bist ein Assistent von Mike (abgemiked), einem Twitch-Streamer. "
    "Analysiere gesammelte Stream- und Community-Fakten und destilliere daraus "
    "die wichtigsten dauerhaften Erkenntnisse für Mike's persönlichen Wissensschatz. "
    "Fokus: loyale Zuschauer, Community-Muster, wiederkehrende Themen, persönliche Fakten über Stammzuschauer. "
    "Antworte als JSON-Liste. Falls nichts relevant: []"
)


async def run_stream_ltm_to_personal(
    stream_ltm: LTMService,
    personal_ltm: LTMService,
    local_llm: LocalLLMClient,
    dry_run: bool = False,
) -> dict:
    """Destilliert stream_ltm-Insights → personal_ltm.

    Returns: {"promoted": int, "source_entries": int}
    """
    try:
        # Neueste stream_ltm-Einträge abrufen
        raw = stream_ltm.recall_relevant(
            query="Zuschauer Community Stream abgemiked",
            session_id=None,
            n_results=_MAX_ENTRIES,
        )
    except Exception as e:  # noqa: BLE001
        log.error("stream_ltm_to_personal_recall_failed", error=str(e))
        return {"promoted": 0, "source_entries": 0}

    if not raw:
        log.info("stream_ltm_to_personal_nothing", reason="no_entries")
        return {"promoted": 0, "source_entries": 0}

    entries_text = "\n".join(
        f"- {entry}" for entry in raw[:_MAX_ENTRIES]
        if entry and len(entry.strip()) > 5
    )

    prompt = (
        f"Gesammelte Twitch-Stream- und Community-Fakten:\n{entries_text}\n\n"
        f"Destilliere die wichtigsten dauerhaften Erkenntnisse für Mike's persönlichen Wissensschatz.\n"
        f"Nur was wirklich langfristig relevant ist (loyale Viewer, Stammzuschauer-Fakten, Community-Muster).\n"
        f'Format: [{{"content": "...", "category": "user_fact|user_preference|user_habit"}}]\n'
        f"Falls nichts dauerhaft relevant: []"
    )

    try:
        result = await local_llm.generate(system=_SYSTEM_PROMPT, prompt=prompt)
        text = result.strip()
        if text.startswith("```"):
            text = text.strip("`")
            if text.lower().startswith("json"):
                text = text[4:]
            text = text.strip()

        facts = __import__("json").loads(text)
        if not isinstance(facts, list):
            facts = []
    except Exception as e:  # noqa: BLE001
        log.warning("stream_ltm_to_personal_parse_failed", error=str(e))
        return {"promoted": 0, "source_entries": len(raw)}

    _valid_cats = {"user_fact", "user_preference", "user_habit", "stream_insight"}
    promoted = 0

    for fact in facts:
        if not isinstance(fact, dict):
            continue
        content = str(fact.get("content", "")).strip()[:_MAX_CONTENT_LEN]
        category = str(fact.get("category", "user_fact"))
        if not content or len(content) < 8:
            continue
        if category not in _valid_cats:
            category = "stream_insight"

        tagged = f"[Stream-Community] {content}"
        if not dry_run:
            personal_ltm.store_memory(
                session_id=_PROMOTION_SESSION_ID,
                content=tagged,
                category=category,
            )
            promoted += 1
        log.info(
            "stream_ltm_promoted_to_personal",
            content=content[:80],
            dry_run=dry_run,
        )

    log.info(
        "stream_ltm_to_personal_done",
        promoted=promoted,
        source_entries=len(raw),
        dry_run=dry_run,
    )
    return {"promoted": promoted, "source_entries": len(raw)}

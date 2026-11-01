"""stream_stm_to_ltm.py — DONNA-101

Täglich 03:00 UTC: konsolidiert alle Twitch-Chat-STM-Sessions → stream_ltm.
- Pro Viewer extrahiert das lokale LLM dauerhaft relevante Fakten
- Fakten werden mit [Viewer:name]-Prefix in stream_ltm gespeichert
- STM-Sessions werden nach Verarbeitung geleert
"""
from __future__ import annotations

import json

from app.services.stm_service import STMService
from app.services.ltm_service import LTMService
from app.services.local_llm_client import LocalLLMClient
from app.core.logger import get_logger

log = get_logger("job.stream_stm_to_ltm")

_MIN_MESSAGES = 3
_MAX_CONTENT_LEN = 400
_VALID_CATEGORIES = {"viewer_fact", "viewer_habit"}

_SYSTEM_PROMPT = (
    "Du bist ein Fakten-Extraktor für einen Twitch-Stream. "
    "Analysiere Chatnachrichten und extrahiere nur dauerhaft relevante Fakten über den Viewer. "
    "Ignoriere Stimmungen, einmalige Kommentare, Stream-Reaktionen, Emotes, Spam. "
    "Antworte ausschließlich mit einer JSON-Liste. Falls nichts dauerhaft Relevant: []"
)


async def run_stream_stm_to_ltm(
    stream_stm: STMService,
    stream_ltm: LTMService,
    local_llm: LocalLLMClient,
    dry_run: bool = False,
) -> dict:
    """Konsolidiert alle twitch_*-Sessions aus stream_stm → stream_ltm.

    Returns: {"promoted": int, "sessions": int, "cleared": int}
    """
    try:
        all_sessions = await stream_stm.get_all_sessions(hours=9999)
    except Exception as e:  # noqa: BLE001
        log.error("stream_stm_to_ltm_get_failed", error=str(e))
        return {"promoted": 0, "sessions": 0, "cleared": 0}

    twitch_sessions = {
        sid: msgs
        for sid, msgs in all_sessions.items()
        if sid.startswith("twitch_") and sid not in ("twitch__warmup",)
    }

    log.info("stream_stm_to_ltm_start", sessions=len(twitch_sessions), dry_run=dry_run)

    promoted = 0
    cleared = 0

    for session_id, messages in twitch_sessions.items():
        viewer_name = session_id[len("twitch_"):]

        user_msgs = [
            m["content"]
            for m in messages
            if m.get("role") == "user" and m.get("content", "").strip()
        ]

        if len(user_msgs) < _MIN_MESSAGES:
            if not dry_run:
                await stream_stm.delete_session(session_id)
                cleared += 1
            continue

        conversation = "\n".join(
            f"{viewer_name}: {msg}" for msg in user_msgs[:50]
        )

        prompt = (
            f"Chatnachrichten von Viewer '{viewer_name}' im Stream von abgemiked:\n"
            f"{conversation}\n\n"
            f"Extrahiere dauerhaft relevante Fakten über diesen Viewer.\n"
            f'Format: [{{"content": "...", "category": "viewer_fact|viewer_habit"}}]\n'
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

            facts = json.loads(text)
            if not isinstance(facts, list):
                facts = []

            for fact in facts:
                if not isinstance(fact, dict):
                    continue
                content = str(fact.get("content", "")).strip()[:_MAX_CONTENT_LEN]
                category = str(fact.get("category", "viewer_fact"))
                if not content or len(content) < 5:
                    continue
                if category not in _VALID_CATEGORIES:
                    category = "viewer_fact"

                tagged = f"[Viewer:{viewer_name}] {content}"
                if not dry_run:
                    stream_ltm.store_memory(
                        session_id=f"twitch_{viewer_name}",
                        content=tagged,
                        category=category,
                    )
                    promoted += 1
                log.info(
                    "stream_stm_promoted",
                    viewer=viewer_name,
                    content=content[:80],
                    dry_run=dry_run,
                )

        except Exception as e:  # noqa: BLE001
            log.warning("stream_stm_session_failed", session_id=session_id, error=str(e))

        if not dry_run:
            try:
                await stream_stm.delete_session(session_id)
                cleared += 1
            except Exception as e:  # noqa: BLE001
                log.warning("stream_stm_delete_failed", session_id=session_id, error=str(e))

    log.info(
        "stream_stm_to_ltm_done",
        promoted=promoted,
        sessions=len(twitch_sessions),
        cleared=cleared,
        dry_run=dry_run,
    )
    return {"promoted": promoted, "sessions": len(twitch_sessions), "cleared": cleared}

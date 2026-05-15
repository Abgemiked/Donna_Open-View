"""
graphiti_service.py — Knowledge-Graph-Memory für Donna (DONNA-111)

Speichert Konversations-Episoden als temporal-typed Graph in Neo4j via graphiti-core.
Ergänzt mem0/LTM (Vektor-Recall) um Beziehungs-Recall (Entitäten, Relationen, Zeitachse).

Feature-Flag DONNA_GRAPHITI=true aktiviert das Backend. Default: false.
LLM/Embedder: lokal via Ollama (OpenAI-kompatibler Endpoint /v1).
KEIN externer API-Call.
"""
from __future__ import annotations

import asyncio
import os
from datetime import datetime, timezone
from typing import Any

from app.core.logger import get_logger

log = get_logger("service.graphiti")

_DONNA_GRAPHITI_ENABLED: bool = os.environ.get("DONNA_GRAPHITI", "false").lower() in ("true", "1", "yes")
_NEO4J_URI: str = os.environ.get("NEO4J_URI", "bolt://neo4j:7687")
_NEO4J_USER: str = os.environ.get("NEO4J_USER", "neo4j")
_NEO4J_PASSWORD: str = os.environ.get("NEO4J_PASSWORD", "")
_OLLAMA_URL: str = os.environ.get("OLLAMA_URL", "http://ollama:11434")
_LLM_MODEL: str = os.environ.get("GRAPHITI_LLM_MODEL", "qwen2.5:7b")
_EMBED_MODEL: str = os.environ.get("GRAPHITI_EMBED_MODEL", "nomic-embed-text")
_GROUP_ID: str = "mike"


class GraphitiService:
    """Knowledge-Graph-Service für Donna. Async-Pattern (graphiti-core ist async).

    Lazy-Init unter asyncio.Lock — kein Crash beim API-Start wenn Neo4j noch hochfährt.
    _init_failed-Latch verhindert Retry-Storm bei dauerhaftem Verbindungsfehler.
    """

    def __init__(self) -> None:
        self._enabled = _DONNA_GRAPHITI_ENABLED
        self._client: Any = None
        self._episode_type: Any = None  # graphiti_core.nodes.EpisodeType — lazy
        self._init_lock = asyncio.Lock()
        self._init_done = False
        self._init_failed = False
        if not self._enabled:
            log.info("graphiti_service_disabled")
        else:
            log.info("graphiti_service_pending_init", neo4j_uri=_NEO4J_URI)

    async def _ensure_client(self) -> Any | None:
        if not self._enabled or self._init_failed:
            return None
        if not _NEO4J_PASSWORD:
            log.warning("neo4j_password_not_set")
            return None
        if self._init_done:
            return self._client
        async with self._init_lock:
            if self._init_done:
                return self._client
            try:
                from graphiti_core import Graphiti  # type: ignore[import]
                from graphiti_core.llm_client.openai_client import OpenAIClient  # type: ignore[import]
                from graphiti_core.llm_client.config import LLMConfig  # type: ignore[import]
                from graphiti_core.embedder.openai import OpenAIEmbedder, OpenAIEmbedderConfig  # type: ignore[import]

                # Ollama spricht OpenAI-kompatiblen Endpoint — kein externer API-Call
                ollama_v1 = f"{_OLLAMA_URL}/v1"
                llm_cfg = LLMConfig(
                    api_key="ollama",
                    model=_LLM_MODEL,
                    base_url=ollama_v1,
                )
                embed_cfg = OpenAIEmbedderConfig(
                    api_key="ollama",
                    embedding_model=_EMBED_MODEL,
                    base_url=ollama_v1,
                )
                self._client = Graphiti(
                    uri=_NEO4J_URI,
                    user=_NEO4J_USER,
                    password=_NEO4J_PASSWORD,
                    llm_client=OpenAIClient(config=llm_cfg),
                    embedder=OpenAIEmbedder(config=embed_cfg),
                )
                await self._client.build_indices_and_constraints()
                from graphiti_core.nodes import EpisodeType  # type: ignore[import]
                self._episode_type = EpisodeType
                self._init_done = True
                log.info("graphiti_client_ready", llm=_LLM_MODEL, embedder=_EMBED_MODEL, neo4j=_NEO4J_URI)
                return self._client
            except Exception as e:  # noqa: BLE001
                self._init_failed = True
                log.error("graphiti_init_failed", error=str(e))
                return None

    async def add_episode(
        self,
        session_id: str,
        user_message: str,
        assistant_message: str,
    ) -> bool:
        """Fügt einen Konversations-Turn als Episode in den Graph ein.

        Fire-and-forget — Caller nutzt asyncio.create_task. Blockiert SSE-Stream NICHT.
        """
        client = await self._ensure_client()
        if client is None:
            return False
        try:
            body = f"User: {user_message}\nDonna: {assistant_message}"
            await client.add_episode(
                name=f"chat_{session_id}",
                episode_body=body[:4000],
                source=self._episode_type.message if self._episode_type else "message",
                source_description="Donna Chat Session",
                group_id=_GROUP_ID,
                reference_time=datetime.now(timezone.utc),
            )
            log.info("graphiti_episode_added", session_id=session_id, body_len=len(body))
            return True
        except Exception as e:  # noqa: BLE001
            log.warning("graphiti_add_episode_failed", error=str(e), session_id=session_id)
            return False

    async def add_episode_raw(
        self,
        name: str,
        episode_body: str,
        source_description: str = "idea_capture",
        group_id: str = "ideas",
    ) -> bool:
        """Direkte Episode ohne User/Assistant-Split — für nicht-Chat-Inhalte (Ideen, Tasks etc.).

        DONNA-115: Ideen-Capture in separater Graph-Gruppe 'ideas'.
        Kein Breaking Change an add_episode() — neue Methode.
        """
        client = await self._ensure_client()
        if client is None:
            return False
        try:
            # EpisodeType.text für strukturierte Nicht-Chat-Inhalte
            source_type = self._episode_type.text if self._episode_type else "text"
            await client.add_episode(
                name=name,
                episode_body=episode_body[:4000],
                source=source_type,
                source_description=source_description,
                group_id=group_id,
                reference_time=datetime.now(timezone.utc),
            )
            log.info("graphiti_episode_raw_added", name=name, group_id=group_id, body_len=len(episode_body))
            return True
        except Exception as e:  # noqa: BLE001
            log.warning("graphiti_add_episode_raw_failed", error=str(e), name=name)
            return False

    async def search(self, query: str, top_k: int = 5, group_id: str | None = None) -> list[dict]:
        """Hybrid-Search über Edge-Facts im Knowledge Graph.

        group_id: optionaler Filter auf eine spezifische Gruppe (z.B. "ideas").
                  None = Default-Gruppe (mike/Chat-Episoden).
        """
        client = await self._ensure_client()
        if client is None:
            return []
        try:
            search_group = group_id if group_id is not None else _GROUP_ID
            results = await client.search(
                query=query,
                group_ids=[search_group],
                num_results=top_k,
            )
            return [
                {
                    "uuid": str(getattr(r, "uuid", "")),
                    "fact": getattr(r, "fact", "") or getattr(r, "name", ""),
                    "valid_at": str(getattr(r, "valid_at", "") or ""),
                    "invalid_at": str(getattr(r, "invalid_at", "") or ""),
                }
                for r in (results or [])
            ]
        except Exception as e:  # noqa: BLE001
            log.warning("graphiti_search_failed", error=str(e))
            return []

    async def neo4j_ready(self) -> bool:
        """Health-Probe — versucht Init falls noch nicht erfolgt."""
        if not self._enabled:
            return False
        client = await self._ensure_client()
        return client is not None and not self._init_failed

    def enabled(self) -> bool:
        return self._enabled

    async def close(self) -> None:
        if self._client is not None:
            try:
                await self._client.close()
            except Exception:  # noqa: BLE001
                pass

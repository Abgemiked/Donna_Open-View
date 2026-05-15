"""Health endpoint — public, no auth."""
from __future__ import annotations

import asyncio
import os
import time

from fastapi import APIRouter, Request

router = APIRouter(tags=["health"])

_DONNA_MEM0_ENABLED: bool = os.environ.get("DONNA_MEM0", "false").lower() in ("true", "1", "yes")
_DONNA_GRAPHITI_ENABLED: bool = os.environ.get("DONNA_GRAPHITI", "false").lower() in ("true", "1", "yes")


@router.get("/health")
async def health(request: Request) -> dict:
    """Liveness + readiness probe.

    Returns OK even when optional components (Gemini/Ollama) are missing — the
    system is designed to degrade gracefully.
    Shows qdrant_ready when DONNA_MEM0=true, chroma_ready always (for migration tracking).
    """
    vault = request.app.state.vault
    vector = request.app.state.vector
    gemini = request.app.state.gemini
    local_llm = getattr(request.app.state, "local_llm", None)
    ltm_service = getattr(request.app.state, "ltm", None)
    graphiti_service = getattr(request.app.state, "graphiti", None)

    vault_ready = vault.ready()
    chroma_ready = vector.ready()

    out: dict = {
        "status": "ok",
        "version": "0.2.0",
        "vault_mounted": vault_ready,
        "chroma_ready": chroma_ready,
        "gemini_key_present": gemini.ready(),
        "gemini_model": getattr(gemini, "_model_name", None) if gemini.ready() else None,
        "vault_notes_count": vault.count_notes() if vault_ready else 0,
    }

    # DONNA-110: mem0+Qdrant Status
    out["donna_mem0_enabled"] = _DONNA_MEM0_ENABLED
    if _DONNA_MEM0_ENABLED:
        _qdrant_ready = False
        if ltm_service is not None:
            try:
                _qdrant_ready = ltm_service.mem0_enabled()
            except Exception:  # noqa: BLE001
                _qdrant_ready = False
        out["qdrant_ready"] = _qdrant_ready
        # Chroma-Count für Migration-Tracking (beide anzeigen solange Migration läuft)
        if ltm_service is not None:
            try:
                out["chroma_ltm_count"] = ltm_service.chroma_count()
            except Exception:  # noqa: BLE001
                out["chroma_ltm_count"] = None

    if chroma_ready:
        try:
            out["chroma_collections"] = vector.count_all()
        except Exception:  # noqa: BLE001
            out["chroma_collections"] = None

    if local_llm is not None:
        try:
            out["local_llm_reachable"] = await asyncio.wait_for(
                local_llm.health(), timeout=3.0
            )
            out["local_llm_model"] = local_llm.model
        except (asyncio.TimeoutError, Exception):  # noqa: BLE001
            out["local_llm_reachable"] = False

    # DONNA-111: Graphiti / Neo4j Status
    out["donna_graphiti_enabled"] = _DONNA_GRAPHITI_ENABLED
    if graphiti_service is not None:
        try:
            out["neo4j_ready"] = await asyncio.wait_for(
                graphiti_service.neo4j_ready(), timeout=3.0
            )
        except (asyncio.TimeoutError, Exception):  # noqa: BLE001
            out["neo4j_ready"] = False
    else:
        out["neo4j_ready"] = False

    return out


@router.get("/health/ping")
async def health_ping() -> dict:
    """Lightweight connectivity check — kein Auth, kein App-State.

    Wird vom VoiceInput-Overlay genutzt um vor dem Chat-Request zu prüfen
    ob das Backend erreichbar ist. Antwortet ohne DB- oder LLM-Zugriff.
    """
    return {"status": "ok", "timestamp": time.time()}

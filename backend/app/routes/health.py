"""Health endpoint — public, no auth."""
from __future__ import annotations

import asyncio
import os
import time

import httpx
from fastapi import APIRouter, Request

router = APIRouter(tags=["health"])

_DONNA_MEM0_ENABLED: bool = os.environ.get("DONNA_MEM0", "false").lower() in ("true", "1", "yes")
_DONNA_GRAPHITI_ENABLED: bool = os.environ.get("DONNA_GRAPHITI", "false").lower() in ("true", "1", "yes")

def _env_float(name: str, default: float) -> float:
    """Liest eine float-ENV robust: ungültiger Wert → Default statt Import-Crash."""
    try:
        raw = (os.environ.get(name) or "").strip()
        return float(raw) if raw else default
    except (TypeError, ValueError):
        return default


_QDRANT_URL: str = os.environ.get("QDRANT_URL", "http://qdrant:6333")
# FIX 1: harter Timeout für den Qdrant-Reachability-Probe im Health-Check.
_QDRANT_HEALTH_TIMEOUT_S: float = _env_float("QDRANT_HEALTH_TIMEOUT_S", 1.5)


async def _check_qdrant(url: str, timeout: float) -> bool:
    """Bounded Qdrant-Erreichbarkeitsprobe für /health.

    Ruft Qdrants /healthz mit hartem Timeout. Jede Antwort < 500 zählt als
    erreichbar (Server lebt); Timeout/Connection-Fehler → nicht erreichbar.
    Blockiert NIE länger als `timeout` → /health bleibt auch bei pausiertem
    Qdrant antwortfähig.
    """
    try:
        async with httpx.AsyncClient(timeout=timeout) as client:
            resp = await client.get(f"{url.rstrip('/')}/healthz")
            return resp.status_code < 500
    except Exception:  # noqa: BLE001
        return False


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
        # mem0-Client-Init-Flag (fast, blockiert nie). mem0_ready() statt
        # mem0_enabled(): löst NIE einen (blockierenden, ggf. 0.64-GB-Download)
        # mem0-Init im Event-Loop aus. Getrennt von echter Qdrant-Erreichbarkeit.
        _mem0_initialized = False
        if ltm_service is not None:
            try:
                _mem0_initialized = ltm_service.mem0_ready()
            except Exception:  # noqa: BLE001
                _mem0_initialized = False
        out["mem0_client_initialized"] = _mem0_initialized

        # FIX 1: echte Qdrant-Erreichbarkeit mit HARTEM Timeout. Ein pausierter/
        # langsamer Qdrant darf /health nicht blockieren — der Probe läuft async
        # (httpx) und zusätzlich unter asyncio.wait_for als Sicherheitsnetz.
        # Qdrant-Status ist ein Teilfeld: der Core bleibt "ok"/200 solange
        # API + Chroma laufen; ein Qdrant-Ausfall meldet sich als status="degraded".
        try:
            _qdrant_ready = await asyncio.wait_for(
                _check_qdrant(_QDRANT_URL, _QDRANT_HEALTH_TIMEOUT_S),
                timeout=_QDRANT_HEALTH_TIMEOUT_S + 0.5,
            )
        except (asyncio.TimeoutError, Exception):  # noqa: BLE001
            _qdrant_ready = False
        out["qdrant_ready"] = _qdrant_ready
        if not _qdrant_ready:
            out["status"] = "degraded"
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

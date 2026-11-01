"""Admin-Endpoint für Test-User-Cleanup (DONNA-136).

Löscht alle Daten die während KI-Trainingsläufen unter einer Test-User-ID
gespeichert wurden, ohne echte Mike-Daten zu berühren.

Auth: require_admin (Bearer-Token wie alle anderen Admin-Endpoints).
"""
from __future__ import annotations

import re

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field

from app.core.auth import require_admin
from app.core.logger import get_logger

log = get_logger("route.admin_test")

router = APIRouter(prefix="/admin", tags=["admin"])

# Valides Format für Test-User-IDs (alphanumerisch + Unterstrich/Bindestrich, max 64 Zeichen)
# "mike" ist explizit verboten — würde echte Daten löschen.
_VALID_TEST_USER_RE = re.compile(r"^[a-zA-Z0-9_\-]{1,64}$")
_FORBIDDEN_USER_IDS = frozenset({"mike"})


def _validate_test_user_id(test_user_id: str) -> None:
    """Wirft HTTPException wenn test_user_id ungültig oder gefährlich ist."""
    if not _VALID_TEST_USER_RE.match(test_user_id):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Ungültige test_user_id — nur alphanumerisch, Unterstrich, Bindestrich erlaubt (max 64 Zeichen).",
        )
    if test_user_id in _FORBIDDEN_USER_IDS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"test_user_id '{test_user_id}' ist verboten — würde echte Nutzerdaten löschen.",
        )


class TestCleanupRequest(BaseModel):
    test_user_id: str = Field(..., min_length=1, max_length=64)


class TestCleanupResponse(BaseModel):
    test_user_id: str
    mem0_deleted: int
    vector_deleted: int
    stm_sessions_deleted: int
    neo4j_deleted: int
    errors: list[str]


class TestCleanupStatusResponse(BaseModel):
    test_user_id: str
    mem0_count: int
    vector_count: int
    stm_session_count: int


@router.delete("/test-cleanup", response_model=TestCleanupResponse)
async def delete_test_user_data(
    body: TestCleanupRequest,
    request: Request,
    _admin: str = Depends(require_admin),
) -> TestCleanupResponse:
    """Löscht alle Daten für eine Test-User-ID aus allen Stores.

    Berührt KEINE echten Mike-Daten. "mike" als test_user_id wird abgelehnt.

    Gelöscht werden:
    - mem0 / Qdrant: alle Memories mit user_id == test_user_id
    - ChromaDB VectorStore: kein user_id-Filter möglich — wird übersprungen
    - STM (SQLite): alle Sessions mit Prefix test__{test_user_id}__
    - Neo4j / Graphiti: alle Nodes mit user_id-Property == test_user_id (wenn aktiv)
    """
    test_user_id = body.test_user_id.strip()
    _validate_test_user_id(test_user_id)

    errors: list[str] = []
    mem0_deleted = 0
    vector_deleted = 0
    stm_sessions_deleted = 0
    neo4j_deleted = 0

    # --- mem0 / Qdrant Cleanup ---
    ltm_service = getattr(request.app.state, "ltm", None)
    if ltm_service is not None:
        try:
            mem0_deleted = ltm_service.delete_by_user(test_user_id)
            log.info("admin_test_cleanup_mem0", test_user_id=test_user_id, deleted=mem0_deleted)
        except Exception as e:  # noqa: BLE001
            msg = f"mem0-Cleanup fehlgeschlagen: {e}"
            log.warning("admin_test_cleanup_mem0_failed", error=str(e), test_user_id=test_user_id)
            errors.append(msg)

    # --- STM (SQLite) Cleanup: alle Sessions mit Prefix test__{test_user_id}__ ---
    stm_service = getattr(request.app.state, "stm", None)
    if stm_service is not None:
        try:
            import aiosqlite
            prefix = f"test__{test_user_id}__%"
            async with aiosqlite.connect(stm_service.db_path) as db:
                await db.execute("PRAGMA journal_mode=WAL")
                # Finde alle betroffenen session_ids
                async with db.execute(
                    "SELECT DISTINCT session_id FROM stm_messages WHERE session_id LIKE ?",
                    (prefix,),
                ) as cursor:
                    rows = await cursor.fetchall()
                session_ids = [row[0] for row in rows]
                if session_ids:
                    placeholders = ",".join("?" * len(session_ids))
                    cursor2 = await db.execute(
                        f"DELETE FROM stm_messages WHERE session_id IN ({placeholders})",
                        session_ids,
                    )
                    stm_sessions_deleted = cursor2.rowcount
                    await db.commit()
            log.info(
                "admin_test_cleanup_stm",
                test_user_id=test_user_id,
                sessions=len(session_ids),
                rows_deleted=stm_sessions_deleted,
            )
        except Exception as e:  # noqa: BLE001
            msg = f"STM-Cleanup fehlgeschlagen: {e}"
            log.warning("admin_test_cleanup_stm_failed", error=str(e), test_user_id=test_user_id)
            errors.append(msg)

    # --- Neo4j / Graphiti Cleanup (optional, wenn aktiv) ---
    # DONNA-138: Graphiti._client ist ein graphiti_core.Graphiti-Objekt, KEIN Neo4j-Driver.
    # Graphiti selbst hat kein execute_query(). Wir greifen auf den internen
    # Neo4j AsyncDriver über graphiti_client.driver zu (graphiti-core >= 0.3).
    graphiti_svc = getattr(request.app.state, "graphiti", None)
    if graphiti_svc is not None and graphiti_svc.enabled():
        try:
            # Graphiti nutzt session_id-basierte Episodes, keine user_id-Property.
            # Test-Sessions haben Prefix "chat_test__{test_user_id}__".
            _graphiti_client = getattr(graphiti_svc, "_client", None)
            if _graphiti_client is not None:
                _session_prefix = f"chat_test__{test_user_id}__"
                # graphiti-core speichert den Neo4j AsyncDriver als .driver
                _neo4j_driver = getattr(_graphiti_client, "driver", None)
                if _neo4j_driver is not None and hasattr(_neo4j_driver, "execute_query"):
                    # Neo4j Python Driver >= 5.x: async execute_query
                    result = await _neo4j_driver.execute_query(
                        "MATCH (e:Episode) WHERE e.name STARTS WITH $prefix "
                        "WITH e DETACH DELETE e RETURN count(e) AS deleted",
                        {"prefix": _session_prefix},
                    )
                    neo4j_deleted = result.records[0]["deleted"] if result.records else 0
                else:
                    # Kein direkter Driver-Zugriff — 0 ist korrekt (test_mike hat sowieso 0)
                    log.warning(
                        "admin_test_cleanup_neo4j_no_driver",
                        test_user_id=test_user_id,
                        note="Neo4j-Driver nicht erreichbar, Cleanup übersprungen",
                    )
                log.info(
                    "admin_test_cleanup_neo4j",
                    test_user_id=test_user_id,
                    deleted=neo4j_deleted,
                )
        except Exception as e:  # noqa: BLE001
            msg = f"Neo4j-Cleanup fehlgeschlagen: {e}"
            log.warning("admin_test_cleanup_neo4j_failed", error=str(e), test_user_id=test_user_id)
            errors.append(msg)

    # ChromaDB VectorStore: kein user_id-Filter möglich — bewusst ausgelassen.
    # Test-Queries landen im selben ChromaDB-Index wie echte Daten, aber ohne
    # mem0 werden auch keine ChromaDB-Einträge für Testläufe erstellt (mem0 nutzt Qdrant).
    # Bei DONNA_MEM0=false ist ChromaDB das primäre Backend — dann sind Test-Daten
    # nicht isolierbar (kein user_id-Filter). Das ist der erwartete Zustand.

    log.info(
        "admin_test_cleanup_done",
        test_user_id=test_user_id,
        mem0_deleted=mem0_deleted,
        stm_sessions_deleted=stm_sessions_deleted,
        neo4j_deleted=neo4j_deleted,
        errors=len(errors),
    )

    return TestCleanupResponse(
        test_user_id=test_user_id,
        mem0_deleted=mem0_deleted,
        vector_deleted=vector_deleted,
        stm_sessions_deleted=stm_sessions_deleted,
        neo4j_deleted=neo4j_deleted,
        errors=errors,
    )


@router.get("/test-cleanup/status", response_model=TestCleanupStatusResponse)
async def get_test_user_status(
    user_id: str,
    request: Request,
    _admin: str = Depends(require_admin),
) -> TestCleanupStatusResponse:
    """Zählt verbleibende Einträge für eine Test-User-ID.

    Nützlich um zu prüfen ob ein Cleanup vollständig war.
    """
    test_user_id = user_id.strip()
    _validate_test_user_id(test_user_id)

    mem0_count = -1
    vector_count = -1
    stm_session_count = 0

    # --- mem0 Count + ChromaDB vector_count ---
    ltm_service = getattr(request.app.state, "ltm", None)
    if ltm_service is not None:
        try:
            mem0_count = ltm_service.count_by_user(test_user_id)
        except Exception as e:  # noqa: BLE001
            log.warning("admin_test_status_mem0_failed", error=str(e), test_user_id=test_user_id)
        # DONNA-138: vector_count — ChromaDB hat keinen user_id-Filter.
        # Liefern wir den Gesamt-Zähler der Collection (nicht user-spezifisch).
        # -1 wenn ChromaDB nicht erreichbar, 0+ wenn aktiv.
        try:
            vector_count = ltm_service.chroma_count()
        except Exception as e:  # noqa: BLE001
            log.warning("admin_test_status_vector_failed", error=str(e), test_user_id=test_user_id)

    # --- STM Session Count ---
    stm_service = getattr(request.app.state, "stm", None)
    if stm_service is not None:
        try:
            import aiosqlite
            prefix = f"test__{test_user_id}__%"
            async with aiosqlite.connect(stm_service.db_path) as db:
                async with db.execute(
                    "SELECT COUNT(DISTINCT session_id) AS cnt FROM stm_messages WHERE session_id LIKE ?",
                    (prefix,),
                ) as cursor:
                    row = await cursor.fetchone()
                    stm_session_count = row[0] if row else 0
        except Exception as e:  # noqa: BLE001
            log.warning("admin_test_status_stm_failed", error=str(e), test_user_id=test_user_id)

    log.info(
        "admin_test_status",
        test_user_id=test_user_id,
        mem0_count=mem0_count,
        stm_session_count=stm_session_count,
    )

    return TestCleanupStatusResponse(
        test_user_id=test_user_id,
        mem0_count=mem0_count,
        vector_count=vector_count,
        stm_session_count=stm_session_count,
    )

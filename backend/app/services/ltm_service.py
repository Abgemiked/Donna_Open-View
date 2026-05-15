"""
ltm_service.py — LTM Service für Donna

Speichert Nutzer-Präferenzen, Fakten und Gewohnheiten dauerhaft.

Feature-Flag DONNA_MEM0=true: mem0 + Qdrant (semantisches Memory-Management)
Feature-Flag DONNA_MEM0=false (Default): ChromaDB embedded (bisheriges Verhalten)

PII-Filter und Floskel-Filter sind unabhängig vom Backend immer aktiv.
"""
from __future__ import annotations

import os
import re
import uuid
from pathlib import Path
from typing import TYPE_CHECKING

import chromadb
from chromadb.config import Settings

from app.core.logger import get_logger

if TYPE_CHECKING:
    pass

log = get_logger("service.ltm")

# Feature-Flag: DONNA_MEM0=true aktiviert mem0 + Qdrant
_DONNA_MEM0_ENABLED: bool = os.environ.get("DONNA_MEM0", "false").lower() in ("true", "1", "yes")
_MEM0_USER_ID: str = "mike"
_QDRANT_URL: str = os.environ.get("QDRANT_URL", "http://qdrant:6333")

# Gültige Kategorien
# DONNA-115: 'idea' als Kategorie für strukturierte Ideen-Erfassung ergänzt
_VALID_CATEGORIES = {"user_preference", "user_fact", "user_habit", "idea"}
_COLLECTION_NAME = "ltm_memories"
_STREAM_COLLECTION_NAME = "stream_memories"
# Duplikat-Schwelle: ChromaDB nutzt L2-Distanz → < 0.1 ≈ sehr ähnlich
_DEDUP_THRESHOLD = 0.1

# PII-Patterns die niemals im LTM landen dürfen (DSGVO Art. 5 Abs. 1c — Datensparsamkeit)
_PII_PATTERNS: list[re.Pattern[str]] = [
    re.compile(r'(?i)\b[A-Z]{2}\d{2}[A-Z0-9]{4}\d{7,19}\b'),                # IBAN (case-insensitive)
    re.compile(r'\b(?:\d{4}[\s\-]){3}\d{4}\b|\b\d{16}\b'),                   # Kreditkarte (formatiert + unformatiert)
    re.compile(r'\b[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}\b'),    # E-Mail
    re.compile(r'(?i)(passwort|password|pin|geheimnis)\s*[:=]\s*\S+'),        # Passwort-Hint
]


def _contains_pii(content: str) -> bool:
    return any(p.search(content) for p in _PII_PATTERNS)


# Floskeln und Füllwörter die nie als eigenständige Memory gespeichert werden sollen.
# Ein Dokument das NUR aus diesen Wörtern besteht wird verworfen.
# Ziel: "hallo" oder "ok danke" landen nicht im Embedding-Index.
_FILLER_WORDS_RE = re.compile(
    r'^[\s,;.!?]*('
    r'hallo|hi|hey|moin|tschüss|tschuss|bye|ciao|servus|'
    r'danke|bitte|ok|okay|ja|nein|ne|jo|nö|jo|yep|nope|'
    r'super|cool|nice|gut|top|prima|klasse|toll|genial|'
    r'wie geht|wie gehts|geht mir gut|alles gut|alles klar|'
    r'verstanden|alles klar|passt|perfekt|'
    r'warte|moment|kurz|gleich|'
    r'ähm|äh|hmm|hm|ah|oh|ach'
    r')[\s,;.!?]*$',
    re.IGNORECASE,
)


def _create_mem0_client():
    """Erstellt mem0-Memory-Client (Lazy Init). Gibt None bei Fehler zurück.

    LLM: Ollama qwen2.5:7b (lokal, kein externer API-Call).
    Embedder: Ollama nomic-embed-text (lokal, 768 Dim).
    """
    try:
        from mem0 import Memory  # type: ignore[import]
        ollama_url = os.environ.get("OLLAMA_URL", "http://ollama:11434")
        config = {
            "llm": {
                "provider": "ollama",
                "config": {
                    "model": "qwen2.5:7b",
                    "ollama_base_url": ollama_url,
                },
            },
            "embedder": {
                "provider": "ollama",
                "config": {
                    "model": "nomic-embed-text",
                    "ollama_base_url": ollama_url,
                },
            },
            "vector_store": {
                "provider": "qdrant",
                "config": {
                    "url": _QDRANT_URL,
                    "collection_name": "brain_ltm",
                    "embedding_model_dims": 768,  # nomic-embed-text via Ollama
                },
            },
        }
        client = Memory.from_config(config)
        log.info("mem0_client_ready", qdrant_url=_QDRANT_URL, llm="ollama/qwen2.5:7b")
        return client
    except Exception as e:  # noqa: BLE001
        log.error("mem0_init_failed", error=str(e))
        return None


class LTMService:
    """LTM-Service für Donna.

    Bei DONNA_MEM0=true: mem0 + Qdrant als Backend.
    Bei DONNA_MEM0=false (Default): ChromaDB embedded.

    Kann als persönlicher LTM (db_path=data/ltm, collection=ltm_memories)
    oder als Stream-LTM (db_path=data/stream_ltm, collection=stream_memories)
    instanziiert werden.
    """

    def __init__(
        self,
        db_path: str = "/data/chroma/ltm",
        collection_name: str = _COLLECTION_NAME,
    ) -> None:
        self._db_path = db_path
        self._collection_name = collection_name
        self._mem0_enabled = _DONNA_MEM0_ENABLED
        self._mem0_client = None  # Lazy init

        if not self._mem0_enabled:
            # ChromaDB-Pfad (bisheriges Verhalten)
            Path(db_path).mkdir(parents=True, exist_ok=True)
            self._client = chromadb.PersistentClient(
                path=db_path,
                settings=Settings(anonymized_telemetry=False),
            )
            self._col = self._client.get_or_create_collection(
                name=collection_name,
                metadata={"hnsw:space": "l2"},
            )
            log.info("ltm_service_ready", backend="chromadb", db_path=db_path, collection=collection_name, count=self._col.count())
        else:
            # mem0 + Qdrant — ChromaDB-Fallback bleibt initialisiert für Migration
            Path(db_path).mkdir(parents=True, exist_ok=True)
            self._client = chromadb.PersistentClient(
                path=db_path,
                settings=Settings(anonymized_telemetry=False),
            )
            self._col = self._client.get_or_create_collection(
                name=collection_name,
                metadata={"hnsw:space": "l2"},
            )
            # mem0 lazy init — wird beim ersten Aufruf erstellt
            log.info("ltm_service_ready", backend="mem0+qdrant", qdrant_url=_QDRANT_URL)

    def _get_mem0(self):
        """Lazy-init mem0-Client. Gibt None zurück wenn nicht verfügbar."""
        if not self._mem0_enabled:
            return None
        if self._mem0_client is None:
            self._mem0_client = _create_mem0_client()
        return self._mem0_client

    def store_memory(self, session_id: str, content: str, category: str, user_id: str | None = None) -> str:
        """
        Speichert eine Memory. Gibt die ID zurück.
        PII-Filter und Floskel-Filter sind immer aktiv (unabhängig vom Backend).
        mem0-Pfad: memory.add() mit Qdrant als Vector Store.
        ChromaDB-Pfad: Deduplizierung + direktes Upsert.

        user_id: Optional — überschreibt _MEM0_USER_ID (für Test-Isolation via X-Test-User-Id).
        """
        effective_user_id = user_id if user_id else _MEM0_USER_ID

        if category not in _VALID_CATEGORIES:
            category = "user_fact"

        # PII-Filter: sensible Muster blockieren (DONNA-83) — immer aktiv
        if _contains_pii(content):
            _hit = next((p.pattern for p in _PII_PATTERNS if p.search(content)), "unknown")
            log.warning("ltm_pii_blocked", pattern=_hit, content_len=len(content))
            return ""

        # Floskel-Filter: Memory nur speichern wenn inhaltlich relevant — immer aktiv
        if _FILLER_WORDS_RE.match(content.strip()):
            log.debug("ltm_filler_skip", content_preview=content[:40])
            return ""

        # mem0-Pfad
        mem0 = self._get_mem0()
        if mem0 is not None:
            try:
                result = mem0.add(
                    [{"role": "user", "content": content}],
                    user_id=effective_user_id,
                    metadata={"category": category, "session_id": session_id},
                )
                # mem0 v2: {'results': [{'id': ..., 'memory': ..., ...}]}
                items = result.get("results", []) if isinstance(result, dict) else (result if isinstance(result, list) else [])
                if items:
                    mem_id = items[0].get("id", str(uuid.uuid4()))
                else:
                    mem_id = str(uuid.uuid4())
                log.info("ltm_stored_mem0", mem_id=mem_id, category=category)
                return str(mem_id)
            except Exception as e:  # noqa: BLE001
                log.warning("ltm_mem0_store_failed_fallback_chroma", error=str(e))
                # Graceful Fallback auf ChromaDB

        # ChromaDB-Pfad (Default oder Fallback)
        # Deduplizierung
        if self._col.count() > 0:
            try:
                res = self._col.query(
                    query_texts=[content],
                    n_results=1,
                    where={"category": category},
                )
                distances = res.get("distances", [[]])[0]
                ids = res.get("ids", [[]])[0]
                if distances and distances[0] < _DEDUP_THRESHOLD:
                    log.info("ltm_dedup_skip", existing_id=ids[0], distance=distances[0])
                    return ids[0]
            except Exception as e:  # noqa: BLE001
                log.warning("ltm_dedup_failed", error=str(e))

        mem_id = str(uuid.uuid4())
        self._col.add(
            documents=[content],
            metadatas=[{"category": category, "session_id": session_id}],
            ids=[mem_id],
        )
        log.info("ltm_stored", mem_id=mem_id, category=category)
        return mem_id

    def recall_relevant(
        self,
        query: str,
        top_k: int = 5,
        category: str | None = None,
        min_score: float | None = None,
        user_id: str | None = None,
    ) -> list[dict]:
        """Semantic Recall: gibt relevante Memories zurück.

        mem0-Pfad: memory.search() mit Qdrant.
        ChromaDB-Pfad: direktes Query (bisheriges Verhalten).

        min_score: Optional[float] — wenn gesetzt, filtert Treffer mit score < min_score raus.
            Empfohlen: 0.55. None = kein Filter (Default).
        user_id: Optional — überschreibt _MEM0_USER_ID (für Test-Isolation via X-Test-User-Id).
        """
        effective_user_id = user_id if user_id else _MEM0_USER_ID
        # mem0-Pfad
        mem0 = self._get_mem0()
        if mem0 is not None:
            try:
                results_raw = mem0.search(
                    query=query,
                    filters={"user_id": effective_user_id},
                    limit=top_k,
                )
                # mem0 v2: {'results': [{'id', 'memory', 'score', 'metadata'}]}
                items_raw = results_raw.get("results", []) if isinstance(results_raw, dict) else results_raw
                results = []
                for item in items_raw:
                    score = float(item.get("score", 0.0))
                    if min_score is not None and score < min_score:
                        continue
                    meta = item.get("metadata") or {}
                    content = item.get("memory", "")
                    if category and meta.get("category") != category:
                        continue
                    results.append({
                        "id": str(item.get("id", "")),
                        "content": content,
                        "category": meta.get("category", "user_fact"),
                        "session_id": meta.get("session_id", ""),
                        "score": round(score, 4),
                    })
                return results
            except Exception as e:  # noqa: BLE001
                log.warning("ltm_mem0_recall_failed_fallback_chroma", error=str(e))
                # Graceful Fallback auf ChromaDB

        # ChromaDB-Pfad (Default oder Fallback)
        if self._col.count() == 0:
            return []
        try:
            kwargs: dict = {
                "query_texts": [query],
                "n_results": min(top_k, self._col.count()),
            }
            if category:
                kwargs["where"] = {"category": category}
            res = self._col.query(**kwargs)
            results = []
            for doc, meta, mem_id, dist in zip(
                res["documents"][0],
                res["metadatas"][0],
                res["ids"][0],
                res["distances"][0],
            ):
                # L2-Distanz → Kosinus-Ähnlichkeit für normierte MiniLM-Embeddings:
                # cosine_sim = 1 - L2² / 2  (gilt für Unit-Vektoren)
                score = round(1.0 - (dist ** 2) / 2, 4)
                if min_score is not None and score < min_score:
                    continue
                results.append({
                    "id": mem_id,
                    "content": doc,
                    "category": meta.get("category", "user_fact"),
                    "session_id": meta.get("session_id", ""),
                    "score": score,
                })
            return results
        except Exception as e:  # noqa: BLE001
            log.warning("ltm_recall_failed", error=str(e))
            return []

    def get_all(self, user_id: str | None = None) -> list[dict]:
        """Alle gespeicherten Memories zurückgeben.

        user_id: Optional — überschreibt _MEM0_USER_ID (für Test-Isolation via X-Test-User-Id).
        """
        effective_user_id = user_id if user_id else _MEM0_USER_ID
        # mem0-Pfad
        mem0 = self._get_mem0()
        if mem0 is not None:
            try:
                results_raw = mem0.get_all(filters={"user_id": effective_user_id})
                # mem0 v2: {'results': [...]}
                items_raw = results_raw.get("results", []) if isinstance(results_raw, dict) else results_raw
                return [
                    {
                        "id": str(item.get("id", "")),
                        "content": item.get("memory", ""),
                        "category": (item.get("metadata") or {}).get("category", "user_fact"),
                        "session_id": (item.get("metadata") or {}).get("session_id", ""),
                    }
                    for item in items_raw
                ]
            except Exception as e:  # noqa: BLE001
                log.warning("ltm_mem0_get_all_failed_fallback_chroma", error=str(e))

        # ChromaDB-Pfad (Default oder Fallback)
        if self._col.count() == 0:
            return []
        res = self._col.get()
        return [
            {
                "id": mem_id,
                "content": doc,
                "category": meta.get("category", "user_fact"),
                "session_id": meta.get("session_id", ""),
            }
            for mem_id, doc, meta in zip(res["ids"], res["documents"], res["metadatas"])
        ]

    def delete_memory(self, memory_id: str) -> bool:
        """Löscht eine Memory. Gibt True zurück wenn gefunden + gelöscht."""
        # mem0-Pfad
        mem0 = self._get_mem0()
        if mem0 is not None:
            try:
                mem0.delete(memory_id=memory_id)
                log.info("ltm_deleted_mem0", mem_id=memory_id)
                return True
            except Exception as e:  # noqa: BLE001
                log.warning("ltm_mem0_delete_failed_fallback_chroma", error=str(e))

        # ChromaDB-Pfad (Default oder Fallback)
        try:
            existing = self._col.get(ids=[memory_id])
            if not existing["ids"]:
                return False
            self._col.delete(ids=[memory_id])
            log.info("ltm_deleted", mem_id=memory_id)
            return True
        except Exception as e:  # noqa: BLE001
            log.warning("ltm_delete_failed", error=str(e))
            return False

    def delete_by_user(self, user_id: str) -> int:
        """Löscht alle Memories für eine user_id. Gibt Anzahl gelöschter Einträge zurück.

        Nur sinnvoll wenn DONNA_MEM0=true (mem0+Qdrant).
        Bei ChromaDB-Only: löscht alle Einträge (kein user_id-Filter in ChromaDB).
        Gedacht für Test-Cleanup via Admin-Endpoint.
        """
        deleted = 0
        mem0 = self._get_mem0()
        if mem0 is not None:
            try:
                # Alle Memories für user_id holen und einzeln löschen
                results_raw = mem0.get_all(filters={"user_id": user_id})
                items_raw = results_raw.get("results", []) if isinstance(results_raw, dict) else results_raw
                for item in items_raw:
                    mem_id = item.get("id")
                    if mem_id:
                        try:
                            mem0.delete(memory_id=str(mem_id))
                            deleted += 1
                        except Exception as _de:  # noqa: BLE001
                            log.warning("ltm_delete_by_user_item_failed", mem_id=mem_id, error=str(_de))
                log.info("ltm_deleted_by_user_mem0", user_id=user_id, deleted=deleted)
                return deleted
            except Exception as e:  # noqa: BLE001
                log.warning("ltm_delete_by_user_mem0_failed", user_id=user_id, error=str(e))

        # ChromaDB-Pfad: keine user_id-Isolierung — sicheres No-Op für echte user_id
        # (würde echte Daten löschen wenn user_id == "mike", daher hier kein blindes Löschen)
        log.warning("ltm_delete_by_user_chroma_noop", user_id=user_id, reason="no user_id filter in ChromaDB")
        return 0

    def count_by_user(self, user_id: str) -> int:
        """Zählt Memories für eine user_id (mem0-Pfad).

        Gibt -1 zurück wenn mem0 nicht aktiv (kein user_id-Filter in ChromaDB möglich).
        """
        mem0 = self._get_mem0()
        if mem0 is not None:
            try:
                results_raw = mem0.get_all(filters={"user_id": user_id})
                items_raw = results_raw.get("results", []) if isinstance(results_raw, dict) else results_raw
                return len(items_raw)
            except Exception as e:  # noqa: BLE001
                log.warning("ltm_count_by_user_failed", user_id=user_id, error=str(e))
                return -1
        return -1  # ChromaDB: kein user_id-Filter möglich

    def mem0_enabled(self) -> bool:
        """Gibt True zurück wenn mem0+Qdrant aktiv ist."""
        return self._mem0_enabled and self._get_mem0() is not None

    def chroma_count(self) -> int:
        """ChromaDB-Zähler (für Migration + Health-Check)."""
        try:
            return self._col.count()
        except Exception:  # noqa: BLE001
            return 0

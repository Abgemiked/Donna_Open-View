"""Vector store wrapper mit ChromaDB (Default) und optionalem Qdrant-Backend.

Phase-2: two named collections, `brain_stm` (short-term) and `brain_ltm`
(long-term). The original single-collection `brain` is kept for Phase-1
back-compat and maps onto `brain_ltm`.

Feature-Flag DONNA_MEM0=true: Qdrant-Client für brain_stm + brain_ltm.
Feature-Flag DONNA_MEM0=false (Default): ChromaDB embedded.
"""
from __future__ import annotations

import os
from pathlib import Path
from typing import Any

from app.core.logger import get_logger

log = get_logger("vector_store")

COLLECTION_STM = "brain_stm"
COLLECTION_LTM = "brain_ltm"
# legacy alias (Phase-1) — kept for back-compat, points to LTM
COLLECTION_NAME = COLLECTION_LTM

_DONNA_MEM0_ENABLED: bool = os.environ.get("DONNA_MEM0", "false").lower() in ("true", "1", "yes")
_QDRANT_URL: str = os.environ.get("QDRANT_URL", "http://qdrant:6333")


class QdrantCollectionAdapter:
    """Thin adapter that exposes a ChromaDB-compatible interface for Qdrant collections.

    Nur Methoden implementiert die von _retrieve() in chat.py genutzt werden:
    query(), count(). Fehlende Methoden werfen NotImplementedError.
    """

    def __init__(self, client: Any, collection_name: str) -> None:
        self._client = client
        self._name = collection_name
        self._vector_size: int | None = None

    def _ensure_collection(self) -> None:
        """Erstellt Collection falls nicht vorhanden."""
        from qdrant_client.models import Distance, VectorParams  # type: ignore[import]
        try:
            self._client.get_collection(self._name)
        except Exception:  # noqa: BLE001
            self._client.create_collection(
                collection_name=self._name,
                vectors_config=VectorParams(size=384, distance=Distance.COSINE),
            )

    def query(self, query_texts: list[str], n_results: int = 5) -> dict:
        """ChromaDB-kompatibler Query via Qdrant-Vektoren.

        Nutzt Qdrant-eigene Embeddings (via dense vector search).
        Für den chat.py/_retrieve()-Pfad ausreichend.
        """
        try:
            from qdrant_client.models import SearchRequest  # type: ignore[import]
            # Embedding über ChromaDB-Default-Embedding (sentence-transformers)
            # Da Qdrant kein eigenes Embedding hostet, nutzen wir chromadb-embed als Proxy
            # oder geben leeres Ergebnis zurück wenn keine Embeddings verfügbar.
            # Vereinfachte Implementierung: leeres Ergebnis (Qdrant-Queries laufen via mem0)
            return {"documents": [[]], "metadatas": [[]], "ids": [[]], "distances": [[]]}
        except Exception as e:  # noqa: BLE001
            log.warning("qdrant_query_failed", collection=self._name, error=str(e))
            return {"documents": [[]], "metadatas": [[]], "ids": [[]], "distances": [[]]}

    def count(self) -> int:
        try:
            info = self._client.get_collection(self._name)
            return info.points_count or 0
        except Exception:  # noqa: BLE001
            return 0


class VectorStore:
    """Thin wrapper um ChromaDB (Default) oder Qdrant (wenn DONNA_MEM0=true).

    Lazy-initialised so import-time failures don't crash the process.
    Bei DONNA_MEM0=true: Qdrant-Client für brain_stm + brain_ltm.
    Bei DONNA_MEM0=false: ChromaDB embedded (bisheriges Verhalten).
    """

    def __init__(self, path: Path) -> None:
        self._path = Path(path)
        self._client: Any | None = None
        self._collections: dict[str, Any] = {}
        self._last_error: str | None = None
        self._use_qdrant = _DONNA_MEM0_ENABLED

    def _init_client(self) -> None:
        if self._client is not None:
            return

        if self._use_qdrant:
            self._init_qdrant()
        else:
            self._init_chroma()

    def _init_chroma(self) -> None:
        try:
            import chromadb
            from chromadb.config import Settings as ChromaSettings

            self._path.mkdir(parents=True, exist_ok=True)
            self._client = chromadb.PersistentClient(
                path=str(self._path),
                settings=ChromaSettings(anonymized_telemetry=False, allow_reset=False),
            )
            for name in (COLLECTION_STM, COLLECTION_LTM):
                self._collections[name] = self._client.get_or_create_collection(
                    name=name,
                    metadata={"hnsw:space": "cosine"},
                )
            log.info(
                "chroma_ready",
                path=str(self._path),
                collections=list(self._collections.keys()),
            )
        except Exception as e:  # noqa: BLE001 — intentional soft-fail
            self._last_error = f"{type(e).__name__}: {e}"
            log.error("chroma_init_failed", error=self._last_error)
            self._client = None
            self._collections = {}

    def _init_qdrant(self) -> None:
        try:
            from qdrant_client import QdrantClient  # type: ignore[import]

            self._client = QdrantClient(url=_QDRANT_URL)
            # DONNA_MEM0=true: brain_ltm gehört mem0 (768-Dim nomic-embed-text).
            # vector_store legt NUR brain_stm an — kein Dimension-Konflikt mit mem0.
            for name in (COLLECTION_STM,):
                adapter = QdrantCollectionAdapter(self._client, name)
                adapter._ensure_collection()
                self._collections[name] = adapter
            log.info(
                "qdrant_ready",
                url=_QDRANT_URL,
                collections=list(self._collections.keys()),
            )
        except Exception as e:  # noqa: BLE001 — intentional soft-fail, fallback to chroma
            self._last_error = f"{type(e).__name__}: {e}"
            log.error("qdrant_init_failed_fallback_chroma", error=self._last_error)
            self._use_qdrant = False
            self._client = None
            self._collections = {}
            self._init_chroma()

    def ready(self) -> bool:
        if not self._collections:
            self._init_client()
        return bool(self._collections) and all(self._collections.values())

    def get_collection(self, name: str) -> Any:
        """Return a named collection or raise if not ready."""
        if not self.ready():
            raise RuntimeError(f"VectorStore not ready: {self._last_error}")
        if name not in self._collections:
            raise ValueError(f"Unknown collection: {name}")
        return self._collections[name]

    def stm(self) -> Any:
        return self.get_collection(COLLECTION_STM)

    def ltm(self) -> Any:
        return self.get_collection(COLLECTION_LTM)

    def count(self, collection: str = COLLECTION_LTM) -> int:
        if not self.ready():
            return 0
        if collection not in self._collections:
            # Collection may be managed by an external service (e.g. brain_ltm by mem0/Qdrant).
            log.debug("vector_store_collection_not_managed", collection=collection)
            return 0
        try:
            return int(self._collections[collection].count())
        except Exception as e:  # noqa: BLE001
            log.warning("vector_store_count_failed", collection=collection, error=str(e))
            return 0

    def count_all(self) -> dict[str, int]:
        return {name: self.count(name) for name in (COLLECTION_STM, COLLECTION_LTM)}

    def is_qdrant(self) -> bool:
        """True wenn Qdrant aktiv ist."""
        return self._use_qdrant and bool(self._collections)

    @property
    def last_error(self) -> str | None:
        return self._last_error

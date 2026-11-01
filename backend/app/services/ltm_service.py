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
import threading
import time
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
# A2.3: 'media_seen' — profilgetaggte Seh-Historie (Filme/Serien die Mike beiläufig erwähnt)
# A5 (#7): 'thread_state' — Session-Zusammenfassung "Stand/offene Punkte" für Cross-Chat-Kontinuität
_VALID_CATEGORIES = {"user_preference", "user_fact", "user_habit", "idea", "media_seen", "thread_state"}
_COLLECTION_NAME = "ltm_memories"
_STREAM_COLLECTION_NAME = "stream_memories"
# A5 (#7): bei kategorie-spezifischem mem0-Recall so viele Kandidaten ziehen, dass die
# Kategorie-Treffer nicht von häufigeren Kategorien aus dem top_k verdrängt werden.
_CATEGORY_RECALL_OVERFETCH = 50
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


# --- mem0-Konfiguration (Phase 1b: Ollama entfernt → Claude + lokales Embedding) ---
# Qdrant-Collection für mem0 (siehe auch scripts/reindex_mem0.py)
_MEM0_COLLECTION = "brain_ltm"
# mem0 v2 get_all() liefert ohne top_k nur 20 Treffer → explizit hohes Limit
_MEM0_LIST_LIMIT = 10000
# Defaults — per ENV überschreibbar (siehe config.py / .env.example)
_MEM0_DEFAULT_LLM_MODEL = "claude-haiku-5-5"
# 768 Dim, deutsch/englisch, ONNX via fastembed (kein torch). Apache-2.0.
_MEM0_DEFAULT_EMBED_MODEL = "jinaai/jina-embeddings-v2-base-de"
_MEM0_DEFAULT_EMBED_DIMS = 768
# fastembed lädt das Modell (~0.64 GB) beim ersten Start; Cache muss auf einem
# persistenten Volume liegen, sonst wird bei jedem Container-Neustart neu geladen.
_MEM0_DEFAULT_FASTEMBED_CACHE = "/data/chroma/fastembed_cache"
# Failure-Cooldown: nach fehlgeschlagenem mem0-Init (z.B. Modell-Download, Qdrant down,
# Key fehlt) wird fuer N Sekunden NICHT erneut initialisiert (sofort ChromaDB-Fallback).
# Verhindert, dass jeder Request erneut einen ~0.64-GB-Download/Init anstoesst.
# Per ENV MEM0_INIT_COOLDOWN_S konfigurierbar; 0 = Cooldown aus.
_MEM0_DEFAULT_INIT_COOLDOWN_S = 300.0

# mem0-Telemetrie (PostHog) ist standardmäßig an → DSGVO: für Donna immer aus,
# außer explizit per ENV gesetzt. Muss vor dem ersten `import mem0` gesetzt sein.
os.environ.setdefault("MEM0_TELEMETRY", "false")


def _resolve_anthropic_key() -> str:
    """ANTHROPIC_API_KEY aus ENV, Fallback Settings (.env). Leerstring wenn nicht gesetzt."""
    key = (os.environ.get("ANTHROPIC_API_KEY") or "").strip()
    if key:
        return key
    try:
        from app.config import get_settings

        return (get_settings().anthropic_api_key or "").strip()
    except Exception:  # noqa: BLE001
        return ""


def _mem0_embed_dims() -> int:
    raw = os.environ.get("MEM0_EMBED_DIMS", "").strip()
    try:
        return int(raw) if raw else _MEM0_DEFAULT_EMBED_DIMS
    except ValueError:
        log.warning("mem0_embed_dims_invalid", value=raw, fallback=_MEM0_DEFAULT_EMBED_DIMS)
        return _MEM0_DEFAULT_EMBED_DIMS


def _mem0_init_cooldown_s() -> float:
    """Cooldown (Sekunden) nach fehlgeschlagenem mem0-Init. Wird bei jedem Aufruf neu gelesen."""
    raw = os.environ.get("MEM0_INIT_COOLDOWN_S", "").strip()
    if not raw:
        return _MEM0_DEFAULT_INIT_COOLDOWN_S
    try:
        value = float(raw)
    except ValueError:
        log.warning("mem0_init_cooldown_invalid", value=raw, fallback=_MEM0_DEFAULT_INIT_COOLDOWN_S)
        return _MEM0_DEFAULT_INIT_COOLDOWN_S
    return max(0.0, value)


def _build_mem0_config(api_key: str) -> dict:
    """Baut die mem0-Konfiguration (rein, ohne Seiteneffekte — gut testbar).

    LLM:      Anthropic Claude (Default claude-haiku-5-5) — Fakten-Extraktion/Dedup.
              temperature=None → es werden keine Sampling-Parameter gesendet
              (neuere Claude-Modelle lehnen teils temperature/top_p ab).
    Embedder: fastembed (ONNX, lokal, kein externer Call, kein torch), 768 Dim.
              embedding_dims wird bewusst NICHT an den Embedder übergeben: mem0
              ermittelt sie dann aus dem geladenen Modell, und
              _create_mem0_client() prüft sie gegen den Vector-Store.
    Vector:   Qdrant, Collection brain_ltm.
    """
    dims = _mem0_embed_dims()
    return {
        "llm": {
            "provider": "anthropic",
            "config": {
                "model": os.environ.get("MEM0_LLM_MODEL", "").strip() or _MEM0_DEFAULT_LLM_MODEL,
                "api_key": api_key,
                "max_tokens": 2000,
                "temperature": None,
            },
        },
        "embedder": {
            "provider": "fastembed",
            "config": {
                "model": os.environ.get("MEM0_EMBED_MODEL", "").strip() or _MEM0_DEFAULT_EMBED_MODEL,
            },
        },
        "vector_store": {
            "provider": "qdrant",
            "config": {
                "url": _QDRANT_URL,
                "collection_name": _MEM0_COLLECTION,
                "embedding_model_dims": dims,
            },
        },
    }


def _prepare_fastembed_cache() -> None:
    """Setzt FASTEMBED_CACHE_PATH auf ein persistentes Verzeichnis (falls nicht gesetzt)."""
    if os.environ.get("FASTEMBED_CACHE_PATH"):
        return
    try:
        Path(_MEM0_DEFAULT_FASTEMBED_CACHE).mkdir(parents=True, exist_ok=True)
        os.environ["FASTEMBED_CACHE_PATH"] = _MEM0_DEFAULT_FASTEMBED_CACHE
    except OSError as e:
        # z.B. lokale Entwicklung ohne /data → fastembed nutzt sein Default-Tempdir
        log.warning("fastembed_cache_unavailable", path=_MEM0_DEFAULT_FASTEMBED_CACHE, error=str(e))


def _parse_anthropic_response(response, tools):
    """Robuster Ersatz für AnthropicLLM._parse_response (mem0 <= 2.0.x).

    Die Original-Implementierung nutzt `response.content[0].text` und bricht ab,
    sobald vor dem Text-Block ein Thinking-Block steht (Claude 5.5: Thinking
    ist standardmäßig an). Hier wird gezielt der Text-Block gesucht.
    """
    if tools:
        result: dict = {"content": None, "tool_calls": []}
        for block in response.content:
            btype = getattr(block, "type", None)
            if btype == "text":
                result["content"] = block.text
            elif btype == "tool_use":
                result["tool_calls"].append({"name": block.name, "arguments": block.input})
        return result
    for block in response.content:
        if getattr(block, "type", None) == "text":
            return block.text
    return ""


def _harden_mem0_anthropic_llm(client) -> None:
    """Passt den mem0-Anthropic-LLM für Claude 5.5 an (best effort, nie fatal).

    1. Thinking-Blöcke überspringen (siehe _parse_anthropic_response).
    2. output_config={"effort": "low"} als Default — wie in claude_client.py:
       Thinking verbraucht sonst das max_tokens-Budget der Fakten-Extraktion.
    """
    try:
        llm = getattr(client, "llm", None)
        if llm is None:
            return
        llm._parse_response = _parse_anthropic_response  # type: ignore[method-assign]
        messages_api = llm.client.messages
        orig_create = messages_api.create

        def _create(*args, **kwargs):
            kwargs.setdefault("output_config", {"effort": "low"})
            return orig_create(*args, **kwargs)

        messages_api.create = _create  # type: ignore[method-assign]
    except Exception as e:  # noqa: BLE001
        log.warning("mem0_llm_harden_failed", error=str(e))


def _create_mem0_client():
    """Erstellt mem0-Memory-Client (Lazy Init). Gibt None bei Fehler zurück.

    LLM: Anthropic Claude (claude-haiku-5-5) — Cloud, ANTHROPIC_API_KEY.
    Embedder: fastembed ONNX lokal (jinaai/jina-embeddings-v2-base-de, 768 Dim).
    Bei None läuft LTMService im ChromaDB-Fallback weiter.
    """
    api_key = _resolve_anthropic_key()
    if not api_key:
        log.error("mem0_init_failed", error="ANTHROPIC_API_KEY nicht gesetzt")
        return None
    try:
        from mem0 import Memory  # type: ignore[import]

        _prepare_fastembed_cache()
        config = _build_mem0_config(api_key)
        client = Memory.from_config(config)

        # Dimensions-Check: Embedder muss zur Qdrant-Collection passen
        expected_dims = config["vector_store"]["config"]["embedding_model_dims"]
        actual_dims = getattr(getattr(getattr(client, "embedding_model", None), "config", None), "embedding_dims", None)
        if isinstance(actual_dims, int) and actual_dims != expected_dims:
            log.error("mem0_init_failed", error=f"Embedding-Dim {actual_dims} != Vector-Store-Dim {expected_dims}")
            return None

        _harden_mem0_anthropic_llm(client)
        log.info(
            "mem0_client_ready",
            qdrant_url=_QDRANT_URL,
            llm=f"anthropic/{config['llm']['config']['model']}",
            embedder=f"fastembed/{config['embedder']['config']['model']}",
            dims=expected_dims,
        )
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
        self._mem0_lock = threading.Lock()  # DONNA-231: Thread-safe lazy-init
        # Monotonic-Zeitstempel des letzten fehlgeschlagenen Inits (None = kein Fehler/Cooldown)
        self._mem0_init_failed_at: float | None = None

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
        """Lazy-init mem0-Client. Gibt None zurück wenn nicht verfügbar.

        Failure-Cooldown: schlägt der Init fehl (-> None), wird für
        MEM0_INIT_COOLDOWN_S Sekunden (Default 300) kein neuer Versuch gestartet;
        Aufrufer fallen sofort auf ChromaDB zurück. Danach wird erneut versucht.
        Achtung: der Init kann blockieren (Modell-Download) → aus dem Event-Loop
        nur via asyncio.to_thread aufrufen (siehe Warmup in main.py).
        """
        if not self._mem0_enabled:
            return None
        client = self._mem0_client
        if client is not None:  # Fast-Path ohne Lock (Attributzugriff ist atomar)
            return client
        with self._mem0_lock:  # DONNA-231: Thread-safe lazy-init (TOCTOU fix)
            if self._mem0_client is not None:
                return self._mem0_client
            failed_at = self._mem0_init_failed_at
            if failed_at is not None and (time.monotonic() - failed_at) < _mem0_init_cooldown_s():
                return None  # Cooldown aktiv → kein erneuter Init-Versuch
            self._mem0_client = _create_mem0_client()
            # Zeitstempel NACH dem Versuch (der Init selbst kann lange dauern)
            self._mem0_init_failed_at = time.monotonic() if self._mem0_client is None else None
            return self._mem0_client

    def store_memory(
        self,
        session_id: str,
        content: str,
        category: str,
        user_id: str | None = None,
        infer: bool = True,
        extra_metadata: dict | None = None,
    ) -> str:
        """
        Speichert eine Memory. Gibt die ID zurück.
        PII-Filter und Floskel-Filter sind immer aktiv (unabhängig vom Backend).
        mem0-Pfad: memory.add() mit Qdrant als Vector Store.
        ChromaDB-Pfad: Deduplizierung + direktes Upsert.

        user_id: Optional — überschreibt _MEM0_USER_ID (für Test-Isolation via X-Test-User-Id).
        infer: Nur mem0-Pfad. infer=False schreibt den Content VERBATIM (kein LLM-Rewrite durch
            Haiku, keine Fakten-Extraktion) — A5/A3: für bereits fertige Zusammenfassungen/Imports,
            die NICHT umgeschrieben oder in Fantasie-Einträge zerlegt werden dürfen. ChromaDB
            ignoriert infer (schreibt ohnehin verbatim).
        extra_metadata: Optional[dict] — ADDITIV (Fix #8-Runde1): zusätzliche Metadaten, die in
            BEIDEN Pfaden mitgeschrieben werden (z.B. media_seen: profile/source). category und
            session_id bleiben reserviert und werden nicht überschrieben. Default None = unverändert.
        """
        effective_user_id = user_id if user_id else _MEM0_USER_ID
        # Reservierte Keys (category/session_id) niemals durch extra_metadata überschreiben.
        _extra = {k: v for k, v in (extra_metadata or {}).items() if k not in ("category", "session_id")}

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
                    metadata={"category": category, "session_id": session_id, **_extra},
                    infer=infer,  # A5/A3: infer=False → verbatim, kein Haiku-Rewrite
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
            metadatas=[{"category": category, "session_id": session_id, **_extra}],
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
        profile: str | None = None,
    ) -> list[dict]:
        """Semantic Recall: gibt relevante Memories zurück.

        mem0-Pfad: memory.search() mit Qdrant.
        ChromaDB-Pfad: direktes Query (bisheriges Verhalten).

        min_score: Optional[float] — wenn gesetzt, filtert Treffer mit score < min_score raus.
            None = kein Filter (Default der Methode). Der Chat nutzt
            settings.mem0_min_score (ENV MEM0_MIN_SCORE, Default 0.30; mem0-2.x-Hybrid-Scores
            liegen niedriger als in v1, Jina-Treffer grob 0.26-0.38). HINWEIS (A5): die Schwellen
            sind OHNE Produktionsdaten nur grob kalibriert — per ENV justierbar, nach Re-Index
            mit echten Queries nachziehen.
        user_id: Optional — überschreibt _MEM0_USER_ID (für Test-Isolation via X-Test-User-Id).
        profile: Optional[str] — ADDITIV (Fix #8-Folge): wenn gesetzt, werden nur Treffer mit
            meta["profile"]==profile zurückgegeben (media_seen ist profilgetaggt, siehe
            media_import_common). Default None = KEIN Filter → identisches Alt-Verhalten,
            bestehende Aufrufer (thread_state/media/general/idea) unberührt. Das Feld `profile`
            wird zusätzlich im Ergebnis-Dict zurückgegeben (additiv).
        """
        effective_user_id = user_id if user_id else _MEM0_USER_ID
        # mem0-Pfad
        mem0 = self._get_mem0()
        if mem0 is not None:
            try:
                # A5 (#7, Kategorie-Crowding): mem0.search kennt kein serverseitiges
                # category-Filter → wir filtern in Python NACH dem Recall. Bei kategorie-
                # spezifischen Recalls (z.B. thread_state) würden sonst häufige user_fact-
                # Treffer die wenigen Kategorie-Treffer aus dem top_k verdrängen.
                # EHRLICHE LIMITIERUNG (Opus-Befund): mem0 v2 ignoriert den Limit-Param
                # (siehe "Default 20 Treffer" unten) — die Over-Fetch wirkt daher nur dort,
                # wo der Client sie honoriert; innerhalb mem0s ~20 Rückgaben ist Kategorie-
                # Crowding möglich (bekannte Limitierung, mit Produktionsdaten zu prüfen).
                # Im ChromaDB-Pfad tritt das Problem nicht auf (serverseitiges where={category}).
                # Der finale results[:top_k]-Cap bleibt in jedem Fall korrekt.
                _search_top_k = max(top_k, _CATEGORY_RECALL_OVERFETCH) if category else top_k
                results_raw = mem0.search(
                    query=query,
                    filters={"user_id": effective_user_id},
                    top_k=_search_top_k,  # mem0 v2: 'limit' wird ignoriert → Default 20 Treffer
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
                    if profile is not None and meta.get("profile") != profile:
                        continue
                    results.append({
                        "id": str(item.get("id", "")),
                        "content": content,
                        "category": meta.get("category", "user_fact"),
                        "session_id": meta.get("session_id", ""),
                        "score": round(score, 4),
                        "profile": meta.get("profile"),
                    })
                return results[:top_k]
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
                if profile is not None and meta.get("profile") != profile:
                    continue
                results.append({
                    "id": mem_id,
                    "content": doc,
                    "category": meta.get("category", "user_fact"),
                    "session_id": meta.get("session_id", ""),
                    "score": score,
                    "profile": meta.get("profile"),
                })
            return results
        except Exception as e:  # noqa: BLE001
            log.warning("ltm_recall_failed", error=str(e))
            return []

    def list_by_category(
        self,
        category: str,
        *,
        profile: str | None = None,
        limit: int = 40,
        user_id: str | None = None,
        legacy_default_profile: str = "Mike",
    ) -> list[dict]:
        """Dedizierter, NICHT-semantischer Abruf aller Einträge einer Kategorie
        (optional profil-gefiltert). Fix #8-Runde1 / M1.

        Motivation (am Live-System belegt): ein semantischer top_k-recall_relevant über die
        media_seen-Historie (3028 Einträge, Family-dominant) lieferte 5/5 Family-Treffer →
        nach profile='Mike'-Filter 0. Ein deterministischer Kategorie-Scan + Profil-Filter
        trifft dagegen treffsicher die gesehenen Titel des Profils.

        Serverseitiger category-Filter (Chroma where / mem0 get_all), der profile-Filter läuft
        anschließend in Python — so werden Legacy-Einträge OHNE profile-Metadatum dem
        `legacy_default_profile` (Default 'Mike') zugeordnet statt still verworfen (das ließe
        sich serverseitig nicht ausdrücken). Ergebnis gedeckelt auf `limit`.
        """
        effective_user_id = user_id if user_id else _MEM0_USER_ID
        out: list[dict] = []

        def _effective_profile(meta: dict) -> str | None:
            """Profil des Eintrags (Legacy ohne Tag → Default). None = passt nicht zum Filter."""
            if meta.get("category") != category:
                return None
            eff = meta.get("profile") or legacy_default_profile
            if profile is not None and eff != profile:
                return None
            return eff

        # mem0-Pfad
        mem0 = self._get_mem0()
        if mem0 is not None:
            try:
                results_raw = mem0.get_all(filters={"user_id": effective_user_id}, top_k=_MEM0_LIST_LIMIT)
                items_raw = results_raw.get("results", []) if isinstance(results_raw, dict) else results_raw
                for item in items_raw:
                    meta = item.get("metadata") or {}
                    eff = _effective_profile(meta)
                    if eff is None:
                        continue
                    out.append({
                        "id": str(item.get("id", "")),
                        "content": item.get("memory", ""),
                        "category": meta.get("category", category),
                        "session_id": meta.get("session_id", ""),
                        "profile": eff,
                    })
                    if len(out) >= limit:
                        break
                return out
            except Exception as e:  # noqa: BLE001
                log.warning("ltm_list_by_category_mem0_failed_fallback_chroma", error=str(e))

        # ChromaDB-Pfad (Default oder Fallback) — serverseitiger category-Filter via where.
        try:
            if self._col.count() == 0:
                return []
            res = self._col.get(where={"category": category})
            ids = res.get("ids", []) or []
            docs = res.get("documents", []) or []
            metas = res.get("metadatas", []) or []
            for mem_id, doc, meta in zip(ids, docs, metas):
                meta = meta or {}
                eff = _effective_profile(meta)
                if eff is None:
                    continue
                out.append({
                    "id": mem_id,
                    "content": doc,
                    "category": meta.get("category", category),
                    "session_id": meta.get("session_id", ""),
                    "profile": eff,
                })
                if len(out) >= limit:
                    break
            return out
        except Exception as e:  # noqa: BLE001
            log.warning("ltm_list_by_category_failed", error=str(e))
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
                results_raw = mem0.get_all(filters={"user_id": effective_user_id}, top_k=_MEM0_LIST_LIMIT)
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
                results_raw = mem0.get_all(filters={"user_id": user_id}, top_k=_MEM0_LIST_LIMIT)
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
                results_raw = mem0.get_all(filters={"user_id": user_id}, top_k=_MEM0_LIST_LIMIT)
                items_raw = results_raw.get("results", []) if isinstance(results_raw, dict) else results_raw
                return len(items_raw)
            except Exception as e:  # noqa: BLE001
                log.warning("ltm_count_by_user_failed", user_id=user_id, error=str(e))
                return -1
        return -1  # ChromaDB: kein user_id-Filter möglich

    def mem0_enabled(self) -> bool:
        """Gibt True zurück wenn mem0+Qdrant aktiv ist (kann einen Init anstoßen → blockierend!)."""
        return self._mem0_enabled and self._get_mem0() is not None

    def mem0_ready(self) -> bool:
        """True nur wenn der mem0-Client bereits initialisiert ist.

        Löst NIE einen Init aus und blockiert nie → sicher für Health-Checks im Event-Loop.
        """
        return self._mem0_enabled and self._mem0_client is not None

    def chroma_count(self) -> int:
        """ChromaDB-Zähler (für Migration + Health-Check)."""
        try:
            return self._col.count()
        except Exception:  # noqa: BLE001
            return 0

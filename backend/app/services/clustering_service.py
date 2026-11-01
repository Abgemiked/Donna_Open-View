"""
clustering_service.py — HDBSCAN Nightly Clustering des LTM-Vaults

Läuft täglich um 02:00 UTC via APScheduler.
Clustert alle LTM-Embeddings, extrahiert Top-Keywords per Cluster (TF-IDF)
und schreibt ein Vault-Profil nach vault/profile/clusters.md.
"""
from __future__ import annotations

import re
import sqlite3
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import TYPE_CHECKING

import numpy as np

from app.core.logger import get_logger

if TYPE_CHECKING:
    from app.services.ltm_service import LTMService

log = get_logger("service.clustering")

# ASCII-safe cluster name: nur a-z, 0-9, underscore, max 30 Zeichen
_SLUG_RE = re.compile(r"[^a-z0-9]+")

# SQLite Tabelle für Clustering-Status
_CREATE_CLUSTER_STATUS_SQL = """
CREATE TABLE IF NOT EXISTS clustering_status (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    run_at      REAL    NOT NULL,
    entry_count INTEGER NOT NULL,
    cluster_count INTEGER NOT NULL,
    noise_count INTEGER NOT NULL,
    dry_run     INTEGER NOT NULL DEFAULT 0
);
"""


def _slugify(text: str, max_len: int = 30) -> str:
    """Erstellt einen ASCII-lowercase Slug aus einem Keyword."""
    slug = _SLUG_RE.sub("_", text.lower().strip())
    slug = slug.strip("_")
    return slug[:max_len] if slug else "unknown"


def _tfidf_top_keywords(documents: list[str], top_n: int = 3) -> list[str]:
    """
    Einfaches TF-IDF für eine Liste von Dokumenten.
    Gibt die top_n Terme mit dem höchsten TF-IDF-Score zurück.
    Stopwörter werden herausgefiltert.
    """
    # Deutsche + englische Basis-Stopwörter
    stopwords = {
        "ich", "du", "er", "sie", "es", "wir", "ihr", "die", "der", "das",
        "ein", "eine", "und", "oder", "aber", "ist", "bin", "hat", "haben",
        "sein", "nicht", "mit", "von", "auf", "in", "an", "zu", "für",
        "im", "am", "dem", "den", "des", "bei", "als", "wie", "auch",
        "the", "a", "an", "is", "are", "was", "be", "to", "of", "and",
        "in", "that", "it", "for", "on", "with", "as", "at", "by", "i",
        "me", "my", "mag", "kann", "will", "dass", "was", "wenn", "dann",
        "habe", "mein", "meine", "sehr", "noch", "so", "mehr", "aus",
    }

    if not documents:
        return []

    # Tokenisierung
    all_tokens: list[list[str]] = []
    for doc in documents:
        tokens = re.findall(r"[a-zäöüA-ZÄÖÜ]{3,}", doc.lower())
        tokens = [t for t in tokens if t not in stopwords]
        all_tokens.append(tokens)

    n_docs = len(all_tokens)
    if n_docs == 0:
        return []

    # Term-Frequenz pro Dokument
    from collections import Counter
    tf_counters = [Counter(tokens) for tokens in all_tokens]

    # Dokumentenfrequenz
    df: dict[str, int] = {}
    for tokens in all_tokens:
        for term in set(tokens):
            df[term] = df.get(term, 0) + 1

    # TF-IDF Score über alle Cluster-Dokumente aggregieren
    tfidf_scores: dict[str, float] = {}
    for tf in tf_counters:
        total = sum(tf.values()) or 1
        for term, count in tf.items():
            tf_val = count / total
            idf_val = np.log((n_docs + 1) / (df.get(term, 0) + 1)) + 1.0
            tfidf_scores[term] = tfidf_scores.get(term, 0.0) + tf_val * idf_val

    # Top-N zurückgeben
    sorted_terms = sorted(tfidf_scores.items(), key=lambda x: x[1], reverse=True)
    return [term for term, _ in sorted_terms[:top_n]]


class ClusteringService:
    """HDBSCAN-basierter LTM-Clustering-Service."""

    def __init__(
        self,
        ltm_service: "LTMService",
        vault_path: str = "/vault",
        status_db_path: str = "/data/stm.db",
        min_cluster_size: int = 3,
    ) -> None:
        self._ltm = ltm_service
        self._vault_path = Path(vault_path)
        self._status_db_path = status_db_path
        self._min_cluster_size = min_cluster_size
        self._init_status_db()

    def _init_status_db(self) -> None:
        """Erstellt die clustering_status Tabelle falls nicht vorhanden."""
        try:
            Path(self._status_db_path).parent.mkdir(parents=True, exist_ok=True)
            with sqlite3.connect(self._status_db_path) as conn:
                conn.execute(_CREATE_CLUSTER_STATUS_SQL)
                conn.commit()
        except Exception as e:  # noqa: BLE001
            log.warning("clustering_status_db_init_failed", error=str(e))

    def _get_all_with_embeddings(self) -> tuple[list[str], list[str], list[dict], list[list[float]]]:
        """
        Lädt alle LTM-Einträge inkl. Embeddings aus ChromaDB.
        Gibt (ids, documents, metadatas, embeddings) zurück.
        """
        col = self._ltm._col
        count = col.count()
        if count == 0:
            return [], [], [], []

        result = col.get(include=["documents", "metadatas", "embeddings"])
        ids = result.get("ids", [])
        documents = result.get("documents", []) or []
        metadatas = result.get("metadatas", []) or []
        embeddings = result.get("embeddings", []) or []

        # Embeddings könnten None sein wenn die Collection keine Embeddings hat
        if not embeddings or embeddings is None:
            log.warning("clustering_no_embeddings", count=count)
            return ids, documents, metadatas, []

        return ids, documents, metadatas, embeddings

    def _run_hdbscan(self, embeddings: list[list[float]]) -> list[int]:
        """
        Führt HDBSCAN auf den Embeddings aus.
        Gibt Cluster-Labels zurück (-1 = Noise).
        """
        import hdbscan  # type: ignore[import-untyped]

        arr = np.array(embeddings, dtype=np.float32)
        clusterer = hdbscan.HDBSCAN(
            min_cluster_size=self._min_cluster_size,
            metric="euclidean",
            core_dist_n_jobs=1,  # kein Parallelismus im Container
        )
        labels: list[int] = clusterer.fit_predict(arr).tolist()
        return labels

    def _build_clusters_md(
        self,
        clusters: dict[str, dict],
        noise_count: int,
        timestamp: str,
    ) -> str:
        """Erstellt den Inhalt der clusters.md Profil-Datei."""
        lines = [
            f"# Donna — Erkannte Muster (Stand: {timestamp})",
            "",
        ]
        for cluster_name, info in sorted(clusters.items()):
            count = info["count"]
            keywords = info["keywords"]
            latest = info.get("latest_timestamp", "")
            example = info.get("example", "")[:100]
            kw_str = ", ".join(keywords) if keywords else "—"
            lines += [
                f"## {cluster_name} ({count} Einträge)",
                f"- Top-Keywords: {kw_str}",
                f"- Letzte Aktivität: {latest}",
                f'- Beispiel: "{example}..."',
                "",
            ]
        lines += [
            f"## Noise ({noise_count} Einträge)",
            "Einträge die keinem Cluster zugeordnet werden konnten.",
            "",
        ]
        return "\n".join(lines)

    async def run_nightly_clustering(self, dry_run: bool = False) -> dict:
        """
        Hauptfunktion: lädt Embeddings, clustert, extrahiert Keywords,
        aktualisiert Metadaten und schreibt das Vault-Profil.

        Args:
            dry_run: Wenn True, werden keine Dateien geschrieben und keine
                     Metadaten in ChromaDB aktualisiert. Nur Logging.

        Returns:
            dict mit entry_count, cluster_count, noise_count, clusters (Namen)
        """
        log.info("clustering_start", dry_run=dry_run)

        ids, documents, metadatas, embeddings = self._get_all_with_embeddings()
        entry_count = len(ids)

        if entry_count == 0:
            log.info("clustering_no_entries")
            self._save_status(entry_count=0, cluster_count=0, noise_count=0, dry_run=dry_run)
            return {"entry_count": 0, "cluster_count": 0, "noise_count": 0, "clusters": []}

        if not embeddings:
            log.warning("clustering_no_embeddings_skip")
            self._save_status(entry_count=entry_count, cluster_count=0, noise_count=entry_count, dry_run=dry_run)
            return {
                "entry_count": entry_count,
                "cluster_count": 0,
                "noise_count": entry_count,
                "clusters": [],
                "warning": "no_embeddings",
            }

        # HDBSCAN
        try:
            labels = self._run_hdbscan(embeddings)
        except Exception as e:  # noqa: BLE001
            log.error("clustering_hdbscan_failed", error=str(e))
            return {"error": str(e), "entry_count": entry_count}

        # Cluster-Gruppen aufbauen
        cluster_groups: dict[int, list[int]] = {}
        for idx, label in enumerate(labels):
            cluster_groups.setdefault(label, []).append(idx)

        noise_indices = cluster_groups.pop(-1, [])
        noise_count = len(noise_indices)
        cluster_count = len(cluster_groups)

        log.info(
            "clustering_done",
            entry_count=entry_count,
            cluster_count=cluster_count,
            noise_count=noise_count,
            dry_run=dry_run,
        )

        # Cluster-Namen + Keywords + Metadaten-Updates
        timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
        clusters_info: dict[str, dict] = {}
        metadata_updates: list[tuple[str, str]] = []  # (id, cluster_name)

        for label, indices in cluster_groups.items():
            cluster_docs = [documents[i] for i in indices]
            keywords = _tfidf_top_keywords(cluster_docs, top_n=3)
            top_kw = keywords[0] if keywords else f"cluster{label}"
            cluster_name = f"cluster_{_slugify(top_kw)}"

            # Zeitstempel aus Metadaten (falls vorhanden)
            latest_ts = ""
            for i in indices:
                ts = (metadatas[i] or {}).get("created_at", "")
                if ts and ts > latest_ts:
                    latest_ts = ts

            clusters_info[cluster_name] = {
                "count": len(indices),
                "keywords": keywords,
                "latest_timestamp": latest_ts,
                "example": cluster_docs[0] if cluster_docs else "",
            }

            for i in indices:
                metadata_updates.append((ids[i], cluster_name))

        # Noise-Einträge als "noise" markieren
        for i in noise_indices:
            metadata_updates.append((ids[i], "noise"))

        # Metadaten in ChromaDB aktualisieren
        if not dry_run and metadata_updates:
            try:
                col = self._ltm._col
                for mem_id, cluster_name in metadata_updates:
                    idx = ids.index(mem_id)
                    existing_meta = dict(metadatas[idx] or {})
                    existing_meta["cluster"] = cluster_name
                    col.update(ids=[mem_id], metadatas=[existing_meta])
                log.info("clustering_metadata_updated", count=len(metadata_updates))
            except Exception as e:  # noqa: BLE001
                log.error("clustering_metadata_update_failed", error=str(e))

        # Vault-Profil schreiben
        if not dry_run:
            self._write_clusters_md(clusters_info, noise_count, timestamp)
        else:
            log.info(
                "clustering_dry_run_skip_write",
                clusters=[n for n in clusters_info],
                noise_count=noise_count,
            )

        # Status speichern
        self._save_status(
            entry_count=entry_count,
            cluster_count=cluster_count,
            noise_count=noise_count,
            dry_run=dry_run,
        )

        return {
            "entry_count": entry_count,
            "cluster_count": cluster_count,
            "noise_count": noise_count,
            "clusters": sorted(clusters_info.keys()),
            "dry_run": dry_run,
        }

    def _write_clusters_md(
        self,
        clusters: dict[str, dict],
        noise_count: int,
        timestamp: str,
    ) -> None:
        """Schreibt vault/profile/clusters.md."""
        try:
            profile_dir = self._vault_path / "profile"
            profile_dir.mkdir(parents=True, exist_ok=True)
            content = self._build_clusters_md(clusters, noise_count, timestamp)
            (profile_dir / "clusters.md").write_text(content, encoding="utf-8")
            log.info("clustering_profile_written", path=str(profile_dir / "clusters.md"))
        except Exception as e:  # noqa: BLE001
            log.error("clustering_profile_write_failed", error=str(e))

    def _save_status(
        self,
        entry_count: int,
        cluster_count: int,
        noise_count: int,
        dry_run: bool,
    ) -> None:
        """Speichert den letzten Clustering-Lauf in SQLite."""
        try:
            with sqlite3.connect(self._status_db_path) as conn:
                conn.execute(
                    "INSERT INTO clustering_status (run_at, entry_count, cluster_count, noise_count, dry_run) "
                    "VALUES (?, ?, ?, ?, ?)",
                    (time.time(), entry_count, cluster_count, noise_count, int(dry_run)),
                )
                conn.commit()
        except Exception as e:  # noqa: BLE001
            log.warning("clustering_status_save_failed", error=str(e))

    def get_status(self) -> dict | None:
        """Gibt den letzten Clustering-Lauf zurück."""
        try:
            with sqlite3.connect(self._status_db_path) as conn:
                row = conn.execute(
                    "SELECT run_at, entry_count, cluster_count, noise_count, dry_run "
                    "FROM clustering_status ORDER BY id DESC LIMIT 1"
                ).fetchone()
            if not row:
                return None
            run_at_ts, entry_count, cluster_count, noise_count, dry_run = row
            run_at_dt = datetime.fromtimestamp(run_at_ts, tz=timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
            return {
                "last_run": run_at_dt,
                "entry_count": entry_count,
                "cluster_count": cluster_count,
                "noise_count": noise_count,
                "dry_run": bool(dry_run),
            }
        except Exception as e:  # noqa: BLE001
            log.warning("clustering_status_get_failed", error=str(e))
            return None

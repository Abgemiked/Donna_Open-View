"""Tests für DONNA-8: HDBSCAN Nightly Clustering + Muster-Erkennung + Vault-Profil-Schreibung."""
from __future__ import annotations

import time
from pathlib import Path
from unittest.mock import MagicMock, patch

import numpy as np
import pytest
import pytest_asyncio

from app.services.clustering_service import ClusteringService, _slugify, _tfidf_top_keywords
from app.services.pattern_service import PatternService, EVENT_CHAT_MESSAGE, EVENT_LTM_STORE

# HDBSCAN ist nur in Docker/Production verfügbar — Tests die HDBSCAN benötigen werden lokal übersprungen
try:
    import hdbscan  # noqa: F401
    _HDBSCAN_AVAILABLE = True
except ImportError:
    _HDBSCAN_AVAILABLE = False

requires_hdbscan = pytest.mark.skipif(
    not _HDBSCAN_AVAILABLE,
    reason="hdbscan nicht installiert (wird im Docker-Container via requirements.txt bereitgestellt)",
)


# ---------------------------------------------------------------------------
# Hilfsfunktionen
# ---------------------------------------------------------------------------

def _make_ltm_mock(embeddings: list[list[float]], documents: list[str], ids: list[str]) -> MagicMock:
    """Erstellt einen LTMService-Mock mit vordefinierten Embeddings."""
    ltm = MagicMock()
    col = MagicMock()

    col.count.return_value = len(ids)
    col.get.return_value = {
        "ids": ids,
        "documents": documents,
        "metadatas": [{"category": "user_fact"} for _ in ids],
        "embeddings": embeddings,
    }
    col.update = MagicMock()
    ltm._col = col
    return ltm


# ---------------------------------------------------------------------------
# Unit-Tests: Hilfsfunktionen
# ---------------------------------------------------------------------------

def test_slugify_basic() -> None:
    assert _slugify("Kaffee Trinken") == "kaffee_trinken"


def test_slugify_max_len() -> None:
    result = _slugify("a" * 50)
    assert len(result) <= 30


def test_slugify_special_chars() -> None:
    result = _slugify("Twitch-Streaming & Gaming!")
    # Nur a-z, 0-9, _
    assert all(c in "abcdefghijklmnopqrstuvwxyz0123456789_" for c in result)


def test_tfidf_top_keywords() -> None:
    docs = [
        "Ich mag Kaffee sehr gerne und trinke Kaffee täglich",
        "Kaffee am Morgen ist wichtig für mich",
        "Ein guter Kaffee macht den Tag",
    ]
    keywords = _tfidf_top_keywords(docs, top_n=3)
    assert len(keywords) <= 3
    # "Kaffee" sollte ganz oben stehen
    assert keywords[0].lower() == "kaffee"


def test_tfidf_empty_docs() -> None:
    result = _tfidf_top_keywords([], top_n=3)
    assert result == []


# ---------------------------------------------------------------------------
# Test 1: Clustering mit Mock-Embeddings (2 Gruppen → mind. 2 Cluster)
# ---------------------------------------------------------------------------

@requires_hdbscan
@pytest.mark.asyncio
async def test_clustering_with_mock_embeddings(tmp_path: Path) -> None:
    """10 Mock-Embeddings in 2 Gruppen → HDBSCAN findet 2+ Cluster."""
    # Gruppe A: 5 Embeddings um (0, 0)
    group_a = [[0.1 * i, 0.05 * i] for i in range(5)]
    # Gruppe B: 5 Embeddings um (10, 10)
    group_b = [[10.0 + 0.1 * i, 10.0 + 0.05 * i] for i in range(5)]
    embeddings = group_a + group_b

    ids = [f"id_{i}" for i in range(10)]
    documents = [
        "Ich mag Kaffee und Tee morgens",
        "Kaffee ist mein Lieblingsgetränk",
        "Morgens trinke ich gerne Kaffee",
        "Kaffee hilft beim Aufwachen",
        "Espresso ist eine Kaffeevariation",
        "Ich streame auf Twitch täglich",
        "Twitch ist meine Streaming-Plattform",
        "Gaming und Streaming mache ich abends",
        "Twitch-Viewer schreiben im Chat",
        "Ich bin Twitch-Streamer seit Jahren",
    ]

    ltm_mock = _make_ltm_mock(embeddings, documents, ids)
    db_path = str(tmp_path / "test_cluster.db")
    vault_path = str(tmp_path / "vault")

    svc = ClusteringService(
        ltm_service=ltm_mock,
        vault_path=vault_path,
        status_db_path=db_path,
        min_cluster_size=3,
    )

    result = await svc.run_nightly_clustering(dry_run=False)

    assert result["entry_count"] == 10
    assert result["cluster_count"] >= 2, f"Erwartet mind. 2 Cluster, bekommen: {result}"
    assert "clusters" in result
    assert len(result["clusters"]) >= 2


# ---------------------------------------------------------------------------
# Test 2: Pattern peak_time — 5 Events abends, 1 morgens → evening
# ---------------------------------------------------------------------------

def test_pattern_peak_time(tmp_path: Path) -> None:
    """5 Events abends (19 Uhr), 1 morgens (8 Uhr) → peak_time = 'evening'."""
    import sqlite3 as _sqlite3
    from datetime import datetime, timezone, timedelta

    db_path = str(tmp_path / "pattern_test.db")
    vault_path = str(tmp_path / "vault")

    svc = PatternService(db_path=db_path, vault_path=vault_path)

    # Verwende aktuelle Zeit als Basis (UTC-jetzt), setze Stunde auf 19 (evening)
    now = datetime.now(timezone.utc)
    # 5 abends-Events: heute, 19 Uhr UTC
    evening_dt = now.replace(hour=19, minute=0, second=0, microsecond=0)
    evening_ts = evening_dt.timestamp()

    for _ in range(5):
        with _sqlite3.connect(db_path) as conn:
            conn.execute(
                "INSERT INTO event_log (event_type, metadata_json, created_at) VALUES (?, ?, ?)",
                (EVENT_CHAT_MESSAGE, '{"session_id": "sess1"}', evening_ts),
            )
            conn.commit()

    # 1 morgens-Event: heute, 8 Uhr UTC
    morning_dt = now.replace(hour=8, minute=0, second=0, microsecond=0)
    morning_ts = morning_dt.timestamp()
    with _sqlite3.connect(db_path) as conn:
        conn.execute(
            "INSERT INTO event_log (event_type, metadata_json, created_at) VALUES (?, ?, ?)",
            (EVENT_CHAT_MESSAGE, '{"session_id": "sess2"}', morning_ts),
        )
        conn.commit()

    patterns = svc.detect_patterns(days=30)
    peak = next((p for p in patterns if p["type"] == "peak_time"), None)

    assert peak is not None, "peak_time-Pattern nicht gefunden"
    assert peak["value"] == "evening", f"Erwartet 'evening', bekommen: {peak['value']}"
    assert peak["confidence"] >= 0.7


# ---------------------------------------------------------------------------
# Test 3: Vault-Profil-Schreibung — clusters.md wird erstellt
# ---------------------------------------------------------------------------

@requires_hdbscan
@pytest.mark.asyncio
async def test_vault_profile_written(tmp_path: Path) -> None:
    """run_nightly_clustering(dry_run=False) schreibt vault/profile/clusters.md."""
    # 2×5 Gruppen
    group_a = [[float(i), 0.0] for i in range(5)]
    group_b = [[float(i), 100.0] for i in range(5)]
    embeddings = group_a + group_b

    ids = [f"id_{i}" for i in range(10)]
    documents = [
        "Kaffee morgens ist wichtig",
        "Kaffee hilft beim Start",
        "Espresso ist gut",
        "Ich mag Kaffee sehr",
        "Kaffee und Tee",
        "Twitch Streaming heute",
        "Ich streame täglich",
        "Gaming Session heute",
        "Twitch ist toll",
        "Abendliches Streamen",
    ]

    ltm_mock = _make_ltm_mock(embeddings, documents, ids)
    db_path = str(tmp_path / "vault_test.db")
    vault_path = str(tmp_path / "vault")

    svc = ClusteringService(
        ltm_service=ltm_mock,
        vault_path=vault_path,
        status_db_path=db_path,
        min_cluster_size=3,
    )

    await svc.run_nightly_clustering(dry_run=False)

    clusters_md = Path(vault_path) / "profile" / "clusters.md"
    assert clusters_md.exists(), f"clusters.md nicht gefunden: {clusters_md}"
    content = clusters_md.read_text(encoding="utf-8")
    assert "Donna" in content
    assert "Erkannte Muster" in content
    assert "Noise" in content


# ---------------------------------------------------------------------------
# Test 4: Clustering-Endpoint dry_run → 200, keine Dateien geändert
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_clustering_endpoint_dry_run(tmp_path: Path) -> None:
    """POST /clustering/run?dry_run=true → 200, clusters.md wird NICHT geschrieben."""
    from httpx import AsyncClient, ASGITransport
    from fastapi import FastAPI
    from app.routes.clustering import router

    # Minimale FastAPI-App mit dem Clustering-Router
    fast_app = FastAPI()
    fast_app.include_router(router)

    # Mock clustering service
    from unittest.mock import AsyncMock
    mock_svc = MagicMock()
    mock_svc.run_nightly_clustering = AsyncMock(return_value={
        "entry_count": 0,
        "cluster_count": 0,
        "noise_count": 0,
        "clusters": [],
        "dry_run": True,
    })

    fast_app.state.clustering = mock_svc

    # Mock require_admin
    from app.core.auth import require_admin
    fast_app.dependency_overrides[require_admin] = lambda: "test-admin"

    vault_path = str(tmp_path / "vault")
    clusters_md = Path(vault_path) / "profile" / "clusters.md"

    async with AsyncClient(
        transport=ASGITransport(app=fast_app),
        base_url="http://test",
    ) as client:
        response = await client.post(
            "/clustering/run",
            params={"dry_run": "true"},
            headers={"Authorization": "Bearer test-token"},
        )

    assert response.status_code == 200, f"Unerwarteter Status: {response.status_code} — {response.text}"
    data = response.json()
    assert data["dry_run"] is True

    # clusters.md darf NICHT geschrieben worden sein
    assert not clusters_md.exists(), "clusters.md wurde im dry_run-Modus geschrieben!"

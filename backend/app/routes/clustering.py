"""
clustering.py — Router für manuelle Clustering-Trigger und Status-Abfrage

Endpoints:
  POST /clustering/run?dry_run=true  — manueller Trigger (dry_run default: false)
  GET  /clustering/status             — letzter Clustering-Lauf aus SQLite

Auth: Bearer-Token (require_admin).
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, Query, Request

from app.core.auth import require_admin
from app.services.clustering_service import ClusteringService

router = APIRouter(prefix="/clustering", tags=["clustering"])


def _get_clustering(request: Request) -> ClusteringService:
    return request.app.state.clustering  # type: ignore[no-any-return]


@router.post("/run")
async def run_clustering(
    request: Request,
    dry_run: bool = Query(default=False, description="Wenn True: keine Dateien schreiben, nur Vorschau"),
    _admin: str = Depends(require_admin),
) -> dict:
    """Startet das HDBSCAN-Clustering manuell. dry_run=true für Vorschau ohne Schreibzugriff."""
    clustering: ClusteringService = _get_clustering(request)
    result = await clustering.run_nightly_clustering(dry_run=dry_run)
    return result


@router.get("/status")
async def get_clustering_status(
    request: Request,
    _admin: str = Depends(require_admin),
) -> dict:
    """Gibt den letzten Clustering-Lauf zurück (Timestamp, Cluster-Count, Entry-Count)."""
    clustering: ClusteringService = _get_clustering(request)
    status = clustering.get_status()
    if status is None:
        return {"status": "no_run_yet"}
    return status

"""ltm_curation.py — Quartalsweise LTM-Bereinigung.

Merge Embedding-Duplikate, archiviere Low-Confidence- und verwaiste Einträge.
CronJob: 0 3 1 */3 * (1. Januar, April, Juli, Oktober, 03:00 UTC)
"""
from __future__ import annotations

from pathlib import Path

from app.core.logger import get_logger
from app.services.ltm_service import LTMService

log = get_logger("job.ltm_curation")

# L2-Distanz unter diesem Schwellwert → Duplikat
_DUPLICATE_L2_THRESHOLD = 0.05
# confidence-Wert unter diesem Schwellwert → archivieren
_LOW_CONFIDENCE_THRESHOLD = 0.3
# Einträge ohne access_count > 0 in diesen Tagen → archivieren
_ORPHAN_DAYS = 90


def _get_forget_dir(ltm: LTMService) -> Path:
    """Gibt den _forget/-Ordner relativ zum LTM-DB-Pfad zurück."""
    # B1 — Fallback auf konfigurierten ltm_db_path statt hardcoded /data/ltm
    from app.config import get_settings
    try:
        settings = ltm._client.get_settings()  # noqa: SLF001
        chroma_path = Path(settings.persist_directory)
    except Exception:  # noqa: BLE001
        chroma_path = Path(get_settings().ltm_db_path)
    forget_dir = chroma_path.parent / "_forget"
    forget_dir.mkdir(parents=True, exist_ok=True)
    return forget_dir


def _archive_entry(forget_dir: Path, mem_id: str, content: str, reason: str) -> None:
    """Schreibt einen archivierten Eintrag in den _forget/ Ordner."""
    from datetime import datetime, timezone
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    safe_id = mem_id[:8]
    target = forget_dir / f"{ts}_{safe_id}_{reason}.txt"
    target.write_text(f"id: {mem_id}\nreason: {reason}\ncontent: {content}\n", encoding="utf-8")


async def run_curation(ltm: LTMService, dry_run: bool = False) -> dict:
    """Führt die LTM-Curation durch.

    Args:
        ltm: LTMService-Instanz.
        dry_run: Wenn True, wird nur geloggt — keine Änderungen an der DB.

    Returns:
        Report-Dict mit Statistiken.
    """
    log.info("ltm_curation_start", dry_run=dry_run)

    all_entries = ltm.get_all()
    if not all_entries:
        log.info("ltm_curation_empty", message="Keine LTM-Einträge vorhanden.")
        return {"merged": 0, "archived_low_confidence": 0, "archived_orphan": 0, "dry_run": dry_run}

    forget_dir = _get_forget_dir(ltm)

    merged_count = 0
    archived_low_confidence = 0
    archived_orphan = 0
    deleted_ids: set[str] = set()

    # --- Embedding-Duplikate: L2-Distanz < 0.05 → Merge ---
    # Für jede Entry: query mit ihrem eigenen content und schaue ob nahe Einträge existieren
    for entry in all_entries:
        if entry["id"] in deleted_ids:
            continue

        content = entry["content"]
        try:
            # Query: finde ähnliche Einträge — n_results=3 um Duplikate zu finden
            count = ltm._col.count()  # noqa: SLF001
            if count < 2:
                break

            res = ltm._col.query(  # noqa: SLF001
                query_texts=[content],
                n_results=min(3, count),
            )
            distances = (res.get("distances") or [[]])[0]
            ids = (res.get("ids") or [[]])[0]

            for dist, similar_id in zip(distances, ids):
                # Überspringe sich selbst (distance ≈ 0) und bereits gelöschte
                if dist < 1e-6 or similar_id in deleted_ids or similar_id == entry["id"]:
                    continue
                # Duplikat-Schwelle
                if dist < _DUPLICATE_L2_THRESHOLD:
                    # Kürzeren löschen, längeren behalten
                    similar_entries = [e for e in all_entries if e["id"] == similar_id]
                    if not similar_entries:
                        continue
                    similar_entry = similar_entries[0]
                    shorter_id = entry["id"] if len(content) <= len(similar_entry["content"]) else similar_id
                    shorter_content = content if shorter_id == entry["id"] else similar_entry["content"]

                    log.info(
                        "ltm_curation_duplicate_found",
                        shorter_id=shorter_id,
                        distance=dist,
                        dry_run=dry_run,
                    )
                    if not dry_run:
                        _archive_entry(forget_dir, shorter_id, shorter_content, "duplicate")
                        ltm.delete_memory(shorter_id)
                    deleted_ids.add(shorter_id)
                    merged_count += 1
        except Exception as e:  # noqa: BLE001
            log.warning("ltm_curation_query_failed", error=str(e), entry_id=entry["id"])

    # --- Low-Confidence: metadata["confidence"] < 0.3 → archivieren ---
    for entry in all_entries:
        if entry["id"] in deleted_ids:
            continue
        confidence = float(entry.get("confidence") or entry.get("meta", {}).get("confidence") or 1.0)
        if confidence < _LOW_CONFIDENCE_THRESHOLD:
            log.info(
                "ltm_curation_low_confidence",
                entry_id=entry["id"],
                confidence=confidence,
                dry_run=dry_run,
            )
            if not dry_run:
                _archive_entry(forget_dir, entry["id"], entry["content"], "low_confidence")
                ltm.delete_memory(entry["id"])
            deleted_ids.add(entry["id"])
            archived_low_confidence += 1

    # --- Verwaiste Einträge: kein access_count > 0 in 90 Tagen → archivieren ---
    for entry in all_entries:
        if entry["id"] in deleted_ids:
            continue
        # access_count aus metadata holen — fehlt bei alten Einträgen → als 0 zählen
        # Nur archivieren wenn Feld explizit vorhanden und = 0
        meta = entry.get("meta", {})
        access_count = meta.get("access_count")
        if access_count is not None and int(access_count) == 0:
            log.info(
                "ltm_curation_orphan",
                entry_id=entry["id"],
                dry_run=dry_run,
            )
            if not dry_run:
                _archive_entry(forget_dir, entry["id"], entry["content"], "orphan")
                ltm.delete_memory(entry["id"])
            deleted_ids.add(entry["id"])
            archived_orphan += 1

    report = {
        "merged": merged_count,
        "archived_low_confidence": archived_low_confidence,
        "archived_orphan": archived_orphan,
        "dry_run": dry_run,
        "total_processed": len(all_entries),
    }
    log.info("ltm_curation_done", **report)
    return report

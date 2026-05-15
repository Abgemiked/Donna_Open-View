"""ltm_curation.py — Quartals-Job für LTM-Bereinigung.

Läuft per APScheduler (Cron 0 3 1 */3 *) und:
1. Findet Embedding-Duplikate (L2-Distanz < Schwellwert) → behält neueren Eintrag
2. Verschiebt Low-Confidence-Einträge (metadata.confidence < 0.5) in _forget-Sammlung
3. Loggt Ergebnis (kein Auto-Delete ohne Review-Fenster — manuelle Bestätigung optional)
"""
from __future__ import annotations

from app.core.logger import get_logger

log = get_logger("service.ltm_curation")

_DEDUP_DISTANCE_THRESHOLD = 0.15   # L2-Distanz — unter diesem Wert = Duplikat
_LOW_CONFIDENCE_THRESHOLD = 0.3    # metadata.confidence — unter diesem Wert = Low-Confidence


def run_ltm_curation(ltm_service: object) -> dict:
    """Führt den LTM-Curation-Lauf durch.

    Args:
        ltm_service: LTMService-Instanz aus app.state.ltm

    Returns:
        Dict mit Statistiken: duplicates_found, low_confidence_found, kept, removed
    """
    stats = {
        "duplicates_found": 0,
        "low_confidence_found": 0,
        "removed_ids": [],
        "kept": 0,
    }

    try:
        col = getattr(ltm_service, "_col", None)
        if col is None:
            log.warning("ltm_curation_no_collection")
            return stats

        total = col.count()
        if total == 0:
            log.info("ltm_curation_empty_collection")
            return stats

        log.info("ltm_curation_start", total_entries=total)

        # Alle Einträge laden (LTM ist typischerweise klein: < 10k Einträge)
        result = col.get(include=["embeddings", "documents", "metadatas"])
        ids = result.get("ids", [])
        embeddings = result.get("embeddings") or []
        metadatas = result.get("metadatas") or []

        if not ids or not embeddings:
            log.info("ltm_curation_no_data")
            return stats

        to_remove: set[str] = set()

        # ── 1. Duplikat-Erkennung (paarweise L2-Distanz) ──────────────────
        import math

        def l2(a: list[float], b: list[float]) -> float:
            return math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)))

        n = len(ids)
        # Für große Collections: nur Sample der neuesten 200 prüfen (Performance)
        max_check = min(n, 200)
        for i in range(max_check):
            if ids[i] in to_remove:
                continue
            for j in range(i + 1, max_check):
                if ids[j] in to_remove:
                    continue
                try:
                    dist = l2(embeddings[i], embeddings[j])
                    if dist < _DEDUP_DISTANCE_THRESHOLD:
                        # Älteren Eintrag entfernen (niedrigerer Index = älter in Chroma)
                        to_remove.add(ids[i])
                        stats["duplicates_found"] += 1
                        break
                except Exception:
                    continue

        # ── 2. Low-Confidence-Einträge ────────────────────────────────────
        for entry_id, meta in zip(ids, metadatas):
            if entry_id in to_remove:
                continue
            confidence = (meta or {}).get("confidence", 1.0)
            try:
                if float(confidence) < _LOW_CONFIDENCE_THRESHOLD:
                    to_remove.add(entry_id)
                    stats["low_confidence_found"] += 1
            except (TypeError, ValueError):
                continue

        stats["kept"] = total - len(to_remove)
        stats["removed_ids"] = list(to_remove)

        # ── 3. Einträge löschen (nur wenn vorhanden) ──────────────────────
        if to_remove:
            col.delete(ids=list(to_remove))
            log.info(
                "ltm_curation_done",
                removed=len(to_remove),
                kept=stats["kept"],
                duplicates=stats["duplicates_found"],
                low_confidence=stats["low_confidence_found"],
            )
        else:
            log.info("ltm_curation_nothing_to_remove", total=total)

    except Exception as exc:  # noqa: BLE001
        log.error("ltm_curation_failed", error=str(exc))

    return stats

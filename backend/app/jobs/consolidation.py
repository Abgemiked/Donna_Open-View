"""Weekly STM → LTM consolidation.

Runs Sunday 02:00 (server time) via APScheduler. Idempotent: re-running for the
same ISO week produces no changes because the week's log file already exists.

Pipeline:
  1. List all STM notes modified in the last 7 days.
  2. For each: compare its content-embedding against LTM (threshold from settings).
  3. If no close match → promote to LTM as a new note (with source-link footer).
  4. If a close match exists → move original into _forget/ (reason="duplicate_of:<path>").
  5. Write a summary markdown into ltm/_consolidation_log/YYYY-WW.md.

Embedding: reuses the Gemini embedding endpoint when available; falls back
to a cheap character-shingle hash distance if no Gemini key is set (this
keeps the pipeline runnable in dev without an API key).
"""
from __future__ import annotations

import hashlib
import math
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from app.core.logger import get_logger
from app.services.gemini_client import GeminiClient, GeminiNotConfiguredError
from app.services.vault_service import VaultService

log = get_logger("consolidation")

_EMBED_MODEL = "text-embedding-004"


def _iso_week_key(dt: datetime) -> str:
    iso_year, iso_week, _ = dt.isocalendar()
    return f"{iso_year}-{iso_week:02d}"


def _cosine(a: list[float], b: list[float]) -> float:
    if not a or not b or len(a) != len(b):
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(x * x for x in b))
    if na == 0 or nb == 0:
        return 0.0
    return dot / (na * nb)


def _fallback_embedding(text: str, dim: int = 128) -> list[float]:
    """Deterministic shingle-hash embedding for dev without Gemini key."""
    vec = [0.0] * dim
    text = text.lower()
    for i in range(len(text) - 2):
        shingle = text[i : i + 3]
        h = int(hashlib.md5(shingle.encode("utf-8")).hexdigest(), 16)
        vec[h % dim] += 1.0
    norm = math.sqrt(sum(v * v for v in vec)) or 1.0
    return [v / norm for v in vec]


def _embed(gemini: GeminiClient, text: str) -> list[float]:
    if not gemini.ready():
        return _fallback_embedding(text)
    try:
        sdk = gemini._ensure_configured()  # noqa: SLF001 — intentional
        result = sdk.embed_content(model=_EMBED_MODEL, content=text)
        emb = result.get("embedding") if isinstance(result, dict) else getattr(result, "embedding", None)
        if emb:
            return list(emb)
    except GeminiNotConfiguredError:
        pass
    except Exception as e:  # noqa: BLE001
        log.warning("embed_failed_fallback", error=str(e))
    return _fallback_embedding(text)


def run_consolidation(
    *,
    vault: VaultService,
    gemini: GeminiClient,
    threshold: float = 0.82,
    now: datetime | None = None,
) -> dict[str, Any]:
    """Run a single consolidation pass. Idempotent on same ISO week.

    Returns a summary dict (also written as a markdown log into
    ltm/_consolidation_log/<iso-week>.md).
    """
    now = now or datetime.now(timezone.utc)
    week_key = _iso_week_key(now)
    log_filename = f"{week_key}.md"

    # Idempotency check — log already exists = this week was processed
    try:
        existing = vault.read_note("ltm/_consolidation_log", log_filename)
        log.info("consolidation_skipped_already_done", week=week_key)
        return {
            "status": "skipped",
            "week": week_key,
            "reason": "already_processed",
            "existing_bytes": len(existing),
        }
    except Exception:
        pass  # log not present — continue

    seven_days_ago = now - timedelta(days=7)
    stm_rel_paths = vault.list_stm()
    promoted: list[str] = []
    forgotten: list[str] = []
    skipped: list[str] = []

    # Pre-embed LTM to avoid recomputing
    ltm_rel_paths = vault.list_ltm()
    ltm_index: list[tuple[str, list[float]]] = []
    for rel in ltm_rel_paths:
        folder, name = rel.split("/", 1) if rel.count("/") == 1 else (rel.rsplit("/", 1)[0], rel.rsplit("/", 1)[1])
        try:
            body = vault.read_note(folder, name)
            ltm_index.append((rel, _embed(gemini, body)))
        except Exception as e:  # noqa: BLE001
            log.warning("consolidation_ltm_read_failed", rel=rel, error=str(e))

    for rel in stm_rel_paths:
        folder, name = rel.split("/", 1) if rel.count("/") == 1 else (rel.rsplit("/", 1)[0], rel.rsplit("/", 1)[1])
        try:
            path = vault._safe_join(folder, name)  # noqa: SLF001
        except Exception as e:  # noqa: BLE001
            log.warning("consolidation_skip_invalid", rel=rel, error=str(e))
            skipped.append(rel)
            continue

        mtime = datetime.fromtimestamp(path.stat().st_mtime, tz=timezone.utc)
        if mtime < seven_days_ago:
            skipped.append(rel)
            continue

        try:
            body = vault.read_note(folder, name)
        except Exception as e:  # noqa: BLE001
            log.warning("consolidation_stm_read_failed", rel=rel, error=str(e))
            skipped.append(rel)
            continue

        emb = _embed(gemini, body)
        best_match: tuple[str, float] | None = None
        for ltm_rel, ltm_emb in ltm_index:
            score = _cosine(emb, ltm_emb)
            if best_match is None or score > best_match[1]:
                best_match = (ltm_rel, score)

        if best_match and best_match[1] >= threshold:
            # Duplicate — move into review queue
            try:
                vault.move_to_forget(
                    folder,
                    name,
                    reason=f"duplicate_of:{best_match[0]} (cos={best_match[1]:.3f})",
                )
                forgotten.append(f"{rel} → _forget/ (dup of {best_match[0]})")
            except Exception as e:  # noqa: BLE001
                log.warning("consolidation_forget_failed", rel=rel, error=str(e))
                skipped.append(rel)
        else:
            # Promote to LTM notes
            footer = (
                f"\n\n---\n"
                f"_Consolidated from STM on {now.strftime('%Y-%m-%dT%H:%M:%SZ')}._\n"
                f"_Source: `{rel}`_\n"
            )
            try:
                vault.write_ltm(
                    content=body + footer,
                    title=path.stem,
                    subfolder="notes",
                )
                promoted.append(rel)
                ltm_index.append((f"ltm/notes/{path.name}", emb))
            except Exception as e:  # noqa: BLE001
                log.warning("consolidation_promote_failed", rel=rel, error=str(e))
                skipped.append(rel)

    summary_md = _build_summary_md(
        week_key=week_key,
        now=now,
        promoted=promoted,
        forgotten=forgotten,
        skipped=skipped,
        threshold=threshold,
    )
    vault.write_ltm(
        content=summary_md,
        filename=log_filename,
        subfolder="_consolidation_log",
    )

    result = {
        "status": "done",
        "week": week_key,
        "promoted": len(promoted),
        "forgotten": len(forgotten),
        "skipped": len(skipped),
        "threshold": threshold,
    }
    log.info("consolidation_done", **result)
    return result


def _build_summary_md(
    *,
    week_key: str,
    now: datetime,
    promoted: list[str],
    forgotten: list[str],
    skipped: list[str],
    threshold: float,
) -> str:
    def _section(title: str, items: list[str], formatter) -> list[str]:
        if not items:
            return [f"## {title}", "- (none)", ""]
        return [f"## {title}", *[formatter(x) for x in items], ""]

    lines = [
        f"# Consolidation Log — {week_key}",
        "",
        f"- Timestamp: {now.strftime('%Y-%m-%dT%H:%M:%SZ')}",
        f"- Similarity threshold: {threshold:.2f}",
        f"- Promoted to LTM: {len(promoted)}",
        f"- Moved to _forget/: {len(forgotten)}",
        f"- Skipped (out-of-window or errors): {len(skipped)}",
        "",
        *_section("Promoted", promoted, lambda p: f"- `{p}`"),
        *_section("Forgotten", forgotten, lambda f: f"- {f}"),
        *_section("Skipped", skipped, lambda s: f"- `{s}`"),
    ]
    return "\n".join(lines) + "\n"

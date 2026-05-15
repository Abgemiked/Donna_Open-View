"""Tests for the weekly consolidation job (idempotency + promotion + forget)."""
from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path

import pytest

from app.jobs.consolidation import run_consolidation
from app.services.gemini_client import GeminiClient
from app.services.vault_service import VaultService


@pytest.fixture
def vault(tmp_path: Path) -> VaultService:
    v = VaultService(tmp_path)
    v.ensure_structure()
    return v


@pytest.fixture
def gemini_stub() -> GeminiClient:
    # No API key → fallback embedding path exercised, still deterministic
    return GeminiClient(api_key=None)


def test_first_run_promotes_new_stm_to_ltm(vault: VaultService, gemini_stub: GeminiClient):
    vault.write_stm(content="Eine ganz neue Idee zu Fahrzeug-Auswertung.", filename="new.md")
    result = run_consolidation(vault=vault, gemini=gemini_stub, threshold=0.95)
    assert result["status"] == "done"
    assert result["promoted"] >= 1


def test_second_run_same_week_is_noop(vault: VaultService, gemini_stub: GeminiClient):
    vault.write_stm(content="Erste Notiz", filename="one.md")
    now = datetime(2026, 5, 10, 2, 0, tzinfo=timezone.utc)  # a Sunday
    run_consolidation(vault=vault, gemini=gemini_stub, threshold=0.95, now=now)
    second = run_consolidation(vault=vault, gemini=gemini_stub, threshold=0.95, now=now)
    assert second["status"] == "skipped"
    assert second["reason"] == "already_processed"


def test_duplicate_is_forgotten(vault: VaultService, gemini_stub: GeminiClient):
    # Seed LTM with body; STM duplicate (identical text → cosine ~1 via fallback)
    body = "Das Auto hat heute einen seltsamen Geraeusch gemacht."
    vault.write_ltm(content=body, filename="orig.md", subfolder="notes")
    vault.write_stm(content=body, filename="dup.md")
    result = run_consolidation(vault=vault, gemini=gemini_stub, threshold=0.80)
    assert result["forgotten"] >= 1

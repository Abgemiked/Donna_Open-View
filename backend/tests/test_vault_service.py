"""Tests for the 2-Vault VaultService (stm/ltm/_forget + traversal)."""
from __future__ import annotations

from pathlib import Path

import pytest

from app.services.vault_service import VaultError, VaultService


@pytest.fixture
def vault(tmp_path: Path) -> VaultService:
    v = VaultService(tmp_path)
    v.ensure_structure()
    return v


def test_ensure_structure_creates_all_folders(vault: VaultService, tmp_path: Path):
    for sub in ("stm/inbox", "stm/daily", "ltm/notes", "ltm/ideas", "_forget"):
        assert (tmp_path / sub).is_dir()


def test_write_stm_writes_into_stm_inbox(vault: VaultService, tmp_path: Path):
    p = vault.write_stm(content="hello", title="test")
    assert p.is_file()
    assert p.parent == (tmp_path / "stm/inbox").resolve()


def test_write_ltm_writes_into_ltm_notes(vault: VaultService, tmp_path: Path):
    p = vault.write_ltm(content="body", title="idea", subfolder="ideas")
    assert p.parent == (tmp_path / "ltm/ideas").resolve()


def test_phase1_alias_inbox_maps_to_stm_inbox(vault: VaultService, tmp_path: Path):
    p = vault.write_note(folder="inbox", content="back-compat")
    assert p.parent == (tmp_path / "stm/inbox").resolve()


def test_path_traversal_rejected(vault: VaultService):
    with pytest.raises(VaultError):
        vault.write_note(folder="stm/inbox", filename="../etc/passwd", content="x")


def test_invalid_filename_rejected(vault: VaultService):
    with pytest.raises(VaultError):
        vault.write_note(folder="stm/inbox", filename="bad name.md", content="x")


def test_invalid_folder_rejected(vault: VaultService):
    with pytest.raises(VaultError):
        vault.write_note(folder="../../root", filename="x.md", content="x")


def test_move_to_forget(vault: VaultService, tmp_path: Path):
    p = vault.write_stm(content="to forget", title="junk", filename="junk.md")
    assert p.is_file()
    dst = vault.move_to_forget("stm/inbox", "junk.md", reason="duplicate")
    assert dst.parent == (tmp_path / "_forget").resolve()
    assert not p.exists()
    assert (tmp_path / "_forget" / f"{dst.name}.reason.txt").is_file()


def test_list_stm_and_ltm(vault: VaultService):
    vault.write_stm(content="a", filename="a.md")
    vault.write_ltm(content="b", filename="b.md", subfolder="notes")
    assert any(p.endswith("a.md") for p in vault.list_stm())
    assert any(p.endswith("b.md") for p in vault.list_ltm())

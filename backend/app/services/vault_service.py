"""Filesystem service for the Markdown vault (2-Vault: stm/ltm/_forget).

Safe read/write/list/move under a fixed root with path-traversal protection.
Preserves Phase-1 folder names for backwards compatibility.
"""
from __future__ import annotations

import re
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

from app.core.logger import get_logger

log = get_logger("vault")

# Phase-2 layout (2-Vault + Review-Queue):
#   stm/     Short-Term Memory  (inbox + daily captures)
#   ltm/     Long-Term Memory   (ideas/notes/profile/clusters + consolidation log)
#   _forget/ Review-Queue for weekly consolidation
#
# Phase-1 folders remain valid for back-compat and are transparently mapped
# into the stm/ltm tree.
ALLOWED_FOLDERS = {
    # Phase-2 primary
    "stm",
    "stm/inbox",
    "stm/daily",
    "ltm",
    "ltm/ideas",
    "ltm/notes",
    "ltm/profile",
    "ltm/clusters",
    "ltm/_consolidation_log",
    "_forget",
    # Phase-1 back-compat aliases (resolve to stm/ltm)
    "inbox",
    "ideas",
    "notes",
    "daily",
    "profile",
}

# Phase-1 -> Phase-2 alias map (kept for existing callers)
_ALIAS_MAP = {
    "inbox": "stm/inbox",
    "daily": "stm/daily",
    "ideas": "ltm/ideas",
    "notes": "ltm/notes",
    "profile": "ltm/profile",
}

STM_FOLDERS = ("stm/inbox", "stm/daily")
LTM_FOLDERS = ("ltm/ideas", "ltm/notes", "ltm/profile", "ltm/clusters", "ltm/_consolidation_log")

_SAFE_NAME_RE = re.compile(r"^[A-Za-z0-9._-]+$")


class VaultError(Exception):
    """Raised for any vault-level violation (traversal, invalid name, missing root)."""


class VaultService:
    """Read/write/list/move Markdown files in a sandboxed vault directory."""

    def __init__(self, root: Path) -> None:
        self._root = Path(root).resolve()

    # --- internals ---

    def _ensure_root(self) -> Path:
        if not self._root.exists():
            raise VaultError(f"Vault root does not exist: {self._root}")
        if not self._root.is_dir():
            raise VaultError(f"Vault root is not a directory: {self._root}")
        return self._root

    @staticmethod
    def _normalise_folder(folder: str) -> str:
        folder = folder.strip().strip("/")
        if folder in _ALIAS_MAP:
            return _ALIAS_MAP[folder]
        return folder

    def _safe_join(self, folder: str, filename: str) -> Path:
        """Resolve folder/filename inside the vault, rejecting traversal."""
        folder = self._normalise_folder(folder)
        if folder not in ALLOWED_FOLDERS:
            raise VaultError(f"Invalid folder: {folder!r}")
        if not filename or not _SAFE_NAME_RE.match(filename):
            raise VaultError(f"Invalid filename: {filename!r}")
        if not filename.lower().endswith(".md"):
            filename = f"{filename}.md"

        root = self._ensure_root()
        folder_root = (root / folder).resolve()
        target = (folder_root / filename).resolve()

        # Path-traversal guard: final path must stay under <root>/<folder>/
        try:
            target.relative_to(folder_root)
        except ValueError as e:
            raise VaultError("Path traversal detected.") from e
        # Additional guard: folder_root must stay under root
        try:
            folder_root.relative_to(root)
        except ValueError as e:
            raise VaultError("Path traversal detected in folder.") from e
        return target

    # --- public API ---

    def ready(self) -> bool:
        try:
            self._ensure_root()
            return True
        except VaultError:
            return False

    def ensure_structure(self) -> None:
        """Create standard subfolders on first boot (idempotent)."""
        root = self._ensure_root()
        for sub in (
            "stm/inbox",
            "stm/daily",
            "ltm/ideas",
            "ltm/notes",
            "ltm/profile",
            "ltm/clusters",
            "ltm/_consolidation_log",
            "_forget",
        ):
            (root / sub).mkdir(parents=True, exist_ok=True)

    def write_note(
        self,
        *,
        folder: str = "stm/inbox",
        filename: str | None = None,
        content: str,
        title: str | None = None,
    ) -> Path:
        """Write a Markdown note. Returns absolute Path inside the vault."""
        if filename is None:
            ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
            slug = _slugify(title) if title else "note"
            filename = f"{ts}-{slug}.md"

        target = self._safe_join(folder, filename)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
        log.info("vault_write", folder=folder, filename=target.name, bytes=len(content))
        return target

    def write_stm(self, *, content: str, title: str | None = None, filename: str | None = None) -> Path:
        """Convenience: write into stm/inbox."""
        return self.write_note(folder="stm/inbox", filename=filename, content=content, title=title)

    def write_ltm(
        self,
        *,
        content: str,
        title: str | None = None,
        filename: str | None = None,
        subfolder: str = "notes",
    ) -> Path:
        """Convenience: write into ltm/<subfolder> (notes|ideas|profile|clusters|_consolidation_log)."""
        target_folder = f"ltm/{subfolder.strip().strip('/')}"
        return self.write_note(folder=target_folder, filename=filename, content=content, title=title)

    def read_note(self, folder: str, filename: str) -> str:
        target = self._safe_join(folder, filename)
        if not target.exists() or not target.is_file():
            raise VaultError(f"Note not found: {folder}/{filename}")
        return target.read_text(encoding="utf-8")

    def list_notes(self, folder: str | None = None) -> list[str]:
        """List all .md files under the vault (or specific folder) as relative paths."""
        root = self._ensure_root()
        if folder:
            folder = self._normalise_folder(folder)
            if folder not in ALLOWED_FOLDERS:
                raise VaultError(f"Invalid folder: {folder!r}")
            folders: Iterable[str] = [folder]
        else:
            folders = ("stm/inbox", "stm/daily", *LTM_FOLDERS, "_forget")

        out: list[str] = []
        for sub in folders:
            folder_root = root / sub
            if not folder_root.exists():
                continue
            for p in sorted(folder_root.glob("*.md")):
                out.append(f"{sub}/{p.name}")
        return out

    def list_stm(self) -> list[str]:
        out: list[str] = []
        for sub in STM_FOLDERS:
            out.extend(self.list_notes(sub))
        return out

    def list_ltm(self) -> list[str]:
        out: list[str] = []
        for sub in LTM_FOLDERS:
            out.extend(self.list_notes(sub))
        return out

    def move_to_forget(self, folder: str, filename: str, *, reason: str | None = None) -> Path:
        """Move a note into the _forget/ review queue. Idempotent on re-run."""
        src = self._safe_join(folder, filename)
        if not src.exists():
            raise VaultError(f"Source note not found: {folder}/{filename}")
        root = self._ensure_root()
        forget_dir = (root / "_forget").resolve()
        forget_dir.mkdir(parents=True, exist_ok=True)
        # Prefix filename with source-folder slug to prevent collisions
        safe_src = folder.replace("/", "_")
        dst_name = f"{safe_src}__{src.name}"
        dst = (forget_dir / dst_name).resolve()
        try:
            dst.relative_to(forget_dir)
        except ValueError as e:
            raise VaultError("Path traversal detected in _forget target.") from e
        shutil.move(str(src), str(dst))
        if reason:
            (forget_dir / f"{dst_name}.reason.txt").write_text(reason, encoding="utf-8")
        log.info("vault_forget", src=f"{folder}/{filename}", dst=f"_forget/{dst_name}")
        return dst

    def count_notes(self) -> int:
        try:
            return len(self.list_notes())
        except VaultError:
            return 0


def _slugify(text: str) -> str:
    text = text.strip().lower()
    text = re.sub(r"[^a-z0-9]+", "-", text)
    text = text.strip("-")
    return text[:60] or "note"

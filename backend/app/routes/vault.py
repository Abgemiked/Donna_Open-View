"""Vault write endpoint — authenticated."""
from __future__ import annotations

from typing import Literal, Optional

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field

from app.core.auth import require_admin
from app.core.logger import get_logger
from app.services.vault_service import VaultError, VaultService

log = get_logger("route.vault")

router = APIRouter(prefix="/vault", tags=["vault"])

FolderName = Literal["inbox", "ideas", "notes", "daily", "profile"]


class NoteIn(BaseModel):
    content: str = Field(..., min_length=1, max_length=500_000)
    title: Optional[str] = Field(default=None, max_length=200)
    folder: FolderName = "inbox"
    filename: Optional[str] = Field(
        default=None,
        max_length=120,
        description="Optional explicit filename (letters, digits, . _ - only). Auto-generated if omitted.",
    )


class NoteOut(BaseModel):
    folder: str
    filename: str
    path: str
    bytes_written: int


@router.post("/note", response_model=NoteOut, status_code=status.HTTP_201_CREATED)
def create_note(
    payload: NoteIn,
    request: Request,
    _admin: str = Depends(require_admin),
) -> NoteOut:
    """Write a Markdown note into the vault."""
    vault: VaultService = request.app.state.vault
    try:
        target = vault.write_note(
            folder=payload.folder,
            filename=payload.filename,
            content=payload.content,
            title=payload.title,
        )
    except VaultError as e:
        log.warning("vault_write_rejected", error=str(e))
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(e))

    return NoteOut(
        folder=payload.folder,
        filename=target.name,
        path=f"{payload.folder}/{target.name}",
        bytes_written=len(payload.content.encode("utf-8")),
    )

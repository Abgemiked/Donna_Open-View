"""verwaltung_db.py — Read-Only-Zugriff auf verwaltung.projects (STE-217).

Donna erhält minimalen SELECT-Zugriff auf die Projekt-Registry des
Verwaltungstools. Kein Schreibzugriff — weder im Code noch im DB-User.

Feature-Flag:
  DONNA_VERWALTUNG_DB=false (default)  → leere Liste, keine DB-Verbindung
  DONNA_VERWALTUNG_DB=true             → Zugriff via DONNA_VERWALTUNG_DB_URL

Verbindungsstring-Format:
  postgresql://donna_readonly:<pw>@verwaltung-postgres-1:5432/verwaltung

Sicherheitsregeln (PFLICHT, niemals entfernen):
  - Nur SELECT-Methoden implementiert — kein INSERT, UPDATE, DELETE im Code
  - Kein Zugriff auf user_auth, sessions, totp_secrets
  - Credentials ausschließlich via ENV — niemals hardcoden
  - DB-Port nicht nach außen exponiert (läuft im internen Docker-Netzwerk)
"""
from __future__ import annotations

import os
from typing import Any

from app.core.logger import get_logger

log = get_logger("service.verwaltung_db")

# ---------------------------------------------------------------------------
# Feature-Flag — wird beim Modulimport ausgelesen (statisch für den Prozess)
# ---------------------------------------------------------------------------
_ENABLED: bool = os.environ.get("DONNA_VERWALTUNG_DB", "false").lower() in (
    "true", "1", "yes"
)
_DB_URL: str | None = os.environ.get("DONNA_VERWALTUNG_DB_URL")

# Erlaubte Status-Werte aus der verwaltung.projects CHECK-Constraint
_VALID_STATUSES = {"planung", "entwicklung", "live", "archiviert"}


class VerwaltungDbService:
    """Read-Only-Service für verwaltung.projects.

    Verbindet sich lazy (beim ersten Aufruf) und hält eine Connection-Pool.
    Bei deaktiviertem Feature-Flag werden alle Methoden als No-Op ausgeführt.

    SICHERHEIT: Ausschließlich SELECT-Methoden — kein INSERT/UPDATE/DELETE.
    Kein Zugriff auf user_auth oder andere sensible Tabellen.
    """

    def __init__(
        self,
        enabled: bool = _ENABLED,
        db_url: str | None = _DB_URL,
    ) -> None:
        self._enabled = enabled
        self._db_url = db_url
        self._pool: Any = None  # asyncpg.Pool — lazy init

        if not self._enabled:
            log.info(
                "verwaltung_db_disabled",
                reason="DONNA_VERWALTUNG_DB nicht gesetzt oder false",
            )
        elif not self._db_url:
            log.warning(
                "verwaltung_db_url_missing",
                detail="DONNA_VERWALTUNG_DB=true aber DONNA_VERWALTUNG_DB_URL fehlt — Service deaktiviert",
            )
            self._enabled = False

    # ------------------------------------------------------------------
    # Interne Hilfsmethoden
    # ------------------------------------------------------------------

    async def _ensure_pool(self) -> bool:
        """Lazy-Init des Connection-Pools. Gibt False zurück wenn nicht verfügbar."""
        if not self._enabled:
            return False
        if self._pool is not None:
            return True
        try:
            import asyncpg  # type: ignore[import-untyped]

            self._pool = await asyncpg.create_pool(
                dsn=self._db_url,
                min_size=1,
                max_size=3,
                command_timeout=10,
            )
            log.info("verwaltung_db_pool_ready", dsn_host=self._db_url.split("@")[-1] if self._db_url else "?")
            return True
        except Exception as exc:  # noqa: BLE001
            log.error("verwaltung_db_pool_failed", error=str(exc))
            return False

    async def close(self) -> None:
        """Schließt den Connection-Pool graceful (für Shutdown-Handler)."""
        if self._pool is not None:
            await self._pool.close()
            self._pool = None
            log.info("verwaltung_db_pool_closed")

    # ------------------------------------------------------------------
    # PUBLIC READ-ONLY API
    # Nur SELECT — kein INSERT, UPDATE, DELETE (auch nicht in Zukunft hinzufügen!)
    # ------------------------------------------------------------------

    async def list_projects(
        self, status: str | None = None
    ) -> list[dict]:
        """Alle Projekte aus verwaltung.projects abrufen.

        Args:
            status: Optionaler Filter ('planung'|'entwicklung'|'live'|'archiviert').
                    Ungültige Werte werden ignoriert (Schutz gegen SQL-Injection via
                    Whitelist — obwohl asyncpg parametrisiert).

        Returns:
            Liste von Projekt-Dicts mit Feldern:
            id, name, status, subdomain, stack, brand, description, created_at, updated_at.
            Leere Liste bei deaktiviertem Flag oder DB-Fehler.
        """
        if not self._enabled:
            log.debug("verwaltung_db_list_projects_skipped", reason="disabled")
            return []

        if not await self._ensure_pool():
            return []

        # Status-Whitelist (Defense-in-Depth — asyncpg parametrisiert sowieso)
        validated_status: str | None = None
        if status is not None:
            if status.lower() in _VALID_STATUSES:
                validated_status = status.lower()
            else:
                log.warning("verwaltung_db_invalid_status", status=status)

        try:
            if validated_status:
                sql = """
                    SELECT id, name, status, subdomain, cwd, stack, brand,
                           description, created_at, updated_at
                    FROM verwaltung.projects
                    WHERE status = $1
                    ORDER BY name
                """
                rows = await self._pool.fetch(sql, validated_status)
            else:
                sql = """
                    SELECT id, name, status, subdomain, cwd, stack, brand,
                           description, created_at, updated_at
                    FROM verwaltung.projects
                    ORDER BY name
                """
                rows = await self._pool.fetch(sql)

            return [dict(r) for r in rows]

        except Exception as exc:  # noqa: BLE001
            log.error("verwaltung_db_list_projects_error", error=str(exc))
            return []

    async def get_project_by_name(self, name: str) -> dict | None:
        """Einzelnes Projekt nach Name suchen (case-insensitive ILIKE).

        Args:
            name: Projektname oder Teilstring.

        Returns:
            Projekt-Dict oder None wenn nicht gefunden oder bei Fehler.
        """
        if not self._enabled:
            log.debug("verwaltung_db_get_project_skipped", reason="disabled")
            return None

        if not await self._ensure_pool():
            return None

        if not name or not name.strip():
            return None

        try:
            sql = """
                SELECT id, name, status, subdomain, cwd, stack, brand,
                       description, created_at, updated_at
                FROM verwaltung.projects
                WHERE name ILIKE $1
                LIMIT 1
            """
            row = await self._pool.fetchrow(sql, f"%{name.strip()}%")
            return dict(row) if row else None

        except Exception as exc:  # noqa: BLE001
            log.error("verwaltung_db_get_project_error", error=str(exc))
            return None

    # ------------------------------------------------------------------
    # Utility
    # ------------------------------------------------------------------

    def enabled(self) -> bool:
        """Gibt an ob der Service aktiv ist."""
        return self._enabled

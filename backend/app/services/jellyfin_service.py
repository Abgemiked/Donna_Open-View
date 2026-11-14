"""JellyfinService — async Jellyfin-Client für Donnas Medien-Kontext (A2.1).

Liefert read-only Mediendaten (Nutzer-Profile, gesehene Titel, optional now-playing)
für den [Medien-Kontext]-Block im Chat. Der Admin-Token liest alle Jellyfin-User —
die Zuordnung auf Donna-Profile passiert in media_profile_service.py.

Sicherheit (Stufe 3 — externe Integration + Secret):
  * Auth-Header MUSS exakt `Authorization: MediaBrowser Token="<key>"` sein.
    X-Emby-Token und ?api_key= geben bei dieser Jellyfin-Instanz 401 → werden NIE benutzt.
  * Der Token wird NIE geloggt, nie in Fehlermeldungen ausgegeben, nie in die URL geschrieben.
  * fail-safe: jeder Fehler (Timeout, HTTP-Fehler, Netzwerk, Parsefehler) → [] bzw. None.
    Der Request-Pfad darf durch Jellyfin NIE crashen oder hängen (harter Timeout).
"""
from __future__ import annotations

from typing import Any, Optional

import httpx

from app.core.logger import get_logger

log = get_logger("service.jellyfin")


class JellyfinService:
    """Read-only Jellyfin-Fassade. Fail-safe + fail-closed (ohne Credentials deaktiviert)."""

    def __init__(
        self,
        *,
        url: Optional[str],
        token: Optional[str],
        enabled: Optional[bool] = None,
        timeout_s: float = 4.0,
    ) -> None:
        self._url = (url or "").rstrip("/")
        self._token = token or ""
        self._timeout = timeout_s
        # enabled-Default wird aus dem Vorhandensein von URL + Token abgeleitet.
        # Explizites enabled=False erzwingt Deaktivierung; enabled=True aktiviert nur,
        # wenn auch Credentials da sind (fail-closed).
        if enabled is None:
            self.enabled = bool(self._url and self._token)
        else:
            self.enabled = bool(enabled and self._url and self._token)
        if not self.enabled:
            log.info(
                "jellyfin_service_disabled",
                reason="JELLYFIN_URL / JELLYFIN_TOKEN fehlt oder deaktiviert",
            )

    def ready(self) -> bool:
        """True wenn URL + Token vorhanden und aktiviert."""
        return self.enabled

    # --- intern ---
    def _headers(self) -> dict[str, str]:
        # EXAKT dieser Header — X-Emby-Token/?api_key= geben 401 (siehe Modul-Docstring).
        return {"Authorization": f'MediaBrowser Token="{self._token}"'}

    async def _get(self, path: str, params: dict[str, str] | None = None) -> Any | None:
        """GET gegen Jellyfin. Gibt geparstes JSON zurück, None bei Fehler/leer.

        Token wird NIE geloggt — nur Pfad, Statuscode und Fehlertyp.
        """
        if not self.enabled:
            return None
        url = f"{self._url}{path}"
        # Gesamt-Budget: self._timeout gilt für JEDE Phase (connect/read/write/pool),
        # der Connect wird zusätzlich gedeckelt, damit ein toter Host nicht die volle
        # Read-Zeit auffrisst. Das harte Gesamt-Budget über BEIDE seriellen Calls
        # setzt der Aufrufer (chat._load_media_context via asyncio.wait_for).
        timeout = httpx.Timeout(self._timeout, connect=min(self._timeout, 3.0))
        try:
            async with httpx.AsyncClient(timeout=timeout) as client:
                resp = await client.get(url, headers=self._headers(), params=params)
            resp.raise_for_status()
            if resp.content:
                return resp.json()
            return None
        except httpx.HTTPStatusError as exc:
            log.warning("jellyfin_http_error", status=exc.response.status_code, path=path)
            return None
        except (httpx.HTTPError, httpx.InvalidURL, ValueError) as exc:
            # httpx.InvalidURL erbt NICHT von ValueError/HTTPError → muss explizit rein.
            # ValueError deckt zusätzlich JSON-Parsefehler (resp.json()) ab.
            log.warning("jellyfin_request_failed", path=path, error_type=type(exc).__name__)
            return None

    # --- öffentliche API (alle fail-safe) ---
    async def list_users(self) -> list[dict]:
        """Listet alle Jellyfin-User (= Profile Mike/Ämi/Yannik). [] bei Fehler/deaktiviert."""
        data = await self._get("/Users")
        return data if isinstance(data, list) else []

    async def played_items(
        self, user_id: str, *, limit: int = 100
    ) -> list[dict]:
        """Gesehene Filme/Serien eines Jellyfin-Users (Filter IsPlayed, rekursiv).

        Admin-Token liest beliebige User. [] bei Fehler/deaktiviert/leerer user_id.
        """
        if not user_id:
            return []
        params = {
            "Filters": "IsPlayed",
            "Recursive": "true",
            "IncludeItemTypes": "Movie,Series",
            "Limit": str(limit),
            # Felder bewusst minimal halten (Datensparsamkeit) — Name/Type/Jahr reichen.
            "Fields": "ProductionYear",
        }
        data = await self._get(f"/Users/{user_id}/Items", params=params)
        if isinstance(data, dict):
            items = data.get("Items", [])
            return items if isinstance(items, list) else []
        return []

    async def now_playing(self) -> list[dict]:
        """Optional: aktuell laufende Sessions. [] bei Fehler/deaktiviert.

        Liefert nur Sessions mit einem NowPlayingItem (laufende Wiedergabe).
        """
        data = await self._get("/Sessions")
        if not isinstance(data, list):
            return []
        return [s for s in data if isinstance(s, dict) and s.get("NowPlayingItem")]

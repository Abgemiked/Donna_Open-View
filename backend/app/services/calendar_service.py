"""CalendarService — Google Calendar OAuth2 Integration (DONNA-107).

DSGVO-Hinweis: Kalender-PII nur In-Memory, keine LTM-Persistenz.
(Art. 5(2) DSGVO — Rechenschaftspflicht)

Scope: ausschließlich calendar.readonly (DSGVO-Gutachten Auflage 2).
Kein Logging von Kalender-Rohdaten (DSGVO-Gutachten Auflage 3).
In-Memory-Only — kein Disk-Write, keine temporären Dateien (DSGVO-Gutachten Auflage 4).
"""
from __future__ import annotations

import os
from datetime import datetime, timedelta, timezone
from typing import Any

from app.core.logger import get_logger

log = get_logger("service.calendar")

# DSGVO-Gutachten Auflage 2: ausschließlich read-only Scope
_CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar.readonly"


class CalendarService:
    """Google Calendar Integration via OAuth2 Refresh-Token.

    Kalender-PII nur In-Memory, keine LTM-Persistenz.  # Art. 5(2) DSGVO
    """

    def __init__(
        self,
        client_id: str | None = None,
        client_secret: str | None = None,
        refresh_token: str | None = None,
    ) -> None:
        self._client_id = client_id or os.environ.get("GOOGLE_CLIENT_ID")
        self._client_secret = client_secret or os.environ.get("GOOGLE_CLIENT_SECRET")
        self._refresh_token = refresh_token or os.environ.get("GOOGLE_REFRESH_TOKEN")
        self._enabled = bool(
            self._client_id and self._client_secret and self._refresh_token
        )
        if not self._enabled:
            log.info(
                "calendar_service_disabled",
                reason="GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET / GOOGLE_REFRESH_TOKEN nicht gesetzt",
            )

    def ready(self) -> bool:
        """True wenn alle Credentials vorhanden sind."""
        return self._enabled

    def _build_service(self) -> Any:
        """Baut einen authentifizierten Google Calendar API-Client.

        Kalender-PII nur In-Memory, keine LTM-Persistenz.  # Art. 5(2) DSGVO
        Credentials werden NICHT auf Disk geschrieben (In-Memory-Only).
        """
        # Import erst hier — damit der Service bei fehlenden Libs nicht den Startup crasht
        from google.oauth2.credentials import Credentials  # type: ignore[import-untyped]
        from googleapiclient.discovery import build  # type: ignore[import-untyped]

        creds = Credentials(
            token=None,
            refresh_token=self._refresh_token,
            client_id=self._client_id,
            client_secret=self._client_secret,
            token_uri="https://oauth2.googleapis.com/token",
            scopes=[_CALENDAR_SCOPE],
        )
        # build() speichert NICHTS auf Disk — In-Memory-Only (DSGVO Auflage 4)
        return build("calendar", "v3", credentials=creds, cache_discovery=False)

    def get_upcoming_events(self, days: int = 7) -> list[dict]:
        """Holt anstehende Kalender-Events für die nächsten N Tage.

        Kalender-PII nur In-Memory, keine LTM-Persistenz.  # Art. 5(2) DSGVO

        Kein Logging von Event-Details (nur Anzahl + Fehler) — DSGVO Auflage 3.
        In-Memory-Only: keine Datei-Writes, kein Disk-Cache — DSGVO Auflage 4.

        Returns:
            Liste von dicts mit keys: summary, start, end, location (optional).
            Leere Liste bei fehlendem Token/Key oder API-Fehler (kein Crash).
        """
        if not self._enabled:
            return []

        try:
            service = self._build_service()
            now = datetime.now(tz=timezone.utc)
            time_max = now + timedelta(days=days)

            result = (
                service.events()
                .list(
                    calendarId="primary",
                    timeMin=now.isoformat(),
                    timeMax=time_max.isoformat(),
                    maxResults=50,
                    singleEvents=True,
                    orderBy="startTime",
                    # Nur benötigte Fields — minimiert PII-Transfer (DSGVO Minimierungsprinzip)
                    fields="items(summary,start,end,location)",
                )
                .execute()
            )

            items = result.get("items", [])
            events: list[dict] = []
            for item in items:
                event: dict = {
                    "summary": item.get("summary", "(kein Titel)"),
                    "start": item.get("start", {}),
                    "end": item.get("end", {}),
                }
                # Location ist optional — nur wenn vorhanden
                if item.get("location"):
                    event["location"] = item["location"]
                events.append(event)

            # DSGVO Auflage 3: Kein Logging von Event-Details — nur Anzahl
            log.info("calendar_events_fetched", count=len(events), days=days)
            return events

        except Exception as exc:  # noqa: BLE001
            # DSGVO Auflage 3: Kein Logging von Kalender-Inhalt — nur Fehlertyp
            log.warning(
                "calendar_fetch_failed",
                error_type=type(exc).__name__,
                detail=str(exc)[:80],
            )
            return []

    def format_for_prompt(self, events: list[dict], max_events: int = 3) -> str | None:
        """Formatiert Events als kompakten [Kalender-Kontext]-Block für den System-Prompt.

        Kalender-PII nur In-Memory, keine LTM-Persistenz.  # Art. 5(2) DSGVO

        Args:
            events: Liste von Event-dicts aus get_upcoming_events().
            max_events: Maximale Anzahl Events im Prompt (Standard: 3).

        Returns:
            Formatierter String oder None wenn events leer.
        """
        if not events:
            return None

        now = datetime.now(tz=timezone.utc)
        today_date = now.date()
        tomorrow_date = today_date + timedelta(days=1)

        lines: list[str] = []
        for event in events[:max_events]:
            summary = event.get("summary", "(kein Titel)")
            start_info = event.get("start", {})

            # Datum/Zeit aus start_info ermitteln
            start_str = start_info.get("dateTime") or start_info.get("date", "")
            label = _format_event_label(start_str, today_date, tomorrow_date)
            lines.append(f"{label}: {summary}")

        if not lines:
            return None

        return "[Kalender-Kontext]\n" + "\n".join(lines)


def _format_event_label(
    start_str: str,
    today_date: "datetime.date",
    tomorrow_date: "datetime.date",
) -> str:
    """Formatiert ein Event-Datum als lesbares Label (Heute/Morgen/Wochentag)."""
    if not start_str:
        return "Demnächst"

    try:
        if "T" in start_str:
            # dateTime: "2026-05-07T10:00:00+02:00"
            dt = datetime.fromisoformat(start_str)
            event_date = dt.date()
            time_str = dt.strftime("%H:%M")
        else:
            # date: "2026-05-07" (ganztägig)
            from datetime import date
            event_date = date.fromisoformat(start_str)
            time_str = None

        if event_date == today_date:
            prefix = "Heute"
        elif event_date == tomorrow_date:
            prefix = "Morgen"
        else:
            weekdays = ["Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"]
            prefix = weekdays[event_date.weekday()]

        if time_str:
            return f"{prefix} {time_str}"
        return prefix

    except (ValueError, AttributeError):
        return "Demnächst"

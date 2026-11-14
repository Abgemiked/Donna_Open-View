"""Zentrale Konstanten für proaktive Push-Sessions.

Hintergrund (Design-Entscheidung Mike, Option A — volle Version):

- Transport (A1): Die ``session_id`` reist als ntfy-**Tag** im Format ``sid=<id>``
  über den bestehenden ``Tags``-Header. ntfy spiegelt Tags nativ in die
  Nachrichten-JSON (``tags: [...]``) zurück — ein Top-Level-Feld ``session_id``
  würde von Stock-ntfy dagegen verworfen. Die App parst den ``sid=``-Tag.

- ID-Semantik (B1): Pro Kanal eine **stabile** Session-ID (kein Datum →
  kein Thread-Sprawl). Beim Antippen einer proaktiven Push lädt die App den
  zugehörigen Thread; zusätzlich wird die proaktive Nachricht ins STM unter
  dieser ID geschrieben, damit echte Thread-Historie existiert.

Streng additiv: Es wird nur die opake ID als Tag transportiert — niemals
zusätzlicher Nachrichteninhalt in der Push.
"""
from __future__ import annotations

# Stabile Proaktiv-Session-IDs je Kanal (kein Datum).
PROACTIVE_SESSION_MORNING = "proactive_morning"
PROACTIVE_SESSION_EVENING = "proactive_evening"
PROACTIVE_SESSION_STREAM = "proactive_stream"
PROACTIVE_SESSION_CALENDAR = "proactive_calendar"
PROACTIVE_SESSION_NOTIFY = "proactive_notify"
PROACTIVE_SESSION_DREAMDUO = "proactive_dreamduo"

# Tag-Präfix, über das die App die session_id aus dem ntfy-Tags-Array liest.
SID_TAG_PREFIX = "sid="


def sid_tag(session_id: str) -> str:
    """Baut den ntfy-Tag, der die session_id transportiert (``sid=<id>``)."""
    return f"{SID_TAG_PREFIX}{session_id}"

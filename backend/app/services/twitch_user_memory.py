"""twitch_user_memory.py — Per-User-Memory für Twitch-Chat-Nutzer (DONNA-42 B).

Ziel: Donna merkt sich pro Twitch-Login wer was gesagt hat — speziell den
Wohnort/Standort, damit "wie ist das wetter?" automatisch den richtigen Ort
einsetzt, ohne dass der User ihn jedes Mal nennen muss.

Design:
- Eine SQLite-Tabelle `twitch_user_memory` (PK: user_login lowercase)
- Felder: location (TEXT), facts_json (TEXT, beliebige Key-Value-Erweiterungen),
  first_seen, last_seen, location_updated_at,
  message_count (INTEGER), is_regular (BOOLEAN), known_for (TEXT),
  first_seen_stream_date (TEXT)
- Schreibzugriff: nur durch den Bot-Service nach Extraktion aus User-Messages
- Privacy: ein User sieht nur seine eigenen Daten (über user_login-Scope) —
  nie wird die Memory eines anderen Users an einen anderen User exposed

DONNA-93: Stammzuschauer-Tracking — is_regular wird auto-gesetzt ab
_REGULAR_THRESHOLD Nachrichten. get_regulars() liefert alle Stammzuschauer.
"""
from __future__ import annotations

import json
import re
import sqlite3
from datetime import datetime, timezone
from pathlib import Path

from app.core.logger import get_logger

log = get_logger("service.twitch_user_memory")

_DEFAULT_DB_PATH = "/data/appdata/twitch_user_memory.db"

# Ab dieser Nachrichten-Anzahl gilt ein User als Stammzuschauer
_REGULAR_THRESHOLD = 50


def _get_db(db_path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(db_path, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    return conn


def _migrate_add_column(conn: sqlite3.Connection, table: str, column: str, col_def: str) -> None:
    """Fügt eine Spalte hinzu wenn sie noch nicht existiert (Migration für bestehende DBs)."""
    existing = {row[1] for row in conn.execute(f"PRAGMA table_info({table})")}
    if column not in existing:
        conn.execute(f"ALTER TABLE {table} ADD COLUMN {column} {col_def}")
        log.info("db_migration_add_column", table=table, column=column)


def _ensure_schema(db_path: str) -> None:
    Path(db_path).parent.mkdir(parents=True, exist_ok=True)
    conn = _get_db(db_path)
    with conn:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS twitch_user_memory (
                user_login              TEXT PRIMARY KEY,
                location                TEXT,
                facts_json              TEXT NOT NULL DEFAULT '{}',
                first_seen              TEXT NOT NULL,
                last_seen               TEXT NOT NULL,
                location_updated_at     TEXT,
                message_count           INTEGER NOT NULL DEFAULT 0,
                is_regular              INTEGER NOT NULL DEFAULT 0,
                known_for               TEXT,
                first_seen_stream_date  TEXT
            )
        """)
        # Migration: neue Spalten zu bestehenden Datenbanken hinzufügen (DONNA-93)
        _migrate_add_column(conn, "twitch_user_memory", "message_count", "INTEGER NOT NULL DEFAULT 0")
        _migrate_add_column(conn, "twitch_user_memory", "is_regular", "INTEGER NOT NULL DEFAULT 0")
        _migrate_add_column(conn, "twitch_user_memory", "known_for", "TEXT")
        _migrate_add_column(conn, "twitch_user_memory", "first_seen_stream_date", "TEXT")
        conn.execute("""
            CREATE INDEX IF NOT EXISTS idx_twitch_user_memory_last_seen
            ON twitch_user_memory(last_seen)
        """)
        conn.execute("""
            CREATE INDEX IF NOT EXISTS idx_twitch_user_memory_is_regular
            ON twitch_user_memory(is_regular)
        """)
    conn.close()


# ──────────────────────────────────────────────────────────────────────────────
# Standort-Extraktion aus User-Nachrichten
# ──────────────────────────────────────────────────────────────────────────────

# Trigger case-insensitive (Inline-Flag (?i:...)), Stadt-Capture case-sensitive
# (Großbuchstabe-Anfang muss erhalten bleiben → einzelne Stadt, kein Folgewort).

# Pattern 1: "wie ist das wetter in [STADT]" / "wetter für [STADT]"
_WEATHER_LOCATION_RE = re.compile(
    r"(?i:\b(?:wetter|temperatur|regen|schnee|sonne)\b[^.?!]*?"
    r"\b(?:in|für|fur|aus|bei|von)\s+)"
    r"([A-ZÄÖÜ][a-zäöüß]{2,}(?:[\s\-][A-ZÄÖÜ][a-zäöüß]+)?)"
)

# Pattern 2: explizite Wohnort-Aussagen "ich wohne in [STADT]" / "ich komme aus [STADT]"
_RESIDENCE_RE = re.compile(
    r"(?i:\bich\s+(?:wohne|lebe|bin|komme)\s+(?:in|aus|bei|von)\s+)"
    r"([A-ZÄÖÜ][a-zäöüß]{2,}(?:[\s\-][A-ZÄÖÜ][a-zäöüß]+)?)"
)

# Pattern 3: "[bei mir] in [STADT]" / "hier in [STADT]"
_HERE_RE = re.compile(
    r"(?i:\b(?:bei\s+mir|hier|zuhause)\s+(?:in|aus)\s+)"
    r"([A-ZÄÖÜ][a-zäöüß]{2,}(?:[\s\-][A-ZÄÖÜ][a-zäöüß]+)?)"
)

# Wörter die KEIN Ort sind — Filter für False Positives aus den Patterns
_NON_LOCATION_WORDS = {
    "deutschland", "germany",  # Land statt Stadt — zu generisch
    "haus", "wohnung", "schule", "arbeit", "büro", "buro",  # Abstrakte Orte
    "norden", "süden", "osten", "westen", "suden",  # Himmelsrichtungen
}


def extract_residence_location(text: str) -> str | None:
    """Extrahiert eine explizite Wohnort-Aussage ("ich wohne in X") aus dem Text.

    Wird NUR für persistente Speicherung verwendet — Wetterfragen ohne explizite
    Aussage ändern den gespeicherten Wohnort nicht.
    """
    for regex in (_RESIDENCE_RE, _HERE_RE):
        m = regex.search(text)
        if m:
            place = m.group(1).strip()
            if place.lower() not in _NON_LOCATION_WORDS:
                return place.title()
    return None


def extract_query_location(text: str) -> str | None:
    """Extrahiert einen in der Frage genannten Standort (z.B. Wetterfrage).

    Wird verwendet um zu prüfen ob die Frage einen expliziten Ort hat — wenn ja,
    überschreibt der gespeicherte Wohnort NICHT den Frage-Ort.
    """
    m = _WEATHER_LOCATION_RE.search(text)
    if m:
        place = m.group(1).strip()
        if place.lower() not in _NON_LOCATION_WORDS:
            return place.title()
    return None


# ──────────────────────────────────────────────────────────────────────────────
# Name-Extraktion ("ich heiße X", "ich bin X", "mein name ist X")
# ──────────────────────────────────────────────────────────────────────────────

# Trigger case-insensitive, Name-Capture case-sensitive (Großbuchstabe-Anfang).
# Nur Vornamen oder zweiteilige Namen — keine Sätze
_NAME_RE = re.compile(
    r"(?i:\b(?:ich\s+heiße|ich\s+heisse|mein\s+name\s+ist|man\s+nennt\s+mich|"
    r"nenn(?:t)?\s+mich|ich\s+bin)\s+)"
    r"([A-ZÄÖÜ][a-zäöüß]{1,20}(?:[\s\-][A-ZÄÖÜ][a-zäöüß]+)?)\b"
)

# Wörter die NICHT als Name extrahiert werden sollen (False Positives bei "ich bin X")
_NON_NAME_WORDS = {
    "müde", "krank", "happy", "traurig", "wach", "online", "live", "raus", "weg",
    "neu", "alt", "jung", "gut", "schlecht", "okay", "ok", "fertig", "da", "hier",
    "zurück", "weg", "zuhause", "unterwegs", "auf", "in", "bei", "von",
    "der", "die", "das", "ein", "eine", "kein", "keine",
}


def extract_name(text: str) -> str | None:
    """Extrahiert einen explizit genannten Vornamen ("ich heiße X")."""
    m = _NAME_RE.search(text)
    if m:
        name = m.group(1).strip()
        # Nur das erste Wort nehmen wenn 2 erkannt wurden und das 2. ein Verb wäre
        if name.lower() not in _NON_NAME_WORDS:
            # Dropfall: "ich bin müde" → "müde" wird in non_name_words gefiltert
            return name.title().split(" ")[0]  # Vorname only
    return None


# ──────────────────────────────────────────────────────────────────────────────
# Hobby-Extraktion
# ──────────────────────────────────────────────────────────────────────────────

# "Mein Hobby ist X", "meine Hobbys sind X", "ich mache X als Hobby",
# "ich spiele gern X", "ich liebe X"
# Capture-Group: Bis zum Satzende oder Konjunktion
_HOBBY_RE = re.compile(
    r"(?i:\b(?:mein(?:e)?\s+hobby(?:s)?\s+(?:ist|sind)|"
    r"(?:als\s+)?hobby(?:\s+(?:habe\s+ich|ist))|"
    r"ich\s+(?:liebe|mag\s+(?:gerne|gern)?|spiele\s+(?:gern|gerne))|"
    r"in\s+meiner\s+freizeit)\s+)"
    r"([^.?!,;\n]{2,80})"
)


def extract_hobby(text: str) -> str | None:
    """Extrahiert eine genannte Hobby-/Interessen-Aussage."""
    m = _HOBBY_RE.search(text)
    if m:
        hobby = m.group(1).strip()
        # Trim zu offensichtlichen Verben/Stop-Wörtern am Anfang
        hobby = re.sub(r"^(?:zu|sehr|total|absolut)\s+", "", hobby, flags=re.IGNORECASE)
        if 2 <= len(hobby) <= 80:
            return hobby
    return None


# ──────────────────────────────────────────────────────────────────────────────
# Service-Klasse — pro Datenbank-Pfad eine Instanz
# ──────────────────────────────────────────────────────────────────────────────

class TwitchUserMemory:
    """SQLite-basiertes Per-User-Memory für Twitch-Chatter."""

    def __init__(self, db_path: str = _DEFAULT_DB_PATH) -> None:
        self._db_path = db_path
        _ensure_schema(db_path)

    def _now(self) -> str:
        return datetime.now(timezone.utc).isoformat()

    def get(self, user_login: str) -> dict | None:
        """Gibt die gespeicherte Memory eines Users zurück oder None."""
        if not user_login:
            return None
        login = user_login.strip().lower()
        conn = _get_db(self._db_path)
        try:
            row = conn.execute(
                "SELECT user_login, location, facts_json, first_seen, last_seen, "
                "location_updated_at, message_count, is_regular, known_for, "
                "first_seen_stream_date FROM twitch_user_memory WHERE user_login = ?",
                (login,),
            ).fetchone()
        finally:
            conn.close()
        if not row:
            return None
        try:
            facts = json.loads(row["facts_json"]) if row["facts_json"] else {}
        except json.JSONDecodeError:
            facts = {}
        return {
            "user_login": row["user_login"],
            "location": row["location"],
            "facts": facts,
            "first_seen": row["first_seen"],
            "last_seen": row["last_seen"],
            "location_updated_at": row["location_updated_at"],
            "message_count": row["message_count"] or 0,
            "is_regular": bool(row["is_regular"]),
            "known_for": row["known_for"],
            "first_seen_stream_date": row["first_seen_stream_date"],
        }

    def touch(self, user_login: str) -> None:
        """Markiert den User als aktiv und erhöht message_count.

        Setzt is_regular automatisch wenn _REGULAR_THRESHOLD erreicht wird.
        """
        if not user_login:
            return
        login = user_login.strip().lower()
        now = self._now()
        today = now[:10]  # YYYY-MM-DD als first_seen_stream_date
        conn = _get_db(self._db_path)
        try:
            with conn:
                conn.execute(
                    "INSERT INTO twitch_user_memory "
                    "  (user_login, first_seen, last_seen, message_count, first_seen_stream_date) "
                    "VALUES (?, ?, ?, 1, ?) "
                    "ON CONFLICT(user_login) DO UPDATE SET "
                    "  last_seen = excluded.last_seen, "
                    "  message_count = message_count + 1, "
                    "  is_regular = CASE "
                    "    WHEN (message_count + 1) >= ? THEN 1 "
                    "    ELSE is_regular "
                    "  END",
                    (login, now, now, today, _REGULAR_THRESHOLD),
                )
        finally:
            conn.close()

    def set_location(self, user_login: str, location: str) -> None:
        """Speichert/aktualisiert den Wohnort eines Users."""
        if not user_login or not location:
            return
        login = user_login.strip().lower()
        loc = location.strip()
        now = self._now()
        conn = _get_db(self._db_path)
        try:
            with conn:
                conn.execute(
                    "INSERT INTO twitch_user_memory "
                    "  (user_login, location, first_seen, last_seen, location_updated_at) "
                    "VALUES (?, ?, ?, ?, ?) "
                    "ON CONFLICT(user_login) DO UPDATE SET "
                    "  location = excluded.location, "
                    "  last_seen = excluded.last_seen, "
                    "  location_updated_at = excluded.location_updated_at",
                    (login, loc, now, now, now),
                )
            log.info("twitch_user_location_set", user=login, location=loc)
        finally:
            conn.close()

    def set_fact(self, user_login: str, key: str, value: object) -> None:
        """Setzt einen freien Key/Value-Fakt für einen User (z.B. Lieblingsspiel)."""
        if not user_login or not key:
            return
        login = user_login.strip().lower()
        existing = self.get(login)
        facts = (existing or {}).get("facts", {})
        facts[key] = value
        now = self._now()
        conn = _get_db(self._db_path)
        try:
            with conn:
                conn.execute(
                    "INSERT INTO twitch_user_memory "
                    "  (user_login, facts_json, first_seen, last_seen) "
                    "VALUES (?, ?, ?, ?) "
                    "ON CONFLICT(user_login) DO UPDATE SET "
                    "  facts_json = excluded.facts_json, last_seen = excluded.last_seen",
                    (login, json.dumps(facts, ensure_ascii=False), now, now),
                )
        finally:
            conn.close()

    def merge_facts(self, user_login: str, facts: dict) -> None:
        """Mergt einen Facts-Dict in die bestehenden User-Facts (Set-Union für Listen).

        Erwartete Keys: interests, traits, preferences (Listen von Strings).
        Skalar-Werte werden überschrieben. Listen werden per Set-Union akkumuliert.
        """
        if not user_login or not facts:
            return
        login = user_login.strip().lower()
        existing = self.get(login)
        existing_facts = (existing or {}).get("facts", {})

        for key, value in facts.items():
            if not key or value is None:
                continue
            if isinstance(value, list):
                # Set-Union: bestehende + neue Einträge, Duplikate entfernen
                existing_list = existing_facts.get(key, [])
                if not isinstance(existing_list, list):
                    existing_list = [str(existing_list)] if existing_list else []
                merged = list(dict.fromkeys(
                    existing_list + [str(v) for v in value if v]
                ))
                existing_facts[key] = merged[:20]  # max 20 Einträge pro Key
            else:
                existing_facts[key] = value

        now = self._now()
        conn = _get_db(self._db_path)
        try:
            with conn:
                conn.execute(
                    "INSERT INTO twitch_user_memory "
                    "  (user_login, facts_json, first_seen, last_seen) "
                    "VALUES (?, ?, ?, ?) "
                    "ON CONFLICT(user_login) DO UPDATE SET "
                    "  facts_json = excluded.facts_json, "
                    "  last_seen = excluded.last_seen",
                    (login, json.dumps(existing_facts, ensure_ascii=False), now, now),
                )
            log.info("twitch_user_facts_merged", user=login, keys=list(facts.keys()))
        finally:
            conn.close()

    def set_known_for(self, user_login: str, known_for: str) -> None:
        """Setzt was der User typischerweise fragt oder beiträgt (z.B. 'Fragen zu Games')."""
        if not user_login or not known_for:
            return
        login = user_login.strip().lower()
        now = self._now()
        conn = _get_db(self._db_path)
        try:
            with conn:
                conn.execute(
                    "INSERT INTO twitch_user_memory "
                    "  (user_login, known_for, first_seen, last_seen) "
                    "VALUES (?, ?, ?, ?) "
                    "ON CONFLICT(user_login) DO UPDATE SET "
                    "  known_for = excluded.known_for, "
                    "  last_seen = excluded.last_seen",
                    (login, known_for.strip()[:200], now, now),
                )
            log.info("twitch_user_known_for_set", user=login, known_for=known_for[:80])
        finally:
            conn.close()

    def get_regulars(self, limit: int = 50) -> list[dict]:
        """Gibt alle als Stammzuschauer markierten User zurück (nach message_count sortiert)."""
        conn = _get_db(self._db_path)
        try:
            rows = conn.execute(
                "SELECT user_login, location, facts_json, first_seen, last_seen, "
                "message_count, known_for, first_seen_stream_date "
                "FROM twitch_user_memory "
                "WHERE is_regular = 1 ORDER BY message_count DESC LIMIT ?",
                (limit,),
            ).fetchall()
        finally:
            conn.close()
        result = []
        for row in rows:
            try:
                facts = json.loads(row["facts_json"]) if row["facts_json"] else {}
            except json.JSONDecodeError:
                facts = {}
            result.append({
                "user_login": row["user_login"],
                "location": row["location"],
                "facts": facts,
                "message_count": row["message_count"] or 0,
                "known_for": row["known_for"],
                "first_seen_stream_date": row["first_seen_stream_date"],
                "last_seen": row["last_seen"],
            })
        return result

    def context_string(self, user_login: str) -> str:
        """Formatiert die User-Memory als kompakten Kontext-String fürs LLM-Prompt.

        Beispiel-Output (Stammzuschauer):
            "[Stammzuschauer: arcsore wohnt in München · bekannt für: Fragen zu Games · Hobby: Gitarre]"
        Beispiel-Output (normaler User):
            "[User-Kontext: arcsore wohnt in München · Name: Tom · Hobby: Gitarre]"
        Leerer String wenn kein Kontext bekannt ist.
        """
        mem = self.get(user_login)
        if not mem:
            return ""
        parts: list[str] = []
        login = mem["user_login"]
        facts = mem.get("facts") or {}

        # Name zuerst (wichtigste Identifizierung)
        if facts.get("name"):
            parts.append(f"{login} heißt {facts['name']}")
        if mem.get("location"):
            parts.append(f"wohnt in {mem['location']}")
        if mem.get("known_for"):
            parts.append(f"bekannt für: {mem['known_for']}")
        if facts.get("hobby"):
            parts.append(f"Hobby: {facts['hobby']}")
        # Beliebige andere Facts (außer name/hobby — die haben wir bereits oben)
        for k, v in facts.items():
            if k in {"name", "hobby"}:
                continue
            parts.append(f"{k}: {v}")

        if not parts:
            return ""
        # User-Login voranstellen wenn Name nicht extrahiert war
        if not facts.get("name"):
            parts.insert(0, login)
        prefix = "[Stammzuschauer: " if mem.get("is_regular") else "[User-Kontext: "
        return prefix + " · ".join(parts) + "]"

"""calendar_roles — Zuordnung der Google-Kalender zu Rollen (#99).

Welche Kalender Donna abfragt, steht NICHT im Repo, sondern in einer JSON-Datei im persistenten
Datenordner des Servers (Setting ``calendar_roles_path``, Standard ``/data/appdata/calendar_roles.json``).
Personenbezogen vorbereitet; der Dienst nutzt vorerst nur die Person ``mike`` (Legacy-Alias des Inhabers).

Rollen:
  general       normale Termine (Kontextblock, allgemeine Terminfragen)
  absence_info  nur Zeitraum + Label als knappe Abwesenheits-Info (kein Titel, kein Ort)
  topic:<thema> nur bei ausdrücklicher Themen-Frage (derzeit "muell")
  ignore        nie abfragen
Nicht zugeordnete Kalender werden nie abgefragt. Der Hauptkalender ist immer ``general``, außer die
Datei ordnet ihn ausdrücklich anders zu. Zuordnung über Kalender-``id`` (zuerst), sonst über den Namen
(Groß-/Kleinschreibung und Randleerraum egal; gegen ``summaryOverride`` und ``summary``).
Fehlt die Datei oder ist sie defekt: nur der Hauptkalender (vorsichtiger Standard).
Optional ``"version": 1`` (fehlt → 1; andere Werte → ungültig); UTF-8 mit/ohne BOM; max. 256 KB.

BEISPIEL (nur Dummy-Werte):
    {
      "version": 1,
      "persons": {
        "mike": {
          "calendars": [
            {"id": "cal-a@example", "role": "general"},
            {"name": "Kalender B", "role": "general"},
            {"id": "cal-c@example", "name": "Kalender C", "role": "absence_info", "label": "Person B"},
            {"id": "cal-d@example", "role": "topic:muell"},
            {"id": "cal-e@example", "role": "ignore"}
          ]
        }
      }
    }

Optional je Person ``"people"`` (#111): Zuordnung E-Mail-Adresse des Termin-Erstellers -> Anzeige-Label, damit
Donna "von <Label>" nennen kann. Wert: Label-Text oder ``{"label": "...", "self": true}``. ``self`` markiert die
abgefragte Person selbst: ihre eigenen Termine bekommen kein "(von ...)". Adressen werden ohne Groß-/Kleinschreibung
und Randleerraum verglichen; Kalender-Adressen (@group.calendar.google.com) zählen nicht als Person. Die Adressen
bleiben auf dem Server: sie stehen nur in dieser Datei, nie in Antworten, Zwischenspeichern, Prompts oder Logs.
``people`` wirkt NUR, wenn mindestens eine gültige Kalender-Regel vorhanden ist (sonst Status "missing"/"invalid",
nur Hauptkalender, keine Zuordnung). Ohne ``people`` ändert sich nichts. Defekte Einträge werden ignoriert (eine Logzeile mit Zähler).

    "people": {
      "person-a@example.invalid": "Person A",
      "person-b@example.invalid": {"label": "Person B", "self": true}
    }

DSGVO: Kalendernamen, IDs, Adressen und Labels werden nie geloggt (nur Zähler/Status).
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Any

from app.core.logger import get_logger

log = get_logger("service.calendar_roles")

DEFAULT_PERSON = "mike"  # LEGACY_MIKE_NS (services/namespaces.py): Schlüssel des Inhabers

ROLE_GENERAL = "general"
ROLE_ABSENCE = "absence_info"
ROLE_IGNORE = "ignore"
TOPIC_PREFIX = "topic:"
_TOPIC_RE = re.compile(r"^[a-z0-9_]{1,32}$")
_BASIC_ROLES = frozenset({ROLE_GENERAL, ROLE_ABSENCE, ROLE_IGNORE})
_MAX_LABEL = 60
MAX_FILE_BYTES = 256 * 1024  # größere Dateien gelten als ungültig


@dataclass(frozen=True)
class Rule:
    id: str | None
    name: str | None
    role: str
    label: str | None


@dataclass(frozen=True)
class Person:
    """Ersteller-Zuordnung (#111): Anzeige-Label (None bei reinem ``self``) und Kennzeichen "abgefragte Person"."""

    label: str | None
    is_self: bool = False


@dataclass(frozen=True)
class RolesConfig:
    rules: list[Rule]
    status: str
    people: dict[str, Person]  # Schlüssel: normalisierte Adresse; nur im Dienst, nie ausgeben


_CALENDAR_ADDR_SUFFIXES = ("@group.calendar.google.com", "@resource.calendar.google.com", "@import.calendar.google.com")


def normalize_email(value: Any) -> str | None:
    """Adresse vergleichbar machen (Randleerraum, Groß/Klein); Nicht-Strings/leer/ohne "@" -> None."""
    if not isinstance(value, str):
        return None
    v = value.strip().casefold()
    return v if "@" in v else None


def is_person_address(email: str) -> bool:
    """Kalender-/Ressourcen-Adressen zählen nicht als Person."""
    return not email.endswith(_CALENDAR_ADDR_SUFFIXES)


def topic_of(role: str) -> str | None:
    """'topic:muell' -> 'muell'; andere Rollen -> None."""
    return role[len(TOPIC_PREFIX):] if role.startswith(TOPIC_PREFIX) else None


def normalize_name(value: Any) -> str:
    return str(value or "").strip().casefold()


def _valid_role(role: Any) -> bool:
    if not isinstance(role, str):
        return False
    if role in _BASIC_ROLES:
        return True
    topic = topic_of(role)
    return topic is not None and bool(_TOPIC_RE.match(topic))


def _clean_label(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    cleaned = " ".join(value.split())  # Umbrüche/Tabs/Mehrfach-Leerraum -> ein Leerzeichen
    cleaned = "".join(c if c.isprintable() else " " for c in cleaned).strip()
    return cleaned[:_MAX_LABEL].rstrip() or None


def _parse_people(raw: Any) -> tuple[dict[str, Person], int]:
    """Liest den Abschnitt ``people``. Rückgabe: (Zuordnung, Zahl ignorierter Einträge). Wirft nie."""
    if raw is None:
        return {}, 0
    if not isinstance(raw, dict):
        return {}, 1
    out: dict[str, Person] = {}
    ignored = 0
    for key, val in raw.items():
        email = normalize_email(key)
        if email is None or not is_person_address(email):
            ignored += 1
            continue
        if isinstance(val, str):
            label, is_self = _clean_label(val), False
        elif isinstance(val, dict):
            label, is_self = _clean_label(val.get("label")), val.get("self") is True
        else:
            ignored += 1
            continue
        if (label is not None and "@" in label) or (label is None and not is_self):
            ignored += 1  # Label sieht aus wie eine Adresse bzw. kein verwertbares Label
            continue
        out[email] = Person(label=label, is_self=is_self)
    return out, ignored


def load_rules(path: str, person_id: str = DEFAULT_PERSON) -> tuple[list[Rule], str]:
    """Lädt die Regeln einer Person. Rückgabe: (Regeln, Status).

    Dünne Hülle um :func:`load_config` (Signatur unverändert).
    """
    cfg = load_config(path, person_id)
    return cfg.rules, cfg.status


def load_config(path: str, person_id: str = DEFAULT_PERSON) -> RolesConfig:
    """Lädt Regeln UND Ersteller-Zuordnung (``people``) einer Person.

    Status: "ok" | "missing" (Datei fehlt / keine Einträge für die Person) | "invalid" (kein gültiges JSON/
    Format). Bei "missing"/"invalid" ist die Liste leer = nur Hauptkalender. Pro Aufruf höchstens
    eine Statuszeile im Log (ohne Pfade, Namen, IDs).
    """
    try:
        with open(path, "rb") as f:
            blob = f.read(MAX_FILE_BYTES + 1)
        if len(blob) > MAX_FILE_BYTES:
            raise ValueError("zu gross")
        raw = blob.decode("utf-8-sig")  # BOM von Windows-Editoren tolerieren
    except FileNotFoundError:
        log.warning("calendar_roles_missing")  # Datei fehlt: Betreiber soll es merken
        return RolesConfig([], "missing", {})
    except (OSError, ValueError):  # inkl. UnicodeDecodeError
        log.warning("calendar_roles_invalid")
        return RolesConfig([], "invalid", {})

    try:
        doc = json.loads(raw)
        persons = doc["persons"]
        version = doc.get("version", 1)
        if not isinstance(persons, dict) or type(version) is not int or version != 1:
            raise TypeError
    except (ValueError, KeyError, TypeError, AttributeError):
        log.warning("calendar_roles_invalid")
        return RolesConfig([], "invalid", {})

    entry = persons.get(person_id)
    if entry is None:
        log.info("calendar_roles_missing")  # Datei da, aber nichts für diese Person: nur info
        return RolesConfig([], "missing", {})
    cals = entry.get("calendars") if isinstance(entry, dict) else None
    if not isinstance(cals, list):
        log.warning("calendar_roles_invalid")
        return RolesConfig([], "invalid", {})

    people, people_ignored = _parse_people(entry.get("people"))
    if people_ignored:
        log.warning("calendar_people_invalid", ignored_entries=people_ignored)  # nur Zahl

    rules: list[Rule] = []
    ignored = 0
    for item in cals:
        if not isinstance(item, dict) or not _valid_role(item.get("role")):
            ignored += 1
            continue
        cid = item.get("id") if isinstance(item.get("id"), str) and item["id"].strip() else None
        name = item.get("name") if isinstance(item.get("name"), str) and item["name"].strip() else None
        if cid is None and name is None:
            ignored += 1
            continue
        rules.append(Rule(id=cid.strip() if cid else None, name=name, role=item["role"], label=_clean_label(item.get("label"))))
    if not rules:
        log.info("calendar_roles_missing")
        return RolesConfig([], "missing", {})
    # nur Zahlen — nie Namen, IDs oder Labels (DSGVO Auflage 3)
    log.info(
        "calendar_roles_loaded",
        general=sum(r.role == ROLE_GENERAL for r in rules),
        absence=sum(r.role == ROLE_ABSENCE for r in rules),
        topics=sum(topic_of(r.role) is not None for r in rules),
        ignore=sum(r.role == ROLE_IGNORE for r in rules),
        ignored_entries=ignored,
    )
    if people:
        log.info("calendar_people_loaded", people=len(people))  # nur Zahl
    return RolesConfig(rules, "ok", people)


def resolve_rule(rules: list[Rule], entry: dict) -> Rule | None:
    """Regel für einen Eintrag der Kalenderliste: zuerst exakte ID, sonst Name (summaryOverride/summary)."""
    cid = entry.get("id")
    if cid:
        for r in rules:
            if r.id and r.id == cid:
                return r
    names = {normalize_name(entry.get("summaryOverride")), normalize_name(entry.get("summary"))} - {""}
    if names:
        for r in rules:
            if r.name and normalize_name(r.name) in names:
                return r
    return None

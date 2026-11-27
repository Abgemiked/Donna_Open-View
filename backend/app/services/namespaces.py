"""namespaces.py — LTM-Namespaces je Person / household / Twitch (Phase A1, #67/#74).

Ein Namespace ist der Wert, der als mem0-``user_id`` (Qdrant-Payload-Filter) bzw. als
Chroma-Metadatum ``user_id`` geschrieben und bei JEDEM Lesen/Löschen erzwungen wird.

* ``mike``           Legacy-Alias für Mike — bestehende Daten bleiben unverändert zugeordnet.
* ``person:<id>``    jede weitere Person (Ämi, Sohn, Gäste …).
* ``household``      Haushaltsfakten (nur ausdrücklich gespeichert, Phase A).
* ``twitch_stream``  Twitch-/Viewer-Brain — eigene Collection, nie vermischt mit Personen.
"""
from __future__ import annotations

import re

LEGACY_MIKE_NS = "mike"
HOUSEHOLD_NS = "household"
TWITCH_NS = "twitch_stream"
PERSON_PREFIX = "person:"

PERSONAL_COLLECTION = "brain_ltm"
STREAM_COLLECTION = "stream_ltm"

_PERSON_ID_RE = re.compile(r"^[a-z0-9_-]{1,32}$")
# Als Personen-ID nicht erlaubt (kollidiert mit Bereichen / Test-Isolation / Gast-Platzhalter)
_RESERVED_IDS = frozenset({HOUSEHOLD_NS, TWITCH_NS, "guest"})


class NamespaceViolation(ValueError):
    """Zugriff auf einen Namespace, der für diese LTM-Instanz/Person nicht erlaubt ist."""


def ns_for_person(person_id: str) -> str:
    """Namespace einer Person. ``mike`` bleibt der Legacy-Alias (keine Migration nötig)."""
    if not isinstance(person_id, str) or not _PERSON_ID_RE.match(person_id):
        raise NamespaceViolation("ungueltige person_id")
    if person_id in _RESERVED_IDS or person_id.startswith("test__"):
        raise NamespaceViolation("reservierte person_id")
    if person_id == LEGACY_MIKE_NS:
        return LEGACY_MIKE_NS
    return f"{PERSON_PREFIX}{person_id}"


TEST_ID_PREFIX = "test_"
TEST_ID_MAX_LEN = 64
_TEST_ID_RE = re.compile(rf"^{TEST_ID_PREFIX}[a-zA-Z0-9_\-]{{1,{TEST_ID_MAX_LEN - len(TEST_ID_PREFIX)}}}$")  # Pflicht-Praefix
_RESERVED_TEST_IDS = frozenset({LEGACY_MIKE_NS, HOUSEHOLD_NS, TWITCH_NS, "guest"})


def is_valid_test_user_id(value: str) -> bool:
    """Gueltige ID fuer den Test-Header X-Test-User-Id / Admin-Cleanup.

    Pflicht-Praefix ``test_`` (Phase A1b) — damit nie mit echten Personen/Namespaces verwechselbar.
    """
    return bool(isinstance(value, str) and _TEST_ID_RE.match(value)) and value not in _RESERVED_TEST_IDS


def validate_personal_namespace(ns: str) -> str:
    """Erlaubte Namespace-Formen im persoenlichen LTM (Allowlist, kein freier String):
    ``mike``, ``household``, ``person:<id>`` oder eine Test-ID (X-Test-User-Id)."""
    if ns in (LEGACY_MIKE_NS, HOUSEHOLD_NS):
        return ns
    if isinstance(ns, str) and ns.startswith(PERSON_PREFIX):
        pid = ns[len(PERSON_PREFIX):]
        if _PERSON_ID_RE.match(pid) and pid not in _RESERVED_IDS and pid != LEGACY_MIKE_NS:
            return ns
        raise NamespaceViolation("ungueltiger person-Namespace")
    if is_valid_test_user_id(ns):
        return ns
    raise NamespaceViolation("ungueltiger Namespace")


# STM-Session-Praefixe: person__<id>__ (Nicht-Mike-Chats, A1b) und test__<id>__ (Test-Isolation).
_NON_OWNER_SESSION_PREFIXES = ("person__", "test__")


def is_non_owner_session(session_id: str) -> bool:
    """True fuer STM-Sessions, die NICHT Mike gehoeren. Nachtjobs, die Sessions als „Mike" deuten oder
    in Mikes Vault/LTM schreiben (stm_to_ltm, stm_obsidian_sync), muessen sie ueberspringen."""
    return str(session_id).startswith(_NON_OWNER_SESSION_PREFIXES)

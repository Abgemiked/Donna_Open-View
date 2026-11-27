"""person_context.py — Person-Kontext request-scoped (Phase A1b, #67).

Wer spricht, kommt aus dem Request (Header ``X-Person-Id``, Standard ``mike``). Spaeter (Stimm-
erkennung/Geraete-Tokens) wird NUR ``_identity_from_request`` ersetzt.

ZWISCHENSTAND (bewusst): Der Header ist client-gewaehlt (gleicher Admin-Token) und ohne Header gilt
Mike. Der Owner-Modus schuetzt damit vor VERMISCHUNG, nicht vor einem Client mit Mikes Token. Echte
Bindung (Geraete-Token/Voice-ID, fehlend = Gast) folgt mit Phase B/C.

Faehigkeiten (capabilities): Eine zentrale Tabelle ordnet jeder Faehigkeit ihre ``app.state``-Services
und Tools zu. Heute: ``mike`` = alle, jede andere Person = NUR ``core`` (fail-closed). Spaeter
(A2/Phase F) schaltet ``capabilities_for`` je Person einzelne Faehigkeiten frei (z. B. Eltern:
``home_control``), ohne dass der chat()-Block umgebaut werden muss.

Fail-closed gilt fuer ``request.app.state`` (nicht freigegebene Services sind None, neue Services
standardmaessig gesperrt), fuer die Tool-Liste UND den Tool-Executor. NICHT abgedeckt: Modul-Singletons
und direkte Modulfunktionen in chat.py (Zeitplan, Games, Kontakt-Praefs) — dort steht je ein
``_person.can(...)``-Gate; bei NEUEN Kontext-Bloecken in chat() gehoert ein solches Gate dazu.
Rollback: ``DONNA_PERSON_NS=false`` ignoriert den Header (alles wie bisher Mike).
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Any, Awaitable, Callable

from fastapi import HTTPException, status

from app.services.namespaces import LEGACY_MIKE_NS, NamespaceViolation, ns_for_person

PERSON_HEADER = "X-Person-Id"

# --- Faehigkeiten-Tabelle (zentral) ------------------------------------------------------------
# capability -> app.state-Attribute / Tool-Namen, die sie freischaltet.
# `vector` (Mikes Chroma-Brain ohne Personentrennung) ist bewusst eine eigene Faehigkeit.
CAPABILITY_STATE: dict[str, frozenset[str]] = {
    "core": frozenset({"settings", "gemini", "mistral", "claude", "smart_router", "local_llm", "stm", "ltm"}),
    "home_control": frozenset({"ha"}),
    "calendar": frozenset({"calendar"}),
    "health_tracking": frozenset({"tracking", "rhythm"}),
    "presence_places": frozenset({"presence", "places"}),
    "mood": frozenset({"mood", "consistency"}),
    "ideas": frozenset({"ideas"}),
    "proactivity": frozenset({"proactivity"}),
    "github_inbox": frozenset({"github_inbox", "project_status"}),
    "media_profiles": frozenset({"media_profiles"}),
    "graphiti": frozenset({"graphiti"}),
    "vector_brain": frozenset({"vector"}),
    "contact": frozenset(),      # Kontakt-Praeferenzen/-Tool (Modul-Store, kein app.state)
    "navigation": frozenset(),   # navigate-Tool (kann Mikes Tesla ansteuern)
    "stream_data": frozenset(),  # Stream-Zeitplan, Games-Stats, Twitch-Kanal-Marker
}
CAPABILITY_TOOLS: dict[str, frozenset[str]] = {
    # Donna-eigene Basis-Tools (kein Fremd-/Mike-Datenzugriff)
    "core": frozenset({"save_memory", "get_weather", "kino_programm", "set_alarm", "set_timer", "play_music", "save_note", "open_url"}),
    "home_control": frozenset({"control_home", "list_smart_home", "get_timers"}),
    "calendar": frozenset({"get_calendar_events", "create_calendar_event"}),
    "health_tracking": frozenset({"get_health_data"}),
    "github_inbox": frozenset({"propose_inbox_issue", "confirm_inbox_issue", "cancel_pending_issue"}),
    "contact": frozenset({"contact_person"}),
    "navigation": frozenset({"navigate"}),
}
# Serverseitige Anthropic-Tools (z.B. web_search_20250305) sind keine Donna-Daten → immer erlaubt.
_SERVER_TOOL_PREFIXES = ("web_search",)

ALL_CAPABILITIES: frozenset[str] = frozenset(CAPABILITY_STATE)
DEFAULT_NON_OWNER_CAPABILITIES: frozenset[str] = frozenset({"core"})  # heutige Default-Belegung


def capabilities_for(person_id: str) -> frozenset[str]:
    """Faehigkeiten einer Person. Heute: mike = alle, alle anderen = nur core.
    (A2/Phase F: hier das Personenregister einhaengen — je Person einzelne Faehigkeiten freischalten.)"""
    return ALL_CAPABILITIES if person_id == LEGACY_MIKE_NS else DEFAULT_NON_OWNER_CAPABILITIES


@dataclass(frozen=True)
class PersonContext:
    person_id: str
    namespace: str  # LTM-Namespace ("mike" = Legacy-Alias)
    capabilities: frozenset[str] = DEFAULT_NON_OWNER_CAPABILITIES

    @property
    def is_owner(self) -> bool:
        """Nur Mike ist Owner (eigener Namespace/Session ohne Prefix). Faehigkeiten sind davon getrennt:
        auch eine Person mit ALLEN Faehigkeiten (A2/Phase F) bleibt in ihrem eigenen Namespace."""
        return self.person_id == LEGACY_MIKE_NS

    def can(self, capability: str) -> bool:
        return capability in self.capabilities

    def allowed_state_attrs(self) -> frozenset[str]:
        return frozenset().union(*(CAPABILITY_STATE.get(c, frozenset()) for c in self.capabilities))

    def allowed_tool_names(self) -> frozenset[str]:
        return frozenset().union(*(CAPABILITY_TOOLS.get(c, frozenset()) for c in self.capabilities))


MIKE = PersonContext("mike", LEGACY_MIKE_NS, ALL_CAPABILITIES)


def _person_ns_enabled() -> bool:
    return os.environ.get("DONNA_PERSON_NS", "true").strip().lower() not in ("false", "0", "no")


def _identity_from_request(request: Any) -> str | None:
    """Quelle der Person. Heute: Header; spaeter: Geraete-Token-/Voice-ID-Mapping."""
    return request.headers.get(PERSON_HEADER)


def resolve_person(request: Any) -> PersonContext:
    """Person des Requests. Ungueltig → HTTP 400 (nie stilles Mike). Flag aus → immer Mike."""
    if not _person_ns_enabled():
        return MIKE
    raw = _identity_from_request(request)
    if raw is None:
        return MIKE
    try:
        ns = ns_for_person(raw)
    except NamespaceViolation:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Ungueltige Person.") from None
    if raw == LEGACY_MIKE_NS:
        return MIKE
    return PersonContext(raw, ns, capabilities_for(raw))


# --- Request-Huelle (fail-closed app.state) ----------------------------------------------------
class _ScopedState:
    """app.state-Sicht: nur freigegebene Services, alles andere None (getattr(..., None)-kompatibel)."""

    def __init__(self, real: Any, allowed: frozenset[str]) -> None:
        self._real = real
        self._allowed = allowed

    def __getattr__(self, name: str) -> Any:
        if name in self._allowed:
            return getattr(self._real, name)
        return None


class _ScopedApp:
    """app-Huelle: ``state`` gefiltert, sonst Delegation."""

    def __init__(self, real_app: Any, state: _ScopedState) -> None:
        self._real = real_app
        self.state = state

    def __getattr__(self, name: str) -> Any:
        return getattr(self._real, name)


# Request-Attribute, die einen Rueckweg zum ungefilterten app.state oeffnen wuerden.
_BLOCKED_REQUEST_ATTRS = frozenset({"scope", "state"})


class _ScopedRequest:
    """Duck-Typed Request-Huelle (``isinstance(x, Request)`` ist False): identisch zum echten
    Request, nur ``app.state`` ist gefiltert; ``scope``/``state`` (Rueckweg) sind gesperrt."""

    def __init__(self, request: Any, person: PersonContext) -> None:
        self._real = request
        self.person = person
        self.app = _ScopedApp(request.app, _ScopedState(request.app.state, person.allowed_state_attrs()))

    def __getattr__(self, name: str) -> Any:
        if name in _BLOCKED_REQUEST_ATTRS:
            raise AttributeError(name)
        return getattr(self._real, name)


def scope_request(request: Any, person: PersonContext) -> Any:
    """Alle Faehigkeiten (Mike) → unveraendert. Sonst Request mit gefiltertem app.state."""
    if person.is_owner:
        return request
    return _ScopedRequest(request, person)


# --- Tools (Angebot UND Ausfuehrung) -----------------------------------------------------------
def _tool_allowed(name: Any, person: PersonContext) -> bool:
    return name in person.allowed_tool_names() or str(name).startswith(_SERVER_TOOL_PREFIXES)


def filter_tools(tools: list[dict] | None, person: PersonContext) -> list[dict] | None:
    """Tool-Angebot nach Faehigkeiten (Mike: unveraendert)."""
    if tools is None or person.is_owner:
        return tools
    return [t for t in tools if _tool_allowed(t.get("name"), person)]


_TOOL_DENIED_RESULT = "Dieses Werkzeug steht fuer diese Person nicht zur Verfuegung. Nicht ausgefuehrt."


def _denied_tool_result(tool_use_id: str) -> dict:
    """Gueltiger tool_result-Block (die Anthropic-API verlangt ihn fuer jede tool_use-ID)."""
    return {"type": "tool_result", "tool_use_id": tool_use_id, "content": _TOOL_DENIED_RESULT, "is_error": True}


def guard_executor(executor: Callable[..., Awaitable[Any]] | None, person: PersonContext):
    """Defense-in-Depth: der Executor fuehrt fuer Nicht-Owner nur freigegebene Tools aus, auch wenn das
    Modell (Prompt-Injection) einen nicht angebotenen Tool-Namen zurueckgibt."""
    if executor is None or person.is_owner:
        return executor

    async def _guarded(name, tool_input, *args, **kwargs):
        if not _tool_allowed(name, person):
            return _denied_tool_result(str(args[0] if args else kwargs.get("tool_use_id", "")))
        return await executor(name, tool_input, *args, **kwargs)

    return _guarded


# --- System-Prompt -----------------------------------------------------------------------------
# Mikes Profil steht im statischen System-Prompt (chat.py SYSTEM_PROMPT, Abschnitt KONTEXT-AWARENESS).
_OWNER_PROFILE_LINES = (
    "- Mike ist Content Creator/Streamer (Twitch-Kanal @abgemiked) und Operations Director, "
    "moechte Consultant fuer Fuehrungskompetenzen, Ideenerstellung, Foerderprojekte etc. sein.\n"
)
_PERSON_OVERRIDE = (
    "# WER SPRICHT\n"
    "Du sprichst gerade NICHT mit Mike, sondern mit einer anderen Person im Haushalt (Kennung: {pid}). "
    "Wo in diesem Prompt 'Mike' steht, ist die aktuelle Person gemeint. Fakten ueber Mike (Beruf, Kanal, "
    "Termine, Smart Home, Kontakte) gelten NICHT fuer sie und werden ihr gegenueber nicht genannt. "
    "Du kennst von ihr nur, was sie dir selbst sagt oder was im Kontext unter [Langzeitgedaechtnis] steht.\n\n"
)


def adapt_system_prompt(prompt: str, person: PersonContext) -> str:
    """Nicht-Owner: Mikes Profil entfernen und die Persona auf die aktuelle Person umstellen."""
    if person.is_owner:
        return prompt
    return _PERSON_OVERRIDE.format(pid=person.person_id) + prompt.replace(_OWNER_PROFILE_LINES, "")

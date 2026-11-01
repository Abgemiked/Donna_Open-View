"""Home-Assistant-REST-Client mit harter Allow-/Deny-Durchsetzung.

Donna steuert Smart-Home + Tesla ausschließlich über diese Fassade. Die
Allow-/Deny-Liste (``data/ha_entities.yaml``) wird VOR jedem Service-Call
geprüft — unabhängig davon, was das LLM anfordert. Sicherheits-Policy (Mike):

* NIE ``lock`` / Türen / Fenster / Alarm am Tesla.
* Keine Fahrzeug-Position in den Donna-Kontext, wenn Live-Guard aktiv ist.

Der HA-Token wird NIE geloggt oder in Fehlermeldungen ausgegeben.
"""
from __future__ import annotations

import logging
import re
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional

import httpx
import yaml

log = logging.getLogger("donna.homeassistant")

# Strikte Formate — verhindern kommaseparierte Entity-Listen, Pfad-Tricks, Leerzeichen.
# \Z statt $ + fullmatch: lehnt auch einen abschließenden Zeilenumbruch ab.
_ID_RE = re.compile(r"[a-z0-9_]+\.[a-z0-9_]+\Z")
_SLUG_RE = re.compile(r"[a-z0-9_]+\Z")
# Fahrzeug-Entities (Tesla): müssen EXPLIZIT in allow_entities stehen (fail-closed),
# damit nicht jede neue Tesla-Entity in einer erlaubten Domain steuerbar wird.
_VEHICLE_RE = re.compile(r"tesla", re.IGNORECASE)
# data-Payload-Schlüssel, die ein HA-Service-Ziel umlenken könnten → immer verboten.
_FORBIDDEN_DATA_KEYS = {
    "entity_id", "entity", "device_id", "area_id", "label_id", "floor_id", "target",
}

# Steuer-/Klammerzeichen + Zeilenumbrüche aus HA-Texten (friendly_name/area/state),
# bevor sie in den Prompt gehen — verhindert Prompt-Struktur-Tricks via HA-Namen.
_UNSAFE_PROMPT_CHARS = re.compile(r"[\r\n\t\[\]{}|]+")


def _sanitize(value: Any, limit: int) -> str:
    return _UNSAFE_PROMPT_CHARS.sub(" ", str(value)).strip()[:limit]


class HAError(Exception):
    """Basisfehler für HA-Interaktion."""


class HADisabled(HAError):
    """HA-Integration nicht konfiguriert/deaktiviert."""


class HANotAllowed(HAError):
    """Service-Call durch Allow-/Deny-Liste blockiert."""


@dataclass
class HAAllowlist:
    """Geladene Allow-/Deny-Regeln. Fail-closed: unbekannt = verboten."""

    allow_domains: set[str] = field(default_factory=set)
    deny_domains: set[str] = field(default_factory=set)
    deny_entities: set[str] = field(default_factory=set)
    # domain -> erlaubte Services. Fail-closed: fehlt der Eintrag, ist KEIN Service erlaubt.
    allow_services: dict[str, set[str]] = field(default_factory=dict)
    # Explizit freigegebene Einzel-Entities. Pflicht für Fahrzeug-Entities (tesla*) und
    # für Domains in explicit_entity_domains (script/scene) — dort ist NUR erlaubt, was
    # hier namentlich steht.
    allow_entities: set[str] = field(default_factory=set)
    # Domains, in denen ausschließlich allow_entities-Einträge erlaubt sind (script/scene
    # können intern beliebige Aktionen auslösen → nur geprüfte Entities zulassen).
    explicit_entity_domains: set[str] = field(default_factory=set)
    # Tesla-Status-Sensoren (read-only, Prefetch)
    status_entities: set[str] = field(default_factory=set)
    # Standort-Entities: NIE in Kontext bei Live-Guard
    location_entities: set[str] = field(default_factory=set)

    @classmethod
    def from_yaml(cls, path: str | Path) -> "HAAllowlist":
        p = Path(path)
        raw = yaml.safe_load(p.read_text(encoding="utf-8")) or {}
        svc_raw = raw.get("allow_services") or {}
        allow_services = {
            str(dom): {str(s) for s in (svcs or [])}
            for dom, svcs in svc_raw.items()
        }
        obj = cls(
            allow_domains={str(d) for d in (raw.get("allow_domains") or [])},
            deny_domains={str(d) for d in (raw.get("deny_domains") or [])},
            deny_entities={str(e) for e in (raw.get("deny_entities") or [])},
            allow_services=allow_services,
            allow_entities={str(e) for e in (raw.get("allow_entities") or [])},
            explicit_entity_domains={str(d) for d in (raw.get("explicit_entity_domains") or [])},
            status_entities={str(e) for e in (raw.get("status_entities") or [])},
            location_entities={str(e) for e in (raw.get("location_entities") or [])},
        )
        # Konsistenz-Validierung — fail-closed: lieber laut scheitern als still Löcher.
        if not obj.allow_domains:
            raise ValueError("allow_domains leer — Allowlist unbrauchbar")
        overlap = obj.allow_domains & obj.deny_domains
        if overlap:
            raise ValueError(f"allow_domains/deny_domains überschneiden sich: {sorted(overlap)}")
        for dom in obj.allow_domains:
            if not obj.allow_services.get(dom):
                raise ValueError(f"Domain '{dom}' erlaubt, aber ohne allow_services (fail-closed)")
        return obj

    def check(self, domain: str, service: str, entity_id: str) -> None:
        """Wirft HANotAllowed, wenn der Call nicht erlaubt ist. Fail-closed."""
        # 0) Striktes Format — blockt Komma-Listen, Leerzeichen, Pfad-Tricks, Nicht-Strings.
        if not isinstance(entity_id, str) or not _ID_RE.fullmatch(entity_id):
            raise HANotAllowed(f"ungültige entity_id: {entity_id!r}")
        if not isinstance(domain, str) or not _SLUG_RE.fullmatch(domain):
            raise HANotAllowed(f"ungültige domain: {domain!r}")
        if not isinstance(service, str) or not _SLUG_RE.fullmatch(service):
            raise HANotAllowed(f"ungültiger service: {service!r}")
        entity_domain = entity_id.split(".", 1)[0]
        # 1) Service-Domain und Entity-Domain müssen übereinstimmen.
        if entity_domain != domain:
            raise HANotAllowed(f"Domain-Mismatch: service={domain} entity={entity_domain}")
        # 2) Entity explizit gesperrt?
        if entity_id in self.deny_entities:
            raise HANotAllowed(f"entity gesperrt: {entity_id}")
        # 3) Domain explizit gesperrt?
        if domain in self.deny_domains:
            raise HANotAllowed(f"domain gesperrt: {domain}")
        # 4) Domain muss erlaubt sein (fail-closed).
        if domain not in self.allow_domains:
            raise HANotAllowed(f"domain nicht erlaubt: {domain}")
        # 5) Service erlaubt? Fail-closed: fehlt der Eintrag, ist nichts erlaubt.
        if service not in (self.allow_services.get(domain) or set()):
            raise HANotAllowed(f"service nicht erlaubt: {domain}.{service}")
        # 6) explicit-only-Domains (script/scene): nur namentlich freigegebene Entities.
        if domain in self.explicit_entity_domains and entity_id not in self.allow_entities:
            raise HANotAllowed(f"nur explizit freigegebene {domain}-Entities erlaubt: {entity_id}")
        # 7) Fahrzeug-Entities (tesla*): müssen explizit in allow_entities stehen.
        if _VEHICLE_RE.search(entity_id) and entity_id not in self.allow_entities:
            raise HANotAllowed(f"Fahrzeug-Entity nicht explizit freigegeben: {entity_id}")


class HomeAssistantService:
    """Async-Fassade zur HA-REST-API mit Allowlist-Enforcement."""

    def __init__(
        self,
        *,
        url: Optional[str],
        token: Optional[str],
        allowlist_path: str,
        enabled: bool = False,
        timeout_s: int = 15,
    ) -> None:
        self._url = (url or "").rstrip("/")
        self._token = token or ""
        self._timeout = timeout_s
        self._allowlist: Optional[HAAllowlist] = None
        # Kurzer Cache für States + Raum-Zuordnung (HA-Abrufe sind teuer; Räume/Geräte
        # ändern sich selten). TTL klein genug, dass Schaltzustände nicht lange veralten.
        self._cache_ttl = 60.0
        self._states_cache: Optional[tuple[float, list[dict[str, Any]]]] = None
        self._areas_cache: Optional[tuple[float, dict[str, str]]] = None
        # Fail-closed: nur aktiv, wenn explizit aktiviert UND vollständig konfiguriert.
        self.enabled = bool(enabled and self._url and self._token)
        if self.enabled:
            try:
                self._allowlist = HAAllowlist.from_yaml(allowlist_path)
                log.info(
                    "ha_service_ready allow_domains=%d deny_entities=%d",
                    len(self._allowlist.allow_domains),
                    len(self._allowlist.deny_entities),
                )
            except Exception as exc:  # noqa: BLE001
                # Ohne Allowlist niemals aktiv — Sicherheit vor Verfügbarkeit.
                self.enabled = False
                log.error("ha_allowlist_load_failed error=%s", exc)

    # --- intern ---
    def _headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self._token}", "Content-Type": "application/json"}

    def _require_enabled(self) -> HAAllowlist:
        if not self.enabled or self._allowlist is None:
            raise HADisabled("Home Assistant ist nicht konfiguriert/aktiviert.")
        return self._allowlist

    async def _request(self, method: str, path: str, *, json: Any = None, expect_text: bool = False) -> Any:
        url = f"{self._url}{path}"
        try:
            async with httpx.AsyncClient(timeout=self._timeout) as client:
                resp = await client.request(method, url, headers=self._headers(), json=json)
            resp.raise_for_status()
            if expect_text:
                return resp.text  # z.B. /api/template liefert text/plain, kein JSON
            if resp.content:
                return resp.json()
            return None
        except httpx.HTTPStatusError as exc:
            # Statuscode ja, aber Body kann Token-frei bleiben — nur Code loggen.
            log.warning("ha_http_error status=%s path=%s", exc.response.status_code, path)
            raise HAError(f"HA HTTP {exc.response.status_code}") from None
        except (httpx.HTTPError, ValueError) as exc:
            # ValueError deckt u.a. resp.json()-Parsefehler und httpx.InvalidURL ab.
            log.warning("ha_request_failed path=%s type=%s", path, type(exc).__name__)
            raise HAError("HA nicht erreichbar") from None

    # --- öffentliche API ---
    async def list_states(self) -> list[dict[str, Any]]:
        self._require_enabled()
        data = await self._request("GET", "/api/states")
        return data if isinstance(data, list) else []

    async def cached_states(self) -> list[dict[str, Any]]:
        """States mit ~60s-Cache (für häufige Smart-Home-Prefetches)."""
        now = time.monotonic()
        if self._states_cache and now - self._states_cache[0] < self._cache_ttl:
            return self._states_cache[1]
        data = await self.list_states()
        if data:  # leeres/Fehler-Ergebnis NICHT cachen (sonst 60s blind)
            self._states_cache = (now, data)
        return data

    async def cached_area_map(self) -> dict[str, str]:
        """Raum-Zuordnung mit ~60s-Cache."""
        now = time.monotonic()
        if self._areas_cache and now - self._areas_cache[0] < self._cache_ttl:
            return self._areas_cache[1]
        data = await self.fetch_area_map()
        if data:  # leere Map (Template-Fehler) NICHT cachen — sonst 60s ohne Raumauflösung
            self._areas_cache = (now, data)
        return data

    async def call_service(
        self,
        domain: str,
        service: str,
        entity_id: str,
        data: Optional[dict[str, Any]] = None,
    ) -> Any:
        """Führt einen HA-Service-Call aus — NUR nach Allowlist-Prüfung.

        Der LLM-gelieferte ``data``-Payload darf KEINE Ziel-Selektoren enthalten
        (entity_id/device_id/area_id/…), sonst ließe sich die geprüfte Entity
        umlenken. Solche Keys werden hart abgelehnt; die geprüfte entity_id wird
        zuletzt gesetzt und gewinnt immer."""
        allowlist = self._require_enabled()
        allowlist.check(domain, service, entity_id)  # wirft HANotAllowed
        payload: dict[str, Any] = {}
        if data:
            # Routinen (script/scene/automation): data KOMPLETT verwerfen. Sonst könnte das LLM
            # über variables/skip_condition ein parametrisiertes Skript auf ein beliebiges Ziel
            # lenken (Security-M1). Eine Routine wird nur ausgelöst, nie parametrisiert.
            if domain in self._ROUTINE_DOMAINS:
                raise HANotAllowed(f"data bei Routinen nicht erlaubt: {domain}")
            bad = _FORBIDDEN_DATA_KEYS & {str(k).lower() for k in data}
            if bad:
                raise HANotAllowed(f"verbotene data-Schlüssel (Ziel-Override): {sorted(bad)}")
            payload.update(data)
        payload["entity_id"] = entity_id  # geprüfte Entity gewinnt, zuletzt gesetzt
        log.info("ha_call_service domain=%s service=%s entity=%s", domain, service, entity_id)
        try:
            return await self._request("POST", f"/api/services/{domain}/{service}", json=payload)
        finally:
            # States-Cache nach jedem Schaltversuch verwerfen (auch bei Fehler) — sonst sieht
            # Donna im Folge-Turn bis zu 60s den alten Zustand und berichtet/schaltet falsch.
            self._states_cache = None

    # Domain-Priorität für die (gedeckelte) Geräteliste — wichtigste zuerst,
    # damit climate/light/scene nicht von vielen number/select/button verdrängt werden.
    # Routinen (scene/script/automation) ZUERST — sie sollen nie aus dem Cap fallen.
    _DOMAIN_PRIORITY = {
        "scene": 0, "script": 1, "automation": 2, "climate": 3, "light": 4,
        "media_player": 5, "switch": 6, "fan": 7, "number": 8, "select": 9, "button": 10,
    }
    _ROUTINE_DOMAINS = frozenset({"scene", "script", "automation"})
    # Routinen stehen in der Priorität vorne, damit sie nicht aus dem Cap fallen —
    # ABER viele Skripte/Szenen/Automationen dürfen die echten Geräte (Licht/Klima/…)
    # nicht aus dem Gesamt-limit verdrängen. Harte Obergrenze für Routinen in der Liste.
    _ROUTINE_SUBCAP = 20

    async def fetch_area_map(self, *, domains: Optional[set[str]] = None) -> dict[str, str]:
        """entity_id -> Raum (Area) über die HA Template-API (area_name()).

        Das /api/states enthält keine Area — die Template-API rendert sie. Nur Entities
        der erlaubten Domains, leere/None-Areas werden weggelassen. Bei Fehler: leeres Dict
        (raum-basierte Auflösung fällt dann einfach weg, Steuerung bleibt möglich)."""
        allowlist = self._require_enabled()
        # Nur validierte Domain-Slugs ins Template interpolieren (fail-closed, kein Template-Break/Injection).
        doms = {d for d in (domains or allowlist.allow_domains) if _SLUG_RE.fullmatch(d)}
        if not doms:
            return {}
        dom_list = "[" + ",".join(f"'{d}'" for d in sorted(doms)) + "]"
        tmpl = (
            "{% for e in states if e.domain in " + dom_list + " %}"
            "{{ e.entity_id }}|{{ area_name(e.entity_id) }}\n{% endfor %}"
        )
        try:
            raw = await self._request("POST", "/api/template", json={"template": tmpl}, expect_text=True)
        except HAError:
            return {}
        out: dict[str, str] = {}
        if isinstance(raw, str):
            for line in raw.splitlines():
                eid, sep, area = line.partition("|")
                if not sep:
                    continue
                eid = eid.strip()
                area = area.strip()
                if eid and area and area.lower() != "none":
                    out[eid] = _sanitize(area, 40)
        return out

    async def controllable_devices(
        self, *, limit: int = 60, states: Optional[list[dict[str, Any]]] = None,
        area_map: Optional[dict[str, str]] = None,
    ) -> list[dict[str, Any]]:
        """Kompakte Liste steuerbarer Entities (für den [Smart-Home]-Prompt-Block).

        Nur Entities aus erlaubten Domains, ohne gesperrte Einzel-Entities. Fahrzeug-
        und explicit-only-Entities nur, wenn explizit freigegeben (so sieht das LLM
        keine entity_id, die es ohnehin nicht steuern darf). Nach Domain-Priorität
        sortiert und auf ``limit`` gedeckelt (Token-Schutz). ``states`` und ``area_map``
        können bereits geholte Abrufe wiederverwenden (sparen Requests)."""
        allowlist = self._require_enabled()
        if states is None:
            states = await self.list_states()
        areas = area_map or {}
        out: list[dict[str, Any]] = []
        for st in states:
            eid = st.get("entity_id") or ""
            if not isinstance(eid, str) or "." not in eid:
                continue
            domain = eid.split(".", 1)[0]
            if domain not in allowlist.allow_domains:
                continue
            if eid in allowlist.deny_entities:
                continue
            # explicit-only-Domains + Fahrzeug-Entities nur zeigen, wenn freigegeben.
            if domain in allowlist.explicit_entity_domains and eid not in allowlist.allow_entities:
                continue
            if _VEHICLE_RE.search(eid) and eid not in allowlist.allow_entities:
                continue
            attrs = st.get("attributes") or {}
            name = _sanitize(attrs.get("friendly_name", eid), 60)  # Prompt-Schutz
            # Fahrzeug- und explizit freigegebene Entities NIE per Raum-Sammelbefehl erfassen
            # ("alles aus im Garage" darf nicht den Tesla schalten) → area=None = Gruppe "Ohne Raum".
            if eid in allowlist.allow_entities or _VEHICLE_RE.search(eid):
                area = None
            else:
                area = areas.get(eid)
            out.append({
                "entity_id": eid, "name": name, "domain": domain,
                "state": _sanitize(st.get("state"), 30), "area": area,
                "is_routine": domain in self._ROUTINE_DOMAINS,  # 5b: Skript/Szene/Automation
            })
        # Explizit freigegebene Entities (Tesla-Klima/Laden/Heizung) ZUERST — sonst würden
        # sie bei vielen Switches aus dem limit-Cap fallen und das LLM kennt ihre entity_id nicht.
        allow = allowlist.allow_entities
        out.sort(key=lambda d: (
            d["entity_id"] not in allow,
            self._DOMAIN_PRIORITY.get(d["domain"], 99),
            d["name"],  # stabile, deterministische Reihenfolge innerhalb einer Domain
        ))
        # Routinen-Sub-Cap: höchstens _ROUTINE_SUBCAP Routinen in die Liste, damit viele
        # Skripte/Szenen nicht die echten Geräte aus dem Gesamt-limit verdrängen (Regression-Fix).
        # allow_entities (Tesla) sind nie Routinen und bleiben unberührt.
        kept: list[dict[str, Any]] = []
        routine_seen = 0
        for d in out:
            if d["is_routine"] and d["entity_id"] not in allow:
                if routine_seen >= self._ROUTINE_SUBCAP:
                    continue
                routine_seen += 1
            kept.append(d)
        return kept[:limit]

    async def tesla_status(
        self, *, include_location: bool = False, states: Optional[list[dict[str, Any]]] = None
    ) -> dict[str, Any]:
        """Liest Tesla-Status-Sensoren. Standort NUR wenn explizit erlaubt
        (include_location=False bei Live-Guard/Stream — Mike-Policy). ``states``
        kann einen bereits geholten /api/states-Abruf wiederverwenden."""
        allowlist = self._require_enabled()
        wanted = set(allowlist.status_entities)
        if include_location:
            wanted |= set(allowlist.location_entities)
        if not wanted:
            return {}
        if states is None:
            states = await self.list_states()
        out: dict[str, Any] = {}
        for st in states:
            eid = st.get("entity_id")
            if eid in wanted:
                attrs = st.get("attributes") or {}
                out[eid] = {
                    "state": st.get("state"),
                    "unit": attrs.get("unit_of_measurement"),
                    "name": str(attrs.get("friendly_name", eid))[:60],
                }
        return out

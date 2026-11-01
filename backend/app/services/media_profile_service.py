"""media_profile_service — Profil-Logik + [Medien-Kontext]-Builder (A2.2/A2.3).

Ordnet quell-spezifische Nutzer kanonischen Profilnamen zu und baut daraus den
[Medien-Kontext]-Prompt-Block: was ein Profil schon gesehen hat + Empfehlungs-Hinweise.

QUELL-UNABHÄNGIGE STRUKTUR, aber JEDE QUELLE FÜHRT IHRE EIGENEN PROFILNAMEN.
Aktiv ist nur Jellyfin mit seinen 4 REALEN Usern (geprüft via GET /Users 2026-10-30):
    ['mike', 'TV', 'Watchparty', 'Yannik']  → normalisiert Mike / TV / Watchparty / Yannik.
Es gibt in Jellyfin KEIN Ämi/Family/DreamDuo.

A3-TODO — Cross-Source-Unifikation (NICHT raten):
    Netflix bringt mit A3 (CSV-Import) eine EIGENE, separate Profilmenge
    (Mike / Ämi / Yannik / Family / DreamDuo). Ob Jellyfin "Watchparty" == Netflix
    "DreamDuo" oder Jellyfin "TV" == Netflix "Family" ist eine offene semantische Frage.
    Default bis Mike es klärt: jede Quelle bleibt bei ihren eigenen Profilnamen, keine
    automatische Zusammenführung.

Stream-Safety: Sehgewohnheiten sind privat. Das Live-Gate sitzt im chat.py
(_load_media_context) — analog Kalender/Health/Smart-Home. Dieser Service formatiert nur.
"""
from __future__ import annotations

import asyncio
import re
from typing import Any, Optional

from app.core.logger import get_logger

log = get_logger("service.media_profile")

# Die 4 REALEN Jellyfin-Profile (= Jellyfin-User, geprüft via GET /Users 2026-10-30).
JELLYFIN_PROFILES: tuple[str, ...] = ("Mike", "TV", "Watchparty", "Yannik")
DEFAULT_PROFILE = "Mike"  # entspricht dem lowercase Jellyfin-User `mike`

# A3-TODO: Netflix führt eine SEPARATE, quell-spezifische Profilmenge (kommt mit A3).
# Nur als Planungs-Doku hier — NICHT mit Jellyfin zusammengeführt (siehe Modul-Docstring).
NETFLIX_PROFILES_PLANNED: tuple[str, ...] = ("Mike", "Ämi", "Yannik", "Family", "DreamDuo")

# B2 — Gemeinsame Profile → ihre Mitglieder-Einzelprofile. Bei einem gemeinsamen Profil
# lädt der Recall media_seen für das gemeinsame Profil UND alle Mitglieder (profilgetrennt),
# damit der [Medien-Kontext] nach Herkunft gruppieren kann (gemeinsam vs. nur-Mitglied-allein).
PROFILE_MEMBERS: dict[str, tuple[str, ...]] = {
    "DreamDuo": ("Mike", "Ämi"),
    "Family": ("Mike", "Ämi", "Yannik"),
}

# A — Eigener, kurzer Timeout NUR für den Jellyfin-Teil des [Medien-Kontext]. Ein langsamer/
# hängender Jellyfin-Call (Tailscale >3s) darf den (Jellyfin-unabhängigen) LTM-Verlauf NICHT
# mitreißen. Bewusst < dem Gesamt-Deckel in chat.py (_MEDIA_CTX_TIMEOUT_S, 3s), damit der
# ltm_seen-Teil nach einem Jellyfin-Timeout noch innerhalb des Budgets gerendert wird.
_JELLYFIN_SEEN_TIMEOUT_S: float = 2.0

# Jellyfin-Nutzername (lowercase) → kanonischer Jellyfin-Profilname. NUR reale User.
# Unbekannte/künftige Jellyfin-User werden in resolve_profile() fail-safe title-cased.
_JELLYFIN_ALIASES: dict[str, str] = {
    "mike": "Mike",
    "tv": "TV",
    "watchparty": "Watchparty",
    "yannik": "Yannik",
}

# Steuer-/Klammerzeichen aus Medien-Titeln (externe Daten) vor dem Prompt entschärfen.
_UNSAFE_TITLE_CHARS = re.compile(r"[\r\n\t\[\]{}|]+")

# B2 — reiner Titel aus einem media_seen-Content ("<Profil> hat auf <Quelle> gesehen: <Titel>").
# Für die Herkunfts-Dedupe werden NUR die Titel verglichen (ohne "X hat gesehen:"-Präfix),
# sonst zählte derselbe Film unter Mike und Ämi nie als "gemeinsam bekannt".
_SEEN_PREFIX_RE = re.compile(r"^.*?\bgesehen:\s*", re.IGNORECASE)


def resolve_profile(source_user: Optional[str]) -> str:
    """Jellyfin-Nutzername → kanonischer Jellyfin-Profilname.

    Reale User: mike→Mike, TV→TV, Watchparty→Watchparty, Yannik→Yannik.
    Unbekannte/künftige Jellyfin-User werden fail-safe title-normalisiert (kein Crash);
    leer → DEFAULT_PROFILE. (Netflix-Profile sind eine separate Quelle, siehe A3-TODO.)
    """
    raw = (source_user or "").strip()
    if not raw:
        return DEFAULT_PROFILE
    key = raw.lower()
    if key in _JELLYFIN_ALIASES:
        return _JELLYFIN_ALIASES[key]
    return raw.title() or DEFAULT_PROFILE


def _clean_title(value: Any) -> str:
    return _UNSAFE_TITLE_CHARS.sub(" ", str(value or "")).strip()


class MediaProfileService:
    """Aggregiert Sehgewohnheiten pro Profil (quell-unabhängig) + baut den Prompt-Block."""

    def __init__(self, jellyfin: Any = None) -> None:
        # Quellen-Registry — aktuell nur Jellyfin. Spätere Quellen (Netflix/YouTube)
        # werden hier ergänzt und in seen_titles() zusammengeführt.
        self._jellyfin = jellyfin

    def ready(self) -> bool:
        """True wenn mindestens eine Quelle bereit ist (aktuell: Jellyfin)."""
        return self._jellyfin is not None and self._jellyfin.ready()

    async def _jellyfin_seen(self, profile: str, limit: int) -> list[dict]:
        """Gesehene Items eines Profils aus Jellyfin. [] wenn Quelle fehlt/kein Match."""
        if self._jellyfin is None or not self._jellyfin.ready():
            return []
        users = await self._jellyfin.list_users()
        user_id = None
        for u in users:
            # Fremd-/Garbage-Antwort kann Nicht-Dict-Elemente enthalten (z.B. [1,2],
            # ["x"]) — überspringen statt AttributeError (sonst kippt der ganze Kontext).
            if not isinstance(u, dict):
                continue
            if resolve_profile(u.get("Name", "")) == profile:
                user_id = u.get("Id")
                break
        if not user_id:
            return []
        return await self._jellyfin.played_items(user_id, limit=limit)

    async def seen_titles(self, profile: str = DEFAULT_PROFILE, *, limit: int = 50) -> list[str]:
        """Titel, die ein Profil schon gesehen hat — quell-unabhängig zusammengeführt.

        Reihenfolge-erhaltende Deduplizierung (case-insensitive).
        """
        titles: list[str] = []
        for item in await self._jellyfin_seen(profile, limit):
            if not isinstance(item, dict):
                continue  # Garbage-Listenelement überspringen (kein Crash)
            name = _clean_title(item.get("Name"))
            if name:
                titles.append(name)
        # Erweiterungspunkt: hier künftig Netflix-/YouTube-Titel desselben Profils anhängen.

        seen: set[str] = set()
        out: list[str] = []
        for t in titles:
            low = t.lower()
            if low not in seen:
                seen.add(low)
                out.append(t)
        return out

    @staticmethod
    def _contents(entries: Optional[list], *, limit: int) -> list[str]:
        """Titel-Zeilen aus media_seen-Einträgen — garbage-resilient, gebounded."""
        out: list[str] = []
        for m in entries or []:
            if not isinstance(m, dict):
                continue  # Garbage-Recall-Element überspringen (kein Crash)
            content = _clean_title(m.get("content", ""))
            if content:
                out.append(content)
        return out[:limit]

    @staticmethod
    def _titles(entries: Optional[list], *, limit: Optional[int] = None) -> list[str]:
        """Reihenfolge-erhaltende, case-insensitive deduplizierte reine TITEL (B2-Vergleich).

        limit=None → KEIN Cap (für die Dedupe-Schnittmenge auf ALLEN geholten Titeln, Runde2
        MAJOR-1/3: sonst bildete sich „beide kennen → gemeinsam" nur aus 8er-Zufallsstichproben
        und wäre auf echten Daten praktisch immer leer).
        """
        seen: set[str] = set()
        out: list[str] = []
        for m in entries or []:
            if not isinstance(m, dict):
                continue
            raw = _clean_title(m.get("content", ""))
            if not raw:
                continue
            title = (_SEEN_PREFIX_RE.sub("", raw).strip() or raw)
            low = title.lower()
            if low in seen:
                continue
            seen.add(low)
            out.append(title)
            if limit is not None and len(out) >= limit:
                break
        return out

    @classmethod
    def _group_origin_titles(
        cls, profile: str, by_origin: dict[str, list[dict]], *, render_limit: int
    ) -> list[tuple[str, list[str]]]:
        """B2-Herkunfts-Gruppierung mit Titel-Dedupe.

        Liefert geordnete (Label-Herkunft, Titel)-Gruppen:
          * "gemeinsam gesehen (<Profil>)" — Titel des gemeinsamen Profils ODER Titel, die
            MEHRERE Mitglieder solo kennen (beide/alle kennen → nicht neu).
          * "nur <Mitglied> allein"        — Titel, die GENAU EIN Mitglied solo kennt und die
            nicht bereits gemeinsam bekannt sind.
        Kein Titel erscheint doppelt (nicht in zwei „nur X allein"-Zeilen, nicht zusätzlich
        gemeinsam). Vergleich case-insensitive auf den reinen Titeln.

        Runde2: die Schnittmenge „kennen ≥2 Mitglieder" wird auf ALLEN geholten Titeln (kein
        Cap) gebildet; ERST die gerenderten Zeilen werden auf `render_limit` gekürzt.
        """
        members = list(PROFILE_MEMBERS.get(profile, ()))
        # Volle Titel-Mengen (ohne Cap) für korrekte Schnittmengen-Bildung.
        shared_titles = cls._titles(by_origin.get(profile))
        member_titles = {m: cls._titles(by_origin.get(m)) for m in members}

        # Wie viele Mitglieder kennen einen Titel solo? (dedupe je Mitglied)
        member_count: dict[str, int] = {}
        for m in members:
            for t in member_titles[m]:
                member_count[t.lower()] = member_count.get(t.lower(), 0) + 1

        common_lower = {t.lower() for t in shared_titles}
        for low, cnt in member_count.items():
            if cnt >= 2:
                common_lower.add(low)

        # gemeinsam: shared zuerst, dann Mehr-Mitglieder-Titel (die nicht aus shared stammen)
        gemeinsam: list[str] = []
        gem_seen: set[str] = set()
        for t in shared_titles:
            if t.lower() not in gem_seen:
                gem_seen.add(t.lower())
                gemeinsam.append(t)
        for m in members:
            for t in member_titles[m]:
                low = t.lower()
                if low in common_lower and low not in gem_seen:
                    gem_seen.add(low)
                    gemeinsam.append(t)

        groups: list[tuple[str, list[str]]] = []
        if gemeinsam:
            groups.append((f"gemeinsam gesehen ({profile})", gemeinsam[:render_limit]))
        for m in members:
            solo = [t for t in member_titles[m] if t.lower() not in common_lower]
            if solo:
                groups.append((f"nur {m} allein", solo[:render_limit]))

        # Fallback: unbekannte Herkünfte (keine Mitglieder/kein gemeinsames Profil) additiv.
        for origin, entries in by_origin.items():
            if origin == profile or origin in members:
                continue
            extra = cls._titles(entries, limit=render_limit)
            if extra:
                groups.append((f"nur {origin} allein", extra))
        return groups

    @staticmethod
    def _prompt_hint(profile: str, is_shared: bool) -> str:
        """Empfehlungs-Hinweis im Block — solo (Default Mike) vs. gemeinsames Profil (B2)."""
        if is_shared:
            members = ", ".join(PROFILE_MEMBERS.get(profile, ()))
            return (
                f"Dies ist ein gemeinsames Profil ({profile}, Mitglieder: {members}). "
                "Bevorzugt vorschlagen, was NIEMAND der Mitglieder und das gemeinsame Profil "
                "schon kennt (echte Neu-Entdeckung). Einen Titel, den EIN Mitglied allein "
                "gesehen hat, nur MIT Vermerk bringen (wer ihn schon allein gesehen hat und "
                "warum er trotzdem passt). Was beide Mitglieder oder das gemeinsame Profil "
                "schon gesehen haben, NICHT als neuen Vorschlag bringen (nur auf ausdrücklichen "
                "Re-Watch-Wunsch)."
            )
        return (
            "Standard = Mike allein. Sagt Mike 'wir/zusammen/mit jemandem' und das Profil ist "
            "unklar (allein / mit Ämi / Family), kurz nachfragen statt zu raten. Sonst direkt "
            "profilgetrennt empfehlen und nichts vorschlagen, was dieses Profil schon gesehen hat."
        )

    async def _safe_jellyfin_titles(
        self, profile: str, *, limit: int, timeout: float | None = None
    ) -> list[str]:
        """A: Jellyfin-Teil in eigenem Timeout + try/except — fail-safe & entkoppelt.

        Ein langsamer/hängender/fehlerhafter Jellyfin-Call (Tailscale >Budget) darf den
        LTM-Verlauf NICHT mitreißen: hier wird er eingekapselt und degradiert zu [].

        timeout: optionaler, vom Aufrufer an den äußeren Deckel gekoppelter Jellyfin-Timeout
            (HIGH-3: innerer = min(2.0, outer−0.5), damit die Entkopplung auch bei kleinem
            MEDIA_CTX_TIMEOUT_S wirksam bleibt). None → Modul-Default _JELLYFIN_SEEN_TIMEOUT_S.
        """
        if self._jellyfin is None or not self._jellyfin.ready():
            return []
        eff_timeout = _JELLYFIN_SEEN_TIMEOUT_S if timeout is None else timeout
        try:
            return await asyncio.wait_for(
                self.seen_titles(profile, limit=limit),
                timeout=eff_timeout,
            )
        except asyncio.TimeoutError:
            log.warning("media_jellyfin_timeout", timeout_s=eff_timeout, profile=profile)
            return []
        except Exception as _je:  # noqa: BLE001
            log.warning("media_jellyfin_failed", error_type=type(_je).__name__, profile=profile)
            return []

    async def build_media_context(
        self,
        *,
        profile: str = DEFAULT_PROFILE,
        ltm_seen: Optional[list[dict]] = None,
        ltm_seen_by_origin: Optional[dict[str, list[dict]]] = None,
        max_items: int = 30,
        jellyfin_timeout: float | None = None,
        emit_hint_if_empty: bool = False,
    ) -> str | None:
        """Baut den [Medien-Kontext]-Block — oder None, wenn nichts bekannt ist.

        Args:
            profile: kanonisches Donna-Profil (Default Mike). Ein gemeinsames Profil
                (PROFILE_MEMBERS) löst die B2-Herkunfts-Gruppierung + Prompt-Regel aus.
            ltm_seen: vorab profil-gefiltert geholte media_seen-Einträge (SOLO-Pfad).
            ltm_seen_by_origin: B2 — media_seen je Herkunft {Profil|Mitglied: [einträge]}.
                Wenn gesetzt, wird nach Herkunft gruppiert + titel-dedupliziert ausgewiesen
                (gemeinsam vs. nur-Mitglied-allein) und ltm_seen ignoriert.
            max_items: Render-Limit — max. Titel je Zeile/Quelle im Block (Runde2: ~30, damit
                der „schon gesehen"-Ausschluss realistisch greift; Dedupe rechnet auf ALLEN
                geholten Titeln, erst die Ausgabe wird auf max_items gekürzt).
            jellyfin_timeout: an den äußeren Deckel gekoppelter Jellyfin-Timeout (HIGH-3).
            emit_hint_if_empty: 2.2 — auch ohne Verlaufsdaten einen (Hinweis-only) Block
                liefern, damit die Nachfrage-/Profil-Instruktion im Medien-Turn ankommt.

        A — Robustheit: der Jellyfin-Teil ist in _safe_jellyfin_titles eingekapselt; der
        LTM-Teil wird IMMER verarbeitet, auch wenn Jellyfin hängt/fehlt.
        """
        lines: list[str] = []

        # --- Jellyfin-Teil (A): eigener Timeout, fail-safe, entkoppelt vom LTM-Verlauf ---
        titles = await self._safe_jellyfin_titles(
            profile, limit=max_items * 3, timeout=jellyfin_timeout
        )
        if titles:
            lines.append(f"Schon gesehen ({profile}): " + ", ".join(titles[:max_items]))

        # --- LTM media_seen — wird IMMER verarbeitet, unabhängig vom Jellyfin-Ergebnis ---
        is_shared = profile in PROFILE_MEMBERS
        if ltm_seen_by_origin:
            # B2: nach Herkunft getrennt + titel-dedupliziert (3.2). Kein Titel doppelt.
            for label, titles_o in self._group_origin_titles(
                profile, ltm_seen_by_origin, render_limit=max_items
            ):
                lines.append(f"{label}: " + "; ".join(titles_o))
        elif ltm_seen:
            mem_lines = self._contents(ltm_seen, limit=max_items)
            if mem_lines:
                lines.append("Schon gesehen (aus dem Verlauf): " + "; ".join(mem_lines))

        has_data = bool(lines)
        if not has_data:
            if not emit_hint_if_empty:
                return None
            lines.append("(Noch keine gespeicherten Verlaufsdaten für dieses Profil.)")

        # 1.3 — Ehrlichkeit: der Verlauf ist ein Auszug, nicht zwingend vollständig.
        honesty = (
            "Hinweis: Das ist ein Auszug des gespeicherten Verlaufs, nicht zwingend vollständig."
            if has_data else ""
        )
        parts = ["[Medien-Kontext]", "\n".join(lines)]
        if honesty:
            parts.append(honesty)
        parts.append(self._prompt_hint(profile, is_shared))
        return "\n".join(parts)

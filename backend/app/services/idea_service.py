"""
idea_service.py — Ideen-Capture für Donna (DONNA-115)

Speichert Ideen in drei Schichten:
  1. LTM (Qdrant/ChromaDB via LTMService) — semantische Suche, PII-Filter inklusive
  2. Obsidian-Vault (Markdown-Datei in vault/ideas/) — menschenlesbar + Syncthing
  3. Graphiti/Neo4j (add_episode_raw) — vernetzte Episoden, Donna zieht automatisch
     Verbindungen zwischen Ideen und früheren Gesprächen

Feature-Flag DONNA_GRAPHITI: wenn false → Graphiti-Layer wird übersprungen,
  Idee wird trotzdem in LTM + Obsidian gespeichert (kein Breaking-Change).

PII-Filter aus ltm_service ist aktiv (über store_memory).

Ideen-Erkennung (Keyword-Confidence, kein LLM-Call):
  detect_idea_intent(text) → float 0.0–1.0, Threshold ≥ 0.65 = Idee erkannt.
"""
from __future__ import annotations

import asyncio
import re
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import TYPE_CHECKING

from app.core.logger import get_logger
from app.services.ltm_service import _contains_pii  # wiederverwendeter PII-Filter

if TYPE_CHECKING:
    from app.services.graphiti_service import GraphitiService
    from app.services.ltm_service import LTMService

log = get_logger("service.ideas")

# ─── Slug / Dateiname ─────────────────────────────────────────────────────────

_SLUG_STRIP_RE = re.compile(r"[^\w\s-]")
_SLUG_WS_RE = re.compile(r"[\s_]+")


def _slugify(text: str, max_len: int = 60) -> str:
    """Erzeugt einen URL/Dateipfad-sicheren Slug aus beliebigem Text."""
    s = text.lower().strip()
    # Umlaute
    for src, dst in [("ä", "ae"), ("ö", "oe"), ("ü", "ue"), ("ß", "ss"),
                     ("Ä", "ae"), ("Ö", "oe"), ("Ü", "ue")]:
        s = s.replace(src, dst)
    s = _SLUG_STRIP_RE.sub("", s)
    s = _SLUG_WS_RE.sub("-", s)
    s = re.sub(r"-{2,}", "-", s).strip("-")
    return s[:max_len] or "idee"


# ─── Idea-Erkennung — Keyword-Confidence ──────────────────────────────────────

_IDEA_KEYWORDS_STRONG = [
    "idee:", "ich hätte gerne", "ich haette gerne",
    "ich brauche eine software", "ich brauche eine app",
    "ich brauche ein tool", "ich brauche ein system",
    "man müsste mal", "man muesste mal",
    "wäre cool wenn", "waere cool wenn",
    "es wäre cool", "es waere cool",
    "stell dir vor", "was wäre wenn", "was waere wenn",
]

_IDEA_KEYWORDS_WEAK = [
    "feature", "automatisch", "automatisieren", "lösung", "loesung",
    "workflow", "skript", "integration", "verbinden", "verbessern",
    "vereinfachen", "müsste", "muesste", "könnte", "koennte",
    "sollte man", "könnte man", "koennte man",
]

_PROBLEM_WORDS = re.compile(
    r"\b(problem|fehler|nervt|kaputt|umständlich|umstaendlich|aufwändig|aufwaendig|manuell|"
    r"vergesse|verliere|zeitaufwendig|schwierig|kompliziert)\b",
    re.IGNORECASE,
)
_SOLUTION_WORDS = re.compile(
    r"\b(automatisch|lösung|loesung|tool|app|system|skript|feature|integration|"
    r"besser|einfacher|schneller|direkt|sofort)\b",
    re.IGNORECASE,
)


def detect_idea_intent(text: str) -> float:
    """Gibt Confidence 0.0–1.0 zurück ob der Text eine Idee enthält.

    Threshold: >= 0.65 → Donna fragt nach / speichert als Idee.
    Kein LLM-Call — nur Keyword-Matching + Heuristik.
    """
    if not text or len(text.strip()) < 20:
        return 0.0

    lower = text.lower()
    confidence = 0.0

    for kw in _IDEA_KEYWORDS_STRONG:
        if kw in lower:
            confidence += 0.4
            break  # nur einmal

    weak_hits = sum(1 for kw in _IDEA_KEYWORDS_WEAK if kw in lower)
    confidence += min(weak_hits, 2) * 0.15

    sentences = [s.strip() for s in re.split(r"[.!?]", text) if s.strip()]
    if len(sentences) >= 2 and _PROBLEM_WORDS.search(text) and _SOLUTION_WORDS.search(text):
        confidence += 0.35

    return min(round(confidence, 2), 1.0)


# ─── Datenstruktur ────────────────────────────────────────────────────────────

@dataclass
class Idea:
    """Strukturierte Idee mit allen Metadaten."""
    id: str
    title: str
    description: str
    raw_input: str
    tags: list[str]
    created_at: datetime
    updated_at: datetime
    source: str  # "chat", "api", "voice"

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "title": self.title,
            "description": self.description,
            "raw_input": self.raw_input,
            "tags": self.tags,
            "created_at": self.created_at.isoformat(),
            "updated_at": self.updated_at.isoformat(),
            "source": self.source,
        }

    @classmethod
    def from_ltm_entry(cls, ltm_id: str, content: str) -> "Idea":
        """Rekonstruiert eine Idea aus einem LTM-Eintrag (best-effort).

        Erwartet content im Format:
          Idee: <title>
          <description>
          [Original: <raw>]
          [Tags: <tag1>, <tag2>]
        """
        now = datetime.now(timezone.utc)
        title = ""
        description = ""
        raw_input = content
        tags: list[str] = []

        lines = content.splitlines()
        remaining: list[str] = []
        for line in lines:
            if line.startswith("Idee: ") and not title:
                title = line[len("Idee: "):].strip()
            elif line.startswith("Original: ") and not raw_input:
                raw_input = line[len("Original: "):].strip()
            elif line.startswith("Tags: "):
                tags = [t.strip() for t in line[len("Tags: "):].split(",") if t.strip()]
            else:
                remaining.append(line)

        if not title:
            title = content[:60].strip()
        description = "\n".join(remaining).strip() or content

        return cls(
            id=ltm_id,
            title=title,
            description=description,
            raw_input=raw_input,
            tags=tags,
            created_at=now,
            updated_at=now,
            source="ltm",
        )


def _idea_to_ltm_content(idea: Idea) -> str:
    """Wandelt eine Idea in einen LTM-Dokument-String um."""
    parts = [f"Idee: {idea.title}", idea.description]
    if idea.raw_input and idea.raw_input != idea.description:
        parts.append(f"Original: {idea.raw_input}")
    if idea.tags:
        parts.append(f"Tags: {', '.join(idea.tags)}")
    return "\n".join(p for p in parts if p.strip())


def _idea_to_obsidian_md(idea: Idea) -> str:
    """Erzeugt Obsidian-Markdown-Inhalt für eine Idee."""
    tags_yaml = "\n".join(f"  - {t}" for t in idea.tags) if idea.tags else "  - idee"
    ts = idea.created_at.strftime("%Y-%m-%d")
    return (
        f"---\n"
        f"id: {idea.id}\n"
        f"title: \"{idea.title}\"\n"
        f"tags:\n{tags_yaml}\n"
        f"source: {idea.source}\n"
        f"created: {ts}\n"
        f"updated: {idea.updated_at.strftime('%Y-%m-%d')}\n"
        f"related_issues:\n"
        f"  - DONNA-115\n"
        f"---\n\n"
        f"# {idea.title}\n\n"
        f"{idea.description}\n\n"
        f"## Kontext\n{idea.raw_input}\n\n"
        f"## Metadaten\n"
        f"- **Quelle:** {idea.source}\n"
        f"- **Erstellt:** {idea.created_at.strftime('%Y-%m-%d %H:%M')} UTC\n"
        f"- **ID:** `{idea.id}`\n"
    )


# ─── IdeaService ──────────────────────────────────────────────────────────────

class IdeaService:
    """Ideen-Capture-Service für Donna.

    Speichert Ideen in:
    - LTM (Qdrant/ChromaDB via LTMService) mit Kategorie 'idea'
    - Obsidian-Vault (vault_path/ideas/<id>-<slug>.md)
    - Graphiti/Neo4j (add_episode_raw, fire-and-forget, group_id='ideas')

    Alle Operationen sind async-safe.
    Graphiti-Calls sind immer fire-and-forget (asyncio.create_task).
    """

    def __init__(
        self,
        ltm: "LTMService",
        vault_path: str,
        graphiti_svc: "GraphitiService | None" = None,
    ) -> None:
        self._ltm = ltm
        self._vault_ideas_dir = Path(vault_path) / "ideas"
        self._vault_ideas_dir.mkdir(parents=True, exist_ok=True)
        self._graphiti = graphiti_svc
        log.info(
            "idea_service_ready",
            vault_ideas_dir=str(self._vault_ideas_dir),
            graphiti_enabled=bool(graphiti_svc and graphiti_svc.enabled()),
        )

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    async def capture_idea(
        self,
        raw_input: str,
        title: str = "",
        description: str = "",
        tags: list[str] | None = None,
        source: str = "api",
    ) -> Idea:
        """Erfasst eine neue Idee und speichert sie in allen Schichten.

        raw_input:   Originaltext (z.B. was Mike gesagt hat)
        title:       Kurztitel (wird aus raw_input abgeleitet wenn leer)
        description: Ausführliche Beschreibung (Fallback: raw_input)
        tags:        Schlagworte (optional)
        source:      Herkunft: "chat", "api", "voice"

        PII-Filter ist über ltm.store_memory() aktiv — PII-haltige Inhalte
        werden abgelehnt (store_memory gibt "" zurück).
        """
        now = datetime.now(timezone.utc)
        idea_id = str(uuid.uuid4())

        if not title:
            title = raw_input[:60].strip().rstrip(".,!?")
        if not description:
            description = raw_input
        resolved_tags = list(tags) if tags else []

        idea = Idea(
            id=idea_id,
            title=title,
            description=description,
            raw_input=raw_input,
            tags=resolved_tags,
            created_at=now,
            updated_at=now,
            source=source,
        )

        # --- Schicht 1: LTM (Qdrant/ChromaDB via LTMService) ---
        ltm_content = _idea_to_ltm_content(idea)
        stored_id = self._ltm.store_memory(
            session_id=f"idea:{idea_id}",
            content=ltm_content,
            category="idea",
        )
        if stored_id:
            log.info("idea_stored_ltm", idea_id=idea_id, ltm_id=stored_id, title=title[:40])
        else:
            log.warning("idea_ltm_store_skipped", idea_id=idea_id, title=title[:40])

        # --- Schicht 2: Obsidian-Vault ---
        self._write_obsidian_file(idea)

        # --- Schicht 3: Graphiti/Neo4j (fire-and-forget) ---
        if self._graphiti and self._graphiti.enabled():
            episode_body = (
                f"{description}\n\n"
                f"Raw: {raw_input}\n"
                f"Tags: {', '.join(resolved_tags)}"
            )
            asyncio.create_task(
                self._graphiti.add_episode_raw(
                    name=f"Idee: {title}",
                    episode_body=episode_body,
                    source_description="idea_capture",
                    group_id="ideas",
                )
            )
            log.info("idea_graphiti_scheduled", idea_id=idea_id)

        return idea

    async def search_ideas(self, query: str, top_k: int = 5) -> list[Idea]:
        """Sucht Ideen in LTM (semantisch) und optional in Graphiti (Graph-Traversal).

        Ergebnisse aus beiden Quellen werden zusammengeführt und dedupliziert.
        """
        # Quelle 1: LTM semantische Suche
        ltm_results = await asyncio.get_event_loop().run_in_executor(
            None,
            lambda: self._ltm.recall_relevant(query=query, top_k=top_k, category="idea"),
        )
        ideas: list[Idea] = []
        seen_ids: set[str] = set()

        for item in ltm_results:
            ltm_id = item.get("id", "")
            if ltm_id in seen_ids:
                continue
            seen_ids.add(ltm_id)
            idea = Idea.from_ltm_entry(
                ltm_id=ltm_id,
                content=item.get("content", ""),
            )
            ideas.append(idea)

        # Quelle 2: Graphiti Graph-Traversal (wenn aktiviert)
        if self._graphiti and self._graphiti.enabled():
            try:
                graph_results = await self._graphiti.search(
                    query=query,
                    top_k=top_k,
                    group_id="ideas",
                )
                for gr in graph_results:
                    fact = gr.get("fact", "")
                    g_uuid = gr.get("uuid", "")
                    if g_uuid and g_uuid not in seen_ids and fact:
                        seen_ids.add(g_uuid)
                        ideas.append(Idea(
                            id=g_uuid,
                            title=fact[:60],
                            description=fact,
                            raw_input=fact,
                            tags=[],
                            created_at=datetime.now(timezone.utc),
                            updated_at=datetime.now(timezone.utc),
                            source="graphiti",
                        ))
            except Exception as e:  # noqa: BLE001
                log.warning("idea_graphiti_search_failed", error=str(e))

        log.info("idea_search_done", query=query[:40], result_count=len(ideas))
        return ideas

    async def list_ideas(self, limit: int = 20) -> list[Idea]:
        """Listet alle gespeicherten Ideen aus dem LTM."""
        all_entries = await asyncio.get_event_loop().run_in_executor(
            None,
            self._ltm.get_all,
        )
        ideas: list[Idea] = []
        for item in all_entries:
            if item.get("category") != "idea":
                continue
            ideas.append(Idea.from_ltm_entry(
                ltm_id=item.get("id", ""),
                content=item.get("content", ""),
            ))
            if len(ideas) >= limit:
                break
        log.info("idea_list_done", count=len(ideas))
        return ideas

    async def update_idea(
        self,
        idea_id: str,
        title: str | None = None,
        description: str | None = None,
        tags: list[str] | None = None,
    ) -> Idea | None:
        """Aktualisiert eine bestehende Idee.

        LTM: delete + re-insert (ChromaDB/Qdrant haben kein natives Update).
        Obsidian: Datei überschreiben.
        Graphiti: neue Episode mit Update-Kontext (Graphiti kennt kein Update).
        """
        # Bestehende Idee aus LTM laden
        all_entries = await asyncio.get_event_loop().run_in_executor(
            None,
            self._ltm.get_all,
        )
        existing: dict | None = None
        for item in all_entries:
            if item.get("id") == idea_id and item.get("category") == "idea":
                existing = item
                break

        if existing is None:
            log.warning("idea_update_not_found", idea_id=idea_id)
            return None

        old_idea = Idea.from_ltm_entry(
            ltm_id=idea_id,
            content=existing.get("content", ""),
        )

        now = datetime.now(timezone.utc)
        updated_idea = Idea(
            id=idea_id,
            title=title if title is not None else old_idea.title,
            description=description if description is not None else old_idea.description,
            raw_input=old_idea.raw_input,
            tags=list(tags) if tags is not None else old_idea.tags,
            created_at=old_idea.created_at,
            updated_at=now,
            source=old_idea.source,
        )

        # LTM: alten Eintrag löschen + neuen anlegen
        self._ltm.delete_memory(idea_id)
        new_ltm_content = _idea_to_ltm_content(updated_idea)
        new_ltm_id = self._ltm.store_memory(
            session_id=f"idea:{idea_id}",
            content=new_ltm_content,
            category="idea",
        )
        log.info("idea_updated_ltm", old_id=idea_id, new_ltm_id=new_ltm_id, title=updated_idea.title[:40])

        # Obsidian: Datei überschreiben
        self._write_obsidian_file(updated_idea)

        # Graphiti: neue Episode mit Update-Kontext (fire-and-forget)
        if self._graphiti and self._graphiti.enabled():
            asyncio.create_task(
                self._graphiti.add_episode_raw(
                    name=f"Idee aktualisiert: {updated_idea.title}",
                    episode_body=(
                        f"Update von Idee {idea_id}.\n"
                        f"Neuer Titel: {updated_idea.title}\n"
                        f"Beschreibung: {updated_idea.description}\n"
                        f"Tags: {', '.join(updated_idea.tags)}"
                    ),
                    source_description="idea_update",
                    group_id="ideas",
                )
            )

        return updated_idea

    # ------------------------------------------------------------------
    # Private helpers
    # ------------------------------------------------------------------

    def _write_obsidian_file(self, idea: Idea) -> None:
        """Schreibt oder überschreibt die Obsidian-Markdown-Datei für eine Idee."""
        slug = _slugify(idea.title)
        # ID-Prefix (8 Zeichen) verhindert Namenskollisionen bei ähnlichen Titeln
        filename = f"{idea.id[:8]}-{slug}.md"
        filepath = self._vault_ideas_dir / filename
        try:
            filepath.write_text(_idea_to_obsidian_md(idea), encoding="utf-8")
            log.info("idea_obsidian_written", path=str(filepath), idea_id=idea.id)
        except Exception as e:  # noqa: BLE001
            log.warning("idea_obsidian_write_failed", error=str(e), idea_id=idea.id)

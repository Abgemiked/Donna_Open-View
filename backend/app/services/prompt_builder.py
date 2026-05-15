"""PromptBuilder — zentraler Prompt-Assembler für alle LLM-Calls (Welle-3).

Ersetzt _build_prompt_with_history() + _build_history_prompt() aus chat.py.
Vorteile: testbar, erweiterbar, kein String-Spaghetti.
"""
from __future__ import annotations
import re
from dataclasses import dataclass, field

# DONNA-32: Koordinaten-Pattern — matcht "YOUR_LAT,YOUR_LON" oder "YOUR_LAT, YOUR_LON"
# Genug Dezimalstellen damit normale Textzahlen nicht als Koords erkannt werden
_COORD_PATTERN = re.compile(
    r'-?\d{1,3}\.\d{4,}\s*,\s*-?\d{1,3}\.\d{4,}'
)


@dataclass
class PromptContext:
    """Alle Inputs für einen LLM-Aufruf."""
    message: str
    history: list[dict[str, str]] = field(default_factory=list)
    ltm_memories: list[dict] = field(default_factory=list)
    brain_hits: list[dict] = field(default_factory=list)
    location_label: str | None = None
    location_city: str | None = None
    weather_data: str | None = None
    frequent_places: list[dict] = field(default_factory=list)
    screen_context: str | None = None
    today: str = ""
    nearby_result: dict | None = None  # {poi, address, distance_km, website, map_url, osm_ok}
    presence_context: str | None = None  # DONNA-98: Geräte-Präsenz (PC/Android, idle, Aktivität)
    calendar_context: str | None = None  # DONNA-107: Kalender-Kontext (nächste Termine, In-Memory-Only)
    schedule_context: str | None = None  # DONNA-148: Stream-Zeitplan als Einzeiler (TTL-gecacht)


def sanitize_ltm_content(content: str) -> str:
    """DONNA-32: Entfernt rohe GPS-Koordinaten aus LTM-Inhalten.

    Ersetzt Koordinaten-Pattern wie "YOUR_LAT,YOUR_LON" durch "[Standort]".
    Verhindert dass LTM-Einträge mit rohen Koords diese ins LLM-Prompt leaken.
    """
    return _COORD_PATTERN.sub("[Standort]", content)


class PromptBuilder:
    """Baut Prompts für Text-LLMs (Gemini/Local) und Message-Arrays für Chat-LLMs (Mistral/Cerebras)."""

    def build_user_prompt(self, ctx: PromptContext, include_history: bool = True) -> str:
        """Für Local LLM + Gemini: vollständiger User-Prompt als String."""
        parts: list[str] = []

        # 1. LTM-Langzeitgedächtnis (DONNA-32: Koordinaten-Sanitizer)
        if ctx.ltm_memories:
            ltm_lines = [
                f"- [{m.get('category','memory')}] {sanitize_ltm_content(m.get('content',''))}"
                for m in ctx.ltm_memories
            ]
            parts.append("[Langzeitgedächtnis über den Nutzer]\n" + "\n".join(ltm_lines))

        # 2. Konversationshistorie
        if include_history and ctx.history:
            parts.append(self._format_history(ctx.history))

        # 3. Wetter (DONNA-74: Koordinaten-Sanitizer als zweiter Schutzwall)
        if ctx.weather_data:
            parts.append(sanitize_ltm_content(ctx.weather_data))

        # 4. Brain-Hits (ChromaDB)
        if ctx.brain_hits:
            hit_lines = [f"- [{h.get('source','')}] {h.get('text','')}" for h in ctx.brain_hits]
            parts.append("[Relevante Brain-Einträge]\n" + "\n".join(hit_lines))

        # 5. Standort-Kontext (DONNA-74: Koordinaten-Sanitizer)
        if ctx.location_label:
            parts.append(f"Standort: {sanitize_ltm_content(ctx.location_label)}")
        if ctx.frequent_places:
            place_lines = [f"- {p.get('place_label', p.get('place_id','?'))} ({p.get('visit_count',0)}x)" for p in ctx.frequent_places[:3]]
            parts.append("[Häufige Orte]\n" + "\n".join(place_lines))

        # 6. Nearby-Result (OpenStreetMap/Overpass) — DONNA-74: Koordinaten-Sanitizer
        if ctx.nearby_result:
            nr = ctx.nearby_result
            nearby_text = sanitize_ltm_content(
                f"Nächster Treffer: {nr.get('poi','')} — {nr.get('address','')} ({nr.get('distance_km',0):.1f} km)"
            )
            if nr.get('website'):
                nearby_text += f" — {nr['website']}"
            parts.append(nearby_text)

        # 7. Screen-Kontext
        if ctx.screen_context:
            parts.append(f"[Letzte App-Aktivität]\n{ctx.screen_context}")

        # 8. Geräte-Präsenz (DONNA-98)
        if ctx.presence_context:
            parts.append(f"[Geräte-Status] {ctx.presence_context}")

        # 9. Kalender-Kontext (DONNA-107) — Kalender-PII nur In-Memory, keine LTM-Persistenz
        if ctx.calendar_context:
            parts.append(ctx.calendar_context)

        # 10. Stream-Zeitplan (DONNA-148) — kompakter Einzeiler, TTL-gecacht
        if ctx.schedule_context:
            parts.append(f"[Stream-Zeitplan] {ctx.schedule_context}")

        # 11. Aktuelle Frage
        parts.append(f"Frage von Mike:\n{ctx.message}")

        return "\n\n".join(p for p in parts if p)

    def build_messages(
        self,
        ctx: PromptContext,
        system_prompt: str,
    ) -> list[dict[str, str]]:
        """Für Mistral/Cerebras: echtes messages-Array mit Multi-Turn-History."""
        # System-Prompt + LTM als Erweiterung (DONNA-32: Koordinaten-Sanitizer)
        sys_content = system_prompt
        if ctx.ltm_memories:
            ltm_lines = [
                f"- [{m.get('category','memory')}] {sanitize_ltm_content(m.get('content',''))}"
                for m in ctx.ltm_memories
            ]
            sys_content += "\n\n[Langzeitgedächtnis über den Nutzer]\n" + "\n".join(ltm_lines)

        messages: list[dict[str, str]] = [{"role": "system", "content": sys_content}]

        # Brain-Hits als System-Ergänzung
        if ctx.brain_hits:
            hit_lines = [f"- [{h.get('source','')}] {h.get('text','')}" for h in ctx.brain_hits]
            brain_msg = "[Relevante Brain-Einträge]\n" + "\n".join(hit_lines)
            messages.append({"role": "system", "content": brain_msg})

        # Konversationshistorie als echte User/Assistant-Messages
        for h in ctx.history:
            role = h.get("role", "user")
            if role not in ("user", "assistant"):
                continue
            messages.append({"role": role, "content": h["content"]})

        # Aktuelle User-Message (mit Wetter/Standort-Kontext)
        # DONNA-74: Koordinaten-Sanitizer als zweiter Schutzwall auf weather_data
        user_parts: list[str] = []
        if ctx.weather_data:
            user_parts.append(sanitize_ltm_content(ctx.weather_data))
        if ctx.nearby_result:
            nr = ctx.nearby_result
            # DONNA-74: Koordinaten-Sanitizer
            nearby_msg = sanitize_ltm_content(
                f"Nächster Treffer: {nr.get('poi','')} — {nr.get('address','')} ({nr.get('distance_km',0):.1f} km)"
            )
            if nr.get('website'):
                nearby_msg += f" — {nr['website']}"
            user_parts.append(nearby_msg)
        if ctx.screen_context:
            user_parts.append(f"[App-Aktivität] {ctx.screen_context}")
        if ctx.presence_context:
            user_parts.append(f"[Geräte-Status] {ctx.presence_context}")
        # DONNA-107: Kalender-Kontext — Kalender-PII nur In-Memory, keine LTM-Persistenz
        if ctx.calendar_context:
            user_parts.append(ctx.calendar_context)
        # DONNA-148: Stream-Zeitplan — kompakter Einzeiler, TTL-gecacht
        if ctx.schedule_context:
            user_parts.append(f"[Stream-Zeitplan] {ctx.schedule_context}")
        user_parts.append(ctx.message)
        messages.append({"role": "user", "content": "\n\n".join(user_parts)})

        return messages

    def _format_history(self, history: list[dict[str, str]]) -> str:
        lines = []
        for msg in history:
            label = "Mike" if msg.get("role") == "user" else "Donna"
            lines.append(f"{label}: {msg.get('content', '')}")
        return "[Gesprächsverlauf]\n" + "\n".join(lines)

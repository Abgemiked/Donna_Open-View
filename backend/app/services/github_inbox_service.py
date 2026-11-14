"""GithubInboxService — Donna legt Aufgaben als GitHub-Issues in der zentralen
privaten Inbox (Abgemiked/donna-inbox) an. Teil des "gemeinsamen Gehirns" Donna <-> PM.

Phase 1 (Issue-Loop): Donna erkennt eine ausdrückliche Aufgaben-Bitte, fragt kurz nach
(wie bei Senden/Anrufen) und legt nach Mikes Bestätigung EIN Issue an — Label `from-donna`
+ `projekt:<slug>`. Der PM listet die offenen `from-donna`-Issues beim Session-Start mit
Mikes EIGENEM gh-Zugang (nicht mit diesem Token) und arbeitet sie nach Absprache auf.

Rückfrage-Mechanik (Review-Rat Phase 1): Der Rückfrage-Zustand lebt NICHT im STM-Text,
sondern in einem server-seitigen Pending-Store (TTL, single-use). Dadurch:
  * gelangt der Marker nie über STM in LTM/mem0/Graphiti/Live-History (Privatsphäre),
  * kann ein doppeltes "ja" kein zweites Issue anlegen (single-use pop),
  * hat das Issue Vorrang vor der Ideen-Erfassung (keine Doppel-Writes).

Sicherheit (Stufe 3 — externer API-Write + Secret):
  * GITHUB_TOKEN (fein granularer PAT, NUR Issues Read&Write) kommt ausschließlich aus der
    .env. Er wird NIE geloggt, nie in Fehlermeldungen/URLs ausgegeben.
  * fail-safe / fail-closed: ohne Token/Repo deaktiviert; JEDER Fehler beim Anlegen → None
    (nie eine Exception in den Request-Pfad, nie einen Erfolg vortäuschen).
  * Donna legt ein Issue NUR nach Mikes verbaler Bestätigung an (strenges, verankertes Gate),
    nur offline (nie im Stream — Live-gated in chat.py).
  * Nur VALIDIERTE Projekt-Labels (KNOWN_PROJECT_SLUGS) — die KI kann keine Labels erfinden.
  * Marker-Syntax in KI-Feldern (title/body) wird neutralisiert (Injection-Defense-in-Depth).
"""
from __future__ import annotations

import json
import re
import time
import unicodedata
import uuid
from datetime import datetime, timezone

import httpx

from app.core.logger import get_logger
# Projekt-Slugs/Aliase/Auflösung aus dem dependency-freien Registry (Single Source — auch von
# der Git-Bridge genutzt → kein Drift zwischen Issue-Labels und Status-Dateinamen).
from app.services.project_registry import (  # noqa: F401 — Re-Export für chat.py/Tests
    KNOWN_PROJECT_SLUGS,
    PROJECT_ALIASES,
    resolve_project_label,
)

log = get_logger("service.github_inbox")

GITHUB_API = "https://api.github.com"
FROM_DONNA_LABEL = "from-donna"


# --- Intent-Erkennung: NUR eine ausdrückliche Capture-Bitte (eng gehalten) ----------------
# Steuert ausschließlich, ob der Anleitungs-Block in den Prompt kommt. Fehltreffer kosten
# nur Tokens, lösen NIE ein Anlegen aus (das Anlegen hängt an Mikes Rückfrage-Bestätigung).
_ISSUE_INTENT_RE = re.compile(
    r"(leg\w*\s+(\w+\s+){0,3}(als\s+)?(issue|aufgabe|task|ticket|to-?do|notiz)"
    r"|(als\s+)?(issue|aufgabe|task|ticket|to-?do)\s+(für|fuer)\s+\w+\s+(an|ein)"
    r"|mach\w*\s+(draus|daraus|ein)\s+(issue|aufgabe|task|ticket)"
    r"|merk\w*\s+(dir\s+)?(das\s+)?(als\s+)?(aufgabe|issue|task)"
    r"|trag\w*\s+(das\s+)?(als\s+)?(aufgabe|issue|task|to-?do)\s+ein"
    r"|(pack|setz)\w*\s+(das\s+)?in\s+die\s+inbox"
    r"|(schreib|erstell)\w*\s+(mir\s+)?(ein\s+)?(issue|ticket|aufgabe)"
    r")",
    re.IGNORECASE,
)


def detect_issue_intent(message: str | None) -> bool:
    """True, wenn Mike AUSDRÜCKLICH ein Issue/eine Aufgabe anlegen will."""
    return bool(_ISSUE_INTENT_RE.search((message or "")[:500]))


# --- Verbale Bestätigung / Ablehnung (STRENG — externer Write!) --------------------------
# Review-Rat Phase 1: Das lockere Ideen-Gate (_IDEA_CONFIRM_VERBAL.search) war zu permissiv
# ("Nein, ist doch klar" hätte ausgelöst). Für das Issue-Anlegen gilt: Ablehnung ZUERST
# prüfen, dann nur ein verankerter, kurzer Zustimmungs-Satz (Vollmatch, max 60 Zeichen).
_ISSUE_REJECT_RE = re.compile(
    r"\b(nein|nee|ne|nö|nicht|kein|keine|lass(\s+es)?|abbruch|abbrechen|stop|stopp|storno|vergiss)\b",
    re.IGNORECASE,
)
_ISSUE_CONFIRM_RE_VERBAL = re.compile(
    r"^\s*(ja|jo|jup|jap|jep|yes|yep|ok|okay|okey|klar|genau|passt|perfekt|gerne|bitte|"
    r"ja\s*bitte|mach(?:s|\s*das|\s*es)?|leg(?:s|\s*das|\s*es)?\s*an|anlegen)[\s!.,]*$",
    re.IGNORECASE,
)


def is_issue_reject(message: str | None) -> bool:
    return bool(_ISSUE_REJECT_RE.search((message or "")[:150]))


def is_issue_confirm(message: str | None) -> bool:
    """True NUR bei kurzer, verankerter Zustimmung ohne Negation (externer Write-Gate)."""
    m = (message or "").strip()
    if not m or len(m) > 60:
        return False
    if is_issue_reject(m):
        return False
    return bool(_ISSUE_CONFIRM_RE_VERBAL.match(m))


# --- Rückfrage-Marker (parallel zu DONNA_IDEA_CONFIRM) -----------------------------------
ISSUE_CONFIRM_PREFIX = "[DONNA_ISSUE_CONFIRM"
ISSUE_CONFIRM_RE = re.compile(r"\[DONNA_ISSUE_CONFIRM:(\{.*?\})\]", re.DOTALL)
# Toleranter Strip: entfernt den Marker ab dem Präfix bis Zeilenende/Textende — auch wenn er
# fehlerhaft geformt ist (z.B. `}]` im Body, Leerzeichen nach dem Namen). Die Anleitung sagt
# dem LLM, den Marker STILL ans Ende zu hängen, daher ist "ab Präfix bis Ende strippen" sicher
# und verhindert, dass ein Marker-Rest in STM/mem0/Graphiti verbleibt (kein LTM-Leak).
_ISSUE_MARKER_STRIP_RE = re.compile(r"\[\s*DONNA_ISSUE_[A-Z_]+.*", re.IGNORECASE | re.DOTALL)


def strip_issue_marker(text: str | None) -> str:
    """Entfernt den (auch fehlerhaft geformten) Issue-Marker robust aus einem Text."""
    if not text:
        return text or ""
    return _ISSUE_MARKER_STRIP_RE.sub("", text).strip()


def parse_issue_confirm(text: str | None) -> tuple[dict | None, str]:
    """Extrahiert [DONNA_ISSUE_CONFIRM:{...}] aus einem Antworttext.

    Returns: (confirm_dict | None, cleaned_text_without_marker).
    Extraktion nur bei wohlgeformtem JSON (sonst fail-closed None, kein Anlegen); das
    Bereinigen nutzt den toleranten Strip (entfernt auch fehlerhafte Marker-Reste).
    """
    if not text:
        return None, text or ""
    data: dict | None = None
    m = ISSUE_CONFIRM_RE.search(text)
    if m:
        try:
            parsed = json.loads(m.group(1))
            if isinstance(parsed, dict):
                data = parsed
        except (json.JSONDecodeError, ValueError):
            data = None
    return data, strip_issue_marker(text)


# --- Server-seitiger Pending-Store (TTL, single-use) -------------------------------------
# Hält den Rückfrage-Zustand je session_id zwischen Turn 1 (Marker) und Turn 2 (Bestätigung).
# Bewusst NICHT im STM, damit der Marker nie in LTM/mem0/Graphiti/Live-History leckt.
# Single-threaded asyncio (ein uvicorn-Worker) → kein Lock nötig.
_PENDING_TTL_S = 900.0   # 15 Min — danach verfällt die Rückfrage
_PENDING_MAX = 256       # Größen-Cap gegen unbegrenztes Wachstum
_pending_issues: dict[str, tuple[float, dict]] = {}


def _clock(now: float | None) -> float:
    return now if now is not None else time.monotonic()


def _prune_pending(now: float) -> None:
    stale = [k for k, (ts, _) in _pending_issues.items() if now - ts > _PENDING_TTL_S]
    for k in stale:
        _pending_issues.pop(k, None)
    if len(_pending_issues) > _PENDING_MAX:
        overflow = sorted(_pending_issues, key=lambda k: _pending_issues[k][0])
        for k in overflow[: len(_pending_issues) - _PENDING_MAX]:
            _pending_issues.pop(k, None)


def remember_pending(
    session_id: str | None, *, projekt_slug: str | None, title: str, body: str,
    now: float | None = None,
) -> str | None:
    """Merkt die offene Rückfrage (Turn 1). projekt_slug ist bereits validiert (oder None).

    Jedes Pending bekommt ein eindeutiges `token` (Identität des konkreten Vorschlags). So
    kann der Aufrufer am Request-Ende ein VERWAISTES Pending (weder bestätigt noch verworfen,
    und nicht durch einen NEUEN Vorschlag ersetzt) gezielt invalidieren — es lebt damit nur
    für den unmittelbar folgenden Turn (wie früher das „bei jedem Folge-Turn verbrauchen").
    Gibt das token zurück. Ebene-2 (Phase A): B4.
    """
    if not session_id:
        return None
    t = _clock(now)
    token = uuid.uuid4().hex
    _pending_issues[session_id] = (
        t, {"projekt": projekt_slug, "title": title or "", "body": body or "", "token": token},
    )
    _prune_pending(t)  # nach dem Insert: hält die Größe hart bei <= _PENDING_MAX
    return token


def drop_pending_if_token(session_id: str | None, token: str | None) -> bool:
    """Invalidiert ein Pending NUR, wenn es noch exakt `token` trägt (B4).

    Verwaistes Pending am Request-Ende wegräumen: Wurde es zwischenzeitlich bestätigt/verworfen
    (take_pending) ODER durch einen neuen Vorschlag ersetzt (neues token), trifft die Bedingung
    nicht zu und nichts passiert. Gibt True zurück, wenn etwas entfernt wurde.
    """
    if not session_id or not token:
        return False
    entry = _pending_issues.get(session_id)
    if entry and entry[1].get("token") == token:
        _pending_issues.pop(session_id, None)
        return True
    return False


def peek_pending(session_id: str | None, now: float | None = None) -> dict | None:
    """Liest die offene Rückfrage, ohne sie zu verbrauchen (None wenn keine/abgelaufen)."""
    if not session_id:
        return None
    entry = _pending_issues.get(session_id)
    if not entry:
        return None
    ts, data = entry
    if _clock(now) - ts > _PENDING_TTL_S:
        _pending_issues.pop(session_id, None)
        return None
    return data


def take_pending(session_id: str | None, now: float | None = None) -> dict | None:
    """Verbraucht die offene Rückfrage (single-use → Schutz gegen Doppel-Anlage)."""
    data = peek_pending(session_id, now=now)
    if data is not None:
        _pending_issues.pop(session_id, None)
    return data


# --- Feld-Sanitisierung + Issue-Komposition ----------------------------------------------
_TITLE_MAX = 120
_BODY_MAX = 4000
# Marker-/Action-Syntax in KI-Feldern neutralisieren (Defense-in-Depth gegen Injection).
# Deckungsgleich mit chat.py (_SHORTHAND_TYPES + _EXTRA_MARKER_TYPES): ALLE Action-/Marker-
# Typen, inkl. navigate/tesla_navigate/play_music/note — sonst bliebe ein eingeschleuster
# Marker dieser Typen in einem Issue-Feld stehen und könnte später vom LLM übernommen werden.
_MARKERISH_RE = re.compile(
    r"\[\s*(DONNA_[A-Z_]+|home_control|create_event|set_alarm|set_timer|"
    r"navigate|tesla_navigate|play_music|note|call|sms|whatsapp|open_url|save_memory)\b",
    re.IGNORECASE,
)
# Zero-Width-/unsichtbare Zeichen, mit denen ein Marker „zerstückelt" werden könnte
# (U+200B..U+200F, U+2060 WORD JOINER, U+FEFF BOM/ZWNBSP).
_ZERO_WIDTH = "​‌‍‎‏⁠﻿"


def _s(v: object) -> str:
    """None-sicherer String (verhindert den Titel 'None' bei fehlendem Feld)."""
    return "" if v is None else str(v)


def _strip_invisible(text: str) -> str:
    """Entfernt unsichtbare/steuernde Zeichen (Newline bleibt erhalten).

    Deckt die Unicode-Kategorien Cc (Steuerzeichen) und Cf (Format-/Zero-Width-Zeichen, inkl.
    Bidi-Overrides, Soft-Hyphen, Word-Joiner) ab — plus das explizite Zero-Width-Set als Gürtel
    und Hosenträger. So lässt sich ein Marker nicht mehr per unsichtbarem Zeichen „zerstückeln".
    """
    out: list[str] = []
    for ch in text:
        if ch == "\n":
            out.append(ch)
            continue
        if ch in _ZERO_WIDTH:
            continue
        if unicodedata.category(ch) in ("Cc", "Cf"):
            continue
        out.append(ch)
    return "".join(out)


def sanitize_issue_field(s: str | None, *, maxlen: int) -> str:
    """Neutralisiert Marker-Syntax, entfernt Steuer-/Format-/Zero-Width-Zeichen, kappt auf maxlen.

    WICHTIG (Reihenfolge, Defense-in-Depth): ZUERST NFKC-normalisieren (zieht Kompatibilitäts-
    varianten wie Fullwidth-Klammern auf ihre ASCII-Form zusammen), DANN unsichtbare Zeichen
    (Cc/Cf + Zero-Width) entfernen, DANN die Marker-Regex — und das in einer Schleife bis zum
    Fixpunkt. Sonst würde ein mit unsichtbaren Zeichen zerstückelter Marker wie
    `[\\x01DONNA_ACTION:…]` nach dem reinen Strippen „heilen" und der Regex ihn verfehlen.
    """
    out = unicodedata.normalize("NFKC", s or "")
    for _ in range(8):  # Fixpunkt (bounded — kein unbegrenztes Schleifen bei pathologischem Input)
        cleaned = _MARKERISH_RE.sub("(", _strip_invisible(out))
        if cleaned == out:
            break
        out = cleaned
    return out.strip()[:maxlen].strip()


def compose_issue(
    projekt_slug: str, title: str, body: str, *, now: datetime | None = None
) -> dict:
    """Baut das finale Issue-Dict (title/body/labels) — deterministisch + sanitisiert.

    labels = ["from-donna", "projekt:<slug>"]. Der Slug MUSS vorher via
    resolve_project_label validiert sein (hier keine Validierung mehr).
    """
    now = now or datetime.now(timezone.utc)
    t = sanitize_issue_field(title, maxlen=_TITLE_MAX) or "Aufgabe von Donna"
    b = sanitize_issue_field(body, maxlen=_BODY_MAX)
    footer = (
        "\n\n---\n"
        f"_Von Donna (Sprachassistentin) erfasst am {now.strftime('%Y-%m-%d %H:%M UTC')}. "
        f"Projekt: {projekt_slug}. Vor der Umsetzung mit Mike absprechen._"
    )
    return {
        "title": t,
        "body": (b + footer).strip(),
        "labels": [FROM_DONNA_LABEL, f"projekt:{projekt_slug}"],
    }


# --- Gated Prompt-Bausteine (werden in chat.py nur offline + wenn ready injiziert) --------
def build_instruction_block(valid_slugs: list[str] | None = None) -> str:
    """STEHENDE Fähigkeit (kein Phrasen-Gate): das LLM erkennt SINNGEMÄSS nach der Bedeutung
    von Mikes Worten, ob er eine Aufgabe in der Inbox anlegen möchte — unabhängig von festen
    Phrasen. Wird NUR offline und nur bei ready() Service eingeblendet (nie im Stream — die
    Inbox ist privat). Das Anlegen selbst hängt weiterhin an Mikes verbaler Bestätigung."""
    slugs = ", ".join(sorted(valid_slugs if valid_slugs is not None else KNOWN_PROJECT_SLUGS))
    return (
        "[Inbox] GITHUB-INBOX — stehende Fähigkeit: Du kannst Aufgaben als Issue in Mikes "
        "zentraler Inbox anlegen. Erkenne SINNGEMÄSS — egal wie Mike es formuliert "
        "(hinterlegen/anlegen/dokumentieren/merken/festhalten/notieren/\"mach draus ein Issue\"/…) "
        "—, ob er das möchte; verlass dich auf die BEDEUTUNG seiner Worte, nicht auf feste Phrasen. "
        "Wenn er es möchte:\n"
        "1. Formuliere einen knappen Titel (max 120 Zeichen) und einen Body (Mikes Anliegen "
        "in seinen Worten, klar und nachvollziehbar). KEINE eckigen oder geschweiften Klammern "
        "in Titel/Body.\n"
        f"2. Bestimme das Projekt und nutze EXAKT einen dieser Slugs: {slugs}.\n"
        "   Gilt der Wunsch für ALLE bzw. mehrere Projekte (z.B. \"für alle Projekte\"), nutze den "
        "Slug \"uebergreifend\" und betone in Titel UND Body AUSDRÜCKLICH, dass es "
        "projektübergreifend für alle Projekte gilt. Lege IMMER nur EIN einziges Issue an "
        "(nie mehrere), und frag NICHT nach, welches einzelne Projekt gemeint ist.\n"
        "3. Frage Mike kurz: \"Soll ich das als Aufgabe für <Projekt> in deiner Inbox "
        "anlegen?\" und hänge GENAU EINEN Marker ans Ende (neue Zeile, still, nicht erklären):\n"
        "[DONNA_ISSUE_CONFIRM:{\"projekt\":\"<slug>\",\"title\":\"<Titel>\",\"body\":\"<Body>\"}]\n"
        "In diesem Fall emittiere KEINEN DONNA_IDEA_CONFIRM-Marker (Issue hat Vorrang).\n"
        "NIEMALS behaupten, das Issue sei angelegt/erledigt, bevor Mike bestätigt hat. Emittiere "
        "den Marker NUR bei echtem Wunsch — nicht, wenn Mike gar keine Aufgabe anlegen will.\n"
    )


def build_result_note(result: dict | None, projekt_slug: str | None) -> str:
    """Ergebnis-Notiz nach einem Anlege-Versuch (Turn 2) — fail-closed ehrlich."""
    if result and result.get("number"):
        return (
            f"[Inbox] SYSTEM-ERGEBNIS: Du hast soeben in der Inbox (Projekt {projekt_slug}) das "
            f"Issue #{result['number']} angelegt. Bestätige Mike das in EINEM kurzen Satz mit der "
            "Nummer. Emittiere KEINEN DONNA_ISSUE_CONFIRM-Marker."
        )
    return (
        "[Inbox] SYSTEM-ERGEBNIS: Das Anlegen des Issues hat NICHT geklappt. Sag Mike ehrlich, "
        "dass es gerade nicht funktioniert hat (keine erfundene Bestätigung), er kann es später "
        "nochmal versuchen. Emittiere KEINEN Marker."
    )


def build_unknown_project_note() -> str:
    """Notiz, wenn das Projekt nicht aufgelöst werden konnte (Turn 2) — ehrliche Nachfrage."""
    return (
        "[Inbox] SYSTEM-ERGEBNIS: Das Projekt war nicht eindeutig, es wurde NICHTS angelegt. "
        "Frag Mike kurz, für welches Projekt die Aufgabe ist, und biete an, sie dann anzulegen. "
        "Emittiere erst nach seiner Antwort wieder einen DONNA_ISSUE_CONFIRM-Marker."
    )


def build_reject_note() -> str:
    """Notiz nach Mikes Ablehnung (Turn 2)."""
    return "[Inbox] Mike hat das Anlegen abgelehnt. Lege NICHTS an, bestätige nur kurz. Kein Marker."


class GithubInboxService:
    """Fail-safe + fail-closed GitHub-Issue-Fassade für die donna-inbox."""

    def __init__(
        self,
        *,
        token: str | None,
        repo: str | None,
        enabled: bool | None = None,
        timeout_s: float = 6.0,
    ) -> None:
        self._token = token or ""
        self._repo = (repo or "").strip()
        self._timeout = timeout_s
        if enabled is None:
            self.enabled = bool(self._token and self._repo)
        else:
            self.enabled = bool(enabled and self._token and self._repo)
        if not self.enabled:
            log.info(
                "github_inbox_disabled",
                reason="GITHUB_TOKEN/Repo fehlt oder deaktiviert",
                repo=self._repo or None,
            )

    def ready(self) -> bool:
        """True wenn Token + Repo vorhanden und aktiviert."""
        return self.enabled

    # --- intern ---
    def _headers(self) -> dict[str, str]:
        # Token wird NIE geloggt — nur hier als Bearer gesetzt.
        return {
            "Authorization": f"Bearer {self._token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "donna-assistentin",
        }

    async def create_issue(
        self,
        *,
        title: str,
        body: str,
        labels: list[str],
        client: httpx.AsyncClient | None = None,
    ) -> dict | None:
        """Legt ein Issue an. Gibt {"number", "url"} zurück oder None (fail-safe).

        Garantie: wirft NIE eine Exception in den Request-Pfad (Catch-all → None).
        Token wird NIE geloggt — nur Repo, Statuscode, Issue-Nummer, Fehlertyp.
        `client` optional injizierbar (Tests via httpx.MockTransport).
        """
        if not self.enabled:
            return None
        url = f"{GITHUB_API}/repos/{self._repo}/issues"
        payload = {"title": title, "body": body, "labels": list(labels)}
        own = client is None
        try:
            if own:
                timeout = httpx.Timeout(self._timeout, connect=min(self._timeout, 3.0))
                client = httpx.AsyncClient(timeout=timeout)
            resp = await client.post(url, headers=self._headers(), json=payload)
            resp.raise_for_status()
            data = resp.json()
            if not isinstance(data, dict):
                log.warning("github_issue_bad_response", repo=self._repo)
                return None
            number = data.get("number")
            if number is None:
                # 2xx ohne Nummer → ehrlich als Fehlschlag behandeln (kein False-Success).
                log.warning("github_issue_no_number", repo=self._repo)
                return None
            log.info("github_issue_created", repo=self._repo, number=number, labels=list(labels))
            return {"number": number, "url": data.get("html_url")}
        except httpx.HTTPStatusError as exc:
            log.warning("github_issue_http_error", repo=self._repo, status=exc.response.status_code)
            return None
        except Exception as exc:  # noqa: BLE001 — fail-safe: NIE Exception in den Request-Pfad
            log.warning("github_issue_failed", repo=self._repo, error_type=type(exc).__name__)
            return None
        finally:
            if own and client is not None:
                try:
                    await client.aclose()
                except Exception:  # noqa: BLE001 — Aufräumen darf das Ergebnis nicht kippen
                    pass

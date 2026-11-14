"""contact_pref_service.py — #1b Kontakt-Präferenz-Lernen.

Deterministischer JSON-Zähl-Store. Lernt, über welchen Kanal (whatsapp / sms / call)
Mike eine Person kontaktiert — getrennt nach Tageszeit (timeslot, Europe/Berlin,
DST-sicher) und Thema (topic) — und liefert eine Aufschlüsselung, aus der Donna den
passenden Kanal KONTEXTUELL vorschlagen kann (sie handelt nie ungefragt).

Architektur-Entscheidung (PLAN_1b): deterministischer Zähl-Store statt mem0/LTM.
Zähl-Präferenzen sind exakt (nicht semantisch), voll TDD-testbar und erzeugen KEINEN
neuen LTM-Recall-/Brain-Hit-Leak-Pfad — der einzige Prompt-Ausgang ist der
fail-closed Kontext-Block in chat.py.

PERSISTENZ (B1): Store unter ``/data/appdata/contact_prefs.json`` (echtes App-DB-Volume,
wie stm.db/mood.db); ``/data/`` selbst ist flüchtig.

ANTI-SELBSTVERSTÄRKUNG (M2 — v1-Limitierung, bewusst dokumentiert):
  * Gezählt wird bei der **Emission** einer Kontakt-Action (LLM-Marker), NICHT bei der
    vom Nutzer bestätigten **Ausführung** (kein Frontend-Callback in v1). Gegenmaßnahmen:
    (a) Mindestbeleg — Vorschlag erst ab Gesamt-Count ≥ _MIN_EVIDENCE je Kontakt;
    (b) Margin ≥ _MARGIN je Dimension (Vorsprung statt Gleichstand); (c) Dimensions-
    Mindestbeleg ≥ _DIM_MIN für by_topic/by_timeslot. Ein echter Ausführungs-Callback
    ist Folge-Ticket, NICHT Teil von #1b.

STORED-PROMPT-INJECTION: name + topic werden am Schreibpunkt saniert (Steuerzeichen/
Newlines raus, name-Längen-Cap, topic NUR aus KNOWN_TOPICS-Allowlist) — die gespeicherten
und später in den Prompt gerenderten Werte sind damit injection-arm.

PRIVACY (Stufe-3-Invariante):
  * Klarnamen/Themen werden NIE im Klartext geloggt — nur ``event``, ``channel``,
    ``count`` und ``error_type`` (nie die Exception-Message, die den Namen tragen könnte).
  * Store-Fehler sind fail-safe: ``record_contact`` fängt JEDE Exception und propagiert
    sie nie. OSError beim Laden bricht das Schreiben ab (kein Überschreiben); korrupte
    Datei (JSON/Decode/kein-dict) wird NUR im Schreibpfad unter Lock nach
    ``.corrupt.<ts>`` verschoben — der Lesepfad benennt NIE um.

Store-Struktur::

    {
      "<contact_key_lower>": {
        "__name__": "<Anzeigename, saniert>",
        "<channel>": {"all": n, "ts:<slot>": n, "tp:<topic>": n, ...}
      }
    }
"""
from __future__ import annotations

import json
import os
import pathlib
import re
import tempfile
import threading
import time
from datetime import datetime, timezone, tzinfo

from app.core.logger import get_logger

log = get_logger("service.contact_pref")

# B1: echtes App-DB-Volume (persistent über Deploys).
_STORE_PATH = pathlib.Path("/data/appdata/contact_prefs.json")

_VALID_CHANNELS: frozenset[str] = frozenset({"whatsapp", "sms", "call"})

# M2-Schwellen.
_MIN_EVIDENCE = 3   # Gesamt-Count je Kontakt, bevor überhaupt ein Vorschlag entsteht
_MARGIN = 2         # Vorsprung top − zweitbester je Dimension (≥, nicht strikt >)
_DIM_MIN = 2        # Mindest-Count der Top-Dimension für by_topic/by_timeslot

# Härtung: Längen-Caps + topic-Key-Obergrenze je Bucket.
_NAME_MAX_LEN = 60
_TOPIC_MAX_LEN = 32
_MAX_TOPIC_KEYS = 16

_NAME_META_KEY = "__name__"

# Platzhalter-Namen, die nie als echter Kontakt zählen (null-Name-Schutz).
_PLACEHOLDER_NAMES: frozenset[str] = frozenset({"none", "null", "undefined", "nan", "n/a"})

# Festes Themen-Vokabular (M1) — topic wird NUR hieraus akzeptiert (Allowlist).
KNOWN_TOPICS: tuple[str, ...] = (
    "arbeit", "termin", "geburtstag", "orga", "privat", "gaming", "gesundheit",
)

_SLOTS: tuple[str, ...] = ("night", "morning", "noon", "afternoon", "evening")

# Steuerzeichen (C0/C1/DEL) + Unicode-Zeilen-/Absatztrenner → entfernen (Injection-Schutz).
_CTRL_RE = re.compile(r"[\x00-\x1f\x7f  ]")

_LOCK = threading.Lock()

# M5: ZoneInfo lazy + gecacht; fehlendes tzdata darf NIE den Chat brechen.
_BERLIN_TZ: tzinfo | None = None
_BERLIN_TZ_RESOLVED = False


class _CorruptStore(Exception):
    """Interne Markierung: Store-Datei ist unlesbar/kein JSON/kein dict."""


def _store_path() -> pathlib.Path:
    return _STORE_PATH


def _berlin_tz() -> tzinfo | None:
    global _BERLIN_TZ, _BERLIN_TZ_RESOLVED
    if _BERLIN_TZ_RESOLVED:
        return _BERLIN_TZ
    try:
        from zoneinfo import ZoneInfo
        _BERLIN_TZ = ZoneInfo("Europe/Berlin")
    except Exception:  # noqa: BLE001 — tzdata fehlt → UTC-Fallback
        _BERLIN_TZ = None
    _BERLIN_TZ_RESOLVED = True
    return _BERLIN_TZ


def _timeslot(dt: datetime) -> str:
    """Tageszeit-Slot in Europe/Berlin (DST-sicher). Naiv → als UTC interpretiert.

    M5-Fallback: ohne auflösbares Europe/Berlin (kein tzdata) wird in UTC eingeordnet.
    Slots: night 0–4 & 23 · morning 5–10 · noon 11–13 · afternoon 14–17 · evening 18–22.
    """
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    tz = _berlin_tz()
    local = dt.astimezone(tz) if tz is not None else dt.astimezone(timezone.utc)
    h = local.hour
    if 5 <= h < 11:
        return "morning"
    if 11 <= h < 14:
        return "noon"
    if 14 <= h < 18:
        return "afternoon"
    if 18 <= h < 23:
        return "evening"
    return "night"


def _clean(s: str) -> str:
    """Steuerzeichen/Newlines entfernen + Whitespace normalisieren (Injection-Schutz)."""
    return " ".join(_CTRL_RE.sub(" ", str(s or "")).split()).strip()


def _has_letter(s: str) -> bool:
    return any(c.isalpha() for c in s)


def _key_of(name) -> str:
    """Kontaktname → kanonischer Store-Key (saniert, lower, Längen-Cap)."""
    return _clean(name).lower()[:_NAME_MAX_LEN]


def _norm_topic(topic) -> str | None:
    """Thema nur aus KNOWN_TOPICS zulassen (Allowlist); sonst None (verwerfen)."""
    if not isinstance(topic, str):
        return None
    t = _clean(topic).lower()[:_TOPIC_MAX_LEN]
    return t if t in KNOWN_TOPICS else None


def _read_store() -> dict:
    """Store lesen (pure). Missing → {}. Korrupt (JSON/Decode/kein-dict) → _CorruptStore.
    IO-Fehler → OSError (propagiert). Benennt NIE um.
    """
    p = _store_path()
    if not p.exists():
        return {}
    try:
        text = p.read_text(encoding="utf-8")
    except UnicodeDecodeError as _e:
        raise _CorruptStore("decode") from _e
    # OSError (Permission/IO) propagiert bewusst.
    try:
        data = json.loads(text)
    except (json.JSONDecodeError, ValueError) as _e:
        raise _CorruptStore("json") from _e
    if not isinstance(data, dict):
        raise _CorruptStore("not-dict")
    return data


def _read_store_safe() -> dict:
    """Lesepfad: nie umbenennen, nie werfen → {} bei korrupt/IO-Fehler."""
    try:
        return _read_store()
    except _CorruptStore:
        log.warning("contact_prefs_corrupt_read_skip")
        return {}
    except OSError as _e:
        log.warning("contact_prefs_read_failed", error_type=type(_e).__name__)
        return {}


def _quarantine_corrupt(p: pathlib.Path) -> None:
    """Korrupte Datei eindeutig beiseitelegen (NUR unter Lock im Schreibpfad)."""
    target = p.with_suffix(p.suffix + f".corrupt.{int(time.time() * 1000)}")
    try:
        p.replace(target)
        log.warning("contact_prefs_corrupt_quarantined")
    except OSError as _e:
        log.warning("contact_prefs_quarantine_failed", error_type=type(_e).__name__)


def _flush(data: dict) -> None:
    """Store atomar schreiben (tmp + fsync + os.replace). Fehler → tmp weg + re-raise."""
    p = _store_path()
    p.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=str(p.parent), prefix=".contact_prefs_", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, p)
    except Exception:
        try:
            if os.path.exists(tmp):
                os.remove(tmp)
        except OSError:
            pass
        raise


def record_contact(name, channel, when_dt: datetime, topic=None) -> bool:
    """Zählt einen Kontaktvorgang (Spuren all / ts:<slot> / tp:<topic>).

    Gezählt bei Emission (siehe M2-Limitierung). Fail-safe: jede Exception → gefangen,
    geloggt OHNE PII (nur error_type), nie propagiert. Ablehnung (False) bei: ungültigem
    Kanal, leerem/number-only/Platzhalter-Namen, OSError beim Laden (kein Überschreiben).
    """
    try:
        ch = str(channel or "").strip().lower()
        display = _clean(name)[:_NAME_MAX_LEN]
        key = display.lower()
        if ch not in _VALID_CHANNELS or not key or key in _PLACEHOLDER_NAMES or not _has_letter(key):
            return False
        slot = _timeslot(when_dt)
        tp = _norm_topic(topic)
        p = _store_path()
        with _LOCK:
            try:
                data = _read_store()
            except _CorruptStore:
                _quarantine_corrupt(p)
                data = {}
            # OSError propagiert hier → äußeres except → False (kein Überschreiben).
            contact = data.get(key)
            if not isinstance(contact, dict):
                contact = {}
                data[key] = contact
            contact.setdefault(_NAME_META_KEY, display or key)
            bucket = contact.get(ch)
            if not isinstance(bucket, dict):
                bucket = {}
                contact[ch] = bucket
            bucket["all"] = int(bucket.get("all", 0) or 0) + 1
            ts_key = "ts:" + slot
            bucket[ts_key] = int(bucket.get(ts_key, 0) or 0) + 1
            if tp:
                tp_key = "tp:" + tp
                _existing = [k for k in bucket if k.startswith("tp:")]
                if tp_key in bucket or len(_existing) < _MAX_TOPIC_KEYS:
                    bucket[tp_key] = int(bucket.get(tp_key, 0) or 0) + 1
            _total = bucket["all"]
            _flush(data)
        # PRIVACY: nur Kanal + Aggregatzahl.
        log.info("contact_pref_recorded", channel=ch, count=_total)
        return True
    except Exception as _e:  # noqa: BLE001 — fail-safe; NIE die Message loggen (PII)
        log.warning("contact_pref_record_failed", error_type=type(_e).__name__)
        return False


def _channels(contact: dict) -> dict[str, dict]:
    return {
        ch: b for ch, b in contact.items()
        if ch in _VALID_CHANNELS and isinstance(b, dict)
    }


def _top_channel_for_key(contact: dict, key: str, *, min_count: int, margin: int) -> str | None:
    """Kanal mit höchstem Zähler für Dimension ``key`` — mit Mindest-Count + Margin.

    Nur wenn top ≥ ``min_count`` UND (top − zweitbester) ≥ ``margin``; sonst None.
    """
    counts = [
        (ch, int(b.get(key, 0) or 0)) for ch, b in _channels(contact).items()
    ]
    counts = [c for c in counts if c[1] > 0]
    if not counts:
        return None
    counts.sort(key=lambda kv: kv[1], reverse=True)
    top_ch, top_n = counts[0]
    second_n = counts[1][1] if len(counts) >= 2 else 0
    if top_n < min_count or (top_n - second_n) < margin:
        return None
    return top_ch


def known_contacts(data: dict | None = None) -> tuple[str, ...]:
    """Store-Keys mit ≥1 gelernter Kanal-Spur (B2-Namensquelle). Rein lesend, fail-safe."""
    try:
        if data is None:
            data = _read_store_safe()
        return tuple(
            key for key, contact in data.items()
            if isinstance(contact, dict) and _channels(contact)
        )
    except Exception as _e:  # noqa: BLE001
        log.warning("contact_prefs_known_failed", error_type=type(_e).__name__)
        return ()


def summarize_contact(name, data: dict | None = None) -> dict | None:
    """Aufschlüsselung der gelernten Kanal-Präferenzen (M1/M2) — oder None.

    Rückgabe::
        {"name": <Anzeigename>, "overall": <ch|None>,
         "by_topic": {<topic>: <ch>}, "by_timeslot": {<slot>: <ch>}}
    None bei unbekanntem Kontakt, Mindestbeleg < _MIN_EVIDENCE oder keinem Signal.
    """
    try:
        if data is None:
            data = _read_store_safe()
        key = _key_of(name)
        if not key:
            return None
        contact = data.get(key)
        if not isinstance(contact, dict):
            return None
        channels = _channels(contact)
        if not channels:
            return None
        total = sum(int(b.get("all", 0) or 0) for b in channels.values())
        if total < _MIN_EVIDENCE:
            return None

        overall = _top_channel_for_key(contact, "all", min_count=1, margin=_MARGIN)

        by_topic: dict[str, str] = {}
        for tk in sorted({k for b in channels.values() for k in b if k.startswith("tp:")}):
            ch = _top_channel_for_key(contact, tk, min_count=_DIM_MIN, margin=_MARGIN)
            if ch:
                by_topic[tk[len("tp:"):]] = ch

        by_timeslot: dict[str, str] = {}
        for slot in _SLOTS:
            ch = _top_channel_for_key(contact, "ts:" + slot, min_count=_DIM_MIN, margin=_MARGIN)
            if ch:
                by_timeslot[slot] = ch

        if not overall and not by_topic and not by_timeslot:
            return None
        return {
            "name": contact.get(_NAME_META_KEY) or key,
            "overall": overall,
            "by_topic": by_topic,
            "by_timeslot": by_timeslot,
        }
    except Exception as _e:  # noqa: BLE001 — fail-safe, nie PII loggen
        log.warning("contact_pref_summarize_failed", error_type=type(_e).__name__)
        return None


def _word_match(term: str, text_lower: str) -> bool:
    return re.search(r"(?<!\w)" + re.escape(term) + r"(?!\w)", text_lower) is not None


def _match_single_contact(message: str, data: dict, aliases: dict | None = None) -> str | None:
    """Genau EINEN gelernten Kontakt in der Nachricht finden (B2 + Alias).

    Kandidaten sind nur Store-Keys mit total ≥ _MIN_EVIDENCE (len ≥ 3). Direkt-Match
    per unicode-Wortgrenze; zusätzlich werden Aliase (``_CONTACT_ALIASES``) read-seitig
    auf ihren kanonischen Key aufgelöst. Mehrdeutig / kein Treffer → None.
    """
    text = (message or "").lower()
    if not text:
        return None
    eligible: set[str] = set()
    for key, contact in data.items():
        if not isinstance(contact, dict) or len(key) < 3:
            continue
        chans = _channels(contact)
        if chans and sum(int(b.get("all", 0) or 0) for b in chans.values()) >= _MIN_EVIDENCE:
            eligible.add(key)
    if not eligible:
        return None

    found: set[str] = set()
    for key in eligible:
        if _word_match(key, text):
            found.add(key)
    if aliases:
        for alias, canonical in aliases.items():
            ckey = _key_of(canonical)
            if ckey in eligible and len(str(alias)) >= 3 and _word_match(str(alias).lower(), text):
                found.add(ckey)
    return next(iter(found)) if len(found) == 1 else None


def contact_pref_block_for(message: str, aliases: dict | None = None, now: datetime | None = None) -> dict | None:
    """End-to-end Read-Block: genau ein gelernter Kontakt → dessen Aufschlüsselung (+Slot).

    EIN Store-Read (``_read_store_safe``), ``data`` wird durchgereicht (kein Doppel-Load).
    Setzt ``now_slot`` (F1), damit das Leseende die aktuelle Tageszeit kennt. fail-safe.
    """
    try:
        data = _read_store_safe()
        key = _match_single_contact(message, data, aliases)
        if not key:
            return None
        summary = summarize_contact(key, data=data)
        if not summary:
            return None
        if now is not None:
            summary["now_slot"] = _timeslot(now)
        return summary
    except Exception as _e:  # noqa: BLE001 — fail-safe
        log.warning("contact_pref_block_failed", error_type=type(_e).__name__)
        return None

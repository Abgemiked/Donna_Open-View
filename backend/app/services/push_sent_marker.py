"""#118: Persistenter „heute schon gesendet"-Marker für Tages-Pushes (Morgen-Briefing, Abend-Check-in).

Datei: ``{"<kind>": {"date": "YYYY-MM-DD", "sent_at": "<ISO>"}}``. Der Tag zählt nach Europe/Berlin.
Enthält bewusst keine Push-Texte oder Personendaten; Logs nur Ereignis + Fehlertyp.
Fehler beim Lesen führen zu „nicht gesendet" (lieber doppelt als verpasst), Fehler beim Schreiben
werden geloggt und nie geworfen.
"""
from __future__ import annotations

import json
import os
import tempfile
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional
from zoneinfo import ZoneInfo

import structlog

log = structlog.get_logger(__name__)

_BERLIN = ZoneInfo("Europe/Berlin")
_lock = threading.Lock()


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _berlin_date(now: Optional[datetime]) -> str:
    return (now or _now()).astimezone(_BERLIN).date().isoformat()


def _load(p: Path) -> tuple[dict, Optional[str]]:
    """Marker lesen -> (Daten, Fehlertyp). Fehlt die Datei: ({}, None). Defekt/unlesbar: ({}, Fehlertyp)."""
    try:
        data = json.loads(p.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return {}, None
    except (OSError, ValueError) as exc:
        return {}, type(exc).__name__
    if not isinstance(data, dict):
        return {}, "NotADict"
    return data, None


def already_sent(kind: str, path, now: Optional[datetime] = None) -> bool:
    """True, wenn für ``kind`` heute (Berlin) schon ein Push erfolgreich markiert wurde. Wirft nie."""
    try:
        with _lock:
            data, err = _load(Path(path))
        if err:
            log.warning("push_marker_read_failed", error_type=err)
        entry = data.get(kind)
        return isinstance(entry, dict) and entry.get("date") == _berlin_date(now)
    except Exception as exc:  # noqa: BLE001
        log.warning("push_marker_read_failed", error_type=type(exc).__name__)
        return False


def mark_sent(kind: str, path, now: Optional[datetime] = None) -> bool:
    """Markiert ``kind`` als heute gesendet (atomar). Gibt False bei Schreibfehler zurück, wirft nie."""
    p = Path(path)
    tmp = None
    try:
        with _lock:
            data, err = _load(p)
            if err:  # Datei vorhanden, aber defekt/unlesbar: Eintrag der anderen Art ist nicht rettbar
                log.warning("push_marker_reset", kind=kind, error_type=err)
            data[kind] = {"date": _berlin_date(now), "sent_at": (now or _now()).isoformat()}
            p.parent.mkdir(parents=True, exist_ok=True)
            fd, tmp = tempfile.mkstemp(dir=str(p.parent), prefix=".push_marker_", suffix=".tmp")
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                json.dump(data, f)
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp, p)
            tmp = None
        return True
    except Exception as exc:  # noqa: BLE001
        log.warning("push_marker_write_failed", kind=kind, error_type=type(exc).__name__)
        if tmp:
            try:
                os.remove(tmp)
            except OSError:
                pass
        return False

"""calendar_token_store — Ablage der Google-Kalender-Zugangsdaten außerhalb der .env (#97, PR 2a).

Zweck: den Refresh-Token ohne Neustart und ohne .env-Bearbeitung erneuern können. Die Ablage ist eine
JSON-Datei, deren Pfad das Setting ``calendar_token_store_path`` (env ``CALENDAR_TOKEN_STORE_PATH``)
nennt. **Standard leer = Ablage aus**, Verhalten wie bisher (Zugangsdaten aus der Umgebung).

Format (nach ``person_id``; nur Dummy-Werte):
    {"version": 1, "persons": {"mike": {"client_id": "...", "client_secret": "...",
                                        "refresh_token": "...", "updated_at": "2026-01-01T00:00:00+00:00"}}}
Client-ID/-Secret stehen im Eintrag, weil ein Refresh-Token an den OAuth-Client gebunden ist.

Sicherheit:
  * Die Datei darf NICHT in einem gesicherten oder synchronisierten Ordner liegen (Datenordner
    ``/data/appdata``, Vault) — ein solcher Pfad wird abgelehnt (Ablage aus, Logzeile).
  * Schreiben atomar (Temp-Datei im selben Ordner + ``os.replace``), Datei wird mit 0600 angelegt.
  * Lesen: unter POSIX wird eine Datei mit Gruppen-/Welt-Rechten nicht verwendet (fail-closed).
    Unter Windows gibt es keine Unix-Modi, dort entfällt die Prüfung.
  * Es wird nie ein Token, Secret, eine Client-ID oder ein Exception-Text geloggt (Exception-Texte
    können Dateiinhalt zitieren) — nur Ereignisname und Fehlertyp.
"""
from __future__ import annotations

import json
import os
import secrets
import stat
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

from app.core.logger import get_logger
from app.services.calendar_roles import DEFAULT_PERSON

log = get_logger("service.calendar_token_store")

STORE_VERSION = 1
MAX_FILE_BYTES = 64 * 1024
DATA_DIR = "/data/appdata"  # gesicherter Datenordner (docker-compose: ./persist -> /data/appdata)
# Feste Sperrliste (zusätzlich zum Vault aus den Settings): Orte, die gesichert oder synchronisiert werden.
#   /data/appdata  ./persist, wird gesichert        /syncthing  Syncthing-Ordner (P2P-Sync)
#   /data/chroma   Vektor-Datenbank-Volume (Backup/Sync-Ziel)
_PROTECTED_FIXED = (DATA_DIR, "/syncthing", "/data/chroma")
_FIELDS = ("client_id", "client_secret", "refresh_token")


@dataclass(frozen=True)
class StoredCredentials:
    # repr=False: ein versehentliches print()/Log des Objekts verrät nichts
    client_id: str = field(repr=False)
    client_secret: str = field(repr=False)
    refresh_token: str = field(repr=False)
    updated_at: str | None = None


def get_settings() -> Any:
    """Settings erst bei Bedarf laden (das Skript läuft ohne pydantic-settings)."""
    from app.config import get_settings as _gs
    return _gs()


def configured_path(settings: Any) -> str | None:
    """Pfad aus dem Setting; leer/Leerraum = Ablage aus (None)."""
    raw = getattr(settings, "calendar_token_store_path", "") or ""
    raw = raw.strip() if isinstance(raw, str) else ""
    return raw or None


def protected_dirs() -> list[str]:
    """Ordner, in denen die Ablage nicht liegen darf: feste Sperrliste (Datenordner, Syncthing, Chroma) und Vault."""
    dirs = list(_PROTECTED_FIXED)
    try:
        vault = getattr(get_settings(), "vault_path", None)
        if isinstance(vault, str) and vault.strip():
            dirs.append(vault)
    except Exception as exc:  # noqa: BLE001 — Settings nicht ladbar: Datenordner bleibt geschützt
        log.warning("calendar_token_store_settings_unavailable", error_type=type(exc).__name__)
    return dirs


def _resolved(p: str | os.PathLike) -> Path:
    return Path(os.path.abspath(os.fspath(p))).resolve()


def path_allowed(path: str, dirs: Iterable[str] | None = None) -> bool:
    """False, wenn der (aufgelöste, symlink-/``..``-bereinigte) Pfad unter einem geschützten Ordner liegt."""
    target = _resolved(path)
    for d in (protected_dirs() if dirs is None else dirs):
        try:
            root = _resolved(d)
        except (OSError, ValueError, RuntimeError):
            continue
        if target == root or root in target.parents:
            return False
    return True


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _parse(raw: bytes) -> dict:
    """Bytes -> geprüftes Dokument. Wirft bei jedem Formatfehler (Aufrufer loggt nur den Typ)."""
    doc = json.loads(raw.decode("utf-8-sig"))
    persons = doc["persons"]
    version = doc["version"]
    if not isinstance(persons, dict) or type(version) is not int or version != STORE_VERSION:
        raise TypeError("format")
    return doc


def _entry_to_credentials(entry: Any) -> StoredCredentials | None:
    if not isinstance(entry, dict):
        return None
    vals = []
    for key in _FIELDS:
        v = entry.get(key)
        if not isinstance(v, str) or not v.strip():
            return None
        vals.append(v.strip())
    ts = entry.get("updated_at")
    return StoredCredentials(vals[0], vals[1], vals[2], ts if isinstance(ts, str) and ts else None)


def inspect_store(
    path: str, person_id: str = DEFAULT_PERSON, protected_dirs: Iterable[str] | None = None,
) -> tuple[StoredCredentials | None, str]:
    """Liest den Eintrag einer Person. Rückgabe: (Zugangsdaten | None, Grund).

    Grund: "ok" | "path_rejected" | "missing" | "permissions" | "invalid" (nie Inhalte). Wirft nie.
    Ablauf ohne Prüfen-dann-Öffnen-Lücke: Pfad EINMAL auflösen (realpath), Sperre auf dem aufgelösten
    Pfad prüfen, genau diesen mit O_NOFOLLOW öffnen, dann am Descriptor fstat (reguläre Datei, unter
    POSIX keine Gruppen-/Welt-Rechte) und erst danach lesen. Im Log darf der konfigurierte Pfad stehen
    (Konfiguration, kein Geheimnis), Inhalte und Exception-Texte nie.
    """
    try:
        real = os.path.realpath(os.fspath(path))
        if not path_allowed(real, protected_dirs):
            log.warning("calendar_token_store_path_rejected", path=path)
            return None, "path_rejected"
        # O_NONBLOCK: eine FIFO am Pfad darf das Öffnen nicht blockieren (sie wird danach abgelehnt).
        fd = os.open(real, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_BINARY", 0)
                     | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NONBLOCK", 0))
        try:
            # fstat am ROH-Descriptor VOR fdopen: unter Linux gelingt open() auf einem Ordner, fdopen würde
            # dann werfen. Der Descriptor wird erst nach bestandener Prüfung an fdopen übergeben und in
            # jedem anderen Zweig im finally geschlossen.
            st = os.fstat(fd)
            if not stat.S_ISREG(st.st_mode):
                log.warning("calendar_token_store_invalid", path=path, error_type="NotRegularFile")
                return None, "invalid"
            if os.name != "nt" and st.st_mode & 0o077:  # POSIX: Gruppen-/Welt-Rechte -> fail-closed
                log.warning("calendar_token_store_permissions", path=path)
                return None, "permissions"
            f = os.fdopen(fd, "rb")
            fd = -1  # Besitz ging an f
        finally:
            if fd >= 0:
                os.close(fd)
        with f:
            blob = f.read(MAX_FILE_BYTES + 1)
        if len(blob) > MAX_FILE_BYTES:
            raise ValueError("zu gross")
        doc = _parse(blob)
        creds = _entry_to_credentials(doc["persons"].get(person_id))
        if creds is None:
            log.warning("calendar_token_store_entry_missing", path=path)
            return None, "invalid"
        return creds, "ok"
    except FileNotFoundError:
        log.info("calendar_token_store_missing", path=path)
        return None, "missing"
    except Exception as exc:  # noqa: BLE001 — nie Exception-Text loggen, nie nach außen werfen
        log.warning("calendar_token_store_invalid", path=path, error_type=type(exc).__name__)
        return None, "invalid"


def load_credentials(
    path: str, person_id: str = DEFAULT_PERSON, protected_dirs: Iterable[str] | None = None,
) -> StoredCredentials | None:
    """Wie inspect_store, aber nur die Zugangsdaten (None bei jedem Problem)."""
    return inspect_store(path, person_id, protected_dirs)[0]


def inspect_for_settings(settings: Any, person_id: str = DEFAULT_PERSON) -> tuple[StoredCredentials | None, str]:
    """Ablage laut Setting: (Zugangsdaten | None, Grund); Grund zusätzlich "store_off" bei leerem Setting."""
    path = configured_path(settings)
    return inspect_store(path, person_id) if path else (None, "store_off")


def load_for_settings(settings: Any, person_id: str = DEFAULT_PERSON) -> StoredCredentials | None:
    """Ablage laut Setting; ``None`` wenn aus oder ungültig."""
    return inspect_for_settings(settings, person_id)[0]


def write_entry(
    path: str, person_id: str, client_id: str, client_secret: str, refresh_token: str,
    *, overwrite: bool = False,
) -> str:
    """Schreibt den Eintrag atomar (0600). Rückgabe: ``updated_at``. Wirft bei Fehlern (z. B. OSError).

    Existiert die Datei, ohne ``overwrite=True`` -> FileExistsError. Mit ``overwrite`` bleiben die
    Einträge anderer Personen erhalten, sofern die alte Datei lesbar ist.
    Es ist bewusst KEIN Schutzordner-Check hier: den erzwingt der Server beim Lesen; das Skript schreibt
    lokal in den Benutzerordner.
    """
    for v in (person_id, client_id, client_secret, refresh_token):
        if not isinstance(v, str) or not v.strip():
            raise ValueError("leerer Wert")
    target = os.path.abspath(path)
    persons: dict = {}
    if os.path.exists(target):
        if not overwrite:
            raise FileExistsError("Datei existiert bereits")
        try:
            with open(target, "rb") as f:
                persons = dict(_parse(f.read(MAX_FILE_BYTES + 1))["persons"])
        except Exception:  # noqa: BLE001 — unlesbare alte Datei wird ersetzt (overwrite ausdrücklich gewollt)
            persons = {}
    updated_at = _now_iso()
    persons[person_id] = {
        "client_id": client_id.strip(), "client_secret": client_secret.strip(),
        "refresh_token": refresh_token.strip(), "updated_at": updated_at,
    }
    payload = json.dumps({"version": STORE_VERSION, "persons": persons}, indent=2).encode("utf-8")

    folder = os.path.dirname(target)
    os.makedirs(folder, mode=0o700, exist_ok=True)
    tmp = os.path.join(folder, f".{os.path.basename(target)}.{secrets.token_hex(6)}.tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0), 0o600)
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(payload)
            f.flush()
            os.fsync(f.fileno())
        if overwrite:
            os.replace(tmp, target)
        else:
            _link_exclusive(tmp, target)
            try:
                os.unlink(tmp)
            except FileNotFoundError:  # Windows-Rückfall (rename) hat die Temp-Datei schon verschoben
                pass
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise
    return updated_at


def _link_exclusive(tmp: str, target: str) -> None:
    """Legt ``target`` nur an, wenn es nicht existiert (sonst FileExistsError) — atomar, ersetzt nie.

    ``os.link`` scheitert unter POSIX und NTFS mit FileExistsError, wenn das Ziel existiert. Wo Hardlinks
    nicht gehen (z. B. exFAT): unter Windows ``os.rename`` (verweigert ein vorhandenes Ziel ebenfalls),
    unter POSIX gibt es keinen gleichwertigen Ersatz -> Fehler weiterreichen.
    """
    try:
        os.link(tmp, target)
    except FileExistsError:
        raise
    except OSError:
        if os.name != "nt":
            raise
        os.rename(tmp, target)  # Windows: FileExistsError, wenn das Ziel existiert

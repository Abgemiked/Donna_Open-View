"""reminder_service.py — Erinnerungen als eigener Dienst (#153).

Eine Erinnerung ist weder Kalendertermin noch Timer: ein Text und ein Zeitpunkt, zu dem Donna per Push
(mit der Kennung der Erinnerung) meldet. Gespeichert in einer eigenen SQLite-Datei, damit sie einen
Neustart des Servers uebersteht (Auftraege im Speicher des Zeitplaners gingen verloren).

Zustaende (status):
  pending    wartet auf ihre Zeit (auch nach "spaeter": neue Zeit, wieder pending)
  sending    vom Versand-Auftrag atomar markiert (genau ein Versand je Eintrag)
  sent       Push ist raus
  done       Mike hat "Erledigt" gemeldet
  cancelled  abgesagt
  missed     beim Nachholen nach einem Neustart zu alt (> 12 Stunden ueberfaellig)
  failed     Versand scheiterte MAX_ATTEMPTS-mal

Haltung: lieber selten doppelt als verpasst — ein beim Absturz haengengebliebenes "sending" wird beim
Start wieder "pending". Keine Texte im Log (nur IDs/Zahlen), kein Netz in diesem Modul.
"""
from __future__ import annotations

import sqlite3
import threading
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Awaitable, Callable
from zoneinfo import ZoneInfo

from app.core.logger import get_logger

log = get_logger("service.reminder")

_UTC = timezone.utc
_BERLIN = ZoneInfo("Europe/Berlin")
DEFAULT_TZ = "Europe/Berlin"

MAX_TEXT_LEN = 200
MAX_AHEAD = timedelta(days=365)           # hoechstens ein Jahr voraus
CATCHUP_WINDOW = timedelta(hours=12)      # Nachholen nach Neustart; aelter = verpasst
# Gestaffelte Wiederholung nach Push-Ausfall: Wartezeit nach dem 1., 2., ... fehlgeschlagenen Versuch.
# Nach dem letzten Versuch (MAX_ATTEMPTS) ist der Eintrag "failed" (in list_reminders sichtbar).
RETRY_BACKOFF = (timedelta(seconds=45), timedelta(minutes=2), timedelta(minutes=5),
                 timedelta(minutes=15), timedelta(minutes=30), timedelta(minutes=60))
MAX_ATTEMPTS = len(RETRY_BACKOFF) + 1     # Obergrenze der Versandversuche je Eintrag
SENDING_TIMEOUT = timedelta(minutes=5)    # haengendes "sending" wird im Betrieb wieder "pending"
MAX_OPEN = 50                             # hoechstens so viele offene (pending/sending) Erinnerungen
MAX_SNOOZE_MINUTES = 7 * 24 * 60          # "Spaeter" hoechstens eine Woche

_WEEKDAYS = ("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")
_OPEN_STATUSES = ("pending", "sending")
_DONE_FROM = ("pending", "sending", "sent", "done")
_SNOOZE_FROM = ("pending", "sending", "sent", "done")
_CANCEL_FROM = ("pending", "failed")

_FMT = "%Y-%m-%dT%H:%M:%SZ"


class ReminderError(ValueError):
    """Ungueltige Eingabe. `message` ist als Werkzeug-Rueckmeldung ans Modell gedacht."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


# ---------------------------------------------------------------------------
# Reine Funktionen
# ---------------------------------------------------------------------------

def _utc_now() -> datetime:
    return datetime.now(_UTC)


def _ts(dt: datetime) -> str:
    return dt.astimezone(_UTC).replace(microsecond=0).strftime(_FMT)


def _parse_ts(value: str) -> datetime:
    return datetime.strptime(value, _FMT).replace(tzinfo=_UTC)


def clean_text(text: Any) -> str:
    """Text 1-200 Zeichen (getrimmt). Kein str()-Cast fremder Typen."""
    if not isinstance(text, str):
        raise ReminderError("text_fehlt", "Der Text der Erinnerung fehlt — bei Unklarheit nachfragen.")
    t = text.strip()
    if not t:
        raise ReminderError("text_fehlt", "Der Text der Erinnerung ist leer — nachfragen, woran erinnert werden soll.")
    if len(t) > MAX_TEXT_LEN:
        raise ReminderError("text_zu_lang", f"Der Text ist zu lang (hoechstens {MAX_TEXT_LEN} Zeichen) — kuerzer fassen.")
    return t


def parse_when(when_iso: Any, now: datetime | None = None) -> datetime:
    """ISO-8601-Zeit MIT Zeitzonen-Offset -> UTC. In der Zukunft, hoechstens ein Jahr voraus."""
    now = (now or _utc_now()).astimezone(_UTC)
    nachfragen = "Zeitangabe ungueltig oder unklar — bei Mike nachfragen (genauer Tag und Uhrzeit), nicht raten."
    if not isinstance(when_iso, str) or not when_iso.strip():
        raise ReminderError("zeit_fehlt", nachfragen)
    try:
        dt = datetime.fromisoformat(when_iso.strip())
        due = dt.astimezone(_UTC).replace(microsecond=0) if dt.tzinfo is not None else None
    except (ValueError, OverflowError):
        raise ReminderError("zeit_ungueltig", nachfragen) from None
    if dt.tzinfo is None:
        raise ReminderError(
            "zeit_ohne_zone",
            "Zeit ohne Zeitzone (Offset). Gib sie mit Offset an (Winterzeit +01:00, Sommerzeit +02:00); "
            "ist Tag oder Uhrzeit unklar, nachfragen.",
        )
    if due <= now:
        raise ReminderError("zeit_vergangen", "Die Zeit liegt nicht in der Zukunft — nachfragen, wann gemeint ist.")
    if due > now + MAX_AHEAD:
        raise ReminderError("zeit_zu_weit", "Die Zeit liegt mehr als ein Jahr voraus — nachfragen, ob das stimmt.")
    return due


def format_due(due_utc: str | datetime, tz: str = DEFAULT_TZ) -> str:
    """'Freitag, 27.11.2026, 11:30 Uhr' in der Zeitzone der Erinnerung (zum Gegenlesen)."""
    dt = due_utc if isinstance(due_utc, datetime) else _parse_ts(due_utc)
    try:
        zone = ZoneInfo(tz)
    except Exception:  # noqa: BLE001
        zone = _BERLIN
    local = dt.astimezone(zone)
    return f"{_WEEKDAYS[local.weekday()]}, {local:%d.%m.%Y}, {local:%H:%M} Uhr"


# ---------------------------------------------------------------------------
# Dienst
# ---------------------------------------------------------------------------

class ReminderService:
    def __init__(self, db_path: str) -> None:
        self._db_path = db_path
        self._lock = threading.Lock()   # eine Instanz: serialisiert; mehrere Instanzen: SQLite-Transaktion
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        self._connect().close()          # legt die Tabelle an (und bei jedem Zugriff neu, falls die Datei fehlt)

    @staticmethod
    def _ensure_schema(conn: sqlite3.Connection) -> None:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS reminders (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                text          TEXT    NOT NULL,
                due_utc       TEXT    NOT NULL,
                tz            TEXT    NOT NULL,
                status        TEXT    NOT NULL,
                created_at    TEXT    NOT NULL,
                sent_at       TEXT,
                attempts      INTEGER NOT NULL DEFAULT 0,
                claimed_at    TEXT,
                next_try_utc  TEXT
            )
            """
        )
        have = {r[1] for r in conn.execute("PRAGMA table_info(reminders)").fetchall()}
        for col in ("claimed_at", "next_try_utc"):      # Migration aelterer Dateien
            if col not in have:
                conn.execute(f"ALTER TABLE reminders ADD COLUMN {col} TEXT")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_reminders_status_due ON reminders(status, due_utc)")

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self._db_path, timeout=10, isolation_level=None, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        self._ensure_schema(conn)
        return conn

    def _tx(self, fn: Callable[[sqlite3.Connection], Any]) -> Any:
        """Eine Schreib-Transaktion (BEGIN IMMEDIATE) — atomar auch ueber Instanzen hinweg."""
        with self._lock:
            conn = self._connect()
            try:
                conn.execute("BEGIN IMMEDIATE")
                try:
                    out = fn(conn)
                    conn.execute("COMMIT")
                    return out
                except BaseException:
                    conn.execute("ROLLBACK")
                    raise
            finally:
                conn.close()

    @staticmethod
    def _row(r: sqlite3.Row | None) -> dict | None:
        return dict(r) if r is not None else None

    # ---- Anlegen / Lesen / Absagen -------------------------------------------------
    def create(self, text: Any, when_iso: Any, *, now: datetime | None = None, tz: str = DEFAULT_TZ) -> dict:
        now = now or _utc_now()
        t = clean_text(text)
        due = parse_when(when_iso, now)

        def _do(conn: sqlite3.Connection) -> int:
            n_open = conn.execute(
                "SELECT COUNT(*) FROM reminders WHERE status IN (?,?)", _OPEN_STATUSES).fetchone()[0]
            if n_open >= MAX_OPEN:
                raise ReminderError(
                    "zu_viele", f"Es gibt schon {MAX_OPEN} offene Erinnerungen — erst welche absagen.")
            cur = conn.execute(
                "INSERT INTO reminders (text, due_utc, tz, status, created_at, attempts) VALUES (?,?,?,?,?,0)",
                (t, _ts(due), tz, "pending", _ts(now)),
            )
            return int(cur.lastrowid)

        rid = self._tx(_do)
        log.info("reminder_created", id=rid)
        return self.get(rid)  # type: ignore[return-value]

    def get(self, rid: int) -> dict | None:
        conn = self._connect()
        try:
            return self._row(conn.execute("SELECT * FROM reminders WHERE id=?", (int(rid),)).fetchone())
        finally:
            conn.close()

    def list_open(self, *, now: datetime | None = None, include_failed: bool = False) -> list[dict]:
        """Noch nicht zugestellte Erinnerungen (pending/sending), nach Zeit sortiert.
        include_failed: auch endgueltig fehlgeschlagene (damit Donna es sagen kann)."""
        statuses = _OPEN_STATUSES + (("failed",) if include_failed else ())
        conn = self._connect()
        try:
            q = ",".join("?" * len(statuses))
            rows = conn.execute(
                f"SELECT * FROM reminders WHERE status IN ({q}) ORDER BY due_utc ASC, id ASC", statuses
            ).fetchall()
            return [dict(r) for r in rows]
        finally:
            conn.close()

    def cancel(self, rid: int) -> bool:
        """Sagt eine wartende (pending) oder fehlgeschlagene Erinnerung ab. Sonst False
        (unbekannt/schon gesendet/abgesagt/gerade im Versand)."""
        def _do(conn: sqlite3.Connection) -> bool:
            q = ",".join("?" * len(_CANCEL_FROM))
            cur = conn.execute(
                f"UPDATE reminders SET status='cancelled' WHERE id=? AND status IN ({q})", (int(rid), *_CANCEL_FROM))
            return cur.rowcount == 1

        ok = bool(self._tx(_do))
        if ok:
            log.info("reminder_cancelled", id=int(rid))
        return ok

    # ---- Versand ------------------------------------------------------------------
    def _expire(self, conn: sqlite3.Connection, now: datetime) -> int:
        cutoff = _ts(now - CATCHUP_WINDOW)
        cur = conn.execute("UPDATE reminders SET status='missed' WHERE status='pending' AND due_utc < ?", (cutoff,))
        return int(cur.rowcount)

    def claim_due(self, *, now: datetime | None = None) -> list[dict]:
        """Holt faellige Eintraege und markiert sie ATOMAR als 'sending' (zaehlt den Versuch).

        Zu alte (mehr als 12 Stunden ueberfaellig) werden 'missed'. Ein Eintrag wird nie zweimal geholt."""
        now = now or _utc_now()

        def _do(conn: sqlite3.Connection) -> list[dict]:
            # haengendes "sending" (Absturz/DB-Fehler nach dem Markieren) im Betrieb wieder offen
            conn.execute(
                "UPDATE reminders SET status='pending' WHERE status='sending' AND "
                "(claimed_at IS NULL OR claimed_at < ?)", (_ts(now - SENDING_TIMEOUT),))
            missed = self._expire(conn, now)
            rows = conn.execute(
                "SELECT * FROM reminders WHERE status='pending' AND due_utc <= ? "
                "AND (next_try_utc IS NULL OR next_try_utc <= ?) ORDER BY due_utc ASC, id ASC",
                (_ts(now), _ts(now)),
            ).fetchall()
            claimed: list[dict] = []
            for r in rows:
                cur = conn.execute(
                    "UPDATE reminders SET status='sending', attempts=attempts+1, claimed_at=?, next_try_utc=NULL "
                    "WHERE id=? AND status='pending'",
                    (_ts(now), r["id"]),
                )
                if cur.rowcount == 1:
                    d = dict(r)
                    d["status"] = "sending"
                    d["attempts"] = int(r["attempts"]) + 1
                    claimed.append(d)
            if missed:
                log.warning("reminders_missed", count=missed)
            return claimed

        return self._tx(_do)

    def mark_sent(self, rid: int, *, now: datetime | None = None) -> None:
        now = now or _utc_now()
        self._tx(lambda c: c.execute(
            "UPDATE reminders SET status='sent', sent_at=? WHERE id=? AND status='sending'", (_ts(now), int(rid))))

    def mark_failed(self, rid: int, *, now: datetime | None = None) -> str:
        """Versand gescheitert: gestaffelt wieder 'pending' (RETRY_BACKOFF), nach dem letzten Versuch 'failed'."""
        now = now or _utc_now()

        def _do(conn: sqlite3.Connection) -> str:
            row = conn.execute("SELECT attempts, status FROM reminders WHERE id=?", (int(rid),)).fetchone()
            if row is None or row["status"] != "sending":
                return row["status"] if row else "unknown"
            attempts = int(row["attempts"])
            if attempts >= MAX_ATTEMPTS:
                conn.execute("UPDATE reminders SET status='failed', next_try_utc=NULL WHERE id=?", (int(rid),))
                return "failed"
            wait = RETRY_BACKOFF[min(attempts, len(RETRY_BACKOFF)) - 1]
            conn.execute("UPDATE reminders SET status='pending', next_try_utc=? WHERE id=?",
                         (_ts(now + wait), int(rid)))
            return "pending"

        new = self._tx(_do)
        if new == "failed":
            log.warning("reminder_failed", id=int(rid))
        return new

    def recover_on_start(self, *, now: datetime | None = None) -> dict:
        """Beim Start: haengengebliebene 'sending' wieder offen (lieber doppelt als verpasst); zu alte 'missed'."""
        now = now or _utc_now()

        def _do(conn: sqlite3.Connection) -> dict:
            back = conn.execute("UPDATE reminders SET status='pending' WHERE status='sending'").rowcount
            missed = self._expire(conn, now)
            return {"reopened": int(back), "missed": int(missed)}

        out = self._tx(_do)
        log.info("reminders_recovered", **out)
        return out

    # ---- Rueckmeldungen der App ---------------------------------------------------
    def mark_done(self, rid: int) -> str | None:
        """'Erledigt'. Gibt den Status zurueck ('done'), den unveraenderten anderen Status (nicht moeglich)
        oder None (unbekannt)."""
        def _do(conn: sqlite3.Connection) -> str | None:
            row = conn.execute("SELECT status FROM reminders WHERE id=?", (int(rid),)).fetchone()
            if row is None:
                return None
            if row["status"] in _DONE_FROM:
                conn.execute("UPDATE reminders SET status='done' WHERE id=?", (int(rid),))
                return "done"
            return str(row["status"])

        return self._tx(_do)

    def snooze(self, rid: int, *, minutes: Any = None, when_iso: Any = None,
               now: datetime | None = None) -> dict | None:
        """'Spaeter': neue Zeit (in `minutes` Minuten ODER `when_iso`), Status wieder 'pending', Zaehler neu.

        None bei unbekannter/nicht mehr verschiebbarer Erinnerung (abgesagt/verpasst/fehlgeschlagen)."""
        now = now or _utc_now()
        if (minutes is None) == (when_iso is None):
            raise ReminderError("spaeter_angabe", "Genau eine Angabe: minutes ODER when_iso.")
        if minutes is not None:
            if isinstance(minutes, bool) or not isinstance(minutes, int) or not (1 <= minutes <= MAX_SNOOZE_MINUTES):
                raise ReminderError("spaeter_minuten", f"minutes muss eine ganze Zahl von 1 bis {MAX_SNOOZE_MINUTES} sein.")
            due = (now + timedelta(minutes=minutes)).astimezone(_UTC).replace(microsecond=0)
        else:
            due = parse_when(when_iso, now)

        def _do(conn: sqlite3.Connection) -> bool:
            q = ",".join("?" * len(_SNOOZE_FROM))
            cur = conn.execute(
                f"UPDATE reminders SET status='pending', due_utc=?, sent_at=NULL, attempts=0, "
                f"claimed_at=NULL, next_try_utc=NULL "
                f"WHERE id=? AND status IN ({q})",
                (_ts(due), int(rid), *_SNOOZE_FROM),
            )
            return cur.rowcount == 1

        if not self._tx(_do):
            return None
        log.info("reminder_snoozed", id=int(rid))
        return self.get(rid)


# ---------------------------------------------------------------------------
# Versand-Auftrag (vom Zeitplaner alle ~45 s aufgerufen)
# ---------------------------------------------------------------------------

PushFn = Callable[..., Awaitable[bool]]


async def dispatch_due(svc: ReminderService, push: PushFn, *, now: datetime | None = None) -> dict:
    """Holt faellige Erinnerungen und sendet je Eintrag EINEN Push (hohe Prioritaet).

    Der Push traegt nur den Text (keine Zusatzfloskeln) und die Kennzeichen `donna_reminder` + `rid=<id>`,
    an denen die App eine Erinnerung samt Kennung erkennt (Popup mit 'Erledigt'/'Spaeter')."""
    import asyncio

    claimed = await asyncio.to_thread(svc.claim_due, now=now)
    sent = failed = 0
    for r in claimed:
        # je Eintrag geschuetzt: ein Fehler bei einem Eintrag laesst die uebrigen nicht haengen
        try:
            ok = False
            try:
                ok = bool(await push(
                    body=r["text"], title="Erinnerung", priority=5,
                    tags=["donna_reminder", f"rid={r['id']}"],
                ))
            except Exception as exc:  # noqa: BLE001
                log.warning("reminder_push_error", id=r["id"], error_type=type(exc).__name__)
            if ok:
                await asyncio.to_thread(svc.mark_sent, r["id"], now=now)
                sent += 1
            else:
                await asyncio.to_thread(svc.mark_failed, r["id"], now=now)
                failed += 1
        except Exception as exc:  # noqa: BLE001 — z. B. "database is locked": bleibt "sending", Sweep holt es zurueck
            log.warning("reminder_dispatch_entry_error", id=r["id"], error_type=type(exc).__name__)
            failed += 1
    return {"sent": sent, "failed": failed}


# ---------------------------------------------------------------------------
# Vertrauenspruefung am Nutzertext (wie bei device_action): Erinnerungen entstehen/verschwinden nur auf
# ausdruecklichen Wunsch der Nutzernachricht selbst — nie wegen Text aus Gedaechtnis, Kalender oder Web.
# ---------------------------------------------------------------------------

import re as _re  # noqa: E402

_UMLAUTS = str.maketrans({"ä": "ae", "ö": "oe", "ü": "ue", "ß": "ss"})
_YES_RE = _re.compile(
    r"^\s*(ja|jo|jep|jup|yes|ok|okay|gerne|gern|bitte|klar|unbedingt|sehr gerne|passt|mach (das|es)|"
    r"mach das bitte|sicher)\b")
# Lieber zu weit als zu eng: das Modell muss ohnehin selbst aufrufen, fremder Text steht nicht in der Nutzernachricht.
_WEEKDAY = r"montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag"
_CREATE_RES = (
    _re.compile(r"erinner"),                                              # erinnere/erinnerst/erinnert/Erinnerung
    _re.compile(r"\bbescheid\s+(?:\w+\s+)?(?:sag|geb|gib)\w*|\b(?:sag|geb|gib)\w*\s+(?:\w+\s+){0,2}?bescheid\b"),
    _re.compile(r"\bdenk\w*\b.*\b(?:daran|dran)\b"),
    _re.compile(r"\bvergiss\w*\s+(?:\w+\s+)?nicht\b|\bnicht\s+vergessen\b"),
    _re.compile(r"\bsag\w*\s+mir\s+(?:\w+\s+){0,2}?(?:in|um|am|morgen|uebermorgen|spaeter|nachher|heute|gleich|"
                + _WEEKDAY + r")\b"),
    _re.compile(r"\bremind me\b"),
)
# Verneinung/Abbestellung ist KEIN create (das ist eine Absage)
_NEG_RE = _re.compile(r"nicht mehr|keine erinnerung|\bhoer\w*\s+auf\b|abbestell|nie wieder")
# Absage: nur zusammen mit "erinner"
_CANCEL_RES = (
    _re.compile(r"loesch|streich|storni|absag|abgesagt|abbrech|entfern|abbestell|sein lassen|nicht mehr"),
    _re.compile(r"\bhoer\w*\s+auf\b"),
    _re.compile(r"\bvergiss(?!\w*\s+(?:\w+\s+)?nicht\b)\w*"),
    _re.compile(r"\blass\w*\b.*\bsein\b"),
    _re.compile(r"erinnerung\w*\b[^.!?\n]*\b(?:weg|raus)\b|\b(?:weg|raus)\b[^.!?\n]*erinnerung"),
    _re.compile(r"\bbrauch\w*\b[^.!?\n]*\bnicht\b"),
    _re.compile(r"\b(?:sag|mach)\w*\b.*\bab\b"),
)


def _norm(text: Any) -> str:
    return str(text).lower().translate(_UMLAUTS) if isinstance(text, str) else ""


def _last_question(prev_assistant: Any) -> str:
    """Letzter Fragesatz der vorigen Donna-Antwort ('' wenn keine Frage). Satzgrenzen: . ! ? gefolgt von
    Leerraum sowie Zeilenumbruch — ein Punkt in '9.30' trennt nicht."""
    prev = _norm(prev_assistant)
    if "?" not in prev:
        return ""
    head = prev[:prev.rindex("?")]
    return _re.split(r"(?<=[.!?])\s+|\n+", head)[-1]


def cancel_requested(user_text: Any, prev_assistant: Any = None) -> bool:
    """Will die Nutzernachricht selbst eine Erinnerung absagen (oder bestaetigt sie Donnas Absage-Frage)?"""
    t = _norm(user_text)
    if not t.strip():
        return False
    if "erinner" in t and any(rx.search(t) for rx in _CANCEL_RES):
        return True
    if _YES_RE.search(t):
        q = _last_question(prev_assistant)
        if "erinner" in q and any(rx.search(q) for rx in _CANCEL_RES):
            return True
    return False


def reminder_requested(user_text: Any, prev_assistant: Any = None) -> bool:
    """Verlangt die Nutzernachricht selbst eine Erinnerung? Oder bestaetigt sie ('ja') Donnas eigene
    Erinnerungs-Rueckfrage (#154) — nur wenn der LETZTE Fragesatz der vorigen Antwort diese Frage war?"""
    t = _norm(user_text)
    if not t.strip():
        return False
    if _NEG_RE.search(t) or cancel_requested(t, None):
        return False
    if any(rx.search(t) for rx in _CREATE_RES):
        return True
    if _YES_RE.search(t):
        q = _last_question(prev_assistant)
        return "erinner" in q and not any(rx.search(q) for rx in _CANCEL_RES)
    return False

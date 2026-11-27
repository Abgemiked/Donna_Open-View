"""habit_patterns.py — Gewohnheiten statt Einzelfakten (#182, Phase 3a/3b).

Verdichtet vorhandene Daten OHNE LLM und OHNE Fremdkanal zu wenigen LTM-Eintraegen:
  * rhythm.nach_stream / rhythm.werktage — Handy-Ruhefenster nach Streamabenden vs. freien Abenden,
    Werktage vs. Wochenende (aus ``rhythm_daily`` + Twitch-VODs)
  * media.geschmack_mike — Geschmacksprofil aus dem Medienverlauf (NUR Aggregate: Mengen je Quelle,
    wiederkehrende Titel-WOERTER; keine Rohtitel-Liste, nur Mikes eigenes Profil)

Regeln: je ``habit_key`` genau ein Eintrag (Ersetzen statt Anhaengen, siehe rhythm_service.publish_habits);
erst ab sinnvoller Datenmenge, sonst keine Aussage ("noch zu wenig Daten"); Konfidenz + Datenbasis im Text;
Gesundheit/Medien sind live und im Twitch-Pfad gesperrt (health_privacy.RHYTHM_PRIVATE_RE).
"""
from __future__ import annotations

import re
import statistics
from collections import Counter
from datetime import date, datetime, timedelta, timezone
from typing import Any

from app.core.logger import get_logger
from app.services import rhythm_service as rs

log = get_logger("service.habit_patterns")

HABIT_NACH_STREAM = "rhythm.nach_stream"
HABIT_WERKTAGE = "rhythm.werktage"
HABIT_GESCHMACK = "media.geschmack_mike"

_MIN_DATA_DAYS = 14          # darunter keine Wochenmuster
_MIN_GROUP_NIGHTS = 3        # je Vergleichsgruppe
_MIN_MEDIA_ENTRIES = 50      # darunter kein Geschmacksprofil
MEDIA_PROFILE = _MEDIA_PROFILE = "Mike"      # nur Mikes eigenes Profil (Ämi/Yannik/Family = Dritte, nicht ohne Bestaetigung)

_NOT_ENOUGH = "noch zu wenig Daten"

SEEN_RE = _SEEN_RE = re.compile(r"^(?P<profile>.+?) hat auf (?P<src>.+?) gesehen:\s*(?P<title>.+)$", re.IGNORECASE)


# =============================================================================================
# 3a — Wochenmuster
# =============================================================================================

def _stream_days(streams: list[dict]) -> set[date]:
    """Lokale Stream-Tage; Starts vor 06:00 zaehlen zum Vortag (Nachtstream)."""
    out: set[date] = set()
    for s in streams:
        st = rs._parse_ts(s.get("start_ts"))
        if st is None:
            continue
        lt = st.astimezone(rs.TZ)
        out.add((lt - timedelta(hours=6)).date())
    return out


def _median_window(rows: list[dict]) -> tuple[str, str, int, str] | None:
    """(start, ende, n, konfidenz) der Ruhefenster-Zeilen; None bei zu wenig."""
    if len(rows) < _MIN_GROUP_NIGHTS:
        return None
    starts = [rs._axis_minutes(rs._parse_ts(r["rest_start"])) for r in rows]
    ends = [rs._axis_minutes(rs._parse_ts(r["rest_end"])) for r in rows]
    spread = (max(starts) - min(starts) + max(ends) - min(ends)) / 4.0
    return (rs._fmt_axis(int(statistics.median(starts))), rs._fmt_axis(int(statistics.median(ends))),
            len(rows), rs._conf_label(len(rows), spread))


def week_patterns(rows: list[dict], streams: list[dict]) -> dict[str, Any]:
    """Vergleichsgruppen aus rhythm_daily. Leeres Ergebnis = zu wenig Daten (ehrlich)."""
    data_days = [r for r in rows if (r.get("samples") or 0) >= rs._MIN_SAMPLES_DAY]
    out: dict[str, Any] = {"data_days": len(data_days), "nach_stream": None, "werktage": None}
    if len(data_days) < _MIN_DATA_DAYS:
        return out
    nights = [r for r in rows if r.get("rest_minutes") and (r.get("rest_conf") or 0) >= rs._MIN_CONF
              and r.get("rest_start") and r.get("rest_end")]
    sdays = _stream_days(streams)
    if sdays:
        # Nur Naechte NACH dem aeltesten bekannten Stream: davor kennen wir die Streams nicht (VOD-Retention)
        # und duerfen sie nicht als "streamfrei" werten.
        first = min(sdays)
        covered = [r for r in nights if (date.fromisoformat(r["day"]) - timedelta(days=1)) >= first]
        # Nacht endet am Tag D -> sie folgt dem Abend D-1
        after_stream = [r for r in covered if (date.fromisoformat(r["day"]) - timedelta(days=1)) in sdays]
        after_free = [r for r in covered if (date.fromisoformat(r["day"]) - timedelta(days=1)) not in sdays]
        a, b = _median_window(after_stream), _median_window(after_free)
        if a and b:
            out["nach_stream"] = {"stream": a, "frei": b}
    work = [r for r in nights if date.fromisoformat(r["day"]).weekday() < 5]
    weekend = [r for r in nights if date.fromisoformat(r["day"]).weekday() >= 5]
    a, b = _median_window(work), _median_window(weekend)
    if a and b:
        out["werktage"] = {"werktage": a, "wochenende": b}
    return out


def week_habit_texts(p: dict[str, Any], now: datetime | None = None) -> dict[str, str]:
    out: dict[str, str] = {}
    stand = (now or datetime.now(timezone.utc)).astimezone(rs.TZ).strftime("%Y-%m")
    n = p.get("nach_stream")
    if n:
        s, f = n["stream"], n["frei"]
        out[HABIT_NACH_STREAM] = (
            f"(Muster {HABIT_NACH_STREAM}) Nach Streamabenden liegt Mikes Handy-Ruhefenster wahrscheinlich bei {s[0]}–{s[1]} Uhr "
            f"({s[2]} Naechte), nach streamfreien Abenden bei {f[0]}–{f[1]} Uhr ({f[2]} Naechte). "
            f"Konfidenz {_worse(s[3], f[3])}, Datenbasis {p['data_days']} Tage, Stand {stand}. Naeherung aus Bildschirm-aus-Zeiten, kein gemessener Schlaf.")
    w = p.get("werktage")
    if w:
        a, b = w["werktage"], w["wochenende"]
        out[HABIT_WERKTAGE] = (
            f"(Muster {HABIT_WERKTAGE}) Mikes Handy-Ruhefenster liegt in Naechten vor Werktagen (Aufwachtag Mo–Fr) wahrscheinlich bei {a[0]}–{a[1]} Uhr ({a[2]} Naechte), "
            f"in Naechten vor dem Wochenende (Aufwachtag Sa/So) bei {b[0]}–{b[1]} Uhr ({b[2]} Naechte). "
            f"Konfidenz {_worse(a[3], b[3])}, Datenbasis {p['data_days']} Tage, Stand {stand}. "
            "Naeherung aus Bildschirm-aus-Zeiten, kein gemessener Schlaf.")
    return out


def _worse(a: str, b: str) -> str:
    order = ["niedrig", "mittel", "hoch"]
    return order[min(order.index(a), order.index(b))]


def render_week(p: dict[str, Any]) -> str:
    """Kurzauskunft fuer das Tool (topic=woche); ehrlich bei zu wenig Daten."""
    texts = week_habit_texts(p)
    if not texts:
        return (f"Wochenmuster: {_NOT_ENOUGH} (Datenbasis {p.get('data_days', 0)} Tage; ab {_MIN_DATA_DAYS} Tagen mit je "
                f"mind. {_MIN_GROUP_NIGHTS} Naechten pro Vergleichsgruppe).")
    return "\n".join(t.split(") ", 1)[1] for t in texts.values())


# =============================================================================================
# 3b — Geschmacksprofil (nur Aggregate)
# =============================================================================================

def taste_profile(entries: list[dict], now: datetime | None = None) -> dict[str, Any] | None:
    """Aggregat aus media_seen-Eintraegen: NUR Mengen je Quelle. None = zu wenig Daten.
    Bewusst keine Titel(-woerter): Titel koennen Rueckschluesse auf Gesundheit/Religion/Politik/Dritte erlauben."""
    now = now or datetime.now(timezone.utc)
    per_src: Counter = Counter()
    for e in entries:
        m = _SEEN_RE.match(e.get("content") or "")
        if not m or m.group("profile").strip().lower() != _MEDIA_PROFILE.lower():
            continue
        per_src[m.group("src").strip()] += 1
    total = sum(per_src.values())
    if total < _MIN_MEDIA_ENTRIES:
        return None
    return {"total": total, "sources": dict(per_src.most_common()), "stand": now.astimezone(rs.TZ).strftime("%Y-%m")}


def taste_text(p: dict[str, Any], genre_counts: dict[str, dict[str, Any]] | None = None) -> str:
    """``genre_counts``: Zaehler je Quelle aus genre_profile (nur Aggregate). Ohne ausgebbare Genres bleibt es
    bei der ehrlichen Aussage 'Genres liegen nicht vor'."""
    from app.services import genre_profile as gp
    src = ", ".join(f"{k} {v}" for k, v in p["sources"].items())
    head = f"(Muster {HABIT_GESCHMACK}) Mikes Sehverlauf (Datenbasis {p['total']} Eintraege, Stand {p['stand']}): {src}. "
    gs = gp.genre_sentence(genre_counts or {})
    if gs:
        return head + gs + " Konfidenz mittel. Nur fuer Empfehlungen im privaten Chat."
    return head + "Genres liegen nicht vor, daher keine Aussage zu Vorlieben; Konfidenz niedrig. Nur fuer Empfehlungen im privaten Chat."


# =============================================================================================
# Orchestrierung (taeglicher Job)
# =============================================================================================

def _load_genre_counts() -> dict[str, dict[str, Any]]:
    """Genre-Zaehler (nur Aggregate) aus dem Cache neben der LTM-DB; fail-safe {}."""
    try:
        from app.config import get_settings
        from app.services import genre_profile as gp
        return gp.load_counts(gp.cache_path(get_settings().ltm_db_path))
    except Exception as e:  # noqa: BLE001
        log.warning("habit_genre_counts_failed", error_type=type(e).__name__)
        return {}


def refresh_habits_sync(service: rs.RhythmService, ltm: Any, now: datetime | None = None) -> dict[str, Any]:
    """Berechnet Wochenmuster + Geschmacksprofil und veroeffentlicht je Key genau einen Eintrag."""
    res: dict[str, Any] = {}
    wp = week_patterns(service.rows(days=60), service.stream_rows())
    res["week"] = rs.publish_habits(ltm, {}, texts=week_habit_texts(wp, now))
    try:
        entries = ltm.list_by_category("media_seen", profile=_MEDIA_PROFILE, limit=10000)
    except Exception as e:  # noqa: BLE001
        log.warning("habit_media_list_failed", error_type=type(e).__name__)
        entries = []
    tp = taste_profile(entries, now)
    genres = _load_genre_counts()
    res["taste"] = rs.publish_habits(ltm, {}, texts={HABIT_GESCHMACK: taste_text(tp, genres)} if tp else {},
                                     category="user_preference")
    log.info("habit_refresh_done", week=len(res["week"]), taste=len(res["taste"]), media=len(entries))
    return res


async def refresh_habits(service: rs.RhythmService, ltm: Any) -> dict[str, Any]:
    import asyncio
    return await asyncio.to_thread(refresh_habits_sync, service, ltm)

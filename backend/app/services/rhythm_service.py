"""rhythm_service.py — Tagesrhythmus / Ruhefenster (#182, Phase 2).

Berechnet OHNE LLM und reproduzierbar Tages-/Wochenmuster aus Rohdaten:
  * Handy-Heartbeat (alle ~5 Min, Feld ``screen_on``)  -> Aktivzeiten, Ruhefenster
  * activity_recognition                               -> Bewegungs-Zaehler je Tag
  * Twitch-VODs                                        -> Stream-Ist (Tage/Zeiten/Dauer)
  * Health Connect (sleep_start/sleep_end/sleep_hours) -> gemessener Schlaf, SOBALD vorhanden

Wichtige Leitplanken (Gesundheits-/Schlafdaten = sensibel, Mike-only):
  * Die Tracking-DB loescht nach 7 Tagen -> eigene Aggregattabelle ``rhythm_daily``
    (``rhythm.db``) mit NUR Aggregaten: keine Koordinaten, keine App-Namen, keine Texte.
  * Das Handy-Ruhefenster ist eine NAEHERUNG aus Bildschirm-aus-Zeiten. Es wird nie als
    "Schlaf" ausgegeben, immer als "Ruhefenster/wahrscheinlich" mit Konfidenz + Datenbasis.
  * Kein Import aus Twitch-/Viewer-Pfaden; Zugriff nur ueber den privaten Tool-Pfad (Mike).
  * LTM: je ``habit_key`` genau ein ``user_habit``-Eintrag (idempotent, ersetzt die alte Version).
"""
from __future__ import annotations

import json
import re
import sqlite3
import statistics
from collections import Counter
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable
from zoneinfo import ZoneInfo

from app.core.logger import get_logger

log = get_logger("service.rhythm")

TZ = ZoneInfo("Europe/Berlin")

# --- Tuning (alle Werte bewusst konservativ) ---------------------------------------------
_STEP_MIN = 5                    # Heartbeat-Takt der Android-App
_MAX_BRIDGE_MIN = 120            # Heartbeat-Luecken bis hierher gelten als "Handy ruhig", darueber = unbekannt
_BLIP_SAMPLES = 2                # bis zu 2 aufeinanderfolgende screen_on-Samples (<=10 Min) = kurzes Handy-Tippen
_MIN_REST_MIN = 180              # kuerzere Ruhefenster zaehlen nicht
_NIGHT_FROM_H = 20               # Nachtfenster fuer Tag D: D-1 20:00 ...
_NIGHT_TO_H = 16                 # ... bis D 16:00 (Streamnaechte enden spaet)
_MIN_SAMPLES_DAY = 60            # < 5 h Heartbeats = Tag zu luecken­haft fuer Aktivzeiten
_MIN_NIGHTS = 3                  # unter 3 Naechten keine Aussage
_MIN_CONF = 0.4                  # Naechte mit geringerer Konfidenz fliessen nicht in Muster ein
_STREAM_LOOKBACK_DAYS = 56
_MIN_STREAMS = 3

_WEEKDAYS = ["Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"]
_HABIT_PREFIX_RE = re.compile(r"^\(Muster ((?:rhythm|media|feedback)\.[a-z_]+)\)")

HABIT_RUHEFENSTER = "rhythm.ruhefenster"
HABIT_AKTIVZEITEN = "rhythm.aktivzeiten"
HABIT_STREAM = "rhythm.streamzeiten"


# =============================================================================================
# Reine Funktionen (ohne I/O) — vollstaendig testbar
# =============================================================================================

def _parse_ts(value: Any) -> datetime | None:
    if isinstance(value, datetime):
        dt = value
    else:
        try:
            dt = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        except (TypeError, ValueError):
            return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def clean_heartbeats(events: Iterable[dict]) -> list[tuple[datetime, bool]]:
    """Heartbeats -> sortierte, entdoppelte (ts_utc, screen_on)-Liste. Ohne screen_on-Feld = verworfen."""
    seen: dict[datetime, bool] = {}
    for ev in events:
        ts = _parse_ts(ev.get("ts"))
        on = ev.get("screen_on")
        if ts is None or on is None:
            continue
        seen[ts] = bool(on)
    return sorted(seen.items())


def local_day(ts: datetime) -> date:
    return ts.astimezone(TZ).date()


def night_window(day: date) -> tuple[datetime, datetime]:
    """Nachtfenster, das an ``day`` (lokal) endet, in UTC."""
    start = datetime(day.year, day.month, day.day, _NIGHT_FROM_H, tzinfo=TZ) - timedelta(days=1)
    end = datetime(day.year, day.month, day.day, _NIGHT_TO_H, tzinfo=TZ)
    return start.astimezone(timezone.utc), end.astimezone(timezone.utc)


def stream_intervals(sessions: Iterable[dict]) -> list[tuple[datetime, datetime]]:
    """Twitch-VODs -> (start, ende) in UTC. Waehrend eines Streams ist Mike am PC: ein ruhiges Handy
    heisst dann NICHT 'Ruhefenster' (Hauptfehlerquelle der Handy-Naeherung)."""
    out = []
    for sess in sessions:
        st = _parse_ts(sess.get("start_ts"))
        if st is not None:
            out.append((st, st + timedelta(seconds=int(sess.get("duration_s") or 0))))
    return out


def mask_streams(samples: list[tuple[datetime, bool]], intervals: list[tuple[datetime, datetime]]) -> list[tuple[datetime, bool]]:
    """Samples innerhalb eines Streams zaehlen als 'aktiv' (screen_on=True)."""
    if not intervals:
        return samples
    return [(t, True if any(a <= t <= b for a, b in intervals) else on) for t, on in samples]


def find_rest_window(samples: list[tuple[datetime, bool]], win_start: datetime, win_end: datetime) -> dict | None:
    """Laengstes Handy-Ruhefenster (Bildschirm aus) im Fenster. None = keines >= 3 h.

    Kurze Handy-Tipper (<= 2 Samples) beenden das Fenster nicht; Heartbeat-Luecken > 2 h
    beenden es (Handy aus/ohne Netz ist NICHT gleich "ruhig").
    """
    pts = [(t, on) for t, on in samples if win_start <= t <= win_end]
    if not pts:
        return None
    best: dict | None = None
    i, n = 0, len(pts)
    while i < n:
        if pts[i][1]:           # screen_on: kein Ruhebeginn
            i += 1
            continue
        start_idx = i
        start_obs = start_idx > 0 and pts[start_idx - 1][1] and \
            (pts[start_idx][0] - pts[start_idx - 1][0]) <= timedelta(minutes=15)
        last_off = i
        end_obs = False
        end_ts = pts[i][0]
        blips = 0
        j = i + 1
        while j < n:
            gap = (pts[j][0] - pts[j - 1][0]).total_seconds() / 60.0
            if gap > _MAX_BRIDGE_MIN:
                break
            if not pts[j][1]:
                last_off = j
                end_ts = pts[j][0]
                j += 1
                continue
            # screen_on: kurzer Tipper (<= _BLIP_SAMPLES) gefolgt von Ruhe?
            k = j
            while k < n and pts[k][1] and k - j < _BLIP_SAMPLES + 1:
                k += 1
            blip_len = k - j
            if blip_len <= _BLIP_SAMPLES and k < n and not pts[k][1] \
                    and (pts[k][0] - pts[k - 1][0]) <= timedelta(minutes=_MAX_BRIDGE_MIN):
                blips += 1
                j = k
                continue
            end_ts = pts[j][0]      # Aufwachen beobachtet
            end_obs = True
            break
        minutes = (end_ts - pts[start_idx][0]).total_seconds() / 60.0
        used = [p for p in pts[start_idx:j if end_obs else last_off + 1]]
        if minutes >= _MIN_REST_MIN:
            expected = max(1.0, minutes / _STEP_MIN)
            cov = max(0.0, min(1.0, len(used) / expected))
            conf = cov * (0.5 + (0.25 if start_obs else 0.0) + (0.25 if end_obs else 0.0))
            if blips > 3:
                conf *= 0.8
            cand = {
                "start": pts[start_idx][0], "end": end_ts, "minutes": round(minutes),
                "conf": round(conf, 2), "start_observed": bool(start_obs),
                "end_observed": end_obs, "blips": blips,
            }
            if best is None or cand["minutes"] > best["minutes"]:
                best = cand
        i = max(j, i + 1)
    return best


def hour_profile(samples: list[tuple[datetime, bool]], day: date) -> list[list[int]]:
    """24 x [screen_on_samples, samples] fuer den lokalen Tag."""
    prof = [[0, 0] for _ in range(24)]
    for ts, on in samples:
        lt = ts.astimezone(TZ)
        if lt.date() != day:
            continue
        prof[lt.hour][1] += 1
        if on:
            prof[lt.hour][0] += 1
    return prof


def compute_day(day: date, heartbeats: list[tuple[datetime, bool]], activities: Iterable[dict],
                health_events: Iterable[dict], streams: list[tuple[datetime, datetime]] | None = None) -> dict:
    """Tagesaggregat (nur Zahlen/Zeiten — keine Orte, Apps, Texte)."""
    prof = hour_profile(heartbeats, day)
    total = sum(p[1] for p in prof)
    ws, we = night_window(day)
    rest = find_rest_window(mask_streams(heartbeats, streams or []), ws, we)
    act: Counter = Counter()
    for a in activities:
        ts = _parse_ts(a.get("ts"))
        if ts is not None and local_day(ts) == day and a.get("activity_type") in ("WALKING", "RUNNING", "IN_VEHICLE", "STILL"):
            act[a["activity_type"]] += 1
    sleep_start = sleep_end = None
    sleep_hours = None
    steps = None
    for h in sorted(health_events, key=lambda e: str(e.get("ts")), reverse=True):   # neuester Push zuerst
        ts = _parse_ts(h.get("ts"))
        if ts is None or local_day(ts) != day:
            continue
        if h.get("steps_today") is not None:
            steps = max(steps or 0, int(h["steps_today"]))
        s_hours = h.get("sleep_hours")
        if sleep_hours is None and isinstance(s_hours, (int, float)) and s_hours > 0:
            sleep_hours = float(s_hours)
        if sleep_start is None and h.get("sleep_start") and h.get("sleep_end"):
            sleep_start, sleep_end = str(h["sleep_start"]), str(h["sleep_end"])
    return {
        "day": day.isoformat(),
        "samples": total,
        "hours": prof,
        "rest_start": rest["start"].isoformat() if rest else None,
        "rest_end": rest["end"].isoformat() if rest else None,
        "rest_minutes": rest["minutes"] if rest else None,
        "rest_conf": rest["conf"] if rest else None,
        "rest_end_observed": int(rest["end_observed"]) if rest else None,
        "moves": {k: act[k] for k in ("WALKING", "RUNNING", "IN_VEHICLE")},
        "sleep_start": sleep_start,
        "sleep_end": sleep_end,
        "sleep_hours": sleep_hours,
        "steps": steps,
    }


def _axis_minutes(ts: datetime) -> int:
    lt = ts.astimezone(TZ)
    return (lt.hour * 60 + lt.minute - _NIGHT_FROM_H * 60) % 1440


def _fmt_axis(mins: int) -> str:
    m = (mins + _NIGHT_FROM_H * 60) % 1440
    return f"{m // 60:02d}:{m % 60:02d}"


def _conf_label(n: int, spread_min: float) -> str:
    if n >= 14 and spread_min <= 90:
        return "hoch"
    if n >= 6 and spread_min <= 150:
        return "mittel"
    return "niedrig"


def _label_from_score(score: float | None) -> str:
    return "hoch" if (score or 0) >= 0.75 else ("mittel" if (score or 0) >= 0.5 else "niedrig")


def _dur_text(minutes: float) -> str:
    h, m = divmod(int(round(minutes)), 60)
    return f"{h} h {m:02d} min" if m else f"{h} h"


def summarize(rows: list[dict], streams: list[dict], now: datetime | None = None) -> dict:
    """Verdichtet Tagesaggregate + Stream-Sessions zu Mustern (mit Konfidenz + Datenbasis)."""
    now = now or datetime.now(timezone.utc)
    data_rows = [r for r in rows if (r.get("samples") or 0) >= _MIN_SAMPLES_DAY]
    out: dict[str, Any] = {"data_days": len(data_rows), "rest": None, "active": None, "stream": None,
                           "last_night": None, "sleep": None}

    nights = [r for r in rows if r.get("rest_minutes") and (r.get("rest_conf") or 0) >= _MIN_CONF]
    if len(nights) >= _MIN_NIGHTS:
        starts = [_axis_minutes(_parse_ts(r["rest_start"])) for r in nights]
        ends = [_axis_minutes(_parse_ts(r["rest_end"])) for r in nights]
        durs = [r["rest_minutes"] for r in nights]
        s_med, e_med = int(statistics.median(starts)), int(statistics.median(ends))
        spread = (max(starts) - min(starts) + max(ends) - min(ends)) / 4.0
        out["rest"] = {
            "start": _fmt_axis(s_med), "end": _fmt_axis(e_med), "median_minutes": int(statistics.median(durs)),
            "nights": len(nights), "spread_min": round(spread),
            "confidence": _conf_label(len(nights), spread),
        }

    with_rest = [r for r in rows if r.get("rest_minutes")]
    if with_rest:
        latest = max(with_rest, key=lambda r: r["day"])
        if (now.astimezone(TZ).date() - date.fromisoformat(latest["day"])).days <= 3:
            out["last_night"] = {k: latest[k] for k in ("day", "rest_start", "rest_end", "rest_minutes", "rest_conf", "rest_end_observed")}

    if len(data_rows) >= _MIN_NIGHTS:
        on = [0] * 24
        tot = [0] * 24
        for r in data_rows:
            for h, (a, b) in enumerate(r["hours"]):
                on[h] += a
                tot[h] += b
        ratio = [(on[h] / tot[h]) if tot[h] >= 6 else None for h in range(24)]
        ranked = sorted((h for h in range(24) if ratio[h] is not None), key=lambda h: -ratio[h])
        rest_hours: set[int] = set()
        if out["rest"]:
            s = (_NIGHT_FROM_H * 60 + _hm_to_axis(out["rest"]["start"])) % 1440
            e = (_NIGHT_FROM_H * 60 + _hm_to_axis(out["rest"]["end"])) % 1440
            h = s // 60
            while True:
                rest_hours.add(h)
                if h == e // 60:
                    break
                h = (h + 1) % 24
        peaks = [h for h in ranked if h not in rest_hours and ratio[h] >= 0.15][:3]
        out["active"] = {"peak_hours": sorted(peaks), "days": len(data_rows),
                         "confidence": _conf_label(len(data_rows), 60)}

    sess = []
    for s in streams:
        st = _parse_ts(s.get("start_ts"))
        if st is not None and (now - st).days <= _STREAM_LOOKBACK_DAYS:
            sess.append((st.astimezone(TZ), int(s.get("duration_s") or 0)))
    if len(sess) >= _MIN_STREAMS:
        wk = Counter(lt.weekday() for lt, _ in sess)
        weeks = max(1, min(_STREAM_LOOKBACK_DAYS, (now - min(lt for lt, _ in sess).astimezone(timezone.utc)).days + 1) // 7)
        typical = sorted(d for d, c in wk.items() if c >= max(2, 0.4 * weeks))
        # Startzeiten auf einer Achse ab 12:00 mitteln (Nachtstarts 23:30 / 00:15 duerfen nicht bei 12 Uhr landen)
        starts = [(lt.hour * 60 + lt.minute - 720) % 1440 for lt, _ in sess if lt.weekday() in typical]
        durs = [d for lt, d in sess if d > 0 and lt.weekday() in typical]
        med = (int(statistics.median(starts)) + 720) % 1440 if starts else 0
        out["stream"] = None if not typical else {
            "days": [_WEEKDAYS[d] for d in typical], "n": len(sess),
            "start": f"{med // 60:02d}:{med % 60:02d}",
            "median_minutes": int(statistics.median(durs) / 60) if durs else None,
            "confidence": "hoch" if len(sess) >= 12 else ("mittel" if len(sess) >= 6 else "niedrig"),
        }

    meas = sorted((r for r in rows if r.get("sleep_hours")), key=lambda r: r["day"])
    if meas:
        last = meas[-1]
        if (now.astimezone(TZ).date() - date.fromisoformat(last["day"])).days <= 7:   # nur aktuelle Messung nennen
            out["sleep"] = {"last_hours": last["sleep_hours"], "last_day": last["day"], "nights": len(meas),
                            "avg_hours": round(statistics.mean(r["sleep_hours"] for r in meas[-7:]), 1)}
    return out


def _hm_to_axis(hm: str) -> int:
    h, m = hm.split(":")
    return (int(h) * 60 + int(m) - _NIGHT_FROM_H * 60) % 1440


# --- Text-Ausgabe (kurz, ehrlich, nie "Schlaf" fuer die Naeherung) ------------------------------

def _hm_local(iso: str) -> str:
    return _parse_ts(iso).astimezone(TZ).strftime("%H:%M")


def render(summary: dict, topic: str = "alles", health_sleep_known: bool = False) -> str:
    """Kurzer deutscher Text. topic: ruhefenster | aktivzeiten | stream | alles."""
    lines: list[str] = []
    n = summary["data_days"]
    if topic in ("ruhefenster", "alles"):
        sl = summary.get("sleep")
        if sl:
            lines.append(f"Gemessener Schlaf (Health Connect, {sl['last_day']}): {sl['last_hours']} h; "
                         f"Schnitt {sl['avg_hours']} h ({sl['nights']} {'Nacht' if sl['nights'] == 1 else 'Naechte'}).")
        elif not health_sleep_known:
            lines.append("Schlafdauer: keine gemessenen Schlafdaten vorhanden — ich kann sie nicht nennen.")
        ln = summary.get("last_night")
        if ln and (ln.get("rest_conf") or 0) >= _MIN_CONF:
            end = f"bis {_hm_local(ln['rest_end'])}" if ln.get("rest_end_observed") else "Ende unklar"
            lines.append(f"Letztes Handy-Ruhefenster ({ln['day']}): wahrscheinlich {_hm_local(ln['rest_start'])} "
                         f"{end}, etwa {_dur_text(ln['rest_minutes'])} (Konfidenz {_label_from_score(ln['rest_conf'])}). "
                         "Das ist eine Naeherung aus Bildschirm-aus-Zeiten, kein gemessener Schlaf.")
        r = summary.get("rest")
        if r:
            lines.append(f"Typisches Ruhefenster: wahrscheinlich {r['start']}–{r['end']} (~{_dur_text(r['median_minutes'])}), "
                         f"Konfidenz {r['confidence']}, Datenbasis {r['nights']} {'Nacht' if r['nights'] == 1 else 'Naechte'}.")
        else:
            lines.append(f"Fuer ein typisches Ruhefenster reichen die Daten noch nicht (Datenbasis {n} Tage, mindestens {_MIN_NIGHTS} Naechte noetig).")
    if topic in ("aktivzeiten", "alles"):
        a = summary.get("active")
        if a and a["peak_hours"]:
            hrs = ", ".join(f"{h}" for h in a["peak_hours"])
            lines.append(f"Handy meist aktiv gegen {hrs} Uhr (Konfidenz {a['confidence']}, Datenbasis {a['days']} Tage). "
                         "Muedigkeit/Energie kann ich nicht messen.")
        elif topic == "aktivzeiten":
            lines.append(f"Fuer Aktivzeiten reichen die Daten noch nicht (Datenbasis {n} Tage). Muedigkeit/Energie kann ich nicht messen.")
    if topic in ("stream", "alles"):
        s = summary.get("stream")
        if s:
            dur = f", ~{_dur_text(s['median_minutes'])}" if s.get("median_minutes") else ""
            lines.append(f"Streams (Twitch-Aufnahmen, {s['n']} Streams): meist {', '.join(s['days'])} ab etwa {s['start']}{dur} "
                         f"(Konfidenz {s['confidence']}).")
        elif topic == "stream":
            lines.append("Zu wenige Stream-Aufnahmen fuer ein belastbares Muster.")
    return "\n".join(lines)


def habit_texts(summary: dict) -> dict[str, str]:
    """habit_key -> Text fuer den LTM-Eintrag (nur wenn belastbar genug)."""
    out: dict[str, str] = {}
    r = summary.get("rest")
    if r:
        out[HABIT_RUHEFENSTER] = (
            f"(Muster {HABIT_RUHEFENSTER}) Mikes Handy-Ruhefenster liegt wahrscheinlich meist zwischen {r['start']} und {r['end']} Uhr "
            f"(~{_dur_text(r['median_minutes'])}; Konfidenz {r['confidence']}, Datenbasis {r['nights']} Naechte). "
            "Naeherung aus Bildschirm-aus-Zeiten, kein gemessener Schlaf.")
    a = summary.get("active")
    if a and a["peak_hours"]:
        out[HABIT_AKTIVZEITEN] = (
            f"(Muster {HABIT_AKTIVZEITEN}) Mikes Handy ist meist gegen {', '.join(str(h) for h in a['peak_hours'])} Uhr aktiv "
            f"(Konfidenz {a['confidence']}, Datenbasis {a['days']} Tage).")
    s = summary.get("stream")
    if s:
        dur = f", ~{_dur_text(s['median_minutes'])}" if s.get("median_minutes") else ""
        out[HABIT_STREAM] = (
            f"(Muster {HABIT_STREAM}) Mike streamt laut Twitch-Aufnahmen meist {', '.join(s['days'])} ab etwa {s['start']} Uhr{dur} "
            f"(Konfidenz {s['confidence']}, {s['n']} Streams).")
    return out


# =============================================================================================
# Speicher (SQLite) + Orchestrierung
# =============================================================================================

class RhythmService:
    """Aggregattabelle ``rhythm_daily`` + ``rhythm_stream`` (nur Aggregate, keine Rohdaten)."""

    def __init__(self, db_path: str) -> None:
        self._db_path = db_path
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        with self._conn() as c:
            c.execute("""CREATE TABLE IF NOT EXISTS rhythm_daily (
                day TEXT PRIMARY KEY, samples INTEGER NOT NULL, hours TEXT NOT NULL,
                rest_start TEXT, rest_end TEXT, rest_minutes INTEGER, rest_conf REAL, rest_end_observed INTEGER,
                moves TEXT, sleep_start TEXT, sleep_end TEXT, sleep_hours REAL, steps INTEGER,
                updated_at TEXT NOT NULL)""")
            c.execute("""CREATE TABLE IF NOT EXISTS rhythm_stream (
                start_ts TEXT PRIMARY KEY, duration_s INTEGER NOT NULL)""")

    def _conn(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self._db_path, check_same_thread=False, timeout=10.0)
        conn.row_factory = sqlite3.Row
        return conn

    # -- Schreiben ----------------------------------------------------------------------------
    def upsert_day(self, agg: dict) -> bool:
        """Idempotent. Ueberschreibt nur, wenn die neue Berechnung nicht weniger Samples hat
        (Retention kuerzt alte Rohdaten — der gespeicherte Stand darf dadurch nie schlechter werden)."""
        with self._conn() as c:
            old = c.execute("SELECT samples, sleep_hours, sleep_start, sleep_end FROM rhythm_daily WHERE day=?",
                            (agg["day"],)).fetchone()
            if old is not None and agg["samples"] < old["samples"]:
                # Rohdaten teilweise geloescht: Heartbeat-Werte behalten, nur Gesundheitswerte nachziehen
                if any(agg.get(k) is not None for k in ("sleep_hours", "sleep_start", "sleep_end", "steps")):
                    c.execute("UPDATE rhythm_daily SET sleep_hours=COALESCE(?,sleep_hours), sleep_start=COALESCE(?,sleep_start), "
                              "sleep_end=COALESCE(?,sleep_end), steps=COALESCE(?,steps), updated_at=? WHERE day=?",
                              (agg.get("sleep_hours"), agg.get("sleep_start"), agg.get("sleep_end"), agg.get("steps"),
                               datetime.now(timezone.utc).isoformat(), agg["day"]))
                return False
            # Schlafwerte nie durch "keine Daten" ersetzen
            for k in ("sleep_hours", "sleep_start", "sleep_end"):
                if agg.get(k) is None and old is not None and old[k] is not None:
                    agg = {**agg, k: old[k]}
            c.execute(
                "INSERT INTO rhythm_daily (day,samples,hours,rest_start,rest_end,rest_minutes,rest_conf,rest_end_observed,"
                "moves,sleep_start,sleep_end,sleep_hours,steps,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                "ON CONFLICT(day) DO UPDATE SET samples=excluded.samples,hours=excluded.hours,rest_start=excluded.rest_start,"
                "rest_end=excluded.rest_end,rest_minutes=excluded.rest_minutes,rest_conf=excluded.rest_conf,"
                "rest_end_observed=excluded.rest_end_observed,moves=excluded.moves,sleep_start=excluded.sleep_start,"
                "sleep_end=excluded.sleep_end,sleep_hours=excluded.sleep_hours,steps=excluded.steps,updated_at=excluded.updated_at",
                (agg["day"], agg["samples"], json.dumps(agg["hours"]), agg["rest_start"], agg["rest_end"],
                 agg["rest_minutes"], agg["rest_conf"], agg["rest_end_observed"], json.dumps(agg["moves"]),
                 agg["sleep_start"], agg["sleep_end"], agg["sleep_hours"], agg["steps"],
                 datetime.now(timezone.utc).isoformat()))
        return True

    def upsert_streams(self, sessions: Iterable[dict]) -> int:
        n = 0
        with self._conn() as c:
            for s in sessions:
                st = _parse_ts(s.get("start_ts"))
                if st is None:
                    continue
                c.execute("INSERT INTO rhythm_stream (start_ts, duration_s) VALUES (?,?) "
                          "ON CONFLICT(start_ts) DO UPDATE SET duration_s=excluded.duration_s",
                          (st.isoformat(), int(s.get("duration_s") or 0)))
                n += 1
        return n

    def ingest(self, tracking: Any, now: datetime | None = None, days_back: int = 5) -> int:
        """Berechnet die letzten Tage aus der Tracking-DB neu (idempotent)."""
        now = now or datetime.now(timezone.utc)
        # Nachtfenster reicht bis D-1 20:00: nur so weit zurueck rechnen, wie die 7-Tage-Retention die Rohdaten noch vollstaendig hat
        hours = (days_back + 2) * 24
        hb = clean_heartbeats(tracking.get_events(("pc_heartbeat",), hours=hours, limit=50000))
        acts = tracking.get_events(("activity_recognition",), hours=hours, limit=5000)
        health = tracking.get_events(("health_connect",), hours=hours, limit=500)
        today = now.astimezone(TZ).date()
        iv = stream_intervals(self.stream_rows())
        written = 0
        for back in range(days_back + 1):
            day = today - timedelta(days=back)
            agg = compute_day(day, hb, acts, health, iv)
            if agg["samples"] == 0 and agg["sleep_hours"] is None and agg["sleep_start"] is None:
                continue
            written += 1 if self.upsert_day(agg) else 0
        return written

    # -- Lesen --------------------------------------------------------------------------------
    def rows(self, days: int = 28) -> list[dict]:
        since = (datetime.now(TZ).date() - timedelta(days=days)).isoformat()
        with self._conn() as c:
            res = c.execute("SELECT * FROM rhythm_daily WHERE day >= ? ORDER BY day", (since,)).fetchall()
        out = []
        for r in res:
            d = dict(r)
            d["hours"] = json.loads(d["hours"])
            d["moves"] = json.loads(d["moves"] or "{}")
            out.append(d)
        return out

    def stream_rows(self) -> list[dict]:
        with self._conn() as c:
            return [dict(r) for r in c.execute("SELECT * FROM rhythm_stream ORDER BY start_ts").fetchall()]

    def summary(self, now: datetime | None = None) -> dict:
        return summarize(self.rows(), self.stream_rows(), now)

    def answer(self, topic: str = "alles", health_sleep_known: bool = False) -> str:
        topic = topic if topic in ("ruhefenster", "aktivzeiten", "stream", "woche", "alles") else "alles"
        if topic == "woche":     # #182 Phase 3: Wochenmuster (nach Stream / Werktage)
            from app.services import habit_patterns
            return habit_patterns.render_week(habit_patterns.week_patterns(self.rows(days=60), self.stream_rows()))
        return render(self.summary(), topic, health_sleep_known)


async def fetch_stream_sessions(settings: Any, first: int = 40) -> list[dict]:
    """Stream-Ist aus den Twitch-VODs des eigenen Kanals (Mikes Kanal, keine Zuschauerdaten)."""
    from app.services import twitch_vod_service as vod
    client_id, token = await vod._get_credentials()
    if not client_id or not token:
        return []
    bid = await vod._get_broadcaster_id(settings.twitch_broadcaster_login, client_id, token)
    if not bid:
        return []
    out = []
    for v in await vod._fetch_vods(bid, client_id, token, first=first):
        if v.get("created_at"):
            out.append({"start_ts": v["created_at"], "duration_s": vod._parse_duration(v.get("duration", ""))})
    return out


# --- LTM-Veroeffentlichung (idempotent, genau ein Eintrag je habit_key) --------------------------

def publish_habits(ltm: Any, summary: dict, user_id: str | None = None, *, texts: dict[str, str] | None = None,
                   category: str = "user_habit") -> dict[str, str]:
    """Schreibt je habit_key genau EINEN Eintrag (Standard: user_habit). Fehlen Daten, bleibt eine aeltere Version
    bewusst stehen (sie traegt ihre Datenbasis/ihren Stand im Text) — es wird nie ohne Ersatz geloescht.
     Ergebnis: key -> noop|stored|replaced|failed.

    Reihenfolge: erst neu speichern, dann alte Versionen loeschen — schlaegt das Speichern fehl,
    bleibt die alte Version erhalten. Unveraenderter Text = No-op (keine Duplikate).
    """
    result: dict[str, str] = {}
    existing = ltm.list_by_category(category, limit=2000, user_id=user_id)
    if len(existing) >= 2000:
        log.warning("publish_habits_list_limit_reached", category=category)   # Duplikate moeglich
    by_key: dict[str, list[dict]] = {}
    for e in existing:
        m = _HABIT_PREFIX_RE.match(e.get("content", ""))
        if m:
            by_key.setdefault(m.group(1), []).append(e)
    others = {e["id"]: k for k, lst in by_key.items() for e in lst}
    for key, text in (texts if texts is not None else habit_texts(summary)).items():
        old = by_key.get(key, [])
        same = [o for o in old if o.get("content") == text]
        if same:
            for dup in same[1:] + [o for o in old if o not in same]:
                ltm.delete_memory(dup["id"], user_id=user_id)
            result[key] = "noop"
            continue
        meta = {"habit_key": key, "sensitive": "media" if key.startswith("media.") else "health"}
        new_id = ltm.store_memory(session_id="rhythm", content=text, category=category,
                                  user_id=user_id, infer=False, extra_metadata=meta)
        old_ids = {o["id"] for o in old}
        if new_id and new_id in old_ids:
            # Chroma-Dedup gab den ALTEN Eintrag zurueck (Text nur in Zahlen geaendert): alte Version
            # gezielt entfernen und neu speichern, sonst bliebe der veraltete Text stehen.
            for o in old:
                ltm.delete_memory(o["id"], user_id=user_id)
            old = []
            new_id = ltm.store_memory(session_id="rhythm", content=text, category=category,
                                      user_id=user_id, infer=False, extra_metadata=meta)
        if not new_id or new_id in old_ids and not old or others.get(new_id, key) != key:
            result[key] = "failed"      # nichts gespeichert oder Treffer eines FREMDEN Eintrags: nichts loeschen
            continue
        # Phantom-ID-Schutz (mem0 kann bei leerem Ergebnis eine ID erfinden): erst loeschen, wenn der Eintrag existiert
        # ID UND Inhalt muessen stimmen (die Chroma-Dedup kann die ID eines FREMDEN aehnlichen Eintrags liefern)
        if not any(e["id"] == new_id and e.get("content") == text
                   for e in ltm.list_by_category(category, limit=2000, user_id=user_id)):
            result[key] = "failed"
            continue
        for o in old:
            if o["id"] != new_id:
                ltm.delete_memory(o["id"], user_id=user_id)
        result[key] = "replaced" if old else "stored"
    return result


async def refresh(service: RhythmService, tracking: Any, ltm: Any, settings: Any) -> dict:
    """Ein Lauf: Tage neu berechnen, Streams holen, Muster ins LTM. Fehler einzelner Schritte sind nicht fatal."""
    import asyncio
    res: dict[str, Any] = {}
    try:
        res["streams"] = service.upsert_streams(await fetch_stream_sessions(settings))
    except Exception as e:  # noqa: BLE001
        log.warning("rhythm_streams_failed", error_type=type(e).__name__)
    try:
        res["days"] = await asyncio.to_thread(service.ingest, tracking)
    except Exception as e:  # noqa: BLE001
        log.warning("rhythm_ingest_failed", error_type=type(e).__name__)
    if ltm is not None:
        try:
            res["habits"] = await asyncio.to_thread(publish_habits, ltm, service.summary())
        except Exception as e:  # noqa: BLE001
            log.warning("rhythm_publish_failed", error_type=type(e).__name__)
    log.info("rhythm_refresh_done", **{k: (v if isinstance(v, int) else len(v)) for k, v in res.items()})
    return res

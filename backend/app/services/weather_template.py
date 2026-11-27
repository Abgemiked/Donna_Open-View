"""Wetter-Antwort ohne Modell (#152): aus den Wetterdaten ein bis zwei natuerliche Saetze zusammensetzen.

Nur fuer REINE Wetterfragen (siehe `template_topic`): "wie ist das Wetter", "wie warm ist es", "regnet es",
"schneit es", mit/ohne Ort, heute/morgen/uebermorgen. Alles andere (Jacke/Schirm/brauche ich, Mehrteiliges,
Wochenende, unbekannte Wetterlage) -> None -> der normale Weg ueber das Modell.

Bausteine in `app/data/weather_phrases.py`. Zufall seedbar (`random.Random`), nie dieselbe Variante zweimal
direkt hintereinander (Gedaechtnis der letzten Wahl je Baustein-Liste).
Reine Funktionen/kleine Klasse, kein Netz, kein Zustand ausser dem Auswahl-Gedaechtnis.
"""
from __future__ import annotations

import math
import random
import re
from datetime import datetime
from typing import Any

from app.data import weather_phrases as P
from app.services import clock_context

_GENERIC_PLACES = frozenset({"", "deinem standort", "deinem aktuellen standort", "unbekannter standort"})
_DAY_WORD = {1: "morgen", 2: "übermorgen"}

# Woerter, die aus einer "reinen" Wetterfrage eine Bewertungs-/Mehrteil-/Rueckfrage machen -> Modell
_BLOCK = frozenset({
    "jacke", "regenschirm", "schirm", "brauche", "brauch", "ich", "lohnt", "soll", "sollte", "muss",
    "kann", "darf", "und", "oder", "aber", "wochenende", "mantel", "mütze", "muetze", "schal",
    "handschuhe", "sonnenbrille", "sonnencreme", "vergleich", "besser", "als",
    # Tageszeit in der Frage: die Vorlage kennt nur Jetzt-/Tageswerte -> Modell mit Stundenwerten
    "abend", "abends", "nachmittag", "nachmittags", "vormittag", "vormittags", "früh", "frueh", "morgens",
    "mittag", "mittags", "nacht", "nachts", "später", "spaeter", "gleich", "bald", "stündlich", "stuendlich",
})
_TOKEN_RE = re.compile(r"[A-Za-zÄÖÜäöüß]+")


def template_topic(message: str) -> str | None:
    """'general' | 'rain' | 'snow' fuer eine reine Wetterfrage, sonst None (-> Modell)."""
    if not isinstance(message, str):
        return None
    tokens = [t.lower() for t in _TOKEN_RE.findall(message)]
    if not tokens or any(t in _BLOCK for t in tokens):
        return None
    if "schneit" in tokens:
        return "snow"
    if "regnet" in tokens or "regen" in tokens:
        return "rain"
    return "general"


class TemplateClaude:
    """Minimale Stream-Quelle mit der Schnittstelle des Claude-Clients: liefert den fertigen Text, ruft kein Modell."""

    last_usage: dict = {}

    def __init__(self, text: str) -> None:
        self._text = text

    def ready(self) -> bool:
        return True

    async def stream(self, **_kwargs: Any):
        yield self._text


def sun_is_up(local: datetime, lat: float = 52.5, lon: float = 13.4) -> bool:
    """Grobe Sonnenstand-Naeherung (+-15 min) fuer Berlin: Sonne ueber dem Horizont? Reicht fuer Tag/Nacht-Texte."""
    try:
        doy = local.timetuple().tm_yday
        offset_h = (local.utcoffset().total_seconds() / 3600.0) if local.utcoffset() is not None else 1.0
        decl = math.radians(23.44) * math.sin(2 * math.pi * (284 + doy) / 365)
        cos_ha = -math.tan(math.radians(lat)) * math.tan(decl)
        cos_ha = max(-1.0, min(1.0, cos_ha))
        half_day_h = math.degrees(math.acos(cos_ha)) / 15.0
        b = 2 * math.pi * (doy - 81) / 364
        eot_min = 9.87 * math.sin(2 * b) - 7.53 * math.cos(b) - 1.5 * math.sin(b)
        noon = 12.0 - (lon - 15.0 * offset_h) / 15.0 - eot_min / 60.0
        h = local.hour + local.minute / 60.0
        return noon - half_day_h <= h <= noon + half_day_h
    except Exception:  # noqa: BLE001
        return 6 <= local.hour < 21


def _fmt_int(n: int) -> str:
    return f"minus {abs(n)}" if n < 0 else str(n)


def _fill(template: str, values: dict[str, str]) -> str:
    """Einmaliges Ersetzen von {name}; eingesetzte Werte werden NICHT erneut ausgewertet (Ortsnamen!)."""
    return re.sub(r"\{(\w+)\}", lambda m: values.get(m.group(1), m.group(0)), template)


def _cap(text: str) -> str:
    return text[:1].upper() + text[1:] if text else text


class WeatherTemplate:
    def __init__(self, rng: random.Random | None = None) -> None:
        self._rng = rng or random.Random()
        self._last: dict[str, int] = {}

    # -- Auswahl ohne direkte Wiederholung -----------------------------------------
    def _pick(self, key: str, seq: tuple[str, ...]) -> str:
        idx = self._rng.randrange(len(seq))
        if len(seq) > 1 and self._last.get(key) == idx:
            idx = (idx + 1 + self._rng.randrange(len(seq) - 1)) % len(seq)
        self._last[key] = idx
        return seq[idx]

    # -- Lage -----------------------------------------------------------------------
    @staticmethod
    def lage_of(card: Any, dark: bool, day_offset: int) -> str | None:
        code = getattr(card, "code", None)
        lage = P.CODE_TO_LAGE.get(code) if isinstance(code, int) else None
        if lage is None:
            return None
        if day_offset == 0 and dark:
            if lage == "klar":
                return "klar_nacht"
            if lage == "leicht":
                return "leicht_nacht"
        return lage

    @staticmethod
    def _hourly(card: Any, hour: int, day_offset: int) -> list[dict]:
        rows = [h for h in (getattr(card, "hourly", None) or []) if isinstance(h, dict)]
        if day_offset != 0:
            return rows
        out = []
        for h in rows:
            m = re.match(r"\s*(\d+)", str(h.get("time", "")))
            if m is None or int(m.group(1)) >= hour - 1:
                out.append(h)
        return out

    # -- Zusatz ------------------------------------------------------------------------
    def _extra(self, card: Any, lage: str, sun_up: bool, day_offset: int, rows: list[dict], vals: dict[str, str]) -> str | None:
        temp = int(card.temp_c)
        base = lage.replace("_nacht", "")
        wind = int(getattr(card, "wind_kmh", 0) or 0)
        hum = int(getattr(card, "humidity", 0) or 0)
        feels = int(getattr(card, "feels_like_c", temp))
        precip = max((int(h.get("precip_pct", 0) or 0) for h in rows), default=0)
        icons = {str(h.get("icon", "")) for h in rows}
        cands: list[tuple[int, str, tuple[str, ...]]] = []
        forced: tuple[str, tuple[str, ...]] | None = None

        # Alltagshinweis
        hint_key = None
        if base in ("niesel", "regen", "schauer", "moeglich_regen"):
            hint_key = "schirm"
        elif base in ("starkregen", "gewitter", "schnee", "schneeregen", "graupel", "nebel"):
            hint_key = base
        elif base == "moeglich_schneeregen":
            hint_key = "schneeregen"
        elif temp <= 0:
            hint_key = "frost"
        elif temp <= 6:
            hint_key = "kalt"
        elif temp >= 28:
            hint_key = "heiss"
        elif base == "klar" and temp >= 8 and (day_offset != 0 or sun_up):
            hint_key = "sonne"
        if hint_key:
            cands.append((3, "hint", P.HINT[hint_key]))
            if hint_key == "gewitter":
                forced = ("hint", P.HINT[hint_key])

        if wind >= 60:
            forced = forced or ("sturm", P.WIND_STURM)
        elif wind >= 35:
            cands.append((2, "wind", P.WIND_STARK))
        if abs(feels - temp) >= 3:
            cands.append((2, "feels", P.FEELS_KAELTER if feels < temp else P.FEELS_WAERMER))
        cands.append((1, "range", P.RANGE_HEUTE if day_offset == 0 else P.RANGE_TAG))
        band = ("frost" if temp <= 0 else "kalt" if temp <= 6 else "kuehl" if temp <= 12 else "mild" if temp <= 19
                else "warm" if temp <= 25 else "heiss" if temp <= 31 else "sehr_heiss")
        cands.append((1, "band_" + band, P.BAND[band]))
        wet = base in P.LAGEN_NASS
        if not wet and hum >= 75 and temp >= 22:
            cands.append((2, "schwuel", P.SCHWUEL))
        elif not wet and hum >= 90 and temp < 22:
            cands.append((1, "feucht", P.FEUCHT))
        if not wet and precip >= 45:
            cands.append((2, "regenchance", P.REGENCHANCE))
        if len(icons) >= 4:
            cands.append((2, "wechselhaft", P.WECHSELHAFT))

        vals = dict(vals, wind=str(wind), hum=str(hum), p=str(precip), feels=_fmt_int(feels),
                    hoch=_fmt_int(int(card.temp_max)), tief=_fmt_int(int(card.temp_min)))
        if forced is not None:
            kind, seq = forced
            return _fill(self._pick(f"extra:{kind}", seq), vals)
        if self._rng.random() >= 0.7:
            return None
        total = sum(w for w, _, _ in cands)
        roll = self._rng.uniform(0, total)
        for w, kind, seq in cands:
            roll -= w
            if roll <= 0:
                return _fill(self._pick(f"extra:{kind}", seq), vals)
        return None

    # -- Haupt-Aufruf --------------------------------------------------------------------
    def render(self, card: Any, *, day_offset: int, topic: str = "general", now: datetime | None = None) -> str | None:
        """Fertiger Antworttext oder None (unbekannte Lage/kaputte Daten -> Modell)."""
        try:
            local = clock_context.berlin_now(now)
            hour = local.hour
            sun_up = sun_is_up(local)
            day_offset = max(0, min(int(day_offset), 2))
            lage = self.lage_of(card, not sun_up, day_offset)
            if lage is None:
                return None
            rows = self._hourly(card, hour, day_offset)
            loc = str(getattr(card, "location", "") or "").strip()[:40]
            generic = loc.lower() in _GENERIC_PLACES
            in_ort = "bei dir" if generic else f"in {loc}"
            tag = _DAY_WORD.get(day_offset, "heute")
            vals = {
                "in_ort": in_ort, "In_ort": _cap(in_ort), "temp": _fmt_int(int(card.temp_c)),
                "tag": tag, "Tag": _cap(tag),
            }
            cond = self._pick(f"cond:{lage}", P.COND[lage])
            vals["c"], vals["C"] = cond, _cap(cond)
            frames = P.FRAMES_NOW if day_offset == 0 else P.FRAMES_DAY
            main = _cap(_fill(self._pick(f"frame:{'now' if day_offset == 0 else 'day'}", frames), vals))

            base = lage.replace("_nacht", "")
            lead = ""
            if topic in ("rain", "snow"):
                precip = max((int(h.get("precip_pct", 0) or 0) for h in rows), default=0)
                now_ = day_offset == 0
                yes = P.LAGEN_REGEN if topic == "rain" else P.LAGEN_SCHNEE
                maybe = P.LAGEN_MOEGLICH_REGEN if topic == "rain" else P.LAGEN_MOEGLICH_SCHNEE
                if base in yes:
                    seq, key = P.LEAD_JA + (P.LEAD_JA_JETZT if now_ else ()), "ja"
                elif base in maybe or (base not in P.LAGEN_NASS and topic == "rain" and precip >= 50):
                    seq, key = P.LEAD_VIELLEICHT, "vielleicht"
                elif base in P.LAGEN_NASS or base in P.LAGEN_MOEGLICH_REGEN | P.LAGEN_MOEGLICH_SCHNEE:
                    seq, key = P.NEIN_ANDERS, "anders"
                else:
                    seq = P.LEAD_NEIN + P.NEIN_TROCKEN + (P.LEAD_NEIN_JETZT if now_ else ())
                    key = "nein"
                lead = self._pick(f"lead:{key}:{int(now_)}", seq)
            elif self._rng.random() < 0.3:
                if day_offset == 0:
                    pool = (P.EINSTIEG_MORGEN if 5 <= hour <= 10 else P.EINSTIEG_MITTAG if 11 <= hour <= 14
                            else P.EINSTIEG_ABEND if 17 <= hour <= 20 else P.EINSTIEG_NACHT if (hour >= 22 or hour < 5)
                            else P.EINSTIEG_NEUTRAL)
                    if self._rng.random() < 0.5:
                        pool = P.EINSTIEG_NEUTRAL
                else:
                    pool = P.EINSTIEG_NEUTRAL
                lead = self._pick("einstieg", pool)

            extra = self._extra(card, lage, sun_up, day_offset, rows, vals)
            return (lead + main + (" " + extra if extra else "")).strip()
        except Exception:  # noqa: BLE001 — Vorlage ist best-effort, sonst Modell
            return None


_DEFAULT = WeatherTemplate()


def render_weather_answer(card: Any, *, day_offset: int, topic: str = "general", now: datetime | None = None) -> str | None:
    return _DEFAULT.render(card, day_offset=day_offset, topic=topic, now=now)

"""Kino-Programm fuer Donnas Kino-Assistenten (4 feste Kinos).

Datenquelle: kinoprogramm.com (oeffentliche Kinoseiten, Filme + Uhrzeiten je Tag als HTML).
Warum nicht cineplex.de direkt: Server-Abrufe werden dort per Bot-Schutz geblockt (403) — das
umgehen wir nicht. Die Eigenseiten von Capitol Ansbach/Cinecity Crailsheim wuerden je einen
eigenen Parser brauchen; kinoprogramm.com liefert alle 4 Kinos im gleichen Format.

Regeln: ehrlicher User-Agent, je Kino hoechstens ein Abruf pro Zwischenspeicher-Zeit, kurzes Zeitlimit,
bei Ausfall nie Absturz und nie erfundene Zeiten (alter Zwischenspeicher nur markiert als "Stand ...").
Die Ausgabetexte sind Rohdaten fuers Modell (tool_result); Ton/Laenge bestimmt der Prompt.
"""
from __future__ import annotations

import asyncio
import difflib
import html as _html
import json
import os
import re
import time
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path
from datetime import date, datetime, timedelta
from urllib.parse import urlsplit
from typing import Any, Awaitable, Callable
from zoneinfo import ZoneInfo

from app.core.logger import get_logger

log = get_logger("kino_service")

BERLIN = ZoneInfo("Europe/Berlin")
SOURCE_NAME = "kinoprogramm.com"
FALLBACK_SOURCE = SOURCE_NAME
USER_AGENT = "DonnaKinoAssistent/1.0 (private Nutzung, Abruf nur bei Nachfrage)"
DEFAULT_TTL_S = 60 * 60          # Zwischenspeicher je Kino (danach veraltet ausliefern + im Hintergrund erneuern)
FAIL_BACKOFF_S = 600.0         # nach einem Ausfall mindestens so lange nicht erneut abrufen (verdoppelt sich je Fehlschlag)
FAIL_BACKOFF_MAX_S = 2 * 3600.0
REQUEST_GAP_S = 1.5            # Mindestabstand zwischen zwei Abrufen (nie parallel)
STALE_MAX_S = 6 * 3600           # aelter als das wird nie mehr gezeigt
DEFAULT_TIMEOUT_S = 6.0
MAX_BYTES = 3_000_000
MAX_TITLES = 5                  # allgemeine "was laeuft"-Antwort: hoechstens so viele Titel (Sprachausgabe)
FOREGROUND_TIMEOUT_S = 4.0      # im Gespraech je Kino hoechstens so lange warten (Abruf laeuft im Hintergrund weiter)
WARM_FIRST_HOUR = 12            # gezieltes Wiederwaermen nur zwischen 12 und 23 Uhr (Berlin) ...
WARM_LAST_HOUR = 23
WARM_ASKED_WITHIN_S = 24 * 3600  # ... und nur, wenn in den letzten 24 h nach Kino gefragt wurde
MAX_FILMS_PER_CINEMA = 12


Fetch = Callable[[str], Awaitable[str]]
Loader = Callable[..., Awaitable[list["FilmDays"]]]       # (fetch, [publish=...]) -> Filme


@dataclass(frozen=True)
class Source:
    """Eine Datenquelle fuer ein Kino. loader holt (ueber das uebergebene, geschuetzte fetch) und parst."""
    name: str                       # Anzeige in der Antwort ("Quelle: ...")
    loader: Loader
    timeout_s: float | None = None  # Gesamtzeit des Loaders (Standard: Service-Zeitlimit + 1)
    gap_s: float | None = None      # Abstand zwischen Abrufen dieser Quelle (Standard: Service-Abstand)
    progressive: bool = False       # Loader darf nach den ersten Tagen ein Teilergebnis veroeffentlichen (publish)


def page_source(name: str, url: str, parser: Callable[[str], list["FilmDays"]]) -> Source:
    async def _load(fetch: Fetch) -> list["FilmDays"]:
        return parser(await fetch(url))

    return Source(name, _load)


@dataclass(frozen=True)
class Cinema:
    key: str
    name: str            # Kurzname fuer die Antwort
    city: str
    page_url: str        # kinoprogramm.com (Zweitquelle)
    own_site: str        # Verweis, falls Daten fehlen
    aliases: tuple[str, ...]
    own: tuple[Source, ...] = ()    # Eigenseite(n) des Kinos, werden zuerst versucht
    filmstarts_id: str | None = None  # Kino-ID bei filmstarts.de (z. B. "A2727"); zweite Quelle

    @property
    def sources(self) -> tuple[Source, ...]:
        mid = (filmstarts_source(self.filmstarts_id),) if self.filmstarts_id else ()
        return self.own + mid + (page_source(FALLBACK_SOURCE, self.page_url, parse_program),)




def fold(text: str) -> str:
    """Kleinschreibung, Umlaute/Akzente weg, nur Buchstaben/Ziffern/Leerzeichen."""
    t = unicodedata.normalize("NFKD", (text or "").lower().replace("ß", "ss"))
    t = "".join(ch for ch in t if not unicodedata.combining(ch))
    t = re.sub(r"[^a-z0-9]+", " ", t)
    return re.sub(r"\s+", " ", t).strip()


def resolve_cinema(text: str | None) -> Cinema | None:
    """Kino aus Freitext (auch Spracherkennungs-Varianten). Unbekannt -> None."""
    f = fold(text or "")
    if not f:
        return None
    for c in CINEMAS:
        if f == c.key or any(fold(a) in f for a in c.aliases):
            return c
    # Rest: knapper Tippfehler-/Erkennungsfehler-Abgleich auf einzelne Woerter
    words = f.split()
    best: tuple[float, Cinema] | None = None
    for c in CINEMAS:
        for w in words:
            for a in {fold(c.city), c.key}:
                r = difflib.SequenceMatcher(None, w, a).ratio()
                if r >= 0.8 and (best is None or r > best[0]):
                    best = (r, c)
    return best[1] if best else None


# ── Datum ──────────────────────────────────────────────────────────────────────────
_WEEKDAYS = {"montag": 0, "dienstag": 1, "mittwoch": 2, "donnerstag": 3, "freitag": 4,
             "samstag": 5, "sonnabend": 5, "sonntag": 6}


def resolve_date(text: str | None, today: date) -> date | None:
    """'heute'/'morgen'/'uebermorgen', Wochentag, '2026-11-26', '26.11.' oder '26.11.2026'. Sonst None."""
    f = fold(text or "")
    if not f:
        return None
    if f in ("heute", "today"):
        return today
    if f in ("morgen", "tomorrow"):
        return today + timedelta(days=1)
    if f in ("uebermorgen", "ubermorgen"):
        return today + timedelta(days=2)
    m = re.fullmatch(r"(\d{4}) (\d{1,2}) (\d{1,2})", f)
    if m:
        try:
            return date(int(m[1]), int(m[2]), int(m[3]))
        except ValueError:
            return None
    m = re.fullmatch(r"(\d{1,2}) (\d{1,2})(?: (\d{2,4}))?", f)
    if m:
        y = int(m[3]) if m[3] else today.year
        y += 2000 if y < 100 else 0
        try:
            d = date(y, int(m[2]), int(m[1]))
        except ValueError:
            return None
        if not m[3] and d < today - timedelta(days=30):
            d = d.replace(year=d.year + 1)
        return d
    for name, wd in _WEEKDAYS.items():
        if f in (name, f"am {name}", f"naechsten {name}", f"kommenden {name}"):
            return today + timedelta(days=(wd - today.weekday()) % 7)
    return None


# ── Parser ─────────────────────────────────────────────────────────────────────────
@dataclass(frozen=True)
class Show:
    time: str       # "19:30"
    tag: str = ""   # "", "OV", "OmU", "3D"


@dataclass
class FilmDays:
    title: str
    days: dict[str, list[Show]] = field(default_factory=dict)   # ISO-Datum -> Zeiten


_ARTICLE_RE = re.compile(r"<article\b[^>]*data-kino-week-film[^>]*>(.*?)</article>", re.S)
_TITLE_RE = re.compile(r"<a\b[^>]*href=\"[^\"]*\"[^>]*>\s*([^<]+?)\s*</a>")
_DAY_RE = re.compile(r"<section\b[^>]*data-kino-week-day=\"(\d{4}-\d{2}-\d{2})\"[^>]*>(.*?)</section>", re.S)
_VERSION_RE = re.compile(r"<div\b[^>]*data-kino-week-version=\"([^\"]*)\"[^>]*>(.*?)</div>", re.S)
_TIME_RE = re.compile(r"<span\b[^>]*>\s*(\d{1,2}:\d{2})\s*</span>")
_LABEL_RE = re.compile(r"<p\b[^>]*>(.*?)</p>", re.S)


def _tag_for(version_attr: str, label: str) -> str:
    s = fold(f"{label} {version_attr}")
    toks = set(s.split())
    if "omu" in toks or "untertiteln" in toks or "untertitel" in toks or "original mit untertiteln" in s:
        return "OmU"
    if "ov" in toks or "originalfassung" in toks or "ovmxp" in "".join(toks):
        return "OV"
    if "3d" in toks or "3d" in s.replace(" ", ""):
        return "3D"
    return ""


def _clean_title(raw: str) -> str:
    """Eine Zeile, keine Steuerzeichen, max. 80 Zeichen (Titel stammen von einer Drittseite)."""
    t = _html.unescape(raw or "")
    t = "".join(ch if ch.isprintable() else " " for ch in t)
    return re.sub(r"\s+", " ", t).strip()[:80]


def parse_program(page_html: str) -> list[FilmDays]:
    """Filme + Zeiten je Tag aus einer kinoprogramm.com-Kinoseite. Leere Liste bei unbekanntem Aufbau."""
    films: list[FilmDays] = []
    for art in _ARTICLE_RE.findall(page_html or ""):
        m = _TITLE_RE.search(art)
        if not m:
            continue
        title = _clean_title(m.group(1))
        if not title or title.lower() == "trailer":
            continue
        fd = FilmDays(title=title)
        for iso, body in _DAY_RE.findall(art):
            shows: list[Show] = []
            for vattr, vbody in _VERSION_RE.findall(body):
                lm = _LABEL_RE.search(vbody)
                tag = _tag_for(vattr, _html.unescape(lm.group(1)) if lm else "")
                for t in _TIME_RE.findall(vbody):
                    hh, mm = t.split(":")
                    shows.append(Show(f"{int(hh):02d}:{mm}", tag))
            if shows:
                fd.days[iso] = sorted(set(shows), key=lambda s: (s.time, s.tag))
        films.append(fd)
    return films


# ── Cinecity Crailsheim: Programm als JSON in der Startseite (pmkinoFrontVars.apiData) ─────
_CC_MARK = "var pmkinoFrontVars = "
_DATE_RE = re.compile(r"\d{4}-\d{2}-\d{2}")
_HM_RE = re.compile(r"(\d{1,2}):(\d{2})")


def _cc_tag(attrs: Any) -> str:
    ids = {str(a.get("id", "")).lower() for a in attrs if isinstance(a, dict)} if isinstance(attrs, list) else set()
    if ids & {"subtitled", "omu"}:
        return "OmU"
    if ids & {"ov", "original"}:
        return "OV"
    if "3d" in ids:
        return "3D"
    return ""


def parse_cinecity(page_html: str) -> list[FilmDays]:
    """Filme + Zeiten je Tag aus dem eingebetteten JSON der Cinecity-Startseite. Leere Liste bei jedem Fehler."""
    try:
        i = (page_html or "").find(_CC_MARK)
        if i < 0:
            return []
        data, _ = json.JSONDecoder().raw_decode(page_html[i + len(_CC_MARK):])
        api = data["apiData"]
        movies = api["movies"]["items"]
        perfs = api["performances"]["items"]
        if not isinstance(movies, dict) or not isinstance(perfs, dict):
            return []
    except Exception:  # noqa: BLE001
        return []
    by_movie: dict[str, FilmDays] = {}
    for p in perfs.values():
        try:
            m = movies.get(p["moviePk"])
            if not isinstance(m, dict):
                continue
            day, hm = str(p["date"]), _HM_RE.fullmatch(str(p["showtime"]).strip())
            if not _DATE_RE.fullmatch(day) or not hm or int(hm[1]) > 23 or int(hm[2]) > 59:
                continue
            date.fromisoformat(day)
            title = _clean_title(str(m.get("title") or ""))
            if not title:
                continue
            fd = by_movie.setdefault(p["moviePk"], FilmDays(title=title))
            fd.days.setdefault(day, []).append(Show(f"{int(hm[1]):02d}:{hm[2]}", _cc_tag(p.get("attributes"))))
        except Exception:  # noqa: BLE001 — eine kaputte Vorstellung ueberspringen, Rest behalten
            continue
    out = []
    for pk in movies:                                  # Reihenfolge der Filme wie auf der Seite
        fd = by_movie.get(pk)
        if fd:
            for d in list(fd.days):
                fd.days[d] = sorted(set(fd.days[d]), key=lambda s: (s.time, s.tag))
            out.append(fd)
    return out


async def _cinecity_loader(fetch: Fetch) -> list[FilmDays]:
    return parse_cinecity(await fetch("https://cinecity-crailsheim.de/"))


# ── Capitol Ansbach: oeffentliches Programm-JSON der Kinoseite (dasselbe, das der Browser nachlaedt) ─────
CAPITOL_API_URL = "https://capitol-kinocenter.de/programm/api/filtered-films"


def _cap_tag(perf: dict) -> str:
    rel = perf.get("releases")
    text = " ".join([str(v) for v in rel.values()] if isinstance(rel, dict) else [])
    text = fold(f"{text} {perf.get('originalReleases') or ''}")
    toks = set(text.split())
    if "omu" in toks:
        return "OmU"
    if "ov" in toks:
        return "OV"
    if "3d" in toks:
        return "3D"
    return ""


def parse_capitol(page: str) -> list[FilmDays]:
    """Filme + Zeiten je Tag aus dem Capitol-Programm-JSON. Leere Liste bei jedem Fehler/anderem Aufbau."""
    try:
        data = json.loads(page)
        raw_films = data["films"]
        if not isinstance(raw_films, list):
            return []
    except Exception:  # noqa: BLE001
        return []
    out: list[FilmDays] = []
    for f in raw_films:
        try:
            title = _clean_title(str(f["filmTitle"]))
            if not title:
                continue
            fd = FilmDays(title=title)
            for g in f["performances"]:
                for p in g["performances"].values():
                    try:
                        if str(p.get("status", "normal")).lower() != "normal":
                            continue                         # abgesagt/sonstiges nie anbieten
                        day, hm = str(p["date"]), _HM_RE.match(str(p["time"]).strip())
                        if not _DATE_RE.fullmatch(day) or not hm or int(hm[1]) > 23 or int(hm[2]) > 59:
                            continue
                        date.fromisoformat(day)
                        fd.days.setdefault(day, []).append(Show(f"{int(hm[1]):02d}:{hm[2]}", _cap_tag(p)))
                    except Exception:  # noqa: BLE001 — eine kaputte Vorstellung ueberspringen
                        continue
            for d in list(fd.days):
                fd.days[d] = sorted(set(fd.days[d]), key=lambda x: (x.time, x.tag))
            if fd.days:
                out.append(fd)
        except Exception:  # noqa: BLE001
            continue
    return out


async def _capitol_loader(fetch: Fetch) -> list[FilmDays]:
    return parse_capitol(await fetch(CAPITOL_API_URL))


# ── Admiral Nuernberg: ganzes Programm als JSON im Next.js-Seitenpayload der Startseite ───────────
ADMIRAL_URL = "https://admiral-kino-nuernberg.de/"
_NEXT_PUSH = "self.__next_f.push([1,"


def _next_payload(page: str) -> str:
    """Alle self.__next_f.push([1,"..."])-Stuecke dekodiert und aneinandergehaengt (kaputte Stuecke werden uebersprungen)."""
    dec = json.JSONDecoder()
    parts: list[str] = []
    pos = 0
    while True:
        i = page.find(_NEXT_PUSH, pos)
        if i < 0:
            break
        start = i + len(_NEXT_PUSH)
        pos = start
        try:
            val, end = dec.raw_decode(page, start)
        except ValueError:
            continue
        if isinstance(val, str):
            parts.append(val)
        pos = end
    return "".join(parts)


def _adm_tag(v: dict) -> str:
    chips = v.get("chips")
    text = fold(f"{v.get('versionen') or ''} {' '.join(str(c) for c in chips) if isinstance(chips, list) else ''}")
    toks = set(text.split())
    if "omu" in toks:
        return "OmU"
    if "ov" in toks:
        return "OV"
    if "3d" in toks:
        return "3D"
    return ""


def parse_admiral(page: str) -> list[FilmDays]:
    """Filme + Zeiten je Tag aus dem Seitenpayload der Admiral-Startseite. Leere Liste bei jedem Fehler."""
    try:
        text = _next_payload(page or "")
        dec = json.JSONDecoder()
        best: list = []
        best_n = 0
        for m in re.finditer(r'"filme":\[', text):
            try:
                arr, _ = dec.raw_decode(text, m.end() - 1)
            except ValueError:
                continue
            if not isinstance(arr, list):
                continue
            n = sum(len(f["vorstellungen"]) for f in arr
                    if isinstance(f, dict) and isinstance(f.get("vorstellungen"), list))
            if n > best_n:                                   # die Liste MIT Vorstellungen (nicht der Teaser)
                best, best_n = arr, n
    except Exception:  # noqa: BLE001
        return []
    out: list[FilmDays] = []
    for f in best:
        try:
            title = _clean_title(str(f["titel"]))
            if not title or not isinstance(f.get("vorstellungen"), list):
                continue
            fd = FilmDays(title=title)
            for v in f["vorstellungen"]:
                try:
                    day, hm = str(v["datum"]), _HM_RE.fullmatch(str(v["zeit"]).strip())
                    if not _DATE_RE.fullmatch(day) or not hm or int(hm[1]) > 23 or int(hm[2]) > 59:
                        continue
                    date.fromisoformat(day)
                    fd.days.setdefault(day, []).append(Show(f"{int(hm[1]):02d}:{hm[2]}", _adm_tag(v)))
                except Exception:  # noqa: BLE001 — eine kaputte Vorstellung ueberspringen
                    continue
            for d in list(fd.days):
                fd.days[d] = sorted(set(fd.days[d]), key=lambda x: (x.time, x.tag))
            if fd.days:
                out.append(fd)
        except Exception:  # noqa: BLE001
            continue
    return out


async def _admiral_loader(fetch: Fetch) -> list[FilmDays]:
    return parse_admiral(await fetch(ADMIRAL_URL))


# ── filmstarts.de: Tages-JSON der Kinoseite (/_/showtimes/theater-<ID>/d-<Tag>/), 7 Tage, nacheinander ─────
FILMSTARTS_DAY_URL = "https://www.filmstarts.de/_/showtimes/theater-{tid}/d-{day}/"
FILMSTARTS_DAYS = 7
FILMSTARTS_GAP_S = 1.0
FILMSTARTS_PUBLISH_AFTER = 2    # nach heute+morgen steht ein Teilergebnis bereit (Rest laeuft im Hintergrund weiter)
_FS_VERSION_TAG = {"original": "OV", "original_st": "OmU", "local_st": "OmU", "dubbed_st": "OmU"}


def _berlin_today() -> date:
    return datetime.now(BERLIN).date()


def _fs_tag(group: str, st: dict) -> str:
    if group in _FS_VERSION_TAG:
        return _FS_VERSION_TAG[group]
    toks: set[str] = set()
    for key in ("tags", "projection", "experience"):
        v = st.get(key)
        if isinstance(v, list):
            for x in v:
                toks.update(fold(str(x).replace(".", " ").replace("_", " ")).split())
    return "3D" if ({"3d", "threed"} & toks or "three d" in " ".join(sorted(toks))) else ""


def parse_filmstarts_day(page: str) -> list[tuple[str, list[Show]]]:
    """(Titel, Zeiten) fuer EINEN Tag aus dem filmstarts-Tages-JSON. ValueError bei kaputtem/unvollstaendigem Aufbau
    (Fehler, mehr als eine Seite Ergebnisse); ein Tag ohne Vorstellungen ist eine leere Liste."""
    try:
        data = json.loads(page)
        if not isinstance(data, dict) or data.get("error") or not isinstance(data.get("results"), list):
            raise ValueError("bad_shape")
    except (TypeError, ValueError) as exc:
        raise ValueError("filmstarts_unparsable") from exc
    pag = data.get("pagination")
    if isinstance(pag, dict) and int(pag.get("totalPages") or 1) > 1:
        raise ValueError("filmstarts_more_pages")                  # unvollstaendig -> lieber Fallback als Luecken
    out: list[tuple[str, list[Show]]] = []
    for r in data["results"]:
        try:
            title = _clean_title(str(r["movie"]["title"]))
            if not title:
                continue
            shows: list[Show] = []
            for group, items in (r.get("showtimes") or {}).items():
                for st in items or []:
                    try:
                        hm = re.fullmatch(r"\d{4}-\d{2}-\d{2}T(\d{2}):(\d{2})(?::\d{2})?.*", str(st["startsAt"]))
                        if not hm or int(hm[1]) > 23 or int(hm[2]) > 59:
                            continue
                        shows.append(Show(f"{hm[1]}:{hm[2]}", _fs_tag(str(group), st)))
                    except Exception:  # noqa: BLE001 — eine kaputte Vorstellung ueberspringen
                        continue
            if shows:
                out.append((title, sorted(set(shows), key=lambda x: (x.time, x.tag))))
        except Exception:  # noqa: BLE001
            continue
    return out


def filmstarts_source(theater_id: str) -> Source:
    async def _load(fetch: Fetch, publish: Callable[..., None] | None = None) -> list["FilmDays"]:
        today = _berlin_today()
        merged: dict[str, FilmDays] = {}
        loaded = 0
        for i in range(FILMSTARTS_DAYS):
            day = (today + timedelta(days=i)).isoformat()
            try:
                rows = parse_filmstarts_day(await fetch(FILMSTARTS_DAY_URL.format(tid=theater_id, day=day)))
            except Exception:  # noqa: BLE001
                if loaded == 0:
                    raise                                           # schon der erste Tag fehlt: Quelle ausgefallen
                break                                               # spaetere Tage fehlen: Horizont endet hier
            loaded += 1
            for title, shows in rows:
                merged.setdefault(fold(title), FilmDays(title=title)).days[day] = shows
            if publish is not None and loaded == FILMSTARTS_PUBLISH_AFTER and merged:
                publish([FilmDays(f.title, dict(f.days)) for f in merged.values()], day)   # Teilstand: heute+morgen
        return list(merged.values())

    return Source("filmstarts.de", _load, timeout_s=FILMSTARTS_DAYS * 8.0, gap_s=FILMSTARTS_GAP_S, progressive=True)


CINEMAS: tuple[Cinema, ...] = (
    Cinema("nuernberg", "Admiral Boutique Kino Nürnberg", "Nürnberg",
           "https://www.kinoprogramm.com/kino/nuernberg-mittelfrangen/admiral-filmpalast-32906",
           "admiral-kino-nuernberg.de oder die Cineplex-App",
           ("nuernberg", "nurnberg", "nuremberg", "nürnberg", "admiral", "nürnburg", "nuernburg", "nuernbeg"),
           own=(Source("admiral-kino-nuernberg.de", _admiral_loader),), filmstarts_id="A0661"),
    Cinema("fuerth", "Cineplex Fürth", "Fürth",
           "https://www.kinoprogramm.com/kino/fuerth/cineplex-87698",
           "cineplex.de oder die Cineplex-App",
           ("fuerth", "furth", "fürth", "fuert", "fürt", "cineplex"), filmstarts_id="A2727"),
    Cinema("ansbach", "Capitol Kinocenter Ansbach", "Ansbach",
           "https://www.kinoprogramm.com/kino/ansbach/capitol-kinocenter-32665",
           "capitol-kino-ansbach.de",
           ("ansbach", "ansbacher", "ansbak", "capitol", "kapitol"),
           own=(Source("capitol-kinocenter.de", _capitol_loader),)),
    Cinema("crailsheim", "Cinecity Crailsheim", "Crailsheim",
           "https://www.kinoprogramm.com/kino/crailsheim/cinecity-und-kammer-filmtheater-42476",
           "cinecity-crailsheim.de",
           ("crailsheim", "kreilsheim", "crailsheimer", "kreilsheim", "crailsham", "cinecity", "cine city"),
           own=(Source("cinecity-crailsheim.de", _cinecity_loader),), filmstarts_id="A0224"),
)
CINEMA_BY_KEY = {c.key: c for c in CINEMAS}


# ── Filmtitel-Abgleich ─────────────────────────────────────────────────────────────
def _title_matches(query: str, title: str) -> bool:
    q, t = fold(query), fold(title)
    if not q or not t:
        return False
    if q == t:
        return True
    qt, tt = q.split(), t.split()
    if all(w in tt for w in qt):                    # ganze Woerter, nie Teilstrings in fremden Woertern
        return True
    if len(q) < 5:                                  # kurze Anfragen ("Up", "Es"): nur exakt/Wortgleich
        return False
    if f" {q} " in f" {t} ":
        return True
    return difflib.SequenceMatcher(None, q, t).ratio() >= 0.82


# ── Abruf + Zwischenspeicher ───────────────────────────────────────────────────────
Fetcher = Fetch


def _same_site(final_host: str, request_url: str) -> bool:
    """Weiterleitung nur innerhalb derselben Seite (www. egal), nie auf fremde Hosts."""
    from urllib.parse import urlsplit

    def norm(h: str) -> str:
        h = (h or "").lower().rstrip(".")
        return h[4:] if h.startswith("www.") else h

    return norm(final_host) == norm(urlsplit(request_url).hostname or "")


async def _http_fetch(url: str, timeout_s: float = DEFAULT_TIMEOUT_S) -> str:
    import httpx

    async with httpx.AsyncClient(timeout=timeout_s, follow_redirects=True,
                                 headers={"User-Agent": USER_AGENT, "Accept-Language": "de-DE,de;q=0.9"}) as cl:
        r = await cl.get(url)
        r.raise_for_status()
        if not _same_site(r.url.host or "", url):
            raise ValueError("redirect_off_host")
        if len(r.content) > MAX_BYTES:
            raise ValueError("response_too_large")
        return r.text


@dataclass
class _Entry:
    ts: float
    films: list[FilmDays]
    source: str = FALLBACK_SOURCE
    until: str | None = None        # Teilstand: Tage bis einschliesslich diesem Datum sind geladen (sonst vollstaendig)


def _films_to_json(films: list[FilmDays]) -> list[dict]:
    return [{"title": f.title, "days": {d: [[s.time, s.tag] for s in sh] for d, sh in f.days.items()}} for f in films]


def _films_from_json(raw: Any) -> list[FilmDays]:
    out: list[FilmDays] = []
    for f in raw:
        out.append(FilmDays(title=str(f["title"]),
                            days={str(d): [Show(str(t), str(g)) for t, g in sh] for d, sh in f["days"].items()}))
    return out


class KinoService:
    """Holt/haelt Kinoprogramme schonend: 60 Min frisch, danach veraltet ausliefern + im Hintergrund erneuern,
    immer nur ein Abruf gleichzeitig mit Abstand, nach Ausfall exponentielle Pause, Sicherung auf Platte."""

    def __init__(self, fetch: Fetcher | None = None, clock: Callable[[], float] = time.time,
                 now_fn: Callable[[], datetime] | None = None, ttl_s: float = DEFAULT_TTL_S,
                 timeout_s: float = DEFAULT_TIMEOUT_S, gap_s: float = REQUEST_GAP_S,
                 cache_path: str | None = None, fg_timeout_s: float = FOREGROUND_TIMEOUT_S) -> None:
        self._fetch = fetch
        self._clock = clock
        self._now = now_fn or (lambda: datetime.now(BERLIN))
        self._ttl = ttl_s
        self._timeout = timeout_s
        self._gap = gap_s
        self._path = cache_path
        self._cache: dict[str, _Entry] = {}
        self._fail_at: dict[str, float] = {}
        self._fail_count: dict[str, int] = {}
        self._locks: dict[str, asyncio.Lock] = {}
        self._first: dict[str, asyncio.Event] = {}            # "erstes Ergebnis da" (Teilstand oder fertig) je Kino
        self._inflight: dict[str, asyncio.Task] = {}          # laufende Erneuerung je Kino (Warmup/Gespraech teilen sie)
        self._tasks: set[asyncio.Task] = set()
        self._gates: dict[str, asyncio.Lock] = {}             # Abstand/Serie nur PRO HOST, nicht zwischen Hosts
        self._last_req: dict[str, float] = {}
        self._fg_timeout = fg_timeout_s
        self._warm_task: asyncio.Task | None = None
        self._last_asked: float | None = None
        self._loaded = False

    async def _download(self, url: str) -> str:
        if self._fetch is not None:
            return await self._fetch(url)
        return await _http_fetch(url, self._timeout)

    # -- Sicherung auf Platte (best effort, nie ein Fehler nach aussen) --
    def _load_snapshot(self) -> None:
        if self._loaded:
            return
        self._loaded = True
        if not self._path:
            return
        try:
            raw = json.loads(Path(self._path).read_text(encoding="utf-8"))
            now = self._clock()
            meta = raw.get("_meta") if isinstance(raw, dict) else None
            if isinstance(meta, dict) and isinstance(meta.get("last_asked"), (int, float)):
                self._last_asked = float(meta["last_asked"])
            for key, v in raw.items():
                ts = float(v["ts"])
                if key in CINEMA_BY_KEY and 0 <= now - ts < STALE_MAX_S:
                    self._cache[key] = _Entry(ts, _films_from_json(v["films"]), str(v.get("src") or FALLBACK_SOURCE))
        except FileNotFoundError:
            pass
        except Exception as exc:  # noqa: BLE001 — kaputte Datei bricht nichts
            log.warning("kino_snapshot_load_failed", error_type=type(exc).__name__)

    def _save_snapshot(self) -> None:
        if not self._path:
            return
        tmp = f"{self._path}.tmp"
        try:
            data: dict[str, Any] = {k: {"ts": e.ts, "src": e.source, "films": _films_to_json(e.films)}
                                    for k, e in self._cache.items() if e.until is None}   # Teilstaende nie sichern
            if self._last_asked is not None:
                data["_meta"] = {"last_asked": self._last_asked}
            Path(tmp).write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
            os.replace(tmp, self._path)
        except Exception as exc:  # noqa: BLE001
            log.warning("kino_snapshot_save_failed", error_type=type(exc).__name__)
            try:
                os.remove(tmp)
            except OSError:
                pass

    # -- Pause nach Ausfall --
    def _in_backoff(self, key: str, now: float) -> bool:
        n = self._fail_count.get(key, 0)
        if not n:
            return False
        wait = min(FAIL_BACKOFF_S * (2 ** (n - 1)), FAIL_BACKOFF_MAX_S)
        return now - self._fail_at.get(key, 0.0) < wait

    async def _refresh(self, cinema: Cinema) -> None:
        """Ein Abruf je Kino (je Host nur einer gleichzeitig, mit Abstand). Wirft nie."""
        lock = self._locks.setdefault(cinema.key, asyncio.Lock())
        async with lock:
            e = self._cache.get(cinema.key)
            now = self._clock()
            if (e and now - e.ts < self._ttl) or self._in_backoff(cinema.key, now):
                return                                         # jemand war schneller / wir sind in der Pause
            films: list[FilmDays] | None = None
            used = FALLBACK_SOURCE
            for src in cinema.sources:                       # Eigenseite zuerst, kinoprogramm.com nur bei Ausfall
                gap = min(src.gap_s, self._gap) if src.gap_s is not None else self._gap

                async def fetch(url: str, _gap: float = gap) -> str:
                    host = (urlsplit(url).hostname or "").lower()
                    async with self._gates.setdefault(host, asyncio.Lock()):   # je Host nacheinander, mit Abstand
                        wait = _gap - (time.monotonic() - self._last_req.get(host, 0.0))
                        if wait > 0:
                            await asyncio.sleep(wait)
                        self._last_req[host] = time.monotonic()
                        return await self._download(url)

                def publish(part: list[FilmDays], until: str, _name: str = src.name) -> None:
                    # Teilstand sofort nutzbar machen; als "abgelaufen" markiert, damit der fertige Abruf ihn ersetzt
                    if cinema.key in self._cache and self._cache[cinema.key].until is None:
                        return                                   # ein aelterer Volleintrag ist besser als der kurze Teilstand
                    self._cache[cinema.key] = _Entry(self._clock() - self._ttl, part, _name, until)
                    ev = self._first.get(cinema.key)
                    if ev is not None:
                        ev.set()

                try:
                    got = await asyncio.wait_for(
                        src.loader(fetch, publish=publish) if src.progressive else src.loader(fetch),
                        timeout=src.timeout_s or self._timeout + 1)
                    if not got:
                        raise ValueError("parse_empty")
                    films, used = got, src.name
                    break
                except Exception as exc:  # noqa: BLE001 — Quelle faellt aus: naechste versuchen
                    log.warning("kino_source_failed", cinema=cinema.key, source=src.name,
                                error_type=type(exc).__name__)
            if films is None:
                if (pe := self._cache.get(cinema.key)) is not None and pe.until is not None:
                    self._cache[cinema.key] = _Entry(self._clock(), pe.films, pe.source, pe.until)   # Teilstand behalten
                self._fail_count[cinema.key] = self._fail_count.get(cinema.key, 0) + 1
                self._fail_at[cinema.key] = self._clock()
                log.warning("kino_fetch_failed", cinema=cinema.key, fails=self._fail_count[cinema.key])
                return
            self._cache[cinema.key] = _Entry(self._clock(), films, used)
            self._fail_count.pop(cinema.key, None)
            self._fail_at.pop(cinema.key, None)
            await asyncio.to_thread(self._save_snapshot)

    def _ensure_refresh(self, cinema: Cinema) -> "asyncio.Task":
        """Laufende Erneuerung dieses Kinos wiederverwenden oder starten (Warmup und Gespraech teilen sie)."""
        t = self._inflight.get(cinema.key)
        if t is not None and not t.done():
            return t
        t = asyncio.get_running_loop().create_task(self._refresh(cinema))
        self._inflight[cinema.key] = t
        self._first[cinema.key] = asyncio.Event()
        self._tasks.add(t)

        def _done(_t: "asyncio.Task", key: str = cinema.key) -> None:
            self._tasks.discard(_t)
            ev = self._first.get(key)
            if ev is not None and self._inflight.get(key) is _t:     # nur das Event des EIGENEN Abrufs setzen
                ev.set()
            if self._inflight.get(key) is _t:
                self._inflight.pop(key, None)

        t.add_done_callback(_done)
        return t

    def _refresh_in_background(self, cinema: Cinema) -> None:
        self._ensure_refresh(cinema)

    async def wait_background(self) -> None:
        while self._tasks:
            await asyncio.gather(*list(self._tasks), return_exceptions=True)

    # -- Vorwaermen --
    def _needs_refresh(self, cinema: Cinema, now: float) -> bool:
        e = self._cache.get(cinema.key)
        return not (e and now - e.ts < self._ttl) and not self._in_backoff(cinema.key, now)

    async def warm(self) -> None:
        """Alle 4 Kinos auffrischen, wo noetig: PARALLEL (Abstand nur pro Host). Wirft nie."""
        try:
            self._load_snapshot()
            now = self._clock()
            todo = [c for c in CINEMAS if self._needs_refresh(c, now)]
            if todo:
                await asyncio.gather(*(self._ensure_refresh(c) for c in todo), return_exceptions=True)
        except Exception as exc:  # noqa: BLE001
            log.warning("kino_warm_failed", error_type=type(exc).__name__)

    def warm_in_background(self) -> None:
        """Sofort zurueckkehren, im Hintergrund waermen (mehrfache Aufrufe starten nichts doppelt)."""
        if self._warm_task is not None and not self._warm_task.done():
            return
        t = asyncio.get_running_loop().create_task(self.warm())
        self._warm_task = t
        self._tasks.add(t)
        t.add_done_callback(self._tasks.discard)

    def note_question(self) -> None:
        """Merkt sich, dass nach Kino gefragt wurde (steuert das gezielte Wiederwaermen)."""
        self._load_snapshot()
        self._last_asked = self._clock()
        self._save_snapshot()

    def _should_warm_now(self) -> bool:
        self._load_snapshot()
        if self._last_asked is None or self._clock() - self._last_asked > WARM_ASKED_WITHIN_S:
            return False
        return WARM_FIRST_HOUR <= self._now().hour <= WARM_LAST_HOUR

    async def warm_if_recent(self) -> None:
        """Stuendlicher Job: nur zwischen 12 und 23 Uhr und nur wenn in den letzten 24 h nach Kino gefragt wurde."""
        if self._should_warm_now():
            await self.warm()

    async def program(self, cinema: Cinema) -> tuple[list[FilmDays] | None, float | None, bool]:
        """(Filme, Stand-Zeitstempel, veraltet). Filme None = keine Daten (Ausfall). Wirft nie."""
        self._load_snapshot()
        now = self._clock()
        e = self._cache.get(cinema.key)
        if e and now - e.ts >= STALE_MAX_S:
            self._cache.pop(cinema.key, None)                  # zu alt: nie mehr zeigen
            e = None
        if e and now - e.ts < self._ttl:
            return e.films, e.ts, False
        if e:                                                  # abgelaufen, aber nutzbar: sofort liefern, im Hintergrund erneuern
            if not self._in_backoff(cinema.key, now):
                self._ensure_refresh(cinema)
            return e.films, e.ts, (now - e.ts) > 2 * self._ttl
        if self._in_backoff(cinema.key, now):
            return None, None, False
        self._ensure_refresh(cinema)                           # nichts im Speicher: holen, aber hoechstens fg_timeout warten
        ev = self._first.get(cinema.key)                       # (Abruf laeuft danach im Hintergrund weiter)
        if ev is not None:
            try:
                await asyncio.wait_for(ev.wait(), timeout=self._fg_timeout)   # erstes Ergebnis: Teilstand oder fertig
            except asyncio.TimeoutError:
                pass
        e = self._cache.get(cinema.key)
        return (e.films, e.ts, False) if e else (None, None, False)

    async def _results(self, cinemas: tuple["Cinema", ...], iso: str) -> list[tuple]:
        """program() je Kino; liegt nur ein Teilstand vor, der den Tag NICHT abdeckt, kurz auf den Rest warten."""
        t0 = time.monotonic()
        results = list(await asyncio.gather(*(self.program(c) for c in cinemas)))
        waits = []
        for c in cinemas:
            e = self._cache.get(c.key)
            t = self._inflight.get(c.key)
            if e is not None and e.until is not None and e.until < iso and t is not None and not t.done():
                waits.append(t)
        if waits:
            await asyncio.wait(waits, timeout=max(0.0, self._fg_timeout - (time.monotonic() - t0)))   # Gesamtgrenze
            results = list(await asyncio.gather(*(self.program(c) for c in cinemas)))
        for i, c in enumerate(cinemas):                        # Tag immer noch nicht geladen -> ehrlich "nicht abrufbar"
            e = self._cache.get(c.key)
            if e is not None and e.until is not None and e.until < iso:
                results[i] = (None, results[i][1], False)
        return results

    async def overview(self, day_offset: int = 0) -> Overview | None:
        """Titelueberblick aller vier Kinos fuer heute/morgen/uebermorgen. None = nichts Sicheres (alle Kinos ohne
        Daten oder Fehler) -> der Aufrufer nimmt den normalen Weg ueber das Modell. Wirft nie."""
        try:
            self.note_question()
            now = self._now()
            day = now.date() + timedelta(days=max(0, min(int(day_offset), 2)))
            results = await self._results(CINEMAS, day.isoformat())
            if all(f is None for f, _, _ in results):
                return None
            ranked = _rank_titles(results, day.isoformat(), day, now)
            missing = tuple(c.city for c, (f, _, _) in zip(CINEMAS, results) if f is None)
            return Overview(tuple(t for t, _, _ in ranked[:MAX_TITLES]), max(0, len(ranked) - MAX_TITLES),
                            missing, max(0, min(int(day_offset), 2)))
        except Exception as exc:  # noqa: BLE001
            log.warning("kino_overview_failed", error_type=type(exc).__name__)
            return None

    # ── Antworten (Rohtext fuers Modell) ───────────────────────────────────────────
    async def answer(self, kino: str | None = None, datum: str | None = None, film: str | None = None,
                     nur_titel: bool = False) -> tuple[str, bool]:
        """(Text, ok). ok=False = Werkzeugfehler (Modell soll ehrlich sagen, was fehlt)."""
        self.note_question()
        now = self._now()
        today = now.date()
        film = (film or "").strip() or None
        wanted: Cinema | None = None
        if kino and kino.strip():
            wanted = resolve_cinema(kino)
            if wanted is None:
                names = ", ".join(c.name for c in CINEMAS)
                return (f"Dieses Kino kenne ich nicht. Ich kenne nur diese vier: {names}. "
                        "Sag Mike das kurz, erfinde keine Zeiten.", False)
        day = resolve_date(datum, today) if (datum and datum.strip()) else today
        if day is None:
            return ("Das Datum war nicht eindeutig. Frag Mike kurz nach dem Tag (z. B. heute, morgen, Freitag).", False)
        if not film and wanted is None and not nur_titel:
            return ("Zu unscharf: Frag Mike in einem Satz, ob er einen bestimmten Film sucht, ein bestimmtes Kino "
                    "möchte oder allgemein wissen will, was in den vier Kinos läuft. Ruf das Werkzeug erst danach auf.",
                    False)

        cinemas = (wanted,) if wanted else CINEMAS
        iso = day.isoformat()
        results = await self._results(cinemas, iso)
        day_label = _day_label(day, today)
        notes: list[str] = []
        stamps = [ts for _, ts, _ in results if ts]
        for c, (films, ts, stale) in zip(cinemas, results):
            if films is None:
                notes.append(f"{c.name}: Programm gerade nicht abrufbar (verweise auf {c.own_site}).")
            elif stale:
                notes.append(f"{c.name}: Daten evtl. veraltet (Stand {_hhmm(ts)} Uhr).")
        if all(f is None for f, _, _ in results):
            return ("Das Kinoprogramm ist gerade nicht abrufbar. Sag das ehrlich und verweise auf die Cineplex-App "
                    "bzw. die Kinoseiten. Nenne KEINE Zeiten.", False)
        srcs = sorted({self._cache[c.key].source for c, (f, _, _) in zip(cinemas, results)
                       if f is not None and c.key in self._cache})
        stand = (f"Quelle (nur auf Nachfrage nennen): {', '.join(srcs) or SOURCE_NAME}, Stand {_hhmm(min(stamps))} Uhr."
                 if stamps else f"Quelle (nur auf Nachfrage nennen): {', '.join(srcs) or SOURCE_NAME}.")

        lines: list[str] = []
        if film:
            lines.append(f"Film '{film}' {day_label}:")
            found_any = False
            for c, (films, _, _) in zip(cinemas, results):
                if films is None:
                    continue
                m = [f for f in films if _title_matches(film, f.title)]
                if not m:
                    lines.append(f"- {c.name}: läuft dort nicht.")
                    continue
                found_any = True
                for f in m:
                    shows = _shows_for(f, iso, day, now)
                    lines.append(f"- {c.name} ({f.title}): {shows}")
            if not found_any:
                lines.append("In keinem der abrufbaren Kinos gefunden. Sag das ehrlich, rate keinen Titel/keine Zeit.")
        elif nur_titel and wanted is None:
            ranked = _rank_titles(results, iso, day, now)
            if not ranked:
                lines.append(f"{day_label.capitalize()} läuft in den vier Kinos nichts (mehr) laut Quelle.")
            else:
                lines.append(f"Filme {day_label} in den vier Kinos, nach Beliebtheit (nur Titel, hoechstens {MAX_TITLES} "
                             "davon in einem Satz nennen, ohne Zeiten, Details, Aufzaehlungszeichen und Quelle):")
                lines += [f"- {t}" for t, _, _ in ranked[:MAX_TITLES]]
                if len(ranked) > MAX_TITLES:
                    lines.append(f"(+ {len(ranked) - MAX_TITLES} weitere, nur auf Nachfrage)")
        else:
            c = wanted
            films, _, _ = results[0]
            rows = []
            for f in films or []:
                if _shows_exist(f, iso, day, now):
                    rows.append(f"- {f.title}: {_shows_for(f, iso, day, now)}")
            if not rows:
                lines.append(f"{c.name} {day_label}: keine Vorstellungen (mehr) laut Quelle.")
            else:
                lines.append(f"{c.name} {day_label}:")
                lines += rows[:MAX_FILMS_PER_CINEMA]
                if len(rows) > MAX_FILMS_PER_CINEMA:
                    lines.append(f"(+ {len(rows) - MAX_FILMS_PER_CINEMA} weitere Filme)")
        lines += notes
        lines.append(stand)
        return "\n".join(lines), True


def _rank_titles(results: list[tuple], iso: str, day: date, now: datetime) -> list[tuple[str, int, int]]:
    """Titel mit Vorstellungen an diesem Tag, nach Beliebtheit: (Name, Anzahl Kinos, Anzahl Vorstellungen)."""
    titles: dict[str, tuple[str, int, int]] = {}
    for films, _, _ in results:
        for f in films or []:
            n = len(_upcoming(f, iso, day, now))
            if not n:
                continue
            k = fold(f.title)
            prev = titles.get(k)
            titles[k] = (prev[0] if prev else f.title, (prev[1] if prev else 0) + 1, (prev[2] if prev else 0) + n)
    return sorted(titles.values(), key=lambda x: (-x[1], -x[2], fold(x[0])))   # Beliebtheit: Kinos, dann Zeiten


@dataclass(frozen=True)
class Overview:
    """Titelueberblick fuer den Schnellweg (ohne Modell)."""
    titles: tuple[str, ...]         # hoechstens MAX_TITLES, nach Beliebtheit
    more: int                       # weitere Titel, die nicht genannt werden
    missing: tuple[str, ...]        # Orte, deren Programm gerade nicht abrufbar ist
    day_offset: int


def _hhmm(ts: float | None) -> str:
    return datetime.fromtimestamp(ts, BERLIN).strftime("%H:%M") if ts else "?"


_WD = ("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")


def _day_label(day: date, today: date) -> str:
    if day == today:
        return f"heute ({day.strftime('%d.%m.')})"
    if day == today + timedelta(days=1):
        return f"morgen ({day.strftime('%d.%m.')})"
    return f"am {_WD[day.weekday()]} {day.strftime('%d.%m.')}"


def _upcoming(f: FilmDays, iso: str, day: date, now: datetime) -> list[Show]:
    shows = f.days.get(iso, [])
    if day == now.date():
        hm = now.strftime("%H:%M")
        shows = [s for s in shows if s.time >= hm]
    return shows


def _shows_exist(f: FilmDays, iso: str, day: date, now: datetime) -> bool:
    return bool(_upcoming(f, iso, day, now))


def _shows_for(f: FilmDays, iso: str, day: date, now: datetime) -> str:
    shows = _upcoming(f, iso, day, now)
    if not shows:
        if iso not in f.days:
            return "keine Vorstellung an dem Tag (oder noch nicht im Programm)."
        return "heute keine Vorstellung mehr."
    return ", ".join(f"{s.time}{' ' + s.tag if s.tag else ''}" for s in shows)


_SERVICE: KinoService | None = None


def get_service() -> KinoService:
    global _SERVICE
    if _SERVICE is None:
        path: str | None = None
        try:
            from app.config import get_settings

            path = getattr(get_settings(), "kino_cache_path", "") or None
        except Exception:  # noqa: BLE001
            path = None
        _SERVICE = KinoService(cache_path=path)
    return _SERVICE

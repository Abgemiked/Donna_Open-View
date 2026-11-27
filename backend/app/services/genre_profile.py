"""genre_profile.py — Genre-Anteile fuer Mikes Geschmacksprofil (#182, Folge zu Phase 3).

Zwei Quellen, beide NUR als Aggregat (Anteile je Genre, nie Titel):
  * Stufe 1 — Jellyfin: ``played_items(..., with_genres=True)`` liefert ``Genres`` je gesehenem Titel.
  * Stufe 2 — Netflix: Titel (aus dem LTM, nur Mikes Profil) werden EINMALIG per TMDB auf Genres abgebildet.
    Default AUS (``GENRE_TMDB_ENABLED``), wirkt nur mit ``TMDB_API_KEY`` in der Server-Umgebung.

Datenschutz-Leitplanken (bewusst im Code, nicht nur im Doc):
  * Gespeichert wird nur ``{"n_titles": int, "genres": {name: count}}`` je Quelle in einer kleinen JSON-Datei
    neben der LTM-DB. KEINE Titel, keine Zeitstempel, kein Mapping Titel->Genre auf Platte.
  * Nach aussen (TMDB) gehen ausschliesslich Titel-Strings, dedupliziert und in zufaelliger Reihenfolge,
    ohne Nutzer-/Profil-/Zeitbezug; der Key steht im Header und wird nie geloggt.
  * Ein Genre erscheint im Profil erst ab ``MIN_GENRE_COUNT`` Titeln (nicht auf Einzeltitel rueckfuehrbar);
    die Anzeige ist auf die Top ``TOP_N`` begrenzt, Prozent gerundet auf 5er-Schritte.
  * Kein LLM sieht Titel: die Abbildung ist reine Regel-/HTTP-Logik.
"""
from __future__ import annotations

import json
import re
import os
import random
import tempfile
import time
from collections import Counter
from pathlib import Path
from typing import Any, Callable, Iterable

from app.core.logger import get_logger

log = get_logger("service.genre_profile")

MIN_GENRE_COUNT = 5      # darunter nie ausgeben (k-Anonymitaet gegen Einzeltitel-Rueckschluss)
MIN_TITLES_WITH_GENRE = 20  # darunter keine Anteile
TOP_N = 5
MAX_TMDB_TITLES = 1500   # Obergrenze je Lauf
_MAX_GENRE_LEN = 40

SRC_JELLYFIN = "Jellyfin"
SRC_NETFLIX = "Netflix"

TMDB_BASE = "https://api.themoviedb.org/3"


# ------------------------------------------------------------------------------------------------
# Reine Aggregation
# ------------------------------------------------------------------------------------------------

_GENRE_OK = re.compile(r"^[\w &/'\-]{1,%d}$" % _MAX_GENRE_LEN)
_MAX_DISTINCT_GENRES = 200


def _norm_genre(g: Any) -> str:
    """Genre-Name normalisieren; alles ausserhalb einer engen Zeichen-Allowlist wird verworfen ('')
    (Jellyfin-Genres sind freie Tags -> kein Freitext/Prompt-Injection im Profiltext)."""
    s = " ".join(str(g or "").split())
    return s if _GENRE_OK.match(s) else ""


def count_genres(genre_lists: Iterable[Iterable[Any]]) -> dict[str, Any]:
    """Zaehler aus einer Liste von Genre-Listen (eine je Titel). Titel ohne Genre zaehlen nicht mit."""
    c: Counter = Counter()
    n = 0
    for gl in genre_lists:
        gs = {_norm_genre(g) for g in (gl or [])}
        gs.discard("")
        if not gs:
            continue
        n += 1
        c.update(gs)
    if len(c) > _MAX_DISTINCT_GENRES:
        c = Counter(dict(c.most_common(_MAX_DISTINCT_GENRES)))
    return {"n_titles": n, "genres": dict(c)}


def shares(counts: dict[str, Any]) -> list[tuple[str, int]] | None:
    """Top-Genres als (Name, Prozent) — Prozent der Titel MIT Genre-Angabe, die das Genre tragen (Mehrfachnennung moeglich).
    None bei zu wenig Daten. Genres unter MIN_GENRE_COUNT werden unterdrueckt; Prozent auf 5er gerundet."""
    n = int(counts.get("n_titles") or 0)
    if n < MIN_TITLES_WITH_GENRE:
        return None
    items = [(g, int(v)) for g, v in (counts.get("genres") or {}).items() if int(v) >= MIN_GENRE_COUNT]
    items.sort(key=lambda kv: (-kv[1], kv[0]))
    out = [(g, int(v * 100.0 / n / 5.0 + 0.5) * 5) for g, v in items[:TOP_N]]
    out = [(g, p) for g, p in out if p >= 5]          # unter 2,5 % nicht ausgeben (kein Hochrunden)
    return out or None


def genre_sentence(per_source: dict[str, dict[str, Any]]) -> str | None:
    """Text-Zusatz fuer taste_text: je Quelle Top-Genres + Datenbasis. None wenn nichts ausgebbar."""
    parts: list[str] = []
    for src, counts in per_source.items():
        sh = shares(counts)
        if not sh:
            continue
        n = int(counts["n_titles"])
        base = f"{src}: {n} Titel via Genre-Zuordnung" if src == SRC_NETFLIX else f"{src}: {n} Titel"
        stand = f", Stand {counts['stand']}" if counts.get("stand") else ""
        parts.append(f"{base}{stand} — " + ", ".join(f"{g} {p}%" for g, p in sh))
    if not parts:
        return None
    return (f"Genre-Anteile (Mehrfachnennung moeglich, nur ab {MIN_GENRE_COUNT} Titeln, nur manuell aktualisiert): " + "; ".join(parts) + ".")


# ------------------------------------------------------------------------------------------------
# Persistenz (nur Zaehler)
# ------------------------------------------------------------------------------------------------

def cache_path(ltm_db_path: str | None = None) -> Path:
    base = Path(ltm_db_path).parent if ltm_db_path else Path("/data")
    return base / "genre_counts.json"


def load_counts(path: Path | None) -> dict[str, dict[str, Any]]:
    """Liest den Zaehler-Cache; fail-safe ({} bei Fehler). Nur bekannte Struktur wird uebernommen."""
    if path is None:
        return {}
    try:
        raw = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    out: dict[str, dict[str, Any]] = {}
    if isinstance(raw, dict):
        for src, c in raw.items():
            if isinstance(c, dict) and isinstance(c.get("genres"), dict):
                try:
                    out[str(src)] = {"n_titles": int(c.get("n_titles") or 0),
                                     "genres": {str(k): int(v) for k, v in c["genres"].items()
                                                if _norm_genre(k)}}
                    if re.fullmatch(r"\d{4}-\d{2}", str(c.get("stand") or "")):
                        out[str(src)]["stand"] = str(c["stand"])
                except (TypeError, ValueError):
                    continue
    return out


def save_counts(path: Path, per_source: dict[str, dict[str, Any]]) -> None:
    """Atomar schreiben, Rechte 0600. Es werden nur Zaehler gespeichert."""
    # Differenzangriff vermeiden: Genres unter MIN_GENRE_COUNT werden nicht gespeichert, Zaehler auf 5er gerundet.
    def _r(v: int) -> int:
        return int(round(v / 5.0)) * 5
    from datetime import datetime
    now = datetime.now().strftime("%Y-%m")
    safe = {s: {"stand": c.get("stand") or now, "n_titles": _r(int(c["n_titles"])),
                "genres": {str(k): _r(int(v)) for k, v in c["genres"].items() if int(v) >= MIN_GENRE_COUNT}}
            for s, c in per_source.items()}
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=str(path.parent), prefix=".genre_counts.")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(safe, f, ensure_ascii=False, indent=1)
        try:
            os.chmod(tmp, 0o600)
        except OSError:
            pass
        os.replace(tmp, path)
    except Exception:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


# ------------------------------------------------------------------------------------------------
# Stufe 1 — Jellyfin
# ------------------------------------------------------------------------------------------------

async def jellyfin_counts(jellyfin: Any, profile: str = "Mike", *, limit: int = 2000) -> dict[str, Any]:
    """Genre-Zaehler aus Jellyfin. Ergebnis: {"status": ok|disabled|no_user|no_data, "counts": {...}}.
    Fail-safe: Jellyfin-Fehler -> status 'no_data' (ehrlich, kein Crash)."""
    if jellyfin is None or not jellyfin.ready():
        return {"status": "disabled", "counts": count_genres([])}
    users = await jellyfin.list_users()
    if not users:
        return {"status": "no_data", "counts": count_genres([]), "hint": "Jellyfin /Users leer oder nicht erreichbar (401/Timeout?)"}
    from app.services.media_profile_service import resolve_profile
    uid = None
    for u in users:
        if isinstance(u, dict) and resolve_profile(u.get("Name", "")) == profile:
            uid = u.get("Id")
            break
    if not uid:
        return {"status": "no_user", "counts": count_genres([])}
    items = await jellyfin.played_items(uid, limit=limit, with_genres=True)
    if not items:
        return {"status": "no_data", "counts": count_genres([]), "hint": "keine gesehenen Items geliefert"}
    lists = [it.get("Genres") for it in items if isinstance(it, dict) and isinstance(it.get("Genres"), list)]
    return {"status": "ok", "counts": count_genres(lists), "items_seen": len(items)}


# ------------------------------------------------------------------------------------------------
# Stufe 2 — Netflix-Titel per TMDB (einmalig, default aus)
# ------------------------------------------------------------------------------------------------

def tmdb_available(env: dict[str, str] | None = None) -> tuple[bool, str]:
    """(verfuegbar, Grund). Schalter GENRE_TMDB_ENABLED muss true sein UND TMDB_API_KEY gesetzt."""
    e = env if env is not None else os.environ
    if str(e.get("GENRE_TMDB_ENABLED", "")).strip().lower() not in ("1", "true", "yes", "on"):
        return False, "Schalter GENRE_TMDB_ENABLED ist aus"
    if not (e.get("TMDB_API_KEY") or "").strip():
        return False, "kein TMDB_API_KEY gesetzt"
    return True, "ok"


def _norm_title(s: str) -> str:
    return " ".join(str(s or "").casefold().split())


class TmdbClient:
    """Minimaler synchroner TMDB-Client (httpx). Sendet nur Titel-Strings. Key nur im Header (v4-Token)
    bzw. als api_key-Parameter (v3) — nie geloggt. ``http_get`` ist fuer Tests injizierbar."""

    def __init__(self, api_key: str, *, http_get: Callable[..., Any] | None = None, delay_s: float = 0.15,
                 timeout_s: float = 8.0) -> None:
        self._key = api_key.strip()
        self._delay = delay_s
        self._timeout = timeout_s
        self._get = http_get
        self._genre_names: dict[int, str] | None = None
        self.fail_streak = 0          # aufeinanderfolgende Request-Fehler (Abbruch im Lauf)
        # httpx loggt auf INFO die volle URL (Titel + v3-api_key) -> ausschalten, sonst Leak ins Container-Log.
        import logging
        for name in ("httpx", "httpcore"):
            logging.getLogger(name).setLevel(logging.WARNING)

    def _request(self, path: str, params: dict[str, str]) -> Any | None:
        headers = {"Accept": "application/json", "User-Agent": "donna-genre-profile"}
        params = dict(params)
        if self._key.startswith("eyJ"):          # v4 Read-Access-Token (JWT)
            headers["Authorization"] = f"Bearer {self._key}"
        else:                                    # v3 API-Key
            params["api_key"] = self._key
        getter = self._get
        if getter is None:
            import httpx
            getter = httpx.get
        try:
            resp = getter(f"{TMDB_BASE}{path}", params=params, headers=headers, timeout=self._timeout)
            resp.raise_for_status()
            data = resp.json()
            self.fail_streak = 0
            return data
        except Exception as e:  # noqa: BLE001 — nie Key/Titel/URL loggen
            self.fail_streak += 1
            log.warning("tmdb_request_failed", path=path, error_type=type(e).__name__)
            return None
        finally:
            if self._delay:
                time.sleep(self._delay)

    def genre_names(self) -> dict[int, str]:
        if not self._genre_names:           # leer/unvollstaendig wird NICHT gecacht
            names: dict[int, str] = {}
            for kind in ("movie", "tv"):
                data = self._request(f"/genre/{kind}/list", {"language": "de-DE"})
                if not isinstance(data, dict):
                    return {}
                for g in (data or {}).get("genres", []) if isinstance(data, dict) else []:
                    if isinstance(g, dict) and isinstance(g.get("id"), int) and g.get("name"):
                        names[g["id"]] = _norm_genre(g["name"])
            self._genre_names = names
        return self._genre_names or {}

    def genres_for_title(self, title: str) -> list[str] | None:
        """Genres des besten TMDB-Treffers; None bei keinem sicheren Treffer.
        'Sicher' = Titel/Originaltitel stimmt (case-insensitiv) exakt ueberein — sonst lieber keine Aussage."""
        data = self._request("/search/multi", {"query": title, "language": "de-DE", "include_adult": "false"})
        if not isinstance(data, dict):
            return None
        want = _norm_title(title)
        names = self.genre_names()
        for r in data.get("results", []):
            if not isinstance(r, dict) or r.get("media_type") not in ("movie", "tv"):
                continue
            cand = {_norm_title(r.get(k)) for k in ("title", "name", "original_title", "original_name")}
            if want in cand:
                gids = [g for g in (r.get("genre_ids") or []) if isinstance(g, int)]
                return [names[g] for g in gids if g in names] or None
        return None


class TmdbAbort(RuntimeError):
    """TMDB-Lauf abgebrochen (Key ungueltig / Netz / Rate-Limit) — es wird nichts gespeichert."""


MAX_FAIL_STREAK = 5


def netflix_counts(titles: Iterable[str], client: TmdbClient, *, max_titles: int = MAX_TMDB_TITLES,
                   rng: random.Random | None = None) -> dict[str, Any]:
    """Einmaliger Lauf: Titel (dedupliziert, zufaellige Reihenfolge) -> Genre-Zaehler.
    Titel bleiben nur im Speicher dieser Funktion; zurueck kommen nur Zaehler + Trefferquote."""
    if not client.genre_names():
        raise TmdbAbort("keine Genre-Liste von TMDB (Key ungueltig/Netz?)")
    uniq = sorted({t.strip()[:200] for t in titles if t and t.strip()})
    (rng or random.Random()).shuffle(uniq)           # keine Verlaufs-Reihenfolge nach aussen
    uniq = uniq[:max_titles]
    lists: list[list[str]] = []
    unmatched = 0
    for t in uniq:
        g = client.genres_for_title(t)
        if client.fail_streak >= MAX_FAIL_STREAK:
            raise TmdbAbort("zu viele aufeinanderfolgende TMDB-Fehler")
        if g:
            lists.append(g)
        else:
            unmatched += 1
    counts = count_genres(lists)
    counts["unmatched"] = unmatched
    counts["queried"] = len(uniq)
    return counts

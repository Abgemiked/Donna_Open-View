"""Erkennung einer EINDEUTIGEN Wetterfrage (#152, Schnellweg).

Bewusst eng und nach dem Ausschlussprinzip gebaut: Die Nachricht darf AUSSCHLIESSLICH aus einer
kleinen Wortliste (Frageformeln, Tagesangabe, Wetterwort) und optional EINEM Ortsnamen hinter
"in"/"bei"/"fuer" bestehen. Jedes weitere Wort (z. B. "und", "Termine", "gestern", "Stream",
"Erinnere", "wenn") macht die Frage mehrdeutig -> None -> normaler Weg mit allen Werkzeugen
und vollem Kontext. Im Zweifel also immer None.

Reine Funktion ohne Netz und ohne Zustand.
"""
from __future__ import annotations

import difflib
import re
from dataclasses import dataclass

_MAX_CHARS = 100
_MAX_TOKENS = 14
_MAX_CITY_TOKENS = 3
_MAX_CITY_CHARS = 40

# Zeichen, die in einer eindeutigen Wetterfrage vorkommen duerfen (alles andere -> None).
_ALLOWED_CHARS = re.compile(r"^[A-Za-zÄÖÜäöüß\s\-?!.,]+$")
_PUNCT = re.compile(r"[?!.,]")

_WEATHER_WORDS = frozenset({
    "wetter", "regnet", "regen", "schneit", "jacke", "regenschirm", "schirm",
    "warm", "kalt", "sonne", "scheint", "sonnig",
})
_DAY_WORDS = {"heute": 0, "morgen": 1, "übermorgen": 2, "uebermorgen": 2}
_FILLER = frozenset({
    "wie", "wird", "wirds", "ist", "das", "es", "jetzt", "gerade", "aktuell", "aktuelle", "hier",
    "bei", "mir", "in", "für", "fuer", "im", "bitte", "mal", "kurz", "donna", "hey", "hallo",
    "sag", "sage", "zeig", "zeige", "ich", "eine", "einen", "ne", "brauche", "brauch",
    "draußen", "draussen", "abend", "abends", "nachmittag", "nachmittags", "vormittag",
    "vormittags", "früh", "morgens",
})
_VOCAB = _WEATHER_WORDS | frozenset(_DAY_WORDS) | _FILLER
_PREPOSITIONS = frozenset({"in", "bei", "für", "fuer"})
# Grossgeschriebene Woerter, die KEIN Ort sind (Wochentage, Monate, Verwandte) -> mehrdeutig -> normaler Weg
_NOT_A_CITY = frozenset({
    "montag", "dienstag", "mittwoch", "donnerstag", "freitag", "samstag", "sonnabend", "sonntag",
    "wochenende", "januar", "februar", "märz", "maerz", "april", "mai", "juni", "juli", "august",
    "september", "oktober", "november", "dezember", "oma", "opa", "mama", "papa", "mutti", "vati",
    "mami", "papi", "tante", "onkel", "mir", "dir", "uns", "euch", "ihm", "ihr", "ihnen",
})


@dataclass(frozen=True)
class WeatherIntent:
    """city None = Standardort (wie das Werkzeug get_weather ohne location); day_offset 0/1/2."""

    city: str | None
    day_offset: int


def detect_weather_intent(message: str) -> WeatherIntent | None:
    if not isinstance(message, str):
        return None
    text = message.strip()
    if not text or len(text) > _MAX_CHARS or not _ALLOWED_CHARS.match(text):
        return None
    tokens = _PUNCT.sub(" ", text).split()
    if not tokens or len(tokens) > _MAX_TOKENS:
        return None

    city_tokens: list[str] = []
    days: set[int] = set()
    has_weather_word = False
    i = 0
    while i < len(tokens):
        tok = tokens[i]
        low = tok.lower()
        if low in _VOCAB:
            if low in _WEATHER_WORDS:
                has_weather_word = True
            if low in _DAY_WORDS:
                days.add(_DAY_WORDS[low])
            if low in _PREPOSITIONS and not city_tokens:
                j = i + 1
                found: list[str] = []
                while (j < len(tokens) and len(found) < _MAX_CITY_TOKENS
                       and tokens[j].lower() not in _VOCAB and tokens[j][:1].isupper()):
                    found.append(tokens[j])
                    j += 1
                if found and any(f.lower() in _NOT_A_CITY for f in found):
                    return None
                if found:
                    city_tokens = found
                    i = j
                    continue
            i += 1
            continue
        return None  # unbekanntes Wort (auch ein Ortsname ohne "in") -> mehrdeutig
    if not has_weather_word or len(days) > 1:
        return None
    city = " ".join(city_tokens) or None
    if city is not None and len(city) > _MAX_CITY_CHARS:
        return None
    return WeatherIntent(city=city, day_offset=next(iter(days), 0))


# ── Folgefrage nur mit Ortsnamen ("Hamburg", "und München?") direkt nach einer Wetterfrage (#152) ──────────
_FOLLOW_LEAD = frozenset({
    "und", "auch", "dann", "denn", "was", "ist", "mit", "wie", "sieht", "es", "aus", "in", "bei", "für", "fuer",
    "bitte", "nun", "das", "dort", "da",
})
_FOLLOW_CONNECTIVE = frozenset({"und", "in", "bei", "mit", "für", "fuer", "auch", "dann", "denn"})
# Grossgeschriebene/kleine Woerter, die kein Ort sind (Tage, Zeit, Verwandte, Floskeln, typische Nomen/Befehle)
_FOLLOW_NOT_CITY = _NOT_A_CITY | frozenset({
    "heute", "morgen", "übermorgen", "uebermorgen", "gestern", "jetzt", "gerade", "spät", "spaet", "später", "spaeter", "noch",
    "nochmal", "mal", "kurz", "genau", "gut", "besser", "schlechter", "mehr", "weniger", "wärmer", "kälter",
    "warum", "wieso", "weshalb", "wann", "wo", "wer", "wieviel", "doch", "schon", "aber", "oder", "ja", "nein",
    "danke", "okay", "ok", "hallo", "hi", "hey", "tschüss", "super", "stopp", "stop", "weiter", "wetter",
    "kalender", "termin", "termine", "licht", "lampe", "timer", "wecker", "musik", "film", "kino", "stream",
    "nachricht", "mail", "email", "anruf", "navigation", "route", "restaurant", "hotel", "preis", "uhr",
    "hauptbahnhof", "bahnhof", "flughafen", "abfahrt", "ankunft", "zug", "bus", "haltestelle", "adresse",
    "stunde", "stunden", "minute", "minuten", "tag", "woche", "nacht", "abend", "mittag", "früh", "frueh",
    # Wetter-/Zeitwoerter und Detailfragen ("Und Regen?", "Und abends?"): wttr kennt Orte mit solchen Namen
    "regen", "wind", "nebel", "sturm", "gewitter", "frost", "wolken", "hagel", "schnee", "sonne", "temperatur",
    "grad", "celsius", "details", "nachher", "gleich", "abends", "nachmittag", "nachmittags", "vormittag",
    "vormittags", "morgens", "mittags", "nachts", "stündlich", "stuendlich", "luftfeuchtigkeit", "feuchtigkeit",
    "vorhersage", "prognose", "wochenende", "donna", "mike", "hilfe", "pause", "lauter", "leise", "los", "ende",
    "moment", "moin", "servus", "ach", "oh", "hm", "ne", "jo", "echt", "passt", "cool", "krass", "status", "test",
    "news", "nachrichten", "zuhause", "hier", "büro", "arbeit",
    # Artikel/Possessiva: "Und die Kinder?", "Und meine Mutter?"
    "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "meine", "mein", "meinen", "meiner", "deine",
    "dein", "seine", "ihre", "unsere", "unser", "vielen", "gute", "guten", "vergiss",
})
# Bekannte Orte (normalisiert): nur diese gelten OHNE ausdrueckliches "in/bei" als Ort ("Hamburg", "und München?")
_KNOWN_PLACES_RAW = (
    "Berlin Hamburg München Köln Frankfurt Stuttgart Düsseldorf Leipzig Dortmund Essen Bremen Dresden Hannover "
    "Nürnberg Duisburg Bochum Wuppertal Bielefeld Bonn Münster Karlsruhe Mannheim Augsburg Wiesbaden Gelsenkirchen "
    "Mönchengladbach Braunschweig Chemnitz Kiel Aachen Halle Magdeburg Freiburg Krefeld Lübeck Mainz Erfurt "
    "Oberhausen Rostock Kassel Hagen Potsdam Saarbrücken Hamm Ludwigshafen Oldenburg Mülheim Osnabrück Leverkusen "
    "Heidelberg Darmstadt Solingen Regensburg Herne Paderborn Neuss Ingolstadt Offenbach Fürth Würzburg Ulm "
    "Heilbronn Pforzheim Wolfsburg Göttingen Bottrop Reutlingen Koblenz Bremerhaven Erlangen Jena Remscheid Trier "
    "Salzgitter Moers Siegen Hildesheim Cottbus Gütersloh Kaiserslautern Schwerin Witten Gera Iserlohn Zwickau "
    "Ansbach Bamberg Bayreuth Passau Landshut Rosenheim Kempten Konstanz Flensburg Wismar Greifswald Stralsund "
    "Görlitz Dessau Weimar Eisenach Fulda Gießen Giessen Marburg Tübingen Baden-Baden Garmisch-Partenkirchen "
    "Berchtesgaden Füssen Sylt Rügen Usedom Helgoland Norderney Schwabach Roth Weißenburg Gunzenhausen Rothenburg "
    "Dinkelsbühl Feuchtwangen YOUR_HOME_CITY Wien Salzburg Innsbruck Graz Linz Zürich Bern Basel Genf Luzern Paris "
    "London Rom Mailand Venedig Florenz Neapel Madrid Barcelona Lissabon Amsterdam Brüssel Kopenhagen Stockholm "
    "Oslo Helsinki Prag Warschau Budapest Athen Istanbul Dublin Edinburgh Moskau Dubai Mallorca Ibiza Teneriffa "
    "Rimini Antalya Kreta Rhodos Malaga Tokio Peking Bangkok Sydney Toronto Chicago Miami "
    "Munich Cologne Nuremberg Vienna Prague Rome Milan Venice Florence Naples Geneva Hanover Lisbon Copenhagen "
    "Warsaw Brussels Athens Moscow Zurich"
)
_KNOWN_PLACES: frozenset[str] = frozenset()  # unten gefuellt (braucht _norm_place)

_FOLLOW_CHARS = re.compile(r"^[A-Za-zÄÖÜäöüß\s\-?!.,]+$")
_FOLLOW_TOKEN = re.compile(r"^[A-Za-zÄÖÜäöüß]+(?:-[A-Za-zÄÖÜäöüß]+)*$")


def detect_place_followup(message: str) -> str | None:
    """Ortsname, wenn die Aeusserung NUR aus einem Ort (1-2 Woerter) plus Fuellwoertern besteht, sonst None.

    "Hamburg", "und München?", "was ist mit Hamburg", "Wie sieht es in Hamburg aus". Kleingeschriebene Orte
    (Spracherkennung) nur mit Bindewort davor ("und hamburg"). Mehr als der Ort ("Hamburg Hauptbahnhof Abfahrt")
    -> None. Der Aufrufer prueft zusaetzlich Wetterkontext, Zeitfenster und die Aufloesung durch den Wetterdienst.
    """
    if not isinstance(message, str):
        return None
    text = message.strip()
    if not text or len(text) > 60 or not _FOLLOW_CHARS.match(text):
        return None
    tokens = _PUNCT.sub(" ", text).split()
    lead_seen: set[str] = set()
    while tokens and tokens[0].lower() in _FOLLOW_LEAD:
        lead_seen.add(tokens.pop(0).lower())
    while tokens and tokens[-1].lower() in _FOLLOW_LEAD:
        lead_seen.add(tokens.pop().lower())
    if not 1 <= len(tokens) <= 2:
        return None
    connective = bool(lead_seen & _FOLLOW_CONNECTIVE)
    explicit_in = bool(lead_seen & {"in", "bei"})  # ausdruecklich "in Hamburg" -> Ortsform
    out: list[str] = []
    for tok in tokens:
        if not _FOLLOW_TOKEN.match(tok) or tok.lower() in _FOLLOW_NOT_CITY or tok.lower() in _FOLLOW_LEAD:
            return None
        if tok[:1].isupper():
            out.append(tok)
        elif connective and len(tok) >= 3:
            out.append("-".join(p.capitalize() for p in tok.split("-")))
        else:
            return None
    # Ohne ausdruecklich "in/bei" nur bekannte Orte: sonst waeren Namen, Befehle und Nomen "Orte"
    if not explicit_in and not (_norm_place(" ".join(out)) in _known_places()):
        return None
    city = " ".join(out)
    return city if len(city) <= _MAX_CITY_CHARS else None


@dataclass(frozen=True)
class WeatherContext:
    """Wetter-Vorfrage: Tag (0/1/2) und Antwort-Thema ('general'/'rain'/'snow'; None = nicht per Vorlage beantwortbar)."""

    day_offset: int
    topic: str | None


def find_weather_context(history: list[dict] | None) -> WeatherContext | None:
    """Letzte Nutzerfrage der Sitzung war eine Wetterfrage (davor ggf. nur Ort-Folgefragen), sonst None."""
    from app.services.weather_template import template_topic  # lokal: kein Importzyklus

    hist = [m for m in (history or []) if isinstance(m, dict)]
    for i in range(len(hist) - 1, -1, -1):
        msg = hist[i]
        if msg.get("role") != "user":
            continue
        content = msg.get("content")
        if not isinstance(content, str):
            return None
        if detect_place_followup(content):
            # nur ueberspringen, wenn darauf wirklich eine Wetterantwort kam (enthaelt "Grad"); sonst Kette gerissen
            nxt = hist[i + 1] if i + 1 < len(hist) else None
            if (nxt is None or nxt.get("role") != "assistant"
                    or "grad" not in str(nxt.get("content", "")).lower()):
                return None
            continue
        wi = detect_weather_intent(content)
        if wi is None:
            return None
        return WeatherContext(day_offset=wi.day_offset, topic=template_topic(content))
    return None


def _known_places() -> frozenset[str]:
    global _KNOWN_PLACES
    if not _KNOWN_PLACES:
        _KNOWN_PLACES = frozenset(_norm_place(p) for p in _KNOWN_PLACES_RAW.split())
    return _KNOWN_PLACES


def _norm_place(text: str) -> str:
    t = text.lower().replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss")
    return re.sub(r"[^a-z]", "", t)


# Deutsche Ortsnamen, die wttr englisch aufloest (normalisiert, ohne Umlaute)
_EXONYMS = {
    "koln": "cologne", "nurnberg": "nuremberg", "wien": "vienna", "prag": "prague", "zurich": "zurich",
    "rom": "rome", "mailand": "milan", "venedig": "venice", "florenz": "florence", "neapel": "naples",
    "genf": "geneva", "hannover": "hanover", "braunschweig": "brunswick", "munchen": "munich",
    "lissabon": "lisbon", "kopenhagen": "copenhagen", "warschau": "warsaw", "brussel": "brussels",
    "athen": "athens", "moskau": "moscow", "sevilla": "seville", "turin": "turin", "basel": "basel",
}


def place_matches(requested: str, resolved: str | None) -> bool:
    """Passt das vom Wetterdienst aufgeloeste Gebiet zum genannten Ort? (Schutz vor Ersatzorten bei Unsinn)"""
    if not requested or not resolved:
        return False
    a, b = _norm_place(requested), _norm_place(resolved)
    if not a or not b:
        return False
    a = _EXONYMS.get(a, a)
    if a == b:
        return True
    # ganzes Wort des aufgeloesten Gebiets (Kiel-Gaarden -> "kiel", Bad Ems -> "ems"), nie ein Teilwort (Hallein)
    words = {_norm_place(w) for w in re.split(r"[\s\-,/]+", resolved) if w}
    if len(a) >= 4 and a in words:
        return True
    req_words = {_EXONYMS.get(_norm_place(w), _norm_place(w)) for w in re.split(r"[\s\-,/]+", requested) if w}
    if len(b) >= 4 and b in req_words:
        return True
    return len(a) >= 5 and len(b) >= 5 and difflib.SequenceMatcher(None, a, b).ratio() >= 0.85

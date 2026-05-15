"""twitch_bot_service.py — Donna Twitch-Bot.

Verbindet sich mit dem Twitch-Chat von Mike's Stream und beantwortet
Viewer-Fragen via !donna-Befehl.

Sicherheits-Regeln:
- Kein Brain-Zugriff für Viewer (kein STM, kein LTM, kein Auth)
- Privat-Schutz: Mike, Yannik, Ämi-Li, Birdy_711 → keine privaten Infos
- Donna gibt sich nicht als Bot aus, außer direkt gefragt
- Rate-Limit: 1 Anfrage pro Viewer pro N Sekunden (default 5)
- Antwort-Max-Länge: 400 Zeichen
- Chat → LocalLLM-Filter → LTM Brain-Pipeline (alle 10 Min.)
"""
from __future__ import annotations

import asyncio
import re
import time

import httpx

from app.core.logger import get_logger

# Modul-Level-Import damit class-body-Decorator darauf zugreifen kann
try:
    from twitchio.ext import commands as _twitchio_commands  # type: ignore[import-untyped]
    _TWITCHIO_OK = True
except ImportError:
    _twitchio_commands = None  # type: ignore[assignment]
    _TWITCHIO_OK = False

log = get_logger("service.twitch_bot")

# Ingest alle 10 Minuten
_BRAIN_INGEST_INTERVAL_SEC = 10 * 60

# Private Personen — keine Infos preisgeben
_PRIVATE_NAMES = {"yannik", "ämi-li", "ami-li", "birdy_711", "birdy711", "ämi"}

# Bot-Accounts die keine Trigger auslösen dürfen (Self-Loop-Prevention, DONNA-100)
_IGNORED_BOT_ACCOUNTS = frozenset({
    "donna_bot",
    "nightbot",
    "streamelements",
    "streamlabs",
    "moobot",
    "fossabot",
    "wizebot",
    "coebot",
    "ohbot",
})

# Private Infos über Mike selbst — Wohnort, Adresse, echter Name etc.
_MIKE_PRIVATE_RE = re.compile(
    r'\b('
    # Wohnort / Aufenthaltsort
    r'wohnt|wohnort|wohnung|adresse|wo lebt|wo wohnt|wohnhaft|wohnst|heimatort|'
    r'heimatstadt|herkunft|kommt.*her|stammt.*aus|zuhause|haushalt|'
    r'wo ist er|wo bist|gerade|aufenthaltsort|standort|'
    # Kontakt / Zugangsdaten (discord/instagram/socials sind öffentlich → NICHT hier sperren)
    r'handy|telefon|nummer|email|e-mail|passwort|password|privat.*kanal|'
    # Name / Identität
    r'echter name|bürgerlicher|vollständiger name|familienname|nachname|vorname|'
    r'geburtsdatum|geburtsort|geboren|alter.*genau|'
    # Beziehung / Familie
    r'frau|ehefrau|freundin|freund|partner|partnerin|beziehung|verheiratet|'
    r'single|ledig|girlfriend|boyfriend|wife|eltern|mutter|vater|geschwister|'
    r'bruder|schwester|kinder|kind|familie|verwandte|'
    # Finanzen / Einkommen
    r'verdienst|verdient|gehalt|einkommen|einnahmen|geld|arm|reich|schulden|'
    r'kredit|steuern|einnahme|umsatz|wie viel verdient|'
    # Gesundheit / Körper
    r'krank|gesund|arzt|krankenhaus|medikamente|diagnose|'
    r'gewicht|wiegt|größe|wie groß|bmi|'
    # Alltag / Privatleben
    r'schläft|schlafen|aufwacht|aufstehen|routine|alltag|tagesablauf|'
    r'wann kommt|wann geht|wann ist er|wann streamt er nicht|'
    r'was macht er gerade|was macht er wenn|was macht er heute|'
    r'was hat er heute|was hat er gestern|privatleben|privat'
    r')\b',
    re.IGNORECASE,
)
# Alle Aliase für Mike / den Streamer selbst
_MIKE_SUBJECTS = {"mike", "your-twitch-channel", "streamer", "retner", "rentner"}

# Indirekte Wohnort-Leaks: Wetter/Lokales "bei mike" würde Wohnort verraten
_MIKE_LOCATION_LEAK_RE = re.compile(
    r'\b(wetter|temperatur|grad|regen|kino|kinos|restaurant|restaurants|'
    r'in der n[äa]he|nearby|n[äa]he|um ihn|bei ihm|in seiner n[äa]he|'
    r'in seinem|bei mike|bei abgemiked|wo genau|genaue?r? ort|'
    r'postleitzahl|plz|stadtteil|stadteil|viertel|bezirk|stra[sß]e|'
    r'koordinaten|google maps|maps|adresse|ort\b|stadt\b|region)\b',
    re.IGNORECASE,
)

# Prompt-Injection-Versuche im Twitch-Chat
_TWITCH_INJECTION_RE = re.compile(
    r'(ignore (all |previous |your )?instructions?|forget (your |all )?|'
    r'you are now|act as|pretend (you are|to be)|disregard|override|'
    r'system:|system prompt|new (persona|role|instructions?)|'
    r'jetzt bist du|vergiss (alle? |deine? )?|ignorier|'
    r'deine (neue |wahre )?rolle|du bist jetzt|ab jetzt bist)',
    re.IGNORECASE,
)

# PII-Filter
_PII_PATTERNS: list[tuple[re.Pattern[str], str]] = [
    (re.compile(r'\b\d{4,}\s+\w+\s+(str\.|straße|gasse|weg|allee)\b', re.IGNORECASE), "[Adresse]"),
    (re.compile(r'\b\d{5}\b'), "[PLZ]"),
    (re.compile(r'\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b'), "[Email]"),
    (re.compile(r'\+?[\d\s\-]{10,}'), "[Telefon]"),
]

_FILLER_STARTS_RE = re.compile(
    r"^(Natürlich!?|Gerne!?|Super(?: Frage)?!?|Aber sicher!?|Klar!?|Absolut!?"
    r"|Selbstverständlich!?|Als KI|Als Bot|Ich bin (ein |eine )?Bot)[,!\s]+",
    re.IGNORECASE,
)


def _strip_llm_filler(text: str) -> str:
    """Entfernt LLM-typische Einstiegsfloskeln die trotz Prompt-Verbots auftauchen."""
    stripped = _FILLER_STARTS_RE.sub("", text).strip()
    if not stripped:
        return text  # Fallback: Originaltext wenn alles weggefiltert wurde
    return stripped[:1].upper() + stripped[1:]


_SYSTEM_PROMPT_VIEWER = (
    "Du bist Donna — schon lange dabei im Chat von abgemiked, kennst Mike und die Community gut. "
    "Du bist entspannt, direkt, manchmal etwas frech aber nie gemein. "
    "Du antwortest wie jemand der live mitschaut: kurz, auf den Punkt, manchmal mit einem Augenzwinkern. "
    "Maximal 1-2 kurze Sätze. Kein Markdown. Emojis nur wenn sie wirklich passen. "
    "Wenn du etwas nicht weißt: sag es kurz und ehrlich — kein Herumdrucksen, kein Erfinden. "
    "Über Mike, Yannik, Ämi-Li, Birdy_711: privates bleibt privat. "
    "Du stellst dich nicht vor und erklärst nicht wer du bist wenn nicht gefragt."
)

# Sofort-Antworten für Grüße & Reaktionen — kein LLM nötig
import random as _random

_QUICK_RESPONSES: list[tuple[list[str], list[str]]] = [
    # Begrüßungen
    (["moin", "hi", "hallo", "hey", "sup", "yo", "servus", "nabend", "n8", "moinsen",
      "was geht", "was geht ab", "was läuft", "alles gut", "alles klar", "wie gehts",
      "wie geht", "wie läuft", "tag", "guten tag", "guten morgen", "morgen", "abend",
      "guten abend", "howdy", "ello", "ey", "jo", "joa", "na", "na?", "naa"],
     ["Hey!", "Moin!", "Hi!", "Was geht?", "Na, alles gut?", "Yo!", "Moin moin!"]),
    # Verabschiedungen
    (["gn", "gn8", "nacht", "schlaf", "bye", "cya", "tschüss", "tschau", "bb",
      "bis dann", "bis später", "ciao", "adios", "tschö", "tschöö", "tschüssi"],
     ["Gute Nacht!", "Ciao!", "Bis dann!", "n8!", "Tschüss!"]),
    # Dank
    (["danke", "thx", "ty", "dankeschön", "danke dir", "danke schön", "merci", "dankö"],
     ["Gerne!", "Np!", "Klar doch!", "Immer!"]),
    # Hype / Positiv
    (["gg", "nice", "geil", "pog", "pogchamp", "lets go", "let's go", "letsgo",
      "ez", "easy", "poggers", "hype", "fire", "goated", "based", "w", "absolute w",
      "lit", "dope", "krass", "omg", "wow", "no way"],
     ["GG!", "nice!", "🔥", "let's go!", "Pog!", "ez clap"]),
    # Lachen
    (["lol", "lul", "kek", "haha", "xd", "hahaha", "lmao", "rofl", "💀", "😂", "hehe",
      "hihi", "xdd", "lmfao"],
     ["😄", "lol", "haha", "💀"]),
    # Trauer / F
    (["f", "rip", "f im chat", "sadge", "pepehands", "feelsbadman", "schade", "nein",
      "nooo", "noo", "oh nein"],
     ["F", "RIP 😔", "Sadge", "Schade..."]),
    # Zustimmung / Reaktion
    (["ja", "jap", "yep", "yep!", "genau", "stimmt", "richtig", "korrekt", "true", "facts",
      "ong", "fr", "fr fr", "100", "1", "+1"],
     ["Ja genau!", "Stimmt!", "True!", "100%"]),
    # Überraschung
    (["wtf", "was", "hä", "hä?", "echt", "echt?", "seriously", "no cap", "bruh"],
     ["Bruh 😅", "Wtf lol", "Echt jetzt?", "😂"]),
]

# Frage-Keywords — wenn enthalten → NICHT Quick-Response, braucht LLM
_QUESTION_KEYWORDS = {"wie", "was", "wann", "warum", "weshalb", "wer", "wo", "wohin",
                      "kann", "konntest", "erkläre", "erklär", "gibt", "welche", "welcher",
                      "welches", "wieviel", "wie viel", "hilf", "sag", "zeig", "?"}

# Schedule-Keywords — Fragen nach dem Stream-Plan
_SCHEDULE_KEYWORDS = re.compile(
    r'\b(schedule|streamplan|stream.?plan|plan|wann streamt|wann stream|'
    r'stream.*heute|heute.*stream|streamt.*heute|heute.*streamt|'
    r'wann geht.*stream|stream.*wann|was streamt|was l[äa]uft|'
    r'abend.*stream|stream.*abend|was ist.*heute|wann f[äa]ngt|'
    r'streams?.*(wann|heute|abend|uhrzeit|zeit))\b',
    re.IGNORECASE,
)


def _is_schedule_question(question: str) -> bool:
    """True wenn die Frage den Stream-Plan betrifft."""
    return bool(_SCHEDULE_KEYWORDS.search(question))


_PAST_STREAM_KEYWORDS = [
    "gestern gestreamt", "gestern gestreamed", "gestern gezockt", "gestern gespielt",
    "gestern abend gestreamt", "gestern abend gestreamed", "gestern abend gezockt",
    "hat mike gestreamt", "hat mike gestreamed", "hat mike gezockt", "hat mike gespielt",
    "hat er gestreamt", "hat er gestreamed", "hat er gezockt", "hat er gespielt",
    "hat abgemiked gestreamt", "hat abgemiked gezockt", "hat abgemiked gespielt",
    "was hat mike", "was hat abgemiked", "was hat er gestreamt", "was hat er gespielt",
    "was hat er gezockt", "was hat er gestreamed",
    "letzten stream", "letztes mal gestreamt", "zuletzt gestreamt", "zuletzt gezockt",
    "letzte session", "letzter stream",
    # Wochentage
    "am montag", "am dienstag", "am mittwoch", "am donnerstag", "am freitag",
    "am samstag", "am sonntag",
    "montag gestreamt", "dienstag gestreamt", "mittwoch gestreamt",
    "donnerstag gestreamt", "freitag gestreamt", "samstag gestreamt", "sonntag gestreamt",
    "montag gespielt", "dienstag gespielt", "mittwoch gespielt",
    "donnerstag gespielt", "freitag gespielt", "samstag gespielt", "sonntag gespielt",
]


def _is_past_stream_question(text: str) -> bool:
    """True wenn nach vergangenen Streams gefragt wird (Vergangenheitsform)."""
    t = text.lower()
    return any(kw in t for kw in _PAST_STREAM_KEYWORDS)


# Wochentag-Namen (DE) — Reihenfolge: Montag=0, Sonntag=6 (wie datetime.weekday())
_WEEKDAY_NAMES_DE = [
    "montag", "dienstag", "mittwoch", "donnerstag", "freitag", "samstag", "sonntag",
]

# Regex zum Erkennen eines Wochentags in der Frage
_WEEKDAY_IN_QUESTION_RE = re.compile(
    r'\b(montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag)\b',
    re.IGNORECASE,
)

# Regex zum Extrahieren einer Wochentag-Zeile aus dem schedule_text.
# Matcht von "Wochentag:" bis zum nächsten Wochentag-Keyword oder Zeilenende.
# Lookahead stoppt beim nächsten bekannten Wochentag damit das Muster nicht
# gierig den Rest des einzeiligen Plans frisst.
_SCHEDULE_DAY_LINE_RE = re.compile(
    r'((?:montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag)\s*:'
    r'(?:(?!(?:montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag)\s*:)[\s\S])*)',
    re.IGNORECASE,
)


def _extract_day_from_question(question: str) -> str | None:
    """Gibt den genannten Wochentag (lowercase) zurück oder None."""
    m = _WEEKDAY_IN_QUESTION_RE.search(question)
    return m.group(1).lower() if m else None


# DONNA-42 Bug D: erkennt sowohl `!donna` (Befehl) als auch `@donna` / `@donna_bot`
# (Mention) als Trigger. Twitch-Bot heißt `donna_bot` — der Underscore ist Word-Char,
# also matched `@donna\b` NICHT bei `@donna_bot`. Daher zwei Patterns.
_DONNA_MENTION_RE = re.compile(r"@donna(?:_ki)?\b[:,]?", re.IGNORECASE)

# Prefix-Liste — längster Match zuerst, damit "@donna_bot:" vor "@donna_bot" und
# "@donna_bot" vor "@donna" greift (sonst würde "@donna_bot ..." als "@donna" + "_ki ..."
# erkannt und die Frage wäre "_ki ...").
_DONNA_TRIGGER_PREFIXES = (
    "!donna",
    "@donna_bot:",
    "@donna_bot,",
    "@donna_bot",
    "@donna:",
    "@donna,",
    "@donna",
)


def _extract_donna_question(content: str) -> str | None:
    """Gibt die bereinigte Frage zurück wenn der Bot getriggert werden soll, sonst None.

    Trigger:
    - `!donna ...` (Befehl, am Anfang)
    - `@donna ...` / `@donna: ...` / `@donna, ...` (Mention, am Anfang)
    - `@donna_bot ...` / `@donna_bot: ...` (Twitch-Bot-Account-Mention)
    - `... @donna(_ki) ...` (Mention im Text — z.B. "hey @donna was geht?")
    """
    text = content.strip()
    lower = text.lower()
    # Prefix-Trigger (Reihenfolge: längster zuerst)
    for prefix in _DONNA_TRIGGER_PREFIXES:
        if lower.startswith(prefix):
            return text[len(prefix):].lstrip(" ,:")
    # Mention irgendwo im Text — strippen und Rest zurückgeben
    m = _DONNA_MENTION_RE.search(text)
    if m:
        cleaned = (text[:m.start()] + " " + text[m.end():]).strip()
        return cleaned if cleaned else None
    return None


# DONNA-42 Bug E: Wochentag-Name → konkrete Date auflösen (nächstes Vorkommen).
_WEEKDAY_TO_INDEX = {
    "montag": 0, "dienstag": 1, "mittwoch": 2, "donnerstag": 3,
    "freitag": 4, "samstag": 5, "sonntag": 6,
}


def _resolve_named_day_to_date(
    named_day: str,
    today: "datetime.date | None" = None,
    prefer_future: bool = True,
) -> "datetime.date":
    """Löst 'montag' / 'dienstag' / etc. zu konkreter Date auf.

    Args:
        named_day: Wochentag-Name (lowercase, ohne 'am ').
        today: Referenzdatum (default: heute).
        prefer_future: True → nächstes Vorkommen (inkl. heute);
                       False → letztes Vorkommen (inkl. heute).
    """
    import datetime as _dt
    if today is None:
        today = _dt.date.today()
    target = _WEEKDAY_TO_INDEX[named_day.lower()]
    if prefer_future:
        diff = (target - today.weekday()) % 7
        return today + _dt.timedelta(days=diff)
    diff = (today.weekday() - target) % 7
    return today - _dt.timedelta(days=diff)


def _filter_schedule_for_day(schedule_text: str, day: str, is_past: bool) -> str:
    """
    Filtert schedule_text auf den Eintrag des gesuchten Wochentags.

    DONNA-42 Bug E: Schedule-API liefert nur die aktuelle Woche. Wenn der User
    nach einem Wochentag fragt der NICHT in dieser Woche liegt (z.B. "am Montag"
    am Donnerstag-Abend → meint nächsten Montag, der nicht im Plan steht),
    geben wir explizit zurück dass wir den Plan dafür noch nicht kennen,
    statt fälschlich den letzten Montag zu liefern.

    Args:
        schedule_text: Mehrzeiliger oder einzeiliger Streamplan-Text (aktuelle Woche).
        day: Gesuchter Wochentag (lowercase, z.B. "mittwoch").
        is_past: True = explizite Vergangenheitsfrage ("am Montag GESTREAMT"),
                 False = neutrale/zukünftige Frage ("am Montag", "was streamt mike Mo.").

    Returns:
        Den gefundenen Tages-Eintrag oder eine Negativ-Antwort.
    """
    import datetime

    # Alle Tages-Einträge aus dem Plan extrahieren
    matches = _SCHEDULE_DAY_LINE_RE.findall(schedule_text)
    if not matches:
        # Kein strukturierter Plan — ganzen Text zurückgeben
        return schedule_text

    # Map: Wochentag-Name (lower) → Zeilen-Text
    day_entries: dict[str, str] = {}
    for line in matches:
        line_stripped = line.strip()
        colon_pos = line_stripped.find(":")
        if colon_pos == -1:
            continue
        day_key = line_stripped[:colon_pos].strip().lower()
        if day_key in _WEEKDAY_NAMES_DE:
            day_entries[day_key] = line_stripped

    today = datetime.date.today()
    # Resolve named_day → konkretes Datum (nächstes Vorkommen wenn FUTURE,
    # letztes Vorkommen wenn PAST)
    target_date = _resolve_named_day_to_date(day, today=today, prefer_future=not is_past)
    day_display = day.capitalize()
    date_display = target_date.strftime("%d.%m.")

    # Bei FUTURE-Fragen: prüfen ob das Datum innerhalb der aktuellen Plan-Woche liegt.
    # Plan-Woche beginnt am Montag der Woche in der `today` liegt.
    week_start = today - datetime.timedelta(days=today.weekday())
    week_end = week_start + datetime.timedelta(days=6)
    in_current_plan_week = week_start <= target_date <= week_end

    if not in_current_plan_week and not is_past:
        # Zukunftsfrage außerhalb der Planwoche → ehrlich antworten
        return (
            f"Für {day_display} ({date_display}) habe ich noch keinen Plan — "
            f"schaut auf example.com/plan, wenn er online ist 🎮"
        )

    if day not in day_entries:
        # Kein Eintrag für diesen Tag in der Planwoche
        if is_past:
            return f"Am {day_display} ({date_display}) hat Mike laut Plan nicht gestreamt."
        return f"Am {day_display} ({date_display}) streamt Mike laut Plan nicht."

    return day_entries[day]

# Passive Reaktion: Nur echte Grüße + Abschiede (kein gg/lol/f)
_PASSIVE_GREETING_KEYWORDS: set[str] = {
    "moin", "hi", "hallo", "hey", "sup", "yo", "servus", "nabend", "moinsen",
    "guten morgen", "morgen", "guten abend", "abend", "howdy", "ello",
    "gn", "gn8", "nacht", "bye", "cya", "tschüss", "tschau", "bb",
    "bis dann", "ciao", "adios",
}
_PASSIVE_COOLDOWN_SEC = 90   # Mindestabstand zwischen passiven Reaktionen
_PASSIVE_RESPONSE_CHANCE = 0.30  # 30 % Wahrscheinlichkeit

# Öffentliche Socials — hardcoded Antwort, kein LLM
_SOCIALS_RE = re.compile(
    r'\b(instagram|insta|tiktok|youtube|discord|socials?|social media|'
    r'twitter|x\.com|twitch.*andere|andere.*plattform|links?)\b',
    re.IGNORECASE,
)
_SOCIALS_ANSWER = "Alle Links: example.com/discord (Discord) — Instagram/TikTok/YouTube per !socials im Chat 🎮"


def _get_quick_response(question: str) -> str | None:
    """Instant-Antwort für Grüße, Reaktionen und kurze Chat-Messages ohne LLM."""
    q = question.lower().strip().rstrip("!")
    words = set(q.split())

    # Echte Fragen → immer LLM
    if any(kw in words for kw in _QUESTION_KEYWORDS) or "?" in question:
        return None

    # Keyword-Match
    for keywords, responses in _QUICK_RESPONSES:
        if q in keywords or any(q.startswith(k + " ") or q == k for k in keywords):
            return _random.choice(responses)

    # Catch-all: sehr kurze Messages ohne Frage-Marker → casual reply
    if len(q) <= 12 and not any(c in q for c in ["?", "!"]):
        return _random.choice(["👍", "ok!", "jo", "nice", "😄"])

    return None


def _apply_pii_filter(text: str) -> str:
    for pattern, replacement in _PII_PATTERNS:
        text = pattern.sub(replacement, text)
    return text


def _truncate_for_chat(text: str, max_len: int = 400) -> str:
    if len(text) <= max_len:
        return text
    return text[:max_len - 3] + "..."


def _asks_about_private(question: str) -> bool:
    """True wenn die Frage private Infos oder indirekte Wohnort-Leaks betrifft."""
    q = question.lower()
    # Bekannte private Personen (Yannik, Ämi-Li, Birdy) → immer ablehnen
    if any(name in q for name in _PRIVATE_NAMES):
        return True
    if any(subj in q for subj in _MIKE_SUBJECTS):
        # Direkte private Infos (Adresse, Beziehung, Finanzen, ...)
        if _MIKE_PRIVATE_RE.search(q):
            return True
        # Indirekte Wohnort-Leaks (Wetter bei mike, Kinos bei abgemiked, ...)
        if _MIKE_LOCATION_LEAK_RE.search(q):
            return True
    return False


def _is_injection_attempt(question: str) -> bool:
    """True bei erkannten Prompt-Injection-Versuchen."""
    return bool(_TWITCH_INJECTION_RE.search(question))


class TwitchBotService:
    """Twitch-Chat-Bot der Viewer-Fragen an Donna weiterleitet."""

    def __init__(
        self,
        token: str,
        channel: str,
        bot_name: str,
        donna_api_url: str,
        donna_api_token: str,
        rate_limit_sec: int = 5,
        stream_stm=None,  # DONNA-42 B+: STMService für Per-User-Verlauf
    ) -> None:
        self._token = token
        self._channel = channel.lstrip("#")
        self._bot_name = bot_name
        self._donna_api_url = donna_api_url.rstrip("/")
        self._donna_api_token = donna_api_token
        self._rate_limit_sec = rate_limit_sec
        self._viewer_last_request: dict[str, float] = {}
        self._bot = None
        self._running = False
        self._chat_buffer: list[dict[str, str]] = []  # Brain-Pipeline-Puffer
        self._brain_task: asyncio.Task | None = None  # type: ignore[type-arg]
        self._last_passive_response: float = 0.0  # Cooldown für passive Reaktionen
        self._greeted_viewers: set[str] = set()  # wer wurde diese Session bereits begrüßt
        # DONNA-42 B: Per-User-Memory (Wohnort, Fakten) für Twitch-Chatter
        from app.services.twitch_user_memory import TwitchUserMemory
        self._user_memory = TwitchUserMemory()
        # DONNA-42 B+: Per-User-Verlauf — jede Twitch-Message wird in stream_stm
        # geschrieben mit session_id=f"twitch_{user_login}", damit Donna alle
        # bisherigen Aussagen des Users kennt (nicht nur explizite Wohnort-/Name-/
        # Hobby-Aussagen). Bei jeder !donna/@donna-Anfrage werden die letzten N
        # Messages dieses Users als Kontext mitgeschickt.
        self._stream_stm = stream_stm
        # DONNA-112: aktives Channel-Objekt für proaktiven Chat (stream_live_watcher)
        self._active_channel = None
        log.info("twitch_bot_init", channel=self._channel, bot=bot_name, rate_limit=rate_limit_sec)

    def get_channel(self):
        """Gibt das aktive twitchio-Channel-Objekt zurück.

        DONNA-112: stream_live_watcher.py nutzt dies um proaktiv channel.send()
        aufzurufen ohne die innere DonnaBot-Klasse zu kennen.

        Returns:
            twitchio.Channel-Objekt wenn Bot verbunden + Kanal beigetreten, sonst None.
        """
        return self._active_channel

    async def start(self) -> None:
        """Startet den Twitch-Bot und die Brain-Pipeline (blockiert nicht)."""
        if not self._token or not self._channel:
            log.warning("twitch_bot_disabled", reason="token or channel not configured")
            return
        if not _TWITCHIO_OK or _twitchio_commands is None:
            log.error("twitch_bot_twitchio_missing", hint="pip install twitchio==2.10.0")
            return
        try:
            commands = _twitchio_commands
            service = self

            class DonnaBot(commands.Bot):
                def __init__(self_bot) -> None:
                    super().__init__(
                        token=service._token,
                        prefix="!",
                        initial_channels=[service._channel],
                        # IRC-Tags aktivieren für Reply-Erkennung (reply-parent-user-login)
                        capabilities=["tags", "commands", "membership"],
                    )

                async def event_ready(self_bot) -> None:
                    log.info("twitch_bot_connected", channel=service._channel, nick=self_bot.nick)
                    asyncio.ensure_future(service._warmup_ollama())
                    # DONNA-112: Channel-Objekt für stream_live_watcher setzen
                    try:
                        channels = self_bot.get_channel(service._channel)
                        if channels is not None:
                            service._active_channel = channels
                    except Exception as _ch_err:
                        log.debug("twitch_channel_ref_failed", error=str(_ch_err))

                async def event_error(self_bot, error: Exception, data: str = "") -> None:
                    log.error("twitch_bot_error", error=str(error))

                async def event_message(self_bot, message: object) -> None:
                    if getattr(message, "echo", False):
                        return
                    content: str = getattr(message, "content", "") or ""
                    author_name: str = getattr(getattr(message, "author", None), "name", "?")
                    # DONNA-100: Nachrichten von donna_bot selbst oder bekannten Bots ignorieren
                    if author_name.lower() in _IGNORED_BOT_ACCOUNTS:
                        return
                    channel_obj = getattr(message, "channel", None)
                    # DONNA-112: Channel-Objekt für stream_live_watcher aktuell halten
                    if channel_obj is not None and service._active_channel is None:
                        service._active_channel = channel_obj

                    # DONNA-42 B+: Jede non-echo Twitch-Message in stream_stm ablegen
                    # mit session_id=f"twitch_{user_login}" → Donna kennt den vollen
                    # Verlauf jedes Users. Auch Messages ohne Bot-Trigger werden
                    # gespeichert, damit kontextuelle Antworten möglich sind
                    # ("vorhin hast du gesagt du wohnst in Stuttgart").
                    if service._stream_stm is not None and author_name and content.strip():
                        try:
                            await service._stream_stm.add_message(
                                session_id=f"twitch_{author_name.lower()}",
                                role="user",
                                content=content,
                            )
                        except Exception as _stm_err:
                            log.warning("twitch_stm_write_failed", viewer=author_name, error=str(_stm_err))

                    # Reply-Tags auslesen (twitchio legt Tags in message.tags)
                    tags: dict = getattr(message, "tags", {}) or {}
                    reply_parent_user: str = tags.get("reply-parent-user-login", "") or ""
                    is_reply_to_donna = reply_parent_user.lower() == service._bot_name.lower()

                    # DONNA-42 Bug D: !donna ODER @donna-Mention triggert Bot
                    donna_question = _extract_donna_question(content)
                    if donna_question is not None:
                        # Forward mit normalisiertem !donna-Prefix damit nachfolgende
                        # Logik in _handle_donna_message unverändert bleibt
                        normalized = f"!donna {donna_question}" if donna_question else "!donna"
                        await service._handle_donna_message(normalized, author_name, message)
                    elif is_reply_to_donna and not content.startswith("!"):
                        # Jemand antwortet direkt auf Donnas Nachricht → reagieren
                        await service._handle_donna_message(
                            f"!donna {content}", author_name, message
                        )
                    elif content.strip().lower() == "!help":
                        if channel_obj:
                            await channel_obj.send(f"@{author_name} !donna [deine Frage] — ich antworte 🎮")
                    elif not content.startswith("!"):
                        # Nicht-Command-Messages in Brain-Puffer
                        service._chat_buffer.append({"author": author_name, "content": content})
                        if len(service._chat_buffer) > 300:
                            service._chat_buffer = service._chat_buffer[-300:]

                        # Passive Reaktion auf Grüße (ohne !donna) — zurückhaltend
                        # Pro Viewer nur EINMAL pro Session begrüßen + globaler Cooldown
                        q_lower = content.lower().strip().rstrip("!")
                        _now = time.time()
                        viewer_key = author_name.lower()
                        if (
                            q_lower in _PASSIVE_GREETING_KEYWORDS
                            and viewer_key not in service._greeted_viewers
                            and (_now - service._last_passive_response) >= _PASSIVE_COOLDOWN_SEC
                            and _random.random() < _PASSIVE_RESPONSE_CHANCE
                        ):
                            quick = _get_quick_response(content)
                            if quick:
                                service._last_passive_response = _now
                                service._greeted_viewers.add(viewer_key)
                                if channel_obj:
                                    await channel_obj.send(f"@{author_name} {quick}")

            self._bot = DonnaBot()
            self._running = True
            asyncio.create_task(self._bot.start())
            self._brain_task = asyncio.create_task(self._brain_loop())
            log.info("twitch_bot_started")
        except Exception as e:  # noqa: BLE001
            log.error("twitch_bot_start_failed", error=str(e))

    async def send_message(self, text: str) -> None:
        """Sendet eine Nachricht in den verbundenen Twitch-Channel (DONNA-201).

        Wird vom Redis-Subscriber genutzt, um Bot-Antworten auf Chat-Nachrichten
        zurückzuschreiben. Nutzt das beim Connect gesetzte `_active_channel`.
        """
        channel = getattr(self, "_active_channel", None)
        if channel is None:
            log.warning("twitch_send_message_no_channel")
            return
        try:
            await channel.send(text[:480])  # Twitch IRC Limit ~500 Zeichen
        except Exception as e:  # noqa: BLE001
            log.warning("twitch_send_message_failed", error=str(e))

    async def stop(self) -> None:
        self._running = False
        if self._brain_task:
            self._brain_task.cancel()
        if self._bot:
            try:
                await self._bot.close()
            except Exception:
                pass
        log.info("twitch_bot_stopped")

    # ── Brain-Pipeline ─────────────────────────────────────────────────────

    async def _brain_loop(self) -> None:
        """Alle 10 Minuten: Chat-Puffer → LocalLLM-Filter → LTM."""
        while self._running:
            await asyncio.sleep(_BRAIN_INGEST_INTERVAL_SEC)
            if self._chat_buffer:
                await self._ingest_chat_to_brain()

    async def _ingest_chat_to_brain(self) -> None:
        messages = list(self._chat_buffer)
        self._chat_buffer.clear()
        try:
            async with httpx.AsyncClient(timeout=45.0) as client:
                resp = await client.post(
                    f"{self._donna_api_url}/twitch/brain-ingest",
                    json={"messages": messages},
                    headers={"Authorization": f"Bearer {self._donna_api_token}"},
                )
                data = resp.json()
                if data.get("stored"):
                    log.info("twitch_brain_stored", summary=str(data.get("summary", ""))[:80])
                else:
                    log.debug("twitch_brain_skipped", reason=data.get("reason", ""))
        except Exception as e:  # noqa: BLE001
            log.error("twitch_brain_ingest_failed", error=str(e))
            # Nachrichten zurücklegen (max. 100 davon)
            self._chat_buffer = messages[-100:] + self._chat_buffer

    # ── Command Handler ────────────────────────────────────────────────────

    async def _handle_donna_message(
        self, content: str, viewer_name: str, message: object
    ) -> None:
        """Verarbeitet eine !donna-Anfrage. Antwortet als nativer Twitch-Reply."""
        channel = getattr(message, "channel", None)

        async def send(text: str) -> None:
            """Sendet @-tagged Nachricht an den Viewer."""
            if channel:
                await channel.send(f"@{viewer_name} {text}")  # type: ignore[union-attr]
            else:
                log.error("twitch_send_no_channel", viewer=viewer_name)

        # Rate-Limit
        now = time.time()
        last = self._viewer_last_request.get(viewer_name, 0.0)
        remaining = self._rate_limit_sec - (now - last)
        if remaining > 0:
            await send(f"Noch {int(remaining) + 1}s warten.")
            return
        self._viewer_last_request[viewer_name] = now

        # Frage extrahieren
        question = re.sub(r'^!donna\s*', '', content, flags=re.IGNORECASE).strip()
        if not question:
            await send("Stell mir eine Frage: !donna [Frage] 🎮")
            return

        if len(question) < 2 or len(question) > 250:
            await send("Frage bitte zwischen 2 und 250 Zeichen.")
            return

        # Prompt-Injection-Schutz — vor allem anderen
        if _is_injection_attempt(question):
            log.warning("twitch_injection_attempt", viewer=viewer_name, question=question[:80])
            await send("Das funktioniert hier nicht.")
            return

        # Öffentliche Socials — hardcoded, kein LLM, kein Over-blocking
        if _SOCIALS_RE.search(question):
            await send(_SOCIALS_ANSWER)
            return

        # Privatpersonen-Schutz + indirekter Wohnort-Schutz — direkt ohne LLM ablehnen
        if _asks_about_private(question):
            await send("Das ist privat — darüber rede ich nicht.")
            return

        # Quick-Response für einfache Grüße (sofort, kein LLM nötig)
        quick = _get_quick_response(question)
        if quick:
            self._user_memory.touch(viewer_name)
            await send(quick)
            return

        # DONNA-42 B: Per-User-Memory updaten + bei expliziter Aussage bestätigen
        from app.services.twitch_user_memory import (
            extract_residence_location,
            extract_query_location,
            extract_name,
            extract_hobby,
        )
        self._user_memory.touch(viewer_name)

        # Sammle alle erkannten Updates aus der einen Nachricht
        updates: list[str] = []
        residence = extract_residence_location(question)
        if residence:
            self._user_memory.set_location(viewer_name, residence)
            updates.append(f"du wohnst in {residence}")
        name = extract_name(question)
        if name:
            self._user_memory.set_fact(viewer_name, "name", name)
            updates.append(f"dein Name ist {name}")
        hobby = extract_hobby(question)
        if hobby:
            self._user_memory.set_fact(viewer_name, "hobby", hobby)
            updates.append(f"dein Hobby ist {hobby}")

        if updates:
            # Kurze Bestätigung — User weiß dass Donna's Memory aktualisiert wurde
            await send(f"Gemerkt: {', '.join(updates)}.")
            return

        # Schedule-Fragen — your-donna-instance.example.com/api/schedule (primär, kein Twitch Helix)
        _is_past_q = _is_past_stream_question(question)
        if _is_schedule_question(question) or _is_past_q:
            from app.services.schedule_service import fetch_schedule, fetch_next_stream, fetch_last_stream
            from app.services.twitch_vod_service import fetch_last_vod
            is_today_question = (
                "heute" in question.lower()
                or "abend" in question.lower()
                or "jetzt" in question.lower()
            )
            named_day = _extract_day_from_question(question)
            try:
                if _is_past_q and not named_day:
                    # DONNA-24: Vergangenheitsfrage → echter Twitch-Helix-VOD (primär)
                    # Fallback auf schedule_service.fetch_last_stream() wenn Helix leer/offline
                    last_text = await fetch_last_vod()
                    if not last_text:
                        last_text = await fetch_last_stream()
                    await send(last_text or "Das konnte ich gerade nicht nachschauen.")
                elif not is_today_question and not _is_past_q and not named_day:
                    # Allgemeine Zukunftsfrage ("wann streamt mike wieder") →
                    # nur den nächsten Slot kompakt (überspringt laufende Session)
                    next_text = await fetch_next_stream()
                    await send(next_text or "Kein weiterer Stream diese Woche geplant.")
                else:
                    # DONNA-26: wenn ein konkreter Wochentag genannt wird (z.B. "am mittwoch
                    # abend"), IMMER den vollen Wochenplan laden (for_today=False) damit
                    # _filter_schedule_for_day den richtigen Tag extrahieren kann.
                    # for_today=True nur für generische "heute"-Fragen ohne named_day.
                    schedule_text = await fetch_schedule(
                        for_today=(is_today_question and not named_day)
                    )
                    if schedule_text:
                        if named_day:
                            filtered = _filter_schedule_for_day(
                                schedule_text, named_day, is_past=_is_past_q
                            )
                            await send(_truncate_for_chat(filtered))
                        else:
                            await send(_truncate_for_chat(schedule_text))
                    else:
                        await send("Ich konnte den Stream-Plan gerade nicht laden. Schau auf your-donna-instance.example.com/schedule nach.")
            except Exception as e:  # noqa: BLE001
                log.error("twitch_schedule_error", error=str(e), viewer=viewer_name)
                await send("Kurz nicht erreichbar, versuch's gleich nochmal.")
            return

        # DONNA-42 B: User-Kontext aus Memory bauen (nur eigener Scope, nie cross-user)
        user_ctx = self._user_memory.context_string(viewer_name)
        # Wetter-Frage ohne explizite Stadt + gespeicherter Wohnort → Hint geben
        query_loc = extract_query_location(question)
        weather_hint = ""
        if not query_loc and user_ctx:
            mem = self._user_memory.get(viewer_name) or {}
            saved_loc = mem.get("location")
            is_weather_q = any(
                kw in question.lower()
                for kw in ("wetter", "regen", "temperatur", "schnee", "sonne")
            )
            if saved_loc and is_weather_q:
                weather_hint = (
                    f" Wenn der User nach Wetter fragt ohne Stadt zu nennen, "
                    f"nimm '{saved_loc}' als seinen Wohnort."
                )

        # DONNA-42 B+: Letzte 15 Messages des Users aus stream_stm als Verlaufs-Kontext.
        # Damit kennt Donna alles was der User in der Session gesagt hat (Name, Hobby,
        # Stadt, Lieblingsspiel, etc.) — auch wenn keine harten Pattern matched haben.
        history_block = ""
        if self._stream_stm is not None:
            try:
                history = await self._stream_stm.get_session_messages(
                    f"twitch_{viewer_name.lower()}", max_messages=15,
                )
                if history:
                    # Nur User-Messages, ältere zuerst, kompakt
                    user_msgs = [
                        h["content"][:160] for h in history
                        if h.get("role") == "user" and h.get("content")
                    ][-15:]
                    if user_msgs:
                        joined = " · ".join(user_msgs)
                        history_block = f" [Vorherige Nachrichten von {viewer_name}: {joined}]"
            except Exception as _hist_err:
                log.warning("twitch_history_load_failed", viewer=viewer_name, error=str(_hist_err))

        donna_extra_parts = [p for p in (user_ctx, weather_hint, history_block) if p]
        donna_extra = " ".join(donna_extra_parts) if donna_extra_parts else None

        # Donna API
        try:
            answer = await self._ask_donna(question, viewer_name, extra_context=donna_extra)
            # DONNA-213: leere Antwort = bewusst verworfen (Caps-Spam/Echo) → nichts senden
            if not answer or not answer.strip():
                log.info("twitch_donna_empty_response_skipped", viewer=viewer_name)
            else:
                safe = _strip_llm_filler(_apply_pii_filter(answer))
                await send(_truncate_for_chat(safe))
        except Exception as e:  # noqa: BLE001
            log.error("twitch_donna_api_error", error=str(e), viewer=viewer_name)
            await send("Kurz nicht erreichbar, versuch's gleich nochmal.")

    async def _warmup_ollama(self) -> None:
        """Wärmt Ollama nach Bot-Start im Hintergrund vor (verhindert Cold-Start-Timeout)."""
        try:
            async with httpx.AsyncClient(timeout=120.0) as client:
                await client.post(
                    f"{self._donna_api_url}/chat/twitch",
                    json={"message": "hallo", "stream": False, "session_id": "_warmup"},
                    headers={
                        "Authorization": f"Bearer {self._donna_api_token}",
                        "Content-Type": "application/json",
                    },
                )
            log.info("twitch_ollama_warmup_done")
        except Exception as e:  # noqa: BLE001
            log.warning("twitch_ollama_warmup_failed", error=str(e))

    async def _ask_donna(
        self,
        question: str,
        viewer_name: str,
        extra_context: str | None = None,
    ) -> str:
        # Timeout 55s — Ollama auf CPU braucht bis zu 30s für eine Antwort
        payload: dict = {
            "message": question,
            "stream": False,
            "session_id": f"twitch_{viewer_name}",
        }
        if extra_context:
            payload["extra_context"] = extra_context
        async with httpx.AsyncClient(timeout=55.0) as client:
            resp = await client.post(
                f"{self._donna_api_url}/chat/twitch",
                json=payload,
                headers={
                    "Authorization": f"Bearer {self._donna_api_token}",
                    "Content-Type": "application/json",
                },
            )
            if resp.status_code != 200:
                raise RuntimeError(f"API error {resp.status_code}")
            # DONNA-213: response kann None sein (Caps-Spam/Echo verworfen) →
            # leerer String signalisiert dem Aufrufer "nichts senden".
            return resp.json().get("response") or ""

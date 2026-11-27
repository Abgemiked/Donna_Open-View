"""#155/#154: Antwort zu einer Aktionskarte — deterministische Absicherung.

Das Modell folgt gern Mustern ("Die Karte liegt bereit, tippe sie an"). Der Prompt allein hat das nicht
zuverlaessig verhindert. Hier: erkennt Karten-/Bedien-Gerede in der Antwort und ersetzt sie durch EINEN kurzen
Satz aus den Kartendaten. Reine Funktionen, keine Ein-/Ausgabe, nichts wird ausgefuehrt.
"""
from __future__ import annotations

import re
from datetime import datetime
from zoneinfo import ZoneInfo

_BERLIN = ZoneInfo("Europe/Berlin")

# Bedien-/Karten-Gerede: "Karte"/"Kärtchen", antippen/anklicken/draufdruecken, Button/Taste/Chip als UI-Wort
_UI_TALK = re.compile(
    r"\b(karte[n]?|kärtchen|antipp\w*|anklick\w*|anklik\w*|antap\w*|"
    r"tipp\w*\s+(?:sie|ihn|es|drauf|darauf|einfach|kurz|bitte)|"
    r"(?:button|chip|schaltfl\w+)\w*)\b",
    re.IGNORECASE,
)

_WEEKDAYS = ["Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"]
_CARDLESS = {"device_action", "save_memory", "set_stream_title", "set_stream_game"}


def mentions_ui(text: str) -> bool:
    """True, wenn die Antwort von der Karte selbst / vom Antippen redet."""
    return bool(text) and _UI_TALK.search(text) is not None


def holds_card(actions: list[dict]) -> bool:
    """Gibt es in dieser Antwort mindestens eine echte Bestaetigungskarte (kein Geraete-Direktbefehl)?"""
    return any(isinstance(a, dict) and a.get("type") and a.get("type") not in _CARDLESS for a in actions or [])


def _when(iso: object, now: datetime) -> str | None:
    if not isinstance(iso, str) or not iso.strip():
        return None
    try:
        dt = datetime.fromisoformat(iso.strip().replace("Z", "+00:00"))
    except ValueError:
        return None
    dt = dt.astimezone(_BERLIN) if dt.tzinfo else dt.replace(tzinfo=_BERLIN)
    local_now = now.astimezone(_BERLIN)
    days = (dt.date() - local_now.date()).days
    if days == 0:
        day = "Heute"
    elif days == 1:
        day = "Morgen"
    elif days == 2:
        day = "Übermorgen"
    elif 2 < days < 7:
        day = _WEEKDAYS[dt.weekday()]
    else:
        day = f"{_WEEKDAYS[dt.weekday()][:2]}. {dt.day}.{dt.month}."
    clock = f"{dt.hour} Uhr" if dt.minute == 0 else f"{dt.hour}:{dt.minute:02d} Uhr"
    return f"{day} {clock}"


def _one(a: dict, now: datetime) -> str | None:
    t = a.get("type")
    if t == "create_event":
        title = str(a.get("title") or "Termin").strip()
        w = _when(a.get("start"), now)
        return f"{w}: {title}. Eintragen?" if w else f"{title}. Eintragen?"
    if t == "set_timer":
        m = a.get("minutes")
        return f"Timer {m} Minuten. Starten?" if m else "Timer starten?"
    if t == "set_alarm":
        tm = str(a.get("time") or "").strip()
        return f"Wecker {tm} Uhr. Stellen?" if tm else "Wecker stellen?"
    if t in ("navigate", "tesla_navigate"):
        dest = str(a.get("destination") or "").strip()
        return f"Navigation zu {dest}. Starten?" if dest else "Navigation starten?"
    if t in ("call", "sms", "whatsapp"):
        who = str(a.get("name") or a.get("number") or "").strip()
        verb = {"call": "Anruf bei", "sms": "SMS an", "whatsapp": "WhatsApp an"}[t]
        return f"{verb} {who}. Los?" if who else None
    if t == "play_music":
        q = str(a.get("query") or "").strip()
        return f"{q} abspielen?" if q else "Musik abspielen?"
    if t == "open_url":
        return "Link öffnen?"
    if t == "note":
        return "Notiz speichern?"
    if t == "calendar_change":   # #147 H: ehrlich, ohne Termindaten; Loeschen sagt, dass es nicht zurueckgeht
        return {"move": "Termin verschieben?", "rename": "Termin umbenennen?",
                "delete": "Termin löschen? Das lässt sich nicht rückgängig machen."}.get(a.get("op"))
    if t == "email_draft":   # #147 Rest: ehrlich, ohne Mailinhalt; gesendet wird nie von Donna
        return "E-Mail-Entwurf in der Mail-App öffnen?"
    return None


def card_reply(actions: list[dict], now: datetime) -> str | None:
    """Kurzer Satz aus den Kartendaten; None, wenn keine Karte eine Vorlage hat."""
    parts = [p for p in (_one(a, now) for a in actions or [] if isinstance(a, dict)) if p]
    return " ".join(parts) if parts else None


def rewrite_card_reply(text: str, actions: list[dict], now: datetime) -> str:
    """Spricht die Antwort von der Karte/vom Antippen, wird sie durch die Vorlage ersetzt; sonst unveraendert."""
    if not holds_card(actions) or not mentions_ui(text):
        return text
    return card_reply(actions, now) or text


# #208: Geraete-Direktbefehle (device_action) sind nur ANGESTOSSEN; ob das Handy sie ausfuehrt, weiss der Server erst
# spaeter (Rueckkanal). Das Modell behauptet trotz Anweisung gern "Timer laeuft" — dann ersetzt ein fester Satz den Text.
_DONE_CLAIM = re.compile(
    r"(?<!\w)(?:l(?:ä|ae)uft|gestellt|gesetzt|gestartet|startet|erledigt|geklappt|eingestellt|aktiviert|deaktiviert|"
    r"angepasst|ausgef(?:ü|ue)hrt|gesperrt|ist\s+jetzt\s+(?:an|aus))(?!\w)",
    re.IGNORECASE,
)
# schon ehrliche Formulierungen: Frage, Vorbehalt ("ob es laeuft"), Folge ("dann laeuft er"), "stoße ... an"
_HONEST = re.compile(r"\?|(?<!\w)(?:ob|dann|sobald|wenn)(?!\w)|(?<!\w)sto(?:ß|ss)e\w*|angesto(?:ß|ss)en", re.IGNORECASE)
# Ehrliche Fehler-/Ablehnungsantworten bleiben unangetastet ("hat nicht geklappt", "konnte ich nicht starten")
_NEGATION = re.compile(r"(?<!\w)(?:nicht|kein\w*|nie|leider|fehlgeschlagen|konnte|kann)(?!\w)", re.IGNORECASE)
_DEVICE_LABEL = {
    "set_timer": "den Timer", "set_alarm": "den Wecker", "flashlight": "die Taschenlampe", "volume": "die Lautstärke",
    "open_app": "die App", "open_settings": "die Einstellungen", "set_brightness": "die Helligkeit",
    "dnd": "Nicht stören", "lock_screen": "das Sperren", "screenshot": "den Screenshot", "open_camera": "die Kamera",
}


def holds_device_ack(actions: list[dict]) -> bool:
    """Gibt es einen sofort angestossenen Geraete-Befehl, dessen Ergebnis noch niemand kennt?"""
    return any(isinstance(a, dict) and a.get("type") == "device_action" for a in actions or [])


def device_reply_guard(text: str, actions: list[dict], hc_outcomes: list | None) -> str:
    """Verdrahtung fuer chat(): Smart-Home-Erfolg nur, wenn es Ergebnisse gibt und ALLE ok sind."""
    home_ok = bool(hc_outcomes) and all(isinstance(o, dict) and o.get("ok") for o in hc_outcomes)
    return rewrite_device_reply(text, actions, home_ok)


def rewrite_device_reply(text: str, actions: list[dict], home_ok: bool = False) -> str:
    """Behauptet die Antwort Vollzug ("Timer laeuft") zu einer nur angestossenen Geraete-Aktion, ersetzt ein fester
    Satz sie. Smart-Home-Erfolg (Server hat selbst geschaltet) bleibt benannt. Sonst unveraendert."""
    if (not holds_device_ack(actions) or not text or _DONE_CLAIM.search(text) is None
            or _NEGATION.search(text) is not None or _HONEST.search(text) is not None):
        return text
    labels: list[str] = []
    for a in actions:
        if isinstance(a, dict) and a.get("type") == "device_action":
            lab = _DEVICE_LABEL.get(a.get("action"), "die Aktion")
            if lab not in labels:
                labels.append(lab)
    what = " und ".join(labels)
    head = "Das Schalten ist erledigt. " if home_ok else ""
    return f"{head}Ich stoße {what} am Handy an — ob es geklappt hat, meldet das Handy selbst."

#!/usr/bin/env python3
"""Re-authorize Google Calendar OAuth2 — holt einen frischen Refresh-Token.

Dieses Script LOKAL ausführen (nicht im Docker-Container), weil ein Browser
für die Google-Autorisierung benötigt wird.

Der Token wird NICHT auf der Konsole ausgegeben, sondern in eine Datei mit engen Rechten (0600)
geschrieben — im Format der Token-Ablage des Servers (app/services/calendar_token_store.py).

Usage (aus dem Repo-Root; Abhängigkeiten lokal: pip install google-auth-oauthlib structlog):
    set GOOGLE_CLIENT_ID=<deine_client_id>
    set GOOGLE_CLIENT_SECRET=<dein_client_secret>
    python backend/reauth_google_calendar.py [--out PFAD] [--person ID] [--overwrite]

Standardort der Datei: <Benutzerordner>/.donna/calendar_token_store.json (nicht im Repo).
Eine vorhandene Datei wird nicht überschrieben, außer mit --overwrite.

Client-ID und Secret findest du in der .env auf Hetzner:
    ssh your-server "grep GOOGLE_ /opt/donna/.env"

Danach: Datei auf den Server in den dort eingerichteten Ablagepfad (CALENDAR_TOKEN_STORE_PATH) legen,
dann POST /calendar/auth/reload (Admin-Token) aufrufen — ohne Neustart — und die lokale Kopie löschen.
"""
from __future__ import annotations

import argparse
import getpass
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))  # "app" auch beim Start aus dem Repo-Root finden

from app.services import calendar_token_store  # noqa: E402

DEFAULT_PERSON = calendar_token_store.DEFAULT_PERSON
_IS_WINDOWS = os.name == "nt"  # eigene Variable, damit Tests die Windows-Zeile prüfen können


def default_output_path() -> str:
    """Standardort: Benutzerordner, bewusst außerhalb des Repos."""
    return str(Path.home() / ".donna" / "calendar_token_store.json")


def save_token_file(
    path: str, person_id: str, client_id: str, client_secret: str, refresh_token: str,
    overwrite: bool = False,
) -> str:
    """Schreibt die Ablage-Datei (ohne Google, ohne Netz testbar). Wirft FileExistsError/OSError."""
    calendar_token_store.write_entry(path, person_id, client_id, client_secret, refresh_token,
                                     overwrite=overwrite)
    return os.path.abspath(path)


def _parse_args(argv: list[str] | None) -> argparse.Namespace:
    ap = argparse.ArgumentParser(description="Google-Kalender neu autorisieren (Token in Datei, nicht auf die Konsole).")
    ap.add_argument("--out", default=None, help=f"Zieldatei (Standard: {default_output_path()})")
    ap.add_argument("--person", default=DEFAULT_PERSON, help=f"person_id des Eintrags (Standard: Inhaber, '{DEFAULT_PERSON}')")
    ap.add_argument("--overwrite", action="store_true", help="vorhandene Datei ersetzen (sonst Abbruch). Achtung: ist die alte Datei unlesbar/defekt, gehen darin enthaltene andere Einträge verloren")
    return ap.parse_args(argv)


def main(argv: list[str] | None = None) -> None:
    args = _parse_args(argv)
    out_path = args.out or default_output_path()

    # Vor dem Browser-Ablauf prüfen: sonst wäre die Autorisierung umsonst gewesen.
    if os.path.exists(out_path) and not args.overwrite:
        print(f"ERROR: {out_path} existiert bereits und wird nicht überschrieben.")
        print("  Andere Datei mit --out wählen oder bewusst ersetzen mit --overwrite.")
        sys.exit(1)

    client_id = os.environ.get("GOOGLE_CLIENT_ID")
    client_secret = os.environ.get("GOOGLE_CLIENT_SECRET")

    if not client_id or not client_secret:
        print("ERROR: GOOGLE_CLIENT_ID und GOOGLE_CLIENT_SECRET müssen gesetzt sein.")
        print()
        print("Werte aus Hetzner holen:")
        print('  ssh your-server "grep GOOGLE_ /opt/donna/.env"')
        print()
        print("Dann setzen (Windows):")
        print("  set GOOGLE_CLIENT_ID=<wert>")
        print("  set GOOGLE_CLIENT_SECRET=<wert>")
        print("  python backend/reauth_google_calendar.py")
        sys.exit(1)

    try:
        from google_auth_oauthlib.flow import Flow  # type: ignore[import-untyped]
    except ImportError:
        print("ERROR: google-auth-oauthlib nicht installiert.")
        print("  pip install google-auth-oauthlib")
        sys.exit(1)

    client_config = {
        "installed": {
            "client_id": client_id,
            "client_secret": client_secret,
            "auth_uri": "https://accounts.google.com/o/oauth2/auth",
            "token_uri": "https://oauth2.googleapis.com/token",
            "redirect_uris": ["http://localhost"],
        }
    }

    flow = Flow.from_client_config(
        client_config,
        scopes=["https://www.googleapis.com/auth/calendar.readonly"],
        redirect_uri="http://localhost",
    )

    auth_url, _ = flow.authorization_url(
        access_type="offline",
        prompt="consent",
        include_granted_scopes="true",
    )

    print()
    print("=" * 60)
    print("  Google Calendar Re-Autorisierung")
    print("=" * 60)
    print()
    print("Schritt 1 — Öffne diese URL im Browser:")
    print()
    print(f"  {auth_url}")
    print()
    print("Schritt 2 — Melde dich mit dem Google-Konto an, dessen Kalender Donna lesen soll.")
    print()
    print("Schritt 3 — Klicke 'Zulassen' (Allow).")
    print()
    print("Schritt 4 — Du wirst zu http://localhost weitergeleitet.")
    print("  Die Seite lädt NICHT — das ist normal.")
    print("  Kopiere die gesamte URL aus der Adressleiste (http://localhost?code=...).")
    print()

    # getpass: die Redirect-Adresse enthält den Auth-Code und soll nicht im Terminal stehen bleiben (keine Anzeige)
    redirect_response = getpass.getpass(
        "Redirect-URL hier einfügen (Eingabe bleibt unsichtbar; beginnt mit http://localhost?code=): "
    ).strip()

    if not redirect_response:
        print("ERROR: Keine URL eingegeben.")
        sys.exit(1)

    # Nur fuer diesen lokalen Ablauf mit Redirect http://localhost noetig: oauthlib
    # verweigert sonst http (insecure_transport). Niemals auf dem Server setzen.
    os.environ.setdefault("OAUTHLIB_INSECURE_TRANSPORT", "1")

    try:
        flow.fetch_token(authorization_response=redirect_response)
    except Exception as exc:
        # Hier nur der Fehlertyp: Exception-Texte der OAuth-Bibliothek können Code/Token enthalten.
        print(f"\nERROR beim Token-Fetch ({type(exc).__name__}).")
        print()
        print("Mögliche Ursachen:")
        print("  - Falsche URL kopiert (muss mit http://localhost?code= beginnen)")
        print("  - Der Code wurde schon benutzt oder ist abgelaufen — Ablauf neu starten")
        print("  - OAuth-Client-Typ ist 'Web application' statt 'Desktop app'")
        print("    → In der Google Cloud Console unter APIs & Dienste > Anmeldedaten ändern")
        print("  - 'insecure_transport': Das Script setzt OAUTHLIB_INSECURE_TRANSPORT für den lokalen Ablauf selbst.")
        print("  - http://localhost muss als Redirect-URI in der Google Cloud Console eingetragen sein")
        sys.exit(1)

    creds = flow.credentials
    if not creds.refresh_token:
        print("\nWARNING: Kein Refresh-Token erhalten.")
        print("Versuche es erneut — stelle sicher dass prompt=consent gesetzt ist.")
        sys.exit(1)

    try:
        written = save_token_file(out_path, args.person, client_id, client_secret,
                                  creds.refresh_token, overwrite=args.overwrite)
    except FileExistsError:
        print(f"\nERROR: {out_path} wurde inzwischen angelegt und wird nicht überschrieben.")
        sys.exit(1)
    except OSError as exc:
        print(f"\nERROR: Datei konnte nicht geschrieben werden ({type(exc).__name__}).")
        print("Der neue Token ist damit VERLOREN (er wurde nirgends gespeichert). Zielpfad prüfen")
        print("und den gesamten Ablauf von vorn starten.")
        sys.exit(1)

    print()
    print("=" * 60)
    print("  Erfolg! Der neue Zugang wurde in eine Datei geschrieben (kein Token auf der Konsole).")
    print("=" * 60)
    print()
    print(f"Datei: {written}")
    if _IS_WINDOWS:
        print("Hinweis: Unter Windows gibt es keine Unix-Dateirechte (0600) — die Datei ist nur über die")
        print("Rechte des Benutzerordners geschützt. Auf dem Server MUSS sie 0600 haben.")
    print()
    print("Nächste Schritte:")
    print("  1. Datei auf den Server in den Ablagepfad kopieren (Setting CALENDAR_TOKEN_STORE_PATH,")
    print("     muss außerhalb von /data/appdata und dem Vault liegen, Rechte 0600, lesbar für den API-Benutzer).")
    print("  2. Ohne Neustart neu laden: POST /calendar/auth/reload mit dem Admin-Token,")
    print("     danach GET /calendar/auth/status prüfen (auth_failed muss false sein).")
    print("  3. Die lokale Kopie danach LÖSCHEN — sie enthält Client-Secret und Refresh-Token.")
    print()
    print("Alter Weg (nur wenn die Token-Ablage auf dem Server AUS ist, CALENDAR_TOKEN_STORE_PATH leer):")
    print("Token aus der Datei von Hand in die Server-.env als GOOGLE_REFRESH_TOKEN eintragen und neu starten:")
    print("cd /opt/donna && docker compose up -d api. Vorbehalt: Ist die Ablage eingerichtet, hat deren")
    print("Eintrag Vorrang vor der .env — ein neuer Token nur in der .env bliebe dann wirkungslos.")
    print()
    print("Hinweis: Steht der OAuth-Zustimmungsbildschirm in der Google Cloud Console auf 'Testing',")
    print("läuft der Refresh-Token nach 7 Tagen erneut ab. Dauerhaft hilft nur der Status")
    print("'In Produktion' (APIs & Dienste > OAuth-Zustimmungsbildschirm).")
    print()


if __name__ == "__main__":
    main()

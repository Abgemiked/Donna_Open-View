#!/usr/bin/env python3
"""Re-authorize Google Calendar OAuth2 — holt einen frischen Refresh-Token.

Dieses Script LOKAL ausführen (nicht im Docker-Container), weil ein Browser
für die Google-Autorisierung benötigt wird.

Usage:
    pip install google-auth-oauthlib
    set GOOGLE_CLIENT_ID=<deine_client_id>
    set GOOGLE_CLIENT_SECRET=<dein_client_secret>
    python backend/reauth_google_calendar.py

Die Client-ID und das Secret findest du in der .env auf Hetzner:
    ssh your-server "grep GOOGLE_ /opt/donna/.env"

Nach dem Script: neuen GOOGLE_REFRESH_TOKEN in /opt/donna/.env eintragen
und den Container neu starten:
    ssh your-server "cd /opt/donna && docker compose up -d assistent-api"
"""
from __future__ import annotations

import os
import sys


def main() -> None:
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
    print("Schritt 2 — Melde dich mit Mikes Google-Konto an.")
    print()
    print("Schritt 3 — Klicke 'Zulassen' (Allow).")
    print()
    print("Schritt 4 — Du wirst zu http://localhost weitergeleitet.")
    print("  Die Seite lädt NICHT — das ist normal.")
    print("  Kopiere die gesamte URL aus der Adressleiste (http://localhost?code=...).")
    print()

    redirect_response = input("Redirect-URL hier einfügen (beginnt mit http://localhost?code=): ").strip()

    if not redirect_response:
        print("ERROR: Keine URL eingegeben.")
        sys.exit(1)

    try:
        flow.fetch_token(authorization_response=redirect_response)
    except Exception as exc:
        print(f"\nERROR beim Token-Fetch: {exc}")
        print()
        print("Mögliche Ursachen:")
        print("  - Falsche URL kopiert (muss mit http://localhost?code= beginnen)")
        print("  - OAuth-Client-Typ ist 'Web application' statt 'Desktop app'")
        print("    → In der Google Cloud Console unter APIs & Dienste > Anmeldedaten ändern")
        print("  - http://localhost muss als Redirect-URI in der Google Cloud Console eingetragen sein")
        sys.exit(1)

    creds = flow.credentials
    if not creds.refresh_token:
        print("\nWARNING: Kein Refresh-Token erhalten.")
        print("Versuche es erneut — stelle sicher dass prompt=consent gesetzt ist.")
        sys.exit(1)

    print()
    print("=" * 60)
    print("  Erfolg! Neuer Refresh-Token:")
    print("=" * 60)
    print()
    print(f"GOOGLE_REFRESH_TOKEN={creds.refresh_token}")
    print()
    print("Nächste Schritte:")
    print("  1. ssh your-server")
    print("  2. nano /opt/donna/.env")
    print("     → GOOGLE_REFRESH_TOKEN=... durch den neuen Wert ersetzen")
    print("  3. cd /opt/donna && docker compose up -d assistent-api")
    print("  4. Logs prüfen: docker compose logs assistent-api --tail=20")
    print()


if __name__ == "__main__":
    main()

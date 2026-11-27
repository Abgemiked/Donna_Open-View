# Vertrag Server ↔ App: Ergebnis einer Geräteaktion (Rückkanal)

Issue #183, Refs #147 (Bixby-Parität, Schritt 0). Maschinenlesbare Quelle: `backend/tests/contracts/device_action_result_contract.json` —
der Server-Test (`backend/tests/test_device_action_results.py`) und der Kotlin-Test (`DeviceActionReportTest`) lesen **dieselbe Datei**.
Spätere Schritte (G1 Mehrfachbefehle, F, **D Offline**) nutzen denselben Vertrag; Änderungen am Vertrag = Änderung der JSON-Datei + beider Seiten.

## Ablauf
1. App sendet mit `/chat` zusätzlich `device_action_results: true` (nur zusammen mit `device_actions`).
2. Server vergibt bei jeder angestoßenen Geräteaktion `action_id` (`^da_[0-9a-f]{16}$`), optional `group_id` (`^g_[0-9a-f]{16}$`, ab G1) im SSE-Ereignis
   `{"type":"action","action":{"type":"device_action","action":"flashlight",…,"action_id":"da_…"}}`.
   **Ohne das Flag (alte App) kein `action_id`** — die alte App lehnt unbekannte Felder ab; Verhalten bleibt „angestoßen".
3. App führt aus (`DeviceActionRunner.runGated` — ein Weg für Chat, Overlay, Sprach-Aktivität) und meldet:
   `POST /device-actions/{action_id}/result`, `Authorization: Bearer …`, Body `{"result": <Code>, "reason": <Ursache, optional>}`.
4. Server speichert (Speicher, Ablauf 15 min, max. 200). `GET /device-actions/{action_id}` liefert den Stand. Das Werkzeug `device_action`
   bekommt eine Zeile „Rückmeldungen des Handys …" (nur Namen und Codes); ohne Meldung bleibt es „keine Rückmeldung (nur angestoßen)".

## Codes
| Ergebnis (`result`) | Bedeutung | Ursachen (`reason`) |
|---|---|---|
| `ok` | Gerät hat ausgeführt | – |
| `braucht_berechtigung` | Android-Recht fehlt | `berechtigung` |
| `nicht_unterstuetzt` | gibt es auf dem Gerät nicht / Schalter aus / gesperrt | `nicht_verfuegbar`, `schalter_aus`, `gesperrt` |
| `fehler` | Ausführung scheiterte oder wurde geprüft abgelehnt | `fehlgeschlagen`, `abgelehnt`, `mehrdeutig` |

Zuordnung der Geräte-Ergebnisarten (`ResultKind`) steht in `kind_mapping` der JSON-Datei. Unbekannte Ursache (beliebiger Text bis 32 Zeichen) → Server speichert `sonstige`, nie den Text. `ok` heißt: das Gerät hat die Aktion ausgeführt bzw. den System-Intent gestartet (die Ziel-App, z. B. Uhr, meldet nichts zurück).

## HTTP
`200` gespeichert / gleiche Wiederholung · `401` Auth · `404` unbekannte oder abgelaufene Kennung · `409` abweichende zweite Meldung (erste gewinnt) ·
`422` ungültiger Body/Kennung (extra Felder verboten) · `429` Limit (60 Meldungen/min) · `503` Speicher nicht verfügbar.
App wiederholt einmal bei Netzfehler, 5xx und 429; alles andere endgültig.

## Bekannte Grenzen (Schritt 0)
- „Rückgängig" auf dem Gerät wird nicht gemeldet; der Server-Stand bleibt `ok`.
- Speicher nur im Arbeitsspeicher, global (ein Nutzer); nach Neustart/Ablauf gilt wieder „angestoßen".
- Meldung bei Prozess-Ende der App geht verloren (best effort, ein Wiederholversuch).
- Das Limit (60/min) zählt auch Fehlversuche.
- Kotlin- und Jest-Tests laufen nicht in der Backend-CI (`donna-eval.yml`); die gemeinsame JSON wird dort nur serverseitig erzwungen.

## Datenschutz
Nur Ergebniscode, Ursache aus fester Liste und Aktionsname (ohne Werte) — kein App-Name, Label, Dauer, Uhrzeit oder sonstiger Inhalt; kein Freitext;
keine Kennungen im App-Log.

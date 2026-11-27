# Server-Vertrag `POST /health/push` (Issue #182)

Für die Android-Seite (SleepSessionRecord lesen + senden). Server-Stand: additiv, alte Clients laufen weiter.

Auth: wie bisher Admin-Bearer. Alle Felder optional; mindestens ein echter Wert nötig, sonst `{"ok": false, "error": "no_health_data_provided"}`.

## Felder
| Feld | Typ | Bedeutung |
|---|---|---|
| `sleep_start` | ISO-8601 **mit Zeitzone** (`Z` oder `+01:00`) | Beginn der Schlafsitzung (Health Connect `SleepSessionRecord.startTime`) |
| `sleep_end` | ISO-8601 **mit Zeitzone** | Ende der Sitzung. `sleep_start`/`sleep_end` nur zusammen; Ende > Start, ≤ 24 h, sonst HTTP 422 |
| `sleep_hours` | float \| null | Dauer in Stunden. Bei gesetzter Sitzung rechnet der Server selbst aus Start/Ende (Client-Wert wird ersetzt). `null` oder `0` = **keine Daten** (nicht als Messwert gespeichert) |
| `steps_today` | int \| null | Schritte des Tages |
| `resting_hr` | int \| null | Ruhepuls; `null`/`0` = keine Daten |
| `stress_score`, `spo2`, `sleep_stage` | wie bisher | Samsung Health |

## Regeln für den Client
- Fehlende Werte als **`null`** (oder Feld weglassen), nie als 0.
- Nach dem Aufwachen eine abgeschlossene Schlafsitzung senden (Start+Ende).
- Identischer Inhalt wie der zuletzt gespeicherte Push → Antwort `{"ok": true, "duplicate": true, "stored": []}` (nichts gespeichert). Doppelt senden ist harmlos, aber unnötig.

- `steps_today=0` gilt als gültiger Wert (z. B. kurz nach Mitternacht). Bitte NICHT 0 senden, wenn die Berechtigung fehlt oder Health Connect nichts liefert — dann Feld weglassen.
- Dedup: verglichen wird mit dem zuletzt gespeicherten Push (48 h). Bleibt der Inhalt gleich (z. B. Schritte unverändert), wird er nicht erneut gespeichert.
- Ungültige Einzelwerte (negative Schritte, Schlaf > 24 h) werden still verworfen; bleibt nichts übrig: HTTP 200 mit `ok: false`. Eine ungültige Sitzung (ohne Zeitzone, Ende <= Start, > 24 h) liefert HTTP 422.
- `GET /health/latest` liefert pro Feld den jüngsten Wert der letzten 48 h plus `updated_at`.

## Beispiel
```json
{
  "sleep_start": "2026-11-22T23:40:00+01:00",
  "sleep_end":   "2026-11-23T06:55:00+01:00",
  "steps_today": 412,
  "resting_hr": 58
}
```
Antwort: `{"ok": true, "stored": ["sleep_start", "sleep_end", "sleep_hours", "steps_today", "resting_hr"]}` (`sleep_hours` = 7.25).

## Datenschutz
Schlaf-/Gesundheitswerte werden nur für Mike genutzt (Tool `get_health_data`, nie im Stream/Live, nie in Twitch-Pfaden). Tagesaggregate liegen in `rhythm.db`.

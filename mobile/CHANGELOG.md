# Changelog — DonnaApp

## [Unreleased]
### Added
- apps/android: Ersteinrichtungs-Assistent (OnboardingScreen + natives DonnaSetupModule). Führt durch alle
  17 Berechtigungen/Spezialzugriffe (Pflicht / Für Funktionen / Erweitert), prüft den Status bei jeder
  Rückkehr in die App neu und gated den Chat, bis alle Pflicht-Schritte erledigt sind. Erneut aufrufbar über
  „Einrichtung“ (⚙️) im Chat-Header.
- apps/android: Health-Connect `activity-alias ViewPermissionUsageActivity` (Pflicht ab Android 14).
- apps/android: Tesla-Navigation (App-Sprint Batch 1, Teil A). Neues natives `TeslaNavModule` (+ `TeslaNav`-Helfer):
  `sendToTesla(text)` schickt das Ziel per explizitem ACTION_SEND-Intent an `com.teslamotors.tesla / com.tesla.share.ShareActivity`
  (lautlos ans Auto). Chat + Seitentasten-Overlay behandeln `tesla_navigate` (Bestätigungs-Karte „Ziel an Tesla senden“,
  ein Tipp, kein Auto-Fire), `navigate_disambiguate` (Treffer wählen → Tesla bzw. Handy-Navigation) und `navigate_not_found`
  (freundliche Meldung).
- apps/android: One-UI-9-Theming (UX-01, App-Sprint Batch 1, Teil B). Zentrale Theme-Quelle `src/theme`
  (Dark/Light via System-Schema, Material-You-Systemfarben ab Android 12, Pulse-Cyan nur noch Fallback);
  Kotlin-Overlay mit `colors.xml` (values / values-night / values-v31 / values-night-v31) und DayNight-Theme.

### Changed
- apps/android: MainActivity zeigt beim Start keine Berechtigungs-Dialoge/Toasts/Settings-Seiten mehr; Dienste
  (Tracking, Aktivitätserkennung, Geofencing, Health-Sync) starten nur noch, wenn die Berechtigung bereits erteilt ist.
- apps/android: VoiceInputActivity bricht ohne Overlay-Berechtigung nicht mehr ab (UX-05), sondern läuft als normales Fenster.
- apps/android: ChatScreen fragt die Standort-Berechtigung nicht mehr selbst an (nur noch Prüfung).
- apps/android: ChatScreen, IdeaConfirmCard und OnboardingScreen nutzen das zentrale Theme statt fester Farbkonstanten
  (iOS-Grau-Karten `#1c1c1e` abgelöst); App-Root zeichnet keinen lila Streifen (`#6200ee`) mehr und setzt den Top-Inset
  nur noch einmal (vorher SafeAreaView + Header-Padding). Statusleiste transparent (edge-to-edge).

### Fixed
- apps/android: Jest-Suite `ChatScreen.test.tsx` lief seit Längerem nicht an (react-native-Spread-Mock) — Harness repariert.

## [0.1.0] — TBD
### Added
- Phase 4 Bootstrap: Monorepo-Struktur (Yarn Workspaces + Turborepo)
- apps/android: React Native 0.76 Android App (Placeholder)
- apps/windows: React Native Windows 0.76 App (Placeholder)
- packages/shared: Shared Business Logic (@donna/shared)

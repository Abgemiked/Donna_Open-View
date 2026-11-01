# Donna als Standard-Assistent einrichten

## Ersteinrichtungs-Assistent (empfohlen)

Die App führt beim ersten Start (und nach jeder Neuinstallation, die Spezialzugriffe zurücksetzt) durch alle
Berechtigungen, inkl. „Standard-Assistent“ und „Über anderen Apps anzeigen“. Erneut öffnen: ⚙️ im Chat-Header.
Die manuelle Anleitung unten ist nur noch Fallback.

## Android-Einstellungen

1. Einstellungen → Apps → Standard-Apps → Digitaler Assistent
2. "Donna" auswählen
3. Side-Button (Einschalttaste gedrückt halten) → öffnet Donna direkt

## Technischer Stand

- VoiceInputActivity: vorhanden
- ASSIST-Intent-Filter: vorhanden (`android.intent.action.ASSIST`)
- showWhenLocked: true
- turnScreenOn: true
- launchMode: singleTask (kein Stapeln mehrerer Donna-Overlays)
- AccessibilityService: Konfiguriert via `res/xml/accessibility_service_config.xml`

## AccessibilityService-Status (DONNA-193)

Behoben: "funktioniert nicht" in Einstellungen → Eingabehilfe → Installierte Apps → Donna

Ursache war doppelte Config-Zuweisung (XML + dynamisches `serviceInfo` in `onServiceConnected()`).
Fix: Dynamische Zuweisung entfernt — XML-Config ist alleinige Autorität.

Nach APK-Install sollte Donna in Eingabehilfe korrekt als "Aus" (nicht "funktioniert nicht") erscheinen.

## Bekannte Einschränkungen

- Samsung Bixby-Button (dedizierter Bixby-Knopf) öffnet weiterhin Bixby — nicht konfigurierbar ohne Root
- Side-Button = Power/Einschalttaste lang gedrückt → öffnet Donna wenn als Standard-Assistent eingestellt
- Bei Samsung One UI: Einstellungen → Erweiterungsfunktionen → Seitentaste → Doppeltippen / Gedrückt halten → Assistent aufrufen

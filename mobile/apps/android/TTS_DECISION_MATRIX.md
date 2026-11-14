# TTS Entscheidungsmatrix — DONNA-192

Stand: 2026-05-11 | Recherche: WebSearch + Codeanalyse

---

## Kritischer Fund: Samsung Neural TTS IST BEREITS GESPERRT

**Samsung hat mit Android 15 / One UI 7 die Samsung TTS Engine (`com.samsung.SMT`) für
Third-Party-Apps gesperrt. Nur Samsung-eigene Apps (Bixby, Samsung-Reader etc.) dürfen
sie noch nutzen.**

Das bedeutet: Der aktuelle `TTSModule.kt`-Code der Donna-App sucht zwar nach Samsung SMT
(`de-de-x-sms*`, `de-DE-SMTf*`), bekommt aber auf dem S25 Ultra / One UI 7 **keine**
Samsung-Stimme mehr — das System fällt automatisch auf Google TTS zurück.

**Mike hört also bereits Google TTS, nicht Samsung Neural TTS.**

---

## Aktueller Stand (TTSModule.kt Analyse)

### Primärpfad: speakOnDevice()
- Nutzt `TextToSpeech` mit System-Default-Engine
- `applyVoiceSettings()` prüft Stimmen in Priorität:
  1. Samsung SMT (`de-de-x-sms*`) — **auf S25 Ultra One UI 7: nicht verfügbar für Third-Party**
  2. Samsung SMT alt (`de-DE-SMTf*`) — **ebenfalls gesperrt**
  3. Google Neural Network (`de-de-x-nfh-network`) — **das ist was Mike tatsächlich hört**
  4. Google Neural lokal (`de-de-x-nfh-local`)
  5. Google Standard (`de-de-x-deb-network`)
  6. Erste deutsche Stimme

### Fallback: speakViaKokoro()
- POST `/tts` an Hetzner-Backend
- Server-TTS war Piper-TTS — **wurde in DONNA-191 entfernt, gibt jetzt 501**
- Fallback auf `speakSystemTts()` (Google TTS) bei HTTP-Fehler

### Aktuell aktiv: Google TTS (`de-de-x-nfh`)
- `pitch=0.92`, `speechRate=0.93` — leicht angepasst
- Klingt nicht wie "Donna" / keine Persönlichkeit

---

## Option A: Samsung Neural TTS (On-Device)

| Kriterium | Bewertung |
|-----------|-----------|
| Verfügbar für Third-Party auf S25 Ultra / One UI 7 | **NEIN** |
| Verfügbare de-DE Stimmen | Gesperrt — nicht erreichbar |
| API-Zugang | `com.samsung.SMT` — nur Samsung-Apps |
| Workaround | Keiner bekannt. Kein offizieller API-Zugang |
| Implementierungsaufwand | hoch (nicht lösbar ohne Root / Samsung-Partnerschaft) |

**Fazit: Option A ist auf dem S25 Ultra nicht realisierbar.**

---

## Option B: Kokoro-82M ONNX On-Device (Empfehlung)

| Kriterium | Bewertung |
|-----------|-----------|
| Fertiges React Native Binding | **JA** — `react-native-sherpa-onnx` (TurboModule, Android + iOS) |
| Kokoro-Modell integriert | JA — Kokoro-82M-v1.0-ONNX über Sherpa-ONNX |
| Deutsch (de-DE) Support | **UNKLAR** — Kokoro 82M unterstützt primär EN, ZH, JA, HI; Deutsch nicht explizit bestätigt |
| Alternative: Piper VITS via Sherpa-ONNX | **JA** — Piper-Modell `de-DE` (kerstin-low) läuft über Sherpa-ONNX On-Device |
| Modellgröße | 82M Parameter, ~330 MB ONNX-Modell |
| Latenz On-Device | ~200-500ms auf S25 Ultra (Snapdragon 8 Elite) |
| Implementierungsaufwand | **3-5 Tage** (TurboModule einbinden + Modell ins APK) |
| DSGVO | ✓ On-Device, kein Cloud-Call |
| Risiken | APK-Größe +300-400MB; Kokoro-Deutsch unbestätigt; Sherpa-ONNX TurboModule ist Community-Library |

**Konkreter Weg mit Piper VITS via Sherpa-ONNX:**
- `react-native-sherpa-onnx` einbinden (New Architecture TurboModule)
- Piper-Modell `de_DE-kerstin-low.onnx` (~60MB) ins Assets-Verzeichnis
- `TTSModule.kt` anpassen: Sherpa-ONNX-TTS als primären Pfad nutzen
- Google TTS als Fallback behalten
- Vorteil gegenüber Kokoro: Deutsch **bestätigt funktionierend** (kerstin-low war Server-Piper-Stimme)

---

## Option C: Google WaveNet / Neural2 de-DE

| Kriterium | Bewertung |
|-----------|-----------|
| On-Device verfügbar (offline) | **NEIN** — WaveNet/Neural2 sind Cloud-only |
| Über Android TextToSpeech API | Nur als `de-de-x-nfh-network` (Online-Stimme) |
| Offline-Variante | `de-de-x-nfh-local` — Google Neural, aber ältere Qualität |
| Cloud-API (Google Cloud TTS) | Kostenpflichtig, ~$4/1M Zeichen für WaveNet |
| DSGVO-relevant | **JA** — Cloud-Call, Google-Server |
| Implementierungsaufwand | gering (API-Key + HTTP-Call) aber DSGVO-Problem |

**Fazit: Option C verstößt gegen Donna-Datenschutz-Prinzipien (kein Cloud-Sync).**

---

## Option D: Google TTS On-Device (Status quo — kein Aufwand)

| Kriterium | Bewertung |
|-----------|-----------|
| Aktuell aktiv | JA — Mike hört bereits diese Stimme |
| Qualität | Mittel — erkennbar synthetisch |
| Persönlichkeit | Keine — Standard-Google-Stimme |
| Aufwand | 0 (läuft bereits) |
| Erweiterungsmöglichkeit | Pitch/Rate-Tuning bereits aktiv (0.92/0.93) |

---

## Empfehlung

**Kurzfristig (sofort, 0 Tage): Option D — Status quo akzeptieren + Pitch/Rate feinjustieren.**
Mike hört bereits Google Neural TTS. Für bessere Persönlichkeit: `setPitch(0.88f)` und
`setSpeechRate(0.90f)` testen — macht die Stimme etwas tiefer und ruhiger (weniger robotisch).

**Mittelfristig (3-5 Tage): Option B — Piper VITS via react-native-sherpa-onnx.**
- `de_DE-kerstin-low` ist das exakte Piper-Modell das bisher auf dem Server lief
- Jetzt On-Device → kein Netzwerk, keine Latenz, gleiche Stimme wie vorher
- `react-native-sherpa-onnx` ist aktiv entwickelt und unterstützt New Architecture (RN 0.76)

**NICHT empfohlen:**
- Samsung SMT (gesperrt für Third-Party)
- Google WaveNet Cloud (DSGVO)
- Kokoro-82M direkt (Deutsch-Support unklar)

---

## Nächste Schritte (nach Mike-Freigabe)

1. Mike bestätigt Empfehlung (Option B — Piper ONNX via Sherpa)
2. team-dev implementiert: `react-native-sherpa-onnx` einbinden + `de_DE-kerstin-low.onnx`
3. Review-Rat Stufe 2 (6/6, Feature-Ebene wegen APK-Größen-Auswirkung)
4. APK-Build + ADB-Install

---

## Quellen

- [Samsung TTS Missing on Android 15 / One UI 7–8](https://speechcentral.net/2026/03/22/samsung-tts-missing-on-android-15-one-ui-7-8-whats-really-happening/)
- [Samsung TTS not usable except for Samsung apps starting Android 15](https://help.locusmap.eu/topic/37343-samsung-tts-cannot-be-used-except-for-samsung-apps-starting-with-android-15)
- [react-native-sherpa-onnx GitHub](https://github.com/XDcobra/react-native-sherpa-onnx)
- [Kokoro Models in react-native-sherpa-onnx](https://mintlify.com/XDcobra/react-native-sherpa-onnx/models/tts/kokoro)
- [Google Cloud TTS](https://cloud.google.com/text-to-speech)

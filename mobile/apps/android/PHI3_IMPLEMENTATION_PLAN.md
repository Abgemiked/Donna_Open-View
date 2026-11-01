# Implementierungsplan: Phi-3 Mini On-Device-LLM — DONNA-189

Stand: 2026-05-11 | Senior Dev Analyse + WebSearch-Recherche
Status: Wartet auf Mike-Freigabe

---

## Zusammenfassung

Phi-3 Mini soll als zweite On-Device-LLM-Option neben Gemini Nano in den bestehenden
`llmRouter.ts` integriert werden. Ziel: schnellere lokale Antworten ohne Cloud-Roundtrip,
besonders bei komplexeren Anfragen die Gemini Nano überfordern (>80 Zeichen, mehrschrittige
Fragen). Der empfohlene Ansatz ist **ONNX Runtime React Native** mit dem quantisierten
Phi-3 Mini INT4 ONNX-Modell (~2.3 GB) — Microsoft liefert offizielle ONNX-Modelle und ein
React Native Package existiert (`onnxruntime-react-native`).

---

## Kritische Vorab-Befunde

### MediaPipe LLM Inference: NICHT empfohlen
- Phi-3 Mini wird NICHT offiziell unterstützt (nur Phi-2, Gemma, Falcon, StableLM)
- MediaPipe LLM Inference API ist **deprecated** — Google migriert zu LiteRT-LM
- react-native-llm-mediapipe nutzt veraltete API

### ONNX Runtime: Empfohlen
- Microsoft liefert offizielle `Phi-3-mini-4k-instruct-onnx` Mobile-Modelle
- `onnxruntime-react-native` npm-Package ist aktiv gepflegt (offizielle Microsoft/ONNX-Lib)
- INT4-Quantisierung: ~2.3 GB (vs ~7 GB FP16)
- NNAPI-Accelerator: nutzt Snapdragon NPU auf S25 Ultra automatisch
- Bekannte Latenz: ~2-5 Tokens/Sek auf Snapdragon 8 Elite (CPU), mit NNAPI deutlich schneller

### Wichtiger Hinweis zur APK-Größe
Phi-3 Mini INT4 ~2.3 GB — das Modell kann NICHT im APK ausgeliefert werden (Play Store Limit: 150 MB).
Strategie: **Download-on-First-Use** mit Progress-Anzeige + lokaler Cache.

---

## Voraussetzungen

- DONNA-157 (Gemini Nano) ist deployed ✓ — Muster für KotlinModule vorhanden
- `llmRouter.ts` existiert und kann Phi-3 als zweiten on-device Provider aufnehmen
- Gerät: S25 Ultra (Snapdragon 8 Elite) — ausreichend RAM (12 GB) für INT4 Phi-3
- Freier Speicher: ~3 GB für Modell-Download + Runtime
- Internet beim ersten Start für Modell-Download

---

## Architektur-Entscheidung

**ONNX Runtime React Native + Phi-3 Mini INT4**

```
llmRouter.ts
  ├── Gemini Nano (AICore) — kurze/einfache Anfragen ≤80 Zeichen
  ├── Phi-3 Mini ONNX — mittlere Anfragen, kein Tool-Use
  └── Cloud API (Hetzner) — Tool-Use, LTM, Kalender, komplexe Anfragen
```

Routing-Logik (Erweiterung von `llmRouter.ts`):
- On-Device Prio 1: Gemini Nano (< 80 Zeichen, sehr einfach)
- On-Device Prio 2: Phi-3 Mini (80-400 Zeichen, kein Tool-Use)
- Cloud: > 400 Zeichen ODER Tool-Use Keywords ODER Phi-3 nicht verfügbar

---

## Betroffene Dateien

| Datei | Aktion | Beschreibung |
|-------|--------|--------------|
| `mobile/apps/android/android/app/build.gradle` | Ändern | `onnxruntime-android` Dependency hinzufügen |
| `mobile/apps/android/android/app/src/main/java/com/yourcompany/donna/Phi3Module.kt` | Neu | Kotlin Native Module: ONNX-Init, generate(), isAvailable(), downloadModel() |
| `mobile/apps/android/android/app/src/main/java/com/yourcompany/donna/DonnaNativePackage.kt` | Ändern | Phi3Module registrieren |
| `mobile/apps/android/src/modules/Phi3.ts` | Neu | TypeScript Bridge analog zu GeminiNano.ts |
| `mobile/apps/android/src/utils/llmRouter.ts` | Ändern | Phi-3 als zweiten on-device Provider integrieren |
| `mobile/apps/android/src/screens/ChatScreen.tsx` | Ändern | Download-Fortschritt anzeigen bei erstem Start |

---

## Implementierungsschritte

### Phase 1 — Setup (Tag 1)

1. `onnxruntime-android` in `build.gradle` einbinden:
   ```gradle
   implementation 'com.microsoft.onnxruntime:onnxruntime-android:latest.release'
   ```

2. `Phi3Module.kt` Grundgerüst anlegen:
   - `isAvailable(): Boolean` — prüft ob Modell lokal vorhanden
   - `isDownloaded(): Boolean` — prüft ob Modell heruntergeladen
   - `downloadModel(promise)` — async Download mit Progress-Events
   - `generate(prompt, maxTokens, promise)` — ONNX-Inferenz

3. `Phi3.ts` TypeScript Bridge erstellen (analog GeminiNano.ts)

### Phase 2 — ONNX-Inferenz (Tag 2-3)

4. ONNX Session-Management in `Phi3Module.kt`:
   - Lazy-Init: Modell erst beim ersten `generate()` laden
   - NNAPI-ExecutionProvider aktivieren (nutzt Snapdragon NPU)
   - Fallback auf CPU wenn NNAPI nicht verfügbar
   - Session-Cache: einmal laden, wiederverwenden

5. Tokenizer:
   - Phi-3 nutzt SentencePiece Tokenizer
   - Option A: HuggingFace Tokenizers Android Library
   - Option B: vorverarbeiteter ONNX-Tokenizer im Modell-Bundle

6. Prompt-Template Phi-3 instruct:
   ```
   <|user|>\n{prompt}<|end|>\n<|assistant|>
   ```

### Phase 3 — Router-Integration (Tag 3-4)

7. `llmRouter.ts` erweitern:
   - `ensurePhi3Ready()` — prüft Verfügbarkeit
   - `shouldUsePhi3(message)` — 80-400 Zeichen, kein Tool-Use
   - `routedGenerate()` — dreistufige Logik: Gemini → Phi-3 → Cloud

8. Download-UX in `ChatScreen.tsx`:
   - Einmalige Meldung beim ersten Start: "Phi-3 wird heruntergeladen (2.3 GB)..."
   - Progress-Bar während Download
   - Nach Download: "On-Device KI aktiviert"

### Phase 4 — Tests (Tag 4-5)

9. Unit-Tests in `Phi3Module.kt`:
   - `isAvailable()` gibt false wenn Modell fehlt
   - `generate()` wirft Exception wenn nicht initialisiert
   - Session-Cleanup bei App-Destroy

10. Router-Tests in `llmRouter.ts`:
    - Phi-3 wird für 80-400 Zeichen ohne Cloud-Keywords gewählt
    - Phi-3 fällt auf Cloud zurück wenn nicht verfügbar
    - Gemini Nano hat Vorrang bei <80 Zeichen

---

## Risiken

| Risiko | Wahrscheinlichkeit | Mitigation |
|--------|--------------------|------------|
| NNAPI-Kompatibilität auf S25 Ultra unbekannt | Mittel | CPU-Fallback implementieren |
| Modell-Download schlägt fehl (Netz) | Mittel | Retry-Logic + "Später" Option |
| RAM-Erschöpfung: 2.3GB ONNX + 12GB RAM | Niedrig | Modell erst bei erster Nutzung laden, bei low-memory entladen |
| Tokenizer-Kompatibilität | Mittel | Microsoft liefert fertigen ONNX-Tokenizer im Modell-Bundle |
| Inferenz zu langsam für Chat-UX (>5s) | Mittel | Max-Token-Limit 128 für Chat, Streaming wenn möglich |
| APK-Größe durch ONNX Runtime (+40 MB) | Niedrig | ABI-Split: nur arm64-v8a |

---

## Aufwand-Schätzung

- Gesamt: **4-5 Tage**
- Phase 1 (Setup + Bridge): 1 Tag
- Phase 2 (ONNX-Inferenz): 2 Tage
- Phase 3 (Router-Integration): 1 Tag
- Phase 4 (Tests + APK-Build): 0.5-1 Tag

---

## Modell-Info

- Modell: `microsoft/Phi-3-mini-4k-instruct-onnx` (mobile INT4)
- Größe: ~2.3 GB nach Download
- Quelle: HuggingFace oder Azure CDN
- Speicherort auf Gerät: `context.filesDir + "/phi3/"`
- Kontext-Länge: 4096 Tokens (im Chat: 512 für History + 128 für Antwort)

---

## Abhängigkeiten

- DONNA-190 (Offline-Modus) wartet auf diesen Plan — erst nach Mike-Freigabe starten
- DONNA-157 (Gemini Nano) muss deployed bleiben als Prio-1 on-device

---

## Quellen

- [ONNX Runtime für Phi-3 Mini](https://onnxruntime.ai/blogs/accelerating-phi-3)
- [Phi-3 Mini ONNX Mobile Models](https://deepwiki.com/microsoft/onnxruntime-inference-examples/2.5-phi-3-mini-for-mobile)
- [onnxruntime-react-native npm](https://www.npmjs.com/package/onnxruntime-react-native)
- [MediaPipe LLM Inference (deprecated)](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android)
- [react-native-llm-mediapipe](https://github.com/cdiddy77/react-native-llm-mediapipe)

/**
 * Statische Vertragstests für das Seitentasten-Overlay (Batch 2: UX-02/03/04).
 * Das Verhalten (Mikrofon, Ton, Animation) ist nur am Gerät prüfbar; die Zustandslogik ist in den
 * Kotlin-JVM-Tests (OverlayStateMachineTest u. a.) abgedeckt. Hier: Quelltext-Verträge, die nicht
 * versehentlich wieder verschwinden sollen.
 */
import fs from 'fs';
import path from 'path';

const ROOT = path.resolve(__dirname, '../../android/app/src/main');
const read = (...p: string[]) => fs.readFileSync(path.join(ROOT, ...p), 'utf8');
const KT = 'java/com/yourcompany/donna';

describe('VoiceInputActivity — Gemini-Overlay (UX-02/03/04)', () => {
  const kt = read(KT, 'VoiceInputActivity.kt');

  it('nutzt den Zustandsautomaten und den Orb', () => {
    expect(kt).toMatch(/OverlayStateMachine\(/);
    expect(kt).toMatch(/DonnaOrbView\(/);
    expect(kt).toMatch(/onRmsChanged[\s\S]{0,200}orb\.setLevel/);
  });

  it('liest den SSE-Stream inkrementell (Zeile für Zeile) statt erst nach Stream-Ende', () => {
    expect(kt).toMatch(/SseLineReader\.readLine\(reader\)/);
    expect(kt).toMatch(/SseLineParser\.parse/);
    expect(kt).not.toMatch(/forEachLine/);
    expect(kt).not.toMatch(/parseSseStream/);
    expect(kt).toMatch(/Accept-Encoding", "identity"/);
  });

  it('spricht satzweise über die Pipeline und nutzt den vorhandenen /tts-Weg', () => {
    expect(kt).toMatch(/SentenceSpeechPipeline\(/);
    expect(kt).toMatch(/SentenceChunker\(/);
    expect(kt).toMatch(/"\/tts\?voice="/);
    expect(kt).not.toMatch(/speakViaPiper/);
  });

  it('A/B-Engine-Kette: Edge ↔ Piper-lokal pro Satz, jeweils die andere als Fallback, dann System-TTS', () => {
    expect(kt).toMatch(/SentenceSynthesizer\(/);
    expect(kt).toMatch(/TtsEngines\.order\(prefs\.getString\(TtsEngines\.PREF_ENGINE/);
    expect(kt).toMatch(/synthesizeViaPiper/);
    expect(kt).toMatch(/fallbackSpeak = \{ speakSystem\(it\) \}/);
    // Piper wird NICHT lazy beim ersten Satz geladen, sondern nur von der Platte geholt, wenn schon heruntergeladen
    expect(kt).toMatch(/PiperTTSModule\.ensureLoadedFromDisk/);
  });

  it('Auto-Relisten hängt am echten Ende der Ausgabe, nicht an einer Zeichen-Schätzung', () => {
    expect(kt).toMatch(/ResponseFinished\(/);
    expect(kt).toMatch(/OnCompletionListener/);
    expect(kt).toMatch(/UtteranceProgressListener/);
    expect(kt).not.toMatch(/\*\s*70/);
  });

  it('keine 300-ms-Wartezeit mehr vor dem Start und kein Halten-zum-Reden', () => {
    expect(kt).not.toMatch(/postDelayed\([\s\S]{0,200},\s*300\s*\)/);
    expect(kt).not.toMatch(/HALTEN ZUM REDEN/);
    expect(kt).not.toMatch(/isPttMode/);
  });

  it('Sprachende automatisch + großzügige Sprechpause + Sprachmodus-Signal', () => {
    expect(kt).toMatch(/onEndOfSpeech[\s\S]{0,300}SpeechEnded/);
    expect(kt).toMatch(/EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS/);
    expect(kt).toMatch(/EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS/);
    expect(kt).toMatch(/put\("input_mode", inputMode\)/);
    expect(kt).toMatch(/INPUT_MODE_VOICE = "voice"/);
  });

  it('sendet client:"android" im /chat-Body (Live-Guard Positiv-Allowlist, atomares Guard-Set)', () => {
    // Das Backend vertraut einem Direktpfad im Stream NUR bei client=="android" + input_mode∈{voice,text}
    // (Positiv-Allowlist). Fällt diese Zeile weg, ist das Overlay still untrusted → der Live-Guard-Bug
    // (Mikes eigene Befehle werden im Stream geblockt) käme zurück. Darum als Contract festgenagelt.
    expect(kt).toMatch(/put\("client",\s*"android"\)/);
  });

  it('Statusmeldungen stehen in Satzschreibung (kein Versal-Status)', () => {
    expect(kt).not.toMatch(/"HÖRE ZU"|"VERARBEITE…"|"DONNA DENKT…"|"KEIN MIKROFON/);
  });

  it('behält Karten, Aktionen, Tesla-Navigation, Biometrie und Live-Guard', () => {
    // Post-B1: Karten-/Aktionslogik lebt in overlay/ActionCardBuilder + overlay/ActionExecutor; die Activity delegiert.
    const cards = read(KT, 'overlay/ActionCardBuilder.kt');
    const exec = read(KT, 'overlay/ActionExecutor.kt');
    const turn = read(KT, 'overlay/TurnRenderer.kt');
    expect(cards).toMatch(/private fun buildNavDisambiguateCard/);
    expect(cards).toMatch(/private fun buildNavNotFoundCard/);
    expect(cards).toMatch(/"navigate_disambiguate" -> return buildNavDisambiguateCard\(action\)/);
    expect(cards).toMatch(/"navigate_not_found"\s+-> return buildNavNotFoundCard\(action\)/);
    expect(cards).toMatch(/NavActionLogic\./);
    expect(kt).toMatch(/NavActionLogic\./); // teslaTargetOf bleibt in der Activity
    expect(exec).toMatch(/fun execute\(action: JSONObject\)/); // vormals private fun executeAction in der Activity
    expect(cards).toMatch(/fun executeAction\(action: JSONObject\)/); // Host-Callback des Karten-Builders
    // Delegation: die Activity verdrahtet die drei Bausteine und ruft sie auf
    expect(kt).toMatch(/ActionExecutor\(object : ActionExecutor\.Host/);
    expect(kt).toMatch(/ActionCardBuilder\(object : ActionCardBuilder\.Host/);
    expect(kt).toMatch(/TurnRenderer\(object : TurnRenderer\.Host/);
    expect(kt).toMatch(/actionExecutor\.execute\(action\)/);
    expect(kt).toMatch(/actionCardBuilder\.buildActionCard\(/);
    expect(kt).toMatch(/turnRenderer\.render\(/);
    expect(turn).toMatch(/fun render\(/);
    expect(kt).toMatch(/BiometricPrompt/);
    expect(kt).toMatch(/X-Biometric-Auth/);
    expect(kt).toMatch(/code == 204/); // Live-Guard bei /tts
  });

  it('a11y: Status ist eine Live-Region, Bedienelemente haben Beschreibungen', () => {
    expect(kt).toMatch(/ACCESSIBILITY_LIVE_REGION_POLITE/);
    expect(kt).toMatch(/contentDescription = "Senden"/);
    expect(kt).toMatch(/orb\.contentDescription/);
  });
});

describe('Orb-View respektiert „Animationen entfernen“', () => {
  const orb = read(KT, 'DonnaOrbView.kt');
  it('fragt areAnimatorsEnabled ab', () => {
    expect(orb).toMatch(/areAnimatorsEnabled\(\)/);
  });
  it('nutzt keine festen Farbwerte', () => {
    expect(orb).not.toMatch(/Color\.parseColor\(|0x[0-9a-fA-F]{8}/);
  });
});

describe('Overlay-Ressourcen', () => {
  it('Orb-Fehlerfarbe und Scrim existieren in Hell und Dunkel', () => {
    for (const dir of ['values', 'values-night']) {
      const xml = read('res', dir, 'donna_orb.xml');
      expect(xml).toMatch(/<color name="donna_orb_error">/);
      expect(xml).toMatch(/<color name="donna_scrim">/);
    }
  });

  it('Scrim liegt bei ca. 20–32 % Deckkraft', () => {
    for (const dir of ['values', 'values-night']) {
      const xml = read('res', dir, 'donna_orb.xml');
      const m = xml.match(/<color name="donna_scrim">#([0-9a-fA-F]{2})[0-9a-fA-F]{6}<\/color>/);
      expect(m).not.toBeNull();
      const pct = parseInt(m![1], 16) / 255;
      expect(pct).toBeGreaterThanOrEqual(0.2);
      expect(pct).toBeLessThanOrEqual(0.32);
    }
  });

  it('Fenster ist wirklich durchscheinend (App hinter dem Scrim sichtbar)', () => {
    const styles = read('res', 'values', 'styles.xml');
    expect(styles).toMatch(/windowIsTranslucent">true</);
    expect(styles).not.toMatch(/windowIsTranslucent">false</);
    expect(styles).toMatch(/windowBackground">@android:color\/transparent</);
    expect(read(KT, 'VoiceInputActivity.kt')).toMatch(/params\.format = android\.graphics\.PixelFormat\.TRANSLUCENT/);
    expect(read(KT, 'VoiceInputActivity.kt')).toMatch(/FLAG_SHOW_WHEN_LOCKED|setShowWhenLocked\(true\)/);
  });

  it('Theme dimmt nicht zusätzlich per System', () => {
    const styles = read('res', 'values', 'styles.xml');
    expect(styles).toMatch(/backgroundDimEnabled">false</);
  });
});

describe('Batch-2-Review-Fixes (Echo-Schutz, Tastatur, Absturzschutz)', () => {
  const kt = read(KT, 'VoiceInputActivity.kt');
  const manifest = read('AndroidManifest.xml');
  const styles = read('res', 'values', 'styles.xml');

  it('A: Echo-Schutz vor dem Auto-Relisten und Turn-Limit', () => {
    expect(kt).toMatch(/ECHO_GUARD_MS = 350L/);
    expect(kt).toMatch(/StartListeningAfterEcho -> \{[\s\S]{0,200}ui\.postDelayed\(autoListenRunnable, ECHO_GUARD_MS\)/);
    const sm = read(KT, 'OverlayStateMachine.kt');
    expect(sm).toMatch(/MAX_AUTO_LISTENS = 3/);
  });

  it('B: Tippen beendet das Zuhören, Stopp bleibt bei offener Tastatur erreichbar', () => {
    expect(kt).toMatch(/imeVisible && !wasVisible && machine\.state == OverlayState\.LISTENING/);
    // schlanke Leiste: der Mini-Orb und die Aktions-Taste bleiben bei offener Tastatur sichtbar
    expect(kt).not.toMatch(/orb\.visibility = if \(imeVisible\)/);
    // die Aktions-Taste (Stopp/Fertig) wird bei offener Tastatur NICHT ausgeblendet
    expect(kt).not.toMatch(/actionPill\.visibility = if \(imeVisible\)/);
    expect(kt).toMatch(/TextSubmitted\(fromRecognizer = inputMode == INPUT_MODE_VOICE, genuineSpeech = genuine\)/);
    expect(kt).toMatch(/gen != listenGeneration/);
    expect(kt).toMatch(/ResponseFinished\(spokeAnything, hasPendingCards\(\), typingActive\(\)\)/);
  });

  it('C: Tastatur öffnet sich beim Start nicht', () => {
    expect(manifest).toMatch(/android:windowSoftInputMode="adjustResize\|stateHidden"/);
    expect(styles).toMatch(/windowSoftInputMode">adjustResize\|stateHidden</);
  });

  it('D: Sprechpause-Standard ist lang', () => {
    const sm = read(KT, 'OverlayStateMachine.kt');
    expect(sm).toMatch(/val DEFAULT = LONG/);
  });

  it('E/F: toter Server-TTS prozessweit, Timeout, Exception-Handler, Player-Race', () => {
    const pipe = read(KT, 'SentenceSpeechPipeline.kt');
    // Review M4: eigene Kette-Health (2 Fehler in Folge, 45 s) statt „1 Fehler / 5 min“
    expect(pipe).toMatch(/val chain = TtsHealth\(cooldownMs = CHAIN_COOLDOWN_MS, failureThreshold = CHAIN_FAILURE_THRESHOLD\)/);
    expect(pipe).toMatch(/CHAIN_FAILURE_THRESHOLD = 2/);
    expect(pipe).toMatch(/CHAIN_COOLDOWN_MS = 45_000L/);
    expect(pipe).toMatch(/health: TtsHealth = TtsHealth\.chain/);
    expect(pipe).not.toMatch(/TtsHealth\.shared/);
    expect(pipe).toMatch(/withTimeout\(synthTimeoutMs\)/);
    expect(kt).toMatch(/CoroutineExceptionHandler/);
    expect(kt).toMatch(/if \(!cancelled\) \{[\s\S]{0,240}it\.start\(\)/);
  });

  it('G: Watchdogs (Zuhören 30 s, Erkenner 15 s, System-TTS)', () => {
    expect(kt).toMatch(/MAX_LISTEN_MS = 30_000L/);
    expect(kt).toMatch(/RECOGNIZER_WATCHDOG_MS = 15_000L/);
    expect(kt).toMatch(/watchdogMs = 4_000L \+ clean\.length \* 90L/);
    // Stille-Zeitgeber starten in startListening(), nicht erst bei onReadyForSpeech
    expect(kt).toMatch(/ui\.postDelayed\(noSpeechRunnable, OverlayTimings\.NO_SPEECH_TIMEOUT_MS\)/);
  });

  it('H/I: Backend-Fehler-Event und content:null', () => {
    const sse = read(KT, 'SseEvents.kt');
    expect(sse).toMatch(/"error" -> SseEvent\.Error/);
    expect(sse).toMatch(/json\.isNull\(key\)/);
  });

  it('J/K/L/M: Nochmal sendet erneut, Panel scrollt, 48dp, Karten-Tippschutz', () => {
    expect(kt).toMatch(/pendingRetry/);
    expect(kt).toMatch(/Einstellungen/);
    expect(kt).toMatch(/adaptivePanel = AdaptivePanel/);
    expect(kt).toMatch(/bodyScroll = ScrollView/);
    expect(kt).toMatch(/minHeight = dp\(48\)/);
    // Post-B1: die Karten-Buttons (48 dp, Tippschutz-Tag) baut overlay/ActionCardBuilder; ARM_TAG liefert die Activity als Host-Wert
    const cards = read(KT, 'overlay/ActionCardBuilder.kt');
    expect(cards).toMatch(/minHeight = host\.dp\(48\)/);
    expect(cards).toMatch(/tag = host\.armTag/);
    expect(kt).toMatch(/override val armTag: String get\(\) = ARM_TAG/);
    expect(kt).toMatch(/v\.tag == ARM_TAG/); // armCardsLater schaltet genau diese Buttons scharf
    expect(kt).toMatch(/armCardsLater\(\)/);
  });

  it('N/O/P/Q/R: Autoscroll, Frage sichtbar, Stream-Cap, TalkBack-Rollen, Volatile', () => {
    expect(kt).toMatch(/userScrolledResponse/);
    expect(kt).toMatch(/questionText/);
    expect(kt).toMatch(/MAX_RESPONSE_CHARS = 20_000/);
    expect(kt).toMatch(/setRole\(.*"android\.widget\.Switch"/);
    expect(kt).toMatch(/@Volatile private var currentSessionId/);
    expect(kt).toMatch(/live = false/);
  });

  it('S/T/V: Stille zeigt Hinweis, Aktions-Antwort ist kein Fehler, onNewIntent', () => {
    expect(kt).toMatch(/Ich habe nichts gehört/);
    expect(kt).toMatch(/!t\.sawAction/);
    expect(kt).toMatch(/override fun onNewIntent/);
  });
});

describe('Schlanke Bodenleiste (Gemini-Look)', () => {
  const kt = read(KT, 'VoiceInputActivity.kt');

  it('hat keine große Kopfzeile, kein Handle und keinen Tippen-Block mehr', () => {
    expect(kt).not.toMatch(/headerRow/);
    expect(kt).not.toMatch(/val handle = /);
    expect(kt).not.toMatch(/typePill|orbZone/);
    expect(kt).not.toMatch(/text = "Donna"\s*\n\s*setTextSize\(TypedValue\.COMPLEX_UNIT_SP, 16f\)/);
  });

  it('Mini-Orb (52 dp) in der Statuszeile, Stopp nur wenn relevant', () => {
    expect(kt).toMatch(/LinearLayout\.LayoutParams\(dp\(52\), dp\(52\)\)/);
    expect(kt).toMatch(/actionPill = buildPill\("Stopp", primary = true\)\.apply \{\s*visibility = View\.GONE/);
    expect(kt).toMatch(/OverlayState\.IDLE -> "" to /);
  });

  it('Antwort und Karten sind erst sichtbar, wenn es etwas anzuzeigen gibt', () => {
    expect(kt).toMatch(/responseScroll = FrameLayout\(ctx\)\.apply \{\s*visibility = View\.GONE/);
    expect(kt).toMatch(/cardContainer = LinearLayout\(ctx\)\.apply \{[\s\S]{0,120}visibility = View\.GONE/);
    expect(kt).toMatch(/questionText = TextView\(ctx\)\.apply \{[\s\S]{0,400}visibility = View\.GONE/);
  });

  it('Gesprächsmodus und Verlauf sind kleine Chips', () => {
    expect(kt).toMatch(/"Gespräch an" else "Gespräch aus"/);
    expect(kt).toMatch(/buildChip\(\)/);
    expect(kt).toMatch(/text = "Verlauf"/);
  });

  it('Eingabekapsel bleibt das Hauptelement', () => {
    expect(kt).toMatch(/hint = "Donna fragen…"/);
    expect(kt).toMatch(/contentDescription = "Senden"/);
  });
});

describe('Barge-in (Overlay und Haupt-Chat)', () => {
  const kt = read(KT, 'VoiceInputActivity.kt');
  const chat = fs.readFileSync(path.resolve(__dirname, '../screens/ChatScreen.tsx'), 'utf8');
  const hook = fs.readFileSync(path.resolve(__dirname, '../../../../packages/shared/src/hooks/useChat.ts'), 'utf8');

  it('Overlay: Mic/Orb-Tipp im Sprechen unterbricht, Tippen im Sprechen stoppt die Wiedergabe', () => {
    expect(kt).toMatch(/machine\.state == OverlayState\.LISTENING \|\| machine\.state == OverlayState\.SPEAKING/);
    expect(kt).toMatch(/OverlayState\.THINKING, OverlayState\.SPEAKING -> fire\(OverlayEvent\.StopRequested\)/);
    expect(kt).toMatch(/OverlayState\.SPEAKING -> "Stopp" to /);
  });

  it('Haupt-Chat: Mic-Tap, Tippen und Senden unterbrechen; Stopp-Taste sichtbar', () => {
    expect(chat).toMatch(/const bargeIn = useCallback/);
    expect(chat).toMatch(/bargeIn\(\);[\s\S]{0,80}const granted = await requestMicPermission/);
    expect(chat).toMatch(/interruptSpeech\(\);[\s\S]{0,120}const quote = replyTo/);
    expect(chat).toMatch(/accessibilityLabel="Donna unterbrechen"/);
    // Senden ist nicht mehr an isLoading gekoppelt
    expect(chat).not.toMatch(/disabled=\{!inputText\.trim\(\) \|\| isLoading/);
  });

  it('Hook: neue Frage ersetzt den Stream (AbortController), cancel() steht zur Verfuegung', () => {
    expect(hook).toMatch(/new AbortController\(\)/);
    expect(hook).toMatch(/reason = 'superseded'/);
    expect(hook).toMatch(/cancel: \(\) => void/);
    expect(hook).not.toMatch(/if \(isLoadingRef\.current\) \{return;\}/);
  });
});

describe('STT-Biasing und Freisprech-Gesprächsmodus', () => {
  const kt = read(KT, 'VoiceInputActivity.kt');
  const stt = read(KT, 'OfflineSTTModule.kt');
  const bias = read(KT, 'SttBias.kt');

  it('beide Erkenner-Pfade nutzen die gemeinsame Bias-Liste (API 33+)', () => {
    expect(kt).toMatch(/SttBias\.applyTo\(intent\)/);
    expect(stt).toMatch(/SttBias\.applyTo\(intent\)/);
    expect(bias).toMatch(/EXTRA_BIASING_STRINGS/);
    expect(bias).toMatch(/sdkInt >= 33/);
    expect(bias).toMatch(/"Streamingzimmer"/);
    expect(bias).toMatch(/"Regallicht Halloween"/);
  });

  it('natives TTS meldet das echte Ende (Auto-Relisten ohne Zeitschätzung)', () => {
    const tts = read(KT, 'TTSModule.kt');
    const piper = read(KT, 'PiperTTSModule.kt');
    expect(tts).toMatch(/TtsEvents\.emit\(reactContext, "ended"\)/);
    expect(piper).toMatch(/TtsEvents\.emit\(reactContext, "ended"\)/);
    expect(read(KT, 'DonnaNativePackage.kt')).toMatch(/BargeInModule\(ctx\)/);
  });

  it('Voice-Barge-in: Echo-Unterdrückung Pflicht, Fallback ohne, abschaltbar', () => {
    const det = read(KT, 'BargeInDetector.kt');
    expect(det).toMatch(/AcousticEchoCanceler/);
    expect(det).toMatch(/aec == null \|\| aec\.setEnabled\(true\) != AudioEffect\.SUCCESS \|\| !aec\.enabled/);
    expect(det).toMatch(/VOICE_COMMUNICATION/);
    expect(kt).toMatch(/SpokenTextMatcher\.isEcho/);
    expect(kt).toMatch(/PREF_VOICE_BARGE_IN/);
    expect(kt).toMatch(/echoStrikes\.record\(\)/);
  });
});

describe('Review-Härtung: Voice-Barge-in (Standard AN, Opt-out), Haupt-Chat-STT, Hintergrund, Limits', () => {
  const kt = read(KT, 'VoiceInputActivity.kt');
  const chat = fs.readFileSync(path.resolve(__dirname, '../screens/ChatScreen.tsx'), 'utf8');
  const det = read(KT, 'BargeInDetector.kt');
  const sm = read(KT, 'OverlayStateMachine.kt');
  const offline = read(KT, 'OfflineSTTModule.kt');

  it('Default-ON (Voice-Batch): Voice-Barge-in ist in beiden Oberflächen Opt-out', () => {
    expect(kt).toMatch(/VOICE_BARGE_IN_DEFAULT = true/);
    expect(kt).not.toMatch(/VOICE_BARGE_IN_DEFAULT = false/);
    expect(kt).toMatch(/getBoolean\(PREF_VOICE_BARGE_IN, VOICE_BARGE_IN_DEFAULT\)/);
    expect(chat).toMatch(/export const VOICE_BARGE_IN_DEFAULT = true/);
    expect(chat).toMatch(/useRef\(VOICE_BARGE_IN_DEFAULT\)/);
    // Opt-out: nur ein ausdrücklich gespeichertes "false" schaltet es aus; ohne gespeicherten Wert gilt der Default
    expect(chat).toMatch(/val === null \|\| val === undefined \? VOICE_BARGE_IN_DEFAULT : val !== 'false'/);
    expect(chat).not.toMatch(/voiceBargeInEnabledRef\.current = val === 'true'/);
    // Freisprech-Turn-Taking (Mic NACH TTS-Ende) bleibt an
    expect(chat).toMatch(/useState\(true\);\s*\n\s*const conversationModeRef = useRef\(true\)/);
  });

  it('F5: Haupt-Chat führt die Erkennung über OfflineSTT (mit Biasing), Cloud-STT nur als Fallback', () => {
    expect(chat).toMatch(/OfflineSTT\.start\('de-DE'\)/);
    expect(chat).toMatch(/const startListeningAny/);
    expect(chat).toMatch(/offlineSTTAvailableRef\.current = false;[\s\S]{0,60}setOfflineAvailable\(false\)/);
    // alle Startpunkte laufen über startListeningAny
    expect(chat).not.toMatch(/startListening\(\)\.catch/);
    expect(chat).toMatch(/startListeningAny\(\)\.catch/);
    expect(offline).toMatch(/SttBias\.applyTo\(intent\)/);
    expect(offline).toMatch(/fun cancelListening/);
  });

  it('F1: Ja/Nein über parseConfirmation (Verneinung zuerst, Wortgrenzen); offene Karte verhindert hands-free', () => {
    expect(chat).toMatch(/parseConfirmation\(trimmed\)/);
    expect(chat).not.toMatch(/lower\.includes\(w\)/);
    expect(chat).toMatch(/if \(cardOpenRef\.current\) \{ return; \}/);
  });

  it('F2: Echo-Filter nur bei Auto-Relisten/Barge-in', () => {
    expect(chat).toMatch(/echoFilterArmedRef\.current = true;\s*\/\/ automatisch geöffnet/);
    expect(chat).toMatch(/if \(armed && isEchoOfSpoken/);
    expect(kt).toMatch(/echoFilterArmed && SpokenTextMatcher\.isEcho\(text, lastSpokenText, bargeIn = bargeInSession\)/);
  });

  it('H1: Hintergrund bricht ab (cancel statt stop), nichts startet im Hintergrund', () => {
    expect(chat).toMatch(/cancelListeningAny\(\)\.catch\(\(\) => \{\}\);\s*\n\s*cancelChat\?\.\(\)/);
    expect(chat).toMatch(/if \(appInBackground\(\)\) \{ return; \}/);
    expect(chat).toMatch(/AppState\.currentState === 'active' &&/);
  });

  it('K1/K2/F3: Barge-in zählt zum Limit und setzt es nicht zurück; max. 3 pro Berührung', () => {
    expect(sm).toMatch(/object VoiceBargeIn : OverlayEvent/);
    expect(sm).toMatch(/MAX_BARGE_INS_PER_TOUCH = 3/);
    expect(chat).toMatch(/MAX_BARGE_INS_PER_TOUCH = 3/);
    expect(chat).toMatch(/bargeInCountRef\.current \+= 1;\s*\n\s*handsFreeTurnsRef\.current \+= 1;/);
    expect(kt).toMatch(/fire\(OverlayEvent\.VoiceBargeIn\)/);
    expect(kt).not.toMatch(/fire\(OverlayEvent\.MicRequested\)\s*\/\/ beendet Anfrage \+ Ausgabe/);
  });

  it('R1/F4: Detektor mit Hard-Cap; Monitor wird in allen Endpfaden gestoppt', () => {
    expect(det).toMatch(/MAX_RUNTIME_MS = 90_000L/);
    expect(chat).toMatch(/BARGE_IN_MAX_MS = 90_000/);
    expect(chat).toMatch(/stopBargeInMonitor\(\);\s*\/\/ das Mikrofon gehört jetzt dem Erkenner/);
    expect(chat).toMatch(/stopAllTTS\(\);\s*\/\/ inkl\. Timer, geplantem Relisten und Stimm-Lauschen/);
    expect(chat).toMatch(/stopBargeInMonitor\(\);\s*\/\/ ohne TTS-Ende-Ereignis/);
  });

  it('R2: Strikes bei Barge-in ohne Äußerung, Reset bei echter Sprache und beim Einschalten', () => {
    expect(kt).toMatch(/endBargeInSession\(genuine = false\)/);
    expect(kt).toMatch(/echoStrikes\.reset\(\)/);
    expect(chat).toMatch(/endBargeInSession\(false\)/);
    expect(chat).toMatch(/echoStrikesRef\.current\.reset\(\)/);
  });

  it('F3-AEC: Detektor startet nur mit wirklich aktivem AEC und Aufnahme, mit Backoff und Lauf-ID', () => {
    expect(det).toMatch(/recordingState != AudioRecord\.RECORDSTATE_RECORDING/);
    expect(det).toMatch(/initFailedUntil = SystemClock\.elapsedRealtime\(\) \+ INIT_BACKOFF_MS/);
    expect(det).toMatch(/val id: Long/);
    expect(det).toMatch(/synchronized\(lock\)/);
  });

  it('M1: startListening(intent) ist abgesichert', () => {
    expect(kt).toMatch(/try \{\s*\n\s*startListening\(intent\)\s*\n\s*\} catch/);
  });

  it('S4: Textfeld bleibt beim Zuhören benutzbar', () => {
    expect(chat).not.toMatch(/editable=\{!isListening\}/);
    expect(chat).toMatch(/onFocus=\{\(\) => \{ if \(isListening\)/);
  });

  it('OfflineSTTModule: API-33-Guard für isOnDeviceRecognitionAvailable und abgesicherte Erzeugung', () => {
    expect(offline).not.toMatch(/SDK_INT >= Build\.VERSION_CODES\.S/);
    expect(offline).toMatch(/VERSION_CODES\.TIRAMISU/);
    expect(offline).toMatch(/catch \(e: Throwable\)[\s\S]{0,120}STT_CREATE_FAILED/);
  });

  it('B1: OfflineSTT-Watchdogs im Haupt-Chat', () => {
    expect(chat).toMatch(/OFFLINE_NO_PARTIAL_MS = 10_000/);
    expect(chat).toMatch(/OFFLINE_RESULT_WATCHDOG_MS = 9_000/);
    expect(chat).toMatch(/if \(appInBackground\(\) \|\| !offlineListeningRef\.current\)/);
    expect(chat).toMatch(/clearOfflineTimers\(\);[\s\S]{0,60}offlineListeningRef\.current = false;[\s\S]{0,40}OfflineSTT\.cancel/);
  });
});

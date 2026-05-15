/**
 * AndroidVoiceModule — VoiceModuleInterface-Implementierung für Android
 *
 * Verwendet @react-native-voice/voice für Google Speech-to-Text.
 * Locale: de-DE (deutsch)
 */
import Voice, {
  SpeechResultsEvent,
  SpeechErrorEvent,
  SpeechStartEvent,
  SpeechEndEvent,
} from '@react-native-voice/voice';
import type {VoiceModuleInterface, VoiceResult, VoiceError} from '@donna/shared';

/**
 * Spracherkennung-Normalisierung: "Amy" → "Ämi" — Google STT transkribiert
 * Mikes Freundin Ämi regelmäßig als "Amy". Ausnahme: bekannte Prominente wie
 * "Amy Winehouse", "Amy Lee" etc. werden durch Kontext-Keywords erkannt.
 */
const _FAMOUS_AMY_KEYWORDS = [
  'winehouse', 'lee', 'adams', 'grant', 'schumer', 'irving', 'macdonald',
];

function _normalizeVoiceTranscript(text: string): string {
  const lower = text.toLowerCase();
  // Behalte "Amy" wenn ein bekannter Nachname folgt
  const hasFamousAmy = _FAMOUS_AMY_KEYWORDS.some(
    kw => lower.includes(`amy ${kw}`) || lower.includes(`amy-${kw}`),
  );
  if (hasFamousAmy) {
    return text;
  }
  // Alle anderen "Amy"-Vorkommen → "Ämi"
  return text.replace(/\bamy\b/gi, 'Ämi');
}

class AndroidVoiceModuleImpl implements VoiceModuleInterface {
  onResult: ((result: VoiceResult) => void) | null = null;
  onError: ((error: VoiceError) => void) | null = null;
  onStart: (() => void) | null = null;
  onEnd: (() => void) | null = null;

  private _available: boolean = true;

  constructor() {
    Voice.onSpeechStart = (_e: SpeechStartEvent) => {
      this.onStart?.();
    };

    Voice.onSpeechEnd = (_e: SpeechEndEvent) => {
      this.onEnd?.();
    };

    Voice.onSpeechResults = (e: SpeechResultsEvent) => {
      const transcript = _normalizeVoiceTranscript(e.value?.[0] ?? '');
      if (transcript) {
        this.onResult?.({transcript, isFinal: true, confidence: 1.0});
      }
    };

    Voice.onSpeechPartialResults = (e: SpeechResultsEvent) => {
      const transcript = _normalizeVoiceTranscript(e.value?.[0] ?? '');
      if (transcript) {
        this.onResult?.({transcript, isFinal: false});
      }
    };

    Voice.onSpeechError = (e: SpeechErrorEvent) => {
      const code = String(e.error?.code ?? 'unknown');
      const message = String(e.error?.message ?? 'Speech recognition error');
      // 7 = keine Eingabe erkannt — kein echter Fehler
      if (code !== '7') {
        this.onError?.({code, message});
      } else {
        this.onEnd?.();
      }
    };
  }

  async start(locale: string = 'de-DE'): Promise<void> {
    await Voice.start(locale);
  }

  async stop(): Promise<void> {
    await Voice.stop();
  }

  async destroy(): Promise<void> {
    await Voice.destroy();
    Voice.removeAllListeners();
  }

  isAvailable(): boolean {
    return this._available;
  }
}

export const AndroidVoiceModule = new AndroidVoiceModuleImpl();

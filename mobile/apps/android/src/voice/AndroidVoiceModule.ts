/**
 * AndroidVoiceModule — @react-native-voice/voice Wrapper
 *
 * DONNA-15: Implements VoiceModuleInterface für Android.
 * Verwendet @react-native-voice/voice (benötigt yarn install).
 *
 * DATENSCHUTZ-HINWEIS: @react-native-voice/voice nutzt standardmäßig
 * die Google Speech Recognition API (cloud-basiert). Sprachdaten werden
 * an Google-Server übertragen. Für lokale Verarbeitung: Whisper-Integration
 * in Phase 5 (DONNA-7 LTM/Mood). Nutzer werden in der App informiert.
 *
 * Fallback: Wenn @react-native-voice/voice nicht verfügbar,
 * liefert isAvailable()=false ohne Crash.
 */
import {PermissionsAndroid, Platform} from 'react-native';
import type {VoiceModuleInterface, VoiceResult, VoiceError} from '@donna/shared';

// Lazy-Import um Build-Fehler zu vermeiden wenn Package fehlt
let Voice: typeof import('@react-native-voice/voice').default | null = null;
try {
  Voice = require('@react-native-voice/voice').default;
} catch {
  Voice = null;
}

/**
 * Prüft und fordert RECORD_AUDIO Permission an.
 * Gibt true zurück wenn Permission erteilt, false sonst.
 */
async function ensureMicrophonePermission(): Promise<boolean> {
  if (Platform.OS !== 'android') {return true;}

  try {
    const existing = await PermissionsAndroid.check(
      PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
    );
    if (existing) {return true;}

    const result = await PermissionsAndroid.request(
      PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
      {
        title: 'Mikrofon-Zugriff',
        message: 'Donna benötigt Zugriff auf das Mikrofon für die Sprachsteuerung.',
        buttonPositive: 'Erlauben',
        buttonNegative: 'Ablehnen',
      },
    );
    return result === PermissionsAndroid.RESULTS.GRANTED;
  } catch {
    return false;
  }
}

export class AndroidVoiceModule implements VoiceModuleInterface {
  onResult: ((result: VoiceResult) => void) | null = null;
  onError: ((error: VoiceError) => void) | null = null;
  onStart: (() => void) | null = null;
  onEnd: (() => void) | null = null;

  constructor() {
    if (Voice == null) {return;}

    Voice.onSpeechStart = () => this.onStart?.();
    Voice.onSpeechEnd = () => this.onEnd?.();

    Voice.onSpeechPartialResults = (e) => {
      const text = e.value?.[0] ?? '';
      if (text) {
        this.onResult?.({transcript: text, isFinal: false});
      }
    };

    Voice.onSpeechResults = (e) => {
      const text = e.value?.[0] ?? '';
      if (text) {
        this.onResult?.({transcript: text, isFinal: true});
      }
    };

    Voice.onSpeechError = (e) => {
      this.onError?.({
        code: String(e.error?.code ?? 'unknown'),
        message: e.error?.message ?? 'Spracherkennungsfehler',
      });
    };
  }

  isAvailable(): boolean {
    return Voice != null;
  }

  async start(locale = 'de-DE'): Promise<void> {
    if (Voice == null) {return;}

    // Runtime-Permission vor Start prüfen/anfordern
    const hasPermission = await ensureMicrophonePermission();
    if (!hasPermission) {
      this.onError?.({
        code: 'permission_denied',
        message: 'Mikrofon-Zugriff verweigert. Bitte in den Einstellungen erlauben.',
      });
      return;
    }

    await Voice.start(locale);
  }

  async stop(): Promise<void> {
    if (Voice == null) {return;}
    await Voice.stop();
  }

  async destroy(): Promise<void> {
    if (Voice == null) {return;}
    await Voice.destroy();
  }
}

// Singleton für App-weite Nutzung
export const androidVoiceModule = new AndroidVoiceModule();

// Voice Integration — Plattform-agnostische Typen
// DONNA-15: Android (@react-native-voice/voice) + Windows (WinRT SpeechRecognizer)

export type VoiceState =
  | 'idle'         // Bereit, noch nicht gestartet
  | 'listening'    // Aufnahme läuft
  | 'processing'   // Transkription läuft
  | 'error';       // Fehler aufgetreten

export interface VoiceResult {
  transcript: string;       // Erkannter Text
  confidence?: number;      // Konfidenz 0-1 (optional, plattformabhängig)
  isFinal: boolean;         // true = finale Erkennung, false = Partial-Result
}

export interface VoiceError {
  code: string;
  message: string;
}

export interface VoiceModuleInterface {
  /** Startet die Spracherkennung. */
  start(locale?: string): Promise<void>;
  /** Stoppt die Spracherkennung. */
  stop(): Promise<void>;
  /** Gibt an ob Spracherkennung verfügbar ist. */
  isAvailable(): boolean;
  /** Callback bei Partial-/Final-Result. */
  onResult: ((result: VoiceResult) => void) | null;
  /** Callback bei Fehler. */
  onError: ((error: VoiceError) => void) | null;
  /** Callback wenn Erkennung beginnt. */
  onStart: (() => void) | null;
  /** Callback wenn Erkennung endet. */
  onEnd: (() => void) | null;
  /** Ressourcen aufräumen. */
  destroy(): Promise<void>;
}

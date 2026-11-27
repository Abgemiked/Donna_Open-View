/**
 * ttsEngine — TypeScript-Wrapper für die A/B-Wahl der Sprachausgabe (Voice-Batch) und das Piper-Modell.
 *
 *  - 'edge'  : Edge-TTS über das Donna-Backend (POST /tts → audio/mpeg), Cloud, weibliche Stimme (Katja/Seraphina)
 *  - 'piper' : Piper on-device (sherpa-onnx, de_DE-kerstin-low, weiblich), offline
 *
 * Die Wahl liegt nativ in den SharedPreferences (PiperTTSModule.setTtsEngine) — dieselbe Quelle liest der Overlay
 * (VoiceInputActivity). Die jeweils andere Engine ist automatischer Fallback (siehe utils/ttsChain.ts).
 * Schlüssel/Werte identisch mit TtsEngines.kt.
 *
 * NativeModules wird bewusst erst beim Aufruf gelesen (nicht beim Import), damit Tests es pro Test setzen können.
 */
import {THEME_CHOICE_DEFAULT, normalizeThemeChoice, type ThemeChoice} from '../theme/themeChoice';
import {NativeModules, Platform} from 'react-native';

export type TtsEngine = 'edge' | 'piper';

/** Edge zuerst: beste Qualität; Piper-lokal springt bei Netzproblemen ein. */
export const TTS_ENGINE_DEFAULT: TtsEngine = 'edge';

export const EDGE_VOICE_KATJA = 'de-DE-KatjaNeural';
export const EDGE_VOICE_SERAPHINA = 'de-DE-SeraphinaMultilingualNeural';
export const EDGE_VOICE_DEFAULT = EDGE_VOICE_KATJA;
export const EDGE_VOICES: ReadonlyArray<{id: string; label: string}> = [
  {id: EDGE_VOICE_KATJA, label: 'Katja'},
  {id: EDGE_VOICE_SERAPHINA, label: 'Seraphina'},
];

/**
 * Sprechtempo (#115): Faktor je Engine, 1.0 = normales Tempo der Engine; die App bietet nur 1,225 / 1,3 / 1,4
 * (ältere Werte unter 1,225 rasten auf 1,225 ein). Stufen/Standard identisch mit SpeechTempo in
 * TtsEngines.kt. Der native Teil liest den Wert bei jedem Sprechen aus den Prefs (kein Neustart nötig).
 */
export const SPEECH_RATE_STEPS: ReadonlyArray<{value: number; label: string}> = [
  {value: 1.225, label: 'Langsam'},
  {value: 1.3, label: 'Mittel'},
  {value: 1.4, label: 'Schnell'},
];
export const SPEECH_RATE_DEFAULT = 1.225;

/**
 * Hörfenster nach der Antwort (#122): so lange hört Donna nach ihrer Antwort weiter zu, bevor das Gespräch ruhig endet.
 * Stufen/Standard identisch mit FollowUpWindow in ConversationLogic.kt; der native Teil liest den Wert bei JEDEM
 * Start des Weiterhörens aus den Prefs (kein Neustart nötig).
 */
export const FOLLOW_UP_WINDOW_STEPS: ReadonlyArray<{seconds: number; label: string}> = [
  {seconds: 6, label: '6 Sekunden'},
  {seconds: 10, label: '10 Sekunden'},
  {seconds: 15, label: '15 Sekunden'},
];
export const FOLLOW_UP_WINDOW_DEFAULT = 10;

/**
 * Sprechpause (#122 Paket C): so lange darf eine Pause beim Sprechen dauern, bevor Donna dich für fertig hält.
 * Schlüssel/Standard identisch mit SilenceProfile in OverlayStateMachine.kt (Standard „Lang“, damit Denkpausen nicht
 * abgeschnitten werden); der native Teil liest den Wert bei JEDEM Start des Zuhörens aus den Prefs.
 */
export type SilenceProfileKey = 'short' | 'normal' | 'long';
export const SILENCE_PROFILE_STEPS: ReadonlyArray<{key: SilenceProfileKey; label: string}> = [
  {key: 'short', label: 'Kurz'},
  {key: 'normal', label: 'Normal'},
  {key: 'long', label: 'Lang'},
];
export const SILENCE_PROFILE_DEFAULT: SilenceProfileKey = 'long';

/**
 * Empfindlichkeit „Unterbrechen per Stimme“ (#143): wie deutlich du reden musst, damit Donna stoppt.
 * Schlüssel/Standard identisch mit BargeInSensitivity in BargeInLogic.kt (Standard „Mittel“); eine native Pref, die
 * der Detektor bei JEDEM Start liest (Chat, Overlay, Blase). „Hoch“ entspricht dem Verhalten vor #143.
 */
export type BargeInSensitivityKey = 'low' | 'medium' | 'high';
export const BARGE_IN_SENSITIVITY_STEPS: ReadonlyArray<{key: BargeInSensitivityKey; label: string}> = [
  {key: 'low', label: 'Niedrig'},
  {key: 'medium', label: 'Mittel'},
  {key: 'high', label: 'Hoch'},
];
export const BARGE_IN_SENSITIVITY_DEFAULT: BargeInSensitivityKey = 'medium';

export const ENGINE_LABELS: Record<TtsEngine, string> = {
  edge: 'Edge (online)',
  piper: 'Piper (lokal)',
};

// ─── Reine Logik ──────────────────────────────────────────────────────────────

export function normalizeEngine(raw: unknown): TtsEngine {
  return raw === 'piper' ? 'piper' : 'edge';
}

export function otherEngine(engine: TtsEngine): TtsEngine {
  return normalizeEngine(engine) === 'piper' ? 'edge' : 'piper';
}

/** Reihenfolge der Versuche: gewählte Engine zuerst, dann die andere. */
export function engineOrder(pref: unknown): TtsEngine[] {
  const first = normalizeEngine(pref);
  return [first, otherEngine(first)];
}

export function normalizeEdgeVoice(raw: unknown): string {
  return EDGE_VOICES.some(v => v.id === raw) ? (raw as string) : EDGE_VOICE_DEFAULT;
}

/** Ungültig (null, NaN, ≤ 0, unendlich) → Standard; sonst nächste Stufe. */
export function normalizeSpeechRate(raw: unknown): number {
  if (typeof raw !== 'number' || !Number.isFinite(raw) || raw <= 0) {
    return SPEECH_RATE_DEFAULT;
  }
  return SPEECH_RATE_STEPS.reduce((best, s) => (Math.abs(s.value - raw) < Math.abs(best - raw) ? s.value : best), SPEECH_RATE_STEPS[0].value);
}

/** Ungültig (null, NaN, ≤ 0, unendlich) → Standard; sonst nächste Stufe (6 / 10 / 15 s). */
export function normalizeFollowUpWindow(raw: unknown): number {
  if (typeof raw !== 'number' || !Number.isFinite(raw) || raw <= 0) {
    return FOLLOW_UP_WINDOW_DEFAULT;
  }
  return FOLLOW_UP_WINDOW_STEPS.reduce(
    (best, s) => (Math.abs(s.seconds - raw) < Math.abs(best - raw) ? s.seconds : best),
    FOLLOW_UP_WINDOW_STEPS[0].seconds,
  );
}

/** Ungültig/fehlend → Standard „medium“; sonst der Schlüssel selbst. */
export function normalizeBargeInSensitivity(raw: unknown): BargeInSensitivityKey {
  return BARGE_IN_SENSITIVITY_STEPS.some(s => s.key === raw) ? (raw as BargeInSensitivityKey) : BARGE_IN_SENSITIVITY_DEFAULT;
}

/** Ungültig/fehlend → Standard „long“; sonst der Schlüssel selbst. */
export function normalizeSilenceProfile(raw: unknown): SilenceProfileKey {
  return SILENCE_PROFILE_STEPS.some(s => s.key === raw) ? (raw as SilenceProfileKey) : SILENCE_PROFILE_DEFAULT;
}

// ─── Native Brücke ────────────────────────────────────────────────────────────

export type PiperStatus = 'ready' | 'downloading' | 'not_downloaded' | 'unavailable';

export interface PiperState {
  status: PiperStatus;
  /** 0-100 */
  progress: number;
  /** Technische Fehlermeldung des letzten Downloads (nur fürs Log — der Nutzer sieht einen freundlichen Satz). */
  error: string | null;
  /** Auto-Start übersprungen, weil kein WLAN (gemessenes Netz): manuell mit „Jetzt laden“. */
  metered?: boolean;
}

interface PiperNative {
  getTtsEngine?(): Promise<string>;
  setTtsEngine?(engine: string): Promise<string>;
  getEdgeVoice?(): Promise<string>;
  setEdgeVoice?(voice: string): Promise<string>;
  getSpeechRate?(): Promise<number>;
  setSpeechRate?(rate: number): Promise<number>;
  getFollowUpWindow?(): Promise<number>;
  setFollowUpWindow?(seconds: number): Promise<number>;
  getSilenceProfile?(): Promise<string>;
  setSilenceProfile?(key: string): Promise<string>;
  getBargeInSensitivity?(): Promise<string>;
  setBargeInSensitivity?(key: string): Promise<string>;
  getVoiceBargeIn?(): Promise<boolean>;
  hasVoiceBargeIn?(): Promise<boolean>;
  setVoiceBargeIn?(enabled: boolean): Promise<boolean>;
  getFloatingBubble?(): Promise<boolean>;
  setFloatingBubble?(enabled: boolean): Promise<boolean>;
  getThemeMode?(): Promise<string>;
  setThemeMode?(choice: string): Promise<string>;
  getPiperState?(): Promise<{status?: string; progress?: number; error?: string | null}>;
  ensureModel?(force: boolean): Promise<string>;
}

function piper(): PiperNative | undefined {
  if (Platform.OS !== 'android') {
    return undefined;
  }
  return (NativeModules as {PiperTTS?: PiperNative}).PiperTTS;
}

/** Gibt es das native Piper-Modul (nur Android-App)? Ohne: kein A/B-Umschalter, kein Download. */
export function isPiperAvailable(): boolean {
  return piper() != null;
}

export async function getTtsEngine(): Promise<TtsEngine> {
  try {
    return normalizeEngine(await piper()?.getTtsEngine?.());
  } catch {
    return TTS_ENGINE_DEFAULT;
  }
}

export async function setTtsEngine(engine: TtsEngine): Promise<TtsEngine> {
  const wanted = normalizeEngine(engine);
  try {
    const applied = await piper()?.setTtsEngine?.(wanted);
    return applied == null ? wanted : normalizeEngine(applied);
  } catch {
    return wanted;
  }
}

export async function getEdgeVoice(): Promise<string> {
  try {
    return normalizeEdgeVoice(await piper()?.getEdgeVoice?.());
  } catch {
    return EDGE_VOICE_DEFAULT;
  }
}

export async function setEdgeVoice(voice: string): Promise<string> {
  const wanted = normalizeEdgeVoice(voice);
  try {
    const applied = await piper()?.setEdgeVoice?.(wanted);
    return applied == null ? wanted : normalizeEdgeVoice(applied);
  } catch {
    return wanted;
  }
}

export async function getSpeechRate(): Promise<number> {
  try {
    return normalizeSpeechRate(await piper()?.getSpeechRate?.());
  } catch {
    return SPEECH_RATE_DEFAULT;
  }
}

export async function setSpeechRate(rate: number): Promise<number> {
  const wanted = normalizeSpeechRate(rate);
  try {
    const applied = await piper()?.setSpeechRate?.(wanted);
    return applied == null ? wanted : normalizeSpeechRate(applied);
  } catch {
    return wanted;
  }
}

export async function getFollowUpWindow(): Promise<number> {
  try {
    return normalizeFollowUpWindow(await piper()?.getFollowUpWindow?.());
  } catch {
    return FOLLOW_UP_WINDOW_DEFAULT;
  }
}

export async function setFollowUpWindow(seconds: number): Promise<number> {
  const wanted = normalizeFollowUpWindow(seconds);
  try {
    const applied = await piper()?.setFollowUpWindow?.(wanted);
    return applied == null ? wanted : normalizeFollowUpWindow(applied);
  } catch {
    return wanted;
  }
}

export async function getSilenceProfile(): Promise<SilenceProfileKey> {
  try {
    return normalizeSilenceProfile(await piper()?.getSilenceProfile?.());
  } catch {
    return SILENCE_PROFILE_DEFAULT;
  }
}

export async function setSilenceProfile(key: SilenceProfileKey): Promise<SilenceProfileKey> {
  const wanted = normalizeSilenceProfile(key);
  try {
    const applied = await piper()?.setSilenceProfile?.(wanted);
    return applied == null ? wanted : normalizeSilenceProfile(applied);
  } catch {
    return wanted;
  }
}

export async function getBargeInSensitivity(): Promise<BargeInSensitivityKey> {
  try {
    return normalizeBargeInSensitivity(await piper()?.getBargeInSensitivity?.());
  } catch {
    return BARGE_IN_SENSITIVITY_DEFAULT;
  }
}

export async function setBargeInSensitivity(key: BargeInSensitivityKey): Promise<BargeInSensitivityKey> {
  const wanted = normalizeBargeInSensitivity(key);
  try {
    const applied = await piper()?.setBargeInSensitivity?.(wanted);
    return applied == null ? wanted : normalizeBargeInSensitivity(applied);
  } catch {
    return wanted;
  }
}

/** Schwebende Blase (#133): Standard AUS — identisch mit FloatingBubbleSetting.DEFAULT in BubbleLogic.kt. */
export const FLOATING_BUBBLE_DEFAULT = false;

export async function getFloatingBubble(): Promise<boolean> {
  try {
    return (await piper()?.getFloatingBubble?.()) === true;
  } catch {
    return FLOATING_BUBBLE_DEFAULT;
  }
}

export async function setFloatingBubble(enabled: boolean): Promise<boolean> {
  try {
    const applied = await piper()?.setFloatingBubble?.(enabled);
    return applied == null ? enabled : applied === true;
  } catch {
    return enabled;
  }
}

/**
 * Darstellung Hell/Dunkel/System (#134 P1): EINE Quelle der Wahrheit ist die native Pref `theme_mode`
 * (ThemeChoice.kt). Fehlende/veraltete native Methode oder Fehler -> "system".
 */
export async function getThemeMode(): Promise<ThemeChoice> {
  try {
    return normalizeThemeChoice(await piper()?.getThemeMode?.());
  } catch {
    return THEME_CHOICE_DEFAULT;
  }
}

/** Gibt es die native Darstellungs-Pref ueberhaupt? (synchron; Grundlage fuer das ready-Kennzeichen im ThemeProvider) */
export function hasNativeThemeMode(): boolean {
  return typeof piper()?.getThemeMode === 'function';
}

export async function setThemeMode(choice: ThemeChoice): Promise<ThemeChoice> {
  try {
    const applied = await piper()?.setThemeMode?.(choice);
    return applied == null ? choice : normalizeThemeChoice(applied);
  } catch {
    return choice;
  }
}

/** Natives Modul für „Unterbrechen per Stimme“ vorhanden? (Quelle der Wahrheit: voiceBargeIn.ts) */
export function hasNativeVoiceBargeIn(): boolean {
  const m = piper();
  return m?.getVoiceBargeIn != null && m?.setVoiceBargeIn != null;
}

/** Ist der Wert nativ schon ausdruecklich gespeichert? (kann werfen; ohne die Methode: ja, nichts ueberschreiben) */
export async function nativeVoiceBargeInIsSet(): Promise<boolean> {
  const m = piper();
  return m?.hasVoiceBargeIn ? (await m.hasVoiceBargeIn()) === true : true;
}

/** Roher nativer Wert (kann werfen). Nur voiceBargeIn.ts nutzt das — dort stehen Übernahme und Ersatz. */
export async function readNativeVoiceBargeIn(): Promise<boolean> {
  return (await piper()!.getVoiceBargeIn!()) !== false;
}

/** Roher nativer Schreibzugriff (kann werfen). */
export async function writeNativeVoiceBargeIn(enabled: boolean): Promise<boolean> {
  return (await piper()!.setVoiceBargeIn!(enabled)) !== false;
}

export async function getPiperState(): Promise<PiperState> {
  const mod = piper();
  if (!mod?.getPiperState) {
    return {status: 'unavailable', progress: 0, error: null};
  }
  try {
    const s = await mod.getPiperState();
    const status: PiperStatus =
      s?.status === 'ready' || s?.status === 'downloading' ? s.status : 'not_downloaded';
    return {
      status,
      progress: Math.max(0, Math.min(100, Math.round(s?.progress ?? 0))),
      error: s?.error ?? null,
    };
  } catch {
    return {status: 'unavailable', progress: 0, error: null};
  }
}

export type EnsureResult = 'ready' | 'downloading' | 'started' | 'metered' | 'unavailable';

/**
 * EAGER-Download des Piper-Modells (App-Start / Onboarding / Einstellungen): startet den Download, wenn das
 * Modell fehlt, und kehrt sofort zurück. Fortschritt: Events `PiperTTS.downloadProgress|downloadComplete|downloadError`
 * (siehe hooks/usePiperModel.ts). Idempotent.
 *
 * `force` = false (Auto-Start): nur im ungemessenen Netz (WLAN), sonst Ergebnis 'metered' — kein stiller
 * 64-MB-Download über Mobilfunk. `force` = true („Jetzt laden“ / „Erneut versuchen“): immer.
 */
export async function ensurePiperModel(force = false): Promise<EnsureResult> {
  const mod = piper();
  if (!mod?.ensureModel) {
    return 'unavailable';
  }
  try {
    const r = await mod.ensureModel(force);
    return r === 'ready' || r === 'downloading' || r === 'started' || r === 'metered' ? r : 'started';
  } catch {
    return 'unavailable';
  }
}

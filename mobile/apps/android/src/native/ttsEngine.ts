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

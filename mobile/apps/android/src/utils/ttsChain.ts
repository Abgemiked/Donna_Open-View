/**
 * ttsChain — Sprachausgabe-Kette des Haupt-Chats (Voice-Batch, A/B Edge ↔ Piper-lokal).
 *
 * Reihenfolge: gewählte Engine → andere Engine → System-TTS (Samsung Neural). Jede Stufe meldet per Promise, ob
 * sie WIRKLICH spricht ("ok"); alles andere ("fallback"/"failed"/Ablehnung/kein Modul) führt zur nächsten Stufe.
 * Nach einer Unterbrechung (Stopp, Barge-in, neue Frage) darf keine weitere Stufe mehr starten — `isStale`
 * bzw. das native Ergebnis "cancelled" (die unterbrochene Stufe löst ihr Promise auf, statt ewig zu hängen).
 *
 * Reine Logik ohne React → testbar (src/__tests__/ttsChain.test.ts).
 */
import {engineOrder, type TtsEngine} from '../native/ttsEngine';

/** Ergebnis: welche Stufe gesprochen hat ('none' = keine; 'stale' = zwischendurch unterbrochen). */
export type SpokenVia = TtsEngine | 'system' | 'none' | 'stale';

type PromiseLike2 = Promise<unknown> | unknown;

export interface TtsNatives {
  PiperTTS?: {
    speakPiper?: (text: string) => PromiseLike2;
  };
  TTSModule?: {
    /** Edge-TTS über das Backend; löst "ok" | "live_guard" | "empty" | "failed" auf. Ohne System-TTS-Fallback. */
    speakViaServer?: (text: string, voice: string) => PromiseLike2;
    /** Legacy (älterer nativer Build): Server-TTS MIT eingebautem System-TTS-Fallback. */
    speakViaKokoro?: (text: string) => PromiseLike2;
    isOnDeviceReady?: () => PromiseLike2;
    speakOnDevice?: (text: string) => PromiseLike2;
  };
}

async function call(fn: () => PromiseLike2): Promise<unknown> {
  try {
    return await fn();
  } catch {
    return undefined;
  }
}

/** "Hat gesprochen" bzw. bewusst still (nichts Sprechbares / Live-Guard) — keine weitere Stufe nötig. */
function handled(result: unknown): boolean {
  return result === 'ok' || result === 'live_guard' || result === 'empty';
}

export interface SpeakOptions {
  /** Edge-Stimme (z. B. de-DE-KatjaNeural). */
  voice: string;
  /** true = die Antwort gehört einer unterbrochenen Frage → nichts mehr starten. */
  isStale?: () => boolean;
}

export async function speakWithFallback(
  text: string,
  pref: unknown,
  natives: TtsNatives,
  {voice, isStale = () => false}: SpeakOptions,
): Promise<SpokenVia> {
  for (const engine of engineOrder(pref)) {
    if (isStale()) {
      return 'stale';
    }
    if (engine === 'piper') {
      const r = await call(() => natives.PiperTTS?.speakPiper?.(text));
      if (r === 'cancelled') {
        return 'stale';
      }
      if (handled(r)) {
        return 'piper';
      }
    } else {
      const tts = natives.TTSModule;
      if (tts?.speakViaServer) {
        const r = await call(() => tts.speakViaServer?.(text, voice));
        if (r === 'cancelled') {
          return 'stale';
        }
        if (handled(r)) {
          return 'edge';
        }
      } else if (tts?.speakViaKokoro) {
        // Älterer nativer Build ohne speakViaServer: dessen Server-Pfad spricht bei Fehlern selbst per System-TTS
        const r = await call(() => tts.speakViaKokoro?.(text));
        if (r === 'cancelled') {
          return 'stale';
        }
        if (handled(r)) {
          return 'edge';
        }
        if (r === 'fallback') {
          return 'system';
        }
      }
    }
  }
  if (isStale()) {
    return 'stale';
  }
  // Letzte Stufe: System-TTS (kein Netz, ~60 ms)
  const tts = natives.TTSModule;
  const ready = await call(() => tts?.isOnDeviceReady?.());
  if (ready === true) {
    const r = await call(() => tts?.speakOnDevice?.(text));
    if (r === 'cancelled') {
      return 'stale';
    }
    if (handled(r)) {
      return 'system';
    }
  }
  return 'none';
}

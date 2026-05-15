/**
 * useVoice — DONNA-33 Voice-Input-Hook (Windows/Overlay)
 *
 * Pipeline:
 *   start() → VAD (Silero-VAD WASM) lauscht auf Mikrofon
 *   VAD onSpeechEnd(Float32Array) → float32→WAV in-memory → /speech/transcribe
 *   transcript gesetzt → isListening = false
 *
 * Kein MediaRecorder! Float32Array direkt von der VAD-Bibliothek:
 * - 16kHz Mono, bereits auf den Speech-Segment getrimmt
 * - WAV-Header trivial zu konstruieren, kein Container-Problem
 * - Keine EBML/matroska-Fehler mehr
 *
 * Manueller stop() pausiert VAD und setzt isListening = false.
 */

import { useState, useRef, useCallback, useEffect } from 'react';

// ─── Typen ────────────────────────────────────────────────────────────────────

export interface UseVoiceReturn {
  isListening: boolean;
  transcript: string;
  error: string | null;
  start: () => void;
  stop: () => void;
  supported: boolean;
}

// ─── Konstanten ───────────────────────────────────────────────────────────────

const API_BASE = 'https://your-donna-instance.example.com';

// DONNA-103: Token dynamisch aus dem Shared-API-Modul (kein Hardcode mehr).
// Wird per IPC vom Main-Prozess in shared/api/index.ts gesetzt (setApiToken).
import { getApiToken } from '@donna/shared';

// VAD-Samplerate — Silero-VAD arbeitet intern mit 16kHz
const VAD_SAMPLE_RATE = 16000;

// Mindest-Audio-Länge: 0.3 Sekunden bei 16kHz = 4800 Samples
const MIN_SAMPLES = VAD_SAMPLE_RATE * 0.3;

// ─── Lazy VAD-Import ──────────────────────────────────────────────────────────

interface MicVAD {
  start: () => void;
  pause: () => void;
  destroy: () => void;
}

interface MicVADOptions {
  onSpeechStart?: () => void;
  onSpeechEnd?: (audio: Float32Array) => void;
  onVADMisfire?: () => void;
  positiveSpeechThreshold?: number;
  negativeSpeechThreshold?: number;
  minSpeechFrames?: number;
  redemptionFrames?: number;
  // DONNA-72: lokale Asset-Pfade verhindern CDN-Roundtrip
  baseAssetPath?: string;
  ortConfig?: (ort: unknown) => void;  // vad-web nimmt Callback der ort.env.wasm.wasmPaths setzt
}

interface MicVADStatic {
  new: (options: MicVADOptions) => Promise<MicVAD>;
}

async function loadVAD(): Promise<MicVADStatic> {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const mod = await import('@ricky0123/vad-web' as any);
  return (mod.MicVAD ?? mod.default?.MicVAD) as MicVADStatic;
}

// ─── Float32Array → WAV-Blob ──────────────────────────────────────────────────

function float32ToWav(samples: Float32Array, sampleRate: number): Blob {
  const numSamples = samples.length;
  const bytesPerSample = 2; // 16-bit PCM
  const blockAlign = bytesPerSample; // mono
  const byteRate = sampleRate * blockAlign;
  const dataSize = numSamples * bytesPerSample;

  const buf = new ArrayBuffer(44 + dataSize);
  const view = new DataView(buf);

  // RIFF chunk
  view.setUint8(0, 0x52); view.setUint8(1, 0x49); view.setUint8(2, 0x46); view.setUint8(3, 0x46); // "RIFF"
  view.setUint32(4, 36 + dataSize, true);
  view.setUint8(8, 0x57); view.setUint8(9, 0x41); view.setUint8(10, 0x56); view.setUint8(11, 0x45); // "WAVE"

  // fmt chunk
  view.setUint8(12, 0x66); view.setUint8(13, 0x6d); view.setUint8(14, 0x74); view.setUint8(15, 0x20); // "fmt "
  view.setUint32(16, 16, true);       // chunk size
  view.setUint16(20, 1, true);        // PCM = 1
  view.setUint16(22, 1, true);        // Mono
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, byteRate, true);
  view.setUint16(32, blockAlign, true);
  view.setUint16(34, 16, true);       // 16 bit

  // data chunk
  view.setUint8(36, 0x64); view.setUint8(37, 0x61); view.setUint8(38, 0x74); view.setUint8(39, 0x61); // "data"
  view.setUint32(40, dataSize, true);

  // Float32 → Int16
  let offset = 44;
  for (let i = 0; i < numSamples; i++) {
    const s = Math.max(-1.0, Math.min(1.0, samples[i]));
    view.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7fff, true);
    offset += 2;
  }

  return new Blob([buf], { type: 'audio/wav' });
}

// ─── Backend-Transkription ────────────────────────────────────────────────────

async function transcribeWav(wavBlob: Blob): Promise<string> {
  const form = new FormData();
  form.append('audio', new File([wavBlob], 'audio.wav', { type: 'audio/wav' }));

  const res = await fetch(`${API_BASE}/speech/transcribe`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${getApiToken() ?? ''}` },
    body: form,
  });

  if (!res.ok) {
    const detail = await res.json().catch(() => ({ detail: `HTTP ${res.status}` }));
    throw new Error((detail as { detail?: string }).detail ?? `HTTP ${res.status}`);
  }

  const data = await res.json() as { text: string };
  return data.text ?? '';
}

// ─── Hook ─────────────────────────────────────────────────────────────────────

export function useVoice(): UseVoiceReturn {
  const [isListening, setIsListening] = useState(false);
  const [transcript, setTranscript] = useState('');
  const [error, setError] = useState<string | null>(null);

  const vadRef = useRef<MicVAD | null>(null);
  const isListeningRef = useRef(false);

  // getUserMedia muss verfügbar sein
  const supported = typeof navigator?.mediaDevices?.getUserMedia === 'function';

  // ── Cleanup bei Unmount ───────────────────────────────────────────────────

  useEffect(() => {
    return () => {
      vadRef.current?.destroy();
      vadRef.current = null;
    };
  }, []);

  // ── Stop ─────────────────────────────────────────────────────────────────

  const stop = useCallback(() => {
    isListeningRef.current = false;
    setIsListening(false);
    try { vadRef.current?.pause(); } catch { /* ignore */ }
  }, []);

  // ── Speech-End Handler (VAD liefert Float32Array, 16kHz Mono) ────────────

  const handleSpeechEnd = useCallback(async (audio: Float32Array) => {
    if (!isListeningRef.current) return;

    // Zu kurze Aufnahme überspringen
    if (audio.length < MIN_SAMPLES) {
      console.debug('[useVoice] Audio zu kurz:', audio.length, 'samples');
      setError('Zu kurz gesprochen — bitte länger sprechen');
      isListeningRef.current = false;
      setIsListening(false);
      return;
    }

    isListeningRef.current = false;
    setIsListening(false);
    try { vadRef.current?.pause(); } catch { /* ignore */ }

    console.debug('[useVoice] Speech-End:', audio.length, 'samples →', (audio.length / VAD_SAMPLE_RATE).toFixed(2), 's');

    try {
      const wav = float32ToWav(audio, VAD_SAMPLE_RATE);
      console.debug('[useVoice] WAV-Blob:', wav.size, 'bytes');
      const text = await transcribeWav(wav);
      if (text) {
        setTranscript(text);
        console.debug('[useVoice] Transkription:', text);
      } else {
        setError('Kein Text erkannt — bitte deutlicher sprechen');
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      console.error('[useVoice] Transkriptionsfehler:', msg);
      setError(msg);
    }
  }, []);

  // ── Start ─────────────────────────────────────────────────────────────────

  const start = useCallback(async () => {
    if (!supported || isListeningRef.current) return;

    setError(null);
    setTranscript('');
    isListeningRef.current = true;
    setIsListening(true);

    try {
      if (!vadRef.current) {
        const MicVAD = await loadVAD();
        // DONNA-72: VAD-Modell + ONNX-Runtime aus lokalem public/-Verzeichnis
        // statt cdn.jsdelivr.net laden — eliminiert Netzwerk-Roundtrip beim
        // ersten Mic-Klick. Path-Prefix abhängig vom Renderer-Context:
        // - Main-App: out/renderer/index.html → './' = out/renderer/
        // - Overlay:  out/renderer/overlay/index.html → '../' = out/renderer/
        const isOverlay = typeof window !== 'undefined'
          && window.location.pathname.includes('/overlay/');
        const assetPath = isOverlay ? '../' : './';
        vadRef.current = await MicVAD.new({
          baseAssetPath: assetPath,
          // ortConfig: vad-web ruft das mit ort-Object auf
          // - wasmPaths: lokale ONNX-Runtime statt CDN
          // - numThreads = 1: vermeidet SharedArrayBuffer (braucht COOP/COEP-Header
          //   die Electron file:// nicht hat → SAB blockiert → Init schlägt fehl)
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          ortConfig: ((ort: any) => {
            ort.env.wasm.wasmPaths = assetPath;
            ort.env.wasm.numThreads = 1;
          }) as any,
          onSpeechStart: () => {
            console.debug('[useVoice] VAD: Sprache erkannt');
          },
          onSpeechEnd: (audio: Float32Array) => {
            handleSpeechEnd(audio);
          },
          onVADMisfire: () => {
            console.debug('[useVoice] VAD: Fehlalarm — ignoriert');
          },
          // Feintuning
          positiveSpeechThreshold: 0.6,
          negativeSpeechThreshold: 0.35,
          minSpeechFrames: 5,      // mind. ~160 ms Sprache
          redemptionFrames: 10,    // ~330 ms Stille bevor onSpeechEnd
        });
      }

      vadRef.current.start();
      console.debug('[useVoice] VAD gestartet (kein MediaRecorder — Float32Array direkt)');

    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      console.error('[useVoice] Start-Fehler:', msg);
      setError(msg);
      isListeningRef.current = false;
      setIsListening(false);
    }
  }, [supported, handleSpeechEnd]);

  // ── Nicht-unterstützte Umgebung ───────────────────────────────────────────

  if (!supported) {
    return {
      isListening: false,
      transcript: '',
      error: 'getUserMedia nicht verfügbar',
      start: () => {},
      stop: () => {},
      supported: false,
    };
  }

  return { isListening, transcript, error, start, stop, supported };
}

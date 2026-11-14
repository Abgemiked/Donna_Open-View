/**
 * useTTS — DONNA-35 TTS-Output bei Voice-Input (Windows/Overlay)
 *
 * Ablauf:
 *   speak(text) → POST /tts {text, was_voice_input: true}
 *   → Opus-Blob → HTML5 Audio.play()
 *   → 204 (Live-Guard aktiv) → silent no-op
 *   → vorherige Wiedergabe wird automatisch abgebrochen
 *
 * TTS-Toggle: ttsEnabled (localStorage 'donna_tts_enabled', default true)
 *   speak() ist no-op wenn ttsEnabled=false.
 */

import { useState, useRef, useCallback, useEffect } from 'react';

// ─── Konstanten ───────────────────────────────────────────────────────────────

const API_BASE = 'https://your-donna-instance.example.com';
const API_TOKEN = 'YOUR_ADMIN_TOKEN_HERE';
const LS_KEY = 'donna_tts_enabled';

// ─── Typen ────────────────────────────────────────────────────────────────────

export interface UseTTSReturn {
  speak: (text: string) => Promise<void>;
  stop: () => void;
  ttsEnabled: boolean;
  setTtsEnabled: (enabled: boolean) => void;
}

// ─── Hook ─────────────────────────────────────────────────────────────────────

export function useTTS(): UseTTSReturn {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  // TTS-Toggle — aus localStorage laden (default: true)
  const [ttsEnabled, setTtsEnabledState] = useState<boolean>(() => {
    try {
      const stored = localStorage.getItem(LS_KEY);
      return stored === null ? true : stored === 'true';
    } catch {
      return true;
    }
  });

  const setTtsEnabled = useCallback((enabled: boolean) => {
    setTtsEnabledState(enabled);
    try {
      localStorage.setItem(LS_KEY, String(enabled));
    } catch {
      /* storage nicht verfügbar — ignorieren */
    }
    if (!enabled) {
      // Laufende Wiedergabe stoppen wenn TTS deaktiviert wird
      abortRef.current?.abort();
      if (audioRef.current) {
        audioRef.current.pause();
        audioRef.current = null;
      }
    }
  }, []);

  // Cleanup bei Unmount
  useEffect(() => {
    return () => {
      abortRef.current?.abort();
      if (audioRef.current) {
        audioRef.current.pause();
        audioRef.current = null;
      }
    };
  }, []);

  const stop = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    if (audioRef.current) {
      audioRef.current.pause();
      audioRef.current = null;
    }
  }, []);

  const speak = useCallback(async (text: string) => {
    if (!ttsEnabled) return;
    if (!text.trim()) return;

    // Vorherige Wiedergabe abbrechen
    abortRef.current?.abort();
    if (audioRef.current) {
      audioRef.current.pause();
      audioRef.current = null;
    }

    const ctrl = new AbortController();
    abortRef.current = ctrl;

    let objectUrl: string | null = null;
    try {
      const resp = await fetch(`${API_BASE}/tts`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${API_TOKEN}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ text, was_voice_input: true }),
        signal: ctrl.signal,
      });

      // 204 = Live-Guard aktiv (Twitch live) → kein Audio
      if (!resp.ok || resp.status === 204) {
        console.debug('[useTTS] TTS-Antwort ignoriert:', resp.status);
        return;
      }

      const blob = await resp.blob();
      if (blob.size === 0) return;

      objectUrl = URL.createObjectURL(blob);
      const audio = new Audio(objectUrl);
      audioRef.current = audio;

      audio.onended = () => {
        if (objectUrl) URL.revokeObjectURL(objectUrl);
        objectUrl = null;
        audioRef.current = null;
      };

      audio.onerror = () => {
        if (objectUrl) URL.revokeObjectURL(objectUrl);
        objectUrl = null;
        audioRef.current = null;
        console.warn('[useTTS] Audio-Wiedergabe fehlgeschlagen');
      };

      await audio.play();
    } catch (e) {
      if ((e as Error).name === 'AbortError') return;
      console.warn('[useTTS] TTS fehlgeschlagen', e);
      if (objectUrl) {
        URL.revokeObjectURL(objectUrl);
      }
    }
  }, [ttsEnabled]);

  return { speak, stop, ttsEnabled, setTtsEnabled };
}

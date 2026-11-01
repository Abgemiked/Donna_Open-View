/**
 * usePiperModel — Zustand des Piper-Sprachmodells (de_DE-kerstin-low) für Banner und Einstellungen.
 *
 * `autoStart`: EAGER-Download beim Einhängen (App-Start / Onboarding) — nicht erst beim ersten Sprechen, aber nur
 * im ungemessenen Netz (WLAN). Bei Mobilfunk bleibt `metered` gesetzt und die Oberfläche bietet „Jetzt laden“ an.
 * Fortschritt kommt über die nativen Events `PiperTTS.downloadProgress | downloadComplete | downloadError`.
 * Technische Fehlertexte gehen nur ins Log; `error` bleibt für Anzeige-Entscheidungen erhalten.
 */
import {useCallback, useEffect, useRef, useState} from 'react';
import {DeviceEventEmitter} from 'react-native';
import {ensurePiperModel, getPiperState, type PiperState} from '../native/ttsEngine';

const INITIAL: PiperState = {status: 'unavailable', progress: 0, error: null, metered: false};

export function usePiperModel(autoStart: boolean): PiperState & {retry: () => void; available: boolean} {
  const [state, setState] = useState<PiperState>(INITIAL);
  const mounted = useRef(true);

  const safeSet = useCallback((next: (prev: PiperState) => PiperState) => {
    if (mounted.current) {
      setState(next);
    }
  }, []);

  /** force = true: bewusste Nutzeraktion („Jetzt laden“, „Erneut versuchen“) — auch über Mobilfunk. */
  const start = useCallback(
    async (force: boolean) => {
      const r = await ensurePiperModel(force);
      if (r === 'started' || r === 'downloading') {
        safeSet(prev => ({
          status: 'downloading',
          progress: prev.status === 'downloading' ? prev.progress : 0,
          error: null,
          metered: false,
        }));
      } else if (r === 'ready') {
        safeSet(() => ({status: 'ready', progress: 100, error: null, metered: false}));
      } else if (r === 'metered') {
        safeSet(prev => ({...prev, status: 'not_downloaded', progress: 0, metered: true}));
      }
    },
    [safeSet],
  );

  useEffect(() => {
    mounted.current = true;
    void getPiperState().then(s => {
      safeSet(() => s);
      // Ohne natives Modul liefert ensurePiperModel() 'unavailable' und tut nichts
      if (autoStart && s.status !== 'ready') {
        void start(false);
      }
    });
    const progressSub = DeviceEventEmitter.addListener('PiperTTS.downloadProgress', (e: {progress?: number}) => {
      safeSet(() => ({
        status: 'downloading',
        progress: Math.max(0, Math.min(100, e?.progress ?? 0)),
        error: null,
        metered: false,
      }));
    });
    const doneSub = DeviceEventEmitter.addListener('PiperTTS.downloadComplete', () => {
      safeSet(() => ({status: 'ready', progress: 100, error: null, metered: false}));
    });
    const errorSub = DeviceEventEmitter.addListener('PiperTTS.downloadError', (e: {message?: string}) => {
      // Technik nur ins Log, der Nutzer bekommt einen freundlichen Satz
      console.warn('[Piper] Download fehlgeschlagen:', e?.message);
      safeSet(() => ({status: 'not_downloaded', progress: 0, error: e?.message ?? 'Download fehlgeschlagen', metered: false}));
    });
    return () => {
      mounted.current = false;
      progressSub.remove();
      doneSub.remove();
      errorSub.remove();
    };
  }, [autoStart, safeSet, start]);

  const retry = useCallback(() => {
    safeSet(prev => ({...prev, error: null}));
    void start(true);
  }, [safeSet, start]);

  return {...state, retry, available: state.status !== 'unavailable'};
}

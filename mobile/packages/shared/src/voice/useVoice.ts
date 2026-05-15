// useVoice — Plattform-agnostischer Voice Hook
// Erwartet eine VoiceModuleInterface-Implementierung via Parameter.
// Android → AndroidVoiceModule, Windows → WindowsVoiceModule

import {useState, useEffect, useCallback, useRef} from 'react';
import type {VoiceState, VoiceResult, VoiceError, VoiceModuleInterface} from './types';

export interface UseVoiceOptions {
  module: VoiceModuleInterface;
  locale?: string;
  onTranscript?: (text: string) => void;
}

export interface UseVoiceReturn {
  voiceState: VoiceState;
  transcript: string;
  partialTranscript: string;
  startListening: () => Promise<void>;
  stopListening: () => Promise<void>;
  isAvailable: boolean;
  error: VoiceError | null;
}

export function useVoice({module, locale = 'de-DE', onTranscript}: UseVoiceOptions): UseVoiceReturn {
  const [voiceState, setVoiceState] = useState<VoiceState>('idle');
  const [transcript, setTranscript] = useState('');
  const [partialTranscript, setPartialTranscript] = useState('');
  const [error, setError] = useState<VoiceError | null>(null);

  // Ref für onTranscript-Callback (kein Stale-Closure)
  const onTranscriptRef = useRef(onTranscript);
  onTranscriptRef.current = onTranscript;

  useEffect(() => {
    // Callbacks verdrahten
    module.onStart = () => {
      setVoiceState('listening');
      setPartialTranscript('');
      setError(null);
    };

    module.onEnd = () => {
      setVoiceState('idle');
      setPartialTranscript('');
    };

    module.onResult = (result: VoiceResult) => {
      if (result.isFinal) {
        setTranscript(result.transcript);
        setPartialTranscript('');
        setVoiceState('idle');
        onTranscriptRef.current?.(result.transcript);
      } else {
        setPartialTranscript(result.transcript);
      }
    };

    module.onError = (err: VoiceError) => {
      setError(err);
      setVoiceState('error');
      setPartialTranscript('');
    };

    return () => {
      module.onStart = null;
      module.onEnd = null;
      module.onResult = null;
      module.onError = null;
      module.destroy().catch(() => {});
    };
  }, [module]);

  const startListening = useCallback(async () => {
    if (voiceState === 'listening') {return;}
    setError(null);
    setTranscript('');
    try {
      await module.start(locale);
    } catch (err) {
      setError({code: 'start_failed', message: String(err)});
      setVoiceState('error');
    }
  }, [module, locale, voiceState]);

  const stopListening = useCallback(async () => {
    if (voiceState !== 'listening') {return;}
    try {
      await module.stop();
    } catch (err) {
      setError({code: 'stop_failed', message: String(err)});
    }
  }, [module, voiceState]);

  return {
    voiceState,
    transcript,
    partialTranscript,
    startListening,
    stopListening,
    isAvailable: module.isAvailable(),
    error,
  };
}

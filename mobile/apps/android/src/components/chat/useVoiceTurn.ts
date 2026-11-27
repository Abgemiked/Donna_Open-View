import {useCallback, useEffect, useState} from 'react';

/** So lange darf es voellig ruhig sein (nichts hoert/denkt/spricht), bevor der Sprachzug als beendet gilt. */
export const VOICE_TURN_IDLE_RESET_MS = 3_000;

export interface VoiceTurnInputs {
  isListening: boolean;
  isLoading: boolean;
  ttsActive: boolean;
}

/**
 * Reaktiver Zustand "Sprachzug laeuft" (#134 P5): wird beim Zuhoeren gesetzt und bleibt ueber Denken und Sprechen
 * bestehen (auch wenn die Antwort eintrifft, bevor Donna spricht). Zurueckgesetzt wird er
 *  - sofort per `reset()` (Nutzer tippt, sendet, drueckt Stopp),
 *  - nach {@link VOICE_TURN_IDLE_RESET_MS} voelliger Ruhe (TTS-Ende ohne Weiterhoeren, Gespraechsende, Fehler, Abbruch).
 * Die kurze Luecke zwischen Hoeren und Denken bzw. Sprechen und Wieder-Zuhoeren setzt ihn damit nie zurueck.
 */
export function useVoiceTurn(i: VoiceTurnInputs): [boolean, () => void] {
  const [voiceTurn, setVoiceTurn] = useState(false);
  const busy = i.isListening || i.isLoading || i.ttsActive;

  useEffect(() => {
    if (i.isListening) {
      setVoiceTurn(true);
    }
  }, [i.isListening]);

  useEffect(() => {
    if (busy) {
      return undefined;
    }
    const t = setTimeout(() => setVoiceTurn(false), VOICE_TURN_IDLE_RESET_MS);
    return () => clearTimeout(t);
  }, [busy]);

  const reset = useCallback(() => setVoiceTurn(false), []);
  return [voiceTurn, reset];
}

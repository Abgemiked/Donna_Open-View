import {useEffect, useRef, useState} from 'react';

/** Nachlaufzeit der Gespraechsansicht in ms (deckt Echo-Schutz und Wieder-Zuhoeren ab). */
export const CONVERSATION_VIEW_GRACE_MS = 1_500;

/**
 * Liefert true, solange `value` true ist, und noch `ms` Millisekunden danach. Verhindert, dass die Gespraechsansicht
 * in der kurzen Luecke zwischen Sprechen und Wieder-Zuhoeren flackert; endet das Gespraech (Ende-Wort, Zeitablauf,
 * Fehler, Hintergrund), verschwindet sie nach der Nachlaufzeit von selbst, weil alles aus dem Zustand abgeleitet ist.
 */
export function useHeldTrue(value: boolean, ms: number): boolean {
  const [held, setHeld] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    if (timer.current) {
      clearTimeout(timer.current);
      timer.current = null;
    }
    if (value) {
      setHeld(true);
    } else {
      timer.current = setTimeout(() => setHeld(false), ms);
    }
    return () => {
      if (timer.current) {
        clearTimeout(timer.current);
        timer.current = null;
      }
    };
  }, [value, ms]);
  return value || held;
}

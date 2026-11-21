import {useEffect, useState} from 'react';
import {AccessibilityInfo} from 'react-native';

/**
 * "Animationen entfernen" (Android-Bedienungshilfen) -> true. Entspricht `ValueAnimator.areAnimatorsEnabled() == false`
 * im Overlay. `null` = noch nicht bekannt: Aufrufer starten dann KEINE Animation (sonst liefe vor dem Ergebnis kurz eine
 * Schleife an, obwohl der Nutzer Bewegung abgeschaltet hat). Aenderungen zur Laufzeit werden uebernommen.
 */
export function useReduceMotion(): boolean | null {
  const [reduce, setReduce] = useState<boolean | null>(null);
  useEffect(() => {
    let mounted = true;
    const pending = AccessibilityInfo?.isReduceMotionEnabled?.();
    if (typeof pending?.then === 'function') {
      pending.then(v => { if (mounted) { setReduce(!!v); } }).catch(() => { if (mounted) { setReduce(false); } });
    } else {
      setReduce(false);   // API nicht verfuegbar -> Animationen an
    }
    const sub = AccessibilityInfo?.addEventListener?.('reduceMotionChanged', v => { if (mounted) { setReduce(!!v); } });
    return () => {
      mounted = false;
      sub?.remove?.();
    };
  }, []);
  return reduce;
}

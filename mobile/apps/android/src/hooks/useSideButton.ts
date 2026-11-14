/**
 * useSideButton — Hook für Samsung S25 Ultra Side-Key Integration
 *
 * Single Press  → onPress Callback (App aktivieren / Focus)
 * Double Press  → onDoublePress Callback (Voice-Input, Stub für DONNA-15)
 *
 * Cleanup erfolgt automatisch beim Unmount.
 * useRef-Pattern stellt sicher dass immer die aktuelle Callback-Version
 * aufgerufen wird — kein Stale-Closure-Risiko.
 */
import {useEffect, useRef} from 'react';
import {NativeSideButtonModule, type SideButtonEvent} from '../native/NativeSideButtonModule';

export interface UseSideButtonOptions {
  onPress?: (event: SideButtonEvent) => void;
  onDoublePress?: (event: SideButtonEvent) => void;
  enabled?: boolean;
}

export function useSideButton({
  onPress,
  onDoublePress,
  enabled = true,
}: UseSideButtonOptions): {isAvailable: boolean} {
  const isAvailable = NativeSideButtonModule.isAvailable();

  // Refs halten immer die aktuelle Callback-Version — kein Stale-Closure
  const onPressRef = useRef(onPress);
  const onDoublePressRef = useRef(onDoublePress);
  onPressRef.current = onPress;
  onDoublePressRef.current = onDoublePress;

  useEffect(() => {
    if (!enabled || !isAvailable) {return;}

    const pressSub = onPress
      ? NativeSideButtonModule.onPress((event: SideButtonEvent) => {
          onPressRef.current?.(event);
        })
      : null;

    const doubleSub = onDoublePress
      ? NativeSideButtonModule.onDoublePress((event: SideButtonEvent) => {
          onDoublePressRef.current?.(event);
        })
      : null;

    return () => {
      pressSub?.remove();
      doubleSub?.remove();
    };
  // Subscription nur neu erstellen wenn sich enabled/isAvailable/Callback-Präsenz ändert
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, isAvailable, !!onPress, !!onDoublePress]);

  return {isAvailable};
}

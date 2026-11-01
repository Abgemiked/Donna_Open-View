import {NativeModules} from 'react-native';

/**
 * Dünne Brücke zum nativen LiveBubbleModule (A2 Live-Bubble). Ohne natives Modul (Tests, andere
 * Plattformen) sind alle Aufrufe sichere No-ops / false.
 */
type LiveBubbleNative = {
  canDrawOverlay(): Promise<boolean>;
  show(): Promise<boolean>;
  hide(): void;
  isRunning(): Promise<boolean>;
};

const LB: LiveBubbleNative | undefined = (NativeModules as {LiveBubbleModule?: LiveBubbleNative})
  .LiveBubbleModule;

export function isLiveBubbleAvailable(): boolean {
  return LB != null;
}

export async function canDrawOverlay(): Promise<boolean> {
  try {
    return !!(await LB?.canDrawOverlay());
  } catch {
    return false;
  }
}

/** Startet die schwebende Blase. false = Overlay-Recht fehlt (im Onboarding erteilen). */
export async function showLiveBubble(): Promise<boolean> {
  try {
    return !!(await LB?.show());
  } catch {
    return false;
  }
}

export function hideLiveBubble(): void {
  try {
    LB?.hide();
  } catch {
    /* no-op */
  }
}

export async function isLiveBubbleRunning(): Promise<boolean> {
  try {
    return !!(await LB?.isRunning());
  } catch {
    return false;
  }
}

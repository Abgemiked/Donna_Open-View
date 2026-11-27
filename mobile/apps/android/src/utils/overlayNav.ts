/**
 * #134 P6: Navigation der Chat-Overlays Einstellungen und Einrichtung als reine Funktionen (Stapel, oberster Eintrag
 * ist sichtbar). Das Zahnrad im Chat oeffnet die Einstellungen; von dort geht es in die Einrichtung und zurueck.
 */
export type OverlayScreen = 'settings' | 'setup';
export type OverlayStack = readonly OverlayScreen[];
export type OverlayAction = 'openFromChat' | 'openSetup' | 'openSettings' | 'close';

export const OVERLAY_EMPTY: OverlayStack = [];

export function overlayReduce(stack: OverlayStack, action: OverlayAction): OverlayStack {
  const top = stack[stack.length - 1];
  switch (action) {
    case 'openFromChat':
      return stack.length === 0 ? ['settings'] : stack;
    case 'openSetup':
      return top === 'setup' ? stack : [...stack, 'setup'];
    case 'openSettings':
      if (top === 'settings') {
        return stack;
      }
      // kam die Einrichtung aus den Einstellungen, geht es dorthin zurueck (kein doppelter Eintrag)
      return stack[stack.length - 2] === 'settings' ? stack.slice(0, -1) : [...stack, 'settings'];
    case 'close':
      return stack.slice(0, -1);
  }
}

export function overlayTop(stack: OverlayStack): OverlayScreen | null {
  return stack.length === 0 ? null : stack[stack.length - 1];
}

/** Ganz oben in den Einstellungen "Einrichtung unvollstaendig" zeigen, solange eine Pflicht-Berechtigung fehlt. */
export function showSetupIncompleteEntry(missingRequired: boolean): boolean {
  return missingRequired;
}

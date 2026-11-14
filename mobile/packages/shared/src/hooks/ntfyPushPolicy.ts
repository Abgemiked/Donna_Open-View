// ntfyPushPolicy — reine Entscheidungslogik für den JS-ntfy-WebSocket-Hook (#51, JVM-/Jest-testbar).
//
// Zwei ntfy-Consumer laufen parallel:
//   * nativ  `NtfyService` (SSE, Hintergrund + App-Kill) — zeigt die Notification, wenn MainActivity NICHT
//     im Vordergrund ist (AppForegroundState.isMainResumed == false) ODER die Push "persistent" ist.
//   * JS     `useNtfyNotifications` (WebSocket) — darf NUR im Vordergrund (AppState 'active') und NUR für
//     nicht-persistente Pushes anzeigen. Exakt das Komplement der nativen Bedingung → nie 2×, nie 0×.
// Persistente Pushes (Tag `donna_persistent`) zeigt IMMER nativ (eigener Channel, ongoing) — der JS-Hook
// überspringt sie, sonst gäbe es im Vordergrund eine Doppelanzeige (nativ unterdrückt sie dort nicht).

/** Muss mit PushPayload.TAG_PERSISTENT (Kotlin) übereinstimmen. */
export const NTFY_TAG_PERSISTENT = 'donna_persistent';

/** True, wenn die ntfy-Tags das Persistent-Tag enthalten (case-insensitiv, getrimmt; robust gegen Müll). */
export function isPersistentPush(tags: unknown): boolean {
  if (!Array.isArray(tags)) {
    return false;
  }
  return tags.some(
    (t) => typeof t === 'string' && t.trim().toLowerCase() === NTFY_TAG_PERSISTENT,
  );
}

/**
 * Darf der JS-Hook diese Push als lokale Notification anzeigen?
 * Nur im Vordergrund (AppState 'active') und nur nicht-persistent; alles andere übernimmt NtfyService.
 */
export function shouldDisplayInJs(appState: string, tags: unknown): boolean {
  return appState === 'active' && !isPersistentPush(tags);
}

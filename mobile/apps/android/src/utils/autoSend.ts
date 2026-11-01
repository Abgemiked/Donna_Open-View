/**
 * autoSend — B2 Accessibility Auto-Send (#3 WhatsApp + #1 Anruf).
 *
 * Nach Mikes expliziter Bestätigung (Bestätigen-Button im Schriftchat ODER verbale Zustimmung im
 * Sprachchat) öffnet confirmPendingAction die Ziel-App (WhatsApp bzw. Telefon) wie gehabt per
 * Linking und löst DANACH den Accessibility-Auto-Tap aus: DonnaAccessibilityService tippt den
 * „Senden"/„Anrufen"-Button selbst (performAction(ACTION_CLICK)).
 *
 * KERN-INVARIANTE (Stufe 3): triggerAutoSend wird ausschließlich aus dem Bestätigungs-Pfad gerufen.
 * Es tippt nie von sich aus — ohne Mikes OK passiert nichts. Die native Seite begrenzt zusätzlich
 * auf die erlaubten Pakete (WhatsApp/Dialer); JS kann kein beliebiges Paket antippen lassen.
 *
 * Fällt der Auto-Tap aus (Service aus, UI geändert, Node nicht gefunden → Bridge-Reject) ODER bleibt
 * die native Promise aus irgendeinem Grund offen (Timeout-Race), bekommt Mike IMMER den Hinweis
 * „bitte selbst tippen" — kein stiller Fehlschlag, und sendingRef im ChatScreen bleibt nie gesperrt.
 */

/** Nur diese Aktions-Typen lösen einen Auto-Tap aus. */
export const AUTO_SEND_KINDS = ['whatsapp', 'call'] as const;
export type AutoSendKind = (typeof AUTO_SEND_KINDS)[number];

/**
 * Minimal-Vertrag der nativen AccessibilityBridge (NativeModules.AccessibilityBridge).
 * WICHTIG (BLOCKER 1): die Argumentzahl MUSS zur Kotlin-@ReactMethod-Signatur passen —
 * `performActionTap(kind: String, promise: Promise)` = genau EIN JS-Argument. Ein Aufruf mit
 * falscher Arity wirft auf der alten RN-Architektur eine NativeArgumentsParseException, die JS
 * NICHT fangen kann. Der Arity-/Vertragstest (autoSendContract.test.ts) sichert das ab.
 */
export interface AccessibilityAutoSend {
  performActionTap(kind: string): Promise<string>;
}

export interface AutoSendOutcome {
  /** true = Bridge hat den Senden/Anrufen-Button getippt. */
  sent: boolean;
  /** Fallback-Hinweis für Mike, wenn NICHT automatisch getippt wurde (sonst undefined). */
  note?: string;
}

/** Obergrenze, bis die JS-Seite spätestens aufgibt (native Deadline 7 s + Puffer). */
export const AUTO_SEND_JS_TIMEOUT_MS = 12_000;

function fallbackHint(type: string): string {
  return type === 'call'
    ? 'Falls nicht automatisch gewählt wird: bitte in der Telefon-App auf Anrufen tippen.'
    : 'Falls nicht automatisch gesendet wird: bitte in WhatsApp auf Senden tippen.';
}

/**
 * Löst nach der Bestätigung den Auto-Tap aus.
 * @param type       Aktions-Typ der bestätigten DonnaAction (nur whatsapp/call tippen).
 * @param bridge     NativeModules.AccessibilityBridge (oder undefined, wenn nicht verfügbar).
 * @param opts.autoSend  false = Opt-out aus dem Backend (abwärtskompatibel). Default: aktiv.
 */
export async function triggerAutoSend(
  type: string,
  bridge: AccessibilityAutoSend | undefined | null,
  opts?: {autoSend?: boolean},
): Promise<AutoSendOutcome> {
  // Nur Handy-Aktionen, die ein Antippen brauchen.
  if (!(AUTO_SEND_KINDS as readonly string[]).includes(type)) {
    return {sent: false};
  }
  // F4: Explizites Opt-out des Backends — kein Tap, aber Mike muss wissen, dass er selbst tippt.
  if (opts?.autoSend === false) {
    return {sent: false, note: fallbackHint(type)};
  }
  // Bridge/Service nicht verfügbar → ehrlicher Fallback-Hinweis statt stillem Fehlschlag.
  if (!bridge || typeof bridge.performActionTap !== 'function') {
    return {sent: false, note: fallbackHint(type)};
  }
  // F2: Race gegen einen Timeout, damit eine (theoretisch) nie auflösende native Promise
  // sendingRef im ChatScreen nicht dauerhaft sperrt.
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<AutoSendOutcome>(resolve => {
    timer = setTimeout(() => resolve({sent: false, note: fallbackHint(type)}), AUTO_SEND_JS_TIMEOUT_MS);
  });
  const tap = bridge
    .performActionTap(type)
    .then<AutoSendOutcome>(() => ({sent: true}))
    .catch<AutoSendOutcome>(() => ({sent: false, note: fallbackHint(type)}));
  try {
    return await Promise.race([tap, timeout]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

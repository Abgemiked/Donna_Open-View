import {triggerAutoSend, AUTO_SEND_KINDS, AUTO_SEND_JS_TIMEOUT_MS} from '../utils/autoSend';

/**
 * B2 Accessibility Auto-Send (#3 WhatsApp + #1 Anruf).
 *
 * confirmPendingAction (ChatScreen) löst nach Mikes Bestätigung den Accessibility-Auto-Tap aus.
 * Die reine Entscheidung (triggern? / Fallback-Hinweis?) ist in triggerAutoSend extrahiert —
 * analog zu ActionIntents / parseConfirmation (Android-Framework von der Logik entkoppelt),
 * weil ein voller ChatScreen-Render + Pending-Karte + Bestätigen-Button fragil wäre.
 *
 * Kern-Invariante: triggerAutoSend wird NUR aus confirmPendingAction/Voice-Bestätigung gerufen —
 * also nur nach Mikes explizitem OK. Es sendet nie von sich aus.
 */
describe('triggerAutoSend (#3/#1 Auto-Send nach Bestätigung)', () => {
  it('whatsapp: ruft Bridge-Auto-Send mit GENAU EINEM Argument (Arity), meldet sent', async () => {
    const performActionTap = jest.fn().mockResolvedValue('CLICKED');
    const out = await triggerAutoSend('whatsapp', {performActionTap});
    // BLOCKER 1: exakt 1 Argument — passt zur Kotlin-Signatur performActionTap(kind, promise).
    expect(performActionTap).toHaveBeenCalledWith('whatsapp');
    expect(performActionTap.mock.calls[0]).toHaveLength(1);
    expect(out.sent).toBe(true);
    expect(out.note).toBeUndefined();
  });

  it('call: ruft Bridge-Auto-Send mit kind=call', async () => {
    const performActionTap = jest.fn().mockResolvedValue('CLICKED');
    const out = await triggerAutoSend('call', {performActionTap});
    expect(performActionTap).toHaveBeenCalledWith('call');
    expect(performActionTap.mock.calls[0]).toHaveLength(1);
    expect(out.sent).toBe(true);
  });

  it('Bridge-Reject (Node nicht gefunden / UI geändert) → Fallback-Hinweis "bitte tippen"', async () => {
    const performActionTap = jest.fn().mockRejectedValue(new Error('NODE_NOT_FOUND'));
    const out = await triggerAutoSend('whatsapp', {performActionTap});
    expect(performActionTap).toHaveBeenCalledWith('whatsapp');
    expect(out.sent).toBe(false);
    expect(out.note).toMatch(/tippen|Senden/i);
  });

  it('F3: whatsapp-Reject nennt WhatsApp/Senden, NICHT Telefon/Anrufen', async () => {
    const performActionTap = jest.fn().mockRejectedValue(new Error('x'));
    const out = await triggerAutoSend('whatsapp', {performActionTap});
    expect(out.note).toMatch(/WhatsApp|Senden/i);
    expect(out.note).not.toMatch(/Telefon|Anrufen/i);
  });

  it('F3: call-Reject nennt Telefon/Anrufen, NICHT WhatsApp (kind-spezifisch)', async () => {
    const performActionTap = jest.fn().mockRejectedValue(new Error('x'));
    const out = await triggerAutoSend('call', {performActionTap});
    expect(out.note).toMatch(/Telefon|Anrufen/i);
    expect(out.note).not.toMatch(/WhatsApp/i);
  });

  it('Bridge fehlt (altes Gerät / Service aus) → Fallback-Hinweis, kein Crash', async () => {
    const out = await triggerAutoSend('whatsapp', undefined);
    expect(out.sent).toBe(false);
    expect(out.note).toMatch(/tippen|Senden/i);
  });

  it('autoSend:false schaltet den Auto-Tap ab, zeigt aber Fallback-Hinweis (F4)', async () => {
    const performActionTap = jest.fn().mockResolvedValue('CLICKED');
    const out = await triggerAutoSend('whatsapp', {performActionTap}, {autoSend: false});
    expect(performActionTap).not.toHaveBeenCalled();
    expect(out.sent).toBe(false);
    // F4: Mike muss wissen, dass er selbst tippen muss.
    expect(out.note).toMatch(/tippen|Senden/i);
  });

  it('F2: hängende native Promise → Timeout-Race löst mit Fallback auf (sendingRef nie blockiert)', async () => {
    jest.useFakeTimers();
    try {
      // performActionTap löst NIE auf → ohne Race bliebe triggerAutoSend hängen.
      const performActionTap = jest.fn().mockReturnValue(new Promise<string>(() => {}));
      const p = triggerAutoSend('whatsapp', {performActionTap});
      await jest.advanceTimersByTimeAsync(AUTO_SEND_JS_TIMEOUT_MS + 10);
      const out = await p;
      expect(out.sent).toBe(false);
      expect(out.note).toMatch(/tippen|Senden/i);
    } finally {
      jest.useRealTimers();
    }
  });

  it('nicht-handy-Aktionen (sms/navigate/open_url) tippen NIE etwas an', async () => {
    const performActionTap = jest.fn().mockResolvedValue('CLICKED');
    for (const t of ['sms', 'navigate', 'open_url', 'create_event']) {
      const out = await triggerAutoSend(t, {performActionTap});
      expect(out.sent).toBe(false);
    }
    expect(performActionTap).not.toHaveBeenCalled();
  });

  it('AUTO_SEND_KINDS ist auf whatsapp + call begrenzt', () => {
    expect([...AUTO_SEND_KINDS].sort()).toEqual(['call', 'whatsapp']);
  });
});

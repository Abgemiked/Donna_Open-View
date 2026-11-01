import {contactActionFields} from '../utils/actionSummary';

/**
 * B2 BLOCKER 4 — informierte Bestätigung: die Karte zeigt Empfänger + Nummer + Nachrichtentext,
 * und markiert eine Nummer, die NICHT aus den Kontakten aufgelöst wurde (Payload/Halluzinations-Risiko).
 */
describe('contactActionFields (informierte Bestätigung)', () => {
  it('whatsapp mit aufgelöstem Kontakt: Name + Nummer (unmarkiert) + Nachricht', () => {
    const fields = contactActionFields(
      {type: 'whatsapp', name: 'Ämi', number: '+490000', message: 'Bin um 18 Uhr da'},
      {name: 'Ämi', number: '+4917012345'},
    );
    const byLabel = Object.fromEntries(fields.map(f => [f.label, f]));
    expect(byLabel['Empfänger'].value).toBe('Ämi');
    // Nummer kommt aus dem Kontakt (nicht Payload) → unmarkiert, kein warn
    expect(byLabel['Nummer'].value).toBe('+4917012345');
    expect(byLabel['Nummer'].warn).toBeFalsy();
    const msg = fields.find(f => f.label === 'Nachricht');
    expect(msg?.value).toBe('Bin um 18 Uhr da');
    expect(msg?.multiline).toBe(true);
  });

  it('whatsapp ohne Kontakt (Nummer aus Payload): Nummer wird als "aus Anfrage" markiert (warn)', () => {
    const fields = contactActionFields(
      {type: 'whatsapp', name: 'Max', number: '017698765', message: 'Hi'},
      null,
    );
    const numField = fields.find(f => f.label.startsWith('Nummer'));
    expect(numField).toBeTruthy();
    expect(numField!.value).toBe('017698765');
    expect(numField!.label).toMatch(/aus Anfrage|nicht aus Kontakten/i);
    expect(numField!.warn).toBe(true);
    // Nachricht wird ebenfalls gezeigt (vollständig)
    expect(fields.some(f => f.label === 'Nachricht' && f.value === 'Hi')).toBe(true);
  });

  it('call ohne Kontakt: Nummer markiert, KEINE Nachricht', () => {
    const fields = contactActionFields({type: 'call', number: '+4930123'}, null);
    const numField = fields.find(f => f.label.startsWith('Nummer'));
    expect(numField!.value).toBe('+4930123');
    expect(numField!.warn).toBe(true);
    expect(fields.some(f => f.label === 'Nachricht')).toBe(false);
  });

  it('sms zeigt immer Empfänger UND Nummer (nicht nur eins von beiden)', () => {
    const fields = contactActionFields(
      {type: 'sms', name: 'Chef', number: '+491111', message: 'Komme später'},
      {name: 'Chef', number: '+49170999'},
    );
    expect(fields.some(f => f.label === 'Empfänger' && f.value === 'Chef')).toBe(true);
    expect(fields.some(f => f.label === 'Nummer' && f.value === '+49170999')).toBe(true);
  });
});

import {parseConfirmation} from '../utils/voiceConfirm';

describe('parseConfirmation (F1: Ja/Nein per Sprache)', () => {
  it('eindeutiges Ja', () => {
    expect(parseConfirmation('Ja')).toBe('yes');
    expect(parseConfirmation('ja, mach das')).toBe('yes');
    expect(parseConfirmation('Okay, klar')).toBe('yes');
    expect(parseConfirmation('Bestätige')).toBe('yes');
  });

  it('eindeutiges Nein - Verneinung hat Vorrang', () => {
    expect(parseConfirmation('Nein')).toBe('no');
    expect(parseConfirmation('nein, mach das nicht')).toBe('no');   // „mach“ ist ein schwaches Ja-Wort
    expect(parseConfirmation('lass das lieber')).toBe('no');
    expect(parseConfirmation('Abbrechen bitte')).toBe('no');
    expect(parseConfirmation('doch nicht')).toBe('no');
  });

  it('Mehrdeutigkeit: starkes Ja UND Verneinung → nichts ausführen', () => {
    expect(parseConfirmation('ja, aber nicht jetzt')).toBe('ambiguous');
    expect(parseConfirmation('ja nein')).toBe('ambiguous');
    expect(parseConfirmation('ja, lass das bleiben')).toBe('ambiguous');
  });

  it('Wortgrenzen statt includes: Teilstrings lösen nichts aus', () => {
    expect(parseConfirmation('das ist eine Machtfrage')).toBe('none');       // „mach“ in „Machtfrage“
    expect(parseConfirmation('bitte aufklären')).toBe('none');               // „klar“ in „aufklären“
    expect(parseConfirmation('Jahresplanung')).toBe('none');                  // „ja“ in „Jahresplanung“
    expect(parseConfirmation('eine Stellungnahme')).toBe('none');             // früher: „stell“
    expect(parseConfirmation('Nichtraucher')).toBe('none');                   // „nicht“ in „Nichtraucher“
  });

  it('weder Ja noch Nein → normale Nachricht', () => {
    expect(parseConfirmation('Wie wird das Wetter morgen')).toBe('none');
    expect(parseConfirmation('')).toBe('none');
  });

  it('Groß-/Kleinschreibung und Satzzeichen sind egal', () => {
    expect(parseConfirmation('JA!!!')).toBe('yes');
    expect(parseConfirmation('Nein.')).toBe('no');
  });

  it('H1: Ja nur bei KURZER Äußerung - lange Sätze sind neue Befehle (Karte bleibt)', () => {
    expect(parseConfirmation('Mach das Licht im Wohnzimmer aus')).toBe('none');
    expect(parseConfirmation('Okay und was ist mit morgen')).toBe('none');
    expect(parseConfirmation('Klar, ruf bitte noch meine Mutter an')).toBe('none');
    expect(parseConfirmation('Genau, stell einen Wecker für sieben Uhr')).toBe('none');
    expect(parseConfirmation('Ja, schalte bitte auch noch die Lampe im Flur an')).toBe('none');
  });

  it('H1: schwache Ja-Wörter (mach, klar, ok, genau …) nur bei höchstens 3 Wörtern', () => {
    expect(parseConfirmation('mach das')).toBe('yes');
    expect(parseConfirmation('ok klar')).toBe('yes');
    expect(parseConfirmation('genau so')).toBe('yes');
    expect(parseConfirmation('mach das Licht aus')).toBe('none');      // 4 Wörter, nur schwaches Ja
    expect(parseConfirmation('klar mach das jetzt')).toBe('none');
  });

  it('H1: starke Ja-Wörter bis 5 Wörter, darüber nichts', () => {
    expect(parseConfirmation('ja bitte mach das jetzt')).toBe('yes');    // 5 Wörter
    expect(parseConfirmation('ja bitte mach das jetzt sofort')).toBe('none');   // 6 Wörter
  });

  it('H1: Verneinung in langen Sätzen löscht die Karte nicht', () => {
    expect(parseConfirmation('Nein, mach stattdessen das Licht im Wohnzimmer aus')).toBe('none');
    expect(parseConfirmation('Lass uns später über das Wetter morgen reden')).toBe('none');
  });

  it('kurze Verneinungen bleiben Nein', () => {
    expect(parseConfirmation('nein, mach das nicht')).toBe('no');
    expect(parseConfirmation('lass das lieber')).toBe('no');
    expect(parseConfirmation('nicht jetzt')).toBe('no');
    expect(parseConfirmation('ja, aber nicht jetzt')).toBe('ambiguous');
  });
});

import {isEchoOfSpoken, EchoStrikes, appendSpoken} from '../utils/echoGuard';

const spoken = 'Ich habe das Licht im Wohnzimmer ausgemacht und die Rollläden geschlossen.';

describe('isEchoOfSpoken', () => {
  it('erkennt Donnas eigenen Text als Echo', () => {
    expect(isEchoOfSpoken('ich habe das licht im wohnzimmer ausgemacht', spoken)).toBe(true);
    expect(isEchoOfSpoken('Licht im Wohnzimmer ausgemacht und die Rollläden geschlossen', spoken)).toBe(true);
  });
  it('eine echte neue Äußerung ist kein Echo', () => {
    expect(isEchoOfSpoken('mach bitte das Licht in der Küche an', spoken)).toBe(false);
    expect(isEchoOfSpoken('wie wird das Wetter morgen', spoken)).toBe(false);
  });
  it('einzelne Wörter sind beim Auto-Relisten nie Echo', () => {
    expect(isEchoOfSpoken('Licht', spoken)).toBe(false);
  });
  it('Barge-in: Einzelwort aus Donnas Text ist Echo, ein Antwortwort wie "Stopp" nicht', () => {
    expect(isEchoOfSpoken('Wohnzimmer', spoken, true)).toBe(true);
    expect(isEchoOfSpoken('Stopp', spoken, true)).toBe(false);
  });
  it('„Ja, das Licht im Wohnzimmer“ ist eine echte Antwort (F2)', () => {
    const question = 'Soll ich das Licht im Wohnzimmer ausschalten?';
    expect(isEchoOfSpoken('Ja, das Licht im Wohnzimmer', question)).toBe(false);
    expect(isEchoOfSpoken('Ja, das Licht im Wohnzimmer', question, true)).toBe(false);
    expect(isEchoOfSpoken('Nein lass das Licht im Wohnzimmer an', question)).toBe(false);
  });
  it('Auto-Relisten: bis 6 Wörter müssen alle aus Donnas Text stammen; Barge-in: Schwelle 0,5', () => {
    const question = 'Soll ich das Licht im Wohnzimmer ausschalten?';
    expect(isEchoOfSpoken('das Licht im Wohnzimmer anmachen', question)).toBe(false);
    expect(isEchoOfSpoken('soll ich das Licht im Wohnzimmer', question)).toBe(true);
    expect(isEchoOfSpoken('das Licht im Wohnzimmer hmm', question, true)).toBe(true);
  });
  it('zwei Wörter müssen beide aus Donnas Text stammen', () => {
    expect(isEchoOfSpoken('Licht ausgemacht', spoken)).toBe(true);
    expect(isEchoOfSpoken('Licht anmachen', spoken)).toBe(false);
  });
  it('ohne gesprochenen Text gibt es kein Echo', () => {
    expect(isEchoOfSpoken('irgendwas ganz anderes hier', '')).toBe(false);
  });
});

describe('EchoStrikes', () => {
  it('schaltet nach zwei Selbstunterbrechungen ab', () => {
    const e = new EchoStrikes(2);
    expect(e.record()).toBe(false);
    expect(e.record()).toBe(true);
    e.reset();
    expect(e.strikes).toBe(0);
  });
});

describe('appendSpoken', () => {
  it('hängt an und kürzt auf die letzten Zeichen', () => {
    expect(appendSpoken('', 'Hallo')).toBe('Hallo');
    expect(appendSpoken('Hallo', 'Welt')).toBe('Hallo Welt');
    expect(appendSpoken('a'.repeat(50), 'b'.repeat(50), 60).length).toBe(60);
  });
});

import {NativeModules} from 'react-native';
import {
  getNavTarget,
  getOptionTarget,
  str,
  isNavChoiceAction,
  navTypeOf,
  notFoundText,
  parseNavOptions,
  sendTargetToTesla,
} from '../utils/navActions';
import {sendToTesla, TeslaNavError} from '../native/TeslaNav';

const nativeModules = NativeModules as Record<string, unknown>;

afterEach(() => {
  delete nativeModules.TeslaNavModule;
});

describe('navActions — reine Logik', () => {
  it('erkennt Geocoding-Rueckfragen', () => {
    expect(isNavChoiceAction({type: 'navigate_disambiguate'})).toBe(true);
    expect(isNavChoiceAction({type: 'navigate_not_found'})).toBe(true);
    expect(isNavChoiceAction({type: 'navigate'})).toBe(false);
    expect(isNavChoiceAction({type: 'tesla_navigate'})).toBe(false);
  });

  it('navTypeOf: Tesla nur bei ausdruecklichem tesla_navigate (Action oder nav_type)', () => {
    expect(navTypeOf({type: 'tesla_navigate'})).toBe('tesla_navigate');
    expect(navTypeOf({type: 'navigate_disambiguate', nav_type: 'tesla_navigate'})).toBe('tesla_navigate');
    expect(navTypeOf({type: 'navigate_not_found', nav_type: 'tesla_navigate'})).toBe('tesla_navigate');
    expect(navTypeOf({type: 'navigate'})).toBe('navigate');
    expect(navTypeOf({type: 'navigate_disambiguate', nav_type: 'navigate'})).toBe('navigate');
    // altes Backend ohne nav_type => Handy
    expect(navTypeOf({type: 'navigate_disambiguate'})).toBe('navigate');
  });

  it('getNavTarget bevorzugt resolved_address, dann destination, dann query', () => {
    expect(getNavTarget({type: 'tesla_navigate', destination: 'Marienplatz', resolved_address: 'Marienplatz 1, 80331 München'}))
      .toBe('Marienplatz 1, 80331 München');
    expect(getNavTarget({type: 'tesla_navigate', destination: 'Marienplatz', resolved_address: 'kurz', nav_address: 'Voll, 80331 München, Deutschland'}))
      .toBe('Voll, 80331 München, Deutschland'); // nav_address (voll) hat Vorrang vor resolved_address (kurz)
    expect(getNavTarget({type: 'tesla_navigate', destination: {x: 1}, nav_address: 5, resolved_address: ['a']})).toBe('');
    expect(getNavTarget({type: 'tesla_navigate', destination: ' Marienplatz '})).toBe('Marienplatz');
    expect(getNavTarget({type: 'tesla_navigate', destination: 'Marienplatz', resolved_address: '  '})).toBe('Marienplatz');
    expect(getNavTarget({type: 'navigate_not_found', query: 'Foo'})).toBe('Foo');
    expect(getNavTarget({type: 'tesla_navigate'})).toBe('');
  });

  it('parseNavOptions filtert ungueltige Eintraege und uebernimmt nur numerische Koordinaten', () => {
    const opts = parseNavOptions({
      type: 'navigate_disambiguate',
      options: [
        {label: 'Hauptstr. 1, Berlin', lat: 52.5, lon: 13.4},
        {label: '  ', lat: 1, lon: 2},
        {label: 'Hauptstr. 1, Hamburg', lat: '53.5', lon: null},
        null,
        {lat: 1, lon: 2},
      ],
    });
    expect(opts).toEqual([
      {label: 'Hauptstr. 1, Berlin', address: undefined, lat: 52.5, lon: 13.4},
      {label: 'Hauptstr. 1, Hamburg', address: undefined, lat: undefined, lon: undefined},
    ]);
    expect(parseNavOptions({type: 'navigate_disambiguate', options: [{label: 'k', address: ' Volle Adresse 1 '}]}))
      .toEqual([{label: 'k', address: 'Volle Adresse 1', lat: undefined, lon: undefined}]);
    expect(parseNavOptions({type: 'navigate_disambiguate'})).toEqual([]);
    expect(parseNavOptions({type: 'navigate_disambiguate', options: 'x'})).toEqual([]);
  });

  it('getOptionTarget: volle Adresse, sonst Label', () => {
    expect(getOptionTarget({label: 'kurz', address: 'Voll 1, 80331 München'})).toBe('Voll 1, 80331 München');
    expect(getOptionTarget({label: 'kurz'})).toBe('kurz');
    expect(getOptionTarget({label: 'kurz', address: '  '})).toBe('kurz');
  });

  it('str liefert nur getrimmte Strings, alles andere leer (Render-Schutz)', () => {
    expect(str('  a ')).toBe('a');
    expect(str({a: 1})).toBe('');
    expect(str(['a'])).toBe('');
    expect(str(42)).toBe('');
    expect(str(null)).toBe('');
    expect(str(undefined)).toBe('');
  });

  it('notFoundText ist freundlich und nennt Suche + Tesla-Bezug', () => {
    const tesla = notFoundText({type: 'navigate_not_found', nav_type: 'tesla_navigate', query: 'Xyz'});
    expect(tesla).toContain('„Xyz“');
    expect(tesla).toContain('Tesla');
    const phone = notFoundText({type: 'navigate_not_found', query: 'Xyz'});
    expect(phone).toContain('„Xyz“');
    expect(phone).not.toContain('Tesla');
    expect(notFoundText({type: 'navigate_not_found'})).toContain('nicht gefunden');
  });
});

describe('TeslaNav-Bridge + sendTargetToTesla', () => {
  it('sendToTesla ruft das native Modul mit getrimmtem Text', async () => {
    const native = {sendToTesla: jest.fn().mockResolvedValue(true)};
    nativeModules.TeslaNavModule = native;
    await sendToTesla('  Marienplatz 1, München ');
    expect(native.sendToTesla).toHaveBeenCalledWith('Marienplatz 1, München');
  });

  it('ohne natives Modul: UNAVAILABLE', async () => {
    await expect(sendToTesla('x')).rejects.toMatchObject({name: 'TeslaNavError', code: 'UNAVAILABLE'});
  });

  it('leerer Text: EMPTY, ohne das native Modul aufzurufen', async () => {
    const native = {sendToTesla: jest.fn()};
    nativeModules.TeslaNavModule = native;
    await expect(sendToTesla('   ')).rejects.toMatchObject({code: 'EMPTY'});
    expect(native.sendToTesla).not.toHaveBeenCalled();
  });

  it('mappt native Reject-Codes', async () => {
    nativeModules.TeslaNavModule = {
      sendToTesla: jest.fn().mockRejectedValue(Object.assign(new Error('weg'), {code: 'TESLA_NOT_INSTALLED'})),
    };
    await expect(sendToTesla('x')).rejects.toBeInstanceOf(TeslaNavError);
    await expect(sendToTesla('x')).rejects.toMatchObject({code: 'NOT_INSTALLED'});

    nativeModules.TeslaNavModule = {
      sendToTesla: jest.fn().mockRejectedValue(Object.assign(new Error('kaputt'), {code: 'SEND_FAILED'})),
    };
    await expect(sendToTesla('x')).rejects.toMatchObject({code: 'FAILED'});
  });

  it('sendTargetToTesla: Erfolg → Bestaetigungstext mit Ziel', async () => {
    nativeModules.TeslaNavModule = {sendToTesla: jest.fn().mockResolvedValue(true)};
    await expect(sendTargetToTesla(' Marienplatz 1 ')).resolves.toBe('An Tesla-App übergeben: Marienplatz 1 ✓');
  });

  it('sendTargetToTesla: Tesla-App nicht installiert → klare Meldung, kein throw', async () => {
    nativeModules.TeslaNavModule = {
      sendToTesla: jest.fn().mockRejectedValue(Object.assign(new Error('x'), {code: 'TESLA_NOT_INSTALLED'})),
    };
    await expect(sendTargetToTesla('Marienplatz')).resolves.toMatch(/Tesla-App nicht installiert/);
  });

  it('sendTargetToTesla: sonstige Fehler → allgemeine Meldung, kein throw', async () => {
    nativeModules.TeslaNavModule = {sendToTesla: jest.fn().mockRejectedValue(new Error('boom'))};
    await expect(sendTargetToTesla('Marienplatz')).resolves.toMatch(/fehlgeschlagen/);
    delete nativeModules.TeslaNavModule;
    await expect(sendTargetToTesla('Marienplatz')).resolves.toMatch(/nicht verfügbar/);
    nativeModules.TeslaNavModule = {sendToTesla: jest.fn()};
    await expect(sendTargetToTesla('  ')).resolves.toMatch(/Kein Ziel/);
  });
});

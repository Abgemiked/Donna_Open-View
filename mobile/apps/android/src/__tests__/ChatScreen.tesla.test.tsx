/**
 * Tesla-Navigation im Chat (Batch 1, Teil A + Review-Verfeinerungen):
 *  - tesla_navigate: Karte oeffnet beim Eintreffen automatisch (einmalig), EIN Tipp auf "An Tesla senden" ruft
 *    sendToTesla(volle Adresse) — KEIN Auto-Senden ohne Tipp, Chip oeffnet die Karte wieder
 *  - Doppel-Send-Guard, Render-Sanitierung, deaktivierter Button ohne Ziel
 *  - navigate_disambiguate (nav_type tesla_navigate): Treffer waehlen -> volle Adresse an Tesla
 *  - navigate_not_found / disambiguate ohne Optionen: freundliche Fehlermeldung
 *  - Handy-Navigation (nav_type navigate / kein nav_type) bleibt bei Maps/geo:
 */
import React from 'react';
import {Linking, NativeModules, PermissionsAndroid, Platform} from 'react-native';
import {render, fireEvent, act} from '@testing-library/react-native';
import ChatScreen from '../screens/ChatScreen';

// ─── Mocks ───────────────────────────────────────────────────────────────────
jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => ({top: 0, bottom: 0, left: 0, right: 0}),
}));

jest.mock('@react-native-async-storage/async-storage', () => ({
  __esModule: true,
  default: {
    getItem: jest.fn().mockResolvedValue(null),
    setItem: jest.fn().mockResolvedValue(undefined),
  },
}));

const mockLoadMessages = jest.fn();
let mockMessages: unknown[] = [];

jest.mock('@donna/shared', () => ({
  useChat: () => ({
    messages: mockMessages,
    isLoading: false,
    rateLimitStatus: null,
    sendMessage: jest.fn().mockResolvedValue(undefined),
    loadMessages: mockLoadMessages,
    clearMessages: jest.fn(),
    setLocation: jest.fn(),
    sessionId: 'test-session',
    startProactiveChat: jest.fn(),
  }),
  useVoice: () => ({
    voiceState: 'idle',
    partialTranscript: '',
    startListening: jest.fn(),
    stopListening: jest.fn(),
    isAvailable: false,
  }),
  fetchSessions: jest.fn().mockResolvedValue([]),
  fetchSessionMessages: jest.fn().mockResolvedValue([]),
  sendFeedback: jest.fn().mockResolvedValue(true),
  fuzzyScore: jest.fn().mockReturnValue(0),
  getApiToken: jest.fn().mockReturnValue('token'),
}));

jest.mock('../App', () => ({
  voiceTriggerRef: {current: null},
  proactiveChatRef: {current: null},
  pendingProactiveMessage: null,
  clearPendingProactiveMessage: jest.fn(),
}));
jest.mock('../native/AndroidVoiceModule', () => ({AndroidVoiceModule: {}}));
jest.mock('../native/ContactsBridge', () => ({searchContactsByName: jest.fn().mockResolvedValue([])}));
jest.mock('../modules/OfflineSTT', () => ({
  OfflineSTT: {
    isAvailable: jest.fn().mockResolvedValue(false),
    onResult: jest.fn(),
    onPartial: jest.fn(),
    onError: jest.fn(),
  },
}));
jest.mock('../modules/PhiModule', () => ({
  PhiModule: {
    getModelStatus: jest.fn().mockResolvedValue('READY'),
    startModelDownload: jest.fn().mockResolvedValue(undefined),
  },
}));
jest.mock('../utils/llmRouter', () => ({
  routedGenerate: jest.fn(),
  invalidateOnDeviceCache: jest.fn(),
}));

const mockSendToTesla = jest.fn();
const nativeModules = NativeModules as Record<string, unknown>;

function assistantWith(actions: Array<Record<string, unknown>>) {
  return [
    {role: 'user', content: 'navigier mit dem tesla zum marienplatz'},
    {role: 'assistant', content: 'Alles klar.', actions},
  ];
}

const FULL_ADDRESS = 'Marienplatz 1, Altstadt-Lehel, 80331 München, Oberbayern, Bayern, Deutschland';

const TESLA_ACTION = {
  type: 'tesla_navigate',
  destination: 'Marienplatz',
  resolved_address: 'Marienplatz 1, 80331 München', // kurz
  nav_address: FULL_ADDRESS, // voll (Backend)
  lat: 48.137,
  lon: 11.575,
};

beforeEach(() => {
  jest.clearAllMocks();
  jest.replaceProperty(Platform, 'OS', 'android');
  mockMessages = [];
  mockSendToTesla.mockResolvedValue(true);
  nativeModules.TeslaNavModule = {sendToTesla: mockSendToTesla};
  nativeModules.PhiModule = {addListener: jest.fn(), removeListeners: jest.fn()};
  // Standort-Polling des ChatScreens: ohne Berechtigung passiert nichts
  jest.spyOn(PermissionsAndroid, 'check').mockResolvedValue(false);
});

afterEach(() => {
  delete nativeModules.TeslaNavModule;
  delete nativeModules.PhiModule;
  jest.restoreAllMocks();
});

async function renderChat() {
  const utils = render(<ChatScreen />);
  await act(async () => {});
  return utils;
}

/** Text der zuletzt per loadMessages angehaengten Assistenten-Bestaetigung. */
function lastConfirmText(): string {
  const calls = mockLoadMessages.mock.calls;
  const list = calls[calls.length - 1][0] as Array<{role: string; content: string}>;
  return list[list.length - 1].content;
}

describe('tesla_navigate — Bestaetigungs-Karte', () => {
  it('oeffnet die Karte automatisch (1 Tipp statt 2) — ohne Tipp wird NICHT gesendet (kein Auto-Fire)', async () => {
    mockMessages = assistantWith([TESLA_ACTION]);
    const {getByText} = await renderChat();
    expect(getByText('🚗 An Tesla')).toBeTruthy(); // Chip bleibt zum Wiederoeffnen
    expect(getByText('Ziel an Tesla senden')).toBeTruthy(); // Karte ist schon offen
    expect(getByText('An Tesla senden')).toBeTruthy();
    expect(mockSendToTesla).not.toHaveBeenCalled();
    expect(mockLoadMessages).not.toHaveBeenCalled();
  });

  it('zeigt die VOLLE Adresse in der Karte; das gesprochene Ziel steht darueber', async () => {
    mockMessages = assistantWith([TESLA_ACTION]);
    const {getByText, getAllByText} = await renderChat();
    // Chip-Beschreibung + Karten-Feld
    expect(getAllByText(FULL_ADDRESS)).toHaveLength(2);
    expect(getByText('Du sagtest')).toBeTruthy();
  });

  it('EIN Tipp auf "An Tesla senden" ruft sendToTesla(volle Adresse) und meldet "uebergeben"', async () => {
    mockMessages = assistantWith([TESLA_ACTION]);
    const {getByText} = await renderChat();
    await act(async () => {
      fireEvent.press(getByText('An Tesla senden'));
    });
    expect(mockSendToTesla).toHaveBeenCalledTimes(1);
    expect(mockSendToTesla).toHaveBeenCalledWith(FULL_ADDRESS); // NICHT die gekuerzte resolved_address
    // ehrlicher Text: Intent-Erfolg = an die Tesla-App uebergeben, nicht "Auto hat's"
    expect(lastConfirmText()).toBe(`An Tesla-App übergeben: ${FULL_ADDRESS} ✓`);
  });

  it('Langtext-Adresse (> 200 Zeichen) wird vollstaendig gesendet und bricht das Rendern nicht', async () => {
    const long = `${'Sehr lange Strasse '.repeat(14)}1, 80331 München`;
    expect(long.length).toBeGreaterThan(200);
    mockMessages = assistantWith([{type: 'tesla_navigate', destination: 'x', nav_address: long}]);
    const {getByText} = await renderChat();
    await act(async () => {
      fireEvent.press(getByText('An Tesla senden'));
    });
    expect(mockSendToTesla).toHaveBeenCalledWith(long.trim());
  });

  it('Fallback-Kette: ohne nav_address -> resolved_address -> destination', async () => {
    mockMessages = assistantWith([{type: 'tesla_navigate', destination: 'Marienplatz', resolved_address: 'Marienplatz 1, 80331 München'}]);
    const first = await renderChat();
    await act(async () => {
      fireEvent.press(first.getByText('An Tesla senden'));
    });
    expect(mockSendToTesla).toHaveBeenLastCalledWith('Marienplatz 1, 80331 München');
    first.unmount();

    mockMessages = assistantWith([{type: 'tesla_navigate', destination: 'Marienplatz'}]);
    const second = await renderChat();
    expect(second.queryByText('Du sagtest')).toBeNull();
    await act(async () => {
      fireEvent.press(second.getByText('An Tesla senden'));
    });
    expect(mockSendToTesla).toHaveBeenLastCalledWith('Marienplatz');
  });

  it('Doppel-Send-Guard: zwei schnelle Tipps senden nur EINMAL', async () => {
    let finish: (v: boolean) => void = () => {};
    mockSendToTesla.mockImplementation(() => new Promise<boolean>(r => { finish = r; }));
    mockMessages = assistantWith([TESLA_ACTION]);
    const {getByText} = await renderChat();
    const btn = getByText('An Tesla senden');
    await act(async () => {
      fireEvent.press(btn);
      fireEvent.press(btn); // 2. Tipp, waehrend der Intent noch laeuft
    });
    expect(mockSendToTesla).toHaveBeenCalledTimes(1);
    await act(async () => {
      finish(true);
    });
    expect(mockLoadMessages).toHaveBeenCalledTimes(1);
  });

  it('geschlossene Karte poppt nicht wieder auf; der Chip oeffnet sie erneut', async () => {
    mockMessages = assistantWith([TESLA_ACTION]);
    const {getByText, queryByText, rerender} = await renderChat();
    fireEvent.press(getByText('Abbrechen'));
    await act(async () => {});
    expect(queryByText('Ziel an Tesla senden')).toBeNull();
    rerender(<ChatScreen />);
    await act(async () => {});
    expect(queryByText('Ziel an Tesla senden')).toBeNull(); // einmalig
    fireEvent.press(getByText('🚗 An Tesla'));
    await act(async () => {});
    expect(getByText('Ziel an Tesla senden')).toBeTruthy();
    expect(mockSendToTesla).not.toHaveBeenCalled();
  });

  it('Abbrechen sendet nichts', async () => {
    mockMessages = assistantWith([TESLA_ACTION]);
    const {getByText, queryByText} = await renderChat();
    fireEvent.press(getByText('Abbrechen'));
    await act(async () => {});
    expect(queryByText('Ziel an Tesla senden')).toBeNull();
    expect(mockSendToTesla).not.toHaveBeenCalled();
    expect(mockLoadMessages).not.toHaveBeenCalled();
  });

  it('ohne Ziel: keine Auto-Karte; Chip zeigt Karte mit gesperrtem Button "Ziel fehlt"', async () => {
    mockMessages = assistantWith([{type: 'tesla_navigate'}]);
    const { getByText, queryByText } = await renderChat();
    expect(queryByText('Ziel an Tesla senden')).toBeNull();
    fireEvent.press(getByText('🚗 An Tesla'));
    await act(async () => {});
    const btn = getByText('Ziel fehlt');
    await act(async () => {
      fireEvent.press(btn);
    });
    expect(mockSendToTesla).not.toHaveBeenCalled();
  });

  it('Tesla-App nicht installiert -> klare Meldung im Chat', async () => {
    mockSendToTesla.mockRejectedValue(Object.assign(new Error('x'), {code: 'TESLA_NOT_INSTALLED'}));
    mockMessages = assistantWith([TESLA_ACTION]);
    const {getByText} = await renderChat();
    await act(async () => {
      fireEvent.press(getByText('An Tesla senden'));
    });
    expect(lastConfirmText()).toMatch(/Tesla-App nicht installiert/);
  });

  it('sonstiger Sendefehler -> Fehlermeldung statt Absturz; danach ist erneutes Senden wieder moeglich', async () => {
    mockSendToTesla.mockRejectedValueOnce(new Error('boom'));
    mockMessages = assistantWith([TESLA_ACTION]);
    const {getByText} = await renderChat();
    await act(async () => {
      fireEvent.press(getByText('An Tesla senden'));
    });
    expect(lastConfirmText()).toMatch(/fehlgeschlagen/);
    // Guard wurde wieder freigegeben: Chip -> Karte -> Senden klappt
    fireEvent.press(getByText('🚗 An Tesla'));
    await act(async () => {});
    await act(async () => {
      fireEvent.press(getByText('An Tesla senden'));
    });
    expect(mockSendToTesla).toHaveBeenCalledTimes(2);
  });

  it('Render-Sanitierung: Nicht-String-Felder (destination/query/name als Objekt) werfen nicht', async () => {
    mockMessages = assistantWith([
      {type: 'tesla_navigate', destination: {x: 1}, resolved_address: ['a'], nav_address: 42},
      {type: 'navigate', destination: {x: 1}},
    ]);
    const {getByText} = await renderChat();
    fireEvent.press(getByText('🧭 Navigation'));
    await act(async () => {});
    expect(getByText('Ziel')).toBeTruthy(); // Karte rendert, Wert ist ''
  });

  it('die normale navigate-Karte bleibt unveraendert (Handy, "Bestaetigen", kein Tesla, keine Auto-Karte)', async () => {
    const open = jest.spyOn(Linking, 'openURL').mockResolvedValue(true as never);
    mockMessages = assistantWith([{type: 'navigate', destination: 'Marienplatz'}]);
    const {getByText, queryByText} = await renderChat();
    expect(queryByText('Bestätigen')).toBeNull(); // navigate wird NICHT automatisch geoeffnet
    fireEvent.press(getByText('🧭 Navigation'));
    await act(async () => {});
    expect(queryByText('An Tesla senden')).toBeNull();
    await act(async () => {
      fireEvent.press(getByText('Bestätigen'));
    });
    expect(open).toHaveBeenCalledWith('geo:0,0?q=Marienplatz');
    expect(mockSendToTesla).not.toHaveBeenCalled();
  });
});

describe('navigate_disambiguate (nav_type tesla_navigate)', () => {
  const DISAMBIGUATE = {
    type: 'navigate_disambiguate',
    nav_type: 'tesla_navigate',
    query: 'Hauptstraße 1',
    options: [
      {label: 'Hauptstraße 1, 10115 Berlin', address: 'Hauptstraße 1, Mitte, 10115 Berlin, Deutschland', lat: 52.53, lon: 13.38},
      {label: 'Hauptstraße 1, 20095 Hamburg', address: 'Hauptstraße 1, Altstadt, 20095 Hamburg, Deutschland', lat: 53.55, lon: 10.0},
    ],
  };

  it('oeffnet die Auswahlkarte sofort; ohne Tipp wird nicht gesendet', async () => {
    mockMessages = assistantWith([DISAMBIGUATE]);
    const {getByText, getByTestId} = await renderChat();
    expect(getByTestId('nav-disambiguate-card')).toBeTruthy();
    expect(getByText('Ziel für den Tesla wählen')).toBeTruthy();
    expect(getByText('Hauptstraße 1, 10115 Berlin')).toBeTruthy(); // Anzeige = kurzes Label
    expect(getByText('Hauptstraße 1, 20095 Hamburg')).toBeTruthy();
    expect(mockSendToTesla).not.toHaveBeenCalled();
  });

  it('Auswahl eines Treffers sendet die VOLLE Adresse (address), nicht das gekuerzte Label', async () => {
    mockMessages = assistantWith([DISAMBIGUATE]);
    const {getByText, queryByTestId} = await renderChat();
    await act(async () => {
      fireEvent.press(getByText('Hauptstraße 1, 20095 Hamburg'));
    });
    expect(mockSendToTesla).toHaveBeenCalledTimes(1);
    expect(mockSendToTesla).toHaveBeenCalledWith('Hauptstraße 1, Altstadt, 20095 Hamburg, Deutschland');
    expect(lastConfirmText()).toBe('An Tesla-App übergeben: Hauptstraße 1, Altstadt, 20095 Hamburg, Deutschland ✓');
    expect(queryByTestId('nav-disambiguate-card')).toBeNull();
  });

  it('aelteres Backend ohne address: Label wird gesendet', async () => {
    mockMessages = assistantWith([
      {...DISAMBIGUATE, options: [{label: 'Hauptstraße 1, 10115 Berlin', lat: 52.5, lon: 13.4}, {label: 'B'}]},
    ]);
    const {getByText} = await renderChat();
    await act(async () => {
      fireEvent.press(getByText('Hauptstraße 1, 10115 Berlin'));
    });
    expect(mockSendToTesla).toHaveBeenCalledWith('Hauptstraße 1, 10115 Berlin');
  });

  it('Abbrechen schliesst die Karte ohne Versand', async () => {
    mockMessages = assistantWith([DISAMBIGUATE]);
    const {getByText, queryByTestId} = await renderChat();
    fireEvent.press(getByText('Abbrechen'));
    await act(async () => {});
    expect(queryByTestId('nav-disambiguate-card')).toBeNull();
    expect(mockSendToTesla).not.toHaveBeenCalled();
  });

  it('ohne gueltige Optionen: Nicht-gefunden-Karte statt leerer Auswahl', async () => {
    mockMessages = assistantWith([{...DISAMBIGUATE, options: [{label: ' '}, null, {lat: 1}]}]);
    const { getByTestId, queryByTestId } = await renderChat();
    expect(queryByTestId('nav-disambiguate-card')).toBeNull();
    expect(getByTestId('nav-not-found-card')).toBeTruthy();
  });

  it('query als Objekt: kein Render-Fehler', async () => {
    mockMessages = assistantWith([{...DISAMBIGUATE, query: {oops: true}}]);
    const {getByTestId} = await renderChat();
    expect(getByTestId('nav-disambiguate-card')).toBeTruthy();
  });

  it('Handy-Variante (ohne nav_type): Treffer oeffnet geo: mit Koordinaten, kein Tesla', async () => {
    const open = jest.spyOn(Linking, 'openURL').mockResolvedValue(true as never);
    mockMessages = assistantWith([{...DISAMBIGUATE, nav_type: undefined}]);
    const {getByText} = await renderChat();
    expect(getByText('Ziel wählen')).toBeTruthy();
    await act(async () => {
      fireEvent.press(getByText('Hauptstraße 1, 10115 Berlin'));
    });
    expect(mockSendToTesla).not.toHaveBeenCalled();
    expect(open).toHaveBeenCalledTimes(1);
    const url = open.mock.calls[0][0] as string;
    expect(url.startsWith('geo:52.53,13.38?q=52.53,13.38(')).toBe(true);
    expect(lastConfirmText()).toBe('Navigation gestartet');
  });

  it('geschlossene Karte poppt bei erneutem Rendern nicht wieder auf', async () => {
    mockMessages = assistantWith([DISAMBIGUATE]);
    const {getByText, queryByTestId, rerender} = await renderChat();
    fireEvent.press(getByText('Abbrechen'));
    await act(async () => {});
    rerender(<ChatScreen />);
    await act(async () => {});
    expect(queryByTestId('nav-disambiguate-card')).toBeNull();
  });
});

describe('navigate_not_found', () => {
  it('zeigt eine freundliche Fehlermeldung (Tesla) und sendet nichts', async () => {
    mockMessages = assistantWith([{type: 'navigate_not_found', nav_type: 'tesla_navigate', query: 'Xyzstadt'}]);
    const {getByText, getByTestId} = await renderChat();
    expect(getByTestId('nav-not-found-card')).toBeTruthy();
    expect(getByText('Ziel nicht gefunden')).toBeTruthy();
    expect(getByText(/Ich habe „Xyzstadt“ nicht gefunden für den Tesla/)).toBeTruthy();
    expect(mockSendToTesla).not.toHaveBeenCalled();
  });

  it('OK schliesst die Karte', async () => {
    mockMessages = assistantWith([{type: 'navigate_not_found', query: 'Xyzstadt'}]);
    const {getByText, queryByTestId} = await renderChat();
    fireEvent.press(getByText('OK'));
    await act(async () => {});
    expect(queryByTestId('nav-not-found-card')).toBeNull();
  });
});

describe('Bedienbarkeit (Review-Verfeinerungen)', () => {
  it('Idle-Text in Satzschreibung statt "SYS · BEREIT"; Header-Icons haben Labels', async () => {
    const idle = await renderChat();
    expect(idle.getByText('Wie kann ich helfen?')).toBeTruthy();
    expect(idle.queryByText('SYS · BEREIT')).toBeNull();
    idle.unmount();

    mockMessages = assistantWith([{type: 'navigate', destination: 'Marienplatz'}]);
    const withMessages = await renderChat();
    expect(withMessages.getByLabelText('Neuer Chat')).toBeTruthy();
    expect(withMessages.getByLabelText('Antwort war hilfreich')).toBeTruthy();
    expect(withMessages.getByLabelText('Antwort war nicht hilfreich')).toBeTruthy();
  });
});

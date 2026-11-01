import React from 'react';
import {Alert, AppState, NativeModules, Platform} from 'react-native';
import {render, fireEvent, waitFor, act, within} from '@testing-library/react-native';
import OnboardingScreen from '../screens/OnboardingScreen';
import {SETUP_STEPS, type SetupStatus, type SetupStepKey} from '../native/DonnaSetup';

// ─── Mocks ───────────────────────────────────────────────────────────────────
jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => ({top: 0, bottom: 0, left: 0, right: 0}),
}));
// TB4: TtsVoiceSettings importiert den @donna/shared-Barrel (zieht native Module wie notifee).
// Der Onboarding-Test braucht dessen Interna nicht → als Marker-Stub rendern (testID prüft die Einbindung).
jest.mock('../components/TtsVoiceSettings', () => {
  const React = require('react');
  const {View} = require('react-native');
  return () => React.createElement(View, {testID: 'tts-settings-stub'});
});

const mockSetItem = jest.fn().mockResolvedValue(undefined);
const mockRemoveItem = jest.fn().mockResolvedValue(undefined);
jest.mock('@react-native-async-storage/async-storage', () => ({
  __esModule: true,
  default: {
    getItem: jest.fn().mockResolvedValue(null),
    setItem: (...args: unknown[]) => mockSetItem(...args),
    removeItem: (...args: unknown[]) => mockRemoveItem(...args),
  },
}));

const REQUIRED: SetupStepKey[] = ['mic', 'notifications', 'overlay', 'assistant', 'battery'];
const FEATURES: SetupStepKey[] = SETUP_STEPS.filter(s => s.group === 'features').map(s => s.key);

/** Vollständiger Status; alles offen außer den angegebenen Schritten. */
function makeStatus(granted: SetupStepKey[] = [], overrides: SetupStatus = {}): SetupStatus {
  const status: SetupStatus = {};
  for (const step of SETUP_STEPS) {
    status[step.key] = {granted: granted.includes(step.key), available: true, blocked: false};
  }
  return {...status, ...overrides};
}

const mockNative = {
  getSetupStatus: jest.fn(),
  requestStep: jest.fn(),
  openBixbyGuide: jest.fn(),
};

let appStateListeners: ((state: string) => void)[] = [];
/** Löst alle registrierten AppState-Listener aus (Screen + Busy-Timeout-Effekt). */
function emitAppState(state: string) {
  appStateListeners.slice().forEach(l => l(state));
}

beforeEach(() => {
  jest.clearAllMocks();
  jest.replaceProperty(Platform, 'OS', 'android');
  (NativeModules as Record<string, unknown>).DonnaSetupModule = mockNative;
  mockNative.getSetupStatus.mockResolvedValue(makeStatus());
  mockNative.openBixbyGuide.mockResolvedValue(undefined);
  mockNative.requestStep.mockImplementation(async (key: SetupStepKey) => ({
    key,
    granted: true,
    available: true,
    blocked: false,
  }));
  appStateListeners = [];
  jest.spyOn(AppState, 'addEventListener').mockImplementation(((
    _type: string,
    cb: (s: string) => void,
  ) => {
    appStateListeners.push(cb);
    return {
      remove: () => {
        appStateListeners = appStateListeners.filter(l => l !== cb);
      },
    };
  }) as never);
});

afterEach(() => {
  jest.restoreAllMocks();
  delete (NativeModules as Record<string, unknown>).DonnaSetupModule;
});

async function renderScreen(props: Partial<React.ComponentProps<typeof OnboardingScreen>> = {}) {
  const onFinish = jest.fn();
  const utils = render(<OnboardingScreen onFinish={onFinish} {...props} />);
  await act(async () => {});
  return {...utils, onFinish};
}

// ─── Status-Rendering ────────────────────────────────────────────────────────
describe('OnboardingScreen — Status-Rendering', () => {
  it('zeigt alle drei Gruppen und alle 17 Schritte', async () => {
    const {getByText} = await renderScreen();
    expect(getByText('Donna einrichten')).toBeTruthy();
    expect(getByText('Pflicht')).toBeTruthy();
    expect(getByText('Für Funktionen (empfohlen)')).toBeTruthy();
    expect(getByText('Erweitert (optional)')).toBeTruthy();
    for (const step of SETUP_STEPS) {
      expect(getByText(step.title)).toBeTruthy();
    }
  });

  it('TB4: bindet den TTS-Einstellungs-Screen (TtsVoiceSettings) in die Einrichtung ein', async () => {
    const {getByTestId} = await renderScreen();
    expect(getByTestId('tts-settings-stub')).toBeTruthy();
  });

  it('markiert erledigte Schritte, zählt den Pflicht-Fortschritt und blendet deren Button aus', async () => {
    mockNative.getSetupStatus.mockResolvedValue(makeStatus(['mic', 'overlay']));
    const {getByText, getByTestId, queryByLabelText, getByLabelText} = await renderScreen();
    expect(getByText('Pflicht: 2 von 5 erledigt')).toBeTruthy();
    expect(within(getByTestId('step-mic')).getByText('Erledigt')).toBeTruthy();
    expect(within(getByTestId('step-notifications')).getByText('Offen – als Nächstes')).toBeTruthy();
    expect(queryByLabelText('Mikrofon einrichten')).toBeNull();
    expect(getByLabelText('Benachrichtigungen einrichten')).toBeTruthy();
  });

  it('offene Schritte haben einen barrierefreien Button (Rolle + Label)', async () => {
    const {getByLabelText} = await renderScreen();
    expect(getByLabelText('Mikrofon einrichten').props.accessibilityRole).toBe('button');
  });

  it('zeigt "Nicht verfügbar" für nicht anwendbare Schritte ohne Button', async () => {
    mockNative.getSetupStatus.mockResolvedValue(
      makeStatus([], {health: {granted: false, available: false}}),
    );
    const {getByTestId, queryByLabelText} = await renderScreen();
    expect(
      within(getByTestId('step-health')).getByText('Auf diesem Gerät nicht verfügbar'),
    ).toBeTruthy();
    expect(queryByLabelText('Gesundheitsdaten (Health Connect) einrichten')).toBeNull();
  });

  it('zeigt "nicht nötig", wenn granted aber nicht available (z. B. Android < 13)', async () => {
    mockNative.getSetupStatus.mockResolvedValue(
      makeStatus([], {notifications: {granted: true, available: false}}),
    );
    const {getByTestId} = await renderScreen();
    expect(
      within(getByTestId('step-notifications')).getByText('Auf dieser Android-Version nicht nötig'),
    ).toBeTruthy();
  });

  it('sperrt "Standort im Hintergrund", bis Standort erteilt ist', async () => {
    const {getByTestId, getByLabelText} = await renderScreen();
    expect(
      within(getByTestId('step-location_bg')).getByText('Zuerst „Standort“ erlauben'),
    ).toBeTruthy();
    expect(
      getByLabelText('Standort im Hintergrund einrichten').props.accessibilityState.disabled,
    ).toBe(true);
  });

  it('schaltet "Standort im Hintergrund" frei, sobald Standort erteilt ist', async () => {
    mockNative.getSetupStatus.mockResolvedValue(makeStatus(['location']));
    const {getByLabelText} = await renderScreen();
    expect(
      getByLabelText('Standort im Hintergrund einrichten').props.accessibilityState.disabled,
    ).toBe(false);
  });

  it('dauerhaft abgelehnte Permission führt zu "Einstellungen öffnen"', async () => {
    mockNative.getSetupStatus.mockResolvedValue(
      makeStatus([], {camera: {granted: false, available: true, blocked: true}}),
    );
    const {getByTestId, getByLabelText} = await renderScreen();
    expect(
      within(getByTestId('step-camera')).getByText('Abgelehnt – in den App-Einstellungen erlauben'),
    ).toBeTruthy();
    expect(getByLabelText('Kamera in den Einstellungen öffnen')).toBeTruthy();
  });

  it('listet offene Punkte; bei vollständiger Einrichtung "Alles eingerichtet"', async () => {
    const open = await renderScreen();
    const summary = within(open.getByTestId('setup-summary'));
    expect(summary.getByText('Noch offen')).toBeTruthy();
    expect(summary.getByText('• Mikrofon (Pflicht)')).toBeTruthy();
    expect(summary.getByText('• Kamera')).toBeTruthy();
    // Erweiterte Schritte stehen NICHT unter "Noch offen", sondern getrennt als "Optional"
    expect(summary.queryByText('• Eingabehilfe (Bildschirmtexte lesen)')).toBeNull();
    expect(summary.getByText(/^Optional \(erweitert\):/)).toBeTruthy();
    open.unmount();

    mockNative.getSetupStatus.mockResolvedValue(makeStatus(SETUP_STEPS.map(s => s.key)));
    const all = await renderScreen();
    expect(all.getByText('✓ Alles eingerichtet')).toBeTruthy();
  });

  it('zeigt einen Fehler bei fehlgeschlagener Prüfung und erlaubt erneutes Prüfen', async () => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
    mockNative.getSetupStatus.mockRejectedValueOnce(new Error('boom'));
    const {getByText, getByLabelText} = await renderScreen();
    expect(getByText('Der Status konnte nicht geprüft werden.')).toBeTruthy();
    await act(async () => {
      fireEvent.press(getByLabelText('Status erneut prüfen'));
    });
    await waitFor(() => expect(getByText('Pflicht: 0 von 5 erledigt')).toBeTruthy());
    warn.mockRestore();
  });
});

// ─── Schritte einrichten / Neuprüfung ────────────────────────────────────────
describe('OnboardingScreen — Schritte', () => {
  it('Einrichten ruft requestStep mit dem Schlüssel auf und setzt den Schritt auf ✓', async () => {
    const {getByLabelText, queryByLabelText} = await renderScreen();
    // Nach dem Dialog meldet das native Modul "Mikrofon" als erteilt
    mockNative.getSetupStatus.mockResolvedValue(makeStatus(['mic']));
    await act(async () => {
      fireEvent.press(getByLabelText('Mikrofon einrichten'));
    });
    expect(mockNative.requestStep).toHaveBeenCalledWith('mic');
    await waitFor(() => expect(queryByLabelText('Mikrofon einrichten')).toBeNull());
  });

  it('prüft den Status bei Rückkehr in die App (AppState → active) neu', async () => {
    const {getByLabelText, queryByLabelText} = await renderScreen();
    expect(getByLabelText('Mikrofon einrichten')).toBeTruthy();
    // Nutzer erteilt "Mikrofon" in den Systemeinstellungen und kommt zurück
    mockNative.getSetupStatus.mockResolvedValue(makeStatus(['mic']));
    await act(async () => {
      emitAppState('active');
    });
    await waitFor(() => expect(queryByLabelText('Mikrofon einrichten')).toBeNull());
  });

  it('zeigt eine verständliche Fehlermeldung, wenn ein Schritt scheitert', async () => {
    mockNative.requestStep.mockRejectedValueOnce(
      Object.assign(new Error('x'), {code: 'E_NO_INTENT'}),
    );
    const {getByLabelText, getByText} = await renderScreen();
    await act(async () => {
      fireEvent.press(getByLabelText('Mikrofon einrichten'));
    });
    expect(
      getByText(
        'Die passende Einstellungsseite wurde nicht gefunden. Bitte in den Android-Einstellungen suchen.',
      ),
    ).toBeTruthy();
  });

  it('erweiterte Schritte verlangen eine bewusste Bestätigung', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    const {getByLabelText} = await renderScreen();
    await act(async () => {
      fireEvent.press(getByLabelText('Eingabehilfe (Bildschirmtexte lesen) einrichten'));
    });
    // Ohne Bestätigung kein Request
    expect(mockNative.requestStep).not.toHaveBeenCalled();
    expect(alertSpy).toHaveBeenCalledTimes(1);
    const buttons = alertSpy.mock.calls[0][2] as {text: string; onPress?: () => void}[];
    expect(buttons.map(b => b.text)).toEqual(['Abbrechen', 'Verstanden, einrichten']);
    await act(async () => {
      buttons[1].onPress?.();
    });
    expect(mockNative.requestStep).toHaveBeenCalledWith('accessibility');
  });

  it('Bixby-Hinweis öffnet die Anleitung (kein Berechtigungs-Schritt)', async () => {
    const {getByLabelText} = await renderScreen();
    await act(async () => {
      fireEvent.press(getByLabelText('Anleitung für den Bixby-Schnellbefehl öffnen'));
    });
    expect(mockNative.openBixbyGuide).toHaveBeenCalled();
    expect(mockNative.requestStep).not.toHaveBeenCalled();
  });
});

// ─── Pflicht-Gate ────────────────────────────────────────────────────────────
describe('OnboardingScreen — Pflicht-Gate', () => {
  it('bietet "Weiter zu Donna" NICHT an, solange ein Pflicht-Schritt offen ist', async () => {
    mockNative.getSetupStatus.mockResolvedValue(makeStatus(['mic', 'notifications', 'overlay']));
    const {queryByText, getByText, onFinish} = await renderScreen();
    expect(queryByText('Weiter zu Donna')).toBeNull();
    expect(
      getByText('Noch 2 Pflicht-Schritte offen – danach geht es weiter zu Donna.'),
    ).toBeTruthy();
    expect(onFinish).not.toHaveBeenCalled();
    expect(mockSetItem).not.toHaveBeenCalled();
  });

  it('Footer führt zum nächsten offenen Pflicht-Schritt', async () => {
    mockNative.getSetupStatus.mockResolvedValue(makeStatus(['mic']));
    const {getByLabelText, onFinish} = await renderScreen();
    await act(async () => {
      fireEvent.press(getByLabelText('Weiter: Benachrichtigungen einrichten'));
    });
    expect(mockNative.requestStep).toHaveBeenCalledWith('notifications');
    expect(onFinish).not.toHaveBeenCalled();
  });

  it('optionale Schritte blockieren das Gate nicht', async () => {
    mockNative.getSetupStatus.mockResolvedValue(makeStatus(REQUIRED));
    const {getByText} = await renderScreen();
    expect(getByText('Weiter zu Donna')).toBeTruthy();
  });

  it('"Weiter zu Donna" setzt onboarding_done und beendet den Wizard', async () => {
    mockNative.getSetupStatus.mockResolvedValue(makeStatus(REQUIRED));
    const {getByText, onFinish} = await renderScreen();
    await act(async () => {
      fireEvent.press(getByText('Weiter zu Donna'));
    });
    expect(mockSetItem).toHaveBeenCalledWith('onboarding_done', 'true');
    expect(onFinish).toHaveBeenCalledTimes(1);
  });

  it('Gate öffnet sich, sobald der letzte Pflicht-Schritt erledigt wird', async () => {
    mockNative.getSetupStatus.mockResolvedValue(
      makeStatus(REQUIRED.filter(k => k !== 'battery')),
    );
    const {getByLabelText, queryByText, getByText} = await renderScreen();
    expect(queryByText('Weiter zu Donna')).toBeNull();
    mockNative.getSetupStatus.mockResolvedValue(makeStatus(REQUIRED));
    await act(async () => {
      fireEvent.press(getByLabelText('Akku-Optimierung aus einrichten'));
    });
    await waitFor(() => expect(getByText('Weiter zu Donna')).toBeTruthy());
  });
});

// ─── Erneuter Einstieg (rerun) ───────────────────────────────────────────────
describe('OnboardingScreen — rerun', () => {
  it('ist jederzeit schließbar, auch bei offenen Pflicht-Schritten, ohne Flag zu setzen', async () => {
    const {getByLabelText, onFinish} = await renderScreen({mode: 'rerun'});
    fireEvent.press(getByLabelText('Einrichtung schließen'));
    expect(onFinish).toHaveBeenCalledTimes(1);
    expect(mockSetItem).not.toHaveBeenCalled();
  });

  it('zeigt "Fertig" statt "Weiter zu Donna", wenn alle Pflicht-Schritte erledigt sind', async () => {
    mockNative.getSetupStatus.mockResolvedValue(makeStatus(REQUIRED));
    const {getByText, queryByText} = await renderScreen({mode: 'rerun'});
    expect(getByText('Fertig')).toBeTruthy();
    expect(queryByText('Weiter zu Donna')).toBeNull();
  });

  it('der initiale Modus hat keinen Schließen-Button', async () => {
    const {queryByLabelText} = await renderScreen();
    expect(queryByLabelText('Einrichtung schließen')).toBeNull();
  });
});

// ─── Nachbesserung Review-Rat Runde 1 ────────────────────────────────────────
describe('OnboardingScreen — Notausgang "Später einrichten" (A)', () => {
  it('ist im Gate-Modus bei offenen Pflicht-Schritten verfügbar und öffnet den Chat OHNE onboarding_done', async () => {
    const {getByLabelText, onFinish} = await renderScreen();
    await act(async () => {
      fireEvent.press(getByLabelText('Später einrichten und zu Donna wechseln'));
    });
    expect(mockSetItem).toHaveBeenCalledWith('onboarding_deferred', 'true');
    expect(mockSetItem).not.toHaveBeenCalledWith('onboarding_done', expect.anything());
    expect(onFinish).toHaveBeenCalledTimes(1);
  });

  it('zeigt nach einem fehlgeschlagenen Pflicht-Schritt den Hinweis "Klappt nicht?"', async () => {
    mockNative.requestStep.mockRejectedValueOnce(
      Object.assign(new Error('x'), {code: 'E_NO_INTENT'}),
    );
    const {getByLabelText, getByText} = await renderScreen();
    await act(async () => {
      fireEvent.press(getByLabelText('Mikrofon einrichten'));
    });
    expect(getByText('Klappt nicht? Später einrichten')).toBeTruthy();
  });

  it('ist weder im rerun-Modus noch bei erledigten Pflicht-Schritten sichtbar', async () => {
    const rerun = await renderScreen({mode: 'rerun'});
    expect(rerun.queryByLabelText('Später einrichten und zu Donna wechseln')).toBeNull();
    rerun.unmount();
    mockNative.getSetupStatus.mockResolvedValue(makeStatus(REQUIRED));
    const done = await renderScreen();
    expect(done.queryByLabelText('Später einrichten und zu Donna wechseln')).toBeNull();
  });

  it('Ladefehler: "Trotzdem fortfahren" öffnet den Chat ohne Flags zu schreiben', async () => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
    mockNative.getSetupStatus.mockRejectedValue(new Error('boom'));
    const {getByLabelText, onFinish} = await renderScreen();
    fireEvent.press(getByLabelText('Trotzdem fortfahren und zu Donna wechseln'));
    expect(onFinish).toHaveBeenCalledTimes(1);
    expect(mockSetItem).not.toHaveBeenCalled();
    warn.mockRestore();
  });
});

describe('OnboardingScreen — Timeout (B)', () => {
  it('ein hängender nativer Status endet im Fehlerzustand mit Retry statt Dauer-Spinner', async () => {
    jest.useFakeTimers();
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
    mockNative.getSetupStatus.mockReturnValue(new Promise(() => {}));
    const {getByText, getByLabelText} = render(<OnboardingScreen onFinish={jest.fn()} />);
    await act(async () => {});
    expect(getByText('Status wird geprüft …')).toBeTruthy();
    await act(async () => {
      await jest.advanceTimersByTimeAsync(8000);
    });
    expect(getByText('Der Status konnte nicht geprüft werden.')).toBeTruthy();
    expect(getByLabelText('Status erneut prüfen')).toBeTruthy();
    jest.useRealTimers();
    warn.mockRestore();
  });
});

describe('OnboardingScreen — Hängender Permission-Callback (D)', () => {
  it('AppState → active gibt gesperrte Buttons wieder frei', async () => {
    mockNative.requestStep.mockReturnValue(new Promise(() => {}));
    const {getByLabelText} = await renderScreen();
    await act(async () => {
      fireEvent.press(getByLabelText('Mikrofon einrichten'));
    });
    // Während "busy" sind alle Schritte gesperrt
    expect(getByLabelText('Benachrichtigungen einrichten').props.accessibilityState.disabled).toBe(true);
    await act(async () => {
      emitAppState('active');
    });
    await waitFor(() =>
      expect(getByLabelText('Benachrichtigungen einrichten').props.accessibilityState.disabled).toBe(false),
    );
  });
});

describe('OnboardingScreen — Done-Logik und Überspringen (G)', () => {
  it('Pflicht-Schritte haben kein "Nicht jetzt"', async () => {
    const {queryByLabelText} = await renderScreen();
    expect(queryByLabelText('Mikrofon nicht jetzt einrichten')).toBeNull();
    expect(queryByLabelText('Kamera nicht jetzt einrichten')).toBeTruthy();
  });

  it('"Nicht jetzt" markiert den Schritt grau als "Übersprungen" und speichert ihn', async () => {
    const {getByLabelText, getByTestId} = await renderScreen();
    await act(async () => {
      fireEvent.press(getByLabelText('Kamera nicht jetzt einrichten'));
    });
    expect(
      within(getByTestId('step-camera')).getByText('Übersprungen'),
    ).toBeTruthy();
    expect(mockSetItem).toHaveBeenCalledWith('onboarding_skipped', JSON.stringify(['camera']));
  });

  it('übersprungene Schritte verschwinden aus "Noch offen"', async () => {
    const {getByLabelText, getByTestId} = await renderScreen();
    await act(async () => {
      fireEvent.press(getByLabelText('Kamera nicht jetzt einrichten'));
    });
    expect(within(getByTestId('setup-summary')).queryByText('• Kamera')).toBeNull();
  });

  it('"Doch einrichten" hebt das Überspringen wieder auf', async () => {
    const {getByLabelText} = await renderScreen();
    await act(async () => {
      fireEvent.press(getByLabelText('Kamera nicht jetzt einrichten'));
    });
    await act(async () => {
      fireEvent.press(getByLabelText('Kamera einrichten'));
    });
    expect(mockNative.requestStep).toHaveBeenCalledWith('camera');
    expect(mockSetItem).toHaveBeenLastCalledWith('onboarding_skipped', JSON.stringify([]));
  });

  it('"Alles eingerichtet" gilt für Pflicht + Funktionen; Erweitert steht getrennt als Optional', async () => {
    mockNative.getSetupStatus.mockResolvedValue(makeStatus([...REQUIRED, ...FEATURES]));
    const {getByText, getByTestId, queryByText} = await renderScreen();
    expect(getByText('✓ Alles eingerichtet')).toBeTruthy();
    expect(queryByText('Noch offen')).toBeNull();
    expect(
      within(getByTestId('setup-summary')).getByText(/^Optional \(erweitert\): Eingabehilfe/),
    ).toBeTruthy();
  });
});

describe('OnboardingScreen — Statustexte (O, R1-7)', () => {
  it('Hintergrund-Standort zeigt sanft "Noch nicht auf „Immer“ gestellt" statt "Abgelehnt"', async () => {
    mockNative.getSetupStatus.mockResolvedValue(
      makeStatus(['location'], {location_bg: {granted: false, available: true, blocked: true}}),
    );
    const {getByTestId, queryByText} = await renderScreen();
    expect(
      within(getByTestId('step-location_bg')).getByText('Noch nicht auf „Immer“ gestellt'),
    ).toBeTruthy();
    expect(queryByText('Abgelehnt – in den App-Einstellungen erlauben')).toBeNull();
  });

  it('Standort-Hinweis verlangt "Genau"', async () => {
    const {getByText} = await renderScreen();
    expect(getByText('Bitte „Genau“ wählen – „Ungefähr“ reicht nicht.')).toBeTruthy();
  });

  it('ein Fehler wird nicht mehr unter einem inzwischen erledigten Schritt angezeigt', async () => {
    mockNative.requestStep.mockRejectedValueOnce(
      Object.assign(new Error('x'), {code: 'E_NO_INTENT'}),
    );
    const {getByLabelText, queryByText} = await renderScreen();
    mockNative.getSetupStatus.mockResolvedValue(makeStatus(['mic']));
    await act(async () => {
      fireEvent.press(getByLabelText('Mikrofon einrichten'));
    });
    await waitFor(() =>
      expect(
        queryByText(
          'Die passende Einstellungsseite wurde nicht gefunden. Bitte in den Android-Einstellungen suchen.',
        ),
      ).toBeNull(),
    );
  });
});

// ─── Schluss-Härtung (Runde 2) ───────────────────────────────────────────────
describe('OnboardingScreen — Notausgang immer klickbar (R1-F2)', () => {
  it('"Später einrichten" bleibt auch während ein Schritt "busy" ist klickbar', async () => {
    mockNative.requestStep.mockReturnValue(new Promise(() => {}));
    const {getByLabelText, onFinish} = await renderScreen();
    await act(async () => {
      fireEvent.press(getByLabelText('Mikrofon einrichten'));
    });
    const defer = getByLabelText('Später einrichten und zu Donna wechseln');
    expect(defer.props.accessibilityState?.disabled).toBeFalsy();
    await act(async () => {
      fireEvent.press(defer);
    });
    expect(mockSetItem).toHaveBeenCalledWith('onboarding_deferred', 'true');
    expect(onFinish).toHaveBeenCalledTimes(1);
  });

  it('harter Timeout: nach 10 s im Vordergrund ist "busy" aufgehoben', async () => {
    jest.useFakeTimers();
    mockNative.requestStep.mockReturnValue(new Promise(() => {}));
    const { getByLabelText } = render(<OnboardingScreen onFinish={jest.fn()} />);
    await act(async () => {});
    await act(async () => {
      fireEvent.press(getByLabelText('Mikrofon einrichten'));
    });
    expect(getByLabelText('Benachrichtigungen einrichten').props.accessibilityState.disabled).toBe(true);
    await act(async () => {
      await jest.advanceTimersByTimeAsync(10000);
    });
    expect(getByLabelText('Benachrichtigungen einrichten').props.accessibilityState.disabled).toBe(false);
    jest.useRealTimers();
  });

  it('läuft die Uhr nicht, solange die App im System-Dialog ist (nicht aktiv)', async () => {
    jest.useFakeTimers();
    mockNative.requestStep.mockReturnValue(new Promise(() => {}));
    const { getByLabelText } = render(<OnboardingScreen onFinish={jest.fn()} />);
    await act(async () => {});
    await act(async () => {
      fireEvent.press(getByLabelText('Mikrofon einrichten'));
    });
    await act(async () => {
      emitAppState('background');
      await jest.advanceTimersByTimeAsync(30000);
    });
    // Nutzer ist noch im System-Dialog → Schritt bleibt busy
    expect(getByLabelText('Benachrichtigungen einrichten').props.accessibilityState.disabled).toBe(true);
    await act(async () => {
      emitAppState('active');
    });
    expect(getByLabelText('Benachrichtigungen einrichten').props.accessibilityState.disabled).toBe(false);
    jest.useRealTimers();
  });
});

describe('OnboardingScreen — Done-Flag und Schließen (R4-F1)', () => {
  it('"Weiter zu Donna" löscht das Verschieben-Flag (Reinstall bekommt wieder das Gate)', async () => {
    mockNative.getSetupStatus.mockResolvedValue(makeStatus(REQUIRED));
    const {getByText} = await renderScreen();
    await act(async () => {
      fireEvent.press(getByText('Weiter zu Donna'));
    });
    expect(mockSetItem).toHaveBeenCalledWith('onboarding_done', 'true');
    expect(mockRemoveItem).toHaveBeenCalledWith('onboarding_deferred');
  });

  it('rerun: Schließen mit erledigten Pflicht-Schritten setzt done und löscht deferred', async () => {
    mockNative.getSetupStatus.mockResolvedValue(makeStatus(REQUIRED));
    const {getByLabelText, onFinish} = await renderScreen({mode: 'rerun'});
    await act(async () => {
      fireEvent.press(getByLabelText('Einrichtung schließen'));
    });
    expect(mockSetItem).toHaveBeenCalledWith('onboarding_done', 'true');
    expect(mockRemoveItem).toHaveBeenCalledWith('onboarding_deferred');
    expect(onFinish).toHaveBeenCalledTimes(1);
  });

  it('rerun: Schließen bei offenen Pflicht-Schritten ändert keine Flags', async () => {
    const {getByLabelText, onFinish} = await renderScreen({mode: 'rerun'});
    await act(async () => {
      fireEvent.press(getByLabelText('Einrichtung schließen'));
    });
    expect(mockSetItem).not.toHaveBeenCalled();
    expect(mockRemoveItem).not.toHaveBeenCalled();
    expect(onFinish).toHaveBeenCalledTimes(1);
  });
});

describe('OnboardingScreen — abhängige Schritte (R6-Logik)', () => {
  it('wird "Standort" übersprungen, gilt "Standort im Hintergrund" automatisch als übersprungen', async () => {
    const {getByLabelText, getByTestId, queryByLabelText} = await renderScreen();
    await act(async () => {
      fireEvent.press(getByLabelText('Standort nicht jetzt einrichten'));
    });
    const bg = within(getByTestId('step-location_bg'));
    expect(bg.getByText('Übersprungen (Standort übersprungen)')).toBeTruthy();
    // kein eigener Button / kein "Nicht jetzt" für den abhängigen Schritt
    expect(queryByLabelText('Standort im Hintergrund einrichten')).toBeNull();
    expect(queryByLabelText('Standort im Hintergrund nicht jetzt einrichten')).toBeNull();
  });

  it('"Alles eingerichtet" ist erreichbar, wenn Standort übersprungen wurde', async () => {
    const withoutLocation = FEATURES.filter(k => k !== 'location' && k !== 'location_bg');
    mockNative.getSetupStatus.mockResolvedValue(makeStatus([...REQUIRED, ...withoutLocation]));
    const {getByLabelText, getByText} = await renderScreen();
    await act(async () => {
      fireEvent.press(getByLabelText('Standort nicht jetzt einrichten'));
    });
    expect(getByText('✓ Alles eingerichtet')).toBeTruthy();
  });
});

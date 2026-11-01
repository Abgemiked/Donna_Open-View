import {NativeModules, Platform} from 'react-native';
import {
  SETUP_STEPS,
  getSetupStatusWithTimeout,
  isAllSetUp,
  isRequiredComplete,
  isStepLocked,
  isStepSkipped,
  missingRequired,
  nextStep,
  openCoreSteps,
  openOptionalSteps,
  parseSkipped,
  stepsOfGroup,
  type SetupStatus,
  type SetupStepKey,
} from '../native/DonnaSetup';
import {hasMissingRequired, markOnboardingDone, shouldShowOnboarding} from '../utils/onboardingGate';

const mockGetItem = jest.fn();
const mockRemoveItem = jest.fn().mockResolvedValue(undefined);
const mockSetItem = jest.fn().mockResolvedValue(undefined);
jest.mock('@react-native-async-storage/async-storage', () => ({
  __esModule: true,
  default: {
    getItem: (...a: unknown[]) => mockGetItem(...a),
    setItem: (...a: unknown[]) => mockSetItem(...a),
    removeItem: (...a: unknown[]) => mockRemoveItem(...a),
  },
}));

const REQUIRED: SetupStepKey[] = ['mic', 'notifications', 'overlay', 'assistant', 'battery'];

function makeStatus(granted: SetupStepKey[] = []): SetupStatus {
  const s: SetupStatus = {};
  for (const step of SETUP_STEPS) {
    s[step.key] = {granted: granted.includes(step.key), available: true};
  }
  return s;
}

describe('Schritt-Katalog', () => {
  it('enthält 17 eindeutige Schritte in 3 Gruppen (5 Pflicht, 8 Funktionen, 4 Erweitert)', () => {
    expect(SETUP_STEPS).toHaveLength(17);
    expect(new Set(SETUP_STEPS.map(s => s.key)).size).toBe(17);
    expect(stepsOfGroup('required').map(s => s.key)).toEqual(REQUIRED);
    expect(stepsOfGroup('features')).toHaveLength(8);
    expect(stepsOfGroup('advanced')).toHaveLength(4);
  });

  it('erweiterte (invasive) Schritte verlangen eine Bestätigung, Pflicht-Schritte nicht', () => {
    expect(stepsOfGroup('advanced').every(s => s.confirm != null)).toBe(true);
    expect(stepsOfGroup('required').every(s => s.confirm == null)).toBe(true);
  });

  it('exakte Wecker sind nur empfohlen, nicht Pflicht', () => {
    expect(SETUP_STEPS.find(s => s.key === 'exact_alarm')?.group).toBe('features');
  });

  it('Zustimmungstexte benennen die Übertragung an den Donna-Server ehrlich', () => {
    const byKey = (k: string) => SETUP_STEPS.find(s => s.key === k)!;
    const a11y = byKey('accessibility');
    // Auto-Push aus WhatsApp/YouTube/TikTok/News muss im Zustimmungsdialog stehen
    expect(a11y.confirm?.message).toContain('WhatsApp');
    expect(a11y.confirm?.message).toContain('YouTube');
    expect(a11y.confirm?.message).toContain('TikTok');
    expect(a11y.confirm?.message).toContain('Donna-Server');
    expect(a11y.confirm?.message).toContain('automatisch');
    expect(a11y.description).toContain('Donna-Server');
    expect(byKey('location').description).toContain('Donna-Server');
    expect(byKey('usage_stats').description).toContain('Donna-Server');
    expect(byKey('usage_stats').confirm?.message).toContain('Donna-Server');
    expect(byKey('health').description).toContain('Donna-Server');
  });

  it('jede dependsOn-Referenz zeigt auf einen existierenden Schritt', () => {
    const keys = new Set(SETUP_STEPS.map(s => s.key));
    for (const s of SETUP_STEPS) {
      if (s.dependsOn) {
        expect(keys.has(s.dependsOn)).toBe(true);
      }
    }
  });
});

describe('Gate-Logik', () => {
  it('isRequiredComplete verlangt alle Pflicht-Schritte', () => {
    expect(isRequiredComplete(makeStatus(REQUIRED))).toBe(true);
    expect(isRequiredComplete(makeStatus(REQUIRED.slice(1)))).toBe(false);
    expect(isRequiredComplete({})).toBe(false);
  });

  it('missingRequired listet nur offene Pflicht-Schritte', () => {
    expect(missingRequired(makeStatus(['mic', 'overlay'])).map(s => s.key)).toEqual([
      'notifications',
      'assistant',
      'battery',
    ]);
  });

  it('nextStep: Pflicht zuerst, dann empfohlen, nie erweitert, gesperrte/nicht verfügbare übersprungen', () => {
    expect(nextStep(makeStatus())?.key).toBe('mic');
    expect(nextStep(makeStatus(['mic']))?.key).toBe('notifications');
    // alle Pflicht erledigt → erster empfohlener Schritt (exakte Wecker)
    expect(nextStep(makeStatus(REQUIRED))?.key).toBe('exact_alarm');
    // Standort erledigt → Hintergrund-Standort ist freigeschaltet und kommt vor Kontakte
    expect(nextStep(makeStatus([...REQUIRED, 'exact_alarm', 'location']))?.key).toBe('location_bg');
    // Standort nicht verfügbar → Hintergrund-Standort gesperrt, nächster ist Kontakte
    const lockedOnly = makeStatus([...REQUIRED, 'exact_alarm']);
    lockedOnly.location = {granted: false, available: false};
    expect(nextStep(lockedOnly)?.key).toBe('contacts');
    // übersprungene Schritte werden nicht vorgeschlagen
    expect(nextStep(makeStatus(REQUIRED), ['exact_alarm', 'location'])?.key).toBe('contacts');
    // alle empfohlenen erledigt, nur erweiterte offen → kein automatischer Vorschlag
    const features = stepsOfGroup('features').map(s => s.key);
    expect(nextStep(makeStatus([...REQUIRED, ...features]))).toBeUndefined();
  });

  it('isStepLocked folgt dependsOn', () => {
    const bg = SETUP_STEPS.find(s => s.key === 'location_bg')!;
    expect(isStepLocked(makeStatus(), bg)).toBe(true);
    expect(isStepLocked(makeStatus(['location']), bg)).toBe(false);
  });

  it('openCoreSteps ignoriert erledigte, nicht verfügbare und übersprungene Schritte', () => {
    const status = makeStatus(REQUIRED);
    status.camera = {granted: false, available: false};
    const keys = openCoreSteps(status, ['phone']).map(s => s.key);
    expect(keys).not.toContain('mic');
    expect(keys).not.toContain('camera');
    expect(keys).not.toContain('phone');
    expect(keys).toContain('calendar');
    // Erweiterte Schritte zählen nie zu "offen"
    expect(keys).not.toContain('accessibility');
  });
});

describe('Done-Logik ("Alles eingerichtet")', () => {
  const features = stepsOfGroup('features').map(s => s.key);

  it('gilt für Pflicht + Funktionen; Erweitert zählt nicht', () => {
    const status = makeStatus([...REQUIRED, ...features]);
    expect(isAllSetUp(status)).toBe(true);
    // erweiterte Schritte sind weiter offen, aber getrennt als "Optional" geführt
    expect(openOptionalSteps(status).map(s => s.key)).toEqual([
      'accessibility',
      'notif_listener',
      'usage_stats',
      'health',
    ]);
  });

  it('ein offener Pflicht- oder Funktions-Schritt verhindert "Alles eingerichtet"', () => {
    expect(isAllSetUp(makeStatus([...REQUIRED]))).toBe(false);
    expect(isAllSetUp(makeStatus([...features]))).toBe(false);
  });

  it('übersprungene Funktions-Schritte gelten als erledigt, übersprungene Pflicht nie', () => {
    const status = makeStatus(REQUIRED);
    expect(isAllSetUp(status, features)).toBe(true);
    const missingMic = makeStatus(REQUIRED.filter(k => k !== 'mic'));
    const mic = SETUP_STEPS.find(s => s.key === 'mic')!;
    expect(isStepSkipped(missingMic, ['mic'], mic)).toBe(false);
    expect(isAllSetUp(missingMic, [...features, 'mic' as SetupStepKey])).toBe(false);
  });

  it('übersprungene erweiterte Schritte verschwinden aus "Optional"', () => {
    const status = makeStatus([...REQUIRED, ...features]);
    expect(openOptionalSteps(status, ['accessibility', 'health']).map(s => s.key)).toEqual([
      'notif_listener',
      'usage_stats',
    ]);
  });

  it('ein später erteilter Schritt ist nicht mehr "übersprungen"', () => {
    const status = makeStatus([...REQUIRED, 'camera']);
    const camera = SETUP_STEPS.find(s => s.key === 'camera')!;
    expect(isStepSkipped(status, ['camera'], camera)).toBe(false);
  });

  it('parseSkipped ignoriert kaputte Werte und Pflicht-Schritte', () => {
    expect(parseSkipped(null)).toEqual([]);
    expect(parseSkipped('kein json')).toEqual([]);
    expect(parseSkipped('{"a":1}')).toEqual([]);
    expect(parseSkipped('["camera","mic","gibtsnicht",5]')).toEqual(['camera']);
  });
});

describe('Abhängigkeiten beim Überspringen', () => {
  const features = stepsOfGroup('features').map(s => s.key);
  const bg = SETUP_STEPS.find(s => s.key === 'location_bg')!;

  it('location_bg gilt automatisch als übersprungen, wenn location übersprungen ist', () => {
    const status = makeStatus(REQUIRED);
    expect(isStepSkipped(status, [], bg)).toBe(false);
    expect(isStepSkipped(status, ['location'], bg)).toBe(true);
  });

  it('ist location erledigt, wirkt das Überspringen nicht mehr auf location_bg', () => {
    const status = makeStatus([...REQUIRED, 'location']);
    expect(isStepSkipped(status, ['location'], bg)).toBe(false);
  });

  it('"Alles eingerichtet" ist erreichbar, wenn location (und damit location_bg) übersprungen wird', () => {
    const rest = features.filter(k => k !== 'location' && k !== 'location_bg');
    const status = makeStatus([...REQUIRED, ...rest]);
    expect(isAllSetUp(status)).toBe(false);
    expect(isAllSetUp(status, ['location'])).toBe(true);
  });
});

describe('markOnboardingDone / Warn-Punkt', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    jest.replaceProperty(Platform, 'OS', 'android');
  });
  afterEach(() => {
    jest.restoreAllMocks();
    delete (NativeModules as Record<string, unknown>).DonnaSetupModule;
  });

  it('setzt onboarding_done und löscht onboarding_deferred', async () => {
    await markOnboardingDone();
    expect(mockSetItem).toHaveBeenCalledWith('onboarding_done', 'true');
    expect(mockRemoveItem).toHaveBeenCalledWith('onboarding_deferred');
  });

  it('löscht deferred auch, wenn das Setzen von done fehlschlägt (kein Crash)', async () => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
    mockSetItem.mockRejectedValueOnce(new Error('disk'));
    await expect(markOnboardingDone()).resolves.toBeUndefined();
    expect(mockRemoveItem).toHaveBeenCalledWith('onboarding_deferred');
    warn.mockRestore();
  });

  it('hasMissingRequired: true bei offenem Pflicht-Recht, false bei vollständig/Fehler/ohne Modul', async () => {
    expect(await hasMissingRequired()).toBe(false); // ohne Modul
    const native = {getSetupStatus: jest.fn(), requestStep: jest.fn(), openBixbyGuide: jest.fn()};
    (NativeModules as Record<string, unknown>).DonnaSetupModule = native;
    native.getSetupStatus.mockResolvedValue(makeStatus(REQUIRED.slice(1)));
    expect(await hasMissingRequired()).toBe(true);
    native.getSetupStatus.mockResolvedValue(makeStatus(REQUIRED));
    expect(await hasMissingRequired()).toBe(false);
    native.getSetupStatus.mockRejectedValue(new Error('x'));
    expect(await hasMissingRequired()).toBe(false);
  });
});

describe('Ehrliche Texte (Runde 2)', () => {
  it('Benachrichtigungszugriff nennt die Medien-Übertragung (Titel/Interpret) an den Donna-Server', () => {
    const n = SETUP_STEPS.find(s => s.key === 'notif_listener')!;
    expect(n.description).toContain('Titel/Interpret');
    expect(n.description).toContain('Donna-Server');
    expect(n.confirm?.message).toContain('Interpreten');
    expect(n.confirm?.message).toContain('Donna-Server');
  });
});

describe('getSetupStatusWithTimeout', () => {
  beforeEach(() => {
    jest.useFakeTimers();
    jest.replaceProperty(Platform, 'OS', 'android');
  });
  afterEach(() => {
    jest.useRealTimers();
    jest.restoreAllMocks();
    delete (NativeModules as Record<string, unknown>).DonnaSetupModule;
  });

  it('liefert null, wenn der native Aufruf hängt (z. B. Health-Connect-Hänger)', async () => {
    (NativeModules as Record<string, unknown>).DonnaSetupModule = {
      getSetupStatus: () => new Promise(() => {}),
    };
    const result = getSetupStatusWithTimeout(5000);
    await jest.advanceTimersByTimeAsync(5000);
    expect(await result).toBeNull();
  });

  it('liefert den Status, wenn er rechtzeitig kommt, und räumt den Timer auf', async () => {
    const status = makeStatus(REQUIRED);
    (NativeModules as Record<string, unknown>).DonnaSetupModule = {
      getSetupStatus: () => Promise.resolve(status),
    };
    expect(await getSetupStatusWithTimeout(5000)).toBe(status);
    expect(jest.getTimerCount()).toBe(0);
  });

  it('reicht native Fehler weiter', async () => {
    (NativeModules as Record<string, unknown>).DonnaSetupModule = {
      getSetupStatus: () => Promise.reject(new Error('boom')),
    };
    await expect(getSetupStatusWithTimeout(5000)).rejects.toThrow('boom');
  });
});

describe('shouldShowOnboarding (Start-Gate)', () => {
  const mockNative = {
    getSetupStatus: jest.fn(),
    requestStep: jest.fn(),
    openBixbyGuide: jest.fn(),
  };

  beforeEach(() => {
    jest.clearAllMocks();
    jest.replaceProperty(Platform, 'OS', 'android');
    (NativeModules as Record<string, unknown>).DonnaSetupModule = mockNative;
  });
  afterEach(() => {
    jest.restoreAllMocks();
    delete (NativeModules as Record<string, unknown>).DonnaSetupModule;
  });

  it('erster Start (Flag fehlt) → Wizard, auch wenn Pflicht schon erledigt ist', async () => {
    mockGetItem.mockResolvedValue(null);
    mockNative.getSetupStatus.mockResolvedValue(makeStatus(REQUIRED));
    expect(await shouldShowOnboarding()).toBe(true);
  });

  it('Flag gesetzt und Pflicht erledigt → Chat', async () => {
    mockGetItem.mockImplementation(async (k: string) => (k === 'onboarding_done' ? 'true' : null));
    mockNative.getSetupStatus.mockResolvedValue(makeStatus(REQUIRED));
    expect(await shouldShowOnboarding()).toBe(false);
  });

  it('Flag gesetzt, aber Pflicht-Schritt wieder offen (z. B. nach APK-Reinstall) → Wizard', async () => {
    mockGetItem.mockImplementation(async (k: string) => (k === 'onboarding_done' ? 'true' : null));
    mockNative.getSetupStatus.mockResolvedValue(makeStatus(REQUIRED.filter(k => k !== 'overlay')));
    expect(await shouldShowOnboarding()).toBe(true);
  });

  it('natives Modul fehlt → Chat (fail-open)', async () => {
    delete (NativeModules as Record<string, unknown>).DonnaSetupModule;
    expect(await shouldShowOnboarding()).toBe(false);
  });

  it('Notausgang: "Später einrichten" (deferred) → nie wieder Gate, auch bei offener Pflicht', async () => {
    mockGetItem.mockImplementation(async (key: string) => (key === 'onboarding_deferred' ? 'true' : null));
    mockNative.getSetupStatus.mockResolvedValue(makeStatus([]));
    expect(await shouldShowOnboarding()).toBe(false);
    expect(mockNative.getSetupStatus).not.toHaveBeenCalled();
  });

  it('Timeout: hängender nativer Status → Chat statt Dauer-Spinner (fail-open)', async () => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
    mockGetItem.mockImplementation(async (k: string) => (k === 'onboarding_done' ? 'true' : null));
    mockNative.getSetupStatus.mockReturnValue(new Promise(() => {}));
    expect(await shouldShowOnboarding(30)).toBe(false);
    warn.mockRestore();
  });

  it('Storage-Fehler beim Lesen des Flags → Wizard nur wenn Pflicht offen (kein Crash)', async () => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
    mockGetItem.mockRejectedValue(new Error('storage down'));
    mockNative.getSetupStatus.mockResolvedValue(makeStatus(REQUIRED));
    // Flag unlesbar = "nicht erledigt" → Wizard wird einmal gezeigt, statt abzustürzen
    expect(await shouldShowOnboarding()).toBe(true);
    warn.mockRestore();
  });

  it('Prüfung schlägt fehl → Chat (fail-open), Nutzer wird nie ausgesperrt', async () => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
    mockGetItem.mockImplementation(async (k: string) => (k === 'onboarding_done' ? 'true' : null));
    mockNative.getSetupStatus.mockRejectedValue(new Error('native down'));
    expect(await shouldShowOnboarding()).toBe(false);
    warn.mockRestore();
  });
});

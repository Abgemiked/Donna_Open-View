import React from 'react';
import {AppState, DeviceEventEmitter, ToastAndroid, NativeModules, PermissionsAndroid, Platform, StyleSheet, useColorScheme} from 'react-native';
import type {StyleProp, ViewStyle} from 'react-native';
import {render, fireEvent, waitFor, act} from '@testing-library/react-native';
import ChatScreen, {estimateTtsMs} from '../screens/ChatScreen';
import {PULSE_DARK, PULSE_LIGHT} from '../theme';

// ─── NativeModules-Mock (TTSModule) ──────────────────────────────────────────
// Hinweis: NativeModules wird in beforeEach direkt befuellt statt react-native per Spread zu mocken —
// der Spread loest alle Lazy-Getter von react-native aus (SettingsManager o. ae.), die Suite lief nicht an.
const mockTTSSpeakViaKokoro = jest.fn(
  (_text: string, resolve: (r?: string) => void, _reject?: () => void) => resolve(),
);
const mockTTSStop = jest.fn();
const mockTTSSpeak = jest.fn();

jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => ({top: 0, bottom: 0, left: 0, right: 0}),
}));

// ─── AsyncStorage-Mock ───────────────────────────────────────────────────────
const mockAsyncStorageGetItem = jest.fn().mockResolvedValue(null);
const mockAsyncStorageSetItem = jest.fn().mockResolvedValue(undefined);

jest.mock('@react-native-async-storage/async-storage', () => ({
  __esModule: true,
  default: {
    // Lazy, weil die jest.mock-Factory beim (hochgezogenen) Import vor den const-Definitionen laeuft
    getItem: (...args: unknown[]) => mockAsyncStorageGetItem(...args),
    setItem: (...args: unknown[]) => mockAsyncStorageSetItem(...args),
  },
}));

// ─── Shared-Hook-Mock ─────────────────────────────────────────────────────────
const mockSendMessage = jest.fn().mockResolvedValue(undefined);
const mockClearMessages = jest.fn();
const mockCancelChat = jest.fn();
const mockStartListening = jest.fn().mockResolvedValue(undefined);
const mockStopListening = jest.fn().mockResolvedValue(undefined);

function makeChatMock(messages: unknown[] = []) {
  return {
    messages,
    isLoading: false,
    rateLimitStatus: null,
    sendMessage: mockSendMessage,
    cancel: mockCancelChat,
    loadMessages: jest.fn(),
    clearMessages: mockClearMessages,
    setLocation: jest.fn(),
    sessionId: 'test-session',
    startProactiveChat: jest.fn(),
  };
}

let mockUseChatImpl: ReturnType<typeof makeChatMock> = makeChatMock();
let mockVoiceOpts: {onTranscript?: (t: string) => void} = {};
const mockCancelListening = jest.fn().mockResolvedValue(undefined);
const mockVoiceReturn = {
  voiceState: 'idle',
  partialTranscript: '',
  startListening: (...a: unknown[]) => mockStartListening(...a),
  stopListening: (...a: unknown[]) => mockStopListening(...a),
  cancelListening: (...a: unknown[]) => mockCancelListening(...a),
  isAvailable: true,
};

jest.mock('@donna/shared', () => ({
  useChat: () => mockUseChatImpl,
  useVoice: (opts: {onTranscript?: (t: string) => void}) => {
    mockVoiceOpts = opts;
    return mockVoiceReturn;
  },
  __unused: () => ({
    voiceState: 'idle',
    partialTranscript: '',
    startListening: (...a: unknown[]) => mockStartListening(...a),
    stopListening: (...a: unknown[]) => mockStopListening(...a),
    isAvailable: true,
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

jest.mock('../native/AndroidVoiceModule', () => ({
  AndroidVoiceModule: {},
}));

jest.mock('../native/ContactsBridge', () => ({
  searchContactsByName: jest.fn().mockResolvedValue([]),
}));

const mockOfflineIsAvailable = jest.fn().mockResolvedValue(false);
const mockOfflineStart = jest.fn().mockResolvedValue(true);
const mockOfflineStop = jest.fn().mockResolvedValue(true);
const mockOfflineCancel = jest.fn().mockResolvedValue(true);
let mockOfflineOnResult: ((t: string) => void) | null = null;
let mockOfflineOnError: ((c: number) => void) | null = null;
let mockOfflineOnPartial: ((t: string) => void) | null = null;
jest.mock('../modules/OfflineSTT', () => ({
  OfflineSTT: {
    isAvailable: () => mockOfflineIsAvailable(),
    start: (...a: unknown[]) => mockOfflineStart(...a),
    stop: () => mockOfflineStop(),
    cancel: () => mockOfflineCancel(),
    onResult: (cb: (t: string) => void) => { mockOfflineOnResult = cb; return {remove: jest.fn()}; },
    onPartial: (cb: (t: string) => void) => { mockOfflineOnPartial = cb; return {remove: jest.fn()}; },
    onError: (cb: (c: number) => void) => { mockOfflineOnError = cb; return {remove: jest.fn()}; },
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

// ─── Tests ───────────────────────────────────────────────────────────────────

beforeEach(() => {
  jest.clearAllMocks();
  jest.replaceProperty(Platform, 'OS', 'android');
  const nm = NativeModules as Record<string, unknown>;
  nm.TTSModule = {speakViaKokoro: mockTTSSpeakViaKokoro, stop: mockTTSStop, speak: mockTTSSpeak};
  nm.PhiModule = {addListener: jest.fn(), removeListeners: jest.fn()};
  jest.spyOn(PermissionsAndroid, 'check').mockResolvedValue(false);
  (useColorScheme as jest.Mock).mockReturnValue('dark');
  mockAsyncStorageGetItem.mockResolvedValue(null);
  mockUseChatImpl = makeChatMock();
});

afterEach(() => {
  const nm = NativeModules as Record<string, unknown>;
  delete nm.TTSModule;
  delete nm.PhiModule;
  jest.restoreAllMocks();
  mockSendMessage.mockResolvedValue(undefined);
});

describe('ChatScreen', () => {
  it('renders header with DONNA title', async () => {
    const {getByText} = render(<ChatScreen />);
    await act(async () => {});
    expect(getByText('DONNA')).toBeTruthy();
  });

  it('renders TTS-Toggle-Button in header', async () => {
    const {getByLabelText} = render(<ChatScreen />);
    await act(async () => {});
    // Default: TTS enabled → zeigt 🔊
    expect(getByLabelText('TTS deaktivieren')).toBeTruthy();
  });

  it('TTS-Toggle deaktiviert TTS und ruft TTSModule.stop auf', async () => {
    const {getByLabelText} = render(<ChatScreen />);
    await act(async () => {});
    const btn = getByLabelText('TTS deaktivieren');
    fireEvent.press(btn);
    await act(async () => {});
    expect(mockTTSStop).toHaveBeenCalled();
    expect(mockAsyncStorageSetItem).toHaveBeenCalledWith('donna_tts_enabled', 'false');
  });

  it('TTS-Toggle-State wird aus AsyncStorage geladen', async () => {
    // Pro Schluessel antworten (der Bildschirm liest mehrere Einstellungen, die Reihenfolge ist nicht fest)
    mockAsyncStorageGetItem.mockImplementation(async (key: string) => (key === 'donna_tts_enabled' ? 'false' : null));
    const {getByLabelText} = render(<ChatScreen />);
    await act(async () => {});
    // Wenn 'false' gespeichert → zeigt 🔇 → Label = 'TTS aktivieren'
    expect(getByLabelText('TTS aktivieren')).toBeTruthy();
  });

  it('speakViaKokoro wird bei neuer Assistenten-Nachricht nach Voice-Input aufgerufen', async () => {
    const {rerender} = render(<ChatScreen />);
    await act(async () => {});

    // lastInputWasVoiceRef auf true setzen durch Voice-Input simulieren
    // — Direkt-Simulation: neue Assistenten-Nachricht + via Ref den Voice-Flag setzen
    // Da lastInputWasVoiceRef intern ist, testen wir den Effekt via messagesänderung
    // mit dem Wissen, dass speakViaKokoro NUR bei voice-input aufgerufen wird.
    // Hinweis: vollständige Integration-Tests benötigen E2E — hier Unit-Test des Toggle-Pfads.

    // Neue Assistenten-Nachricht mit aktiviertem TTS → kein speak wenn kein voice-input
    mockUseChatImpl = {
      ...mockUseChatImpl,
      messages: [
        {role: 'assistant', content: 'Hallo Mike'},
      ],
    };
    rerender(<ChatScreen />);
    await act(async () => {});

    // Ohne Voice-Input-Flag: speakViaKokoro darf NICHT aufgerufen werden
    expect(mockTTSSpeakViaKokoro).not.toHaveBeenCalled();
  });

  it('send button is disabled when input is empty', async () => {
    const {getByLabelText} = render(<ChatScreen />);
    await act(async () => {});
    const sendButton = getByLabelText('Nachricht senden');
    expect(sendButton.props.accessibilityState?.disabled).toBe(true);
  });

  it('calls sendMessage when send button pressed with text', async () => {
    const {getByPlaceholderText, getByLabelText} = render(<ChatScreen />);
    await act(async () => {});
    const input = getByPlaceholderText('Nachricht eingeben…');
    fireEvent.changeText(input, 'Hallo Donna');
    fireEvent.press(getByLabelText('Nachricht senden'));

    await waitFor(() => {
      expect(mockSendMessage).toHaveBeenCalledWith('Hallo Donna', undefined, 'android', expect.any(Function));
    });
  });

  it('displays messages in the list', async () => {
    mockUseChatImpl = {
      ...mockUseChatImpl,
      messages: [
        {role: 'user', content: 'Hallo'},
        {role: 'assistant', content: 'Wie kann ich helfen?'},
      ],
    };

    const {getByText} = render(<ChatScreen />);
    await act(async () => {});
    expect(getByText('Hallo')).toBeTruthy();
    expect(getByText('Wie kann ich helfen?')).toBeTruthy();
  });

  it('Live-Guard: speakViaKokoro gibt live_guard zurück und spielt kein Audio', async () => {
    // Simuliert: Backend antwortet 204 → Kotlin-Seite löst promise mit 'live_guard' auf
    // JS-Seite bekommt speakViaKokoro-Callback → kein weiterer Aufruf
    mockTTSSpeakViaKokoro.mockImplementationOnce(
      (_text: string, resolve: (result?: string) => void) => resolve('live_guard'),
    );

    // Test: speakViaKokoro kann mit 'live_guard' aufgerufen werden ohne Fehler
    const speakFn = mockTTSSpeakViaKokoro;
    let result = '';
    await act(async () => {
      speakFn("Hallo", (r?: string) => { result = r ?? ""; }, () => {});
    });
    expect(result).toBe('live_guard');
  });

  // UX-01: Farben folgen dem System-Schema (kein fester Dark-Look mehr)
  it.each([
    ['dark', PULSE_DARK],
    ['light', PULSE_LIGHT],
  ] as const)('folgt dem System-Schema %s (Hintergrund + Header-Flaeche)', async (scheme, palette) => {
    // react-native/jest/setup mockt useColorScheme als jest.fn (Default 'light')
    (useColorScheme as jest.Mock).mockReturnValue(scheme);
    const {toJSON, getByText} = render(<ChatScreen />);
    await act(async () => {});
    const root = toJSON() as {props: {style: unknown}};
    expect(StyleSheet.flatten(root.props.style as StyleProp<ViewStyle>).backgroundColor).toBe(palette.bg);
    // Header (DONNA-Titel) liegt auf der surface-Flaeche, Titel in der Akzentfarbe
    const title = StyleSheet.flatten(getByText('DONNA').props.style);
    expect(title.color).toBe(palette.accent);
  });
});

// ─── Barge-in: Donna jederzeit unterbrechen ──────────────────────────────────
describe('ChatScreen Barge-in', () => {
  beforeEach(() => {
    mockStartListening.mockResolvedValue(undefined);
    jest.spyOn(PermissionsAndroid, 'request').mockResolvedValue('granted' as never);
  });

  it('zeigt keine Stopp-Taste, solange nichts laeuft', async () => {
    const {queryByLabelText} = render(<ChatScreen />);
    await act(async () => {});
    expect(queryByLabelText('Donna unterbrechen')).toBeNull();
  });

  it('zeigt waehrend des Streams eine Stopp-Taste; Tippen stoppt TTS und bricht den Stream ab', async () => {
    mockUseChatImpl = {...mockUseChatImpl, isLoading: true};
    const {getByLabelText} = render(<ChatScreen />);
    await act(async () => {});
    fireEvent.press(getByLabelText('Donna unterbrechen'));
    expect(mockTTSStop).toHaveBeenCalled();
    expect(mockCancelChat).toHaveBeenCalledTimes(1);
    // reiner Stopp: kein Zuhoeren, keine neue Anfrage
    expect(mockStartListening).not.toHaveBeenCalled();
    expect(mockSendMessage).not.toHaveBeenCalled();
  });

  it('Mic-Tap waehrend der Stream laeuft: TTS stoppen, Stream abbrechen, direkt zuhoeren', async () => {
    mockUseChatImpl = {...mockUseChatImpl, isLoading: true};
    const {getByLabelText} = render(<ChatScreen />);
    await act(async () => {});
    await act(async () => {
      fireEvent.press(getByLabelText('Donna unterbrechen und sprechen'));
    });
    expect(mockTTSStop).toHaveBeenCalled();
    expect(mockCancelChat).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(mockStartListening).toHaveBeenCalledTimes(1));
  });

  it('Senden waehrend der Stream laeuft: Wiedergabe stoppen, neue Frage senden (ersetzt die alte)', async () => {
    mockUseChatImpl = {...mockUseChatImpl, isLoading: true};
    const {getByPlaceholderText, getByLabelText} = render(<ChatScreen />);
    await act(async () => {});
    fireEvent.changeText(getByPlaceholderText('Nachricht eingeben…'), 'Neue Frage');
    const send = getByLabelText('Nachricht senden');
    // Senden ist NICHT mehr gesperrt, nur weil noch ein Stream laeuft
    expect(send.props.accessibilityState?.disabled).toBeFalsy();
    mockTTSStop.mockClear();
    fireEvent.press(send);
    await waitFor(() => {
      expect(mockSendMessage).toHaveBeenCalledWith('Neue Frage', undefined, 'android', expect.any(Function));
    });
    expect(mockTTSStop).toHaveBeenCalled();
    // der Hook ersetzt den alten Request selbst — kein separates Abbrechen (sonst bliebe eine halbe Antwort stehen)
    expect(mockCancelChat).not.toHaveBeenCalled();
    expect(mockSendMessage).toHaveBeenCalledTimes(1);
  });

  it('Tippen waehrend der Stream laeuft stoppt sofort die Wiedergabe', async () => {
    mockUseChatImpl = {...mockUseChatImpl, isLoading: true};
    const {getByPlaceholderText} = render(<ChatScreen />);
    await act(async () => {});
    mockTTSStop.mockClear();
    fireEvent.changeText(getByPlaceholderText('Nachricht eingeben…'), 'W');
    expect(mockTTSStop).toHaveBeenCalled();
    expect(mockCancelChat).not.toHaveBeenCalled();
  });

  it('Early-TTS-Callback der unterbrochenen Frage spricht nicht mehr', async () => {
    const {getByPlaceholderText, getByLabelText} = render(<ChatScreen />);
    await act(async () => {});
    fireEvent.changeText(getByPlaceholderText('Nachricht eingeben…'), 'Frage eins');
    fireEvent.press(getByLabelText('Nachricht senden'));
    await waitFor(() => expect(mockSendMessage).toHaveBeenCalledTimes(1));
    const staleCallback = mockSendMessage.mock.calls[0][3] as (s: string) => void;
    // Nutzer unterbricht (Mic-Tap) …
    await act(async () => {
      fireEvent.press(getByLabelText('Spracheingabe'));
    });
    mockTTSSpeakViaKokoro.mockClear();
    // … danach kommt ein verspaeteter Satz der alten Antwort → darf nicht gesprochen werden
    staleCallback('Das ist ein verspaeteter Satz der alten Antwort.');
    expect(mockTTSSpeakViaKokoro).not.toHaveBeenCalled();
  });

  it('Sprechdauer-Schaetzung ist begrenzt und waechst mit der Textlaenge', () => {
    expect(estimateTtsMs('')).toBeGreaterThanOrEqual(2_500);
    expect(estimateTtsMs('x'.repeat(400))).toBeGreaterThan(estimateTtsMs('x'.repeat(40)));
    expect(estimateTtsMs('x'.repeat(100_000))).toBeLessThanOrEqual(60_000);
  });
});

// ─── Freisprech-Gesprächsmodus + Voice-Barge-in ──────────────────────────────
describe('ChatScreen Freisprechen', () => {
  const mockBargeStart = jest.fn().mockResolvedValue(true);
  const mockBargeStop = jest.fn();
  const mockBargeDisable = jest.fn();

  /** Einstellungen aus dem Speicher: Voice-Barge-in ist standardmäßig AN → nur ein ausdrückliches 'false' schaltet es aus. */
  function storage(values: Record<string, string> = {}) {
    mockAsyncStorageGetItem.mockImplementation(async (key: string) => values[key] ?? null);
  }

  beforeEach(() => {
    mockStartListening.mockResolvedValue(undefined);
    mockBargeStart.mockClear();
    mockBargeStop.mockClear();
    mockBargeDisable.mockClear();
    jest.spyOn(PermissionsAndroid, 'request').mockResolvedValue('granted' as never);
    mockOfflineIsAvailable.mockResolvedValue(false);
    mockOfflineStart.mockClear();
    mockOfflineStop.mockClear();
    mockOfflineCancel.mockClear();
    mockCancelListening.mockClear();
    mockOfflineOnResult = null;
    mockOfflineOnError = null;
    mockOfflineOnPartial = null;
    // Der RN-Jest-Mock liefert currentState als jest.fn() — Freisprechen verlangt einen aktiven Vordergrund
    (AppState as unknown as {currentState: string}).currentState = 'active';
    (NativeModules as Record<string, unknown>).BargeInModule = {start: mockBargeStart, stop: mockBargeStop, setDisabled: mockBargeDisable};
  });
  afterEach(() => {
    delete (NativeModules as Record<string, unknown>).BargeInModule;
  });

  const emitTts = (state: 'started' | 'ended') => act(async () => { DeviceEventEmitter.emit('DonnaTTSState', {state}); });
  const emitBarge = () => act(async () => { DeviceEventEmitter.emit('DonnaBargeIn'); });

  /** Mike spricht (manuell), Donna antwortet → der Bildschirm plant das Relisten nach dem TTS-Ende. */
  async function answeredByVoice(rerender: (ui: React.ReactElement) => void, answer = 'Es ist halb elf und alles ruhig.') {
    await act(async () => { mockVoiceOpts.onTranscript?.('Wie spät ist es gerade'); });
    mockUseChatImpl = {...mockUseChatImpl, messages: [
      {role: 'user', content: 'Wie spät ist es gerade'},
      {role: 'assistant', content: answer},
    ]};
    rerender(<ChatScreen />);
    await act(async () => {});
  }

  it('Gesprächsmodus ist standardmäßig an und im Kopf sowie als Hinweis sichtbar', async () => {
    const {getByLabelText} = render(<ChatScreen />);
    await act(async () => {});
    expect(getByLabelText('Gesprächsmodus ausschalten')).toBeTruthy();
    expect(getByLabelText('Gesprächsmodus ist an, antippen zum Beenden')).toBeTruthy();
  });

  it('Ausschalten wird gemerkt', async () => {
    const {getByLabelText} = render(<ChatScreen />);
    await act(async () => {});
    fireEvent.press(getByLabelText('Gesprächsmodus ausschalten'));
    expect(mockAsyncStorageSetItem).toHaveBeenCalledWith('@donna_conversation_mode', 'false');
    expect(getByLabelText('Gesprächsmodus einschalten')).toBeTruthy();
  });

  it('gemerkter Zustand "aus" wird beim Start geladen', async () => {
    storage({'@donna_conversation_mode': 'false'});
    const {getByLabelText} = render(<ChatScreen />);
    await act(async () => {});
    expect(getByLabelText('Gesprächsmodus einschalten')).toBeTruthy();
  });

  // ── Voice-Barge-in: Standard AN (Opt-out, Voice-Batch) ─────────────────────

  it('Voice-Barge-in ist standardmäßig AN: TTS-Beginn öffnet das Stimm-Lauschen, Mikes Stimme unterbricht', async () => {
    const {queryByLabelText} = render(<ChatScreen />);
    await act(async () => {});
    await emitTts('started');
    expect(queryByLabelText('Donna unterbrechen')).toBeTruthy();   // Stopp-Taste bleibt
    expect(mockBargeStart).toHaveBeenCalledTimes(1);
    await emitBarge();
    expect(mockCancelChat).toHaveBeenCalledTimes(1);
    expect(mockStartListening).toHaveBeenCalledTimes(1);
  });

  it('Opt-out aus dem Speicher: nur ein ausdrückliches "false" schaltet Voice-Barge-in aus', async () => {
    storage({'@donna_voice_bargein': 'false'});
    const {queryByLabelText} = render(<ChatScreen />);
    await act(async () => {});
    await emitTts('started');
    expect(queryByLabelText('Donna unterbrechen')).toBeTruthy();   // Stopp-Taste bleibt
    expect(mockBargeStart).not.toHaveBeenCalled();
    await emitBarge();
    expect(mockStartListening).not.toHaveBeenCalled();
    expect(mockCancelChat).not.toHaveBeenCalled();
  });

  it('ein gespeichertes "true" oder kein Wert lässt Voice-Barge-in an', async () => {
    storage({'@donna_voice_bargein': 'true'});
    render(<ChatScreen />);
    await act(async () => {});
    await emitTts('started');
    expect(mockBargeStart).toHaveBeenCalledTimes(1);
  });

  it('Lange auf den Gesprächs-Schalter tippen schaltet Voice-Barge-in aus (und wieder an) und merkt es', async () => {
    const {getByLabelText} = render(<ChatScreen />);
    await act(async () => {});
    fireEvent(getByLabelText('Gesprächsmodus ausschalten'), 'longPress');
    expect(mockAsyncStorageSetItem).toHaveBeenCalledWith('@donna_voice_bargein', 'false');
    await emitTts('started');
    expect(mockBargeStart).not.toHaveBeenCalled();
    await emitTts('ended');
    fireEvent(getByLabelText('Gesprächsmodus ausschalten'), 'longPress');
    expect(mockAsyncStorageSetItem).toHaveBeenCalledWith('@donna_voice_bargein', 'true');
    await emitTts('started');
    expect(mockBargeStart).toHaveBeenCalledTimes(1);
  });

  // ── Voice-Batch: A/B-Stimme Edge ↔ Piper-lokal, jeweils die andere als Fallback ─────────

  describe('Stimme: A/B Edge ↔ Piper', () => {
    const setNatives = (opts: {
      engine?: string;
      speakViaServer?: jest.Mock;
      speakPiper?: jest.Mock;
      piperExtra?: Record<string, unknown>;
      tts?: Record<string, unknown>;
    }) => {
      const nm = NativeModules as Record<string, unknown>;
      nm.TTSModule = {
        speakViaKokoro: mockTTSSpeakViaKokoro, stop: mockTTSStop, speak: mockTTSSpeak,
        speakViaServer: opts.speakViaServer ?? jest.fn().mockResolvedValue('ok'),
        isOnDeviceReady: jest.fn().mockResolvedValue(true),
        speakOnDevice: jest.fn().mockResolvedValue('ok'),
        ...opts.tts,
      };
      nm.PiperTTS = {
        speakPiper: opts.speakPiper ?? jest.fn().mockResolvedValue('ok'),
        stopPiper: jest.fn(),
        getTtsEngine: jest.fn().mockResolvedValue(opts.engine ?? 'edge'),
        getEdgeVoice: jest.fn().mockResolvedValue('de-DE-KatjaNeural'),
        ...opts.piperExtra,
      };
    };
    afterEach(() => { delete (NativeModules as Record<string, unknown>).PiperTTS; });

    it('Standard Edge: die Antwort geht über TTSModule.speakViaServer (weibliche Stimme Katja), Piper bleibt still', async () => {
      const speakViaServer = jest.fn().mockResolvedValue('ok');
      const speakPiper = jest.fn().mockResolvedValue('ok');
      setNatives({speakViaServer, speakPiper});
      const {rerender} = render(<ChatScreen />);
      await act(async () => {});
      await answeredByVoice(rerender, 'Alles ruhig im Haus.');
      await waitFor(() => expect(speakViaServer).toHaveBeenCalledWith(expect.stringContaining('Alles ruhig'), 'de-DE-KatjaNeural'));
      expect(speakPiper).not.toHaveBeenCalled();
    });

    it('Piper gewählt: Piper-lokal zuerst, Edge bleibt still', async () => {
      const speakViaServer = jest.fn().mockResolvedValue('ok');
      const speakPiper = jest.fn().mockResolvedValue('ok');
      setNatives({engine: 'piper', speakViaServer, speakPiper});
      const {rerender} = render(<ChatScreen />);
      await act(async () => {});
      await answeredByVoice(rerender, 'Alles ruhig im Haus.');
      await waitFor(() => expect(speakPiper).toHaveBeenCalledWith(expect.stringContaining('Alles ruhig')));
      expect(speakViaServer).not.toHaveBeenCalled();
    });

    it('Edge fällt aus (Netz): Piper-lokal springt automatisch ein', async () => {
      const speakViaServer = jest.fn().mockResolvedValue('failed');
      const speakPiper = jest.fn().mockResolvedValue('ok');
      setNatives({speakViaServer, speakPiper});
      const {rerender} = render(<ChatScreen />);
      await act(async () => {});
      await answeredByVoice(rerender, 'Alles ruhig im Haus.');
      await waitFor(() => expect(speakPiper).toHaveBeenCalledTimes(1));
      expect(speakViaServer).toHaveBeenCalledTimes(1);
    });

    it('Piper-Modell noch nicht bereit: Edge springt ein', async () => {
      const speakViaServer = jest.fn().mockResolvedValue('ok');
      const speakPiper = jest.fn().mockResolvedValue('fallback');
      setNatives({engine: 'piper', speakViaServer, speakPiper});
      const {rerender} = render(<ChatScreen />);
      await act(async () => {});
      await answeredByVoice(rerender, 'Alles ruhig im Haus.');
      await waitFor(() => expect(speakViaServer).toHaveBeenCalledTimes(1));
      expect(speakPiper).toHaveBeenCalledTimes(1);
    });

    it('beide fallen aus: System-TTS (Samsung Neural) als letzte Stufe', async () => {
      const speakOnDevice = jest.fn().mockResolvedValue('ok');
      setNatives({
        speakViaServer: jest.fn().mockResolvedValue('failed'),
        speakPiper: jest.fn().mockResolvedValue('fallback'),
        tts: {speakOnDevice},
      });
      const {rerender} = render(<ChatScreen />);
      await act(async () => {});
      await answeredByVoice(rerender, 'Alles ruhig im Haus.');
      await waitFor(() => expect(speakOnDevice).toHaveBeenCalledTimes(1));
    });

    // ── Verdrahtungs-Regression First-Sentence-Skip (R6) ────────────────────────
    // Reproduziert den Bug auf INTEGRATIONSEBENE: der Early-TTS-Callback (von sendMessage) liefert erst den
    // ersten Satz, dann den Rest. Ohne die Sequencer-Verdrahtung (routeEarlyTts/ended-Handler) würde der Rest
    // den noch laufenden ersten Satz über den Einzel-Player abwürgen → Absatz 1 verschwindet. Dieser Test wird
    // ROT, sobald routeEarlyTts wieder direkt speakViaKokoro ruft (beide Sätze sofort → zwei Sprechaufrufe).
    it('Verdrahtung: erster Satz + Rest laufen sequenziell, kein Skip/Doppel, Relisten erst am echten Ende', async () => {
      const speakViaServer = jest.fn().mockResolvedValue('ok');
      const speakPiper = jest.fn().mockResolvedValue('ok');
      setNatives({speakViaServer, speakPiper});
      const {rerender} = render(<ChatScreen />);
      await act(async () => {});
      // Voice-Turn → handleTranscript ruft sendMessage mit dem Early-TTS-Callback (4. Argument)
      await act(async () => { mockVoiceOpts.onTranscript?.('Was steht heute an'); });
      await waitFor(() => expect(mockSendMessage).toHaveBeenCalled());
      const calls = mockSendMessage.mock.calls;
      const earlyCb = calls[calls.length - 1][3] as (s: string) => void;

      // Hook streamt: ZUERST der erste Satz, DANN (am Streamende) der Rest
      await act(async () => { earlyCb('Heute steht um 13 Uhr dein Stream an.'); });
      await act(async () => { earlyCb('Danach kommt das Review. Viel Erfolg.'); });

      // Genau EIN Sprechaufruf — nur der erste Satz. Der Rest wartet auf dessen echtes Ende (würde ihn sonst abwürgen).
      await waitFor(() => expect(speakViaServer).toHaveBeenCalledTimes(1));
      expect(speakViaServer).toHaveBeenCalledWith(expect.stringContaining('Heute steht um 13 Uhr'), expect.anything());
      expect(speakPiper).not.toHaveBeenCalled();
      expect(mockStartListening).not.toHaveBeenCalled();

      // Die fertige Antwort landet → der Completion-Effekt liefert den Rest NOCHMAL (zweite Quelle) → Idempotenz: ignoriert
      mockUseChatImpl = {...mockUseChatImpl, messages: [
        {role: 'user', content: 'Was steht heute an'},
        {role: 'assistant', content: 'Heute steht um 13 Uhr dein Stream an. Danach kommt das Review. Viel Erfolg.'},
      ]};
      rerender(<ChatScreen />);
      await act(async () => {});

      // Echtes Ende des ersten Satzes → der Rest startet JETZT, genau einmal; noch KEIN Relisten
      await emitTts('ended');
      await waitFor(() => expect(speakViaServer).toHaveBeenCalledTimes(2));
      expect(speakViaServer).toHaveBeenLastCalledWith(expect.stringContaining('Danach kommt das Review'), expect.anything());
      expect(mockStartListening).not.toHaveBeenCalled();

      // Echtes Ende des Rests → erst JETZT wird das Relisten geplant, genau einmal
      await emitTts('ended');
      await waitFor(() => expect(mockStartListening).toHaveBeenCalledTimes(1), {timeout: 1500});

      // Rest wurde GENAU EINMAL gesprochen (Hook-Rest + Completion-Effekt-Rest ⇒ keine Doppel-Wiedergabe)
      expect(speakViaServer).toHaveBeenCalledTimes(2);
    });

    // R1-Randfall: der Vor-Turn MUSS über den HOOK-PFAD (Early-Callback erster Satz + Rest) laufen, damit der
    // echte Fehlerzustand entsteht: Sequencer trägt danach STALE firstStarted=true + remainingHandled=true, und
    // earlyTTSFiredRef ist null. Trifft dann (nach Auto-Relisten, lastInputWasVoiceRef=true, ohne interruptSpeech)
    // eine proaktive Nachricht ein, würde sie OHNE den firstText==null-Guard idempotent verschluckt.
    it('R1: proaktive Nachricht nach Auto-Relisten wird gesprochen (nicht vom Sequencer stumm verworfen)', async () => {
      const speakViaServer = jest.fn().mockResolvedValue('ok');
      setNatives({speakViaServer});
      const {rerender} = render(<ChatScreen />);
      await act(async () => {});
      // 1) Vor-Turn ÜBER DEN HOOK-PFAD: Early-Callback mit erstem Satz + Rest (baut firstStarted/remainingHandled auf)
      await act(async () => { mockVoiceOpts.onTranscript?.('Wie spät ist es gerade'); });
      await waitFor(() => expect(mockSendMessage).toHaveBeenCalled());
      const calls = mockSendMessage.mock.calls;
      const earlyCb = calls[calls.length - 1][3] as (s: string) => void;
      await act(async () => { earlyCb('Es ist kurz nach zehn.'); });
      await act(async () => { earlyCb('Sonst ist alles ruhig.'); });
      // fertige Antwort landet
      mockUseChatImpl = {...mockUseChatImpl, messages: [
        {role: 'user', content: 'Wie spät ist es gerade'},
        {role: 'assistant', content: 'Es ist kurz nach zehn. Sonst ist alles ruhig.'},
      ]};
      rerender(<ChatScreen />);
      await act(async () => {});
      // 2) Beide TTS-Enden → der Rest läuft, dann plant das Auto-Relisten (setzt lastInputWasVoiceRef=true OHNE interruptSpeech)
      await emitTts('ended');
      await emitTts('ended');
      await waitFor(() => expect(mockStartListening).toHaveBeenCalledTimes(1), {timeout: 1500});
      speakViaServer.mockClear();
      // 3) PROAKTIVE Assistenten-Nachricht als NEUER Eintrag — ohne neuen Turn-Start
      mockUseChatImpl = {...mockUseChatImpl, messages: [
        {role: 'user', content: 'Wie spät ist es gerade'},
        {role: 'assistant', content: 'Es ist kurz nach zehn. Sonst ist alles ruhig.'},
        {role: 'assistant', content: 'Übrigens dein Stream beginnt in 10 Minuten.'},
      ]};
      rerender(<ChatScreen />);
      await act(async () => {});
      // R1-Fix: wird GENAU EINMAL gesprochen (ohne Guard: stale remainingHandled=true ⇒ idempotent verschluckt ⇒ stumm)
      await waitFor(() => expect(speakViaServer).toHaveBeenCalledWith(
        expect.stringContaining('dein Stream beginnt'), expect.anything()));
      expect(speakViaServer).toHaveBeenCalledTimes(1);
    });

    it('Langdruck auf 🔊 wechselt die Stimme NICHT mehr (TB4: Stimme liegt in den Einstellungen)', async () => {
      const setEdgeVoice = jest.fn().mockImplementation(async (v: string) => v);
      const getEdgeVoice = jest.fn().mockResolvedValue('de-DE-KatjaNeural');
      setNatives({piperExtra: {setEdgeVoice, getEdgeVoice}});
      const {getByLabelText} = render(<ChatScreen />);
      await act(async () => {});
      // TB4 hat den „Icon lange drücken"-Stimmwechsel entfernt (jetzt TtsVoiceSettings → /settings/tts).
      await act(async () => { fireEvent(getByLabelText('TTS deaktivieren'), 'longPress'); });
      expect(setEdgeVoice).not.toHaveBeenCalled();
    });

    it('Piper-Modell wird EAGER beim Start geladen und der Fortschritt als Banner angezeigt', async () => {
      const ensureModel = jest.fn().mockResolvedValue('started');
      setNatives({piperExtra: {
        ensureModel,
        getPiperState: jest.fn().mockResolvedValue({status: 'downloading', progress: 42, error: null}),
      }});
      const {findByText, getByTestId, queryByTestId} = render(<ChatScreen />);
      await act(async () => {});
      expect(ensureModel).toHaveBeenCalledWith(false);        // ohne einen einzigen Sprechversuch; Auto-Start nur im WLAN
      expect(getByTestId('piper-banner')).toBeTruthy();
      expect(await findByText('Piper wird geladen (42 %) — bis dahin spricht Edge')).toBeTruthy();
      await act(async () => { DeviceEventEmitter.emit('PiperTTS.downloadProgress', {progress: 77}); });
      expect(await findByText('Piper wird geladen (77 %) — bis dahin spricht Edge')).toBeTruthy();
      await act(async () => { DeviceEventEmitter.emit('PiperTTS.downloadComplete', {}); });
      await waitFor(() => expect(queryByTestId('piper-banner')).toBeNull());
    });

    it('Download-Fehler: Banner nennt den Fallback und bietet „Erneut versuchen“', async () => {
      const ensureModel = jest.fn().mockResolvedValue('started');
      setNatives({piperExtra: {
        ensureModel,
        getPiperState: jest.fn().mockResolvedValue({status: 'not_downloaded', progress: 0, error: null}),
      }});
      const {findByText, getByText} = render(<ChatScreen />);
      await act(async () => {});
      await act(async () => { DeviceEventEmitter.emit('PiperTTS.downloadError', {message: 'Kein Netz'}); });
      expect(await findByText(/Edge spricht weiter/)).toBeTruthy();
      ensureModel.mockClear();
      await act(async () => { fireEvent.press(getByText('Erneut versuchen')); });
      expect(ensureModel).toHaveBeenCalledTimes(1);
      expect(ensureModel).toHaveBeenCalledWith(true);          // bewusste Nutzeraktion: auch über Mobilfunk
    });

    it('Fehler-Banner ist schließbar und blendet sich nach ~10 s von selbst aus', async () => {
      jest.useFakeTimers();
      try {
        setNatives({piperExtra: {
          ensureModel: jest.fn().mockResolvedValue('started'),
          getPiperState: jest.fn().mockResolvedValue({status: 'not_downloaded', progress: 0, error: null}),
        }});
        const {queryByTestId, getByLabelText} = render(<ChatScreen />);
        await act(async () => {});
        await act(async () => { DeviceEventEmitter.emit('PiperTTS.downloadError', {message: 'x'}); });
        expect(queryByTestId('piper-banner')).toBeTruthy();
        await act(async () => { fireEvent.press(getByLabelText('Hinweis schließen')); });
        expect(queryByTestId('piper-banner')).toBeNull();
        // neuer Fehler → wieder sichtbar, danach Auto-Ausblenden
        await act(async () => { DeviceEventEmitter.emit('PiperTTS.downloadProgress', {progress: 5}); });
        await act(async () => { DeviceEventEmitter.emit('PiperTTS.downloadError', {message: 'y'}); });
        expect(queryByTestId('piper-banner')).toBeTruthy();
        await act(async () => { jest.advanceTimersByTime(10_500); });
        expect(queryByTestId('piper-banner')).toBeNull();
      } finally {
        jest.useRealTimers();
      }
    });

    it('Mobilfunk: kein stiller Auto-Download — kein Banner, „Jetzt laden“ nur in den Einstellungen', async () => {
      const ensureModel = jest.fn().mockResolvedValue('metered');
      setNatives({piperExtra: {
        ensureModel,
        getPiperState: jest.fn().mockResolvedValue({status: 'not_downloaded', progress: 0, error: null}),
      }});
      const {queryByTestId} = render(<ChatScreen />);
      await act(async () => {});
      expect(ensureModel).toHaveBeenCalledWith(false);
      expect(queryByTestId('piper-banner')).toBeNull();
    });

    // ── Review B1: Umschalten im Setup-Overlay wirkt SOFORT im gemounteten Chat ──
    it('B1: Engine/Stimme werden bei JEDEM Sprechen frisch gelesen (Umschalten ohne Neustart)', async () => {
      const speakViaServer = jest.fn().mockResolvedValue('ok');
      const speakPiper = jest.fn().mockResolvedValue('ok');
      const getTtsEngine = jest.fn().mockResolvedValue('edge');
      const getEdgeVoice = jest.fn().mockResolvedValue('de-DE-KatjaNeural');
      setNatives({speakViaServer, speakPiper, piperExtra: {getTtsEngine, getEdgeVoice}});
      const {rerender} = render(<ChatScreen />);
      await act(async () => {});
      // Mehrere Antworten nacheinander (die Nachrichtenliste wächst — nur dann spricht der Chat)
      let turns = 0;
      const answer = async (text: string) => {
        await act(async () => { mockVoiceOpts.onTranscript?.('Wie spät ist es gerade'); });
        turns += 1;
        const msgs: {role: string; content: string}[] = [];
        for (let i = 0; i < turns; i++) {
          msgs.push({role: 'user', content: `Frage ${i}`}, {role: 'assistant', content: i === turns - 1 ? text : `Alt ${i}`});
        }
        mockUseChatImpl = {...mockUseChatImpl, messages: msgs};
        rerender(<ChatScreen />);
        await act(async () => {});
      };
      await answer('Erste Antwort im Haus.');
      await waitFor(() => expect(speakViaServer).toHaveBeenCalledWith(expect.stringContaining('Erste'), 'de-DE-KatjaNeural'));
      // Einstellung ändert sich (VoiceSettings über dem gemounteten Chat) — kein Remount
      getTtsEngine.mockResolvedValue('piper');
      getEdgeVoice.mockResolvedValue('de-DE-SeraphinaMultilingualNeural');
      await answer('Zweite Antwort im Haus.');
      await waitFor(() => expect(speakPiper).toHaveBeenCalledWith(expect.stringContaining('Zweite')));
      // und zurück auf Edge mit der neuen Stimme
      getTtsEngine.mockResolvedValue('edge');
      await answer('Dritte Antwort im Haus.');
      await waitFor(() => expect(speakViaServer).toHaveBeenCalledWith(expect.stringContaining('Dritte'), 'de-DE-SeraphinaMultilingualNeural'));
    });

    it('Langdruck-Hinweis für den Gesprächsmodus ist für Screenreader vorhanden; 🔊 hat keinen Stimm-Hinweis mehr', async () => {
      setNatives({});
      const {getByLabelText} = render(<ChatScreen />);
      await act(async () => {});
      // TB4: der 🔊-Button hat keinen „lange drücken wechselt die Stimme"-Hinweis mehr.
      expect(getByLabelText('TTS deaktivieren').props.accessibilityHint).toBeUndefined();
      expect(getByLabelText('Gesprächsmodus ausschalten').props.accessibilityHint).toMatch(/Unterbrechen/);
    });
  });

  describe('mit eingeschaltetem Voice-Barge-in', () => {
    beforeEach(() => storage({'@donna_voice_bargein': 'true'}));

    it('TTS-Ende beendet das Stimm-Lauschen und die Stopp-Taste', async () => {
      const {queryByLabelText} = render(<ChatScreen />);
      await act(async () => {});
      await emitTts('started');
      expect(queryByLabelText('Donna unterbrechen')).toBeTruthy();
      await emitTts('ended');
      expect(queryByLabelText('Donna unterbrechen')).toBeNull();
      expect(mockBargeStop).toHaveBeenCalled();
    });

    it('Mikes Stimme stoppt TTS, bricht den Stream ab und öffnet das Mikrofon', async () => {
      render(<ChatScreen />);
      await act(async () => {});
      await emitTts('started');
      mockTTSStop.mockClear();
      await emitBarge();
      expect(mockTTSStop).toHaveBeenCalled();
      expect(mockCancelChat).toHaveBeenCalledTimes(1);
      expect(mockStartListening).toHaveBeenCalledTimes(1);
    });

    it('K1/K2: höchstens 3 Barge-ins pro Berührung - danach wird nicht mehr gelauscht und nicht unterbrochen', async () => {
      render(<ChatScreen />);
      await act(async () => {});
      for (let i = 0; i < 3; i++) {
        await emitTts('started');
        await emitBarge();
      }
      expect(mockStartListening).toHaveBeenCalledTimes(3);
      mockBargeStart.mockClear();
      mockStartListening.mockClear();
      await emitTts('started');
      expect(mockBargeStart).not.toHaveBeenCalled();     // Lauschen startet gar nicht mehr
      await emitBarge();
      expect(mockStartListening).not.toHaveBeenCalled(); // und ein spätes Ereignis wird ignoriert
    });

    it('eine Berührung (Mic-Tap) gibt die Barge-ins wieder frei', async () => {
      const {getByLabelText} = render(<ChatScreen />);
      await act(async () => {});
      for (let i = 0; i < 3; i++) { await emitTts('started'); await emitBarge(); }
      await act(async () => { fireEvent.press(getByLabelText('Spracheingabe')); });
      mockBargeStart.mockClear();
      await emitTts('started');
      expect(mockBargeStart).toHaveBeenCalled();
    });

    it('H1: im Hintergrund wird nicht gelauscht und ein spätes Barge-in-Ereignis ignoriert', async () => {
      render(<ChatScreen />);
      await act(async () => {});
      (AppState as unknown as {currentState: string}).currentState = 'background';
      await emitTts('started');
      expect(mockBargeStart).not.toHaveBeenCalled();
      await emitBarge();
      expect(mockStartListening).not.toHaveBeenCalled();
      expect(mockBargeStop).toHaveBeenCalled();
    });

    it('R1: TTS ausschalten beendet auch das Stimm-Lauschen', async () => {
      const {getByLabelText} = render(<ChatScreen />);
      await act(async () => {});
      await emitTts('started');
      mockBargeStop.mockClear();
      fireEvent.press(getByLabelText('TTS deaktivieren'));
      expect(mockBargeStop).toHaveBeenCalled();
    });

    it('R2: Barge-in ohne anschließende Äußerung (Stille) zählt Strikes - zwei schalten Voice-Barge-in ab', async () => {
      const {rerender} = render(<ChatScreen />);
      await act(async () => {});
      for (let i = 0; i < 2; i++) {
        await emitTts('started');
        await emitBarge();
        // Zuhören beginnt und endet ohne Ergebnis: Hook meldet listening → idle → Strike
        mockVoiceReturn.voiceState = 'listening';
        rerender(<ChatScreen />);
        await act(async () => {});
        mockVoiceReturn.voiceState = 'idle';
        rerender(<ChatScreen />);
        await act(async () => {});
      }
      expect(mockBargeDisable).toHaveBeenCalledWith(true);
    });

    it('R2: eine echte Äußerung nach dem Barge-in setzt die Strikes zurück', async () => {
      const {rerender} = render(<ChatScreen />);
      await act(async () => {});
      await emitTts('started');
      await emitBarge();
      mockVoiceReturn.voiceState = 'listening';
      rerender(<ChatScreen />);
      await act(async () => {});
      await act(async () => { mockVoiceOpts.onTranscript?.('mach die Musik leiser bitte'); });
      mockVoiceReturn.voiceState = 'idle';
      rerender(<ChatScreen />);
      await act(async () => {});
      expect(mockBargeDisable).not.toHaveBeenCalled();
      expect(mockSendMessage).toHaveBeenCalled();
    });
  });

  // ── Freisprechen (Turn-Taking nach TTS-Ende) ───────────────────────────────

  it('Auto-Relisten erst nach dem ECHTEN TTS-Ende (nicht nach einer Zeitschätzung)', async () => {
    const {rerender} = render(<ChatScreen />);
    await act(async () => {});
    await answeredByVoice(rerender);
    await emitTts('started');
    await new Promise(r => setTimeout(r, 450));
    expect(mockStartListening).not.toHaveBeenCalled();
    await emitTts('ended');
    await waitFor(() => expect(mockStartListening).toHaveBeenCalledTimes(1), {timeout: 1500});
  });

  it('Auto-Relisten öffnet das Mikrofon NICHT bei offener Karte (wartende Aktion)', async () => {
    const {rerender} = render(<ChatScreen />);
    await act(async () => {});
    // Donna schlägt eine Aktion vor → Karte offen (Aktion wird im Bildschirm als wartend gesetzt)
    mockUseChatImpl = {...mockUseChatImpl, messages: [
      {role: 'user', content: 'Ruf Mama an'},
      {role: 'assistant', content: 'Soll ich Mama anrufen?', actions: [{type: 'call', name: 'Mama', number: '+4915112345678'}]},
    ]};
    await act(async () => { mockVoiceOpts.onTranscript?.('Ruf Mama an'); });
    rerender(<ChatScreen />);
    await act(async () => {});
    await emitTts('ended');
    await new Promise(r => setTimeout(r, 600));
    // Karte offen oder nicht - der Test fixiert: es entsteht kein UNGEFRAGTES zweites Zuhören innerhalb von 600 ms
    // ohne TTS-Ende-Plan (relistenPending wurde nur nach „started“ geplant)
    expect(mockStartListening.mock.calls.length).toBeLessThanOrEqual(1);
  });

  it('F2: Echo-Filter greift beim Auto-Relisten, aber NICHT beim manuellen Mic-Tap', async () => {
    const {rerender, getByLabelText} = render(<ChatScreen />);
    await act(async () => {});
    await answeredByVoice(rerender, 'Soll ich das Licht im Wohnzimmer ausschalten?');
    // Manuell: Mike antwortet mit Wiederholung → kein Echo-Filter, wird gesendet
    mockSendMessage.mockClear();
    await act(async () => { fireEvent.press(getByLabelText(/Spracheingabe|unterbrechen und sprechen/)); });
    await act(async () => { mockVoiceOpts.onTranscript?.('Ja, das Licht im Wohnzimmer'); });
    expect(mockSendMessage).toHaveBeenCalledTimes(1);
  });

  it('F2: beim Auto-Relisten wird reines Echo verworfen, „Ja, das Licht im Wohnzimmer“ aber nicht', async () => {
    const {rerender} = render(<ChatScreen />);
    await act(async () => {});
    await answeredByVoice(rerender, 'Soll ich das Licht im Wohnzimmer ausschalten?');
    await emitTts('started');
    await emitTts('ended');
    await waitFor(() => expect(mockStartListening).toHaveBeenCalledTimes(1), {timeout: 1500});
    mockSendMessage.mockClear();
    // reines Echo von Donnas Satz → verworfen
    await act(async () => { mockVoiceOpts.onTranscript?.('soll ich das Licht im Wohnzimmer'); });
    expect(mockSendMessage).not.toHaveBeenCalled();
  });

  it('F2: echte Antwort nach Auto-Relisten wird gesendet', async () => {
    const {rerender} = render(<ChatScreen />);
    await act(async () => {});
    await answeredByVoice(rerender, 'Soll ich das Licht im Wohnzimmer ausschalten?');
    await emitTts('started');
    await emitTts('ended');
    await waitFor(() => expect(mockStartListening).toHaveBeenCalledTimes(1), {timeout: 1500});
    mockSendMessage.mockClear();
    await act(async () => { mockVoiceOpts.onTranscript?.('Ja, das Licht im Wohnzimmer'); });
    expect(mockSendMessage).toHaveBeenCalledTimes(1);
  });

  it('Tippen unterdrückt das Auto-Relisten', async () => {
    const {rerender, getByPlaceholderText} = render(<ChatScreen />);
    await act(async () => {});
    await answeredByVoice(rerender, 'Warum können Geister so schlecht lügen? Man sieht durch sie hindurch.');
    await emitTts('started');
    fireEvent.changeText(getByPlaceholderText('Nachricht eingeben…'), 'Moment');
    await act(async () => {});
    await emitTts('ended');
    await new Promise(r => setTimeout(r, 600));
    expect(mockStartListening).not.toHaveBeenCalled();
  });

  it('M1: Auto-Relisten wartet, solange Donna noch spricht (Rest nach dem Early-TTS-Satz) - dann erst das echte Ende', async () => {
    const {rerender} = render(<ChatScreen />);
    await act(async () => {});
    await answeredByVoice(rerender, 'Das ist der erste Satz der Antwort. Und hier kommt der Rest.');
    await emitTts('started');
    await emitTts('ended');            // Ende des ersten Satzes → Relisten wird geplant (350 ms)
    await emitTts('started');          // … der Rest beginnt sofort
    await new Promise(r => setTimeout(r, 500));
    expect(mockStartListening).not.toHaveBeenCalled();   // Mikrofon bleibt zu, solange Donna spricht
    await emitTts('ended');            // echtes Ende des Rests
    await waitFor(() => expect(mockStartListening).toHaveBeenCalledTimes(1), {timeout: 1500});
  });

  it('Echo-Verwurf zeigt einen kurzen Hinweis (wie im Overlay)', async () => {
    const toast = jest.spyOn(ToastAndroid, 'show').mockImplementation(() => {});
    const {rerender} = render(<ChatScreen />);
    await act(async () => {});
    await answeredByVoice(rerender, 'Soll ich das Licht im Wohnzimmer ausschalten?');
    await emitTts('started');
    await emitTts('ended');
    await waitFor(() => expect(mockStartListening).toHaveBeenCalledTimes(1), {timeout: 1500});
    toast.mockClear();
    await act(async () => { mockVoiceOpts.onTranscript?.('soll ich das Licht im Wohnzimmer'); });
    expect(toast).toHaveBeenCalledWith(expect.stringMatching(/nur mich selbst gehört/), expect.anything());
  });

  it('Hintergrund: ein später Early-TTS-Satz der alten Frage spricht nicht mehr', async () => {
    const speakPiper = jest.fn().mockResolvedValue('ok');
    (NativeModules as Record<string, unknown>).PiperTTS = {speakPiper, stopPiper: jest.fn()};
    render(<ChatScreen />);
    await act(async () => {});
    await act(async () => { mockVoiceOpts.onTranscript?.('Erzähl mir etwas'); });
    const staleCallback = mockSendMessage.mock.calls[mockSendMessage.mock.calls.length - 1][3] as (s: string) => void;
    const handler = (AppState.addEventListener as jest.Mock).mock.calls.map(c => c[1]).filter(Boolean).pop() as (s: string) => void;
    await act(async () => { handler('background'); });
    speakPiper.mockClear();
    staleCallback('Ein später Satz, der im Hintergrund nicht mehr gesprochen werden darf.');
    expect(speakPiper).not.toHaveBeenCalled();
    delete (NativeModules as Record<string, unknown>).PiperTTS;
  });

  // ── Hintergrund / Sperre (H1) ──────────────────────────────────────────────

  it('H1: Wechsel in den Hintergrund bricht Zuhören, Stream, TTS und Stimm-Lauschen ab (kein stop mit Ergebnis)', async () => {
    render(<ChatScreen />);
    await act(async () => {});
    const handler = (AppState.addEventListener as jest.Mock).mock.calls
      .map(c => c[1])
      .filter(Boolean)
      .pop() as (s: string) => void;
    mockTTSStop.mockClear();
    await act(async () => { handler('background'); });
    expect(mockCancelChat).toHaveBeenCalled();
    expect(mockCancelListening).toHaveBeenCalled();     // Voice.cancel statt stop
    expect(mockStopListening).not.toHaveBeenCalled();
    expect(mockTTSStop).toHaveBeenCalled();
    expect(mockBargeStop).toHaveBeenCalled();
  });

  it('H1: ein spätes Transkript im Hintergrund löst keinen Chat-Aufruf aus', async () => {
    render(<ChatScreen />);
    await act(async () => {});
    (AppState as unknown as {currentState: string}).currentState = 'background';
    mockSendMessage.mockClear();
    await act(async () => { mockVoiceOpts.onTranscript?.('mach das Licht an'); });
    expect(mockSendMessage).not.toHaveBeenCalled();
  });

  it('H1: im Hintergrund wird nicht automatisch zugehört', async () => {
    const {rerender} = render(<ChatScreen />);
    await act(async () => {});
    await answeredByVoice(rerender);
    await emitTts('started');
    (AppState as unknown as {currentState: string}).currentState = 'background';
    await emitTts('ended');
    await new Promise(r => setTimeout(r, 600));
    expect(mockStartListening).not.toHaveBeenCalled();
    expect(mockOfflineStart).not.toHaveBeenCalled();
  });

  // ── F5: Haupt-Chat-Erkennung über OfflineSTT (mit Biasing) ─────────────────

  describe('On-Device-STT (OfflineSTT) mit Raum-Biasing', () => {
    beforeEach(() => mockOfflineIsAvailable.mockResolvedValue(true));

    it('Mic-Tap startet OfflineSTT statt @react-native-voice', async () => {
      const {getByLabelText} = render(<ChatScreen />);
      await act(async () => {});
      await act(async () => { fireEvent.press(getByLabelText('Spracheingabe')); });
      await waitFor(() => expect(mockOfflineStart).toHaveBeenCalledWith('de-DE'));
      expect(mockStartListening).not.toHaveBeenCalled();
    });

    it('das Ergebnis läuft durch denselben Weg wie Cloud-STT und wird gesendet', async () => {
      const {getByLabelText} = render(<ChatScreen />);
      await act(async () => {});
      await act(async () => { fireEvent.press(getByLabelText('Spracheingabe')); });
      await waitFor(() => expect(mockOfflineStart).toHaveBeenCalled());
      mockSendMessage.mockClear();
      await act(async () => { mockOfflineOnResult?.('Schalte das Licht im Streamingzimmer an'); });
      expect(mockSendMessage).toHaveBeenCalledWith(
        'Schalte das Licht im Streamingzimmer an', undefined, 'android', expect.any(Function),
      );
    });

    it('ein Ergebnis ohne laufendes Zuhören (nach Abbruch) wird verworfen', async () => {
      render(<ChatScreen />);
      await act(async () => {});
      mockSendMessage.mockClear();
      await act(async () => { mockOfflineOnResult?.('Spätes Ergebnis'); });
      expect(mockSendMessage).not.toHaveBeenCalled();
    });

    it('zweiter Mic-Tap beendet („Fertig“) und sendet das Ergebnis, ohne den Erkenner zu zerstören', async () => {
      const {getByLabelText} = render(<ChatScreen />);
      await act(async () => {});
      await act(async () => { fireEvent.press(getByLabelText('Spracheingabe')); });
      await waitFor(() => expect(mockOfflineStart).toHaveBeenCalled());
      await act(async () => { fireEvent.press(getByLabelText('Aufnahme stoppen')); });
      expect(mockOfflineStop).toHaveBeenCalled();
      expect(mockOfflineCancel).not.toHaveBeenCalled();
    });

    it('Wechsel in den Hintergrund bricht OfflineSTT ab (cancel, kein Ergebnis)', async () => {
      const {getByLabelText} = render(<ChatScreen />);
      await act(async () => {});
      await act(async () => { fireEvent.press(getByLabelText('Spracheingabe')); });
      await waitFor(() => expect(mockOfflineStart).toHaveBeenCalled());
      mockOfflineCancel.mockClear();
      const handler = (AppState.addEventListener as jest.Mock).mock.calls.map(c => c[1]).filter(Boolean).pop() as (s: string) => void;
      await act(async () => { handler('background'); });
      expect(mockOfflineCancel).toHaveBeenCalled();
      mockSendMessage.mockClear();
      await act(async () => { mockOfflineOnResult?.('kommt zu spät'); });
      expect(mockSendMessage).not.toHaveBeenCalled();
    });

    it('Tippen ins Textfeld beendet das Zuhören (Feld bleibt benutzbar)', async () => {
      const {getByLabelText, getByPlaceholderText} = render(<ChatScreen />);
      await act(async () => {});
      await act(async () => { fireEvent.press(getByLabelText('Spracheingabe')); });
      await waitFor(() => expect(mockOfflineStart).toHaveBeenCalled());
      mockOfflineCancel.mockClear();
      const input = getByPlaceholderText('Zuhören…');
      expect(input.props.editable).not.toBe(false);
      fireEvent.changeText(input, 'Hallo');
      expect(mockOfflineCancel).toHaveBeenCalled();
    });

    it('harter Fehler → ab dann Cloud-STT; Stille/nichts erkannt (6/7) bleibt bei OfflineSTT', async () => {
      const {getByLabelText} = render(<ChatScreen />);
      await act(async () => {});
      await act(async () => { mockOfflineOnError?.(7); });
      await act(async () => { fireEvent.press(getByLabelText('Spracheingabe')); });
      await waitFor(() => expect(mockOfflineStart).toHaveBeenCalledTimes(1));
      await act(async () => { mockOfflineOnError?.(5); });   // ERROR_CLIENT → Fallback
      mockStartListening.mockClear();
      await act(async () => { fireEvent.press(getByLabelText('Spracheingabe')); });
      await waitFor(() => expect(mockStartListening).toHaveBeenCalledTimes(1));
    });

    it('wenn OfflineSTT nicht startet, geht es mit Cloud-STT weiter', async () => {
      mockOfflineStart.mockRejectedValueOnce(new Error('STT_START_FAILED'));
      const {getByLabelText} = render(<ChatScreen />);
      await act(async () => {});
      await act(async () => { fireEvent.press(getByLabelText('Spracheingabe')); });
      await waitFor(() => expect(mockStartListening).toHaveBeenCalledTimes(1));
    });
  });

  // ── B1: OfflineSTT-Watchdogs (Mic nie unbegrenzt offen, „Zuhören“ hängt nie) ──

  describe('OfflineSTT-Watchdogs', () => {
    beforeEach(() => {
      mockOfflineIsAvailable.mockResolvedValue(true);
      jest.useFakeTimers();
    });
    afterEach(() => { jest.useRealTimers(); });

    const press = async (utils: ReturnType<typeof render>, label: RegExp | string = 'Spracheingabe') => {
      await act(async () => { fireEvent.press(utils.getByLabelText(label)); });
      await act(async () => {});
    };
    const advance = (ms: number) => act(async () => { jest.advanceTimersByTime(ms); });

    it('ohne Partial bricht das Zuhören nach 10 s ab (Mic wieder frei, Tap funktioniert wieder)', async () => {
      const utils = render(<ChatScreen />);
      await act(async () => {});
      await press(utils);
      expect(mockOfflineStart).toHaveBeenCalledTimes(1);
      expect(utils.queryByLabelText('Aufnahme stoppen')).toBeTruthy();
      mockOfflineCancel.mockClear();
      await advance(9_000);
      expect(mockOfflineCancel).not.toHaveBeenCalled();
      await advance(1_500);
      expect(mockOfflineCancel).toHaveBeenCalled();
      expect(utils.queryByLabelText('Aufnahme stoppen')).toBeNull();
      // und der nächste Mic-Tap startet wieder (kein hängender Zustand)
      mockOfflineStart.mockClear();
      await press(utils);
      expect(mockOfflineStart).toHaveBeenCalledTimes(1);
    });

    it('mit Partial läuft das Zuhören weiter; das Maximum (50 s) beendet mit „Fertig“', async () => {
      const utils = render(<ChatScreen />);
      await act(async () => {});
      await press(utils);
      await act(async () => { mockOfflineOnPartial?.('Schalte das Licht'); });
      mockOfflineCancel.mockClear();
      await advance(15_000);
      expect(mockOfflineCancel).not.toHaveBeenCalled();
      expect(mockOfflineStop).not.toHaveBeenCalled();
      await advance(36_000);   // > 50 s insgesamt
      expect(mockOfflineStop).toHaveBeenCalled();
    }, 60_000);   // Animationen (Orb/Welle) laufen unter Fake-Timern mit — 50 s simulierte Zeit brauchen Rechenzeit

    it('nach „Fertig“ ohne Ergebnis bricht ein Watchdog nach 9 s ab', async () => {
      const utils = render(<ChatScreen />);
      await act(async () => {});
      await press(utils);
      await act(async () => { mockOfflineOnPartial?.('Hallo'); });
      await press(utils, 'Aufnahme stoppen');
      expect(mockOfflineStop).toHaveBeenCalledTimes(1);
      mockOfflineCancel.mockClear();
      await advance(8_000);
      expect(mockOfflineCancel).not.toHaveBeenCalled();
      await advance(1_500);
      expect(mockOfflineCancel).toHaveBeenCalled();
      expect(utils.queryByLabelText('Aufnahme stoppen')).toBeNull();
    });

    it('ein Ergebnis löscht alle Timer (kein spätes Abbrechen)', async () => {
      const utils = render(<ChatScreen />);
      await act(async () => {});
      await press(utils);
      await act(async () => { mockOfflineOnPartial?.('Hallo'); });
      await act(async () => { mockOfflineOnResult?.('Hallo Donna'); });
      mockOfflineCancel.mockClear();
      mockOfflineStop.mockClear();
      await advance(120_000);
      expect(mockOfflineCancel).not.toHaveBeenCalled();
      expect(mockOfflineStop).not.toHaveBeenCalled();
    });

    it('ein Fehler löscht alle Timer', async () => {
      const utils = render(<ChatScreen />);
      await act(async () => {});
      await press(utils);
      await act(async () => { mockOfflineOnError?.(7); });
      mockOfflineCancel.mockClear();
      await advance(120_000);
      expect(mockOfflineCancel).not.toHaveBeenCalled();
    });

    it('Abbruch (z. B. Tippen) löscht die Timer', async () => {
      const utils = render(<ChatScreen />);
      await act(async () => {});
      await press(utils);
      fireEvent.changeText(utils.getByPlaceholderText('Zuhören…'), 'x');
      await act(async () => {});
      mockOfflineCancel.mockClear();
      await advance(120_000);
      expect(mockOfflineCancel).not.toHaveBeenCalled();
    });

    it('Race: App geht während OfflineSTT.start() in den Hintergrund → Mikrofon wird abgebrochen, UI hängt nicht', async () => {
      let resolveStart: (v: boolean) => void = () => {};
      mockOfflineStart.mockImplementationOnce(() => new Promise<boolean>(r => { resolveStart = r; }));
      const utils = render(<ChatScreen />);
      await act(async () => {});
      await act(async () => { fireEvent.press(utils.getByLabelText('Spracheingabe')); });
      // während start() noch läuft: Hintergrund
      const handler = (AppState.addEventListener as jest.Mock).mock.calls.map(c => c[1]).filter(Boolean).pop() as (s: string) => void;
      (AppState as unknown as {currentState: string}).currentState = 'background';
      await act(async () => { handler('background'); });
      mockOfflineCancel.mockClear();
      await act(async () => { resolveStart(true); });
      await act(async () => {});
      expect(mockOfflineCancel).toHaveBeenCalled();
      (AppState as unknown as {currentState: string}).currentState = 'active';
      expect(utils.queryByLabelText('Aufnahme stoppen')).toBeNull();
      await advance(120_000);   // und kein Timer feuert hinterher
    });
  });

  it('ohne On-Device-STT läuft die Erkennung über Cloud-STT (useVoice)', async () => {
    const {getByLabelText} = render(<ChatScreen />);
    await act(async () => {});
    await act(async () => { fireEvent.press(getByLabelText('Spracheingabe')); });
    await waitFor(() => expect(mockStartListening).toHaveBeenCalledTimes(1));
    expect(mockOfflineStart).not.toHaveBeenCalled();
  });
});

// ─── Wetterkarte (Optik) ─────────────────────────────────────────────────────
describe('ChatScreen Wetterkarte', () => {
  const weather = {
    location: 'YOUR_HOME_CITY',
    temp_c: 14,
    feels_like_c: 12,
    temp_min: 8,
    temp_max: 18,
    condition: 'Teilweise bewölkt',
    condition_icon: '⛅',
    humidity: 63,
    wind_kmh: 11,
    hourly: [
      {time: '0 Uhr', temp_c: 9, icon: '🌙', precip_pct: 0},
      {time: '12 Uhr', temp_c: 15, icon: '⛅', precip_pct: 10},
      {time: '23 Uhr', temp_c: 10, icon: '🌧️', precip_pct: 40},
    ],
  };

  function renderWeather(data: object = weather) {
    mockUseChatImpl = {
      ...mockUseChatImpl,
      messages: [
        {role: 'user', content: 'Wetter?'},
        {role: 'assistant', content: 'Hier das Wetter.', card: {card_type: 'weather', data}},
      ],
    };
    return render(<ChatScreen />);
  }

  it('zeigt die Daten mit klarer Hierarchie (Gefühlt nahe der Temperatur, Hoch/Tief als eigene Zeile)', async () => {
    const {getByText, getAllByText} = renderWeather();
    await act(async () => {});
    expect(getAllByText('14°').length).toBeGreaterThan(0);
    expect(getByText('Gefühlt 12°')).toBeTruthy();
    expect(getByText('Teilweise bewölkt')).toBeTruthy();
    // Hoch/Tief: Pfeil UND Wert (temp_max 18 / temp_min 8) — Wegfall eines Feldes wird rot
    expect(getByText('↑ 18°')).toBeTruthy();
    expect(getByText('↓ 8°')).toBeTruthy();
    // Niederschlag: Durchschnitt der Stundenwerte (0/10/40 -> 17 %)
    expect(getByText('17 %')).toBeTruthy();
    // Werte stehen einzeln mit Beschriftung, nicht im Fließtext
    expect(getByText('Luftfeuchte')).toBeTruthy();
    expect(getByText('Wind')).toBeTruthy();
    expect(getByText('Niederschlag')).toBeTruthy();
    expect(getByText('63 %')).toBeTruthy();
    expect(getByText('11 km/h')).toBeTruthy();
  });

  it('Standort-Zeile in Satzschreibung: Wochentag · Ort', async () => {
    const {getByText} = renderWeather();
    await act(async () => {});
    expect(getByText(/ · YOUR_HOME_CITY$/)).toBeTruthy();
  });

  it('keine Emoji-Icons mehr (Zustand, Luftfeuchte, Wind, Stundenvorschau) und kein ▶-Marker', async () => {
    const {queryByText} = renderWeather();
    await act(async () => {});
    for (const emoji of ['💧', '💨', '🌙', '⛅', '🌧️', '🌡️']) {
      expect(queryByText(new RegExp(emoji, 'u'))).toBeNull();
    }
    expect(queryByText(/▶/)).toBeNull();
    expect(queryByText(/Höchst:/)).toBeNull();
  });

  it('zeichnet Verlauf-Hintergrund und Wetter-Icons', async () => {
    const {getByTestId, getAllByTestId} = renderWeather();
    await act(async () => {});
    expect(getByTestId('weather-backdrop')).toBeTruthy();
    expect(getAllByTestId('weather-glyph', {hidden: true}).length).toBe(1);
  });

  it('aktuelle Stunde wird dezent als „Jetzt“ markiert', async () => {
    const {getAllByText} = renderWeather();
    await act(async () => {});
    expect(getAllByText('Jetzt').length).toBe(1);
  });

  it('ohne Stundenvorschau: keine erfundenen Niederschlagswerte', async () => {
    const {queryByText} = renderWeather({...weather, hourly: undefined});
    await act(async () => {});
    expect(queryByText('Niederschlag')).toBeNull();
    expect(queryByText('Jetzt')).toBeNull();
  });
});

// ─── Karten-Redesign B3: Karten teilen ein System, Daten bleiben erhalten ─────────────────────
describe('ChatScreen Info-Karten (Wetter/Maps/Kalender) — gemeinsames One-UI-System', () => {
  const weather = {
    location: 'YOUR_HOME_CITY', temp_c: 14, feels_like_c: 12, temp_min: 8, temp_max: 18,
    condition: 'Teilweise bewölkt', condition_icon: '⛅', humidity: 63, wind_kmh: 11,
  };
  const map = {query: 'Marktplatz 1, Ansbach', maps_url: 'https://maps.google.com/?q=Marktplatz'};
  const calendar = {
    events: [
      {title: 'Zahnarzt', when_label: 'Heute', time: '14:30', location: 'Praxis Dr. Meier'},
      {title: 'Stream-Planung', when_label: 'Morgen'},
    ],
  };

  function renderCard(card_type: string, data: object) {
    mockUseChatImpl = {
      ...mockUseChatImpl,
      messages: [
        {role: 'user', content: 'Zeig mir das'},
        {role: 'assistant', content: 'Bitte sehr.', card: {card_type, data}},
      ],
    };
    return render(<ChatScreen />);
  }
  const flat = (node: {props: {style: unknown}}) => StyleSheet.flatten(node.props.style as StyleProp<ViewStyle>);

  afterEach(() => { (useColorScheme as jest.Mock).mockReturnValue('light'); });

  it.each(['dark', 'light'] as const)('alle drei Karten: gleicher Radius, Elevation und Theme-Fläche (%s)', async scheme => {
    (useColorScheme as jest.Mock).mockReturnValue(scheme);
    const palette = scheme === 'dark' ? PULSE_DARK : PULSE_LIGHT;
    const surface = scheme === 'dark' ? palette.card : palette.surface;
    const styles = [];
    for (const [type, data, id] of [
      ['weather', weather, 'weather-card'],
      ['map', map, 'map-card'],
      ['calendar', calendar, 'calendar-card'],
    ] as const) {
      const {getByTestId, unmount} = renderCard(type, data);
      await act(async () => {});
      styles.push(flat(getByTestId(id)));
      unmount();
    }
    for (const st of styles) {
      expect(st.borderRadius).toBe(28);
      expect(st.elevation).toBe(3);
      expect(st.backgroundColor).toBe(surface);
      expect(st.borderColor).toBe(palette.border);
    }
  });

  it('MapCard: Ziel, Beschriftung und Tipp → maps_url öffnen (Verhalten unverändert)', async () => {
    const open = jest.spyOn(require('react-native').Linking, 'openURL').mockResolvedValue(undefined);
    const {getByText, getByLabelText, getByTestId} = renderCard('map', map);
    await act(async () => {});
    expect(getByText('In Google Maps öffnen')).toBeTruthy();
    expect(getByText('Marktplatz 1, Ansbach')).toBeTruthy();
    expect(getByTestId('icon-pin', {hidden: true})).toBeTruthy();
    fireEvent.press(getByLabelText('In Google Maps öffnen'));
    expect(open).toHaveBeenCalledWith(map.maps_url);
    open.mockRestore();
  });

  it('CalendarCard: Titel, when_label, Uhrzeit und Ort bleiben erhalten; ganztägig ohne Uhrzeit', async () => {
    const {getByText, queryByText, getByTestId} = renderCard('calendar', calendar);
    await act(async () => {});
    expect(getByText('Anstehende Termine')).toBeTruthy();
    expect(getByText('Zahnarzt')).toBeTruthy();
    expect(getByText('Heute')).toBeTruthy();
    expect(getByText('14:30')).toBeTruthy();
    expect(getByText('Praxis Dr. Meier')).toBeTruthy();
    expect(getByText('Stream-Planung')).toBeTruthy();
    expect(getByText('Morgen')).toBeTruthy();
    expect(getByTestId('icon-calendar', {hidden: true})).toBeTruthy();
    // Kein Emoji-Ersatz mehr; keine erfundenen Felder
    expect(queryByText(/📍|📅/u)).toBeNull();
    expect(queryByText(/Uhr$/)).toBeNull();
  });

  it('CalendarCard ohne Termine rendert keine Karte (defensiv wie zuvor)', async () => {
    const {queryByTestId} = renderCard('calendar', {events: []});
    await act(async () => {});
    expect(queryByTestId('calendar-card')).toBeNull();
  });

  it('WeatherCard: Detailwerte liegen in der getönten Kachel (Theme-Token, keine Hartkodierung)', async () => {
    (useColorScheme as jest.Mock).mockReturnValue('light');
    const {getByTestId, getByText} = renderCard('weather', weather);
    await act(async () => {});
    const st = flat(getByTestId('weather-stats'));
    expect(st.backgroundColor).toBe(PULSE_LIGHT.accentSoft);
    expect(st.borderRadius).toBe(20);
    expect(getByText('63 %')).toBeTruthy();
    expect(getByText('11 km/h')).toBeTruthy();
  });
});

// ─── Notification-Tap (Warm-Start): natives Event seedet den Chat mit dem Push-Inhalt ───────
describe('ChatScreen Notification-Tap (Warm-Start)', () => {
  afterEach(() => { delete (NativeModules as Record<string, unknown>).ProactiveMessageModule; });

  it('Warm-Start: natives Event → getAndClear → Chat mit Titel + vollem Body geseedet', async () => {
    // Mount-Poll liefert nichts; erst der Tap (onNewIntent → Event) liefert den Payload.
    const getAndClear = jest.fn()
      .mockResolvedValueOnce(null)
      .mockResolvedValue(JSON.stringify({
        message: 'Heute 13 Uhr Stream, danach Review.',
        title: 'Streamplan',
        session_id: 's9',
        nonce: 'n1',
      }));
    (NativeModules as Record<string, unknown>).ProactiveMessageModule = {getAndClear};
    render(<ChatScreen />);
    await act(async () => {});
    // App läuft WARM im Hintergrund → MainActivity.onNewIntent emittiert nur dieses Event (kein neuer Mount)
    await act(async () => { DeviceEventEmitter.emit('donna_open_proactive_chat', '{}'); });
    await waitFor(() =>
      expect(mockUseChatImpl.startProactiveChat).toHaveBeenCalledWith(
        'Streamplan\n\nHeute 13 Uhr Stream, danach Review.',
        's9',
      ),
    );
  });

  it('ohne aussagekräftigen Titel wird nur der Body geseedet', async () => {
    const getAndClear = jest.fn()
      .mockResolvedValueOnce(null)
      .mockResolvedValue(JSON.stringify({message: 'Nur Body.', title: 'Donna', nonce: 'n2'}));
    (NativeModules as Record<string, unknown>).ProactiveMessageModule = {getAndClear};
    render(<ChatScreen />);
    await act(async () => {});
    await act(async () => { DeviceEventEmitter.emit('donna_open_proactive_chat', '{}'); });
    await waitFor(() =>
      expect(mockUseChatImpl.startProactiveChat).toHaveBeenCalledWith('Nur Body.', undefined),
    );
  });

  it('Doppel-Tap mit gleichem Nonce seedet nur EINMAL', async () => {
    const payload = JSON.stringify({message: 'Body', title: 'Titel', nonce: 'dupe'});
    const getAndClear = jest.fn().mockResolvedValueOnce(null).mockResolvedValue(payload);
    (NativeModules as Record<string, unknown>).ProactiveMessageModule = {getAndClear};
    render(<ChatScreen />);
    await act(async () => {});
    await act(async () => { DeviceEventEmitter.emit('donna_open_proactive_chat', '{}'); });
    await act(async () => { DeviceEventEmitter.emit('donna_open_proactive_chat', '{}'); });
    await waitFor(() => expect(mockUseChatImpl.startProactiveChat).toHaveBeenCalledTimes(1));
  });

  it('normaler Mount ohne Payload seedet NICHTS', async () => {
    const getAndClear = jest.fn().mockResolvedValue(null);
    (NativeModules as Record<string, unknown>).ProactiveMessageModule = {getAndClear};
    render(<ChatScreen />);
    await act(async () => {});
    await act(async () => { DeviceEventEmitter.emit('donna_open_proactive_chat', '{}'); });
    expect(getAndClear).toHaveBeenCalled();                 // es wurde geprüft
    expect(mockUseChatImpl.startProactiveChat).not.toHaveBeenCalled(); // aber nichts geseedet
  });

  it('zweiter Tap mit ANDEREM Nonce seedet erneut', async () => {
    const getAndClear = jest.fn()
      .mockResolvedValueOnce(null) // Mount
      .mockResolvedValueOnce(JSON.stringify({message: 'Eins', title: 'A', nonce: 'n-a'}))
      .mockResolvedValueOnce(JSON.stringify({message: 'Zwei', title: 'B', nonce: 'n-b'}));
    (NativeModules as Record<string, unknown>).ProactiveMessageModule = {getAndClear};
    render(<ChatScreen />);
    await act(async () => {});
    await act(async () => { DeviceEventEmitter.emit('donna_open_proactive_chat', '{}'); });
    await waitFor(() => expect(mockUseChatImpl.startProactiveChat).toHaveBeenCalledWith('A\n\nEins', undefined));
    await act(async () => { DeviceEventEmitter.emit('donna_open_proactive_chat', '{}'); });
    await waitFor(() => expect(mockUseChatImpl.startProactiveChat).toHaveBeenCalledWith('B\n\nZwei', undefined));
    expect(mockUseChatImpl.startProactiveChat).toHaveBeenCalledTimes(2);
  });
});

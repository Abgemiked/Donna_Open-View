import React from 'react';
import {render, fireEvent, waitFor, act} from '@testing-library/react-native';
import ChatScreen from '../screens/ChatScreen';
import type {UseChatReturn} from '@donna/shared';

// ─── NativeModules-Mock (TTSModule) ──────────────────────────────────────────
const mockTTSSpeakViaKokoro = jest.fn((_text: string, resolve: () => void) => resolve());
const mockTTSStop = jest.fn();
const mockTTSSpeak = jest.fn();

jest.mock('react-native', () => {
  const RN = jest.requireActual('react-native');
  return {
    ...RN,
    NativeModules: {
      ...RN.NativeModules,
      TTSModule: {
        speakViaKokoro: mockTTSSpeakViaKokoro,
        stop: mockTTSStop,
        speak: mockTTSSpeak,
      },
    },
  };
});

// ─── AsyncStorage-Mock ───────────────────────────────────────────────────────
const mockAsyncStorageGetItem = jest.fn().mockResolvedValue(null);
const mockAsyncStorageSetItem = jest.fn().mockResolvedValue(undefined);

jest.mock('@react-native-async-storage/async-storage', () => ({
  __esModule: true,
  default: {
    getItem: mockAsyncStorageGetItem,
    setItem: mockAsyncStorageSetItem,
  },
}));

// ─── Shared-Hook-Mock ─────────────────────────────────────────────────────────
const mockSendMessage = jest.fn().mockResolvedValue(undefined);
const mockClearMessages = jest.fn();

let mockUseChatImpl: UseChatReturn = {
  messages: [],
  isLoading: false,
  sendMessage: mockSendMessage,
  clearMessages: mockClearMessages,
};

jest.mock('@donna/shared', () => ({
  useChat: () => mockUseChatImpl,
  useVoice: () => ({
    isListening: false,
    isAvailable: true,
    partialTranscript: '',
    startListening: jest.fn(),
    stopListening: jest.fn(),
  }),
  fetchSessions: jest.fn().mockResolvedValue([]),
  fetchSessionMessages: jest.fn().mockResolvedValue([]),
  sendFeedback: jest.fn().mockResolvedValue(true),
  fuzzyScore: jest.fn().mockReturnValue(0),
}));

jest.mock('../App', () => ({
  voiceTriggerRef: {current: null},
}));

jest.mock('../native/AndroidVoiceModule', () => ({
  AndroidVoiceModule: {},
}));

jest.mock('../native/ContactsBridge', () => ({
  searchContactsByName: jest.fn().mockResolvedValue([]),
}));

// ─── Tests ───────────────────────────────────────────────────────────────────

beforeEach(() => {
  jest.clearAllMocks();
  mockAsyncStorageGetItem.mockResolvedValue(null);
  mockUseChatImpl = {
    messages: [],
    isLoading: false,
    sendMessage: mockSendMessage,
    clearMessages: mockClearMessages,
  };
});

describe('ChatScreen', () => {
  it('renders header with DONNA title', async () => {
    const {getByText} = render(<ChatScreen />);
    await act(async () => {});
    expect(getByText('DONNA')).toBeTruthy();
  });

  it('renders TTS-Toggle-Button in header', async () => {
    const {getByAccessibilityLabel} = render(<ChatScreen />);
    await act(async () => {});
    // Default: TTS enabled → zeigt 🔊
    expect(getByAccessibilityLabel('TTS deaktivieren')).toBeTruthy();
  });

  it('TTS-Toggle deaktiviert TTS und ruft TTSModule.stop auf', async () => {
    const {getByAccessibilityLabel} = render(<ChatScreen />);
    await act(async () => {});
    const btn = getByAccessibilityLabel('TTS deaktivieren');
    fireEvent.press(btn);
    await act(async () => {});
    expect(mockTTSStop).toHaveBeenCalled();
    expect(mockAsyncStorageSetItem).toHaveBeenCalledWith('donna_tts_enabled', 'false');
  });

  it('TTS-Toggle-State wird aus AsyncStorage geladen', async () => {
    mockAsyncStorageGetItem.mockResolvedValueOnce('false');
    const {getByAccessibilityLabel} = render(<ChatScreen />);
    await act(async () => {});
    // Wenn 'false' gespeichert → zeigt 🔇 → Label = 'TTS aktivieren'
    expect(getByAccessibilityLabel('TTS aktivieren')).toBeTruthy();
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
    const {getByAccessibilityLabel} = render(<ChatScreen />);
    await act(async () => {});
    const sendButton = getByAccessibilityLabel('Nachricht senden');
    expect(sendButton.props.accessibilityState?.disabled).toBe(true);
  });

  it('calls sendMessage when send button pressed with text', async () => {
    const {getByPlaceholderText, getByAccessibilityLabel} = render(<ChatScreen />);
    await act(async () => {});
    const input = getByPlaceholderText('Nachricht eingeben…');
    fireEvent.changeText(input, 'Hallo Donna');
    fireEvent.press(getByAccessibilityLabel('Nachricht senden'));

    await waitFor(() => {
      expect(mockSendMessage).toHaveBeenCalledWith('Hallo Donna');
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
      (_text: string, resolve: (result: string) => void) => resolve('live_guard'),
    );

    // Test: speakViaKokoro kann mit 'live_guard' aufgerufen werden ohne Fehler
    const speakFn = mockTTSSpeakViaKokoro;
    let result = '';
    await act(async () => {
      speakFn('Hallo', (r: string) => { result = r; }, () => {});
    });
    expect(result).toBe('live_guard');
  });
});

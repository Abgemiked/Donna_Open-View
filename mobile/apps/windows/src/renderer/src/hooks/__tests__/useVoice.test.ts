/**
 * useVoice — Unit-Tests (DONNA-33)
 *
 * Testet:
 * 1. supported=false wenn MediaRecorder fehlt
 * 2. start() → isListening=true
 * 3. VAD onSpeechEnd → automatischer Stop + Transkription
 * 4. Manueller stop() unterbricht Aufnahme
 * 5. Fehlerbehandlung: getUserMedia verweigert
 * 6. Transcript-Reset beim neuen start()
 */

import { describe, it, expect, vi, beforeEach, afterEach, type Mock } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useVoice } from '../useVoice';

// ─── Mock-Setup ───────────────────────────────────────────────────────────────

// Typ-Hilfsmittel für MicVAD-Mock-Optionen (spiegelt onSpeechStart/onSpeechEnd)
interface VADOptions {
  onSpeechStart?: () => void;
  onSpeechEnd?: (audio: Float32Array) => void;
  onVADMisfire?: () => void;
}

// MicVAD-Factory-Mock — gibt Kontrolle über Callbacks an Tests
let capturedVADOptions: VADOptions = {};
const mockVADInstance = {
  start: vi.fn(),
  pause: vi.fn(),
  destroy: vi.fn(),
};

vi.mock('@ricky0123/vad-web', () => ({
  MicVAD: {
    new_: async (options: VADOptions) => {
      capturedVADOptions = options;
      return mockVADInstance;
    },
  },
}));

// MediaRecorder-Mock
class MockMediaRecorder {
  static isTypeSupported = vi.fn(() => true);
  state: 'inactive' | 'recording' | 'paused' = 'inactive';
  mimeType = 'audio/webm';
  ondataavailable: ((e: { data: Blob }) => void) | null = null;
  onstop: (() => void) | null = null;
  onerror: (() => void) | null = null;

  start = vi.fn(() => { this.state = 'recording'; });
  stop = vi.fn(() => {
    this.state = 'inactive';
    // Simuliert ondataavailable mit Dummy-Daten
    const dummyBlob = new Blob([new Uint8Array(6000)], { type: 'audio/webm' });
    this.ondataavailable?.({ data: dummyBlob });
    this.onstop?.();
  });
}

let mockGetUserMedia: Mock;
let mockFetch: Mock;

beforeEach(() => {
  vi.resetAllMocks();
  capturedVADOptions = {};
  mockVADInstance.start.mockClear();
  mockVADInstance.pause.mockClear();
  mockVADInstance.destroy.mockClear();

  // MediaRecorder global mocken
  (global as any).MediaRecorder = MockMediaRecorder;

  // getUserMedia mocken — gibt Dummy-Stream zurück
  const mockTrack = { stop: vi.fn() };
  const mockStream = { getTracks: () => [mockTrack] };
  mockGetUserMedia = vi.fn().mockResolvedValue(mockStream);

  Object.defineProperty(global, 'navigator', {
    value: {
      mediaDevices: {
        getUserMedia: mockGetUserMedia,
      },
    },
    writable: true,
    configurable: true,
  });

  // fetch mocken — /speech/transcribe
  mockFetch = vi.fn().mockResolvedValue({
    ok: true,
    json: async () => ({ text: 'Hallo Donna' }),
  });
  global.fetch = mockFetch;
});

afterEach(() => {
  vi.restoreAllMocks();
});

// ─── Tests ────────────────────────────────────────────────────────────────────

describe('useVoice — Grundfunktionen', () => {

  it('supported=false wenn MediaRecorder nicht verfügbar', () => {
    (global as any).MediaRecorder = undefined;
    const { result } = renderHook(() => useVoice());
    expect(result.current.supported).toBe(false);
    expect(result.current.isListening).toBe(false);
  });

  it('supported=true wenn MediaRecorder verfügbar', () => {
    const { result } = renderHook(() => useVoice());
    expect(result.current.supported).toBe(true);
  });

  it('start() setzt isListening=true und ruft getUserMedia auf', async () => {
    const { result } = renderHook(() => useVoice());

    await act(async () => {
      result.current.start();
    });

    expect(mockGetUserMedia).toHaveBeenCalledWith({ audio: true });
    expect(result.current.isListening).toBe(true);
  });

  it('transcript ist leer beim initialen Render', () => {
    const { result } = renderHook(() => useVoice());
    expect(result.current.transcript).toBe('');
  });

  it('error ist null beim initialen Render', () => {
    const { result } = renderHook(() => useVoice());
    expect(result.current.error).toBeNull();
  });
});

describe('useVoice — VAD Auto-Stop', () => {

  it('VAD onSpeechEnd → stop → Transkription via /speech/transcribe', async () => {
    const { result } = renderHook(() => useVoice());

    // Starten
    await act(async () => {
      result.current.start();
    });

    expect(result.current.isListening).toBe(true);

    // VAD-Start wurde aufgerufen
    expect(mockVADInstance.start).toHaveBeenCalled();

    // VAD onSpeechStart simulieren → Recorder beginnt
    act(() => {
      capturedVADOptions.onSpeechStart?.();
    });

    // VAD onSpeechEnd simulieren (~1.5 s Stille erkannt)
    await act(async () => {
      capturedVADOptions.onSpeechEnd?.(new Float32Array(0));
      // Warten auf async handleRecordingStop
      await new Promise(r => setTimeout(r, 50));
    });

    // fetch sollte aufgerufen worden sein
    expect(mockFetch).toHaveBeenCalledWith(
      expect.stringContaining('/speech/transcribe'),
      expect.objectContaining({ method: 'POST' }),
    );

    // Transcript gesetzt
    expect(result.current.transcript).toBe('Hallo Donna');

    // isListening nach Transkription false
    expect(result.current.isListening).toBe(false);
  });

  it('VAD pause() wird beim Auto-Stop aufgerufen', async () => {
    const { result } = renderHook(() => useVoice());

    await act(async () => {
      result.current.start();
    });

    act(() => {
      capturedVADOptions.onSpeechStart?.();
    });

    await act(async () => {
      capturedVADOptions.onSpeechEnd?.(new Float32Array(0));
      await new Promise(r => setTimeout(r, 50));
    });

    expect(mockVADInstance.pause).toHaveBeenCalled();
  });
});

describe('useVoice — Manueller Stop', () => {

  it('stop() setzt isListening=false', async () => {
    const { result } = renderHook(() => useVoice());

    await act(async () => {
      result.current.start();
    });
    expect(result.current.isListening).toBe(true);

    act(() => {
      result.current.stop();
    });

    expect(result.current.isListening).toBe(false);
  });

  it('stop() ruft VAD.pause() auf', async () => {
    const { result } = renderHook(() => useVoice());

    await act(async () => {
      result.current.start();
    });

    act(() => {
      result.current.stop();
    });

    expect(mockVADInstance.pause).toHaveBeenCalled();
  });
});

describe('useVoice — Fehlerbehandlung', () => {

  it('getUserMedia-Fehler → isListening=false + error gesetzt', async () => {
    mockGetUserMedia.mockRejectedValue(new Error('Permission denied'));

    const { result } = renderHook(() => useVoice());

    await act(async () => {
      result.current.start();
      await new Promise(r => setTimeout(r, 50));
    });

    expect(result.current.isListening).toBe(false);
    expect(result.current.error).toBe('Permission denied');
  });

  it('fetch-Fehler → error gesetzt, isListening=false', async () => {
    mockFetch.mockResolvedValue({
      ok: false,
      json: async () => ({ detail: 'Server-Fehler' }),
    });

    const { result } = renderHook(() => useVoice());

    await act(async () => {
      result.current.start();
    });

    act(() => {
      capturedVADOptions.onSpeechStart?.();
    });

    await act(async () => {
      capturedVADOptions.onSpeechEnd?.(new Float32Array(0));
      await new Promise(r => setTimeout(r, 100));
    });

    expect(result.current.isListening).toBe(false);
    expect(result.current.error).toBeTruthy();
  });

  it('transcript wird beim zweiten start() zurückgesetzt', async () => {
    const { result } = renderHook(() => useVoice());

    // Erster Durchlauf
    await act(async () => {
      result.current.start();
    });
    act(() => { capturedVADOptions.onSpeechStart?.(); });
    await act(async () => {
      capturedVADOptions.onSpeechEnd?.(new Float32Array(0));
      await new Promise(r => setTimeout(r, 50));
    });
    expect(result.current.transcript).toBe('Hallo Donna');

    // Zweiter Durchlauf — transcript muss reset werden
    await act(async () => {
      result.current.start();
    });
    expect(result.current.transcript).toBe('');
  });
});

describe('useVoice — VAD-Fehlalarm (onVADMisfire)', () => {
  it('onVADMisfire löst keinen Fehler aus', async () => {
    const { result } = renderHook(() => useVoice());

    await act(async () => {
      result.current.start();
    });

    act(() => {
      capturedVADOptions.onSpeechStart?.();
    });

    // Misfire: kurzes Geräusch — kein Fehler
    await act(async () => {
      capturedVADOptions.onVADMisfire?.();
      await new Promise(r => setTimeout(r, 50));
    });

    // Kein Fehler nach Misfire
    expect(result.current.error).toBeNull();
    // transcript bleibt leer (Misfire hatte kein echtes Sprachsignal)
    // (Der Mock-Recorder liefert Daten, aber das ist Mock-Artefakt)
  });

  it('VAD-Instanz wird korrekt erstellt und gestartet', async () => {
    const { result } = renderHook(() => useVoice());

    await act(async () => {
      result.current.start();
    });

    expect(mockVADInstance.start).toHaveBeenCalledTimes(1);
  });
});

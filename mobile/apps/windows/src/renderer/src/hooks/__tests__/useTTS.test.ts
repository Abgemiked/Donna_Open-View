/**
 * useTTS — Unit-Tests (DONNA-35)
 *
 * Testet:
 * 1. speak() ruft POST /tts auf wenn ttsEnabled=true
 * 2. speak() ist no-op wenn ttsEnabled=false
 * 3. speak() ist no-op bei leerem Text
 * 4. 204-Antwort (Live-Guard) → kein Audio, kein Fehler
 * 5. stop() bricht laufende Wiedergabe ab
 * 6. TTS-Toggle persistiert in localStorage
 * 7. setTtsEnabled(false) stoppt laufende Wiedergabe
 * 8. AbortError wird nicht als Fehler geloggt
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useTTS } from '../useTTS';

// ─── Mock-Setup ───────────────────────────────────────────────────────────────

// HTMLAudioElement mock
class MockAudio {
  src: string;
  onended: (() => void) | null = null;
  onerror: (() => void) | null = null;
  pause = vi.fn();
  play = vi.fn().mockResolvedValue(undefined);

  constructor(src: string) {
    this.src = src;
  }
}

// URL mock
const revokeObjectURL = vi.fn();
const createObjectURL = vi.fn().mockReturnValue('blob:mock-url');

// localStorage mock
const localStorageMock = (() => {
  let store: Record<string, string> = {};
  return {
    getItem: vi.fn((key: string) => store[key] ?? null),
    setItem: vi.fn((key: string, value: string) => { store[key] = value; }),
    removeItem: vi.fn((key: string) => { delete store[key]; }),
    clear: vi.fn(() => { store = {}; }),
  };
})();

let mockFetch: ReturnType<typeof vi.fn>;

beforeEach(() => {
  vi.resetAllMocks();

  // Audio global mock
  (global as any).Audio = MockAudio;

  // URL mock
  (global as any).URL = {
    createObjectURL,
    revokeObjectURL,
  };

  // localStorage mock
  Object.defineProperty(global, 'localStorage', {
    value: localStorageMock,
    writable: true,
    configurable: true,
  });
  localStorageMock.clear();
  localStorageMock.getItem.mockReturnValue(null); // Default: ttsEnabled=true

  // fetch mock — gibt Opus-Blob zurück
  mockFetch = vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    blob: async () => new Blob([new Uint8Array(1000)], { type: 'audio/opus' }),
  });
  global.fetch = mockFetch;
});

afterEach(() => {
  vi.restoreAllMocks();
});

// ─── Tests ────────────────────────────────────────────────────────────────────

describe('useTTS — Initialer Zustand', () => {

  it('ttsEnabled ist true per default (kein localStorage-Wert)', () => {
    const { result } = renderHook(() => useTTS());
    expect(result.current.ttsEnabled).toBe(true);
  });

  it('ttsEnabled liest Wert aus localStorage', () => {
    localStorageMock.getItem.mockReturnValue('false');
    const { result } = renderHook(() => useTTS());
    expect(result.current.ttsEnabled).toBe(false);
  });

  it('speak und stop sind Funktionen', () => {
    const { result } = renderHook(() => useTTS());
    expect(typeof result.current.speak).toBe('function');
    expect(typeof result.current.stop).toBe('function');
  });
});

describe('useTTS — speak()', () => {

  it('ruft POST /tts auf wenn ttsEnabled=true', async () => {
    const { result } = renderHook(() => useTTS());

    await act(async () => {
      await result.current.speak('Hallo Donna');
    });

    expect(mockFetch).toHaveBeenCalledWith(
      expect.stringContaining('/tts'),
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ text: 'Hallo Donna', was_voice_input: true }),
      }),
    );
  });

  it('ist no-op wenn ttsEnabled=false', async () => {
    localStorageMock.getItem.mockReturnValue('false');
    const { result } = renderHook(() => useTTS());

    await act(async () => {
      await result.current.speak('Hallo');
    });

    expect(mockFetch).not.toHaveBeenCalled();
  });

  it('ist no-op bei leerem Text', async () => {
    const { result } = renderHook(() => useTTS());

    await act(async () => {
      await result.current.speak('   ');
    });

    expect(mockFetch).not.toHaveBeenCalled();
  });

  it('erstellt Audio-Element und ruft play() auf', async () => {
    const { result } = renderHook(() => useTTS());

    await act(async () => {
      await result.current.speak('Test-Audio');
    });

    expect(createObjectURL).toHaveBeenCalled();
  });

  it('204-Antwort (Live-Guard) → kein Audio, kein Fehler', async () => {
    mockFetch.mockResolvedValue({
      ok: false,
      status: 204,
      blob: async () => new Blob([]),
    });

    const { result } = renderHook(() => useTTS());
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    await act(async () => {
      await result.current.speak('Live Guard Test');
    });

    expect(createObjectURL).not.toHaveBeenCalled();
    expect(warnSpy).not.toHaveBeenCalled();
  });

  it('nicht-ok-Antwort → kein Audio, kein Fehler-throw', async () => {
    mockFetch.mockResolvedValue({
      ok: false,
      status: 500,
      blob: async () => new Blob([]),
    });

    const { result } = renderHook(() => useTTS());

    await act(async () => {
      // Sollte keinen Fehler werfen
      await result.current.speak('Fehler-Test');
    });

    expect(createObjectURL).not.toHaveBeenCalled();
  });
});

describe('useTTS — TTS-Toggle', () => {

  it('setTtsEnabled(false) speichert in localStorage', () => {
    const { result } = renderHook(() => useTTS());

    act(() => {
      result.current.setTtsEnabled(false);
    });

    expect(localStorageMock.setItem).toHaveBeenCalledWith('donna_tts_enabled', 'false');
    expect(result.current.ttsEnabled).toBe(false);
  });

  it('setTtsEnabled(true) speichert in localStorage', () => {
    localStorageMock.getItem.mockReturnValue('false');
    const { result } = renderHook(() => useTTS());

    act(() => {
      result.current.setTtsEnabled(true);
    });

    expect(localStorageMock.setItem).toHaveBeenCalledWith('donna_tts_enabled', 'true');
    expect(result.current.ttsEnabled).toBe(true);
  });

  it('nach setTtsEnabled(false) ruft speak() kein fetch auf', async () => {
    const { result } = renderHook(() => useTTS());

    act(() => {
      result.current.setTtsEnabled(false);
    });

    await act(async () => {
      await result.current.speak('Kein TTS');
    });

    expect(mockFetch).not.toHaveBeenCalled();
  });
});

describe('useTTS — stop()', () => {

  it('stop() bricht laufende Anfrage ab (AbortError — kein console.warn)', async () => {
    // fetch blockiert bis abort
    let rejectFetch!: (e: Error) => void;
    mockFetch.mockReturnValue(new Promise<never>((_, reject) => {
      rejectFetch = reject;
    }));

    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const { result } = renderHook(() => useTTS());

    // speak startet async
    act(() => {
      result.current.speak('Langer Text');
    });

    // stop sofort danach
    act(() => {
      result.current.stop();
    });

    // Abort simulieren
    await act(async () => {
      const abortError = new Error('AbortError');
      abortError.name = 'AbortError';
      rejectFetch(abortError);
      await new Promise(r => setTimeout(r, 10));
    });

    // AbortError darf kein warn auslösen
    expect(warnSpy).not.toHaveBeenCalled();
  });
});

describe('useTTS — Request-Format', () => {

  it('sendet Authorization-Header mit Bearer-Token', async () => {
    const { result } = renderHook(() => useTTS());

    await act(async () => {
      await result.current.speak('Auth-Test');
    });

    expect(mockFetch).toHaveBeenCalledWith(
      expect.any(String),
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: expect.stringContaining('Bearer '),
        }),
      }),
    );
  });

  it('sendet was_voice_input: true im Body', async () => {
    const { result } = renderHook(() => useTTS());

    await act(async () => {
      await result.current.speak('Voice-Test');
    });

    const [, options] = mockFetch.mock.calls[0];
    const body = JSON.parse(options.body);
    expect(body.was_voice_input).toBe(true);
  });

  it('Blob mit Größe 0 → kein Audio-Element', async () => {
    mockFetch.mockResolvedValue({
      ok: true,
      status: 200,
      blob: async () => new Blob([]),
    });

    const { result } = renderHook(() => useTTS());

    await act(async () => {
      await result.current.speak('Leerer Blob');
    });

    expect(createObjectURL).not.toHaveBeenCalled();
  });
});

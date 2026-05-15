import {renderHook, act} from '@testing-library/react-hooks';
import {useStreamingChat} from '../hooks/useStreamingChat';
import * as api from '../api';

jest.mock('../api', () => ({
  streamChat: jest.fn(),
}));

const mockStreamChat = api.streamChat as jest.MockedFunction<typeof api.streamChat>;

beforeEach(() => {
  jest.clearAllMocks();
});

describe('useStreamingChat', () => {
  it('starts with empty state', () => {
    const {result} = renderHook(() => useStreamingChat());
    expect(result.current.messages).toEqual([]);
    expect(result.current.isStreaming).toBe(false);
    expect(result.current.streamingContent).toBe('');
  });

  it('adds user message immediately on sendMessage', async () => {
    mockStreamChat.mockImplementation(async (_, onChunk) => {
      onChunk({type: 'done'});
    });

    const {result} = renderHook(() => useStreamingChat());
    await act(async () => {
      await result.current.sendMessage('Hallo');
    });

    expect(result.current.messages[0]).toEqual({role: 'user', content: 'Hallo'});
  });

  it('accumulates streaming content and adds as assistant message on done', async () => {
    mockStreamChat.mockImplementation(async (_, onChunk) => {
      onChunk({type: 'delta', content: 'Hal'});
      onChunk({type: 'delta', content: 'lo!'});
      onChunk({type: 'done'});
    });

    const {result} = renderHook(() => useStreamingChat());
    await act(async () => {
      await result.current.sendMessage('Test');
    });

    const assistantMsg = result.current.messages.find(m => m.role === 'assistant');
    expect(assistantMsg?.content).toBe('Hallo!');
    expect(result.current.streamingContent).toBe('');
    expect(result.current.isStreaming).toBe(false);
  });

  it('shows error message on error chunk', async () => {
    mockStreamChat.mockImplementation(async (_, onChunk) => {
      onChunk({type: 'error', error: 'Backend nicht erreichbar'});
    });

    const {result} = renderHook(() => useStreamingChat());
    await act(async () => {
      await result.current.sendMessage('Test');
    });

    const errorMsg = result.current.messages.find(m => m.role === 'assistant');
    expect(errorMsg?.content).toContain('Backend nicht erreichbar');
    expect(result.current.isStreaming).toBe(false);
  });

  it('shows fallback error message on network error (non-AbortError)', async () => {
    mockStreamChat.mockImplementation(async () => {
      throw new Error('Network failure');
    });

    const {result} = renderHook(() => useStreamingChat());
    await act(async () => {
      await result.current.sendMessage('Test');
    });

    const errorMsg = result.current.messages.find(m => m.role === 'assistant');
    expect(errorMsg?.content).toContain('Verbindungsfehler');
    expect(result.current.isStreaming).toBe(false);
  });

  it('does not add error message on AbortError (user cancelled)', async () => {
    mockStreamChat.mockImplementation(async () => {
      const err = new Error('Aborted');
      err.name = 'AbortError';
      throw err;
    });

    const {result} = renderHook(() => useStreamingChat());
    await act(async () => {
      await result.current.sendMessage('Test');
    });

    // Nur die user-Message, keine assistant-Fehlermeldung
    expect(result.current.messages.filter(m => m.role === 'assistant')).toHaveLength(0);
    expect(result.current.isStreaming).toBe(false);
  });

  it('cancelStream aborts and resets streaming state', async () => {
    let resolveStream!: () => void;
    mockStreamChat.mockImplementation(
      () => new Promise<void>(resolve => { resolveStream = resolve; }),
    );

    const {result} = renderHook(() => useStreamingChat());

    // Stream starten (nicht awaiten — bleibt hängen bis cancelStream)
    act(() => {
      void result.current.sendMessage('Test');
    });

    // cancelStream aufrufen
    act(() => {
      result.current.cancelStream();
    });

    expect(result.current.isStreaming).toBe(false);
    expect(result.current.streamingContent).toBe('');

    // Stream auflösen damit keine offenen Promises verbleiben
    act(() => { resolveStream(); });
  });

  it('prevents double-submit while streaming (isStreamingRef guard)', async () => {
    let resolveFirst!: () => void;
    mockStreamChat.mockImplementationOnce(
      () => new Promise<void>(resolve => { resolveFirst = resolve; }),
    );

    const {result} = renderHook(() => useStreamingChat());

    // Erster Submit — hängt
    act(() => { void result.current.sendMessage('Erste Nachricht'); });
    // Zweiter Submit sofort danach — soll ignoriert werden
    act(() => { void result.current.sendMessage('Zweite Nachricht'); });

    // Nur 1 streamChat-Aufruf
    expect(mockStreamChat).toHaveBeenCalledTimes(1);

    act(() => { resolveFirst(); });
  });

  it('clearMessages resets all state', async () => {
    mockStreamChat.mockImplementation(async (_, onChunk) => {
      onChunk({type: 'delta', content: 'Test'});
      onChunk({type: 'done'});
    });

    const {result} = renderHook(() => useStreamingChat());
    await act(async () => {
      await result.current.sendMessage('Hallo');
    });
    act(() => {
      result.current.clearMessages();
    });

    expect(result.current.messages).toEqual([]);
    expect(result.current.streamingContent).toBe('');
  });
});

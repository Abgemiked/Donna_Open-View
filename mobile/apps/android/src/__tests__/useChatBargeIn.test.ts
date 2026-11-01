/**
 * Barge-in im geteilten Chat-Hook (useChat): eine neue Frage ersetzt den laufenden Stream,
 * „Stopp“ bricht ab, ohne Fehlermeldung und ohne Doppel-Request.
 */
import {renderHook, act} from '@testing-library/react-native';

const mockStreamChat = jest.fn();
jest.mock('../../../../packages/shared/src/api', () => ({
  streamChat: (...args: unknown[]) => mockStreamChat(...args),
}));
jest.mock('@react-native-async-storage/async-storage', () => ({
  __esModule: true,
  default: {
    getItem: jest.fn().mockResolvedValue('s-test'),
    setItem: jest.fn().mockResolvedValue(undefined),
  },
}));

// eslint-disable-next-line import/first
import {useChat} from '../../../../packages/shared/src/hooks/useChat';

type OnChunk = (c: {type: string; content?: string}) => void;

/** Ein Stream, den der Test von aussen steuert (Chunks senden, beenden); bricht bei abort() mit AbortError ab. */
function controllableStream() {
  let onChunk: OnChunk = () => {};
  let finish: () => void = () => {};
  let signal: AbortSignal | undefined;
  const promise = new Promise<void>((resolve, reject) => {
    finish = resolve;
    mockStreamChat.mockImplementationOnce(async (_msg: string, cb: OnChunk, sig?: AbortSignal) => {
      onChunk = cb;
      signal = sig;
      sig?.addEventListener('abort', () => reject(Object.assign(new Error('Aborted'), {name: 'AbortError'})));
      return promise;
    });
  });
  return {
    emit: (c: {type: string; content?: string}) => onChunk(c),
    finish: () => finish(),
    get aborted() { return signal?.aborted === true; },
  };
}

beforeEach(() => {
  mockStreamChat.mockReset();
  jest.spyOn(console, 'error').mockImplementation(() => {});
});
afterEach(() => jest.restoreAllMocks());

describe('useChat Barge-in', () => {
  it('uebergibt ein AbortSignal an den Stream', async () => {
    const s = controllableStream();
    const {result} = renderHook(() => useChat());
    let p: Promise<void>;
    act(() => { p = result.current.sendMessage('Frage'); });
    await act(async () => {});
    expect(mockStreamChat.mock.calls[0][2]).toBeInstanceOf(AbortSignal);
    s.emit({type: 'delta', content: 'Antwort.'});
    s.finish();
    await act(async () => { await p; });
    expect(result.current.messages.map(m => m.role)).toEqual(['user', 'assistant']);
  });

  it('neue Frage waehrend des Streams: alter Stream wird abgebrochen, nur die neue Antwort erscheint', async () => {
    const first = controllableStream();
    const {result} = renderHook(() => useChat());
    let p1: Promise<void>;
    act(() => { p1 = result.current.sendMessage('Frage eins'); });
    await act(async () => {});
    first.emit({type: 'delta', content: 'Halbe Antwort der ersten Frage'});

    const second = controllableStream();
    let p2: Promise<void>;
    act(() => { p2 = result.current.sendMessage('Frage zwei'); });
    await act(async () => {});
    expect(first.aborted).toBe(true);
    expect(mockStreamChat).toHaveBeenCalledTimes(2);

    second.emit({type: 'delta', content: 'Antwort zwei.'});
    second.finish();
    await act(async () => { await Promise.all([p1, p2]); });

    const contents = result.current.messages.map(m => m.content);
    expect(contents).toEqual(['Frage eins', 'Frage zwei', 'Antwort zwei.']);
    // kein Fehler-Text der abgebrochenen ersten Frage
    expect(contents.some(c => c.startsWith('Fehler'))).toBe(false);
    expect(result.current.isLoading).toBe(false);
  });

  it('Early-TTS der alten Frage feuert nach dem Abbruch nicht mehr', async () => {
    const first = controllableStream();
    const tts1 = jest.fn();
    const {result} = renderHook(() => useChat());
    let p1: Promise<void>;
    act(() => { p1 = result.current.sendMessage('Frage eins', undefined, 'android', tts1); });
    await act(async () => {});
    const second = controllableStream();
    act(() => { void result.current.sendMessage('Frage zwei'); });
    await act(async () => {});
    // verspaetetes Delta der abgebrochenen Frage
    first.emit({type: 'delta', content: 'Das ist ein langer erster Satz der alten Antwort. Und mehr.'});
    first.finish();
    second.finish();
    await act(async () => { await p1; });
    await act(async () => {});
    expect(tts1).not.toHaveBeenCalled();
  });

  it('cancel() (Stopp): bricht ab, behaelt den bis dahin empfangenen Text, keine Fehlermeldung', async () => {
    const s = controllableStream();
    const {result} = renderHook(() => useChat());
    let p: Promise<void>;
    act(() => { p = result.current.sendMessage('Erzaehl was'); });
    await act(async () => {});
    s.emit({type: 'delta', content: 'Es war einmal'});
    act(() => { result.current.cancel(); });
    await act(async () => { await p; });
    expect(s.aborted).toBe(true);
    expect(result.current.messages.map(m => m.content)).toEqual(['Erzaehl was', 'Es war einmal']);
    expect(result.current.isLoading).toBe(false);
  });

  it('cancel() ohne bisherigen Text: keine leere Antwort, kein Fehler', async () => {
    const s = controllableStream();
    const {result} = renderHook(() => useChat());
    let p: Promise<void>;
    act(() => { p = result.current.sendMessage('Hallo'); });
    await act(async () => {});
    act(() => { result.current.cancel(); });
    await act(async () => { await p; });
    expect(s.aborted).toBe(true);
    expect(result.current.messages).toEqual([{role: 'user', content: 'Hallo'}]);
  });

  it('cancel() ohne laufende Anfrage ist wirkungslos', () => {
    const {result} = renderHook(() => useChat());
    expect(() => act(() => { result.current.cancel(); })).not.toThrow();
    expect(result.current.messages).toEqual([]);
  });

  it('echte Fehler werden weiterhin angezeigt', async () => {
    mockStreamChat.mockRejectedValueOnce(new Error('HTTP 500'));
    const {result} = renderHook(() => useChat());
    await act(async () => { await result.current.sendMessage('Hallo'); });
    expect(result.current.messages[1].content).toBe('Fehler: HTTP 500');
  });
});

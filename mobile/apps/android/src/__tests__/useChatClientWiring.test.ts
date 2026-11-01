/**
 * Wiring-Test (R6 SOLL 2): useChat.sendMessage muss client:'android' UND input_mode:'text'
 * TATSÄCHLICH an streamChat übergeben — und zwar an den RICHTIGEN Positionsargumenten.
 *
 * Warum nicht via tsc abgedeckt: client und input_mode sind beide `string` an benachbarten
 * Positionen von streamChat(message, onChunk, signal, location, sessionId, client, inputMode).
 * Ein vertauschtes Argument (z.B. inputMode an die client-Position) wäre typkorrekt, würde aber
 * das Backend-Trust-Signal zerstören (Overlay/Chat still untrusted unter der Positiv-Allowlist).
 * cards_inputmode.test.ts ruft streamChat direkt auf und deckt die useChat-Verdrahtung NICHT ab.
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

// Positionsvertrag von streamChat (shared/src/api/index.ts):
//   0 message | 1 onChunk | 2 signal | 3 location | 4 sessionId | 5 client | 6 inputMode
const CLIENT_ARG = 5;
const INPUT_MODE_ARG = 6;

beforeEach(() => {
  mockStreamChat.mockReset();
  jest.spyOn(console, 'error').mockImplementation(() => {});
});
afterEach(() => jest.restoreAllMocks());

describe('useChat → streamChat Wiring (Live-Guard-Trust)', () => {
  it('übergibt client "android" an Position 5 und input_mode "text" an Position 6', async () => {
    mockStreamChat.mockResolvedValueOnce(undefined);
    const {result} = renderHook(() => useChat());

    // ChatScreen ruft sendMessage mit client='android' auf; sendMessage setzt input_mode intern auf 'text'.
    await act(async () => {
      await result.current.sendMessage('mach das licht an', undefined, 'android');
    });

    expect(mockStreamChat).toHaveBeenCalledTimes(1);
    const args = mockStreamChat.mock.calls[0];
    expect(args[CLIENT_ARG]).toBe('android');
    expect(args[INPUT_MODE_ARG]).toBe('text');
    // Gegenprobe gegen Vertauschung: die beiden Werte dürfen nicht gleich sein und nicht getauscht.
    expect(args[CLIENT_ARG]).not.toBe('text');
    expect(args[INPUT_MODE_ARG]).not.toBe('android');
  });

  it('input_mode bleibt "text" auch ohne explizites client-Argument (Default-Pfad)', async () => {
    mockStreamChat.mockResolvedValueOnce(undefined);
    const {result} = renderHook(() => useChat());

    await act(async () => {
      await result.current.sendMessage('hallo');
    });

    const args = mockStreamChat.mock.calls[0];
    expect(args[INPUT_MODE_ARG]).toBe('text');
    expect(args[CLIENT_ARG]).toBeUndefined(); // kein client übergeben → bleibt undefined (Backend: untrusted)
  });
});

import {
  getTtsSettings,
  saveTtsSettings,
  setApiToken,
  TTS_MODELS,
  TTS_MODEL_DEFAULT,
  TTS_VOICES,
  TTS_VOICE_DEFAULT,
} from '../../../../packages/shared/src/api';

/**
 * TB4 — HTTP-Ebene der TTS-Settings (direkt aus dem api-Modul, ohne den native-lastigen Barrel).
 * Verifiziert Endpoint, Methode, Body und Bearer-Header.
 */
const mockFetch = jest.fn();
(global as unknown as {fetch: jest.Mock}).fetch = mockFetch;

beforeEach(() => {
  mockFetch.mockReset();
  setApiToken('test-token');
});
afterEach(() => setApiToken(''));

describe('TTS-Settings-API (TB4)', () => {
  it('getTtsSettings: GET /settings/tts mit Bearer, liefert voice_id/model_id', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({voice_id: 'R24r0IV80Iw9eyByq1ps', model_id: 'eleven_v4'}),
    });
    expect(await getTtsSettings()).toEqual({voice_id: 'R24r0IV80Iw9eyByq1ps', model_id: 'eleven_v4'});
    const [url, opts] = mockFetch.mock.calls[0];
    expect(url).toContain('/settings/tts');
    expect((opts as {headers: Record<string, string>}).headers.Authorization).toBe('Bearer test-token');
  });

  it('getTtsSettings: null bei !ok oder unvollständiger Antwort', async () => {
    mockFetch.mockResolvedValueOnce({ok: false, json: async () => ({})});
    expect(await getTtsSettings()).toBeNull();
    mockFetch.mockResolvedValueOnce({ok: true, json: async () => ({voice_id: 'x'})}); // model_id fehlt
    expect(await getTtsSettings()).toBeNull();
  });

  it('getTtsSettings: null bei Netzfehler', async () => {
    mockFetch.mockRejectedValueOnce(new Error('net'));
    expect(await getTtsSettings()).toBeNull();
  });

  it('saveTtsSettings: POST mit Body {voice_id, model_id} + Bearer + Content-Type', async () => {
    mockFetch.mockResolvedValueOnce({ok: true, json: async () => ({})});
    expect(await saveTtsSettings('3EKymYQPdS1Q9fxZBuq8', 'eleven_multilingual_v2')).toBe(true);
    const [url, opts] = mockFetch.mock.calls[0] as [string, Record<string, unknown>];
    expect(url).toContain('/settings/tts');
    expect(opts.method).toBe('POST');
    expect(JSON.parse(opts.body as string)).toEqual({
      voice_id: '3EKymYQPdS1Q9fxZBuq8',
      model_id: 'eleven_multilingual_v2',
    });
    const headers = opts.headers as Record<string, string>;
    expect(headers.Authorization).toBe('Bearer test-token');
    expect(headers['Content-Type']).toBe('application/json');
  });

  it('saveTtsSettings: false bei !ok / Netzfehler', async () => {
    mockFetch.mockResolvedValueOnce({ok: false});
    expect(await saveTtsSettings('a', 'b')).toBe(false);
    mockFetch.mockRejectedValueOnce(new Error('net'));
    expect(await saveTtsSettings('a', 'b')).toBe(false);
  });

  it('Defaults sind Eva + v4 Turbo', () => {
    expect(TTS_VOICE_DEFAULT).toBe('R24r0IV80Iw9eyByq1ps');
    expect(TTS_MODEL_DEFAULT).toBe('eleven_v4_turbo');
  });

  it('TTS_VOICES/TTS_MODELS enthalten exakt die Backend-Allowlist-IDs (keine Drift)', () => {
    expect(TTS_VOICES.map(v => v.id)).toEqual(['R24r0IV80Iw9eyByq1ps', '3EKymYQPdS1Q9fxZBuq8']);
    expect(TTS_MODELS.map(m => m.id)).toEqual([
      'eleven_v4_turbo',
      'eleven_v4',
      'eleven_multilingual_v2',
    ]);
  });
});

import {checkHealth, getVoiceAuthChallenge, verifyVoiceAuth, streamChat, sendChatMessage, DonnaApiError} from '../api';

// fetch mocken
const mockFetch = jest.fn();
global.fetch = mockFetch;

beforeEach(() => {
  mockFetch.mockReset();
});

describe('checkHealth', () => {
  it('returns true when backend is healthy', async () => {
    mockFetch.mockResolvedValueOnce({ok: true});
    expect(await checkHealth()).toBe(true);
  });

  it('returns false when backend unreachable', async () => {
    mockFetch.mockRejectedValueOnce(new Error('Network error'));
    expect(await checkHealth()).toBe(false);
  });
});

describe('getVoiceAuthChallenge', () => {
  it('returns challenge on success', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({challenge_id: 'abc-123', phrase: 'Sag mir etwas'}),
    });
    const result = await getVoiceAuthChallenge();
    expect(result.challenge_id).toBe('abc-123');
    expect(result.phrase).toBe('Sag mir etwas');
  });

  it('throws DonnaApiError on 429', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 429,
      json: async () => ({detail: 'rate_limited', retry_after: 60}),
    });
    await expect(getVoiceAuthChallenge()).rejects.toThrow(DonnaApiError);
  });
});

describe('verifyVoiceAuth', () => {
  const validReq = {
    challenge_id: 'abc-123',
    nonce: 'unique-nonce',
    timestamp: Date.now() / 1000,
    audio_hash: 'a'.repeat(64),
  };

  it('returns ok on successful verify', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({status: 'ok'}),
    });
    const result = await verifyVoiceAuth(validReq);
    expect(result.status).toBe('ok');
  });

  it('throws DonnaApiError with reason on 401', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 401,
      json: async () => ({reason: 'nonce_replay', message: 'Nonce bereits verwendet.'}),
    });
    const err = await verifyVoiceAuth(validReq).catch(e => e) as DonnaApiError;
    expect(err).toBeInstanceOf(DonnaApiError);
    expect(err.status).toBe(401);
    expect(err.reason).toBe('nonce_replay');
  });
});

describe('streamChat', () => {
  it('calls onChunk with delta and done for JSON response', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      headers: {get: () => 'application/json'},
      json: async () => ({response: 'Hallo!'}),
    });

    const chunks: Array<{type: string; content?: string}> = [];
    await streamChat('Hallo', (c) => chunks.push(c));

    expect(chunks).toEqual([
      {type: 'delta', content: 'Hallo!'},
      {type: 'done'},
    ]);
  });

  it('calls onChunk with error on non-ok response', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 500,
      json: async () => ({detail: 'Internal Server Error'}),
    });

    const chunks: Array<{type: string}> = [];
    await streamChat('test', (c) => chunks.push(c));
    expect(chunks[0].type).toBe('error');
  });
});

describe('sendChatMessage', () => {
  it('aggregates streaming chunks to single string', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      headers: {get: () => 'application/json'},
      json: async () => ({response: 'Antwort von Donna'}),
    });
    const result = await sendChatMessage('Frage');
    expect(result).toBe('Antwort von Donna');
  });
});

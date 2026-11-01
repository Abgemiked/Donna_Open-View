// DONNA — Tests für input_mode im Request-Body (Teil A2) + Kalender-Karten-Parsing (Teil B).
// Stil gespiegelt von api.test.ts (fetch gemockt).
import {streamChat} from '../api';

const mockFetch = jest.fn();
global.fetch = mockFetch as unknown as typeof fetch;

beforeEach(() => {
  mockFetch.mockReset();
});

describe('streamChat — input_mode + client im Body (Live-Guard-Trust-Signal)', () => {
  it('schreibt input_mode und client in den Request-Body', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      headers: {get: () => 'application/json'},
      text: async () => '{"response":"ok"}',
      json: async () => ({response: 'ok'}),
    });

    await streamChat(
      'mach das licht an',
      () => {},
      undefined,      // signal
      undefined,      // location
      's1',           // sessionId
      'android',      // client
      'text',         // inputMode
    );

    const [, init] = mockFetch.mock.calls[0];
    const body = JSON.parse((init as {body: string}).body);
    expect(body.client).toBe('android');
    expect(body.input_mode).toBe('text');
  });

  it('lässt input_mode weg, wenn nicht gesetzt (kein leeres Feld)', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      headers: {get: () => 'application/json'},
      text: async () => '{"response":"ok"}',
      json: async () => ({response: 'ok'}),
    });

    await streamChat('hallo', () => {});

    const [, init] = mockFetch.mock.calls[0];
    const body = JSON.parse((init as {body: string}).body);
    expect('input_mode' in body).toBe(false);
  });
});

describe('streamChat — card_type "calendar" Parsing', () => {
  it('liefert einen card-Chunk mit card_type=calendar und den Events', async () => {
    const payload = {
      type: 'card',
      card_type: 'calendar',
      data: {events: [{title: 'Zahnarzt', when_label: 'Heute', time: '09:30'}]},
    };
    const sse =
      `data: ${JSON.stringify(payload)}\n` +
      'data: {"type":"done"}\n';

    mockFetch.mockResolvedValueOnce({
      ok: true,
      headers: {get: () => 'text/event-stream'},
      body: null,                 // RN-Fallback-Pfad (kein ReadableStream)
      text: async () => sse,
    });

    const chunks: Array<{type: string; card?: {card_type: string; data: unknown}}> = [];
    await streamChat('was steht heute an?', (c) => chunks.push(c as never));

    const cardChunk = chunks.find((c) => c.type === 'card');
    expect(cardChunk).toBeDefined();
    expect(cardChunk?.card?.card_type).toBe('calendar');
    expect((cardChunk?.card?.data as {events: unknown[]}).events).toHaveLength(1);
  });
});

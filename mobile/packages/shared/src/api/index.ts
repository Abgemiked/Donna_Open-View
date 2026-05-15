// Donna API Client
// Streaming SSE + Voice-Auth Flow — DONNA-12
import type {
  ChatMessage,
  ChatLocation,
  ChatCard,
  WeatherCardData,
  MapCardData,
  VoiceAuthChallenge,
  VoiceAuthVerifyRequest,
  VoiceAuthVerifyResponse,
  StreamChunk,
  DonnaAction,
} from './types';
export type {
  ChatMessage,
  ChatLocation,
  ChatCard,
  WeatherCardData,
  MapCardData,
  VoiceAuthChallenge,
  VoiceAuthVerifyRequest,
  VoiceAuthVerifyResponse,
  StreamChunk,
  DonnaAction,
};
export type { SessionInfo, SessionMessage };
export { DonnaApiError } from './types';

const API_BASE_URL = 'https://your-donna-instance.example.com';

// DONNA-103: Token dynamisch — kein Hardcode mehr.
// Android: aus TokenStore via TTSModule / direkte native Nutzung.
// Windows: wird beim App-Start via IPC vom Main-Prozess gesetzt (onApiToken).
// React Native: wird via setApiToken() gesetzt (z.B. nach TokenStore-Lesen beim Start).
let _apiToken: string | null = null;

/** Setzt den API-Token (wird vom Main-Prozess / App-Start aufgerufen). */
export function setApiToken(token: string): void {
  _apiToken = token;
}

/** Gibt den aktuellen API-Token zurück oder null wenn nicht gesetzt. */
export function getApiToken(): string | null {
  return _apiToken;
}

const AUTH_HEADERS = (): Record<string, string> => {
  const token = _apiToken;
  return token ? {'Authorization': `Bearer ${token}`} : {};
};

// ── Health ──────────────────────────────────────────────────────────────────

export async function checkHealth(): Promise<boolean> {
  try {
    const res = await fetch(`${API_BASE_URL}/health`, {signal: AbortSignal.timeout(5000)});
    return res.ok;
  } catch {
    return false;
  }
}

// ── Voice Auth ───────────────────────────────────────────────────────────────

export async function getVoiceAuthChallenge(): Promise<VoiceAuthChallenge> {
  const res = await fetch(`${API_BASE_URL}/voice-auth/challenge`);
  if (!res.ok) {
    const body = await res.json().catch(() => ({})) as Record<string, unknown>;
    throw new DonnaApiError({
      status: res.status,
      reason: String(body['detail'] ?? 'unknown'),
      message: `Challenge request failed: ${res.status}`,
      retry_after: typeof body['retry_after'] === 'number' ? body['retry_after'] : undefined,
    });
  }
  return res.json() as Promise<VoiceAuthChallenge>;
}

export async function verifyVoiceAuth(
  req: VoiceAuthVerifyRequest,
): Promise<VoiceAuthVerifyResponse> {
  const res = await fetch(`${API_BASE_URL}/voice-auth/verify`, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({})) as Record<string, unknown>;
    throw new DonnaApiError({
      status: res.status,
      reason: String(body['reason'] ?? 'unknown'),
      message: String(body['message'] ?? `Verify failed: ${res.status}`),
      retry_after: typeof body['retry_after'] === 'number' ? body['retry_after'] : undefined,
    });
  }
  return res.json() as Promise<VoiceAuthVerifyResponse>;
}

// ── Chat — Streaming SSE ─────────────────────────────────────────────────────

/**
 * streamChat — sendet eine Nachricht an das Backend und liefert Streaming-Chunks via Callback.
 *
 * Unterstützt:
 * 1. SSE-Streaming (text/event-stream) — delta-Chunks werden einzeln geliefert
 * 2. JSON-Fallback (application/json) — gesamte Response als einziger Chunk
 *
 * @param message   - User-Nachricht
 * @param onChunk   - Callback für jeden empfangenen Chunk
 * @param signal    - AbortSignal zum Abbrechen des Streams
 */
export async function streamChat(
  message: string,
  onChunk: (chunk: StreamChunk) => void,
  signal?: AbortSignal,
  location?: ChatLocation,
  sessionId?: string,
  client?: string,
): Promise<void> {
  // onChunk-Exceptions dürfen den Streaming-Loop nicht abbrechen — safeOnChunk isoliert jeden Aufruf
  const safeOnChunk = (chunk: StreamChunk) => {
    try { onChunk(chunk); } catch (e) { console.warn('[streamChat] onChunk threw:', e); }
  };
  const body: Record<string, unknown> = {message};
  if (location) {
    body.lat = location.lat;
    body.lon = location.lon;
  }
  if (sessionId) {
    body.session_id = sessionId;
  }
  if (client) {
    body.client = client;
  }
  // Eigenen Timeout-Controller anlegen falls kein externer Signal übergeben wurde.
  // Gemini 429-Retry-Backoff kann 140–420s dauern → 600s Timeout.
  let effectiveSignal = signal;
  let cleanupTimeout: (() => void) | undefined;
  if (!signal) {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 600_000);
    effectiveSignal = controller.signal;
    cleanupTimeout = () => clearTimeout(timeoutId);
  }
  const res = await fetch(`${API_BASE_URL}/chat`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'text/event-stream, application/json',
      ...AUTH_HEADERS(),
    },
    body: JSON.stringify(body),
    signal: effectiveSignal,
  });

  if (!res.ok) {
    const body = await res.json().catch(() => ({})) as Record<string, unknown>;
    safeOnChunk({
      type: 'error',
      error: String(body['detail'] ?? `HTTP ${res.status}`),
    });
    return;
  }

  const contentType = res.headers.get('content-type') ?? '';
  // SSE-DEBUG: Zeigt ob res.body verfügbar ist (null = Hermes-Fallback aktiv)
  console.debug('[SSE-DEBUG] res.body:', res.body != null, 'contentType:', contentType);

  // ── SSE-Streaming-Pfad (nur wenn ReadableStream verfügbar — nicht in allen RN-Versionen) ──
  if (contentType.includes('text/event-stream') && res.body != null) {
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    try {
      while (true) {
        const {done, value} = await reader.read();

        if (!done) {
          buffer += decoder.decode(value, {stream: true});
        } else {
          // Stream beendet — decoder flushen und verbleibenden Buffer verarbeiten
          buffer += decoder.decode();
        }

        const lines = buffer.split('\n');
        // Bei done: alle Zeilen verarbeiten (kein Rest mehr übrig lassen)
        buffer = done ? '' : (lines.pop() ?? '');

        for (const line of lines) {
          if (line.startsWith('data: ')) {
            const data = line.slice(6).trim();
            if (data === '[DONE]') {
              safeOnChunk({type: 'done'});
              return;
            }
            try {
              // DONNA-Welle1 Task 5: 'action'-Events durchreichen
              const parsed = JSON.parse(data) as {type?: string; content?: string; delta?: string; card_type?: string; data?: unknown; action?: unknown};
              if (parsed.type === 'card' && parsed.card_type) {
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                safeOnChunk({type: 'card', card: {card_type: parsed.card_type as 'weather' | 'map', data: parsed.data as any}});
              } else if (parsed.type === 'action' && parsed.action) {
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                safeOnChunk({type: 'action', action: parsed.action as any});
              } else {
                const text = parsed.content ?? parsed.delta ?? '';
                if (text) { safeOnChunk({type: 'delta', content: text}); }
              }
            } catch {
              // Ungültiges JSON — ignorieren
            }
          }
        }

        if (done) break;
      }
    } finally {
      reader.releaseLock();
      cleanupTimeout?.();
    }
    safeOnChunk({type: 'done'});
    return;
  }

  // ── Text/JSON-Fallback (React Native: res.body oft null, kein Streaming) ──
  try {
  const rawText = await res.text();

  // Versuche JSON zu parsen (falls Backend {response: "..."} liefert)
  if (contentType.includes('application/json')) {
    try {
      const json = JSON.parse(rawText) as {response?: string; content?: string};
      const content = json.response ?? json.content ?? rawText;
      safeOnChunk({type: 'delta', content});
      safeOnChunk({type: 'done'});
      return;
    } catch {
      // kein valides JSON → als Text behandeln
    }
  }

  // Plain Text oder SSE-Body komplett als String (Hermes-Fallback)
  if (rawText) {
    // SSE-Zeilen parsen falls vorhanden
    if (rawText.includes('data: ')) {
      for (const line of rawText.split('\n')) {
        if (line.startsWith('data: ')) {
          const data = line.slice(6).trim();
          // Legacy-[DONE]-Marker (falls doch mal gesendet)
          if (data === '[DONE]') break;
          try {
            const parsed = JSON.parse(data) as {type?: string; content?: string; delta?: string; card_type?: string; data?: unknown; action?: unknown};
            console.debug('[SSE-DEBUG] parsed event type:', parsed?.type);
            // Backend sendet {"type":"done"} als Stream-Ende (nicht "[DONE]")
            if (parsed.type === 'done') break;
            if (parsed.type === 'card' && parsed.card_type) {
              // eslint-disable-next-line @typescript-eslint/no-explicit-any
              safeOnChunk({type: 'card', card: {card_type: parsed.card_type as 'weather' | 'map', data: parsed.data as any}});
            } else if (parsed.type === 'action' && parsed.action) {
              // eslint-disable-next-line @typescript-eslint/no-explicit-any
              safeOnChunk({type: 'action', action: parsed.action as any});
            } else {
              const text = parsed.content ?? parsed.delta ?? '';
              if (text) { safeOnChunk({type: 'delta', content: text}); }
            }
          } catch {
            // Zeile ist kein JSON (z.B. keep-alive oder debug-output) → ignorieren
          }
        }
      }
    } else {
      // Reiner Plaintext (Backend liefert direkt Text ohne SSE-Format)
      safeOnChunk({type: 'delta', content: rawText});
    }
  }
  safeOnChunk({type: 'done'});
  } finally {
    cleanupTimeout?.();
  }
}

/**
 * sendChatMessage — einfacher (nicht-streaming) Chat-Aufruf.
 * Aggregiert alle Streaming-Chunks zu einer vollständigen Antwort.
 * Rückwärtskompatibel mit dem ursprünglichen useChat-Hook.
 */
export async function sendChatMessage(
  message: string,
  _history: ChatMessage[] = [],
  location?: ChatLocation,
): Promise<string> {
  let result = '';
  await streamChat(message, (chunk) => {
    if (chunk.type === 'delta' && chunk.content) {
      result += chunk.content;
    }
  }, undefined, location);
  return result;
}

// ─── STM Session API ──────────────────────────────────────────────────────────

export interface SessionInfo {
  session_id: string;
  started_at: number;   // Unix timestamp (float)
  message_count: number;
  preview: string;
}

export interface SessionMessage {
  role: 'user' | 'assistant';
  content: string;
  // DONNA-19: Optionale Rich-Content-Felder — Backend (STM) speichert sie derzeit
  // nicht, daher im Normalfall undefined. Der Type erlaubt es aber, damit ein
  // künftiges Backend-Update diese Felder ohne Client-Änderung durchreichen kann.
  card?: import('./types').ChatCard;
  actions?: import('./types').DonnaAction[];
  ideaConfirm?: import('./types').IdeaConfirmPayload;
  ideaUpdate?: import('./types').IdeaUpdatePayload;
}

/** Alle Sessions der letzten 24h, neueste zuerst. */
export async function fetchSessions(): Promise<SessionInfo[]> {
  try {
    const res = await fetch(`${API_BASE_URL}/stm/sessions`, {
      headers: AUTH_HEADERS(),
    });
    if (!res.ok) return [];
    return (await res.json()) as SessionInfo[];
  } catch {
    return [];
  }
}

/** Sendet 👍/👎 Feedback auf eine Donna-Antwort.
 *
 * DONNA-139: Erweitert um message_id, message_text, user_message für LTM-Integration.
 * Der Backend-Endpoint schreibt bei jedem Feedback eine mem0-Memory — Donna lernt
 * was Mike als hilfreich empfindet.
 */
export async function sendFeedback(
  sessionId: string,
  rating: 'positive' | 'negative',
  snippet?: string,
  messageId?: string,
  messageText?: string,
  userMessage?: string,
  category?: string,
): Promise<boolean> {
  try {
    const res = await fetch(`${API_BASE_URL}/feedback`, {
      method: 'POST',
      headers: {...AUTH_HEADERS(), 'Content-Type': 'application/json'},
      body: JSON.stringify({
        session_id: sessionId,
        rating,
        snippet,
        message_id: messageId,
        message_text: messageText,
        user_message: userMessage,
        category,
      }),
    });
    return res.ok;
  } catch {
    return false;
  }
}

/** Nachrichten einer Session (max. 50) — ohne TTL-Filter für Verlauf-Ansicht. */
export async function fetchSessionMessages(sessionId: string): Promise<SessionMessage[]> {
  try {
    const res = await fetch(`${API_BASE_URL}/stm/${encodeURIComponent(sessionId)}?limit=50&history=true`, {
      headers: AUTH_HEADERS(),
    });
    if (!res.ok) return [];
    const data = await res.json() as { messages?: SessionMessage[] };
    return data.messages ?? [];
  } catch {
    return [];
  }
}

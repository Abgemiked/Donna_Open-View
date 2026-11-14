// useNtfyNotifications — ntfy WebSocket Hook für lokale Android-Notifications
// DONNA-13: Empfängt Push-Nachrichten via ntfy WebSocket, zeigt System-Notifications
// via @notifee/react-native — keine separate ntfy-App nötig.
import {useEffect, useRef} from 'react';
import {AppState} from 'react-native';
import notifee, {AndroidStyle} from '@notifee/react-native';
import {shouldDisplayInJs} from './ntfyPushPolicy';

export interface UseNtfyNotificationsOptions {
  /** WebSocket-URL zum ntfy-Topic, z.B. 'wss://your-donna-instance.example.com/donna/ws' */
  wsUrl: string;
  /** Notifee Android Channel-ID (muss vorher via createChannel erstellt worden sein) */
  channelId: string;
  /** Aktiviert/Deaktiviert den Hook (default: true) */
  enabled?: boolean;
}

/** ntfy JSON-Nachrichtenformat */
interface NtfyMessage {
  id?: string;
  time?: number;
  event: string; // 'open' | 'keepalive' | 'message'
  topic?: string;
  title?: string;
  message?: string;
  // ntfy nutzt auch 'body' in manchen Varianten — beide Felder abdecken
  body?: string;
  // ntfy-Tags; die proaktive Push trägt die Session-ID hier als `sid=<id>` (Top-Level verwirft ntfy);
  // `donna_persistent` → wird nativ angezeigt, nicht hier (#51).
  tags?: string[];
}

const BASE_RECONNECT_DELAY_MS = 1000;
const MAX_RECONNECT_DELAY_MS = 30000;
/** ntfy sendet ~alle 45 s ein keepalive; länger still (+Puffer) ⇒ Socket gilt als tot (Doze). */
const STALE_SOCKET_MS = 75_000;
/** WebSocket.OPEN (readyState) — als Konstante, damit das Fake-WS im Test keinen Static braucht. */
const WS_OPEN = 1;

/**
 * Liest die Session-ID aus dem ntfy-Tag `sid=<id>` (z.B. `sid=proactive_morning`). Der Wert wird 1:1
 * als session_id genutzt (kein chan=/cat=-Mapping). Nur URL-sichere Werte (die ID geht in GET /stm/{id});
 * sonst undefined → die App startet eine neue Session (nicht schlechter als vorher).
 */
const SID_TAG_PREFIX = 'sid=';
const SAFE_SESSION_ID = /^[A-Za-z0-9_-]{1,128}$/;
function sessionIdFromTags(tags?: string[]): string | undefined {
  if (!Array.isArray(tags)) {
    return undefined;
  }
  for (const raw of tags) {
    if (typeof raw !== 'string') {
      continue;
    }
    const t = raw.trim();
    if (t.toLowerCase().startsWith(SID_TAG_PREFIX)) {
      const v = t.slice(SID_TAG_PREFIX.length).trim();
      if (SAFE_SESSION_ID.test(v)) {
        return v;
      }
    }
  }
  return undefined;
}

export function useNtfyNotifications({
  wsUrl,
  channelId,
  enabled = true,
}: UseNtfyNotificationsOptions): void {
  const wsRef = useRef<WebSocket | null>(null);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reconnectDelayRef = useRef<number>(BASE_RECONNECT_DELAY_MS);
  const mountedRef = useRef<boolean>(true);
  const lastActivityRef = useRef<number>(Date.now());

  useEffect(() => {
    mountedRef.current = true;

    if (!enabled) {
      return;
    }

    function connect(): void {
      if (!mountedRef.current) {
        return;
      }

      console.log('[useNtfyNotifications] Verbinde zu:', wsUrl);
      const ws = new WebSocket(wsUrl);
      wsRef.current = ws;

      ws.onopen = () => {
        if (!mountedRef.current) {
          ws.close();
          return;
        }
        console.log('[useNtfyNotifications] WebSocket verbunden');
        lastActivityRef.current = Date.now();
        // Erfolgreiche Verbindung → Backoff zurücksetzen
        reconnectDelayRef.current = BASE_RECONNECT_DELAY_MS;
      };

      ws.onmessage = async (event: WebSocketMessageEvent) => {
        if (!mountedRef.current) {
          return;
        }
        lastActivityRef.current = Date.now(); // auch keepalive/open zählen als Lebenszeichen

        let parsed: NtfyMessage;
        try {
          // eslint-disable-next-line @typescript-eslint/no-unsafe-argument
          const raw = typeof event.data === 'string' ? event.data : JSON.stringify(event.data);
          parsed = JSON.parse(raw) as NtfyMessage;
        } catch {
          console.warn('[useNtfyNotifications] Ungültiges JSON:', event.data);
          return;
        }

        // Nur event: "message" verarbeiten — "open" und "keepalive" ignorieren
        if (parsed.event !== 'message') {
          return;
        }

        // #51 Mutual Exclusion: Der JS-Hook zeigt NUR im Vordergrund (AppState 'active') und nur für
        // nicht-persistente Pushes. Im Hintergrund / bei App-Kill übernimmt der native NtfyService
        // (Komplementärbedingung: AppForegroundState.isMainResumed) → keine Doppelzustellung.
        if (!shouldDisplayInJs(AppState.currentState, parsed.tags)) {
          return;
        }

        const title = parsed.title ?? 'Donna';
        // ntfy nutzt "message", manche Varianten "body"
        const body = parsed.message ?? parsed.body ?? '';

        if (!body) {
          console.warn('[useNtfyNotifications] Nachricht ohne Body empfangen:', parsed);
          return;
        }

        try {
          await notifee.displayNotification({
            title,
            body,
            // BUG-1 Fix: source-Tag damit JS-Handler den Notification-Tap identifizieren kann
            data: {
              source: 'ntfy',
              // Session-ID aus dem `sid=`-Tag (vorher fälschlich die ntfy-Message-ID parsed.id).
              session_id: sessionIdFromTags(parsed.tags) ?? '',
            },
            android: {
              channelId,
              // #51: aufklappbar — ohne BigText bleibt der lange Text (Morgen-Briefing) abgeschnitten.
              style: {type: AndroidStyle.BIGTEXT, text: body},
              pressAction: {
                id: 'default',
                launchActivity: 'default',
              },
            },
          });
        } catch (err) {
          console.error('[useNtfyNotifications] Notification fehlgeschlagen:', err);
        }
      };

      ws.onerror = (error: WebSocketErrorEvent) => {
        console.warn('[useNtfyNotifications] WebSocket Fehler:', error.message);
      };

      ws.onclose = (event: WebSocketCloseEvent) => {
        if (!mountedRef.current) {
          return;
        }
        console.log(
          `[useNtfyNotifications] WebSocket geschlossen (Code: ${event.code ?? 0}), Reconnect in ${reconnectDelayRef.current}ms`,
        );
        scheduleReconnect();
      };
    }

    function scheduleReconnect(): void {
      if (!mountedRef.current) {
        return;
      }
      reconnectTimerRef.current = setTimeout(() => {
        if (!mountedRef.current) {
          return;
        }
        connect();
        // Exponentieller Backoff, max 30s
        reconnectDelayRef.current = Math.min(
          reconnectDelayRef.current * 2,
          MAX_RECONNECT_DELAY_MS,
        );
      }, reconnectDelayRef.current);
    }

    // #51 (Review R1): Nach Doze/Bildschirm-aus kann der Socket still gestorben sein. Im Vordergrund unterdrückt
    // der NtfyService die native Push — ein toter Socket würde die erste Push nach dem Entsperren verschlucken
    // (ntfy hat über den WS-Proxy kein Replay). Daher beim Wechsel auf 'active' sofort neu verbinden, wenn der
    // Socket nicht OPEN ist oder länger als STALE_SOCKET_MS nichts (auch kein keepalive) kam.
    function reconnectNow(): void {
      if (!mountedRef.current) {
        return;
      }
      if (reconnectTimerRef.current !== null) {
        clearTimeout(reconnectTimerRef.current);
        reconnectTimerRef.current = null;
      }
      const old = wsRef.current;
      if (old !== null) {
        // Handler lösen, damit das Schließen kein zweites (Backoff-)Reconnect auslöst.
        old.onopen = null;
        old.onmessage = null;
        old.onerror = null;
        old.onclose = null;
        try {
          old.close();
        } catch {
          // schon geschlossen
        }
        wsRef.current = null;
      }
      reconnectDelayRef.current = BASE_RECONNECT_DELAY_MS;
      connect();
    }

    const appStateSub = AppState.addEventListener('change', (next) => {
      if (next !== 'active' || !mountedRef.current) {
        return;
      }
      const ws = wsRef.current;
      const stale = Date.now() - lastActivityRef.current > STALE_SOCKET_MS;
      if (ws === null || ws.readyState !== WS_OPEN || stale) {
        reconnectNow();
      }
    });

    connect();

    return () => {
      appStateSub.remove();
      mountedRef.current = false;
      if (reconnectTimerRef.current !== null) {
        clearTimeout(reconnectTimerRef.current);
        reconnectTimerRef.current = null;
      }
      if (wsRef.current !== null) {
        wsRef.current.close();
        wsRef.current = null;
      }
      console.log('[useNtfyNotifications] Hook unmounted, WebSocket geschlossen');
    };
  }, [wsUrl, channelId, enabled]);
}

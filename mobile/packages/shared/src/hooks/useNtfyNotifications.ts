// useNtfyNotifications — ntfy WebSocket Hook für lokale Android-Notifications
// DONNA-13: Empfängt Push-Nachrichten via ntfy WebSocket, zeigt System-Notifications
// via @notifee/react-native — keine separate ntfy-App nötig.
import {useEffect, useRef} from 'react';
import notifee from '@notifee/react-native';

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
}

const BASE_RECONNECT_DELAY_MS = 1000;
const MAX_RECONNECT_DELAY_MS = 30000;

export function useNtfyNotifications({
  wsUrl,
  channelId,
  enabled = true,
}: UseNtfyNotificationsOptions): void {
  const wsRef = useRef<WebSocket | null>(null);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reconnectDelayRef = useRef<number>(BASE_RECONNECT_DELAY_MS);
  const mountedRef = useRef<boolean>(true);

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
        // Erfolgreiche Verbindung → Backoff zurücksetzen
        reconnectDelayRef.current = BASE_RECONNECT_DELAY_MS;
      };

      ws.onmessage = async (event: WebSocketMessageEvent) => {
        if (!mountedRef.current) {
          return;
        }

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
              session_id: parsed.id ?? '',
            },
            android: {
              channelId,
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

    connect();

    return () => {
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

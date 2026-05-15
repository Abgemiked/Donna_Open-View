import React, {useCallback, useEffect, useRef} from 'react';
import {AppState, DeviceEventEmitter, NativeModules, Platform, StatusBar, StyleSheet} from 'react-native';
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';
import notifee, {AndroidImportance, EventType} from '@notifee/react-native';
import ChatScreen from './screens/ChatScreen';
import {useSideButton} from './hooks/useSideButton';
import {useNtfyNotifications, setApiToken} from '@donna/shared';

// DONNA-103: API-Token beim Start aus nativem TokenStore in JS-Schicht laden
async function loadApiToken(): Promise<void> {
  try {
    const token: string | null = await NativeModules.TTSModule?.getApiToken();
    if (token) {
      setApiToken(token);
      console.log('[App] API-Token geladen');
    } else {
      console.warn('[App] Kein API-Token im TokenStore — Pairing ausstehend');
    }
  } catch (e) {
    console.error('[App] Token-Laden fehlgeschlagen:', e);
  }
}

// DONNA-13: Notification Channel einmalig beim App-Start anlegen
async function setupNotificationChannel(): Promise<void> {
  await notifee.createChannel({
    id: 'donna-alerts',
    name: 'Donna Benachrichtigungen',
    importance: AndroidImportance.HIGH,
  });
}

// Globaler Ref damit Side-Button-Handler den Voice-Start auslösen kann
export const voiceTriggerRef = React.createRef<() => void>();

// BUG-1 Fix: Globaler Ref für Notification-Tap-Handler (ChatScreen registriert sich hier)
export const notificationTapRef = React.createRef<(sessionId?: string) => void>();

// DONNA-135: Globaler Ref für Proaktiven-Chat-Handler (ChatScreen registriert sich hier)
// DONNA-198: Handler erhält nun {message, session_id} statt plain string
export const proactiveChatRef = React.createRef<(payload: ProactiveChatPayload) => void>();

// DONNA-198: Payload-Typ für proaktiven Chat (message + optionale session_id vom Backend)
export interface ProactiveChatPayload {
  message: string;
  session_id?: string;
}

// DONNA-147: Pending-Nachricht für Cold-Start-Race-Condition:
// Event kann ankommen bevor ChatScreen gemountet + proactiveChatRef gesetzt ist.
// ChatScreen prüft diese Variable beim Mounten und handled sie sofort.
export let pendingProactiveMessage: ProactiveChatPayload | null = null;
export function clearPendingProactiveMessage(): void {
  pendingProactiveMessage = null;
}

// BUG-1 Fix: Hilfsfunktion — wird auch beim App-Start aus getInitialNotification aufgerufen
function handleNtfyNotificationTap(sessionId?: string): void {
  console.log('[App] Notification-Tap: ntfy, session_id=', sessionId ?? '(leer)');
  notificationTapRef.current?.(sessionId);
}

function App(): React.JSX.Element {
  const channelSetupRef = useRef(false);

  // DONNA-198 v7: SharedPreferences ist IMMER autoritär. Beide Pfade (DeviceEventEmitter
  // + AppState-Resume) rufen consumeProactiveFromPrefs(). getAndClear() ist atomar —
  // ein zweiter Aufruf gibt null zurück → früher Return, kein Doppel-Trigger, kein Flag nötig.
  const consumeProactiveFromPrefs = useCallback(async () => {
    if (Platform.OS !== 'android' || !NativeModules.ProactiveMessageModule) return;
    try {
      const raw: string | null = await NativeModules.ProactiveMessageModule.getAndClear();
      if (!raw) return; // SharedPreferences leer → nichts zu tun, atomar sicher
      console.log('[App] consumeProactiveFromPrefs: payload=', raw.slice(0, 80));
      let payload: ProactiveChatPayload;
      try {
        const parsed = JSON.parse(raw);
        payload = {message: parsed.message ?? raw, session_id: parsed.session_id};
      } catch {
        payload = {message: raw};
      }
      if (proactiveChatRef.current) {
        console.log('[App] consumeProactiveFromPrefs: ref bereit → direkt aufrufen');
        proactiveChatRef.current(payload);
      } else {
        // ChatScreen noch nicht gemountet (Cold Start) — pending speichern
        pendingProactiveMessage = payload;
        console.log('[App] consumeProactiveFromPrefs: ref null, pending gespeichert payload.message=', payload.message?.slice(0, 60));
      }
    } catch (e) {
      // ignore — native Modul ggf. nicht verfügbar
    }
  }, []);

  useEffect(() => {
    if (!channelSetupRef.current) {
      channelSetupRef.current = true;
      // Token zuerst laden, dann Notification-Channel (Reihenfolge egal, aber sauber)
      loadApiToken();
      setupNotificationChannel().catch(err =>
        console.error('[App] Notification Channel Setup fehlgeschlagen:', err),
      );
    }

    // BUG-1 Fix: App war geschlossen → wurde durch Notification-Tap geöffnet
    notifee.getInitialNotification().then(initial => {
      if (initial?.notification?.data?.source === 'ntfy') {
        const sessionId = initial.notification.data.session_id as string | undefined;
        handleNtfyNotificationTap(sessionId);
      }
    }).catch(err =>
      console.error('[App] getInitialNotification fehlgeschlagen:', err),
    );

    // BUG-1 Fix: App war im Vordergrund/Background → Notification-Tap im Foreground-Event
    const unsubscribeForeground = notifee.onForegroundEvent(({type, detail}) => {
      if (
        type === EventType.PRESS &&
        detail.notification?.data?.source === 'ntfy'
      ) {
        const sessionId = detail.notification.data.session_id as string | undefined;
        handleNtfyNotificationTap(sessionId);
      }
    });

    // DONNA-135/147/198 v7: DeviceEventEmitter-Pfad ruft consumeProactiveFromPrefs().
    // NtfyService hat den Payload bereits in SharedPreferences geschrieben. getAndClear()
    // liest+löscht atomar → ein zweiter Aufruf (z.B. AppState-Resume 250ms später)
    // gibt null zurück und kehrt früh zurück. Kein Flag, kein Race.
    const proactiveSub = DeviceEventEmitter.addListener(
      'donna_open_proactive_chat',
      () => {
        console.log('[App] donna_open_proactive_chat empfangen → consumeProactiveFromPrefs');
        void consumeProactiveFromPrefs();
      },
    );

    // Cold-Start: sofort beim Mount SharedPreferences abfragen.
    // Falls die App durch Notification-Tap geöffnet wurde, ist der Payload bereits dort.
    void consumeProactiveFromPrefs();

    // Background-Resume: bei jedem Wechsel nach 'active' erneut prüfen.
    // Szenario: User tippt Notification während App im Hintergrund → onNewIntent feuert,
    // AppState wechselt auf 'active'. Kein Flag-Reset nötig — getAndClear() ist idempotent.
    const appStateSub = AppState.addEventListener('change', (next) => {
      if (next === 'active') {
        // Kleine Verzögerung: onNewIntent → emit läuft async, SharedPreferences
        // sollte aber bereits geschrieben sein (NtfyService schreibt vor Notification-Show).
        setTimeout(() => void consumeProactiveFromPrefs(), 250);
      }
    });

    return () => {
      unsubscribeForeground();
      proactiveSub.remove();
      appStateSub.remove();
    };
  }, [consumeProactiveFromPrefs]);

  // DONNA-13: ntfy WebSocket Notifications
  useNtfyNotifications({
    wsUrl: 'wss://your-donna-instance.example.com/donna/ws',
    channelId: 'donna-alerts',
  });

  const handleSidePress = useCallback(() => {
    // Single Press: App ist bereits im Vordergrund — kein weiterer Effekt nötig
  }, []);

  const handleSideDoublePress = useCallback(() => {
    // Double Press → Voice-Input starten (Ref auf ChatScreen-Funktion)
    voiceTriggerRef.current?.();
  }, []);

  useSideButton({
    onPress: handleSidePress,
    onDoublePress: handleSideDoublePress,
    enabled: true,
  });

  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.container} edges={['top']}>
        <StatusBar barStyle="light-content" backgroundColor="#6200ee" />
        <ChatScreen />
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#6200ee',
  },
});

export default App;

import React, {useCallback, useEffect, useRef, useState} from 'react';
import {ActivityIndicator, AppState, DeviceEventEmitter, NativeModules, Platform, StatusBar, StyleSheet, View} from 'react-native';
import {SafeAreaProvider} from 'react-native-safe-area-context';
import notifee, {AndroidImportance, EventType} from '@notifee/react-native';
import ChatScreen from './screens/ChatScreen';
import OnboardingScreen from './screens/OnboardingScreen';
import {hasMissingRequired, shouldShowOnboarding} from './utils/onboardingGate';
import {useSideButton} from './hooks/useSideButton';
import {useNtfyNotifications, setApiToken} from '@donna/shared';
import {ThemeProvider, useTheme} from './theme';
import {ErrorBoundary} from './components/ErrorBoundary';

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
// Notification-Tap-Kontext: title = Überschrift der angetippten Push, nonce = Doppel-Seed-Schutz.
export interface ProactiveChatPayload {
  message: string;
  session_id?: string;
  title?: string;
  nonce?: string;
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

// Ersteinrichtung: 'loading' = Gate-Prüfung läuft, 'onboarding' = Pflicht-Gate vor dem Chat, 'chat' = normal
type AppRoute = 'loading' | 'onboarding' | 'chat';

function AppContent(): React.JSX.Element {
  const theme = useTheme();
  const channelSetupRef = useRef(false);
  const [route, setRoute] = useState<AppRoute>('loading');
  // "Einrichtung" aus dem Chat: Wizard als Overlay, ChatScreen (und sein State) bleibt gemountet
  const [setupOverlayOpen, setSetupOverlayOpen] = useState(false);

  // Pflicht-Gate: Wizard vor dem Chat, solange onboarding_done fehlt oder ein Pflicht-Schritt offen ist
  useEffect(() => {
    let cancelled = false;
    void shouldShowOnboarding().then(show => {
      if (!cancelled) {
        setRoute(show ? 'onboarding' : 'chat');
      }
    });
    return () => {
      cancelled = true;
    };
  }, []);

  // Warn-Punkt am ⚙️: Pflicht-Recht fehlt (z. B. nach Reinstall oder später verschobener Einrichtung)
  const [setupWarning, setSetupWarning] = useState(false);
  useEffect(() => {
    if (route !== 'chat' || setupOverlayOpen) {
      return undefined;
    }
    let cancelled = false;
    const check = () => {
      void hasMissingRequired().then(missing => {
        if (!cancelled) {
          setSetupWarning(missing);
        }
      });
    };
    check();
    const sub = AppState.addEventListener('change', next => {
      if (next === 'active') {
        check();
      }
    });
    return () => {
      cancelled = true;
      sub.remove();
    };
  }, [route, setupOverlayOpen]);

  const handleOnboardingFinished = useCallback(() => setRoute('chat'), []);
  const openSetupOverlay = useCallback(() => setSetupOverlayOpen(true), []);
  const closeSetupOverlay = useCallback(() => setSetupOverlayOpen(false), []);

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
        payload = {message: parsed.message ?? raw, session_id: parsed.session_id, title: parsed.title, nonce: parsed.nonce};
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

    // BUG-1 Fix / DONNA-228: App war geschlossen → wurde durch Notification-Tap geöffnet.
    // NtfyService baut Notifications via NotificationCompat.Builder (kein notifee),
    // daher ist data IMMER leer. Wir prüfen NICHT mehr auf data.source === 'ntfy' —
    // stattdessen vertrauen wir consumeProactiveFromPrefs() als primären Pfad.
    // getInitialNotification() ist hier nur noch als Fallback für notifee-eigene
    // Notifications (falls zukünftig genutzt) — kein direkter Handler mehr.
    notifee.getInitialNotification().then(initial => {
      if (initial?.notification?.data) {
        // Notifee-native Notification (z.B. lokale Benachrichtigungen) — Payload auslesen
        const sessionId = initial.notification.data.session_id as string | undefined;
        const msg = initial.notification.data.message as string | undefined;
        if (msg) {
          handleNtfyNotificationTap(sessionId);
        }
        // Hauptpfad: consumeProactiveFromPrefs() läuft direkt unterhalb ohnehin
      }
    }).catch(err =>
      console.error('[App] getInitialNotification fehlgeschlagen:', err),
    );

    // BUG-1 Fix: App war im Vordergrund/Background → Notification-Tap im Foreground-Event
    // DONNA-228: source-Check entfernt — NtfyService-Notifications haben kein data.source
    const unsubscribeForeground = notifee.onForegroundEvent(({type, detail}) => {
      if (type === EventType.PRESS) {
        // Alle Notification-Taps → consumeProactiveFromPrefs() auslösen
        // (NtfyService schreibt Payload vorher in SharedPreferences)
        console.log('[App] notifee ForegroundEvent PRESS → consumeProactiveFromPrefs');
        void consumeProactiveFromPrefs();
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
    // DONNA-249: 3-stufiger Retry — 300ms / 800ms / 1800ms — robuster gegen Doze-Mode-Timing
    // auf Samsung One UI 7. SharedPreferences commit() von NtfyService ist synchron, aber
    // der AppState-'active'-Event kann vor dem Prozess-Resume des NtfyService ankommen.
    const appStateSub = AppState.addEventListener('change', (next) => {
      if (next === 'active') {
        setTimeout(() => void consumeProactiveFromPrefs(), 300);
        setTimeout(() => void consumeProactiveFromPrefs(), 800);
        setTimeout(() => void consumeProactiveFromPrefs(), 1800);
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

  const isChat = route === 'chat';

  // UX-01: Ein Layout-Container, EIN Top-Inset. Frueher: SafeAreaView(edges=['top']) UND Header-
  // paddingTop (insets.top + 8) => doppelter Abstand, dazu ein lila Streifen hinter der
  // Statusleiste. Jetzt zeichnet jeder Screen seinen Top-Inset selbst (ChatScreen-Header /
  // OnboardingScreen) und die Statusleiste ist transparent (edge-to-edge, Icons je nach Schema).
  return (
    <SafeAreaProvider>
      <View style={[styles.container, {backgroundColor: theme.bg}]}>
        <StatusBar
          barStyle={theme.scheme === 'dark' ? 'light-content' : 'dark-content'}
          backgroundColor="transparent"
          translucent
        />
        {route === 'loading' && (
          <View
            style={[styles.loading, {backgroundColor: theme.bg}]}
            accessibilityLabel="Donna wird gestartet">
            <ActivityIndicator color={theme.accent} size="large" />
          </View>
        )}
        {route === 'onboarding' && (
          <ErrorBoundary name="OnboardingScreen">
            <OnboardingScreen mode="initial" onFinish={handleOnboardingFinished} />
          </ErrorBoundary>
        )}
        {isChat && (
          <>
            {/* Hinter dem Setup-Overlay darf TalkBack den Chat nicht mehr erreichen */}
            <View
              style={styles.chatWrapper}
              importantForAccessibility={setupOverlayOpen ? 'no-hide-descendants' : 'auto'}>
              <ErrorBoundary name="ChatScreen">
                <ChatScreen onOpenSetup={openSetupOverlay} setupWarning={setupWarning} />
              </ErrorBoundary>
            </View>
            {setupOverlayOpen && (
              <View style={StyleSheet.absoluteFill}>
                <ErrorBoundary name="OnboardingScreen">
                  <OnboardingScreen mode="rerun" onFinish={closeSetupOverlay} />
                </ErrorBoundary>
              </View>
            )}
          </>
        )}
      </View>
    </SafeAreaProvider>
  );
}

function App(): React.JSX.Element {
  return (
    <ThemeProvider>
      <AppContent />
    </ThemeProvider>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  chatWrapper: {
    flex: 1,
  },
  loading: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
});

export default App;

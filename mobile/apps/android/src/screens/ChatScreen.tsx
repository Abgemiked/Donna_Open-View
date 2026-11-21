import React, {useState, useRef, useCallback, useEffect, useMemo} from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  FlatList,
  ScrollView,
  Modal,
  ActivityIndicator,
  StyleSheet,
  Keyboard,
  Animated,
  AppState,
  PermissionsAndroid,
  Easing,
  Linking,
  NativeModules,
  NativeEventEmitter,
  DeviceEventEmitter,
  ToastAndroid,
  Platform,
  Share,
} from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import {useSafeAreaInsets} from 'react-native-safe-area-context';
import {useChat, useVoice, fetchSessions, fetchSessionMessages, sendFeedback, fuzzyScore, getApiToken} from '@donna/shared';
import type {ChatMessage, WeatherCardData, MapCardData, CalendarCardData, SessionInfo, SessionMessage, DonnaAction, IdeaConfirmPayload, IdeaUpdatePayload} from '@donna/shared';
import {AndroidVoiceModule} from '../native/AndroidVoiceModule';
import {searchContactsByName, type ContactMatch} from '../native/ContactsBridge';
import {voiceTriggerRef, proactiveChatRef, pendingProactiveMessage, clearPendingProactiveMessage} from '../App';
import type {ProactiveChatPayload} from '../App';
import {IdeaConfirmCard, IdeaUpdateCard} from './IdeaConfirmCard';
import {OfflineSTT} from '../modules/OfflineSTT';
import {PhiModule} from '../modules/PhiModule';
import {routedGenerate, invalidateOnDeviceCache} from '../utils/llmRouter';
import {markLatency, measureLatency, clearLatencyMarks} from '../utils/latencyBenchmark';
import {isEchoOfSpoken, EchoStrikes, appendSpoken, shouldCountEchoStrike} from '../utils/echoGuard';
import {speakWithFallback} from '../utils/ttsChain';
import {TtsSequencer} from '../utils/ttsSequencer';
import {usePiperModel} from '../hooks/usePiperModel';
import {
  EDGE_VOICE_DEFAULT,
  TTS_ENGINE_DEFAULT,
  getEdgeVoice,
  getTtsEngine,
} from '../native/ttsEngine';
import type {TtsEngine} from '../native/ttsEngine';
import {getVoiceBargeIn, setVoiceBargeIn, subscribeVoiceBargeIn, VOICE_BARGE_IN_DEFAULT} from '../native/voiceBargeIn';
import {parseConfirmation} from '../utils/voiceConfirm';
import {triggerAutoSend} from '../utils/autoSend';
import {contactActionFields} from '../utils/actionSummary';
import {
  weatherKind, weatherTint, gradientStops, glyphPalette, isNightHour, hourFromLabel, currentHourIndex, averagePrecip,
} from '../utils/weatherLook';
import {WeatherGlyph, DropIcon, WindIcon} from '../components/WeatherGlyph';
import {CalendarIcon, PinIcon, ChevronRightIcon} from '../components/CardIcons';
import {useTheme, useThemedStyles, FONT, RADIUS, SPACE, TOUCH_MIN} from '../theme';
import {DonnaAvatar} from '../components/DonnaAvatar';
import {ChatOrb} from '../components/chat/ChatOrb';
import {Icon} from '../components/Icon';
import {ChatHeader} from '../components/ChatHeader';
import {ChatInputRow} from '../components/ChatInputRow';
import {mapOrbState} from '../theme/orbState';
import type {OrbDisplayState} from '../theme/orbState';
import type {Theme} from '../theme';
import {makeCardStyles, makePickerStyles, makeFallbackStyles, makeInfoCardStyles, INFO_CARD} from './cardStyles';
import {NavDisambiguateCard, NavNotFoundCard} from './NavCards';
import {
  NAV_DISAMBIGUATE,
  NAV_NOT_FOUND,
  TESLA_NAVIGATE,
  getNavTarget,
  isNavChoiceAction,
  navTypeOf,
  notFoundText,
  getOptionTarget,
  parseNavOptions,
  sendTargetToTesla,
  str,
} from '../utils/navActions';
import type {NavOption, NavType} from '../utils/navActions';
import {Waveform} from '../components/chat/Waveform';
import {ActionSheetState, MessageActionSheet} from '../components/chat/MessageActionSheet';
import {PendingActionCard, ContactPickerCard, ContactFallbackCard} from '../components/chat/ActionCards';
import {MessageBubble} from '../components/chat/MessageBubble';
import {TypingIndicator} from '../components/chat/TypingIndicator';
import {IdleActionBar} from '../components/chat/IdleActionBar';
import {ConversationView} from '../components/chat/ConversationView';
import {useHeldTrue, CONVERSATION_VIEW_GRACE_MS} from '../components/chat/useHeldTrue';
import {
  greetingForHour, IDLE_SUGGESTIONS, selectChatView, conversationTranscript, typingPriorityEnds, idleBarVisible,
  headerShowsConversationToggle,
} from '../components/chat/chatView';
import {useVoiceTurn} from '../components/chat/useVoiceTurn';
import {IdleScreen} from '../components/chat/IdleScreen';
import {ReplyPreview} from '../components/chat/ReplyPreview';
import {HistoryPanel} from '../components/chat/HistoryPanel';

const {AlarmModule, AccessibilityBridge} = NativeModules;

// ─── TTS-Konstante ────────────────────────────────────────────────────────────
const TTS_ENABLED_KEY = 'donna_tts_enabled';

// ─── Theme ────────────────────────────────────────────────────────────────────
// UX-01: Farben kommen aus der zentralen Theme-Quelle (src/theme): Dark/Light + System-/Material-You-
// Farben, Pulse-Cyan nur noch als Fallback. Stylesheets sind Factories `make...Styles(P)` und werden in den
// Komponenten ueber useThemedStyles() gebunden; `P` ist dort das aktuelle Theme (useTheme()).

// ─── Freisprech-Gesprächsmodus ────────────────────────────────────────────────
const CONVERSATION_MODE_KEY = '@donna_conversation_mode';
/** Einmaliger Erst-Hinweis, dass Unterbrechen per Stimme jetzt standardmäßig an ist. */
const VOICE_BARGE_IN_HINT_KEY = '@donna_voice_bargein_hint_shown';
/** Echo-Schutz: kurze Pause nach dem TTS-Ende, bevor das Mikrofon wieder aufgeht. */
export const ECHO_GUARD_MS = 350;
/** Automatische Relisten in Folge OHNE echte Sprache dazwischen (Echo-Schleife). */
export const MAX_AUTO_RELISTENS = 3;
/** Harte Obergrenze für Freisprech-Runden ohne Berührung (Sicherheitsnetz für das API-Budget). */
export const MAX_HANDS_FREE_TURNS = 12;
/** Voice-Barge-ins pro Berührung (Barge-in zählt zum Freisprech-Limit und setzt es nie zurück). */
export const MAX_BARGE_INS_PER_TOUCH = 3;
/** Selbst-Stopp des Stimm-Lauschens (Hard-Cap; das native Modul hat dasselbe Limit). */
export const BARGE_IN_MAX_MS = 90_000;
/** #53: Barge-in-Sitzungen, die rund um einen App-Zustandswechsel (minimiert/gesperrt/zurück) enden, zählen nie als Strike. */
export const LIFECYCLE_GRACE_MS = 2_500;
/**
 * Voice-Barge-in (Mikrofon offen WÄHREND Donna spricht) ist standardmäßig AN (Opt-out, Voice-Batch). Schutz gegen
 * Selbst-Auslösung/Lärm: AEC-Pflicht, Lernphase für Donnas Echo, Sprachband-VAD + Stimm-Ähnlichkeit + Umgebungsboden
 * (BargeInVad), Echo-Filter auf den erkannten Text und Strikes (2 Fehlauslöser -> aus). 90-s-Hard-Cap und Abbruch im
 * Hintergrund/gesperrt bleiben. Ausschalten: lange auf den Gesprächs-Schalter tippen (wird gemerkt).
 */
export {VOICE_BARGE_IN_DEFAULT}; // Definition: native/voiceBargeIn.ts (nur eine Stelle)
/** OfflineSTT-Watchdogs: ohne Partial nach 10 s abbrechen, nach „Fertig“ höchstens 9 s auf das Ergebnis warten. */
export const OFFLINE_NO_PARTIAL_MS = 10_000;
export const OFFLINE_MAX_LISTEN_MS = 50_000;
export const OFFLINE_RESULT_WATCHDOG_MS = 9_000;
/** On-Device-STT-Fehler, die KEINE Umschaltung auf Cloud-STT rechtfertigen (Stille, nichts erkannt, kurz belegt). */
const OFFLINE_SOFT_ERRORS = new Set([3, 6, 7, 8]);

// ─── Barge-in: Schätzung der Sprechdauer ──────────────────────────────────────
// Die nativen TTS-Module melden kein Wiedergabe-Ende. Die Schätzung steuert NUR die Sichtbarkeit der
// Stopp-Taste (zu lang = Taste bleibt etwas länger, ein Tipp ist dann harmlos).
export function estimateTtsMs(text: string): number {
  const chars = text?.length ?? 0;
  return Math.min(60_000, Math.max(2_500, chars * 75 + 1_200));
}

// ─── Permissions ──────────────────────────────────────────────────────────────
async function requestMicPermission(): Promise<boolean> {
  try {
    const granted = await PermissionsAndroid.request(
      PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
      {
        title: 'Mikrofon-Zugriff',
        message: 'Donna braucht das Mikrofon für die Spracheingabe.',
        buttonPositive: 'Erlauben',
        buttonNegative: 'Abbrechen',
      },
    );
    return granted === PermissionsAndroid.RESULTS.GRANTED;
  } catch {
    return false;
  }
}

// ─── History Action-Heuristik ─────────────────────────────────────────────────
// Port der _heuristic_actions()-Logik aus dem Backend (chat.py).
// Wird beim Laden historischer Sessions auf die letzte Assistenten-Nachricht
// angewandt, da das Backend DONNA_ACTION-Marker vor dem STM-Speichern strippt.
// Gibt die erste erkannte Action zurück oder null.
const _NAME_PART = '[A-Za-zÄÖÜäöüß][A-Za-zÄÖÜäöüß\\-]*(?:\\s+[A-Za-zÄÖÜäöüß][A-Za-zÄÖÜäöüß\\-]*){0,2}';
const _HISTORY_PATTERNS: Array<{type: string; re: RegExp; extract: (m: RegExpMatchArray) => DonnaAction | null}> = [
  {
    type: 'whatsapp',
    re: new RegExp(
      `whatsapp(?:[\\s-]+nachricht)?\\s+an\\s+['"\`]?(${_NAME_PART})['"\`]?\\s*(?:mit\\s+(?:der\\s+nachricht\\s+)?|:\\s*)['"\`]?([^—\\n]+?)['"\`]?\\s*(?:\\s+[—\\-]\\s+|[.\\n]|$)`,
      'i',
    ),
    extract: (m) => {
      const name = (m[1] ?? '').trim().replace(/[.,!?]$/, '');
      const message = (m[2] ?? '').trim().replace(/[.,!?]$/, '').replace(/^['"`]|['"`]$/g, '');
      const placeholders = new Set(['deinem text', 'der text', 'text', 'deiner nachricht', 'der nachricht', 'antippen zum senden', 'antippen']);
      if (!name || !message || placeholders.has(message.toLowerCase())) return null;
      return {type: 'whatsapp', name, message};
    },
  },
  {
    type: 'sms',
    re: new RegExp(
      `\\bsms\\s+an\\s+['"\`]?(${_NAME_PART})['"\`]?\\s*(?:mit\\s+(?:der\\s+nachricht\\s+)?|:\\s*)['"\`]?([^—\\n]+?)['"\`]?\\s*(?:\\s+[—\\-]\\s+|[.\\n]|$)`,
      'i',
    ),
    extract: (m) => {
      const name = (m[1] ?? '').trim().replace(/[.,!?]$/, '');
      const message = (m[2] ?? '').trim().replace(/[.,!?]$/, '').replace(/^['"`]|['"`]$/g, '');
      const placeholders = new Set(['deinem text', 'der text', 'text', 'deiner nachricht', 'der nachricht']);
      if (!name || !message || placeholders.has(message.toLowerCase())) return null;
      return {type: 'sms', name, message};
    },
  },
  {
    type: 'call',
    re: new RegExp(
      `\\banruf\\s+(?:an|zu)\\s+['"\`]?(${_NAME_PART})['"\`]?(?=\\s*(?:[—.\\n]|wird|geht|verbinden|$))`,
      'i',
    ),
    extract: (m) => {
      const name = (m[1] ?? '').trim().replace(/[.,!?]$/, '');
      if (!name) return null;
      return {type: 'call', name};
    },
  },
  {
    type: 'call',
    re: new RegExp(`\\brufe?\\s+(${_NAME_PART})\\s+an\\b`, 'i'),
    extract: (m) => {
      const name = (m[1] ?? '').trim().replace(/[.,!?]$/, '');
      if (!name) return null;
      return {type: 'call', name};
    },
  },
  {
    type: 'set_alarm',
    re: /(?:wecker|alarm)\s+(?:fuer|für|um|auf)\s+(\d{1,2})(?::(\d{2}))?\s*(?:uhr)?/i,
    extract: (m) => {
      const hh = parseInt(m[1] ?? '0', 10);
      const mm = parseInt(m[2] ?? '0', 10);
      if (hh < 0 || hh > 23 || mm < 0 || mm > 59) return null;
      return {type: 'set_alarm', time: `${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}`};
    },
  },
  {
    type: 'set_timer',
    re: /timer\s+(?:fuer|für|auf)?\s*(\d+)\s*(?:min|minuten)/i,
    extract: (m) => {
      const mins = parseInt(m[1] ?? '0', 10);
      if (mins < 1 || mins > 1440) return null;
      return {type: 'set_timer', minutes: mins};
    },
  },
  {
    type: 'navigate',
    re: /(?:navigation\s+(?:zu|nach)|navigiere\s+(?:zu|nach))\s+['"`]?([^'"`\n—]+?)['"`]?\s*(?:\s+[—\-]\s+|[.\n]|$)/i,
    extract: (m) => {
      const dest = (m[1] ?? '').trim().replace(/[.,!?]$/, '');
      if (!dest) return null;
      return {type: 'navigate', destination: dest};
    },
  },
];

function _detectActionFromText(text: string): DonnaAction | null {
  if (!text || text.length < 8) return null;
  for (const {re, extract} of _HISTORY_PATTERNS) {
    const match = text.match(re);
    if (match) {
      const action = extract(match);
      if (action) return action;
    }
  }
  return null;
}

// ─── ChatScreen (main) ────────────────────────────────────────────────────────
export interface ChatScreenProps {
  /** Öffnet den Einrichtungs-Assistenten erneut (z. B. nach einer APK-Neuinstallation). */
  onOpenSetup?: () => void;
  /** Warn-Punkt am Einrichtungs-Button, wenn ein Pflicht-Recht fehlt. */
  setupWarning?: boolean;
}

export default function ChatScreen({onOpenSetup, setupWarning = false}: ChatScreenProps = {}): React.JSX.Element {
  const P = useTheme();
  const styles = useThemedStyles(makeStyles);
  const [inputText, setInputText] = useState('');
  // #134 P4: "Tippen" gewaehlt (Leerzustand: Eingabeleiste mit Tastatur; Gespraechsansicht: zurueck in den Chat)
  const [typingOpen, setTypingOpen] = useState(false);
  const [replyTo, setReplyTo] = useState<string | null>(null);
  const [historyVisible, setHistoryVisible] = useState(false);
  const [actionSheet, setActionSheet] = useState<ActionSheetState>({
    visible: false, content: '', isAssistant: false,
  });
  const {messages, isLoading, rateLimitStatus, sendMessage, cancel: cancelChat, loadMessages, clearMessages, setLocation, sessionId, startProactiveChat} = useChat();

  // DONNA-115: Ideen-Karten
  const [pendingIdeaConfirm, setPendingIdeaConfirm] = useState<IdeaConfirmPayload | null>(null);
  const [pendingIdeaUpdate, setPendingIdeaUpdate] = useState<IdeaUpdatePayload | null>(null);

  // DONNA-Welle3: Pending Action — In-Chat-Bestätigungskarte
  const [pendingAction, setPendingAction] = useState<DonnaAction | null>(null);
  const [pendingContact, setPendingContact] = useState<ContactMatch | null>(null);
  const [contactCandidates, setContactCandidates] = useState<ContactMatch[]>([]);
  const [contactFallback, setContactFallback] = useState<{
    action: DonnaAction;
    originalName: string;
    suggestions: ContactMatch[];
  } | null>(null);
  // Navigations-Rückfrage des Backends (navigate_disambiguate / navigate_not_found) — eigene Karte
  const [navChoice, setNavChoice] = useState<DonnaAction | null>(null);
  const navOptions = useMemo(
    () => (navChoice != null && navChoice.type === NAV_DISAMBIGUATE ? parseNavOptions(navChoice) : []),
    [navChoice],
  );
  const pendingActionRef = useRef<DonnaAction | null>(null);
  const pendingContactRef = useRef<ContactMatch | null>(null);
  useEffect(() => { pendingActionRef.current = pendingAction; }, [pendingAction]);
  useEffect(() => { pendingContactRef.current = pendingContact; }, [pendingContact]);

  // Guard: History-Modal darf pendingAction NICHT löschen. Wenn historyVisible von
  // true -> false wechselt und der Ref noch eine Action hält, State wiederherstellen.
  // (RN-Android-Modal kann bei Unmount/Mount den Parent-State kurzzeitig resetten.)
  useEffect(() => {
    if (!historyVisible) {
      const saved = pendingActionRef.current;
      if (saved !== null) {
        // Micro-task: nach dem Modal-Unmount State sichern
        const t = setTimeout(() => {
          setPendingAction(prev => prev !== null ? prev : saved);
        }, 0);
        return () => clearTimeout(t);
      }
    }
    return undefined;
  }, [historyVisible]);

  // Welle-4: Contact-Lookup VOR Anzeige der Action-Karte. Bei call/sms/whatsapp
  // mit `name`-Feld werden die System-Kontakte durchsucht. Eindeutiger Treffer
  // -> Card mit Kontaktinfos. Mehrere Treffer -> ContactPicker. Kein Treffer
  // mit `number` da -> Warnhinweis in Card.
  const handleDonnaAction = useCallback(async (action: DonnaAction) => {
    // Geocoding-Rückfrage/-Fehler: Auswahl- bzw. Hinweiskarte statt Bestätigungskarte.
    if (isNavChoiceAction(action)) {
      setPendingAction(null);
      setPendingContact(null);
      setContactCandidates([]);
      setContactFallback(null);
      setNavChoice(action);
      return;
    }
    setNavChoice(null);
    const needsContact = action.type === 'call' || action.type === 'sms' || action.type === 'whatsapp';
    const name = (action.name as string | undefined)?.trim();
    const hasNumber = !!(action.number as string | undefined)?.trim();

    if (needsContact && name && !hasNumber) {
      const retryCount = (action as DonnaAction & {_retryCount?: number})._retryCount ?? 0;
      const matches = await searchContactsByName(name);
      if (matches.length === 1) {
        setPendingContact(matches[0]);
        setContactCandidates([]);
        setContactFallback(null);
        setPendingAction(action);
        return;
      }
      if (matches.length > 1) {
        const exact = matches.find(m => m.name.toLowerCase() === name.toLowerCase());
        if (exact) {
          setPendingContact(exact);
          setContactCandidates([]);
          setContactFallback(null);
          setPendingAction(action);
          return;
        }
        // Mehrere Treffer -> Picker zeigen
        setPendingContact(null);
        setContactCandidates(matches);
        setContactFallback(null);
        setPendingAction(action);
        return;
      }
      // Kein Treffer -> FallbackCard (max 2 Rekursionen), danach PendingActionCard
      if (retryCount < 2) {
        const nameLower = name.toLowerCase();
        const scored = matches
          .map(c => ({contact: c, score: fuzzyScore(nameLower, c.name)}))
          .filter(x => x.score > 0)
          .sort((a, b) => b.score - a.score)
          .map(x => x.contact);
        setContactFallback({
          action,
          originalName: name,
          suggestions: scored,
        });
        setPendingContact(null);
        setContactCandidates([]);
        setPendingAction(null);
        return;
      }
      // Zu viele Retries -> direkt PendingActionCard mit Warnung
      setPendingContact(null);
      setContactCandidates([]);
      setContactFallback(null);
      setPendingAction(action);
      return;
    }

    setPendingContact(null);
    setContactCandidates([]);
    setContactFallback(null);
    setPendingAction(action);
  }, []);

  const handlePickContact = useCallback((c: ContactMatch) => {
    setPendingContact(c);
    setContactCandidates([]);
  }, []);

  const handleRetryWithName = useCallback((newName: string) => {
    if (!contactFallback) return;
    const currentRetry = (contactFallback.action as DonnaAction & {_retryCount?: number})._retryCount ?? 0;
    if (currentRetry >= 2) {
      // Sicherheits-Guard: direkt PendingActionCard zeigen
      setContactFallback(null);
      setPendingAction(contactFallback.action);
      return;
    }
    const retryAction = {
      ...contactFallback.action,
      name: newName,
      _retryCount: currentRetry + 1,
    } as DonnaAction & {_retryCount: number};
    setContactFallback(null);
    handleDonnaAction(retryAction);
  }, [contactFallback, handleDonnaAction]);

  const messagesRef = useRef(messages);
  useEffect(() => { messagesRef.current = messages; }, [messages]);

  // Hilfsfunktion: Telefonnummer fuer URI-Schemas saeubern, fuehrendes "+" erlauben
  const cleanNumber = (n: string): string => n.replace(/[^0-9+]/g, '');

  // WhatsApp-Format: nationale Nummer ohne "+"; deutsche 0XXX -> 49XXX umrechnen
  const waNumber = (n: string): string => {
    const c = cleanNumber(n);
    if (c.startsWith('+')) return c.slice(1);
    if (c.startsWith('00')) return c.slice(2);
    if (c.startsWith('0')) return '49' + c.slice(1);
    return c;
  };

  // Doppel-Send-Guard: Tipp + Sprach-"ja" (oder zwei schnelle Tipps) duerfen eine Aktion nur EINMAL
  // ausloesen - gilt bis zum Ende des await (inkl. Tesla-Intent), nicht nur bis zum State-Update.
  const sendingRef = useRef(false);

  const confirmPendingAction = useCallback(async () => {
    const action = pendingActionRef.current;
    if (!action || sendingRef.current) return;
    sendingRef.current = true;
    pendingActionRef.current = null; // sofort leeren; der State-Effekt zieht erst nach dem Render nach
    const contact = pendingContactRef.current;
    setPendingAction(null);
    setPendingContact(null);
    setContactCandidates([]);
    let confirmText = '';
    try {
      switch (action.type) {
        case 'set_alarm': {
          const parts = ((action.time as string) ?? '00:00').split(':');
          const h = parseInt(parts[0], 10);
          const m = parseInt(parts[1] ?? '0', 10);
          await AlarmModule?.setAlarm(h, m, (action.label as string) ?? 'Donna');
          confirmText = `Wecker für ${action.time} Uhr gestellt`;
          break;
        }
        case 'set_timer': {
          const mins = (action.minutes as number) ?? 5;
          await AlarmModule?.setTimer(mins, (action.label as string) ?? 'Donna Timer');
          confirmText = `Timer für ${mins} Minuten gestartet`;
          break;
        }
        case 'create_event': {
          const startMs = action.start ? new Date(action.start as string).getTime() : Date.now();
          const endMs = action.end ? new Date(action.end as string).getTime() : startMs + 3600000;
          const extras: Array<{key: string; value: string | number | boolean}> = [
            {key: 'title', value: (action.title as string) ?? 'Termin'},
            {key: 'beginTime', value: startMs},
            {key: 'endTime', value: endMs},
          ];
          if (action.location) extras.push({key: 'eventLocation', value: action.location as string});
          await Linking.sendIntent('android.intent.action.INSERT', extras);
          confirmText = `Termin "${action.title}" eingetragen`;
          break;
        }
        case 'navigate':
          await Linking.openURL(`geo:0,0?q=${encodeURIComponent((action.destination as string) ?? '')}`);
          confirmText = `Navigation gestartet`;
          break;
        case TESLA_NAVIGATE:
          // Ziel lautlos an die Tesla-App (ShareActivity) senden; fängt Fehler selbst ab -> Text zurück.
          confirmText = await sendTargetToTesla(getNavTarget(action));
          break;
        case 'call': {
          const raw = contact?.number ?? (action.number as string) ?? '';
          const safe = cleanNumber(raw);
          if (safe) {
            await Linking.openURL(`tel:${safe}`);
            // B2 #1: nach der Bestätigung den Anrufen-Button in der Telefon-App selbst tippen.
            const out = await triggerAutoSend('call', AccessibilityBridge, {
              autoSend: action.autoSend as boolean | undefined,
            });
            confirmText = out.sent
              ? `Anruf an ${contact?.name ?? safe} wird gestartet`
              : `Anruf an ${contact?.name ?? safe} vorbereitet.${out.note ? ' ' + out.note : ''}`;
          } else {
            confirmText = `Keine Nummer verfügbar`;
          }
          break;
        }
        case 'sms': {
          const raw = contact?.number ?? (action.number as string) ?? '';
          const safe = cleanNumber(raw);
          const body = encodeURIComponent((action.message as string) ?? '');
          if (safe) {
            await Linking.openURL(`sms:${safe}${body ? `?body=${body}` : ''}`);
            confirmText = `SMS-App für ${contact?.name ?? safe} geöffnet — bitte absenden`;
          } else {
            confirmText = `Keine Nummer verfügbar`;
          }
          break;
        }
        case 'whatsapp': {
          // Oeffnet WhatsApp mit Kontakt + vorgeschriebener Nachricht, dann (B2 #3) tippt der
          // Accessibility-Dienst nach Mikes Bestätigung den Senden-Button selbst (3-Bug-Fix).
          const raw = contact?.number ?? (action.number as string) ?? '';
          const phone = waNumber(raw);
          const text = (action.message as string) ?? '';
          if (phone) {
            const url = `whatsapp://send?phone=${phone}${text ? `&text=${encodeURIComponent(text)}` : ''}`;
            try {
              await Linking.openURL(url);
            } catch {
              // Fallback: wa.me Web-Link, oeffnet ebenfalls die App wenn installiert
              await Linking.openURL(`https://wa.me/${phone}${text ? `?text=${encodeURIComponent(text)}` : ''}`);
            }
            const out = await triggerAutoSend('whatsapp', AccessibilityBridge, {
              autoSend: action.autoSend as boolean | undefined,
            });
            confirmText = out.sent
              ? `WhatsApp an ${contact?.name ?? phone} gesendet`
              : `WhatsApp für ${contact?.name ?? phone} geöffnet.${out.note ? ' ' + out.note : ''}`;
          } else {
            confirmText = `Keine Nummer verfügbar`;
          }
          break;
        }
        case 'open_url': {
          const url = (action.url as string) ?? '';
          if (/^https?:\/\//i.test(url)) await Linking.openURL(url);
          confirmText = `Link geöffnet`;
          break;
        }
        default:
          confirmText = 'Ausgeführt';
      }
    } catch {
      confirmText = 'Fehler beim Ausführen — bitte manuell';
    } finally {
      sendingRef.current = false;
    }
    if (confirmText) {
      loadMessages([...messagesRef.current, {role: 'assistant', content: confirmText}]);
    }
  }, [loadMessages]);

  const cancelPendingAction = useCallback(() => {
    setPendingAction(null);
    setPendingContact(null);
    setContactCandidates([]);
    setContactFallback(null);
    setNavChoice(null);
  }, []);

  // Treffer der Geocoding-Rückfrage gewählt: der Tipp ist die Bestätigung -> Ziel direkt ausführen
  // (Tesla: lautlos an die Tesla-App, sonst Handy-Navigation mit den Treffer-Koordinaten).
  const handleNavPick = useCallback(async (option: NavOption) => {
    if (!navChoice || sendingRef.current) return;
    sendingRef.current = true;
    const navType: NavType = navTypeOf(navChoice);
    setNavChoice(null);
    // Gesendet wird die VOLLE Adresse (option.address); das kurze Label ist nur Anzeige.
    const target = getOptionTarget(option);
    let confirmText: string;
    if (navType === TESLA_NAVIGATE) {
      confirmText = await sendTargetToTesla(target);
    } else {
      try {
        const q = encodeURIComponent(target.replace(/[()]/g, ' '));
        const hasCoords = option.lat != null && option.lon != null;
        await Linking.openURL(
          hasCoords ? `geo:${option.lat},${option.lon}?q=${option.lat},${option.lon}(${q})` : `geo:0,0?q=${q}`,
        );
        confirmText = 'Navigation gestartet';
      } catch {
        confirmText = 'Fehler beim Ausführen — bitte manuell';
      }
    }
    sendingRef.current = false;
    loadMessages([...messagesRef.current, {role: 'assistant', content: confirmText}]);
  }, [navChoice, loadMessages]);

  // Rueckfrage/Fehler des Geocodings sowie die Tesla-Bestaetigungs-Karte sofort zeigen (sonst bliebe nur
  // ein unscheinbarer Chip -> 2 Tipps) - pro Assistenten-Nachricht genau einmal, damit eine geschlossene
  // Karte nicht wieder aufpoppt. Gesendet wird weiterhin NUR nach dem Tipp auf "An Tesla senden".
  const lastNavChoiceMsgRef = useRef<unknown>(null);
  useEffect(() => {
    const last = messages[messages.length - 1];
    if (!last || last.role !== 'assistant' || last === lastNavChoiceMsgRef.current) return;
    const choice =
      last.actions?.find(isNavChoiceAction) ??
      last.actions?.find(a => a.type === TESLA_NAVIGATE && getNavTarget(a) !== '');
    if (choice) {
      lastNavChoiceMsgRef.current = last;
      void handleDonnaAction(choice);
    }
  }, [messages, handleDonnaAction]);

  // DONNA-115: Ideen-Karten Handler
  const handleIdeaConfirm = useCallback(() => {
    setPendingIdeaConfirm(null);
    sendMessage('ja, speicher die Idee', undefined, 'android');
  }, [sendMessage]);

  const handleIdeaReject = useCallback(() => {
    setPendingIdeaConfirm(null);
    sendMessage('nein, nicht speichern', undefined, 'android');
  }, [sendMessage]);

  const handleIdeaUpdateConfirm = useCallback(() => {
    setPendingIdeaUpdate(null);
    sendMessage('ja, gehört dazu', undefined, 'android');
  }, [sendMessage]);

  const handleIdeaUpdateReject = useCallback(() => {
    setPendingIdeaUpdate(null);
    sendMessage('nein, neue Idee', undefined, 'android');
  }, [sendMessage]);

  // Standort über nativen LocationModule holen (navigator.geolocation ohne Community-
  // Package nicht funktionsfähig auf Android). Einmal beim Start + alle 5 Minuten.
  useEffect(() => {
    let cancelled = false;
    const fetchLocation = () => {
      PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION)
        .then(async hasPermission => {
          // Standort-Berechtigung fragt der Einrichtungs-Assistent (OnboardingScreen) ab.
          // Hier nur prüfen — sonst würde ein bewusst übersprungener Schritt alle 5 Minuten
          // erneut einen System-Dialog auslösen.
          if (!hasPermission) { return; }
          try {
            const loc = await NativeModules.LocationModule.getLastKnownLocation();
            if (!cancelled && loc?.lat != null && loc?.lon != null) {
              setLocation({lat: loc.lat, lon: loc.lon});
            }
          } catch {
            // Kein GPS-Fix vorhanden — ignorieren
          }
        })
        .catch(() => {});
    };

    fetchLocation();
    const interval = setInterval(fetchLocation, 5 * 60 * 1000); // alle 5 Min aktualisieren
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, [setLocation]);
  const flatListRef = useRef<FlatList>(null);
  const insets = useSafeAreaInsets();

  // ─── Barge-in: Donna jederzeit unterbrechen ─────────────────────────────────
  // Während TTS spricht ODER der Stream läuft, kann Mike
  //  a) die Wiedergabe sofort stoppen (Stopp-Taste, Mic-Tap, Tippen) und
  //  b) sofort neu fragen (Mic-Tap -> Zuhören, oder Senden -> ersetzt die laufende Anfrage).
  // Die nativen TTS-Module melden kein Wiedergabe-Ende; `ttsActive` ist deshalb eine Schätzung (nur für die
  // sichtbare Stopp-Taste). Das Stoppen selbst ist immer idempotent und hängt NICHT an dieser Schätzung.
  const [ttsActive, setTtsActive] = useState(false);
  const ttsActiveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Wird bei jedem Unterbrechen erhöht: Early-TTS-Callbacks der alten Frage verfallen damit
  const ttsEpochRef = useRef(0);
  const ttsActiveRef = useRef(false);

  // First-Sentence-Skip-Fix (DONNA-Voice): Early-TTS spricht den ersten Satz, der Rest wird EINGEREIHT und erst
  // nach dessen echtem Ende ("ended") gestartet — sonst würgt der Rest-Aufruf über den Einzel-Player den noch
  // ladenden ersten Satz (Edge-Cloud) ab und Absatz 1 wird nie gehört. Logik: utils/ttsSequencer.ts.
  const speakViaKokoroRef = useRef<(t: string) => void>(() => {});
  const earlyFirstSeenRef = useRef(false);      // erster Chunk dieses Turns schon gesehen?
  const ttsSequencerRef = useRef<TtsSequencer | null>(null);
  if (ttsSequencerRef.current === null) {
    ttsSequencerRef.current = new TtsSequencer(t => speakViaKokoroRef.current(t));
  }
  /** Early-TTS-Chunk des Hooks einspeisen: erster Chunk = sofort sprechen, folgende = nach dem Ende einreihen. */
  const routeEarlyTts = useCallback((sentence: string) => {
    earlyTTSFiredRef.current = sentence;
    if (!earlyFirstSeenRef.current) {
      earlyFirstSeenRef.current = true;
      ttsSequencerRef.current?.speakFirst(sentence);
    } else {
      ttsSequencerRef.current?.queueRemaining(sentence);
    }
  }, []);

  // Freisprechen: Echo-Abgleich, Zähler, Relisten-Planung, Voice-Barge-in
  const lastSpokenRef = useRef('');                       // was Donna in dieser Antwort gesprochen hat
  const autoRelistensRef = useRef(0);
  const handsFreeTurnsRef = useRef(0);
  const relistenPendingRef = useRef(false);               // nach dem nächsten TTS-Ende wieder zuhören
  const relistenDelayTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const voiceBargeInSessionRef = useRef(false);           // das Zuhören wurde durch Mikes Stimme ausgelöst
  const voiceBargeInEnabledRef = useRef(VOICE_BARGE_IN_DEFAULT);  // Einstellung (Standard AN, Opt-out, siehe VOICE_BARGE_IN_DEFAULT)
  // #53/#54: KEIN Dauer-Aus mehr. Echo-Fehlauslöser lösen nur eine TEMPORÄRE Pause aus (EchoStrikes: 3 Strikes,
  // 90 s Cooldown, Decay) — spiegelt die native Logik (BargeInLogic.kt). Und es wird NICHT mehr prozessweit
  // `BargeInModule.setDisabled(true)` gesetzt: das hatte das Unterbrechen auch in der Live-Bubble lahmgelegt.
  const echoStrikesRef = useRef(new EchoStrikes());
  // Zeitpunkt des letzten App-Zustandswechsels (Vordergrund <-> minimiert/gesperrt). Eine Barge-in-Sitzung, die
  // dabei abbricht, ist KEIN Echo-Fehlauslöser (#53).
  const lastLifecycleChangeRef = useRef(0);
  const bargeInCountRef = useRef(0);                      // Voice-Barge-ins seit der letzten Berührung
  const bargeInMaxTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Echo-Filter nur bei Auto-Relisten/Barge-in: bei manuellem Mic-Tap spricht Mike garantiert selbst
  const echoFilterArmedRef = useRef(false);
  // Erkennung: On-Device (OfflineSTT, mit Biasing) bevorzugt, sonst Cloud-STT (useVoice)
  const offlineSTTAvailableRef = useRef(false);
  const offlineListeningRef = useRef(false);
  const [offlineAvailable, setOfflineAvailable] = useState(false);
  const [offlineListening, setOfflineListening] = useState(false);
  const [offlinePartial, setOfflinePartial] = useState('');
  const cardOpenRef = useRef(false);                       // Auswahl-/Bestätigungskarte offen
  // OfflineSTT-Watchdogs (der Erkenner kann ohne Ergebnis stumm bleiben -> Mic nie unbegrenzt offen lassen)
  const offlineNoPartialTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const offlineMaxTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const offlineResultWatchdogRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const clearOfflineTimers = useCallback(() => {
    for (const ref of [offlineNoPartialTimerRef, offlineMaxTimerRef, offlineResultWatchdogRef]) {
      if (ref.current) { clearTimeout(ref.current); ref.current = null; }
    }
  }, []);

  /** Nutzer hat selbst eingegriffen (Mic, Tippen, Senden, Stopp) -> Freisprech-Zähler zurücksetzen. */
  const resetTurnCounters = useCallback(() => {
    autoRelistensRef.current = 0;
    handsFreeTurnsRef.current = 0;
    bargeInCountRef.current = 0;
  }, []);

  /** Stimm-Lauschen beenden (und seinen Selbst-Stopp-Timer). */
  const stopBargeInMonitor = useCallback(() => {
    if (bargeInMaxTimerRef.current) { clearTimeout(bargeInMaxTimerRef.current); bargeInMaxTimerRef.current = null; }
    try { NativeModules.BargeInModule?.stop?.(); } catch { /* ignorieren */ }
  }, []);

  /** App im Hintergrund/gesperrt: nichts darf starten oder weiterlaufen. */
  const appInBackground = useCallback((): boolean => {
    const st = AppState.currentState as unknown;
    return st === 'background' || st === 'inactive';
  }, []);

  /**
   * Ende einer Barge-in-Sitzung. Ohne echte Äußerung (Stille, Fehler, Echo) zählt ein Strike; drei Strikes lösen
   * nur eine TEMPORÄRE Pause aus (Cooldown, danach wieder scharf — kein Dauer-Aus, #54). Rund um einen
   * App-Zustandswechsel (minimiert/gesperrt/zurück) zählt nichts (#53). Echte Sprache setzt die Strikes zurück.
   */
  const endBargeInSession = useCallback((genuine: boolean) => {
    if (!voiceBargeInSessionRef.current) { return; }
    voiceBargeInSessionRef.current = false;
    if (genuine) {
      echoStrikesRef.current.reset();
      return;
    }
    // #53: Endet die Sitzung wegen eines App-Zustandswechsels (minimiert/gesperrt/zurück) oder im Hintergrund,
    // ist das keine Echo-Evidenz -> kein Strike, keine Meldung.
    if (!shouldCountEchoStrike(appInBackground(), Date.now() - lastLifecycleChangeRef.current, LIFECYCLE_GRACE_MS)) { return; }
    if (echoStrikesRef.current.record()) {
      // Nur TEMPORÄRE Pause (selbst-erholend); kein setDisabled(true) — siehe echoStrikesRef.
      console.warn('[BargeIn] nach wiederholten Fehlauslösern kurz pausiert (Cooldown) — danach wieder scharf');
      ToastAndroid.show('Unterbrechen kurz pausiert', ToastAndroid.SHORT);
    }
  }, [appInBackground]);

  /**
   * Prüft eine erkannte Äußerung: war es nur Donnas eigene Stimme (Echo), wird sie VERWORFEN (kein Senden,
   * keine Endlosschleife). Zwei Echo-Selbstunterbrechungen schalten Voice-Barge-in ab -> es bleibt das
   * zuverlässige Verhalten (Mikrofon erst nach dem TTS-Ende).
   * @returns true = echte Äußerung von Mike
   */
  const acceptVoiceText = useCallback((text: string): boolean => {
    // Der Echo-Filter gilt nur, wenn das Zuhören automatisch (Auto-Relisten) oder per Voice-Barge-in begann
    const armed = echoFilterArmedRef.current;
    echoFilterArmedRef.current = false;
    if (armed && isEchoOfSpoken(text, lastSpokenRef.current, voiceBargeInSessionRef.current)) {
      endBargeInSession(false);   // Echo ist kein echter Sprecher -> Strike
      ToastAndroid.show('Ich habe nur mich selbst gehört.', ToastAndroid.SHORT);
      return false;
    }
    endBargeInSession(true);
    autoRelistensRef.current = 0;   // echte Sprache setzt nur den Echo-Zähler zurück; das harte Limit bleibt
    return true;
  }, [endBargeInSession]);

  const markTtsActive = useCallback((text: string) => {
    ttsActiveRef.current = true;
    setTtsActive(true);
    if (ttsActiveTimerRef.current) {clearTimeout(ttsActiveTimerRef.current);}
    // Sicherheitsnetz: meldet das native Modul kein TTS-Ende, verschwindet die Stopp-Taste nach der Schätzung
    ttsActiveTimerRef.current = setTimeout(() => {
      ttsActiveTimerRef.current = null;
      ttsActiveRef.current = false;
      setTtsActive(false);
      stopBargeInMonitor();   // ohne TTS-Ende-Ereignis darf das Stimm-Lauschen nicht weiterlaufen
    }, estimateTtsMs(text) + 3_000);
  }, [stopBargeInMonitor]);

  /** Wiedergabe sofort stoppen (Piper, System-/Server-TTS) und alle Timer der Wiedergabe löschen. */
  const stopAllTTS = useCallback(() => {
    if (ttsTimerRef.current) { clearTimeout(ttsTimerRef.current); ttsTimerRef.current = null; }
    if (ttsActiveTimerRef.current) { clearTimeout(ttsActiveTimerRef.current); ttsActiveTimerRef.current = null; }
    NativeModules.PiperTTS?.stopPiper?.(); NativeModules.TTSModule?.stop?.();
    // Eingereihten Resttext (First-Sentence-Sequencer) verwerfen — sonst spräche Donna nach dem Stoppen weiter
    ttsSequencerRef.current?.clear();
    // Auch ein geplantes Auto-Relisten und das Stimm-Lauschen enden mit dem Stoppen
    relistenPendingRef.current = false;
    if (relistenDelayTimerRef.current) { clearTimeout(relistenDelayTimerRef.current); relistenDelayTimerRef.current = null; }
    stopBargeInMonitor();
    ttsActiveRef.current = false;
    setTtsActive(false);
  }, [stopBargeInMonitor]);

  /**
   * Nur die SPRACHE unterbrechen (ohne den Request abzubrechen): TTS stoppen, noch ausstehende Sätze der
   * laufenden Antwort verwerfen, keine Vorlese-Logik für die alte Antwort mehr. Für „neu senden“ — den
   * Request selbst ersetzt sendMessage.
   */
  const interruptSpeech = useCallback(() => {
    ttsEpochRef.current += 1;
    earlyTTSFiredRef.current = null;
    // Neuer Turn / Barge-in: eingereihten Resttext verwerfen und den Early-TTS-First-Marker zurücksetzen,
    // damit der erste Satz der NÄCHSTEN Antwort wieder als „erster“ erkannt wird.
    earlyFirstSeenRef.current = false;
    ttsSequencerRef.current?.clear();
    lastInputWasVoiceRef.current = false;
    stopAllTTS();
  }, [stopAllTTS]);

  /** Stopp-Taste / Mic-Tap: Sprache UND laufenden Stream beenden (bereits empfangener Text bleibt stehen). */
  const bargeIn = useCallback(() => {
    interruptSpeech();
    cancelChat?.();
  }, [interruptSpeech, cancelChat]);

  /**
   * Eine erkannte Äußerung (Cloud-STT ODER On-Device-STT) — gemeinsamer Weg für beide Erkenner.
   * Reihenfolge: Hintergrund-Sperre -> Echo-Filter (nur Auto/Barge-in) -> Ja/Nein-Bestätigung -> senden.
   */
  const handleTranscript = useCallback(
    (text: string) => {
      // Im Hintergrund/gesperrt entsteht KEIN Chat-Aufruf (ein spätes Ergebnis wird verworfen)
      if (appInBackground()) { return; }
      const trimmed = text.trim();
      if (!trimmed) { return; }
      // Echo von Donnas eigener Stimme verwerfen (nur Auto-Relisten/Barge-in) — sonst redet Donna mit sich selbst
      if (!acceptVoiceText(trimmed)) { return; }
      // Wartende Aktion per Sprache bestätigen/ablehnen: Verneinung zuerst, Wortgrenzen, bei Mehrdeutigkeit
      // passiert NICHTS (die Karte bleibt)
      if (pendingActionRef.current && conversationModeRef.current) {
        const answer = parseConfirmation(trimmed);
        if (answer === 'yes') { confirmPendingAction(); return; }
        if (answer === 'no') { cancelPendingAction(); return; }
        if (answer === 'ambiguous') {
          ToastAndroid.show('Bitte nur „Ja“ oder „Nein“ sagen.', ToastAndroid.SHORT);
          return;
        }
      }
      // TTS der vorherigen Antwort sofort stoppen + Timer canceln (Barge-in); sendMessage ersetzt einen laufenden Stream
      interruptSpeech();
      lastSpokenRef.current = '';
      lastInputWasVoiceRef.current = true;
      const epoch = ttsEpochRef.current;
      sendMessage(trimmed, undefined, 'android', (sentence) => {
        if (epoch !== ttsEpochRef.current) {return;}   // inzwischen unterbrochen
        routeEarlyTts(sentence);
      });
    },
    [sendMessage, confirmPendingAction, cancelPendingAction, routeEarlyTts, interruptSpeech, acceptVoiceText, appInBackground],
  );
  const handleTranscriptRef = useRef(handleTranscript);
  handleTranscriptRef.current = handleTranscript;

  const {voiceState, partialTranscript, startListening, stopListening, cancelListening, isAvailable} = useVoice({
    module: AndroidVoiceModule,
    locale: 'de-DE',
    onTranscript: handleTranscript,
  });

  /**
   * Zuhören starten: On-Device-STT (OfflineSTT, hat das Raum-/Gerätenamen-Biasing und die lange Sprechpause)
   * bevorzugt; Cloud-STT (useVoice) nur, wenn On-Device nicht verfügbar ist oder nicht startet.
   */
  /** Abbruch OHNE Ergebnis (Hintergrund, Unterbrechen, Tippen): danach kommt kein Transkript mehr. */
  const cancelListeningAny = useCallback(async () => {
    clearOfflineTimers();
    if (offlineListeningRef.current || offlineSTTAvailableRef.current) {
      offlineListeningRef.current = false;
      setOfflineListening(false);
      setOfflinePartial('');
      await OfflineSTT.cancel().catch(() => false);
    }
    await cancelListening?.();
  }, [cancelListening, clearOfflineTimers]);

  /**
   * „Fertig“: Aufnahme beenden, das Ergebnis kommt noch und wird gesendet. Bleibt es aus (hängender Erkenner),
   * bricht ein Watchdog nach 9 s ab — der „Zuhören“-Zustand darf nie hängen bleiben.
   */
  const finishListeningAny = useCallback(async () => {
    if (offlineListeningRef.current) {
      if (offlineResultWatchdogRef.current) { clearTimeout(offlineResultWatchdogRef.current); }
      offlineResultWatchdogRef.current = setTimeout(() => {
        offlineResultWatchdogRef.current = null;
        if (offlineListeningRef.current) {
          console.warn('[OfflineSTT] kein Ergebnis nach „Fertig“ — Abbruch');
          cancelListeningAny().catch(() => {});
        }
      }, OFFLINE_RESULT_WATCHDOG_MS);
      await OfflineSTT.stop().catch(() => false);
      return;
    }
    await stopListening();
  }, [stopListening, cancelListeningAny]);

  const startListeningAny = useCallback(async () => {
    if (appInBackground()) { return; }
    if (offlineSTTAvailableRef.current) {
      try {
        setOfflinePartial('');
        // Vor dem await auf „hört zu“ setzen: Hintergrund-/Abbruch-Handler können währenddessen zurücksetzen
        offlineListeningRef.current = true;
        await OfflineSTT.start('de-DE');
        // Race geschlossen: ging die App während des Starts in den Hintergrund (oder wurde abgebrochen),
        // darf das Mikrofon nicht offen bleiben und der Zustand nicht wieder auf „hört zu“ springen
        if (appInBackground() || !offlineListeningRef.current) {
          offlineListeningRef.current = false;
          setOfflineListening(false);
          await OfflineSTT.cancel().catch(() => false);
          return;
        }
        setOfflineListening(true);
        // Watchdogs: ohne Partial nach 10 s abbrechen; Maximum 50 s, dann „Fertig“
        clearOfflineTimers();
        offlineNoPartialTimerRef.current = setTimeout(() => {
          offlineNoPartialTimerRef.current = null;
          if (offlineListeningRef.current) { cancelListeningAny().catch(() => {}); }
        }, OFFLINE_NO_PARTIAL_MS);
        offlineMaxTimerRef.current = setTimeout(() => {
          offlineMaxTimerRef.current = null;
          if (offlineListeningRef.current) { finishListeningAny().catch(() => {}); }
        }, OFFLINE_MAX_LISTEN_MS);
        return;
      } catch (e) {
        console.warn('[OfflineSTT] Start fehlgeschlagen — Fallback auf Cloud-STT:', e);
        offlineListeningRef.current = false;
        setOfflineListening(false);
        offlineSTTAvailableRef.current = false;
        setOfflineAvailable(false);
      }
    }
    await startListening();
  }, [startListening, appInBackground, clearOfflineTimers, cancelListeningAny, finishListeningAny]);

  // DONNA-159: OfflineSTT ist der bevorzugte STT-Pfad (On-Device, Samsung Gauss NPU): dort wirken das
  // Biasing auf Raum-/Gerätenamen („Streamingzimmer“) und die lange Sprechpause. Nur wenn On-Device nicht
  // verfügbar ist, läuft die Erkennung über @react-native-voice (Cloud-STT) ohne Biasing.
  useEffect(() => {
    let mounted = true;
    OfflineSTT.isAvailable().then(available => {
      if (!mounted || !available) { return; }
      offlineSTTAvailableRef.current = true;
      setOfflineAvailable(true);
      console.log('[OfflineSTT] On-Device-STT aktiv (mit Biasing)');
    }).catch(() => {});

    const resultSub = OfflineSTT.onResult(text => {
      // Nur während eines Zuhörens: nach Abbruch (Hintergrund, Tippen, Unterbrechen) wird nichts mehr verarbeitet
      if (!offlineListeningRef.current) { return; }
      clearOfflineTimers();
      offlineListeningRef.current = false;
      setOfflineListening(false);
      setOfflinePartial('');
      handleTranscriptRef.current(text);
    });
    const partialSub = OfflineSTT.onPartial(text => {
      if (!offlineListeningRef.current) { return; }
      // Es wird gesprochen: der „kein Partial“-Watchdog ist erledigt
      if (offlineNoPartialTimerRef.current && text.trim()) {
        clearTimeout(offlineNoPartialTimerRef.current);
        offlineNoPartialTimerRef.current = null;
      }
      setOfflinePartial(text);
    });
    const errorSub = OfflineSTT.onError(code => {
      clearOfflineTimers();
      offlineListeningRef.current = false;
      setOfflineListening(false);
      setOfflinePartial('');
      if (!OFFLINE_SOFT_ERRORS.has(code)) {
        console.warn('[OfflineSTT] Fehler', code, '— weiter mit Cloud-STT');
        offlineSTTAvailableRef.current = false;
        setOfflineAvailable(false);
      }
    });
    return () => {
      mounted = false;
      resultSub?.remove?.();
      partialSub?.remove?.();
      errorSub?.remove?.();
      clearOfflineTimers();
      offlineListeningRef.current = false;
      OfflineSTT.cancel().catch(() => false);
    };
  }, [clearOfflineTimers]);

  const isListening = voiceState === 'listening' || offlineListening;
  const micAvailable = isAvailable || offlineAvailable;
  const partialShown = partialTranscript || offlinePartial;
  const hasMessages = messages.length > 0;
  // #134 P5: reaktiver Sprachzug-Zustand (ersetzt das Ref lastInputWasVoiceRef beim Rendern)
  const [voiceTurn, resetVoiceTurn] = useVoiceTurn({isListening, isLoading, ttsActive});
  // "Tippen": Zaehler fuer den Fokus im Eingabefeld (ChatInputRow ist ggf. schon gemountet)
  const [focusTick, setFocusTick] = useState(0);
  const openTyping = useCallback(() => { setTypingOpen(true); setFocusTick(t => t + 1); }, []);
  const inputTextRef = useRef('');

  const sendText = async (raw: string) => {
    const text = raw.trim();
    if (!text) {return;}
    if (isListening) {return;}  // DONNA-237: kein Senden während Spracheingabe
    resetVoiceTurn();   // getippte Frage: kein Sprachzug
    // Barge-in: laufende Wiedergabe sofort stoppen. Einen noch laufenden Stream ersetzt sendMessage durch
    // die neue Frage (alter Request wird abgebrochen — kein Doppel-Request, keine Antwort der alten Frage).
    interruptSpeech();
    lastSpokenRef.current = '';
    resetTurnCounters();
    const quote = replyTo;
    setInputText('');
    setReplyTo(null);
    const epoch = ttsEpochRef.current;
    const onEarlyTTSCallback = ttsEnabled ? (sentence: string) => {
      if (epoch !== ttsEpochRef.current) {return;}   // inzwischen unterbrochen
      routeEarlyTts(sentence);
    } : undefined;
    await sendMessage(text, quote ?? undefined, 'android', onEarlyTTSCallback);
    setTimeout(() => flatListRef.current?.scrollToEnd({animated: true}), 100);
  };
  const handleSend = () => sendText(inputText);

  const handleVoicePress = useCallback(async () => {
    resetTurnCounters();
    // Manueller Start: Mike spricht garantiert selbst -> kein Echo-Abgleich, Vergleichstext leeren
    voiceBargeInSessionRef.current = false;
    echoFilterArmedRef.current = false;
    if (isListening) {
      await finishListeningAny();
      return;
    }
    // Barge-in: Mic-Tap während Donna spricht oder der Stream läuft -> alles sofort beenden, dann zuhören
    // (sonst nähme das Mikrofon die eigene Stimme auf und der alte Request liefe weiter)
    bargeIn();
    lastSpokenRef.current = '';
    const granted = await requestMicPermission();
    if (!granted) {return;}
    await startListeningAny();
  }, [isListening, finishListeningAny, startListeningAny, bargeIn, resetTurnCounters]);

  /** Stopp-Taste: Wiedergabe und Stream beenden, ohne neu zu starten. */
  const handleStop = useCallback(() => { resetTurnCounters(); resetVoiceTurn(); bargeIn(); }, [bargeIn, resetTurnCounters, resetVoiceTurn]);

  /** Tippen während Donna spricht: Wiedergabe sofort stoppen (die Frage wird erst beim Senden ersetzt). */
  const handleInputChange = useCallback((value: string) => {
    if (value.length > 0 && (ttsActive || isLoading)) { interruptSpeech(); }
    if (value.length > 0) {
      resetTurnCounters();
      resetVoiceTurn();
      // Tippen während des Zuhörens beendet das Zuhören (das Eingabefeld bleibt benutzbar)
      if (isListening) { cancelListeningAny().catch(() => {}); }
    }
    setInputText(value);
  }, [ttsActive, isLoading, isListening, interruptSpeech, resetTurnCounters, resetVoiceTurn, cancelListeningAny]);

  // Sichtbare Stopp-Taste, solange Donna spricht oder antwortet
  const canInterrupt = ttsActive || isLoading;
  // #122 Paket D: Anzeige-Zustand (hört zu / denkt / spricht / ruhig). Fehlerzustand: im Chat derzeit NICHT verdrahtet (kein Fehler-Zustand,
  // useChat meldet Fehler als Nachricht) -> error bleibt bewusst false, nicht künstlich ableiten.
  const orbState = mapOrbState({listening: isListening, speaking: ttsActive, thinking: isLoading, error: false});

  // Side-Button trigger
  useEffect(() => {
    // @ts-ignore
    voiceTriggerRef.current = handleVoicePress;
  }, [handleVoicePress]);

  // DONNA-135: Proaktiver Chat-Handler — Notification-Tap öffnet neuen Chat mit Donnas Nachricht
  // DONNA-147: Cold-Start-Race-Condition — pendingProactiveMessage prüfen sobald Ref gesetzt ist
  // DONNA-198 v3: startProactiveChat() — atomisches State-Update, kein setTimeout-Race mehr
  // DONNA-198 v8: belt-and-suspenders — ChatScreen pollt SharedPreferences direkt beim Mount,
  // unabhängig von App.tsx. Falls App.tsx-Pfad versagt (z.B. Ref-Race), zieht dieser zweite Pfad.
  // getAndClear() ist atomar — kein Doppel-Trigger möglich.
  // DONNA-247: Nach startProactiveChat() explizit zum Ende scrollen. onContentSizeChange feuert
  // nach dem Render-Zyklus, aber bei Warm-Start (App bereits gemountet) kann ein explizites
  // scrollToEnd mit kurzem Delay sicherstellen dass die neue Nachricht sichtbar ist.
  // Notification-Tap-Dedupe: jeder Tap trägt einen Nonce; derselbe Nonce (Doppel-Tap, onCreate+onNewIntent,
  // AppState-Resume-Retries, App.tsx- UND ChatScreen-Pfad) seedet nur EINMAL. Ein alter Seed ohne/mit bereits
  // gesehenem Nonce taucht so nicht versehentlich erneut auf.
  const lastProactiveNonceRef = useRef<string | null>(null);

  // SharedPreferences einmal lesen (atomar) und als proaktiven Chat einspielen. Genutzt beim Mount UND beim
  // nativen Event (Warm-Start: RN-View ist schon gemountet -> ohne Live-Listener bliebe der alte Chat stehen).
  const consumeProactivePrefs = useCallback(() => {
    if (Platform.OS !== 'android' || !NativeModules.ProactiveMessageModule) {
      return;
    }
    NativeModules.ProactiveMessageModule.getAndClear()
      .then((raw: string | null) => {
        if (!raw) {
          return;
        }
        let payload: ProactiveChatPayload;
        try {
          const parsed = JSON.parse(raw);
          payload = {message: parsed.message ?? raw, session_id: parsed.session_id, title: parsed.title, nonce: parsed.nonce};
        } catch {
          payload = {message: raw};
        }
        proactiveChatRef.current?.(payload);
      })
      .catch((e: unknown) => console.warn('[ChatScreen] getAndClear fehlgeschlagen', e));
  }, []);

  useEffect(() => {
    // @ts-ignore
    proactiveChatRef.current = (payload: ProactiveChatPayload) => {
      const message = (payload?.message ?? (payload as unknown as string))?.toString() ?? '';
      if (!message.trim()) {
        console.warn('[ChatScreen] proactive message leer — ignoriert');
        return;
      }
      // Doppel-Seed verwerfen: derselbe Tap darf nicht zweimal einen Chat anlegen.
      const nonce = payload?.nonce;
      if (nonce && nonce === lastProactiveNonceRef.current) {
        return;
      }
      if (nonce) {
        lastProactiveNonceRef.current = nonce;
      }
      // Notification-Tap-Kontext: Titel (sofern aussagekräftig) VORANSTELLEN, damit Mike sofort sieht, worum es
      // ging, und direkt darüber weiterreden kann.
      const title = payload?.title?.trim();
      const content = title && title !== 'Donna' ? `${title}\n\n${message.trim()}` : message.trim();
      startProactiveChat(content, payload?.session_id);
      // DONNA-247: Explizit zum Ende scrollen nach Render-Zyklus (Warm-Start-Absicherung)
      setTimeout(() => flatListRef.current?.scrollToEnd({animated: true}), 150);
    };
    // DONNA-147: Event kann vor ChatScreen-Mount angekommen sein (Cold Start)
    if (pendingProactiveMessage) {
      const pending = pendingProactiveMessage;
      clearPendingProactiveMessage();
      proactiveChatRef.current?.(pending);
    }
    // DONNA-198 v8: Zusätzlicher unabhängiger Pfad — SharedPreferences direkt beim Mount pollen.
    consumeProactivePrefs();
    // Warm-Start-Fix: auch LIVE auf das native Event hören. Tippt Mike eine Push an, während die App warm im
    // Hintergrund läuft, feuert nur MainActivity.onNewIntent -> dieses Event (kein onCreate, kein neuer Mount).
    // Ohne diesen Listener bliebe der alte Chat ohne Inhalt stehen. getAndClear() ist atomar -> kein Doppel mit App.tsx.
    const sub = DeviceEventEmitter.addListener('donna_open_proactive_chat', () => consumeProactivePrefs());
    return () => sub.remove();
  }, [startProactiveChat, consumeProactivePrefs]);

  // Hintergrund-Handling: Aufnahme + TTS stoppen wenn App in Hintergrund geht
  useEffect(() => {
    const sub = AppState.addEventListener('change', nextState => {
      lastLifecycleChangeRef.current = Date.now();   // #53: Sitzungsende rund um den Wechsel zählt nicht als Strike
      if (nextState !== 'active') {
        // Gesperrt/Hintergrund: NICHTS darf weiterlaufen — weder Zuhören (abbrechen statt stop: stop liefert noch ein
        // Ergebnis = weiterer Chat-Aufruf im Hintergrund), noch ein laufender Stream, TTS, Relisten oder Stimm-Lauschen
        cancelListeningAny().catch(() => {});
        cancelChat?.();
        // Epoche erhöhen + TTS stoppen: ein später Early-TTS-Callback darf im Hintergrund nicht mehr sprechen
        interruptSpeech();
        NativeModules.PiperTTS?.stopPiper?.(); NativeModules.TTSModule?.stop?.();
        relistenPendingRef.current = false;
        voiceBargeInSessionRef.current = false;
        echoFilterArmedRef.current = false;
        if (relistenDelayTimerRef.current) { clearTimeout(relistenDelayTimerRef.current); relistenDelayTimerRef.current = null; }
        stopBargeInMonitor();
      }
    });
    return () => sub.remove();
  }, [cancelListeningAny, cancelChat, stopBargeInMonitor, interruptSpeech]);

  // ─── Gesprächsmodus (DONNA-15) ───────────────────────────────────────────────
  // Freisprechen ist der Standard: einmal sprechen, danach hört Donna nach jeder Antwort von selbst wieder zu.
  // Ein/Aus: Schalter in der Kopfzeile oder der Hinweis über der Eingabeleiste; die Wahl wird gemerkt.
  const [conversationMode, setConversationMode] = useState(true);
  const conversationModeRef = useRef(true);
  const ttsTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Ref synchron halten (für Closures in setTimeout)
  useEffect(() => {
    conversationModeRef.current = conversationMode;
  }, [conversationMode]);

  // Gemerkte Einstellungen laden (Gesprächsmodus, Unterbrechen per Stimme)
  useEffect(() => {
    AsyncStorage.getItem(CONVERSATION_MODE_KEY).then(val => {
      if (val !== null) { setConversationMode(val === 'true'); }
    }).catch(() => {});
    // #122 Paket C: eine Quelle (native Pref, siehe native/voiceBargeIn.ts); Opt-out, Standard = an
    getVoiceBargeIn().then(on => {
      voiceBargeInEnabledRef.current = on;
    }).catch(() => {});
    // Änderungen aus den Einstellungen wirken im gemounteten Chat sofort (ohne Neustart)
    return subscribeVoiceBargeIn(on => {
      voiceBargeInEnabledRef.current = on;
    });
  }, []);

  /** Erst-Hinweis (einmalig, gemerkt): Voice-Barge-in ist jetzt standardmäßig an. */
  const bargeInHintCheckedRef = useRef(false);
  const showBargeInHintOnce = useCallback(() => {
    if (bargeInHintCheckedRef.current) { return; }
    bargeInHintCheckedRef.current = true;
    AsyncStorage.getItem(VOICE_BARGE_IN_HINT_KEY).then(val => {
      if (val === 'true') { return; }
      AsyncStorage.setItem(VOICE_BARGE_IN_HINT_KEY, 'true').catch(() => {});
      ToastAndroid.show(
        'Du kannst Donna jederzeit unterbrechen — einfach reinreden. Ausschalten: lange auf „Gespräch“ tippen.',
        ToastAndroid.LONG,
      );
    }).catch(() => {});
  }, []);

  const toggleConversationMode = useCallback(() => {
    resetTurnCounters();
    setConversationMode(prev => {
      const next = !prev;
      AsyncStorage.setItem(CONVERSATION_MODE_KEY, String(next)).catch(() => {});
      if (!next) {
        // Aus: geplantes Relisten und Stimm-Lauschen beenden
        if (ttsTimerRef.current) { clearTimeout(ttsTimerRef.current); ttsTimerRef.current = null; }
        relistenPendingRef.current = false;
        if (relistenDelayTimerRef.current) { clearTimeout(relistenDelayTimerRef.current); relistenDelayTimerRef.current = null; }
        try { NativeModules.BargeInModule?.stop?.(); } catch { /* ignorieren */ }
      }
      return next;
    });
  }, [resetTurnCounters]);

  /** Lange auf den Gesprächs-Schalter tippen: Unterbrechen per Stimme ein/aus. */
  const toggleVoiceBargeIn = useCallback(() => {
    const next = !voiceBargeInEnabledRef.current;
    voiceBargeInEnabledRef.current = next;
    if (next) {
      // Wieder-Einschalten: frischer Start (Strikes und Abschaltung zurücksetzen)
      echoStrikesRef.current.reset();
      try { NativeModules.BargeInModule?.setDisabled?.(false); } catch { /* ignorieren */ }
    } else {
      stopBargeInMonitor();
    }
    // der tatsächlich gültige Wert gewinnt (bei Fehlschlag des Schreibens bleibt der bisherige)
    void setVoiceBargeIn(next).then(applied => { voiceBargeInEnabledRef.current = applied; });
    ToastAndroid.show(next ? 'Unterbrechen per Stimme: an' : 'Unterbrechen per Stimme: aus', ToastAndroid.SHORT);
  }, [stopBargeInMonitor]);

  // ─── Freisprechen: Zustand für Closures ──────────────────────────────────────
  const isListeningRef = useRef(false);
  const typingActiveRef = useRef(false);

  /**
   * Auto-Relisten nach dem ECHTEN TTS-Ende. Nicht, wenn Mike gerade tippt, schon zugehört wird, die App im
   * Hintergrund ist oder die Zähler-Grenzen erreicht sind (Schutz vor Echo-Endlosschleifen / API-Kosten).
   */
  const performAutoRelisten = useCallback(() => {
    stopBargeInMonitor();   // das Mikrofon gehört jetzt dem Erkenner
    // Donna spricht noch (z. B. Rest der Antwort nach dem Early-TTS-Satz): nicht jetzt öffnen, sondern nach
    // dem nächsten TTS-Ende — sonst nähme das Mikrofon ihre Stimme auf und der Echo-Filter verwürfe die Runde
    if (ttsActiveRef.current) { relistenPendingRef.current = true; return; }
    if (!conversationModeRef.current || AppState.currentState !== 'active') { return; }
    if (isListeningRef.current || typingActiveRef.current) { return; }
    // Offene Karte (Bestätigung/Auswahl): nicht hands-free zuhören — wie im Overlay (!hasPendingCards)
    if (cardOpenRef.current) { return; }
    if (autoRelistensRef.current >= MAX_AUTO_RELISTENS || handsFreeTurnsRef.current >= MAX_HANDS_FREE_TURNS) { return; }
    autoRelistensRef.current += 1;
    handsFreeTurnsRef.current += 1;
    lastInputWasVoiceRef.current = true;
    echoFilterArmedRef.current = true;   // automatisch geöffnet: Donnas Ausklang könnte erkannt werden
    voiceBargeInSessionRef.current = false;
    startListeningAny().catch(() => {});
  }, [startListeningAny, stopBargeInMonitor]);

  /** Echo-Schutz: erst kurz nach dem TTS-Ende wieder zuhören (der Ausklang soll nicht erkannt werden). */
  const scheduleAutoRelisten = useCallback(() => {
    if (relistenDelayTimerRef.current) { clearTimeout(relistenDelayTimerRef.current); }
    relistenDelayTimerRef.current = setTimeout(() => {
      relistenDelayTimerRef.current = null;
      performAutoRelisten();
    }, ECHO_GUARD_MS);
  }, [performAutoRelisten]);

  // Echter TTS-Zustand vom nativen Modul ("started" | "ended") + Voice-Barge-in-Ereignis
  const ttsEventHandlersRef = useRef({
    started: () => {},
    ended: () => {},
    voiceBargeIn: () => {},
  });
  ttsEventHandlersRef.current = {
    started: () => {
      ttsActiveRef.current = true;
      setTtsActive(true);
      // Mikes Stimme über Donnas Ausgabe erkennen (nur im Gesprächsmodus, nur wenn nicht abgeschaltet).
      // start() liefert false ohne Echo-Unterdrückung -> dann bleibt es beim Mikrofon nach dem TTS-Ende.
      // Nie im Hintergrund/gesperrt; Hard-Cap als Sicherheitsnetz gegen ein vergessenes Stoppen.
      if (
        conversationModeRef.current && voiceBargeInEnabledRef.current && !echoStrikesRef.current.isPaused() &&
        AppState.currentState === 'active' &&
        bargeInCountRef.current < MAX_BARGE_INS_PER_TOUCH && handsFreeTurnsRef.current < MAX_HANDS_FREE_TURNS
      ) {
        try { NativeModules.BargeInModule?.start?.(); } catch { /* ignorieren */ }
        if (bargeInMaxTimerRef.current) { clearTimeout(bargeInMaxTimerRef.current); }
        bargeInMaxTimerRef.current = setTimeout(stopBargeInMonitor, BARGE_IN_MAX_MS);
        showBargeInHintOnce();
      }
    },
    ended: () => {
      // First-Sentence-Sequencer: War das nur das Ende des ERSTEN Satzes und steht noch Resttext aus, startet
      // ihn der Sequencer JETZT — Donna spricht weiter, also NICHT als TTS-Ende behandeln (kein Relisten, Barge-in
      // läuft durch). Erst das Ende des Restes (onEnded -> false) löst die normale Ende-Logik aus.
      if (ttsSequencerRef.current?.onEnded()) {
        if (ttsActiveTimerRef.current) { clearTimeout(ttsActiveTimerRef.current); ttsActiveTimerRef.current = null; }
        return;
      }
      if (ttsActiveTimerRef.current) { clearTimeout(ttsActiveTimerRef.current); ttsActiveTimerRef.current = null; }
      ttsActiveRef.current = false;
      setTtsActive(false);
      stopBargeInMonitor();
      if (relistenPendingRef.current) {
        relistenPendingRef.current = false;
        if (ttsTimerRef.current) { clearTimeout(ttsTimerRef.current); ttsTimerRef.current = null; }
        scheduleAutoRelisten();
      }
    },
    voiceBargeIn: () => {
      if (!conversationModeRef.current || echoStrikesRef.current.isPaused() || !voiceBargeInEnabledRef.current) { return; }
      if (AppState.currentState !== 'active') { stopBargeInMonitor(); return; }
      if (!ttsActiveRef.current) { return; }   // veraltet: Donna spricht gar nicht mehr
      // Barge-in ist KEINE Berührung: er zählt zum Freisprech-Limit (nie zurücksetzen) und ist pro Berührung begrenzt
      if (bargeInCountRef.current >= MAX_BARGE_INS_PER_TOUCH || handsFreeTurnsRef.current >= MAX_HANDS_FREE_TURNS) {
        stopBargeInMonitor();
        return;
      }
      bargeInCountRef.current += 1;
      handsFreeTurnsRef.current += 1;
      voiceBargeInSessionRef.current = true;
      echoFilterArmedRef.current = true;
      bargeIn();                               // Ausgabe + Stream beenden, Monitor stoppen
      startListeningAny().catch(() => {});     // Mikes Äußerung als neuen Turn hören
    },
  };
  useEffect(() => {
    const ttsSub = DeviceEventEmitter.addListener('DonnaTTSState', (e: {state?: string}) => {
      if (e?.state === 'started') { ttsEventHandlersRef.current.started(); }
      else if (e?.state === 'ended') { ttsEventHandlersRef.current.ended(); }
    });
    const bargeSub = DeviceEventEmitter.addListener('DonnaBargeIn', () => ttsEventHandlersRef.current.voiceBargeIn());
    return () => { ttsSub.remove(); bargeSub.remove(); };
  }, []);

  // ─── DONNA-189: Phi-3 Mini Download-Banner ───────────────────────────────────
  // Zeigt ein Banner solange das Phi-3 Mini Modell noch nicht heruntergeladen ist.
  // Verschwindet automatisch wenn Download abgeschlossen (percent === 100 + status READY).
  const [phiModelStatus, setPhiModelStatus] = useState<'unknown' | 'NOT_DOWNLOADED' | 'DOWNLOADING' | 'READY' | 'ERROR'>('unknown');
  const [phiDownloadPercent, setPhiDownloadPercent] = useState(0);

  // Beim Mounten: Phi-3 Status prüfen
  useEffect(() => {
    PhiModule.getModelStatus().then(status => {
      setPhiModelStatus(status as typeof phiModelStatus);
    }).catch(() => setPhiModelStatus('unknown'));
  }, []);

  // PhiDownloadProgress-Events vom Native-Modul empfangen
  useEffect(() => {
    const emitter = new NativeEventEmitter(NativeModules.PhiModule);
    const sub = emitter.addListener('PhiDownloadProgress', (event: {bytes: number; total: number; percent: number}) => {
      setPhiDownloadPercent(event.percent);
      if (event.percent >= 100) {
        // Download abgeschlossen — Status neu laden + Router-Cache invalidieren
        PhiModule.getModelStatus().then(status => {
          setPhiModelStatus(status as typeof phiModelStatus);
          if (status === 'READY') invalidateOnDeviceCache();
        }).catch(() => {});
      } else {
        setPhiModelStatus('DOWNLOADING');
      }
    });
    return () => sub.remove();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ─── Stimme: A/B Edge <-> Piper-lokal (Voice-Batch) ────────────────────────────
  // Die Wahl liegt nativ (SharedPreferences, auch vom Overlay gelesen); hier nur der Spiegel für die Sprech-Kette.
  const ttsEngineRef = useRef<TtsEngine>(TTS_ENGINE_DEFAULT);
  const edgeVoiceRef = useRef<string>(EDGE_VOICE_DEFAULT);
  // EAGER: Piper-Modell (Kerstin) beim App-Start laden, Fortschritt als Banner sichtbar
  const piperModel = usePiperModel(true);
  // Fehler-Banner: schließbar und blendet sich nach ~10 s von selbst aus (neuer Fehler -> wieder sichtbar)
  const [piperErrorHidden, setPiperErrorHidden] = useState(false);
  useEffect(() => {
    if (piperModel.error == null) { return undefined; }
    setPiperErrorHidden(false);
    const t = setTimeout(() => setPiperErrorHidden(true), 10_000);
    return () => clearTimeout(t);
  }, [piperModel.error]);

  useEffect(() => {
    let alive = true;
    // Anfangswert der Spiegel; maßgeblich ist das frische Lesen in speakViaKokoro
    getTtsEngine().then(e => { if (alive) { ttsEngineRef.current = e; } }).catch(() => {});
    getEdgeVoice().then(v => { if (alive) { edgeVoiceRef.current = v; } }).catch(() => {});
    return () => { alive = false; };
  }, []);

  // TB4: Der frühere „Icon lange drücken"-Stimmwechsel (toggleEdgeVoice) wurde entfernt — die Stimme
  // wird jetzt serverseitig in der Einrichtung gewählt (TtsVoiceSettings -> /settings/tts). Die Edge-Stimme
  // (edgeVoiceRef, frisch in speakViaKokoro gelesen) dient weiter als automatischer Offline-Fallback.

  // ─── TTS-Toggle (DONNA-36) ───────────────────────────────────────────────────
  // Persistiert in AsyncStorage, default: true
  const [ttsEnabled, setTtsEnabledState] = useState(true);

  // Beim Mounten aus AsyncStorage laden
  useEffect(() => {
    AsyncStorage.getItem(TTS_ENABLED_KEY).then(val => {
      if (val !== null) setTtsEnabledState(val === 'true');
    }).catch(() => {});
  }, []);

  const setTtsEnabled = useCallback((enabled: boolean) => {
    setTtsEnabledState(enabled);
    AsyncStorage.setItem(TTS_ENABLED_KEY, String(enabled)).catch(() => {});
    if (!enabled) {
      stopAllTTS();   // inkl. Timer, geplantem Relisten und Stimm-Lauschen
    }
  }, [stopAllTTS]);

  // TTS: Donna-Antwort vorlesen wenn letzter Input per Sprache war (DONNA-36: Kokoro primär)
  // Im Gesprächsmodus: Mic nach TTS automatisch wieder starten
  const lastInputWasVoiceRef = useRef(false);
  const prevMessageCountRef = useRef(0);
  // Early-TTS-Guard: verhindert Doppel-TTS wenn erster Satz schon während Stream gesprochen wurde
  const earlyTTSFiredRef = useRef<string | null>(null);

  // speakViaKokoro: TTS-Kette des Haupt-Chats (Name bleibt für Abwärtskompatibilität / Tests)
  // DONNA-38: Kotlin-Signaturen sind (text, [voice,] Promise) — KEINE Callback-Funktionen
  // übergeben (Hermes wirft "JS Functions are not convertible to dynamic")
  // Voice-Batch (A/B): gewählte Engine (Edge-TTS über Backend-/tts ODER Piper-lokal, Kerstin) -> die jeweils
  // andere Engine -> System-TTS (Samsung Neural). Reihenfolge/Logik: utils/ttsChain.ts.
  // Nach einer Unterbrechung (ttsEpoch) startet keine weitere Stufe mehr.
  const speakViaKokoro = useCallback((text: string) => {
    if (!ttsEnabled || !text?.trim()) return;
    markTtsActive(text);
    lastSpokenRef.current = appendSpoken(lastSpokenRef.current, text);
    markLatency('tts_start');
    const epoch = ttsEpochRef.current;

    // Engine und Stimme bei JEDEM Sprechen frisch lesen: Die Einstellung kann im Setup-Overlay über dem (gemounteten)
    // Chat geändert worden sein — sonst wirkte der A/B-Umschalter erst nach einem Neustart. Die Refs sind nur
    // Spiegel/Fallback (Lesefehler -> letzter bekannter Wert).
    Promise.all([getTtsEngine(), getEdgeVoice()])
      .then(([engine, voice]) => {
        ttsEngineRef.current = engine;
        edgeVoiceRef.current = voice;
        return speakWithFallback(
          text,
          engine,
          {PiperTTS: NativeModules.PiperTTS, TTSModule: NativeModules.TTSModule},
          {voice, isStale: () => epoch !== ttsEpochRef.current},
        );
      })
      .then(via => {
        if (via === 'none') {
          console.warn('[TTS] keine Sprachausgabe verfügbar (Edge, Piper und System-TTS fehlgeschlagen)');
        } else if (via !== 'stale') {
          measureLatency('tts_start', 'tts_end');
        }
      })
      .catch((e: unknown) => console.warn('[TTS] Kette fehlgeschlagen:', e));
  }, [ttsEnabled, markTtsActive]);
  // Der Sequencer ruft stets die AKTUELLE speakViaKokoro-Instanz (Engine/Stimme werden darin frisch gelesen).
  speakViaKokoroRef.current = speakViaKokoro;

  // DONNA-153: Early TTS — ersten vollständigen Satz sofort sprechen
  // Restliche Sätze werden nach und nach gesprochen wenn Streaming-Chunks ankommen
  // TODO: speakNextSentence in Streaming-Loop einbauen wenn Backend Chunk-Events liefert
  const speakNextSentence = (buffer: string): string => {
    const match = buffer.match(/^([^.!?\n]+[.!?\n])\s*/);
    if (match) {
      speakViaKokoro(match[1].trim());
      return buffer.slice(match[0].length);
    }
    return buffer;
  };

  // DONNA-115: Ideen-Karte anzeigen wenn neue Assistent-Nachricht ideaConfirm/ideaUpdate enthält
  useEffect(() => {
    if (messages.length === 0) return;
    const last = messages[messages.length - 1];
    if (last?.role === 'assistant') {
      if (last.ideaConfirm) {
        setPendingIdeaConfirm(last.ideaConfirm);
        setPendingIdeaUpdate(null);
      } else if (last.ideaUpdate) {
        setPendingIdeaUpdate(last.ideaUpdate);
        setPendingIdeaConfirm(null);
      }
    }
  }, [messages]);

  useEffect(() => {
    if (messages.length > prevMessageCountRef.current) {
      prevMessageCountRef.current = messages.length;
      const last = messages[messages.length - 1];
      if (last?.role === 'assistant' && last.content && lastInputWasVoiceRef.current) {
        // DONNA-Voice First-Sentence-Skip-Fix: Erster Satz + Rest laufen über EINEN idempotenten ttsSequencer,
        // der sie strikt nacheinander spielt (Rest erst nach dem echten Ende des ersten Satzes). Hat der
        // useChat-Hook schon den frühen ersten Satz gesprochen (earlyFirstSeenRef), reihen wir hier nur den
        // Rest ein — der Sequencer ignoriert ihn, falls der Hook den Rest bereits eingereiht hat (keine zwei
        // konkurrierenden Aufrufe mehr). Wurde früh NICHTS gesprochen (z. B. kurze Antwort), sprechen wir die
        // ganze Antwort als „ersten“ Chunk.
        const firstText = earlyTTSFiredRef.current;
        earlyTTSFiredRef.current = null;
        lastInputWasVoiceRef.current = false;
        // R1-Guard: firstText == null -> für DIESE Nachricht lief KEIN Early-TTS (z. B. proaktive Nachricht, die
        // nach einem Auto-Relisten mit lastInputWasVoiceRef=true ohne frischen Turn-Start/interruptSpeech reinkommt).
        // Dann tragen earlyFirstSeenRef/der Sequencer noch STALE Flags des Vor-Turns -> ohne Reset würde der Rest
        // idempotent verschluckt und die Nachricht bliebe stumm. Also frisch zurücksetzen und ganz sprechen.
        if (!earlyFirstSeenRef.current || firstText == null) {
          ttsSequencerRef.current?.clear();
          earlyFirstSeenRef.current = false;
          routeEarlyTts(last.content);
        } else {
          const remaining = last.content.startsWith(firstText)
            ? last.content.slice(firstText.length).trimStart()
            : last.content;
          if (remaining.length > 0) { ttsSequencerRef.current?.queueRemaining(remaining); }
        }

        // Gesprächsmodus (Freisprechen): sobald die Sprachausgabe WIRKLICH endet ("ended"-Ereignis des nativen
        // TTS), öffnet das Mikrofon von selbst (kurzer Echo-Schutz, siehe scheduleAutoRelisten).
        if (conversationModeRef.current && ttsEnabled) {
          relistenPendingRef.current = true;
          // Sicherheitsnetz, falls ein TTS-Pfad kein Ende meldet: großzügige Dauer-Schätzung (nie vor dem Ende).
          // Obergrenze = gesamter Antworttext (erster Satz + Rest werden nacheinander gesprochen).
          const safetyMs = Math.max(6000, estimateTtsMs('x'.repeat(last.content.length)) * 1.6 + 3000);
          if (ttsTimerRef.current) clearTimeout(ttsTimerRef.current);
          ttsTimerRef.current = setTimeout(() => {
            ttsTimerRef.current = null;
            if (relistenPendingRef.current) {
              relistenPendingRef.current = false;
              scheduleAutoRelisten();
            }
          }, safetyMs);
        }
      }
    }
  }, [messages, startListening, speakViaKokoro, ttsEnabled, scheduleAutoRelisten]);

  // Cleanup Timer bei Unmount
  useEffect(() => {
    return () => {
      if (ttsTimerRef.current) clearTimeout(ttsTimerRef.current);
      if (ttsActiveTimerRef.current) clearTimeout(ttsActiveTimerRef.current);
      if (relistenDelayTimerRef.current) clearTimeout(relistenDelayTimerRef.current);
      try { NativeModules.BargeInModule?.stop?.(); } catch { /* ignorieren */ }
    };
  }, []);

  // Keyboard-Höhe manuell tracken (zuverlässiger als KAV auf Android edge-to-edge)
  // endCoordinates.height = volle Tastaturhöhe inkl. Gesten-Bar -> direkt als Padding nutzen
  const [kbHeight, setKbHeight] = useState(0);
  // Refs für das Auto-Relisten: beim Tippen/offener Tastatur nicht automatisch zuhören
  useEffect(() => { isListeningRef.current = isListening; }, [isListening]);
  useEffect(() => { typingActiveRef.current = kbHeight > 0 || inputText.trim().length > 0; }, [kbHeight, inputText]);
  useEffect(() => {
    cardOpenRef.current =
      pendingAction != null || navChoice != null || contactFallback != null ||
      pendingIdeaConfirm != null || pendingIdeaUpdate != null;
  }, [pendingAction, navChoice, contactFallback, pendingIdeaConfirm, pendingIdeaUpdate]);

  // Barge-in-Sitzung ohne anschließende Äußerung (Stille/Fehler/Timeout) = Fehlauslöser -> Strike
  useEffect(() => {
    if (!isListening && voiceBargeInSessionRef.current) { endBargeInSession(false); }
  }, [isListening, endBargeInSession]);
  useEffect(() => {
    const show = Keyboard.addListener('keyboardDidShow', e => {
      // endCoordinates.height enthält NICHT die Gesten-Navigationsleiste (insets.bottom)
      // -> beide addieren damit die InputRow über Tastatur UND Gesten-Bar liegt
      setKbHeight(e.endCoordinates.height + insets.bottom);
    });
    const hide = Keyboard.addListener('keyboardDidHide', () => {
      setKbHeight(0);
      // #134 C: Tastatur schliesst ohne Text -> Tipp-Vorrang endet (grosse Leiste / Gespraechsansicht kehren zurueck)
      if (typingPriorityEnds({listeningStarted: false, keyboardHidden: true, hasText: inputTextRef.current.trim().length > 0})) {
        setTypingOpen(false);
      }
    });
    return () => { show.remove(); hide.remove(); };
  }, [insets.bottom]);

  inputTextRef.current = inputText;
  // #134 C: wird wieder gehoert (Auto-Weiterhoeren, Umschalter, Mikrofon), endet der Tipp-Vorrang
  useEffect(() => {
    if (typingPriorityEnds({listeningStarted: isListening, keyboardHidden: false, hasText: false})) {
      setTypingOpen(false);
    }
  }, [isListening]);
  const cardOpen = navChoice != null || contactFallback != null || pendingAction != null
    || pendingIdeaConfirm != null || pendingIdeaUpdate != null;
  // #134 P5: Gespraechsansicht als Darstellung ueber dem vorhandenen Gespraechszustand (keine neue Logik)
  const viewInputs = {
    hasMessages,
    conversationMode,
    micAvailable,
    isListening,
    isLoading,
    ttsActive,
    voiceTurn,
    typingOverride: typingOpen,
    cardOpen,
  };
  const convActive = selectChatView(viewInputs) === 'conversation';
  const convHeld = useHeldTrue(convActive, CONVERSATION_VIEW_GRACE_MS);
  const viewKind = selectChatView({...viewInputs, hold: convHeld});
  const showConversation = viewKind === 'conversation';
  const lastOf = (role: 'user' | 'assistant') => [...messages].reverse().find(m => m.role === role)?.content ?? '';
  const transcript = conversationTranscript({
    state: orbState,
    partial: partialShown,
    lastUserText: lastOf('user'),
    lastAssistantText: lastOf('assistant'),
    lastMessageRole: messages.length > 0 ? (messages[messages.length - 1].role as 'user' | 'assistant') : null,
  });
  const barState = {hasMessages, typingOpen, canInterrupt, isListening};

  return (
    <View style={[styles.container, {paddingBottom: kbHeight}]}>
      {/* #134 P5: Gespraechsansicht liegt ueber dem unveraenderten Chat (der bleibt gemountet, DONNA-38) */}
      {showConversation ? (
        <ConversationView
          topInset={insets.top}
          bottomInset={insets.bottom}
          orbState={orbState}
          listening={isListening}
          interrupting={canInterrupt}
          onOrbPress={() => { handleVoicePress().catch(() => {}); }}
          speaker={transcript.speaker}
          transcript={transcript.text}
          onTyping={() => { resetVoiceTurn(); openTyping(); cancelListeningAny().catch(() => {}); }}
          onEnd={() => { toggleConversationMode(); handleStop(); cancelListeningAny().catch(() => {}); }}
        />
      ) : null}
      <View style={styles.content} importantForAccessibility={showConversation ? 'no-hide-descendants' : 'auto'}>
      <MessageActionSheet
        state={actionSheet}
        onClose={() => setActionSheet(s => ({...s, visible: false}))}
      />

      <HistoryPanel
        visible={historyVisible}
        onClose={() => setHistoryVisible(false)}
        onLoadSession={(msgs) => {
          // SessionMessage -> ChatMessage konvertieren und in Chat laden
          const chatMsgs: ChatMessage[] = msgs.map(m => ({role: m.role, content: m.content}));
          loadMessages(chatMsgs);

          // Action-Karten-Wiederherstellung: letzte Assistenten-Nachricht auf Action prüfen.
          // Das Backend hat DONNA_ACTION-Marker vor dem STM-Speichern bereits gestriped,
          // daher nutzen wir eine Text-Heuristik (analog zu _heuristic_actions() im Backend).
          const lastAssistant = [...chatMsgs].reverse().find(m => m.role === 'assistant');
          if (lastAssistant?.content) {
            const action = _detectActionFromText(lastAssistant.content);
            if (action) {
              // Micro-task: nach loadMessages State setzen damit kein Race
              setTimeout(() => handleDonnaAction(action), 0);
            }
          }
        }}
      />

      {/* Header */}
      <ChatHeader
        variant={hasMessages ? 'chat' : 'idle'}
        orbState={orbState}
        orbActive={!showConversation}
        topInset={insets.top}
        ttsEnabled={ttsEnabled}
        onToggleTts={() => setTtsEnabled(!ttsEnabled)}
        showConversationToggle={headerShowsConversationToggle({...barState, micAvailable})}
        conversationMode={conversationMode}
        onToggleConversation={toggleConversationMode}
        onToggleBargeIn={toggleVoiceBargeIn}
        onOpenHistory={() => setHistoryVisible(true)}
        onOpenSetup={onOpenSetup}
        setupWarning={setupWarning}
        showNewChat={messages.length > 0}
        onNewChat={() => { setTypingOpen(false); clearMessages(); }}
      />

      {/* Voice-Batch: Piper-Stimme (Kerstin) — eager geladen, Fortschritt sichtbar; Edge springt solange ein */}
      {(piperModel.status === 'downloading' ||
        (piperModel.status === 'not_downloaded' && piperModel.error != null && !piperErrorHidden)) && (
        <View style={styles.phiBanner} testID="piper-banner">
          <Text style={styles.phiBannerText}>
            {piperModel.status === 'downloading'
              ? `Piper wird geladen (${piperModel.progress} %) — bis dahin spricht Edge`
              : 'Piper konnte nicht geladen werden — Edge spricht weiter'}
          </Text>
          {piperModel.status === 'not_downloaded' && (
            <>
              <TouchableOpacity onPress={piperModel.retry} style={styles.phiBannerBtn} accessibilityRole="button">
                <Text style={styles.phiBannerBtnText}>Erneut versuchen</Text>
              </TouchableOpacity>
              <TouchableOpacity
                onPress={() => setPiperErrorHidden(true)}
                style={styles.phiBannerBtn}
                accessibilityRole="button"
                accessibilityLabel="Hinweis schließen">
                <Icon name="close" size={16} color={P.text as string} />
              </TouchableOpacity>
            </>
          )}
        </View>
      )}

      {/* DONNA-189: Phi-3 Mini Download-Banner */}
      {(phiModelStatus === 'NOT_DOWNLOADED' || phiModelStatus === 'DOWNLOADING') && (
        <View style={styles.phiBanner}>
          <Text style={styles.phiBannerText}>
            {phiModelStatus === 'DOWNLOADING'
              ? `Phi-3 Mini wird heruntergeladen... (${phiDownloadPercent}%)`
              : 'Phi-3 Mini noch nicht geladen — On-Device-KI nicht verfügbar'}
          </Text>
          {phiModelStatus === 'NOT_DOWNLOADED' && (
            <TouchableOpacity
              onPress={() => {
                PhiModule.startModelDownload().then(() => setPhiModelStatus('DOWNLOADING')).catch(() => {});
              }}
              style={styles.phiBannerBtn}>
              <Text style={styles.phiBannerBtnText}>Herunterladen</Text>
            </TouchableOpacity>
          )}
        </View>
      )}

      {/* Messages or Idle */}
      {!hasMessages ? (
        <IdleScreen
          greeting={greetingForHour(new Date().getHours())}
          orbState={orbState}
          orbActive={!showConversation}
          suggestions={IDLE_SUGGESTIONS}
          onSuggestion={text => { sendText(text).catch(() => {}); }}
        />
      ) : (
        <FlatList
          ref={flatListRef}
          data={messages}
          keyExtractor={(item, index) =>
            `${item.role}-${index}-${item.content.slice(0, 8)}`
          }
          renderItem={({item, index}) => {
            // DONNA-139: Vorangehende User-Message für LTM-Feedback ermitteln
            const prevUserMsg = item.role === 'assistant'
              ? messages.slice(0, index).reverse().find(m => m.role === 'user')?.content
              : undefined;
            return (
              <MessageBubble
                message={item}
                sessionId={sessionId}
                onAction={handleDonnaAction}
                userMessage={prevUserMsg}
                onLongPress={(content, isAssistant) =>
                  setActionSheet({
                    visible: true,
                    content,
                    isAssistant,
                    replyFn: isAssistant ? () => setReplyTo(content) : undefined,
                  })
                }
              />
            );
          }}
          contentContainerStyle={styles.messageList}
          onContentSizeChange={() =>
            flatListRef.current?.scrollToEnd({animated: true})
          }
        />
      )}

      {/* Listening state: orb + waveform + partial transcript
          DONNA-38: NICHT conditional unmount — Animated-Loops würden sonst
          ihren View-Tag verlieren (FATAL EXCEPTION: disconnectAnimatedNodeFromView).
          Stattdessen opacity-toggle, Component bleibt gemounted. */}
      <View
        style={[styles.listeningPanel, { opacity: isListening ? 1 : 0, height: isListening ? undefined : 0, overflow: 'hidden' }]}
        pointerEvents={isListening ? 'auto' : 'none'}
      >
        <View style={!hasMessages ? styles.hidden : undefined}>
          <ChatOrb state={isListening ? 'listening' : 'idle'} silent active={isListening && hasMessages && !showConversation} />
        </View>
        <Waveform active={isListening} />
        {partialShown ? (
          <Text style={styles.partialText}>{partialShown}</Text>
        ) : (
          <Text style={styles.listeningHint}>Ich höre zu…</Text>
        )}
      </View>

      {/* Typing indicator — bleibt gemounted, opacity-toggle */}
      <View
        style={[styles.typingWrap, { opacity: (isLoading && !isListening) ? 1 : 0, height: (isLoading && !isListening) ? undefined : 0, overflow: 'hidden' }]}
        pointerEvents="none"
      >
        <TypingIndicator />
        {rateLimitStatus ? (
          <Text style={styles.rateLimitText}>{rateLimitStatus}</Text>
        ) : null}
      </View>

      {/* Reply-Vorschau */}
      {replyTo && (
        <ReplyPreview text={replyTo} onCancel={() => setReplyTo(null)} />
      )}

      {/* DONNA-Welle4: Bixby-Stil Bestätigungskarte mit Detail-Feldern */}
      {navChoice != null && navChoice.type === NAV_DISAMBIGUATE && navOptions.length > 0 ? (
        <NavDisambiguateCard
          query={str(navChoice.query)}
          options={navOptions}
          navType={navTypeOf(navChoice)}
          onPick={handleNavPick}
          onCancel={cancelPendingAction}
        />
      ) : navChoice != null ? (
        // not_found ODER disambiguate ohne gueltige Optionen -> freundliche Meldung statt leerer Auswahl
        <NavNotFoundCard message={notFoundText(navChoice)} onClose={cancelPendingAction} />
      ) : contactFallback != null ? (
        <ContactFallbackCard
          originalName={contactFallback.originalName}
          suggestions={contactFallback.suggestions}
          onSelect={(contact) => {
            setContactFallback(null);
            setPendingAction({...contactFallback.action, name: contact.name} as DonnaAction);
            setPendingContact(contact);
          }}
          onRetry={handleRetryWithName}
          onCancel={() => setContactFallback(null)}
        />
      ) : pendingAction != null && contactCandidates.length > 1 ? (
        <ContactPickerCard
          query={(pendingAction.name as string) ?? ''}
          matches={contactCandidates}
          onPick={handlePickContact}
          onCancel={cancelPendingAction}
        />
      ) : pendingAction != null ? (
        <PendingActionCard
          action={pendingAction}
          contact={pendingContact}
          onConfirm={confirmPendingAction}
          onCancel={cancelPendingAction}
        />
      ) : null}

      {/* DONNA-115: Ideen-Bestätigungs-Karten */}
      {pendingIdeaConfirm != null ? (
        <IdeaConfirmCard
          idea={pendingIdeaConfirm}
          onConfirm={handleIdeaConfirm}
          onReject={handleIdeaReject}
        />
      ) : pendingIdeaUpdate != null ? (
        <IdeaUpdateCard
          idea={pendingIdeaUpdate}
          onConfirm={handleIdeaUpdateConfirm}
          onReject={handleIdeaUpdateReject}
        />
      ) : null}

      {/* Input row: kein Nav-Bar-Padding wenn Tastatur offen (Tastatur deckt es ab) */}
      {!idleBarVisible(barState) ? (
      <ChatInputRow
        micAvailable={micAvailable}
        isListening={isListening}
        canInterrupt={canInterrupt}
        focusSignal={focusTick}
        autoFocus={typingOpen}
        onStop={handleStop}
        onVoicePress={() => { setTypingOpen(false); handleVoicePress().catch(() => {}); }}
        value={inputText}
        onChangeText={handleInputChange}
        onSend={handleSend}
        onFocus={() => { if (isListening) { cancelListeningAny().catch(() => {}); } }}
        bottomInset={kbHeight > 0 ? 0 : insets.bottom}
      />
      ) : (
        <IdleActionBar
          micAvailable={micAvailable}
          isListening={isListening}
          canInterrupt={canInterrupt}
          onTyping={openTyping}
          onVoicePress={() => { handleVoicePress().catch(() => {}); }}
          conversationMode={conversationMode}
          onToggleConversation={toggleConversationMode}
          onToggleBargeIn={toggleVoiceBargeIn}
          bottomInset={insets.bottom}
        />
      )}
      </View>
    </View>
  );
}

const makeStyles = (P: Theme) => StyleSheet.create({
  container: {flex: 1, backgroundColor: P.bg},
  // Inhalt unter der Gespraechsansicht: fuer TalkBack ausgeblendet, solange sie darueber liegt
  content: {flex: 1},

  // DONNA-189: Phi-3 Mini Download-Banner
  phiBanner: {
    backgroundColor: P.accentSoft,
    borderBottomWidth: 1,
    borderBottomColor: P.accentBorder,
    paddingHorizontal: SPACE.lg,
    paddingVertical: SPACE.sm,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  phiBannerText: {
    color: P.accent2,
    fontSize: FONT.caption,
    flex: 1,
  },
  phiBannerBtn: {
    marginLeft: SPACE.md,
    backgroundColor: P.accent,
    borderRadius: RADIUS.sm,
    paddingHorizontal: SPACE.md,
    minHeight: TOUCH_MIN,
    justifyContent: 'center',
  },
  phiBannerBtnText: {
    color: P.onAccent,
    fontSize: FONT.caption,
    fontWeight: '600',
  },

  // Messages
  messageList: {
    padding: SPACE.lg,
    paddingBottom: SPACE.sm,
    flexGrow: 1,
  },

  // Im Leerzustand zeigt schon der grosse Orb den Zustand: der Orb im Hoerpanel bleibt gemountet (DONNA-38), aber unsichtbar
  hidden: {display: 'none'},

  // Listening panel
  listeningPanel: {
    backgroundColor: P.surface,
    borderTopWidth: 1,
    borderTopColor: P.border,
    paddingBottom: SPACE.sm,
  },
  listeningHint: {
    color: P.muted,
    fontSize: FONT.label,
    textAlign: 'center',
    paddingBottom: SPACE.sm,
    fontStyle: 'italic',
  },
  partialText: {
    color: P.accent2,
    fontSize: FONT.body,
    textAlign: 'center',
    paddingHorizontal: SPACE.lg,
    paddingBottom: SPACE.sm,
    fontStyle: 'italic',
  },

  // Typing
  typingWrap: {
    paddingHorizontal: SPACE.lg,
    paddingTop: SPACE.xs,
  },
  rateLimitText: {
    fontSize: FONT.caption,
    color: P.muted,
    fontStyle: 'italic',
    marginTop: 2,
    marginLeft: 4,
  },

});

import React, {useState, useRef, useCallback, useEffect} from 'react';
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
  Platform,
  Share,
} from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import {useSafeAreaInsets} from 'react-native-safe-area-context';
import {useChat, useVoice, fetchSessions, fetchSessionMessages, sendFeedback, fuzzyScore, getApiToken} from '@donna/shared';
import type {ChatMessage, WeatherCardData, MapCardData, SessionInfo, SessionMessage, DonnaAction, IdeaConfirmPayload, IdeaUpdatePayload} from '@donna/shared';
import {AndroidVoiceModule} from '../native/AndroidVoiceModule';
import {searchContactsByName, type ContactMatch} from '../native/ContactsBridge';
import {voiceTriggerRef, proactiveChatRef, pendingProactiveMessage, clearPendingProactiveMessage} from '../App';
import type {ProactiveChatPayload} from '../App';
import {IdeaConfirmCard, IdeaUpdateCard} from './IdeaConfirmCard';
import {OfflineSTT} from '../modules/OfflineSTT';
import {PhiModule} from '../modules/PhiModule';
import {routedGenerate, invalidateOnDeviceCache} from '../utils/llmRouter';
import {markLatency, measureLatency, clearLatencyMarks} from '../utils/latencyBenchmark';

const {AlarmModule} = NativeModules;

// ─── TTS-Konstante ────────────────────────────────────────────────────────────
const TTS_ENABLED_KEY = 'donna_tts_enabled';

// ─── Pulse Theme Tokens ───────────────────────────────────────────────────────
const P = {
  bg: '#03090f',
  surface: '#080f1a',
  card: '#0d1626',
  border: 'rgba(56,189,248,0.12)',
  accent: '#38bdf8',
  accent2: '#7dd3fc',
  glow: 'rgba(56,189,248,0.4)',
  text: '#e0f2fe',
  muted: 'rgba(224,242,254,0.45)',
  userBubble: '#0c2a3e',
  userBorder: 'rgba(56,189,248,0.35)',
  donnaBubble: '#0d1626',
  donnaBorder: 'rgba(56,189,248,0.12)',
};

// ─── DonnaAvatar (Hexagon-ish with D) ────────────────────────────────────────
function DonnaAvatar({size = 32}: {size?: number}): React.JSX.Element {
  return (
    <View style={[avatarStyles.wrap, {width: size, height: size, borderRadius: size * 0.22}]}>
      <Text style={[avatarStyles.letter, {fontSize: size * 0.48}]}>D</Text>
    </View>
  );
}
const avatarStyles = StyleSheet.create({
  wrap: {
    backgroundColor: P.accent,
    justifyContent: 'center',
    alignItems: 'center',
  },
  letter: {
    color: P.bg,
    fontWeight: '700',
  },
});

// ─── DonnaOrb (animated pulsing sphere) ──────────────────────────────────────
function DonnaOrb({listening}: {listening: boolean}): React.JSX.Element {
  const pulse1 = useRef(new Animated.Value(1)).current;
  const pulse2 = useRef(new Animated.Value(1)).current;
  const glow   = useRef(new Animated.Value(0.5)).current;

  // All animations use useNativeDriver:false — mixing native+non-native on the
  // same Animated.View crashes RN. Opacity and scale on same view = conflict.
  useEffect(() => {
    if (listening) {
      const anim1 = Animated.loop(
        Animated.sequence([
          Animated.timing(pulse1, {toValue: 1.18, duration: 600, easing: Easing.inOut(Easing.ease), useNativeDriver: false}),
          Animated.timing(pulse1, {toValue: 1,    duration: 600, easing: Easing.inOut(Easing.ease), useNativeDriver: false}),
        ]),
      );
      const anim2 = Animated.loop(
        Animated.sequence([
          Animated.timing(pulse2, {toValue: 1.32, duration: 900, easing: Easing.inOut(Easing.ease), useNativeDriver: false}),
          Animated.timing(pulse2, {toValue: 1,    duration: 900, easing: Easing.inOut(Easing.ease), useNativeDriver: false}),
        ]),
      );
      const anim3 = Animated.loop(
        Animated.sequence([
          Animated.timing(glow, {toValue: 1,   duration: 800, useNativeDriver: false}),
          Animated.timing(glow, {toValue: 0.4, duration: 800, useNativeDriver: false}),
        ]),
      );
      anim1.start();
      anim2.start();
      anim3.start();
      // DONNA-38: Cleanup verhindert "Animated node does not exist"-Crash beim
      // abrupten Unmount (isListening → false wechselt während Loop läuft)
      return () => {
        anim1.stop();
        anim2.stop();
        anim3.stop();
        pulse1.stopAnimation();
        pulse2.stopAnimation();
        glow.stopAnimation();
      };
    } else {
      pulse1.stopAnimation();
      pulse2.stopAnimation();
      glow.stopAnimation();
      Animated.timing(pulse1, {toValue: 1,   duration: 300, useNativeDriver: false}).start();
      Animated.timing(pulse2, {toValue: 1,   duration: 300, useNativeDriver: false}).start();
      Animated.timing(glow,   {toValue: 0.5, duration: 300, useNativeDriver: false}).start();
    }
    return undefined;
  }, [listening, pulse1, pulse2, glow]);

  const glowOpacity = glow.interpolate({inputRange: [0, 1], outputRange: [0.25, 0.6]});

  return (
    <View style={orbStyles.container}>
      {/* Outer glow ring — wrap opacity+scale in separate views, same driver */}
      <Animated.View style={[orbStyles.ring2Wrapper, {opacity: glowOpacity, transform: [{scale: pulse2}]}]} />
      {/* Inner ring */}
      <Animated.View style={[orbStyles.ring1, {transform: [{scale: pulse1}]}]} />
      {/* Core orb */}
      <View style={orbStyles.core}>
        <DonnaAvatar size={48} />
      </View>
    </View>
  );
}
const orbStyles = StyleSheet.create({
  container: {
    width: 120,
    height: 120,
    justifyContent: 'center',
    alignItems: 'center',
    alignSelf: 'center',
    marginVertical: 12,
  },
  ring2Wrapper: {
    position: 'absolute',
    width: 110,
    height: 110,
    borderRadius: 55,
    backgroundColor: 'rgba(56,189,248,0.08)',
    borderWidth: 1,
    borderColor: 'rgba(56,189,248,0.15)',
  },
  ring1: {
    position: 'absolute',
    width: 88,
    height: 88,
    borderRadius: 44,
    backgroundColor: 'rgba(56,189,248,0.12)',
    borderWidth: 1,
    borderColor: 'rgba(56,189,248,0.3)',
  },
  core: {
    width: 64,
    height: 64,
    borderRadius: 32,
    backgroundColor: P.card,
    borderWidth: 1,
    borderColor: P.accent,
    justifyContent: 'center',
    alignItems: 'center',
  },
});

// ─── Waveform (28 animated bars) ─────────────────────────────────────────────
const BAR_COUNT = 28;
function Waveform({active}: {active: boolean}): React.JSX.Element {
  const bars = useRef(
    Array.from({length: BAR_COUNT}, () => new Animated.Value(0.15)),
  ).current;

  useEffect(() => {
    if (!active) {
      bars.forEach(b => Animated.timing(b, {toValue: 0.15, duration: 200, useNativeDriver: false}).start());
      return;
    }
    const animations = bars.map((b, i) => {
      const duration = 300 + ((i * 37) % 400);
      return Animated.loop(
        Animated.sequence([
          Animated.timing(b, {toValue: 0.15 + Math.random() * 0.8, duration, useNativeDriver: false, easing: Easing.inOut(Easing.ease)}),
          Animated.timing(b, {toValue: 0.1  + Math.random() * 0.3, duration, useNativeDriver: false, easing: Easing.inOut(Easing.ease)}),
        ]),
      );
    });
    animations.forEach(a => a.start());
    return () => animations.forEach(a => a.stop());
  }, [active, bars]);

  return (
    <View style={waveStyles.row}>
      {bars.map((b, i) => (
        <Animated.View
          key={i}
          style={[
            waveStyles.bar,
            {
              height: b.interpolate({inputRange: [0, 1], outputRange: [4, 40]}),
              opacity: active ? b.interpolate({inputRange: [0, 1], outputRange: [0.4, 1]}) : 0.25,
            },
          ]}
        />
      ))}
    </View>
  );
}
const waveStyles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    height: 48,
    gap: 3,
    paddingHorizontal: 16,
    marginVertical: 8,
  },
  bar: {
    width: 3,
    borderRadius: 2,
    backgroundColor: P.accent,
  },
});

// ─── WeatherCard ──────────────────────────────────────────────────────────────
interface HourlyEntry {time: string; temp_c: number; icon: string; precip_pct: number}

function WeatherCard({data}: {data: WeatherCardData & {hourly?: HourlyEntry[]}}): React.JSX.Element {
  const hourly: HourlyEntry[] = (data as any).hourly ?? [];
  // Aktuelle Stunde → passenden Hourly-Slot markieren
  const nowH = new Date().getHours();
  const currentIdx = hourly.reduce((best, h, i) => {
    const hh = parseInt(h.time.split(' ')[0], 10);
    return hh <= nowH ? i : best;
  }, 0);
  return (
    <View style={weatherStyles.card}>
      {/* Kopfzeile: Ort + Wochentag */}
      <Text style={weatherStyles.location}>
        {new Date().toLocaleDateString('de-DE', {weekday: 'long'})} · {data.location}
      </Text>
      {/* Temperatur + Icon */}
      <View style={weatherStyles.topRow}>
        <View style={{flex: 1}}>
          <Text style={weatherStyles.temp}>{data.temp_c}°</Text>
          <Text style={weatherStyles.condition}>{data.condition}</Text>
          <Text style={weatherStyles.subInfo}>
            Höchst: {data.temp_max}°  Tief: {data.temp_min}°  Niederschlag: {
              hourly.length > 0
                ? Math.round(hourly.reduce((s, h) => s + h.precip_pct, 0) / hourly.length)
                : 0
            } %
          </Text>
        </View>
        <Text style={weatherStyles.icon}>{data.condition_icon}</Text>
      </View>
      {/* Stundenvorschau (wie Gemini) */}
      {hourly.length > 0 && (
        <>
          <View style={weatherStyles.divider} />
          <ScrollView horizontal showsHorizontalScrollIndicator={false} style={weatherStyles.hourlyScroll}>
            {hourly.map((h, i) => {
              const isCurrent = i === currentIdx;
              return (
                <View key={i} style={[
                  weatherStyles.hourlyItem,
                  isCurrent && weatherStyles.hourlyItemCurrent,
                ]}>
                  <Text style={[weatherStyles.hourlyTime, isCurrent && {color: P.accent}]}>
                    {isCurrent ? '▶ ' : ''}{h.time}
                  </Text>
                  <Text style={weatherStyles.hourlyIcon}>{h.icon}</Text>
                  <Text style={weatherStyles.hourlyPrecip}>{h.precip_pct} %</Text>
                  <Text style={[weatherStyles.hourlyTemp, isCurrent && {color: P.accent, fontWeight: '700'}]}>
                    {h.temp_c}°
                  </Text>
                </View>
              );
            })}
          </ScrollView>
        </>
      )}
      {/* Details: Feuchtigkeit + Wind */}
      <View style={weatherStyles.detailRow}>
        <Text style={weatherStyles.detail}>💧 {data.humidity}%</Text>
        <Text style={weatherStyles.detailSep}>·</Text>
        <Text style={weatherStyles.detail}>💨 {data.wind_kmh} km/h</Text>
        <Text style={weatherStyles.detailSep}>·</Text>
        <Text style={weatherStyles.detail}>Gefühlt {data.feels_like_c}°</Text>
      </View>
    </View>
  );
}
const weatherStyles = StyleSheet.create({
  card: {
    backgroundColor: P.card,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: 'rgba(56,189,248,0.25)',
    padding: 16,
    marginBottom: 8,
  },
  location: {color: P.muted, fontSize: 12, letterSpacing: 0.5, marginBottom: 8},
  topRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    justifyContent: 'space-between',
    marginBottom: 12,
  },
  temp: {color: P.text, fontSize: 52, fontWeight: '200', lineHeight: 56},
  condition: {color: P.accent2, fontSize: 16, marginTop: 2},
  subInfo: {color: P.muted, fontSize: 11, marginTop: 6},
  icon: {fontSize: 52, lineHeight: 56},
  divider: {height: 1, backgroundColor: 'rgba(56,189,248,0.1)', marginBottom: 10},
  hourlyScroll: {marginBottom: 12},
  hourlyItem: {
    alignItems: 'center',
    marginRight: 20,
    minWidth: 52,
    paddingVertical: 6,
    paddingHorizontal: 4,
    borderRadius: 10,
  },
  hourlyItemCurrent: {
    backgroundColor: 'rgba(56,189,248,0.12)',
    borderWidth: 1,
    borderColor: 'rgba(56,189,248,0.4)',
  },
  hourlyTime: {color: P.muted, fontSize: 11, marginBottom: 4},
  hourlyIcon: {fontSize: 22, marginBottom: 2},
  hourlyPrecip: {color: P.accent2, fontSize: 11, marginBottom: 2},
  hourlyTemp: {color: P.text, fontSize: 14, fontWeight: '500'},
  detailRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 4,
    alignItems: 'center',
    borderTopWidth: 1,
    borderTopColor: 'rgba(56,189,248,0.1)',
    paddingTop: 10,
  },
  detail: {color: P.muted, fontSize: 12},
  detailSep: {color: 'rgba(56,189,248,0.2)', fontSize: 12},
});

// ─── MarkdownText — rendert **bold**, • bullets, URLs klickbar ───────────────
function MarkdownText({text, style: baseStyle}: {text: string; style?: object}): React.JSX.Element {
  const lines = text.split('\n');
  return (
    <View>
      {lines.map((line, li) => {
        if (!line.trim()) {return <View key={li} style={{height: 5}} />;}
        // Header ## / ###
        if (/^#{1,3} /.test(line)) {
          const content = line.replace(/^#{1,3} /, '');
          return <Text key={li} style={[mdSt.header, baseStyle]}>{content}</Text>;
        }
        // Bullet
        const isBullet = /^[\s]*[*•-] /.test(line);
        const content = isBullet ? '• ' + line.replace(/^[\s]*[*•-] /, '') : line;
        // Inline: **bold** + URLs
        const parts = content.split(/(\*\*[^*]+\*\*|https?:\/\/[^\s]+)/g);
        return (
          <Text key={li} style={[mdSt.base, isBullet && mdSt.bullet, baseStyle]}>
            {parts.map((part, pi) => {
              if (/^\*\*[^*]+\*\*$/.test(part)) {
                return <Text key={pi} style={mdSt.bold}>{part.slice(2, -2)}</Text>;
              }
              if (/^https?:\/\//.test(part)) {
                return (
                  <Text key={pi} style={mdSt.link} onPress={() => Linking.openURL(part)}>
                    {part}
                  </Text>
                );
              }
              return <Text key={pi}>{part.replace(/\*/g, '')}</Text>;
            })}
          </Text>
        );
      })}
    </View>
  );
}
const mdSt = StyleSheet.create({
  base: {fontSize: 15, lineHeight: 22, color: P.text, marginBottom: 1},
  bold: {fontWeight: '700', color: P.text},
  bullet: {marginBottom: 3},
  header: {fontSize: 16, fontWeight: '700', color: P.accent2, marginTop: 6, marginBottom: 3, lineHeight: 22},
  link: {color: P.accent, textDecorationLine: 'underline'},
});

// ─── MessageActionSheet — Pulse-styled bottom sheet ──────────────────────────
interface ActionSheetState {
  visible: boolean;
  content: string;
  isAssistant: boolean;
  replyFn?: () => void;
}
function MessageActionSheet({
  state,
  onClose,
}: {
  state: ActionSheetState;
  onClose: () => void;
}): React.JSX.Element {
  const handleCopy = () => {
    Share.share({message: state.content}).catch(() => {});
    onClose();
  };
  const handleReply = () => {
    state.replyFn?.();
    onClose();
  };
  return (
    <Modal visible={state.visible} transparent animationType="slide" onRequestClose={onClose}>
      <TouchableOpacity style={asStyles.overlay} activeOpacity={1} onPress={onClose}>
        <View style={asStyles.sheet}>
          {/* Handle */}
          <View style={asStyles.handle} />
          {/* Vorschau */}
          <Text style={asStyles.preview} numberOfLines={2}>{state.content}</Text>
          <View style={asStyles.divider} />
          {/* Aktionen */}
          <TouchableOpacity style={asStyles.item} onPress={handleCopy}>
            <Text style={asStyles.itemIcon}>📋</Text>
            <Text style={asStyles.itemText}>Kopieren</Text>
          </TouchableOpacity>
          {state.isAssistant && (
            <TouchableOpacity style={asStyles.item} onPress={handleReply}>
              <Text style={asStyles.itemIcon}>↩</Text>
              <Text style={asStyles.itemText}>Antworten</Text>
            </TouchableOpacity>
          )}
          <View style={asStyles.divider} />
          <TouchableOpacity style={asStyles.cancelItem} onPress={onClose}>
            <Text style={asStyles.cancelText}>Abbrechen</Text>
          </TouchableOpacity>
        </View>
      </TouchableOpacity>
    </Modal>
  );
}
const asStyles = StyleSheet.create({
  overlay: {flex: 1, backgroundColor: 'rgba(0,0,0,0.55)', justifyContent: 'flex-end'},
  sheet: {
    backgroundColor: P.surface,
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    borderWidth: 1,
    borderColor: P.border,
    paddingBottom: 32,
    paddingTop: 12,
  },
  handle: {
    width: 40, height: 4, borderRadius: 2,
    backgroundColor: P.border,
    alignSelf: 'center', marginBottom: 16,
  },
  preview: {
    color: P.muted, fontSize: 13, fontStyle: 'italic',
    paddingHorizontal: 20, marginBottom: 12, lineHeight: 18,
  },
  divider: {height: 1, backgroundColor: P.border, marginVertical: 4},
  item: {
    flexDirection: 'row', alignItems: 'center',
    paddingHorizontal: 24, paddingVertical: 16, gap: 16,
  },
  itemIcon: {fontSize: 20, width: 28, textAlign: 'center'},
  itemText: {color: P.text, fontSize: 16},
  cancelItem: {paddingHorizontal: 24, paddingVertical: 16, alignItems: 'center'},
  cancelText: {color: P.muted, fontSize: 15},
});

// ─── MapCard ─────────────────────────────────────────────────────────────────
function MapCard({data}: {data: MapCardData}): React.JSX.Element {
  return (
    <TouchableOpacity
      style={mapStyles.card}
      onPress={() => Linking.openURL(data.maps_url)}
      accessibilityLabel="In Google Maps öffnen">
      <View style={mapStyles.row}>
        <Text style={mapStyles.mapIcon}>🗺️</Text>
        <View style={{flex: 1}}>
          <Text style={mapStyles.title}>In Google Maps öffnen</Text>
          <Text style={mapStyles.query} numberOfLines={1}>{data.query}</Text>
        </View>
        <Text style={mapStyles.arrow}>›</Text>
      </View>
    </TouchableOpacity>
  );
}
const mapStyles = StyleSheet.create({
  card: {
    backgroundColor: P.card,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: 'rgba(56,189,248,0.25)',
    padding: 14,
    marginBottom: 8,
  },
  row: {flexDirection: 'row', alignItems: 'center', gap: 12},
  mapIcon: {fontSize: 28},
  title: {color: P.accent, fontSize: 14, fontWeight: '600'},
  query: {color: P.muted, fontSize: 12, marginTop: 2},
  arrow: {color: P.accent, fontSize: 22, fontWeight: '300'},
});

// ─── FeedbackRow ─────────────────────────────────────────────────────────────
// DONNA-139: Erweitert um messageId + userMessage für LTM-Integration.
// Der Backend-Endpoint schreibt bei Bewertung eine mem0-Memory — Donna lernt
// was Mike als hilfreich empfindet.
function FeedbackRow({
  content,
  sessionId,
  messageId,
  userMessage,
}: {
  content: string;
  sessionId: string;
  messageId?: string;
  userMessage?: string;
}): React.JSX.Element {
  const [sent, setSent] = React.useState<'positive' | 'negative' | null>(null);

  const rate = async (rating: 'positive' | 'negative') => {
    if (sent) return;
    setSent(rating);
    await sendFeedback(
      sessionId,
      rating,
      content.slice(0, 200),
      messageId,
      content,
      userMessage,
    );
  };

  return (
    <View style={fbStyles.row}>
      <TouchableOpacity
        onPress={() => rate('positive')}
        disabled={!!sent}
        style={[fbStyles.btn, sent === 'positive' && fbStyles.btnActive]}
        hitSlop={{top: 8, bottom: 8, left: 8, right: 8}}>
        <Text style={[fbStyles.icon, sent === 'positive' && fbStyles.iconActive]}>👍</Text>
      </TouchableOpacity>
      <TouchableOpacity
        onPress={() => rate('negative')}
        disabled={!!sent}
        style={[fbStyles.btn, sent === 'negative' && fbStyles.btnActive]}
        hitSlop={{top: 8, bottom: 8, left: 8, right: 8}}>
        <Text style={[fbStyles.icon, sent === 'negative' && fbStyles.iconActive]}>👎</Text>
      </TouchableOpacity>
      {sent && <Text style={fbStyles.thanks}>Danke!</Text>}
    </View>
  );
}
const fbStyles = StyleSheet.create({
  row: {flexDirection: 'row', alignItems: 'center', gap: 4, marginTop: 4, marginLeft: 32},
  btn: {padding: 4, borderRadius: 8},
  btnActive: {backgroundColor: 'rgba(56,189,248,0.12)'},
  icon: {fontSize: 14, opacity: 0.4},
  iconActive: {opacity: 1},
  thanks: {color: P.muted, fontSize: 11, marginLeft: 4},
});

// ─── ActionList ────────────────────────────────────────────────────────────────
// DONNA-Welle1 Task 5/6: Zeigt vom Backend emittierte DONNA_ACTION-Events als
// Hinweis-Chips unter der Donna-Bubble. Welle 1 = nur visuell + open_url klickbar.
// Volle Action-Handler (create_event, set_alarm, navigate, …) kommen in Welle 2.
// DONNA-Welle3: Bestätigungs-Karte statt direkter Ausführung. ActionList ruft
// onAction(action) auf → ChatScreen zeigt PendingActionCard → User bestätigt →
// Ausführung via AlarmModule (native Intent mit SKIP_UI=true) oder Linking.
function formatEventTime(iso: string | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  const dayMs = 24 * 60 * 60 * 1000;
  const today = new Date(); today.setHours(0,0,0,0);
  const target = new Date(d); target.setHours(0,0,0,0);
  const diff = Math.round((target.getTime() - today.getTime()) / dayMs);
  let dayLabel: string;
  if (diff === 0) dayLabel = 'Heute';
  else if (diff === 1) dayLabel = 'Morgen';
  else if (diff === -1) dayLabel = 'Gestern';
  else dayLabel = d.toLocaleDateString('de-DE', {weekday: 'short', day: '2-digit', month: '2-digit'});
  const time = d.toLocaleTimeString('de-DE', {hour: '2-digit', minute: '2-digit'});
  return `${dayLabel}, ${time}`;
}

function getActionConfirmLabel(action: DonnaAction): string {
  switch (action.type) {
    case 'set_alarm': return `⏰ Wecker für ${(action.time as string) ?? ''} stellen?`;
    case 'set_timer': return `⏱ Timer für ${(action.minutes as number) ?? ''} Minuten starten?`;
    case 'create_event': return `📅 "${(action.title as string) ?? 'Termin'}" in Kalender eintragen?`;
    case 'navigate': return `🗺 Navigation zu ${(action.destination as string) ?? ''} starten?`;
    case 'call': return `📞 ${(action.number as string) ?? ''} anrufen?`;
    case 'sms': return `💬 SMS an ${(action.number as string) ?? ''} senden?`;
    case 'open_url': return `🔗 Link öffnen: ${(action.title as string) ?? (action.url as string) ?? ''}?`;
    default: return `Aktion ausführen?`;
  }
}

// ─── Reichere Pending-Action-Karte mit typ-spezifischen Detail-Layouts ───────
// Zeigt fuer Termin/Wecker/Anruf/Nachricht eine Detail-Vorschau mit allen
// relevanten Feldern. Anschliessend Bestaetigen/Abbrechen.
function PendingActionCard({
  action,
  contact,
  onConfirm,
  onCancel,
}: {
  action: DonnaAction;
  contact: ContactMatch | null;
  onConfirm: () => void;
  onCancel: () => void;
}): React.JSX.Element {
  // Bixby-Stil: Label/Wert-Paare mit Trennstrichen. Pro Action-Typ andere Felder.
  const renderFields = (): Array<{label: string; value: string; multiline?: boolean}> => {
    switch (action.type) {
      case 'create_event': {
        const fields: Array<{label: string; value: string; multiline?: boolean}> = [];
        const title = (action.title as string) ?? 'Termin';
        fields.push({label: 'Titel', value: title});
        const start = action.start as string | undefined;
        const end = action.end as string | undefined;
        if (start) {
          const endTxt = end ? ` – ${new Date(end).toLocaleTimeString('de-DE', {hour:'2-digit', minute:'2-digit'})}` : '';
          fields.push({label: 'Wann', value: `${formatEventTime(start)}${endTxt}`});
        }
        const location = action.location as string | undefined;
        if (location) fields.push({label: 'Ort', value: location});
        return fields;
      }
      case 'set_alarm': {
        const fields = [{label: 'Uhrzeit', value: (action.time as string) ?? '--:--'}];
        const label = (action.label as string) ?? '';
        if (label) fields.push({label: 'Bezeichnung', value: label});
        return fields;
      }
      case 'set_timer': {
        const mins = (action.minutes as number) ?? 0;
        const fields = [{label: 'Dauer', value: `${mins} Minuten`}];
        const label = (action.label as string) ?? '';
        if (label) fields.push({label: 'Bezeichnung', value: label});
        return fields;
      }
      case 'navigate':
        return [{label: 'Ziel', value: (action.destination as string) ?? ''}];
      case 'call': {
        const name = contact?.name ?? (action.name as string) ?? '';
        const number = contact?.number ?? (action.number as string) ?? '';
        const fields: Array<{label: string; value: string}> = [];
        if (name) fields.push({label: 'Empfänger', value: name});
        if (number) fields.push({label: 'Nummer', value: number});
        return fields;
      }
      case 'sms':
      case 'whatsapp': {
        const name = contact?.name ?? (action.name as string) ?? '';
        const number = contact?.number ?? (action.number as string) ?? '';
        const msg = (action.message as string) ?? '';
        const fields: Array<{label: string; value: string; multiline?: boolean}> = [];
        if (name) fields.push({label: 'Empfänger', value: name});
        else if (number) fields.push({label: 'Nummer', value: number});
        if (msg) fields.push({label: 'Nachricht', value: msg, multiline: true});
        return fields;
      }
      case 'open_url':
        return [{label: 'Link', value: (action.title as string) ?? (action.url as string) ?? ''}];
      case 'play_music': {
        const fields = [{label: 'Musik', value: (action.query as string) ?? ''}];
        const service = (action.service as string) ?? '';
        if (service) fields.push({label: 'Dienst', value: service});
        return fields;
      }
      default:
        return [{label: 'Aktion', value: getActionConfirmLabel(action)}];
    }
  };

  const fields = renderFields();
  const noNumber = (action.type === 'call' || action.type === 'sms' || action.type === 'whatsapp') &&
    !contact && !((action.number as string)?.length);
  const noContactWarn = !contact && (action.name as string) &&
    (action.type === 'call' || action.type === 'sms' || action.type === 'whatsapp');

  return (
    <View style={cardStyles.cardOuter}>
      <View style={cardStyles.cardInner}>
        {fields.map((f, i) => (
          <View key={`${f.label}-${i}`}>
            {i > 0 && <View style={cardStyles.divider} />}
            <Text style={cardStyles.fieldLabel}>{f.label}</Text>
            <Text style={cardStyles.fieldValue} numberOfLines={f.multiline ? 6 : 2}>{f.value}</Text>
          </View>
        ))}
        {noContactWarn ? (
          <Text style={cardStyles.warnText}>"{action.name as string}" nicht in Kontakten gefunden</Text>
        ) : null}
      </View>
      <View style={cardStyles.btnRow}>
        <TouchableOpacity
          onPress={onConfirm}
          disabled={noNumber}
          style={[cardStyles.btn, noNumber && cardStyles.btnDisabled]}>
          <Text style={cardStyles.btnText}>{noNumber ? 'Kontakt fehlt' : 'Bestätigen'}</Text>
        </TouchableOpacity>
        <View style={cardStyles.btnDivider} />
        <TouchableOpacity onPress={onCancel} style={cardStyles.btn}>
          <Text style={cardStyles.btnText}>Abbrechen</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

const cardStyles = StyleSheet.create({
  cardOuter: {
    backgroundColor: '#1c1c1e',
    borderRadius: 16,
    marginHorizontal: 8,
    marginVertical: 6,
    overflow: 'hidden',
  },
  cardInner: {padding: 18, paddingBottom: 14},
  divider: {height: 1, backgroundColor: 'rgba(255,255,255,0.08)', marginVertical: 12},
  fieldLabel: {color: 'rgba(255,255,255,0.55)', fontSize: 13, fontWeight: '400', marginBottom: 4},
  fieldValue: {color: '#ffffff', fontSize: 22, fontWeight: '500', lineHeight: 28},
  warnText: {color: '#fbbf24', fontSize: 12, marginTop: 12, fontStyle: 'italic'},
  btnRow: {
    flexDirection: 'row',
    backgroundColor: '#161618',
    borderTopWidth: 1,
    borderTopColor: 'rgba(255,255,255,0.06)',
  },
  btn: {flex: 1, paddingVertical: 14, alignItems: 'center'},
  btnDisabled: {opacity: 0.4},
  btnText: {color: 'rgba(255,255,255,0.85)', fontSize: 15, fontWeight: '400'},
  btnDivider: {width: 1, backgroundColor: 'rgba(255,255,255,0.08)'},
});

// Contact-Picker — wird angezeigt wenn mehrere Kontakte zum Namen passen
function ContactPickerCard({
  query,
  matches,
  onPick,
  onCancel,
}: {
  query: string;
  matches: ContactMatch[];
  onPick: (c: ContactMatch) => void;
  onCancel: () => void;
}): React.JSX.Element {
  return (
    <View style={cardStyles.cardOuter}>
      <View style={cardStyles.cardInner}>
        <Text style={cardStyles.fieldLabel}>Kontakt wählen</Text>
        <Text style={[cardStyles.fieldValue, {fontSize: 18, marginBottom: 8}]}>Mehrere Treffer für "{query}"</Text>
        <ScrollView style={{maxHeight: 240}}>
          {matches.map((m, i) => (
            <TouchableOpacity
              key={`${m.contactId}-${i}`}
              onPress={() => onPick(m)}
              style={pickerStyles.row}>
              <Text style={pickerStyles.name}>{m.name}</Text>
              <Text style={pickerStyles.number}>{m.number}</Text>
            </TouchableOpacity>
          ))}
        </ScrollView>
      </View>
      <View style={cardStyles.btnRow}>
        <TouchableOpacity onPress={onCancel} style={cardStyles.btn}>
          <Text style={cardStyles.btnText}>Abbrechen</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}
const pickerStyles = StyleSheet.create({
  row: {
    paddingVertical: 12,
    paddingHorizontal: 8,
    borderBottomWidth: 1,
    borderBottomColor: 'rgba(255,255,255,0.06)',
  },
  name: {color: '#ffffff', fontSize: 16, fontWeight: '500'},
  number: {color: 'rgba(255,255,255,0.55)', fontSize: 13, marginTop: 2},
});

// Kontakt nicht gefunden — zeigt fuzzy Vorschläge + Freitexteingabe
interface ContactFallbackCardProps {
  originalName: string;
  suggestions: ContactMatch[];
  onSelect: (contact: ContactMatch) => void;
  onRetry: (name: string) => void;
  onCancel: () => void;
}
function ContactFallbackCard({
  originalName,
  suggestions,
  onSelect,
  onRetry,
  onCancel,
}: ContactFallbackCardProps): React.JSX.Element {
  const [inputText, setInputText] = useState('');
  return (
    <View style={cardStyles.cardOuter}>
      <View style={cardStyles.cardInner}>
        <Text style={cardStyles.fieldLabel}>Kontakt nicht gefunden</Text>
        <Text style={[cardStyles.fieldValue, {fontSize: 18, marginBottom: 8}]}>
          "{originalName}" — wen meinst du?
        </Text>
        {suggestions.length > 0 && (
          <ScrollView style={{maxHeight: 200}}>
            {suggestions.map((m, i) => (
              <TouchableOpacity
                key={`${m.contactId ?? i}-fallback`}
                onPress={() => onSelect(m)}
                style={pickerStyles.row}>
                <Text style={pickerStyles.name}>{m.name}</Text>
                <Text style={pickerStyles.number}>{m.number}</Text>
              </TouchableOpacity>
            ))}
          </ScrollView>
        )}
        {suggestions.length > 0 && <View style={cardStyles.divider} />}
        <TextInput
          style={fallbackStyles.input}
          placeholder="anderen Namen eingeben…"
          placeholderTextColor="rgba(255,255,255,0.35)"
          value={inputText}
          onChangeText={setInputText}
          autoCorrect={false}
        />
      </View>
      <View style={cardStyles.btnRow}>
        <TouchableOpacity
          onPress={() => onRetry(inputText)}
          style={[cardStyles.btn, inputText.trim().length < 2 && cardStyles.btnDisabled]}
          disabled={inputText.trim().length < 2}>
          <Text style={cardStyles.btnText}>Suchen</Text>
        </TouchableOpacity>
        <View style={cardStyles.btnDivider} />
        <TouchableOpacity onPress={onCancel} style={cardStyles.btn}>
          <Text style={cardStyles.btnText}>Abbrechen</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}
const fallbackStyles = StyleSheet.create({
  input: {
    backgroundColor: 'rgba(255,255,255,0.07)',
    borderRadius: 10,
    paddingHorizontal: 14,
    paddingVertical: 10,
    color: '#ffffff',
    fontSize: 15,
    marginTop: 4,
  },
});

function ActionList({actions, onAction}: {actions: DonnaAction[]; onAction?: (a: DonnaAction) => void}): React.JSX.Element {
  const labels: Record<string, string> = {
    create_event: '📅 Termin',
    set_alarm: '⏰ Alarm',
    set_timer: '⏲️ Timer',
    navigate: '🧭 Navigation',
    call: '📞 Anruf',
    sms: '💬 SMS',
    whatsapp: '💚 WhatsApp',
    play_music: '🎵 Musik',
    note: '📝 Notiz',
    open_url: '🔗 Link',
  };
  const interactiveTypes = new Set([
    'set_alarm', 'set_timer', 'create_event', 'navigate', 'call', 'sms', 'whatsapp', 'open_url', 'play_music',
  ]);
  return (
    <View style={actionStyles.row}>
      {actions.map((a, i) => {
        const label = labels[a.type] ?? `⚡ ${a.type}`;
        // Beschreibung pro Action-Typ
        let desc = '';
        if (a.type === 'create_event') {
          desc = `${(a.title as string) ?? ''} ${(a.start as string) ?? ''}`.trim();
        } else if (a.type === 'set_alarm') {
          desc = `${(a.time as string) ?? ''} ${(a.label as string) ?? ''}`.trim();
        } else if (a.type === 'set_timer') {
          desc = `${a.minutes ?? ''}min ${(a.label as string) ?? ''}`.trim();
        } else if (a.type === 'navigate') {
          desc = (a.destination as string) ?? '';
        } else if (a.type === 'open_url') {
          desc = (a.title as string) ?? (a.url as string) ?? '';
        } else if (a.type === 'play_music') {
          desc = (a.query as string) ?? '';
        } else if (a.type === 'call') {
          desc = (a.number as string) ?? '';
        } else if (a.type === 'sms') {
          desc = `${(a.number as string) ?? ''} ${(a.message as string) ?? ''}`.trim();
        }
        const interactive = interactiveTypes.has(a.type);
        return (
          <TouchableOpacity
            key={`${a.type}-${i}`}
            onPress={interactive ? () => { onAction?.(a); } : undefined}
            disabled={!interactive}
            activeOpacity={interactive ? 0.7 : 1}
            style={actionStyles.chip}>
            <Text style={actionStyles.chipLabel}>{label}</Text>
            {desc ? <Text style={actionStyles.chipDesc} numberOfLines={1}>{desc}</Text> : null}
          </TouchableOpacity>
        );
      })}
    </View>
  );
}
const actionStyles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    marginTop: 6,
    gap: 6,
  },
  chip: {
    backgroundColor: 'rgba(56,189,248,0.22)',
    borderColor: 'rgba(56,189,248,0.85)',
    borderWidth: 1.5,
    borderRadius: 14,
    paddingHorizontal: 14,
    paddingVertical: 10,
    flexDirection: 'row',
    alignItems: 'center',
    maxWidth: '100%',
    minHeight: 44,
  },
  chipLabel: {color: '#7dd3fc', fontSize: 15, fontWeight: '700', marginRight: 8},
  chipDesc: {color: '#cbd5e1', fontSize: 13, flexShrink: 1, fontWeight: '500'},
});

// ─── MessageBubble ─────────────────────────────────────────────────────────────
function MessageBubble({
  message,
  sessionId,
  onLongPress,
  onAction,
  userMessage,
}: {
  message: ChatMessage;
  sessionId: string;
  onLongPress?: (content: string, isAssistant: boolean) => void;
  onAction?: (a: DonnaAction) => void;
  userMessage?: string;
}): React.JSX.Element {
  const isUser = message.role === 'user';

  return (
    <View style={[bubbleStyles.outerWrap, isUser ? bubbleStyles.userOuter : bubbleStyles.donnaOuter]}>
      {!isUser && (
        <View style={bubbleStyles.avatarCol}>
          <DonnaAvatar size={24} />
        </View>
      )}
      <View style={{flex: 1}}>
        {/* Karte vor dem Text anzeigen */}
        {!isUser && message.card?.card_type === 'weather' && (
          <WeatherCard data={message.card.data as WeatherCardData} />
        )}
        {!isUser && message.card?.card_type === 'map' && (
          <MapCard data={message.card.data as MapCardData} />
        )}
        {/* Textblase */}
        {message.content ? (
          <TouchableOpacity
            onLongPress={() => onLongPress?.(message.content, !isUser)}
            delayLongPress={400}
            activeOpacity={0.88}>
            <View
              style={[bubbleStyles.bubble, isUser ? bubbleStyles.userBubble : bubbleStyles.donnaBubble]}>
              {isUser ? (
                <Text style={[bubbleStyles.text, bubbleStyles.userText]}>{message.content}</Text>
              ) : (
                <MarkdownText text={message.content} style={bubbleStyles.donnaText} />
              )}
            </View>
          </TouchableOpacity>
        ) : null}
        {/* DONNA-Welle1 Task 5: Action-Chips (create_event, set_alarm, …) */}
        {!isUser && message.actions && message.actions.length > 0 ? (
          <ActionList actions={message.actions} onAction={onAction} />
        ) : null}
        {/* Feedback nur unter Donna-Antworten — DONNA-139: userMessage für LTM */}
        {!isUser && message.content ? (
          <FeedbackRow
            content={message.content}
            sessionId={sessionId}
            messageId={(message as any).id}
            userMessage={userMessage}
          />
        ) : null}
      </View>
    </View>
  );
}
const bubbleStyles = StyleSheet.create({
  outerWrap: {
    flexDirection: 'row',
    marginBottom: 12,
    maxWidth: '92%',
  },
  userOuter: {
    alignSelf: 'flex-end',
    flexDirection: 'row-reverse',
  },
  donnaOuter: {
    alignSelf: 'flex-start',
  },
  avatarCol: {
    marginRight: 8,
    marginTop: 4,
  },
  bubble: {
    borderRadius: 16,
    paddingHorizontal: 14,
    paddingVertical: 10,
    borderWidth: 1,
  },
  userBubble: {
    backgroundColor: P.userBubble,
    borderColor: P.userBorder,
    borderBottomRightRadius: 4,
  },
  donnaBubble: {
    backgroundColor: P.donnaBubble,
    borderColor: P.donnaBorder,
    borderBottomLeftRadius: 4,
  },
  text: {fontSize: 15, lineHeight: 22},
  userText: {color: P.text},
  donnaText: {color: P.text},
});

// ─── TypingIndicator ──────────────────────────────────────────────────────────
function TypingIndicator(): React.JSX.Element {
  const dots = useRef([
    new Animated.Value(0),
    new Animated.Value(0),
    new Animated.Value(0),
  ]).current;

  useEffect(() => {
    const anims = dots.map((d, i) =>
      Animated.loop(
        Animated.sequence([
          Animated.delay(i * 150),
          Animated.timing(d, {toValue: 1, duration: 350, useNativeDriver: true}),
          Animated.timing(d, {toValue: 0, duration: 350, useNativeDriver: true}),
          Animated.delay(450 - i * 150),
        ]),
      ),
    );
    anims.forEach(a => a.start());
    return () => anims.forEach(a => a.stop());
  }, [dots]);

  return (
    <View style={typingStyles.row}>
      <DonnaAvatar size={24} />
      <View style={typingStyles.bubble}>
        {dots.map((d, i) => (
          <Animated.View
            key={i}
            style={[typingStyles.dot, {opacity: d.interpolate({inputRange: [0, 1], outputRange: [0.3, 1]})}]}
          />
        ))}
      </View>
    </View>
  );
}
const typingStyles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 12,
    marginLeft: 4,
  },
  bubble: {
    flexDirection: 'row',
    backgroundColor: P.card,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: P.donnaBorder,
    paddingHorizontal: 14,
    paddingVertical: 12,
    marginLeft: 8,
    gap: 5,
    alignItems: 'center',
  },
  dot: {
    width: 6,
    height: 6,
    borderRadius: 3,
    backgroundColor: P.accent,
  },
});

// ─── MicButton ────────────────────────────────────────────────────────────────
function MicButton({active, onPress}: {active: boolean; onPress: () => void}): React.JSX.Element {
  const scale = useRef(new Animated.Value(1)).current;
  useEffect(() => {
    if (active) {
      const anim = Animated.loop(
        Animated.sequence([
          Animated.timing(scale, {toValue: 1.12, duration: 500, useNativeDriver: true}),
          Animated.timing(scale, {toValue: 1,    duration: 500, useNativeDriver: true}),
        ]),
      );
      anim.start();
      // DONNA-38: Cleanup verhindert "Animated node does not exist"-Crash
      return () => {
        anim.stop();
        scale.stopAnimation();
      };
    } else {
      scale.stopAnimation();
      Animated.timing(scale, {toValue: 1, duration: 150, useNativeDriver: true}).start();
    }
    return undefined;
  }, [active, scale]);

  return (
    <TouchableOpacity onPress={onPress} accessibilityLabel={active ? 'Aufnahme stoppen' : 'Spracheingabe'}>
      <Animated.View style={[micStyles.btn, active && micStyles.btnActive, {transform: [{scale}]}]}>
        <Text style={micStyles.icon}>{active ? '⏹' : '🎤'}</Text>
      </Animated.View>
    </TouchableOpacity>
  );
}
const micStyles = StyleSheet.create({
  btn: {
    width: 44,
    height: 44,
    borderRadius: 22,
    backgroundColor: P.card,
    borderWidth: 1,
    borderColor: 'rgba(56,189,248,0.25)',
    justifyContent: 'center',
    alignItems: 'center',
  },
  btnActive: {
    backgroundColor: 'rgba(56,189,248,0.18)',
    borderColor: P.accent,
  },
  icon: {fontSize: 18},
});

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

// ─── IdleScreen (shown when no messages) ──────────────────────────────────────
function IdleScreen({onVoicePress, isAvailable}: {onVoicePress: () => void; isAvailable: boolean}): React.JSX.Element {
  return (
    <View style={idleStyles.container}>
      <Text style={idleStyles.statusLabel}>SYS · BEREIT</Text>
      <DonnaOrb listening={false} />
      <Text style={idleStyles.hint}>
        {isAvailable ? 'Tippe oder sprich mit Donna' : 'Tippe mit Donna'}
      </Text>
      {isAvailable && (
        <TouchableOpacity style={idleStyles.voiceChip} onPress={onVoicePress}>
          <Text style={idleStyles.voiceChipText}>🎤  Sprachanfrage starten</Text>
        </TouchableOpacity>
      )}
    </View>
  );
}
const idleStyles = StyleSheet.create({
  container: {flex: 1, alignItems: 'center', justifyContent: 'center', paddingBottom: 40},
  statusLabel: {
    color: P.muted,
    fontSize: 11,
    letterSpacing: 2,
    textTransform: 'uppercase',
    marginBottom: 8,
  },
  hint: {
    color: P.muted,
    fontSize: 14,
    marginTop: 16,
    textAlign: 'center',
  },
  voiceChip: {
    marginTop: 20,
    paddingHorizontal: 20,
    paddingVertical: 10,
    borderRadius: 24,
    borderWidth: 1,
    borderColor: 'rgba(56,189,248,0.3)',
    backgroundColor: 'rgba(56,189,248,0.08)',
  },
  voiceChipText: {
    color: P.accent2,
    fontSize: 14,
    fontWeight: '500',
  },
});

// ─── ReplyPreview ─────────────────────────────────────────────────────────────
function ReplyPreview({text, onCancel}: {text: string; onCancel: () => void}): React.JSX.Element {
  return (
    <View style={replyStyles.wrap}>
      <View style={replyStyles.bar} />
      <Text style={replyStyles.text} numberOfLines={2}>{text}</Text>
      <TouchableOpacity onPress={onCancel} hitSlop={{top: 8, bottom: 8, left: 8, right: 8}}>
        <Text style={replyStyles.close}>✕</Text>
      </TouchableOpacity>
    </View>
  );
}
const replyStyles = StyleSheet.create({
  wrap: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: 'rgba(56,189,248,0.08)',
    borderTopWidth: 1,
    borderTopColor: 'rgba(56,189,248,0.15)',
    paddingHorizontal: 14,
    paddingVertical: 8,
    gap: 10,
  },
  bar: {width: 3, borderRadius: 2, backgroundColor: P.accent, alignSelf: 'stretch'},
  text: {flex: 1, color: P.muted, fontSize: 12, fontStyle: 'italic'},
  close: {color: P.muted, fontSize: 16},
});

// ─── History Panel ────────────────────────────────────────────────────────────
function HistoryPanel({
  visible, onClose, onLoadSession,
}: {
  visible: boolean;
  onClose: () => void;
  onLoadSession: (msgs: SessionMessage[]) => void;
}): React.JSX.Element {
  const [sessions, setSessions] = useState<SessionInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadingSession, setLoadingSession] = useState<string | null>(null);
  const [fetchError, setFetchError] = useState<'no_token' | 'network' | null>(null);

  const loadSessions = useCallback(async () => {
    // DONNA-114: Auth-Token-Guard — ohne Token liefert das Backend 401
    // und fetchSessions() gibt still [] zurück. Stattdessen Fehlerzustand anzeigen.
    if (!getApiToken()) {
      setFetchError('no_token');
      setLoading(false);
      return;
    }
    setFetchError(null);
    setLoading(true);
    try {
      const s = await fetchSessions();
      setSessions(s);
    } catch {
      setFetchError('network');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!visible) return;
    loadSessions();
  }, [visible, loadSessions]);

  const handleSession = async (sid: string) => {
    setLoadingSession(sid);
    const msgs = await fetchSessionMessages(sid);
    setLoadingSession(null);
    onLoadSession(msgs);
    onClose();
  };

  const fmtTime = (ts: number) => {
    const d = new Date(ts * 1000);
    const now = new Date();
    const diffH = (now.getTime() - d.getTime()) / 3600000;
    if (diffH < 24) return d.toLocaleTimeString('de-DE', {hour: '2-digit', minute: '2-digit'});
    return d.toLocaleDateString('de-DE', {day: '2-digit', month: '2-digit'}) + ' ' +
           d.toLocaleTimeString('de-DE', {hour: '2-digit', minute: '2-digit'});
  };

  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}>
      <TouchableOpacity style={hStyles.backdrop} activeOpacity={1} onPress={onClose} />
      <View style={hStyles.panel}>
        <View style={hStyles.handle} />
        <View style={hStyles.header}>
          <Text style={hStyles.title}>GESPRÄCHSVERLAUF</Text>
          <TouchableOpacity onPress={onClose} hitSlop={{top:8,bottom:8,left:8,right:8}}>
            <Text style={hStyles.close}>✕</Text>
          </TouchableOpacity>
        </View>
        {loading ? (
          <ActivityIndicator color={P.accent} style={{marginTop: 32}} />
        ) : fetchError === 'no_token' ? (
          <View style={hStyles.errorWrap}>
            <Text style={hStyles.empty}>Nicht authentifiziert — bitte kurz warten und erneut öffnen.</Text>
            <TouchableOpacity style={hStyles.retryBtn} onPress={loadSessions}>
              <Text style={hStyles.retryText}>Erneut laden</Text>
            </TouchableOpacity>
          </View>
        ) : fetchError === 'network' ? (
          <View style={hStyles.errorWrap}>
            <Text style={hStyles.empty}>Verbindungsfehler — kein Verlauf verfügbar.</Text>
            <TouchableOpacity style={hStyles.retryBtn} onPress={loadSessions}>
              <Text style={hStyles.retryText}>Erneut laden</Text>
            </TouchableOpacity>
          </View>
        ) : sessions.length === 0 ? (
          <Text style={hStyles.empty}>Keine Gespräche in den letzten 24h</Text>
        ) : (
          <FlatList
            data={sessions}
            keyExtractor={s => s.session_id}
            contentContainerStyle={{paddingHorizontal: 16, paddingBottom: 24}}
            renderItem={({item}) => (
              <TouchableOpacity
                style={hStyles.sessionRow}
                onPress={() => handleSession(item.session_id)}
                disabled={loadingSession === item.session_id}
              >
                <View style={hStyles.sessionLeft}>
                  <Text style={hStyles.sessionTime}>{fmtTime(item.started_at)}</Text>
                  <Text style={hStyles.sessionPreview} numberOfLines={2}>{item.preview}</Text>
                </View>
                <View style={hStyles.sessionRight}>
                  {loadingSession === item.session_id
                    ? <ActivityIndicator size="small" color={P.accent} />
                    : <Text style={hStyles.sessionCount}>{item.message_count} Nachr.</Text>
                  }
                  <Text style={hStyles.chevron}>›</Text>
                </View>
              </TouchableOpacity>
            )}
          />
        )}
      </View>
    </Modal>
  );
}

const hStyles = StyleSheet.create({
  backdrop: {flex: 1, backgroundColor: 'rgba(0,0,0,0.55)'},
  panel: {
    backgroundColor: P.card,
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    maxHeight: '75%',
    borderTopWidth: 1,
    borderColor: P.border,
  },
  handle: {
    width: 36, height: 4, borderRadius: 2,
    backgroundColor: P.border,
    alignSelf: 'center', marginTop: 10,
  },
  header: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingHorizontal: 20, paddingVertical: 14,
    borderBottomWidth: 1, borderBottomColor: P.border,
  },
  title: {color: P.accent, fontSize: 12, fontWeight: '700', letterSpacing: 2},
  close: {color: P.muted, fontSize: 18},
  empty: {color: P.muted, textAlign: 'center', marginTop: 32, fontSize: 14, paddingHorizontal: 24},
  errorWrap: {alignItems: 'center', paddingTop: 24},
  retryBtn: {marginTop: 16, paddingHorizontal: 20, paddingVertical: 10, borderRadius: 8, borderWidth: 1, borderColor: P.accent},
  retryText: {color: P.accent, fontSize: 13, fontWeight: '600'},
  sessionRow: {
    flexDirection: 'row', alignItems: 'center',
    paddingVertical: 14, borderBottomWidth: 1, borderBottomColor: P.border,
    gap: 12,
  },
  sessionLeft: {flex: 1, gap: 4},
  sessionTime: {color: P.accent2, fontSize: 11, fontWeight: '600'},
  sessionPreview: {color: P.text, fontSize: 13},
  sessionRight: {alignItems: 'flex-end', gap: 4},
  sessionCount: {color: P.muted, fontSize: 11},
  chevron: {color: P.accent, fontSize: 20},
});

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
export default function ChatScreen(): React.JSX.Element {
  const [inputText, setInputText] = useState('');
  const [replyTo, setReplyTo] = useState<string | null>(null);
  const [historyVisible, setHistoryVisible] = useState(false);
  const [actionSheet, setActionSheet] = useState<ActionSheetState>({
    visible: false, content: '', isAssistant: false,
  });
  const {messages, isLoading, rateLimitStatus, sendMessage, loadMessages, clearMessages, setLocation, sessionId, startProactiveChat} = useChat();

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
  const pendingActionRef = useRef<DonnaAction | null>(null);
  const pendingContactRef = useRef<ContactMatch | null>(null);
  useEffect(() => { pendingActionRef.current = pendingAction; }, [pendingAction]);
  useEffect(() => { pendingContactRef.current = pendingContact; }, [pendingContact]);

  // Guard: History-Modal darf pendingAction NICHT löschen. Wenn historyVisible von
  // true → false wechselt und der Ref noch eine Action hält, State wiederherstellen.
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
  // → Card mit Kontaktinfos. Mehrere Treffer → ContactPicker. Kein Treffer
  // mit `number` da → Warnhinweis in Card.
  const handleDonnaAction = useCallback(async (action: DonnaAction) => {
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
        // Mehrere Treffer → Picker zeigen
        setPendingContact(null);
        setContactCandidates(matches);
        setContactFallback(null);
        setPendingAction(action);
        return;
      }
      // Kein Treffer → FallbackCard (max 2 Rekursionen), danach PendingActionCard
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
      // Zu viele Retries → direkt PendingActionCard mit Warnung
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

  // WhatsApp-Format: nationale Nummer ohne "+"; deutsche 0XXX → 49XXX umrechnen
  const waNumber = (n: string): string => {
    const c = cleanNumber(n);
    if (c.startsWith('+')) return c.slice(1);
    if (c.startsWith('00')) return c.slice(2);
    if (c.startsWith('0')) return '49' + c.slice(1);
    return c;
  };

  const confirmPendingAction = useCallback(async () => {
    const action = pendingActionRef.current;
    if (!action) return;
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
          confirmText = `Wecker für ${action.time} Uhr gestellt ✓`;
          break;
        }
        case 'set_timer': {
          const mins = (action.minutes as number) ?? 5;
          await AlarmModule?.setTimer(mins, (action.label as string) ?? 'Donna Timer');
          confirmText = `Timer für ${mins} Minuten gestartet ✓`;
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
          confirmText = `Termin "${action.title}" eingetragen ✓`;
          break;
        }
        case 'navigate':
          await Linking.openURL(`geo:0,0?q=${encodeURIComponent((action.destination as string) ?? '')}`);
          confirmText = `Navigation gestartet`;
          break;
        case 'call': {
          const raw = contact?.number ?? (action.number as string) ?? '';
          const safe = cleanNumber(raw);
          if (safe) {
            await Linking.openURL(`tel:${safe}`);
            confirmText = `Anruf an ${contact?.name ?? safe} wird verbunden...`;
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
          // Oeffnet WhatsApp mit Kontakt + vorgeschriebener Nachricht.
          // Mike sieht das Eingabefeld bereits gefuellt und tippt nur noch Senden.
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
            confirmText = `WhatsApp für ${contact?.name ?? phone} geöffnet — bitte absenden`;
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
  }, []);

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
          if (!hasPermission) {
            const result = await PermissionsAndroid.request(
              PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
            );
            if (result !== PermissionsAndroid.RESULTS.GRANTED) { return; }
          }
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

  const {voiceState, partialTranscript, startListening, stopListening, isAvailable} = useVoice({
    module: AndroidVoiceModule,
    locale: 'de-DE',
    onTranscript: useCallback(
      (text: string) => {
        const trimmed = text.trim();
        if (!trimmed) return;
        // Pending Action per Sprache bestätigen/ablehnen
        if (pendingActionRef.current && conversationModeRef.current) {
          const lower = trimmed.toLowerCase();
          const isYes = ['ja', 'ok', 'mach', 'stell', 'bestätige', 'klar'].some(w => lower.includes(w));
          const isNo = ['nein', 'nicht', 'abbrechen', 'lass', 'cancel'].some(w => lower.includes(w));
          if (isYes) { confirmPendingAction(); return; }
          if (isNo) { cancelPendingAction(); return; }
        }
        // TTS der vorherigen Antwort sofort stoppen + Timer canceln
        if (ttsTimerRef.current) { clearTimeout(ttsTimerRef.current); ttsTimerRef.current = null; }
        NativeModules.PiperTTS?.stopPiper?.(); NativeModules.TTSModule?.stop?.();
        lastInputWasVoiceRef.current = true;
        earlyTTSFiredRef.current = null;
        sendMessage(trimmed, undefined, 'android', (sentence) => {
          earlyTTSFiredRef.current = sentence;
          speakViaKokoro(sentence);
        });
      },
      [sendMessage, confirmPendingAction, cancelPendingAction, speakViaKokoro],
    ),
  });

  // DONNA-159: OfflineSTT als bevorzugter STT-Pfad (Samsung Gauss NPU, offline)
  // Prüft beim Mount ob On-Device-STT verfügbar ist. Falls ja, überschreibt
  // startListening/stopListening mit OfflineSTT-Implementierung.
  const offlineSTTAvailableRef = React.useRef(false);
  useEffect(() => {
    let mounted = true;
    let resultSub: ReturnType<typeof OfflineSTT.onResult> | null = null;
    let partialSub: ReturnType<typeof OfflineSTT.onPartial> | null = null;
    let errorSub: ReturnType<typeof OfflineSTT.onError> | null = null;

    OfflineSTT.isAvailable().then(available => {
      if (!mounted || !available) return;
      offlineSTTAvailableRef.current = true;
      console.log('[OfflineSTT] Gauss NPU verfügbar — On-Device-STT aktiv');
      resultSub = OfflineSTT.onResult(text => {
        if (text.trim()) {
          lastInputWasVoiceRef.current = true;
          earlyTTSFiredRef.current = null;
          sendMessage(text.trim(), undefined, 'android', ttsEnabled ? (sentence: string) => {
            earlyTTSFiredRef.current = sentence;
            speakViaKokoro(sentence);
          } : undefined);
        }
      });
      partialSub = OfflineSTT.onPartial(text => {
        console.log('[OfflineSTT] partial:', text);
      });
      errorSub = OfflineSTT.onError(code => {
        console.warn('[OfflineSTT] error code:', code, '— Fallback auf Cloud-STT');
        offlineSTTAvailableRef.current = false;
      });
    });

    return () => {
      mounted = false;
      resultSub?.remove();
      partialSub?.remove();
      errorSub?.remove();
    };
  }, [sendMessage]);

  const isListening = voiceState === 'listening';
  const hasMessages = messages.length > 0;

  const handleSend = async () => {
    const text = inputText.trim();
    if (!text) {return;}
    // TTS der vorherigen Antwort sofort stoppen + Timer canceln (immer, auch bei isLoading)
    if (ttsTimerRef.current) { clearTimeout(ttsTimerRef.current); ttsTimerRef.current = null; }
    NativeModules.PiperTTS?.stopPiper?.(); NativeModules.TTSModule?.stop?.();
    if (isLoading) {return;}
    const quote = replyTo;
    setInputText('');
    setReplyTo(null);
    earlyTTSFiredRef.current = null;
    const onEarlyTTSCallback = ttsEnabled ? (sentence: string) => {
      earlyTTSFiredRef.current = sentence;
      speakViaKokoro(sentence);
    } : undefined;
    await sendMessage(text, quote ?? undefined, 'android', onEarlyTTSCallback);
    setTimeout(() => flatListRef.current?.scrollToEnd({animated: true}), 100);
  };

  const handleVoicePress = useCallback(async () => {
    if (isListening) {
      await stopListening();
      return;
    }
    const granted = await requestMicPermission();
    if (!granted) {return;}
    await startListening();
  }, [isListening, stopListening, startListening]);

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
  useEffect(() => {
    // @ts-ignore
    proactiveChatRef.current = (payload: ProactiveChatPayload) => {
      const message = payload?.message ?? (payload as unknown as string);
      console.log('[ChatScreen] proactiveChatRef invoked, message=', String(message).slice(0, 60));
      if (!message?.trim()) {
        console.warn('[ChatScreen] proactive message leer — ignoriert');
        return;
      }
      startProactiveChat(message, payload?.session_id);
      console.log('[ChatScreen] startProactiveChat aufgerufen — setMessages erfolgt');
    };
    // DONNA-147: Event kann vor ChatScreen-Mount angekommen sein (Cold Start)
    if (pendingProactiveMessage) {
      const pending = pendingProactiveMessage;
      clearPendingProactiveMessage();
      console.log('[ChatScreen] pendingProactiveMessage gefunden — direkt einspielen');
      // synchron statt setTimeout — Ref ist eine Zeile vorher gesetzt worden
      proactiveChatRef.current?.(pending);
    }
    // DONNA-198 v8: Zusätzlicher unabhängiger Pfad — SharedPreferences direkt pollen.
    // Wenn App.tsx zuvor schon geleert hat → null, kein Effekt. Wenn nicht → wir ziehen.
    if (Platform.OS === 'android' && NativeModules.ProactiveMessageModule) {
      NativeModules.ProactiveMessageModule.getAndClear()
        .then((raw: string | null) => {
          if (!raw) {
            console.log('[ChatScreen] SharedPreferences leer — kein Fallback nötig');
            return;
          }
          console.log('[ChatScreen] Fallback-Pfad: SharedPreferences hatte payload=', raw.slice(0, 80));
          let payload: ProactiveChatPayload;
          try {
            const parsed = JSON.parse(raw);
            payload = {message: parsed.message ?? raw, session_id: parsed.session_id};
          } catch {
            payload = {message: raw};
          }
          proactiveChatRef.current?.(payload);
        })
        .catch((e: unknown) => console.warn('[ChatScreen] Fallback-getAndClear fehlgeschlagen', e));
    }
  }, [startProactiveChat]);

  // Hintergrund-Handling: Aufnahme + TTS stoppen wenn App in Hintergrund geht
  useEffect(() => {
    const sub = AppState.addEventListener('change', nextState => {
      if (nextState !== 'active') {
        if (isListening) {
          stopListening().catch(() => {});
        }
        NativeModules.PiperTTS?.stopPiper?.(); NativeModules.TTSModule?.stop?.();
      }
    });
    return () => sub.remove();
  }, [isListening, stopListening]);

  // ─── Gesprächsmodus (DONNA-15) ───────────────────────────────────────────────
  const [conversationMode, setConversationMode] = useState(false);
  const conversationModeRef = useRef(false);
  const ttsTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Ref synchron halten (für Closures in setTimeout)
  useEffect(() => {
    conversationModeRef.current = conversationMode;
  }, [conversationMode]);

  const toggleConversationMode = useCallback(() => {
    setConversationMode(prev => {
      const next = !prev;
      if (!next && ttsTimerRef.current) {
        clearTimeout(ttsTimerRef.current);
        ttsTimerRef.current = null;
      }
      return next;
    });
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
      NativeModules.PiperTTS?.stopPiper?.(); NativeModules.TTSModule?.stop?.();
    }
  }, []);

  // TTS: Donna-Antwort vorlesen wenn letzter Input per Sprache war (DONNA-36: Kokoro primär)
  // Im Gesprächsmodus: Mic nach TTS automatisch wieder starten
  const lastInputWasVoiceRef = useRef(false);
  const prevMessageCountRef = useRef(0);
  // Early-TTS-Guard: verhindert Doppel-TTS wenn erster Satz schon während Stream gesprochen wurde
  const earlyTTSFiredRef = useRef<string | null>(null);

  // speakViaKokoro: TTS-Prioritätskette (Name bleibt für Abwärtskompatibilität)
  // DONNA-38: Kotlin-Signatur ist (text, Promise) — KEINE Callback-Funktionen
  // übergeben (Hermes wirft "JS Functions are not convertible to dynamic")
  // DONNA-192: Piper ONNX (on-device, ~80-150ms) → Samsung Neural TTS (~60ms) → Kokoro-Backend
  const speakViaKokoro = useCallback((text: string) => {
    if (!ttsEnabled || !text?.trim()) return;
    markLatency('tts_start');

    // Stufe 1: Piper ONNX (DONNA-192) — lokale ONNX-Inferenz, keine Cloud-Abhängigkeit
    NativeModules.PiperTTS?.speakPiper?.(text)
      ?.then((result: string) => {
        if (result === 'ok') {
          measureLatency('tts_start', 'tts_end');
          return;
        }
        // "fallback" → Modell noch nicht bereit → Stufe 2: Samsung Neural TTS
        NativeModules.TTSModule?.isOnDeviceReady?.()
          ?.then((ready: boolean) => {
            if (ready) {
              // Stufe 2: Samsung Neural TTS (kein Netz, ~60ms)
              NativeModules.TTSModule?.speakOnDevice?.(text)
                ?.then(() => measureLatency('tts_start', 'tts_end'))
                ?.catch(() => {
                  // Stufe 3: Kokoro-Backend (Server-TTS)
                  NativeModules.TTSModule?.speakViaKokoro?.(text)
                    ?.catch((e: unknown) => console.warn('[TTS] Kokoro-Fallback fehlgeschlagen:', e));
                });
            } else {
              // Stufe 3: Kokoro-Backend
              NativeModules.TTSModule?.speakViaKokoro?.(text)
                ?.catch((e: unknown) => console.warn('[TTS] Kokoro-Fallback fehlgeschlagen:', e));
            }
          })
          ?.catch(() => {
            NativeModules.TTSModule?.speakViaKokoro?.(text)
              ?.catch((e: unknown) => console.warn('[TTS] Kokoro-Fallback fehlgeschlagen:', e));
          });
      })
      ?.catch(() => {
        // PiperTTS-Modul nicht verfügbar → direkt zu Stufe 2
        NativeModules.TTSModule?.isOnDeviceReady?.()
          ?.then((ready: boolean) => {
            if (ready) {
              NativeModules.TTSModule?.speakOnDevice?.(text)
                ?.catch(() => {
                  NativeModules.TTSModule?.speakViaKokoro?.(text)
                    ?.catch((e: unknown) => console.warn('[TTS] Fallback fehlgeschlagen:', e));
                });
            } else {
              NativeModules.TTSModule?.speakViaKokoro?.(text)
                ?.catch((e: unknown) => console.warn('[TTS] Fallback fehlgeschlagen:', e));
            }
          })
          ?.catch(() => {
            NativeModules.TTSModule?.speakViaKokoro?.(text)
              ?.catch((e: unknown) => console.warn('[TTS] Fallback fehlgeschlagen:', e));
          });
      });
  }, [ttsEnabled]);

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
        // Ref sofort nullen — verhindert Race Condition bei schnell folgender zweiter Anfrage
        const earlyText = earlyTTSFiredRef.current;
        earlyTTSFiredRef.current = null;
        lastInputWasVoiceRef.current = false;
        // Early TTS hat ersten Satz gesprochen — verbleibenden Rest sprechen
        if (earlyText) {
          // startsWith-Guard: sichert ab dass earlyText wirklich Präfix ist (Backend könnte Text normalisieren)
          const remaining = last.content.startsWith(earlyText)
            ? last.content.slice(earlyText.length).trimStart()
            : last.content; // Fallback: ganzen Text sprechen wenn Präfix-Garantie verletzt
          if (remaining.length > 0) { speakViaKokoro(remaining); }
        } else {
          speakViaKokoro(last.content);
        }

        // Gesprächsmodus: nach TTS-Ende Mic automatisch wieder aktivieren
        // Timer-Schätzung: ~70ms pro Zeichen + 800ms Puffer (konservativ für Netzwerk-Latenz)
        if (conversationModeRef.current) {
          const ttsLength = earlyText ? last.content.slice(earlyText.length).trimStart().length : last.content.length;
          const estimatedMs = Math.max(3000, ttsLength * 70 + 800);
          if (ttsTimerRef.current) clearTimeout(ttsTimerRef.current);
          ttsTimerRef.current = setTimeout(() => {
            // Nur starten wenn Modus noch aktiv + App im Vordergrund + nicht bereits zuhörend
            if (conversationModeRef.current && AppState.currentState === 'active') {
              startListening().catch(() => {});
              lastInputWasVoiceRef.current = true;
            }
          }, estimatedMs);
        }
      }
    }
  }, [messages, startListening, speakViaKokoro]);

  // Cleanup Timer bei Unmount
  useEffect(() => {
    return () => {
      if (ttsTimerRef.current) clearTimeout(ttsTimerRef.current);
    };
  }, []);

  // Keyboard-Höhe manuell tracken (zuverlässiger als KAV auf Android edge-to-edge)
  // endCoordinates.height = volle Tastaturhöhe inkl. Gesten-Bar → direkt als Padding nutzen
  const [kbHeight, setKbHeight] = useState(0);
  useEffect(() => {
    const show = Keyboard.addListener('keyboardDidShow', e => {
      // endCoordinates.height enthält NICHT die Gesten-Navigationsleiste (insets.bottom)
      // → beide addieren damit die InputRow über Tastatur UND Gesten-Bar liegt
      setKbHeight(e.endCoordinates.height + insets.bottom);
    });
    const hide = Keyboard.addListener('keyboardDidHide', () => setKbHeight(0));
    return () => { show.remove(); hide.remove(); };
  }, [insets.bottom]);

  return (
    <View style={[styles.container, {paddingBottom: kbHeight}]}>
      <MessageActionSheet
        state={actionSheet}
        onClose={() => setActionSheet(s => ({...s, visible: false}))}
      />

      <HistoryPanel
        visible={historyVisible}
        onClose={() => setHistoryVisible(false)}
        onLoadSession={(msgs) => {
          // SessionMessage → ChatMessage konvertieren und in Chat laden
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
      <View style={[styles.header, {paddingTop: insets.top + 8}]}>
        <TouchableOpacity style={styles.headerLeft} onPress={() => setHistoryVisible(true)}
          hitSlop={{top: 8, bottom: 8, left: 8, right: 8}}>
          <DonnaAvatar size={28} />
          <Text style={styles.headerTitle}>DONNA</Text>
        </TouchableOpacity>
        <View style={styles.headerRight}>
          {/* TTS-Toggle (DONNA-36) */}
          <TouchableOpacity
            onPress={() => setTtsEnabled(!ttsEnabled)}
            hitSlop={{top: 8, bottom: 8, left: 8, right: 8}}
            style={[styles.newChatBtn, !ttsEnabled && styles.ttsDisabledBtn]}
            accessibilityLabel={ttsEnabled ? 'TTS deaktivieren' : 'TTS aktivieren'}>
            <Text style={styles.newChatIcon}>{ttsEnabled ? '🔊' : '🔇'}</Text>
          </TouchableOpacity>
          {/* Gesprächsmodus Toggle (DONNA-15) */}
          {isAvailable && (
            <TouchableOpacity
              onPress={toggleConversationMode}
              hitSlop={{top: 8, bottom: 8, left: 8, right: 8}}
              style={[styles.newChatBtn, conversationMode && styles.convModeActive]}>
              <Text style={styles.newChatIcon}>🔄</Text>
            </TouchableOpacity>
          )}
          {messages.length > 0 && (
            <TouchableOpacity
              onPress={clearMessages}
              hitSlop={{top: 8, bottom: 8, left: 8, right: 8}}
              style={styles.newChatBtn}>
              <Text style={styles.newChatIcon}>✏️</Text>
            </TouchableOpacity>
          )}
          <View style={styles.statusDot} />
        </View>
      </View>

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
        <IdleScreen onVoicePress={handleVoicePress} isAvailable={isAvailable} />
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
        <DonnaOrb listening={isListening} />
        <Waveform active={isListening} />
        {partialTranscript ? (
          <Text style={styles.partialText}>{partialTranscript}</Text>
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
      {contactFallback != null ? (
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
      <View style={[styles.inputRow, {paddingBottom: kbHeight > 0 ? 8 : insets.bottom + 8}]}>
        {isAvailable && (
          <MicButton active={isListening} onPress={handleVoicePress} />
        )}
        <TextInput
          style={styles.input}
          value={inputText}
          onChangeText={setInputText}
          placeholder={isListening ? 'Zuhören…' : 'Nachricht eingeben…'}
          placeholderTextColor={P.muted}
          multiline
          onSubmitEditing={handleSend}
          editable={!isListening}
          selectionColor={P.accent}
        />
        <TouchableOpacity
          style={[
            styles.sendButton,
            (!inputText.trim() || isLoading || isListening) && styles.sendButtonDisabled,
          ]}
          onPress={handleSend}
          disabled={!inputText.trim() || isLoading || isListening}
          accessibilityLabel="Nachricht senden">
          <Text style={styles.sendIcon}>➤</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {flex: 1, backgroundColor: P.bg},

  // Header
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 20,
    paddingBottom: 12,
    backgroundColor: P.surface,
    borderBottomWidth: 1,
    borderBottomColor: P.border,
  },
  headerLeft: {flexDirection: 'row', alignItems: 'center', gap: 10},
  headerRight: {flexDirection: 'row', alignItems: 'center', gap: 12},
  newChatBtn: {padding: 4},
  newChatIcon: {fontSize: 18},
  convModeActive: {
    backgroundColor: 'rgba(56,189,248,0.2)',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: P.accent,
  },
  ttsDisabledBtn: {
    opacity: 0.45,
  },
  headerTitle: {
    color: P.accent,
    fontSize: 15,
    fontWeight: '700',
    letterSpacing: 3,
  },
  statusDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: '#22c55e',
  },

  // DONNA-189: Phi-3 Mini Download-Banner
  phiBanner: {
    backgroundColor: 'rgba(56,189,248,0.10)',
    borderBottomWidth: 1,
    borderBottomColor: 'rgba(56,189,248,0.25)',
    paddingHorizontal: 16,
    paddingVertical: 8,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  phiBannerText: {
    color: P.accent2,
    fontSize: 12,
    flex: 1,
  },
  phiBannerBtn: {
    marginLeft: 12,
    backgroundColor: P.accent,
    borderRadius: 6,
    paddingHorizontal: 10,
    paddingVertical: 4,
  },
  phiBannerBtnText: {
    color: P.bg,
    fontSize: 12,
    fontWeight: '600',
  },

  // Messages
  messageList: {
    padding: 16,
    paddingBottom: 8,
    flexGrow: 1,
  },

  // Listening panel
  listeningPanel: {
    backgroundColor: P.surface,
    borderTopWidth: 1,
    borderTopColor: P.border,
    paddingBottom: 8,
  },
  listeningHint: {
    color: P.muted,
    fontSize: 13,
    textAlign: 'center',
    paddingBottom: 8,
    fontStyle: 'italic',
  },
  partialText: {
    color: P.accent2,
    fontSize: 14,
    textAlign: 'center',
    paddingHorizontal: 20,
    paddingBottom: 8,
    fontStyle: 'italic',
  },

  // Typing
  typingWrap: {
    paddingHorizontal: 16,
    paddingTop: 4,
  },
  rateLimitText: {
    fontSize: 11,
    color: '#888',
    fontStyle: 'italic',
    marginTop: 2,
    marginLeft: 4,
  },

  // Input row
  inputRow: {
    flexDirection: 'row',
    paddingTop: 10,
    paddingHorizontal: 12,
    paddingBottom: 10,
    backgroundColor: P.surface,
    borderTopWidth: 1,
    borderTopColor: P.border,
    alignItems: 'flex-end',
    gap: 8,
  },
  input: {
    flex: 1,
    borderWidth: 1,
    borderColor: 'rgba(56,189,248,0.2)',
    borderRadius: 22,
    paddingHorizontal: 16,
    paddingVertical: 10,
    fontSize: 15,
    maxHeight: 120,
    color: P.text,
    backgroundColor: P.card,
  },
  sendButton: {
    width: 44,
    height: 44,
    borderRadius: 22,
    backgroundColor: P.accent,
    justifyContent: 'center',
    alignItems: 'center',
  },
  sendButtonDisabled: {
    backgroundColor: 'rgba(56,189,248,0.2)',
  },
  sendIcon: {
    color: P.bg,
    fontSize: 16,
    fontWeight: '700',
  },
});

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
  useColorScheme,
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
import {isEchoOfSpoken, EchoStrikes, appendSpoken} from '../utils/echoGuard';
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
import {parseConfirmation} from '../utils/voiceConfirm';
import {triggerAutoSend} from '../utils/autoSend';
import {contactActionFields} from '../utils/actionSummary';
import {
  weatherKind, weatherTint, gradientStops, glyphPalette, isNightHour, hourFromLabel, currentHourIndex, averagePrecip,
} from '../utils/weatherLook';
import {WeatherGlyph, DropIcon, WindIcon} from '../components/WeatherGlyph';
import {CalendarIcon, PinIcon, ChevronRightIcon} from '../components/CardIcons';
import {useTheme, useThemedStyles} from '../theme';
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

const {AlarmModule, AccessibilityBridge} = NativeModules;

// ─── TTS-Konstante ────────────────────────────────────────────────────────────
const TTS_ENABLED_KEY = 'donna_tts_enabled';

// ─── Theme ────────────────────────────────────────────────────────────────────
// UX-01: Farben kommen aus der zentralen Theme-Quelle (src/theme): Dark/Light + System-/Material-You-
// Farben, Pulse-Cyan nur noch als Fallback. Stylesheets sind Factories `make...Styles(P)` und werden in den
// Komponenten ueber useThemedStyles() gebunden; `P` ist dort das aktuelle Theme (useTheme()).

// ─── DonnaAvatar (Hexagon-ish with D) ────────────────────────────────────────
function DonnaAvatar({size = 32}: {size?: number}): React.JSX.Element {
  const avatarStyles = useThemedStyles(makeAvatarStyles);
  return (
    <View style={[avatarStyles.wrap, {width: size, height: size, borderRadius: size * 0.22}]}>
      <Text style={[avatarStyles.letter, {fontSize: size * 0.48}]}>D</Text>
    </View>
  );
}
const makeAvatarStyles = (P: Theme) => StyleSheet.create({
  wrap: {
    backgroundColor: P.accent,
    justifyContent: 'center',
    alignItems: 'center',
  },
  letter: {
    color: P.onAccent,
    fontWeight: '700',
  },
});

// ─── DonnaOrb (animated pulsing sphere) ──────────────────────────────────────
function DonnaOrb({listening}: {listening: boolean}): React.JSX.Element {
  const orbStyles = useThemedStyles(makeOrbStyles);
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
const makeOrbStyles = (P: Theme) => StyleSheet.create({
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
    backgroundColor: P.accentSoft,
    borderWidth: 1,
    borderColor: P.accentBorder,
  },
  ring1: {
    position: 'absolute',
    width: 88,
    height: 88,
    borderRadius: 44,
    backgroundColor: P.accentSoftStrong,
    borderWidth: 1,
    borderColor: P.accentBorder,
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
  const waveStyles = useThemedStyles(makeWaveStyles);
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
const makeWaveStyles = (P: Theme) => StyleSheet.create({
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
// Optik-Update: zustandsabhängiger, dezenter Verlauf (ohne Bibliothek), konsistenter Icon-Satz statt Emoji,
// klarere Hierarchie. Datenvertrag (WeatherCardData + hourly) unverändert.
interface HourlyEntry {time: string; temp_c: number; icon: string; precip_pct: number}

/** Weicher Verlauf aus gestapelten Streifen (oben getönt → unten transparent). */
function WeatherBackdrop({stops}: {stops: string[]}): React.JSX.Element {
  return (
    <View style={StyleSheet.absoluteFill} pointerEvents="none" testID="weather-backdrop">
      {stops.map((c, i) => (
        <View key={i} style={{flex: 1, backgroundColor: c}} />
      ))}
    </View>
  );
}

function WeatherCard({data}: {data: WeatherCardData & {hourly?: HourlyEntry[]}}): React.JSX.Element {
  const dark = useColorScheme() === 'dark';
  const weatherStyles = useThemedStyles(makeWeatherStyles);
  const hourly: HourlyEntry[] = (data as any).hourly ?? [];
  const nowH = new Date().getHours();
  const night = isNightHour(nowH);
  const kind = weatherKind(data.condition_icon, data.condition, night);
  const stops = gradientStops(weatherTint(kind, dark));
  const glyphs = glyphPalette(dark);
  // Aktuelle Stunde → passenden Hourly-Slot markieren
  const currentIdx = currentHourIndex(hourly, nowH);
  const precip = averagePrecip(hourly);
  const weekday = new Date().toLocaleDateString('de-DE', {weekday: 'long'});
  return (
    <View style={weatherStyles.card} testID="weather-card">
      <WeatherBackdrop stops={stops} />
      {/* Kopfzeile: Wochentag · Ort (Satzschreibung) */}
      <Text style={weatherStyles.location} numberOfLines={1}>
        {weekday} · {data.location}
      </Text>
      {/* Temperatur (groß) + Icon */}
      <View style={weatherStyles.topRow}>
        <View style={{flex: 1}}>
          <Text style={weatherStyles.temp}>{data.temp_c}°</Text>
          <Text style={weatherStyles.feels}>Gefühlt {data.feels_like_c}°</Text>
          <Text style={weatherStyles.condition}>{data.condition}</Text>
        </View>
        <WeatherGlyph kind={kind} size={68} palette={glyphs} testID="weather-glyph" />
      </View>
      {/* Höchst/Tief als eigene, klare Zeile */}
      <View style={weatherStyles.hiLoRow}>
        <Text style={weatherStyles.hiLoItem}>
          <Text style={weatherStyles.arrowUp}>↑ </Text>{data.temp_max}°
        </Text>
        <Text style={weatherStyles.hiLoItem}>
          <Text style={weatherStyles.arrowDown}>↓ </Text>{data.temp_min}°
        </Text>
      </View>
      {/* Stundenvorschau (wie Gemini) */}
      {hourly.length > 0 && (
        <>
          <View style={weatherStyles.divider} />
          <ScrollView horizontal showsHorizontalScrollIndicator={false} style={weatherStyles.hourlyScroll}>
            {hourly.map((h, i) => {
              const isCurrent = i === currentIdx;
              const hh = hourFromLabel(h.time);
              return (
                <View key={i} style={[
                  weatherStyles.hourlyItem,
                  isCurrent && weatherStyles.hourlyItemCurrent,
                ]}>
                  <Text style={[weatherStyles.hourlyTime, isCurrent && weatherStyles.hourlyTimeCurrent]}>
                    {isCurrent ? 'Jetzt' : h.time}
                  </Text>
                  <WeatherGlyph
                    kind={weatherKind(h.icon, null, hh !== null && isNightHour(hh))}
                    size={28}
                    palette={glyphs}
                  />
                  <Text style={weatherStyles.hourlyPrecip}>{h.precip_pct} %</Text>
                  <Text style={[weatherStyles.hourlyTemp, isCurrent && weatherStyles.hourlyTempCurrent]}>
                    {h.temp_c}°
                  </Text>
                </View>
              );
            })}
          </ScrollView>
        </>
      )}
      {/* Details: Luftfeuchte · Wind · Niederschlag (eigene Werte, nicht ins Fließtext-subInfo gequetscht) */}
      <View style={weatherStyles.statRow} testID="weather-stats">
        <View style={weatherStyles.stat}>
          <View style={weatherStyles.statValueRow}>
            <DropIcon size={14} color={glyphs.drop} />
            <Text style={weatherStyles.statValue}>{data.humidity} %</Text>
          </View>
          <Text style={weatherStyles.statLabel}>Luftfeuchte</Text>
        </View>
        <View style={weatherStyles.stat}>
          <View style={weatherStyles.statValueRow}>
            <WindIcon size={14} color={glyphs.line} />
            <Text style={weatherStyles.statValue}>{data.wind_kmh} km/h</Text>
          </View>
          <Text style={weatherStyles.statLabel}>Wind</Text>
        </View>
        {precip !== null && (
          <View style={weatherStyles.stat}>
            <View style={weatherStyles.statValueRow}>
              <WeatherGlyph kind="rain" size={16} palette={glyphs} />
              <Text style={weatherStyles.statValue}>{precip} %</Text>
            </View>
            <Text style={weatherStyles.statLabel}>Niederschlag</Text>
          </View>
        )}
      </View>
    </View>
  );
}
const makeWeatherStyles = (P: Theme) => {
  const I = makeInfoCardStyles(P);
  return StyleSheet.create({
    card: {...I.surface},
    location: {...I.eyebrow, marginBottom: 12},
    topRow: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      marginBottom: 16,
    },
    temp: {color: P.text, fontSize: 64, fontWeight: '200', lineHeight: 68},
    feels: {...I.caption, marginTop: 2},
    condition: {...I.title, marginTop: 8},
    hiLoRow: {flexDirection: 'row', gap: 10},
    hiLoItem: {
      ...I.pill,
      color: P.text,
      fontSize: 14,
      fontWeight: '600',
    },
    arrowUp: {color: P.accent, fontWeight: '700'},
    arrowDown: {color: P.accent2, fontWeight: '700'},
    divider: {...I.divider, marginTop: 18, marginBottom: 12},
    hourlyScroll: {marginBottom: 16},
    hourlyItem: {
      alignItems: 'center',
      marginRight: 8,
      minWidth: 60,
      paddingVertical: 10,
      paddingHorizontal: 8,
      borderRadius: INFO_CARD.innerRadius,
      gap: 4,
    },
    hourlyItemCurrent: {
      backgroundColor: P.accentSoftStrong,
    },
    hourlyTime: {color: P.muted, fontSize: 13},
    hourlyTimeCurrent: {color: P.accent2, fontWeight: '700'},
    hourlyPrecip: {color: P.muted, fontSize: 12},
    hourlyTemp: {color: P.text, fontSize: 15, fontWeight: '500'},
    hourlyTempCurrent: {fontWeight: '700'},
    // Details als eigene, getönte Kachel (One-UI-Gruppierung) statt loser Zeile mit Trennlinie
    statRow: {
      flexDirection: 'row',
      marginTop: 18,
      paddingVertical: 14,
      paddingHorizontal: 8,
      borderRadius: INFO_CARD.innerRadius,
      backgroundColor: P.accentSoft,
    },
    stat: {flex: 1, alignItems: 'center', gap: 2},
    statValueRow: {flexDirection: 'row', alignItems: 'center', gap: 6},
    statValue: {color: P.text, fontSize: 14, fontWeight: '600'},
    statLabel: {color: P.muted, fontSize: 13},
  });
};

// ─── MarkdownText — rendert **bold**, • bullets, URLs klickbar ───────────────
function MarkdownText({text, style: baseStyle}: {text: string; style?: object}): React.JSX.Element {
  const mdSt = useThemedStyles(makeMdStyles);
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
const makeMdStyles = (P: Theme) => StyleSheet.create({
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
  const asStyles = useThemedStyles(makeActionSheetStyles);
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
const makeActionSheetStyles = (P: Theme) => StyleSheet.create({
  overlay: {flex: 1, backgroundColor: P.scrim, justifyContent: 'flex-end'},
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
// Optik: gleiche Kartenfläche wie Wetter/Kalender (One-UI-Look), Ort-Icon in getöntem Badge,
// Ziel als Titel + Chevron-Button. Verhalten unverändert: Tipp öffnet data.maps_url.
function MapCard({data}: {data: MapCardData}): React.JSX.Element {
  const mapStyles = useThemedStyles(makeMapStyles);
  const theme = useTheme();
  return (
    <TouchableOpacity
      style={mapStyles.card}
      activeOpacity={0.85}
      onPress={() => Linking.openURL(data.maps_url)}
      accessibilityRole="button"
      accessibilityLabel="In Google Maps öffnen"
      testID="map-card">
      <View style={mapStyles.row}>
        <View style={mapStyles.badge}>
          <PinIcon size={22} color={theme.accent} />
        </View>
        <View style={mapStyles.textCol}>
          <Text style={mapStyles.eyebrow}>In Google Maps öffnen</Text>
          <Text style={mapStyles.query} numberOfLines={2}>{data.query}</Text>
        </View>
        <View style={mapStyles.chevron}>
          <ChevronRightIcon size={20} color={theme.accent} />
        </View>
      </View>
    </TouchableOpacity>
  );
}
const makeMapStyles = (P: Theme) => {
  const I = makeInfoCardStyles(P);
  return StyleSheet.create({
    // Kompakter als Wetter/Kalender (eine Zeile), aber gleicher Radius/Schatten/Rahmen.
    card: {...I.surface, paddingVertical: 14, paddingHorizontal: 16, minHeight: 72, justifyContent: 'center'},
    row: {flexDirection: 'row', alignItems: 'center', gap: 14},
    badge: {...I.badge, width: 44, height: 44, borderRadius: 22},
    textCol: {flex: 1},
    eyebrow: {...I.eyebrow, color: P.accent, fontWeight: '600'},
    query: {...I.title, marginTop: 1},
    chevron: {...I.badge, width: 36, height: 36, borderRadius: 18},
  });
};

// ─── CalendarCard ─────────────────────────────────────────────────────────────
// DONNA — Kalender-Karte (Stufe 1, read-only). Gleiche Kartenfläche/Radius/Rahmen/Elevation
// wie WeatherCard/MapCard (Bixby/Gemini-Look, geteiltes System aus cardStyles.makeInfoCardStyles).
// Zeigt die anstehenden Termine als Liste mit Akzentbalken und Zeitangabe.
// Nur Anzeige — keine Aktion, kein Tap-Handler (die Terminerstellung läuft separat über
// die PendingActionCard mit create_event, die hier bewusst NICHT angefasst wird).
function CalendarCard({data}: {data: CalendarCardData}): React.JSX.Element {
  const calStyles = useThemedStyles(makeCalendarStyles);
  const theme = useTheme();
  const events = data?.events ?? [];
  if (events.length === 0) {
    // Defensive: leere Karten sendet das Backend nicht — aber falls doch, nichts rendern.
    return <View />;
  }
  return (
    <View style={calStyles.card} testID="calendar-card">
      <View style={calStyles.header}>
        <View style={calStyles.badge}>
          <CalendarIcon size={22} color={theme.accent} />
        </View>
        <Text style={calStyles.headerTitle}>Anstehende Termine</Text>
      </View>
      {events.map((ev, i) => (
        <View
          key={i}
          style={[calStyles.eventRow, i < events.length - 1 && calStyles.eventRowDivider]}>
          <View style={calStyles.accentBar} />
          <View style={calStyles.whenCol}>
            <Text style={calStyles.whenLabel}>{ev.when_label}</Text>
            {ev.time ? <Text style={calStyles.timeLabel}>{ev.time}</Text> : null}
          </View>
          <View style={calStyles.detailCol}>
            <Text style={calStyles.title} numberOfLines={2}>{ev.title}</Text>
            {ev.location ? (
              <View style={calStyles.locationRow}>
                <PinIcon size={14} color={theme.muted} />
                <Text style={calStyles.location} numberOfLines={1}>{ev.location}</Text>
              </View>
            ) : null}
          </View>
        </View>
      ))}
    </View>
  );
}
const makeCalendarStyles = (P: Theme) => {
  const I = makeInfoCardStyles(P);
  return StyleSheet.create({
    card: {...I.surface},
    header: {...I.header, marginBottom: 8},
    badge: {...I.badge},
    headerTitle: {...I.title},
    eventRow: {flexDirection: 'row', alignItems: 'stretch', gap: 12, paddingVertical: 14},
    eventRowDivider: {
      borderBottomWidth: StyleSheet.hairlineWidth,
      borderBottomColor: P.border,
    },
    accentBar: {width: 4, borderRadius: 2, backgroundColor: P.accent},
    whenCol: {minWidth: 64, alignItems: 'flex-start', justifyContent: 'center'},
    whenLabel: {color: P.accent2, fontSize: 14, fontWeight: '700'},
    timeLabel: {...I.caption, marginTop: 2},
    detailCol: {flex: 1, justifyContent: 'center'},
    title: {...I.body},
    locationRow: {flexDirection: 'row', alignItems: 'center', gap: 4, marginTop: 4},
    location: {...I.caption, flexShrink: 1},
  });
};

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
  const fbStyles = useThemedStyles(makeFeedbackStyles);
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
        accessibilityRole="button"
        accessibilityLabel="Antwort war hilfreich"
        hitSlop={{top: 12, bottom: 12, left: 12, right: 12}}>
        <Text style={[fbStyles.icon, sent === 'positive' && fbStyles.iconActive]}>👍</Text>
      </TouchableOpacity>
      <TouchableOpacity
        onPress={() => rate('negative')}
        disabled={!!sent}
        style={[fbStyles.btn, sent === 'negative' && fbStyles.btnActive]}
        accessibilityRole="button"
        accessibilityLabel="Antwort war nicht hilfreich"
        hitSlop={{top: 12, bottom: 12, left: 12, right: 12}}>
        <Text style={[fbStyles.icon, sent === 'negative' && fbStyles.iconActive]}>👎</Text>
      </TouchableOpacity>
      {sent && <Text style={fbStyles.thanks}>Danke!</Text>}
    </View>
  );
}
const makeFeedbackStyles = (P: Theme) => StyleSheet.create({
  row: {flexDirection: 'row', alignItems: 'center', gap: 4, marginTop: 4, marginLeft: 32},
  btn: {padding: 4, borderRadius: 8},
  btnActive: {backgroundColor: P.accentSoft},
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
    case 'navigate': return `🗺 Navigation zu ${str(action.destination)} starten?`;
    case TESLA_NAVIGATE: return `🚗 Ziel an Tesla senden: ${getNavTarget(action)}?`;
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
  const cardStyles = useThemedStyles(makeCardStyles);
  // Bixby-Stil: Label/Wert-Paare mit Trennstrichen. Pro Action-Typ andere Felder.
  const renderFields = (): Array<{label: string; value: string; multiline?: boolean; warn?: boolean}> => {
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
        return [{label: 'Ziel', value: str(action.destination)}];
      case TESLA_NAVIGATE: {
        // Aufgeloeste Adresse (Geocoding) wird gesendet; das gesprochene Ziel steht darueber, wenn es abweicht.
        const target = getNavTarget(action);
        const spoken = str(action.destination);
        const fields: Array<{label: string; value: string}> = [{label: 'Ziel an Tesla senden', value: target}];
        if (spoken && spoken !== target) fields.unshift({label: 'Du sagtest', value: spoken});
        return fields;
      }
      case 'call':
      case 'sms':
      case 'whatsapp':
        // BLOCKER 4 (informierte Bestätigung): IMMER Empfänger + Nummer + (bei Nachricht) Text,
        // Nummer aus dem Payload wird sichtbar markiert. Einzige Kontrolle vor dem Auto-Send.
        return contactActionFields(
          {type: action.type, name: action.name, number: action.number, message: action.message},
          contact,
        );
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
  // tesla_navigate ohne Ziel: nichts zu senden -> Bestaetigen sperren (wie "Kontakt fehlt")
  const noTarget = action.type === TESLA_NAVIGATE && !getNavTarget(action);
  const confirmDisabled = noNumber || noTarget;
  const noContactWarn = !contact && (action.name as string) &&
    (action.type === 'call' || action.type === 'sms' || action.type === 'whatsapp');

  return (
    <View style={cardStyles.cardOuter}>
      <View style={cardStyles.cardInner}>
        {fields.map((f, i) => (
          <View key={`${f.label}-${i}`}>
            {i > 0 && <View style={cardStyles.divider} />}
            <Text style={f.warn ? [cardStyles.fieldLabel, cardStyles.warnText] : cardStyles.fieldLabel}>{f.label}</Text>
            {/* BLOCKER 2: Nachricht (multiline) NICHT kürzen — Mike muss vor „Ja" den vollen Text sehen,
                der auch vollständig gesendet wird. Nur Kurzfelder bleiben auf 2 Zeilen begrenzt. */}
            <Text style={cardStyles.fieldValue} numberOfLines={f.multiline ? undefined : 2}>{f.value}</Text>
          </View>
        ))}
        {noContactWarn ? (
          <Text style={cardStyles.warnText}>„{str(action.name)}“ nicht in Kontakten gefunden</Text>
        ) : null}
      </View>
      <View style={cardStyles.btnRow}>
        <TouchableOpacity
          onPress={onConfirm}
          disabled={confirmDisabled}
          accessibilityRole="button"
          accessibilityState={{disabled: confirmDisabled}}
          style={[cardStyles.btn, confirmDisabled && cardStyles.btnDisabled]}>
          <Text style={confirmDisabled ? cardStyles.btnText : cardStyles.btnTextPrimary}>
            {noNumber
              ? 'Kontakt fehlt'
              : noTarget
                ? 'Ziel fehlt'
                : action.type === TESLA_NAVIGATE
                  ? 'An Tesla senden'
                  : 'Bestätigen'}
          </Text>
        </TouchableOpacity>
        <View style={cardStyles.btnDivider} />
        <TouchableOpacity onPress={onCancel} style={cardStyles.btn} accessibilityRole="button">
          <Text style={cardStyles.btnText}>Abbrechen</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

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
  const cardStyles = useThemedStyles(makeCardStyles);
  const pickerStyles = useThemedStyles(makePickerStyles);
  return (
    <View style={cardStyles.cardOuter}>
      <View style={cardStyles.cardInner}>
        <Text style={cardStyles.fieldLabel}>Kontakt wählen</Text>
        <Text style={[cardStyles.fieldValue, {fontSize: 18, marginBottom: 8}]}>Mehrere Treffer für „{str(query)}“</Text>
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
  const P = useTheme();
  const cardStyles = useThemedStyles(makeCardStyles);
  const pickerStyles = useThemedStyles(makePickerStyles);
  const fallbackStyles = useThemedStyles(makeFallbackStyles);
  const [inputText, setInputText] = useState('');
  return (
    <View style={cardStyles.cardOuter}>
      <View style={cardStyles.cardInner}>
        <Text style={cardStyles.fieldLabel}>Kontakt nicht gefunden</Text>
        <Text style={[cardStyles.fieldValue, {fontSize: 18, marginBottom: 8}]}>
          „{str(originalName)}“ — wen meinst du?
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
          placeholderTextColor={P.muted}
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
function ActionList({actions, onAction}: {actions: DonnaAction[]; onAction?: (a: DonnaAction) => void}): React.JSX.Element {
  const actionStyles = useThemedStyles(makeActionStyles);
  const labels: Record<string, string> = {
    create_event: '📅 Termin',
    set_alarm: '⏰ Alarm',
    set_timer: '⏲️ Timer',
    navigate: '🧭 Navigation',
    [TESLA_NAVIGATE]: '🚗 An Tesla',
    [NAV_DISAMBIGUATE]: '🧭 Ziel wählen',
    [NAV_NOT_FOUND]: '⚠️ Ziel nicht gefunden',
    call: '📞 Anruf',
    sms: '💬 SMS',
    whatsapp: '💚 WhatsApp',
    play_music: '🎵 Musik',
    note: '📝 Notiz',
    open_url: '🔗 Link',
  };
  const interactiveTypes = new Set([
    'set_alarm', 'set_timer', 'create_event', 'navigate', TESLA_NAVIGATE, NAV_DISAMBIGUATE, NAV_NOT_FOUND,
    'call', 'sms', 'whatsapp', 'open_url', 'play_music',
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
        } else if (a.type === 'navigate' || a.type === TESLA_NAVIGATE) {
          desc = getNavTarget(a);
        } else if (a.type === NAV_DISAMBIGUATE || a.type === NAV_NOT_FOUND) {
          desc = str(a.query);
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
const makeActionStyles = (P: Theme) => StyleSheet.create({
  row: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    marginTop: 6,
    gap: 6,
  },
  chip: {
    backgroundColor: P.accentSoftStrong,
    borderColor: P.accentBorderStrong,
    borderWidth: 1.5,
    borderRadius: 14,
    paddingHorizontal: 14,
    paddingVertical: 10,
    flexDirection: 'row',
    alignItems: 'center',
    maxWidth: '100%',
    minHeight: 48,
    minWidth: 48,
  },
  chipLabel: {color: P.accent2, fontSize: 15, fontWeight: '700', marginRight: 8},
  chipDesc: {color: P.text, fontSize: 13, flexShrink: 1, fontWeight: '500'},
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
  const bubbleStyles = useThemedStyles(makeBubbleStyles);
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
        {!isUser && message.card?.card_type === 'calendar' && (
          <CalendarCard data={message.card.data as CalendarCardData} />
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
const makeBubbleStyles = (P: Theme) => StyleSheet.create({
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
  const typingStyles = useThemedStyles(makeTypingStyles);
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
const makeTypingStyles = (P: Theme) => StyleSheet.create({
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

// ─── Freisprech-Gesprächsmodus ────────────────────────────────────────────────
const CONVERSATION_MODE_KEY = '@donna_conversation_mode';
const VOICE_BARGE_IN_KEY = '@donna_voice_bargein';
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
/**
 * Voice-Barge-in (Mikrofon offen WÄHREND Donna spricht) ist standardmäßig AN (Opt-out, Voice-Batch). Schutz gegen
 * Selbst-Auslösung/Lärm: AEC-Pflicht, Lernphase für Donnas Echo, Sprachband-VAD + Stimm-Ähnlichkeit + Umgebungsboden
 * (BargeInVad), Echo-Filter auf den erkannten Text und Strikes (2 Fehlauslöser → aus). 90-s-Hard-Cap und Abbruch im
 * Hintergrund/gesperrt bleiben. Ausschalten: lange auf den Gesprächs-Schalter tippen (wird gemerkt).
 */
export const VOICE_BARGE_IN_DEFAULT = true;
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

// ─── MicButton ────────────────────────────────────────────────────────────────
function MicButton({active, interrupting = false, onPress}: {active: boolean; interrupting?: boolean; onPress: () => void}): React.JSX.Element {
  const micStyles = useThemedStyles(makeMicStyles);
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
    <TouchableOpacity
      onPress={onPress}
      accessibilityLabel={active ? 'Aufnahme stoppen' : interrupting ? 'Donna unterbrechen und sprechen' : 'Spracheingabe'}>
      <Animated.View style={[micStyles.btn, active && micStyles.btnActive, {transform: [{scale}]}]}>
        <Text style={micStyles.icon}>{active ? '⏹' : '🎤'}</Text>
      </Animated.View>
    </TouchableOpacity>
  );
}
const makeMicStyles = (P: Theme) => StyleSheet.create({
  btn: {
    width: 48,
    height: 48,
    borderRadius: 24,
    backgroundColor: P.card,
    borderWidth: 1,
    borderColor: P.accentBorder,
    justifyContent: 'center',
    alignItems: 'center',
  },
  btnActive: {
    backgroundColor: P.accentSoftStrong,
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
  const idleStyles = useThemedStyles(makeIdleStyles);
  return (
    <View style={idleStyles.container}>
      <Text style={idleStyles.statusLabel}>Wie kann ich helfen?</Text>
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
const makeIdleStyles = (P: Theme) => StyleSheet.create({
  container: {flex: 1, alignItems: 'center', justifyContent: 'center', paddingBottom: 40},
  statusLabel: {
    color: P.text,
    fontSize: 18,
    fontWeight: '500',
    textAlign: 'center',
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
    borderColor: P.accentBorder,
    backgroundColor: P.accentSoft,
  },
  voiceChipText: {
    color: P.accent2,
    fontSize: 14,
    fontWeight: '500',
  },
});

// ─── ReplyPreview ─────────────────────────────────────────────────────────────
function ReplyPreview({text, onCancel}: {text: string; onCancel: () => void}): React.JSX.Element {
  const replyStyles = useThemedStyles(makeReplyStyles);
  return (
    <View style={replyStyles.wrap}>
      <View style={replyStyles.bar} />
      <Text style={replyStyles.text} numberOfLines={2}>{text}</Text>
      <TouchableOpacity
        onPress={onCancel}
        hitSlop={{top: 14, bottom: 14, left: 14, right: 14}}
        accessibilityRole="button"
        accessibilityLabel="Antwort abbrechen">
        <Text style={replyStyles.close}>✕</Text>
      </TouchableOpacity>
    </View>
  );
}
const makeReplyStyles = (P: Theme) => StyleSheet.create({
  wrap: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: P.accentSoft,
    borderTopWidth: 1,
    borderTopColor: P.border,
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
  const P = useTheme();
  const hStyles = useThemedStyles(makeHistoryStyles);
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
          <Text style={hStyles.title} accessibilityRole="header">Gesprächsverlauf</Text>
          <TouchableOpacity
            onPress={onClose}
            hitSlop={{top: 14, bottom: 14, left: 14, right: 14}}
            accessibilityRole="button"
            accessibilityLabel="Verlauf schließen">
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

const makeHistoryStyles = (P: Theme) => StyleSheet.create({
  backdrop: {flex: 1, backgroundColor: P.scrim},
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
  title: {color: P.accent, fontSize: 15, fontWeight: '700'},
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
        case TESLA_NAVIGATE:
          // Ziel lautlos an die Tesla-App (ShareActivity) senden; fängt Fehler selbst ab → Text zurück.
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
              ? `Anruf an ${contact?.name ?? safe} wird gestartet ✓`
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
              ? `WhatsApp an ${contact?.name ?? phone} gesendet ✓`
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

  // Treffer der Geocoding-Rückfrage gewählt: der Tipp ist die Bestätigung → Ziel direkt ausführen
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
  //  b) sofort neu fragen (Mic-Tap → Zuhören, oder Senden → ersetzt die laufende Anfrage).
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
  const bargeInDisabledRef = useRef(false);               // wegen Echo abgeschaltet
  const echoStrikesRef = useRef(new EchoStrikes(2));
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
  // OfflineSTT-Watchdogs (der Erkenner kann ohne Ergebnis stumm bleiben → Mic nie unbegrenzt offen lassen)
  const offlineNoPartialTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const offlineMaxTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const offlineResultWatchdogRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const clearOfflineTimers = useCallback(() => {
    for (const ref of [offlineNoPartialTimerRef, offlineMaxTimerRef, offlineResultWatchdogRef]) {
      if (ref.current) { clearTimeout(ref.current); ref.current = null; }
    }
  }, []);

  /** Nutzer hat selbst eingegriffen (Mic, Tippen, Senden, Stopp) → Freisprech-Zähler zurücksetzen. */
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
   * Ende einer Barge-in-Sitzung. Ohne echte Äußerung (Stille, Fehler, Echo) zählt ein Strike; zwei Strikes
   * schalten Voice-Barge-in ab. Echte Sprache setzt die Strikes zurück.
   */
  const endBargeInSession = useCallback((genuine: boolean) => {
    if (!voiceBargeInSessionRef.current) { return; }
    voiceBargeInSessionRef.current = false;
    if (genuine) {
      echoStrikesRef.current.reset();
    } else if (echoStrikesRef.current.record()) {
      bargeInDisabledRef.current = true;
      try { NativeModules.BargeInModule?.setDisabled?.(true); } catch { /* ignorieren */ }
      console.warn('[BargeIn] nach wiederholten Fehlauslösern abgeschaltet — Fallback: Mikrofon erst nach TTS-Ende');
      // Sichtbar machen: sonst wundert sich Mike, warum das Unterbrechen per Stimme plötzlich nicht mehr geht
      ToastAndroid.show('Unterbrechen pausiert — zu viele Fehlauslöser', ToastAndroid.LONG);
    }
  }, []);

  /**
   * Prüft eine erkannte Äußerung: war es nur Donnas eigene Stimme (Echo), wird sie VERWORFEN (kein Senden,
   * keine Endlosschleife). Zwei Echo-Selbstunterbrechungen schalten Voice-Barge-in ab → es bleibt das
   * zuverlässige Verhalten (Mikrofon erst nach dem TTS-Ende).
   * @returns true = echte Äußerung von Mike
   */
  const acceptVoiceText = useCallback((text: string): boolean => {
    // Der Echo-Filter gilt nur, wenn das Zuhören automatisch (Auto-Relisten) oder per Voice-Barge-in begann
    const armed = echoFilterArmedRef.current;
    echoFilterArmedRef.current = false;
    if (armed && isEchoOfSpoken(text, lastSpokenRef.current, voiceBargeInSessionRef.current)) {
      endBargeInSession(false);   // Echo ist kein echter Sprecher → Strike
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
   * Reihenfolge: Hintergrund-Sperre → Echo-Filter (nur Auto/Barge-in) → Ja/Nein-Bestätigung → senden.
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

  const handleSend = async () => {
    const text = inputText.trim();
    if (!text) {return;}
    if (isListening) {return;}  // DONNA-237: kein Senden während Spracheingabe
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

  const handleVoicePress = useCallback(async () => {
    resetTurnCounters();
    // Manueller Start: Mike spricht garantiert selbst → kein Echo-Abgleich, Vergleichstext leeren
    voiceBargeInSessionRef.current = false;
    echoFilterArmedRef.current = false;
    if (isListening) {
      await finishListeningAny();
      return;
    }
    // Barge-in: Mic-Tap während Donna spricht oder der Stream läuft → alles sofort beenden, dann zuhören
    // (sonst nähme das Mikrofon die eigene Stimme auf und der alte Request liefe weiter)
    bargeIn();
    lastSpokenRef.current = '';
    const granted = await requestMicPermission();
    if (!granted) {return;}
    await startListeningAny();
  }, [isListening, finishListeningAny, startListeningAny, bargeIn, resetTurnCounters]);

  /** Stopp-Taste: Wiedergabe und Stream beenden, ohne neu zu starten. */
  const handleStop = useCallback(() => { resetTurnCounters(); bargeIn(); }, [bargeIn, resetTurnCounters]);

  /** Tippen während Donna spricht: Wiedergabe sofort stoppen (die Frage wird erst beim Senden ersetzt). */
  const handleInputChange = useCallback((value: string) => {
    if (value.length > 0 && (ttsActive || isLoading)) { interruptSpeech(); }
    if (value.length > 0) {
      resetTurnCounters();
      // Tippen während des Zuhörens beendet das Zuhören (das Eingabefeld bleibt benutzbar)
      if (isListening) { cancelListeningAny().catch(() => {}); }
    }
    setInputText(value);
  }, [ttsActive, isLoading, isListening, interruptSpeech, resetTurnCounters, cancelListeningAny]);

  // Sichtbare Stopp-Taste, solange Donna spricht oder antwortet
  const canInterrupt = ttsActive || isLoading;

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
  // nativen Event (Warm-Start: RN-View ist schon gemountet → ohne Live-Listener bliebe der alte Chat stehen).
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
    // Hintergrund läuft, feuert nur MainActivity.onNewIntent → dieses Event (kein onCreate, kein neuer Mount).
    // Ohne diesen Listener bliebe der alte Chat ohne Inhalt stehen. getAndClear() ist atomar → kein Doppel mit App.tsx.
    const sub = DeviceEventEmitter.addListener('donna_open_proactive_chat', () => consumeProactivePrefs());
    return () => sub.remove();
  }, [startProactiveChat, consumeProactivePrefs]);

  // Hintergrund-Handling: Aufnahme + TTS stoppen wenn App in Hintergrund geht
  useEffect(() => {
    const sub = AppState.addEventListener('change', nextState => {
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
    AsyncStorage.getItem(VOICE_BARGE_IN_KEY).then(val => {
      // Opt-out: nur ein ausdrücklich gespeichertes „false“ schaltet Voice-Barge-in aus (sonst Standard = an)
      voiceBargeInEnabledRef.current = val === null || val === undefined ? VOICE_BARGE_IN_DEFAULT : val !== 'false';
    }).catch(() => {});
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
        'Du kannst Donna jederzeit unterbrechen — einfach reinreden. Ausschalten: lange auf 🔄 tippen.',
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
      bargeInDisabledRef.current = false;
      echoStrikesRef.current.reset();
      try { NativeModules.BargeInModule?.setDisabled?.(false); } catch { /* ignorieren */ }
    } else {
      stopBargeInMonitor();
    }
    AsyncStorage.setItem(VOICE_BARGE_IN_KEY, String(next)).catch(() => {});
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
      // start() liefert false ohne Echo-Unterdrückung → dann bleibt es beim Mikrofon nach dem TTS-Ende.
      // Nie im Hintergrund/gesperrt; Hard-Cap als Sicherheitsnetz gegen ein vergessenes Stoppen.
      if (
        conversationModeRef.current && voiceBargeInEnabledRef.current && !bargeInDisabledRef.current &&
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
      // läuft durch). Erst das Ende des Restes (onEnded → false) löst die normale Ende-Logik aus.
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
      if (!conversationModeRef.current || bargeInDisabledRef.current || !voiceBargeInEnabledRef.current) { return; }
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

  // ─── Stimme: A/B Edge ↔ Piper-lokal (Voice-Batch) ────────────────────────────
  // Die Wahl liegt nativ (SharedPreferences, auch vom Overlay gelesen); hier nur der Spiegel für die Sprech-Kette.
  const ttsEngineRef = useRef<TtsEngine>(TTS_ENGINE_DEFAULT);
  const edgeVoiceRef = useRef<string>(EDGE_VOICE_DEFAULT);
  // EAGER: Piper-Modell (Kerstin) beim App-Start laden, Fortschritt als Banner sichtbar
  const piperModel = usePiperModel(true);
  // Fehler-Banner: schließbar und blendet sich nach ~10 s von selbst aus (neuer Fehler → wieder sichtbar)
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
  // wird jetzt serverseitig in der Einrichtung gewählt (TtsVoiceSettings → /settings/tts). Die Edge-Stimme
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
  // Voice-Batch (A/B): gewählte Engine (Edge-TTS über Backend-/tts ODER Piper-lokal, Kerstin) → die jeweils
  // andere Engine → System-TTS (Samsung Neural). Reihenfolge/Logik: utils/ttsChain.ts.
  // Nach einer Unterbrechung (ttsEpoch) startet keine weitere Stufe mehr.
  const speakViaKokoro = useCallback((text: string) => {
    if (!ttsEnabled || !text?.trim()) return;
    markTtsActive(text);
    lastSpokenRef.current = appendSpoken(lastSpokenRef.current, text);
    markLatency('tts_start');
    const epoch = ttsEpochRef.current;

    // Engine und Stimme bei JEDEM Sprechen frisch lesen: Die Einstellung kann im Setup-Overlay über dem (gemounteten)
    // Chat geändert worden sein — sonst wirkte der A/B-Umschalter erst nach einem Neustart. Die Refs sind nur
    // Spiegel/Fallback (Lesefehler → letzter bekannter Wert).
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
        // R1-Guard: firstText == null ⇒ für DIESE Nachricht lief KEIN Early-TTS (z. B. proaktive Nachricht, die
        // nach einem Auto-Relisten mit lastInputWasVoiceRef=true ohne frischen Turn-Start/interruptSpeech reinkommt).
        // Dann tragen earlyFirstSeenRef/der Sequencer noch STALE Flags des Vor-Turns → ohne Reset würde der Rest
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
  // endCoordinates.height = volle Tastaturhöhe inkl. Gesten-Bar → direkt als Padding nutzen
  const [kbHeight, setKbHeight] = useState(0);
  // Refs für das Auto-Relisten: beim Tippen/offener Tastatur nicht automatisch zuhören
  useEffect(() => { isListeningRef.current = isListening; }, [isListening]);
  useEffect(() => { typingActiveRef.current = kbHeight > 0 || inputText.trim().length > 0; }, [kbHeight, inputText]);
  useEffect(() => {
    cardOpenRef.current =
      pendingAction != null || navChoice != null || contactFallback != null ||
      pendingIdeaConfirm != null || pendingIdeaUpdate != null;
  }, [pendingAction, navChoice, contactFallback, pendingIdeaConfirm, pendingIdeaUpdate]);

  // Barge-in-Sitzung ohne anschließende Äußerung (Stille/Fehler/Timeout) = Fehlauslöser → Strike
  useEffect(() => {
    if (!isListening && voiceBargeInSessionRef.current) { endBargeInSession(false); }
  }, [isListening, endBargeInSession]);
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
          {/* TTS-Toggle (DONNA-36). TB4: Stimm-Wahl wanderte in die Einrichtung
              (TtsVoiceSettings → /settings/tts); der Button schaltet nur noch TTS an/aus. */}
          <TouchableOpacity
            onPress={() => setTtsEnabled(!ttsEnabled)}
            hitSlop={{top: 8, bottom: 8, left: 8, right: 8}}
            style={[styles.newChatBtn, !ttsEnabled && styles.ttsDisabledBtn]}
            accessibilityLabel={ttsEnabled ? 'TTS deaktivieren' : 'TTS aktivieren'}>
            <Text style={styles.newChatIcon}>{ttsEnabled ? '🔊' : '🔇'}</Text>
          </TouchableOpacity>
          {/* Gesprächsmodus Toggle (DONNA-15) */}
          {micAvailable && (
            <TouchableOpacity
              onPress={toggleConversationMode}
              onLongPress={toggleVoiceBargeIn}
              accessibilityHint="Lange drücken schaltet das Unterbrechen per Stimme ein oder aus"
              hitSlop={{top: 8, bottom: 8, left: 8, right: 8}}
              style={[styles.newChatBtn, conversationMode && styles.convModeActive]}
              accessibilityRole="button"
              accessibilityLabel={conversationMode ? 'Gesprächsmodus ausschalten' : 'Gesprächsmodus einschalten'}
              accessibilityState={{selected: conversationMode}}>
              <Text style={styles.newChatIcon}>🔄</Text>
            </TouchableOpacity>
          )}
          {/* Ersteinrichtung erneut öffnen (Berechtigungen prüfen/nachholen) */}
          {onOpenSetup != null && (
            <TouchableOpacity
              onPress={onOpenSetup}
              hitSlop={{top: 8, bottom: 8, left: 8, right: 8}}
              style={[styles.newChatBtn, styles.setupBtn]}
              accessibilityRole="button"
              accessibilityLabel={setupWarning ? 'Einrichtung öffnen, Pflicht-Berechtigung fehlt' : 'Einrichtung öffnen'}>
              <Text style={styles.newChatIcon}>⚙️</Text>
              {setupWarning && <View style={styles.setupWarnDot} testID="setup-warning-dot" />}
            </TouchableOpacity>
          )}
          {messages.length > 0 && (
            <TouchableOpacity
              onPress={clearMessages}
              hitSlop={{top: 8, bottom: 8, left: 8, right: 8}}
              style={styles.newChatBtn}
              accessibilityRole="button"
              accessibilityLabel="Neuer Chat">
              <Text style={styles.newChatIcon}>✏️</Text>
            </TouchableOpacity>
          )}
          <View style={styles.statusDot} />
        </View>
      </View>

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
                <Text style={styles.phiBannerBtnText}>✕</Text>
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
        <IdleScreen onVoicePress={handleVoicePress} isAvailable={micAvailable} />
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

      {/* Barge-in: sichtbare Stopp-Taste, solange Donna spricht oder antwortet.
          Mic-Tap beendet ebenfalls alles und hört sofort zu; Tippen/Senden ersetzt die Anfrage. */}
      {canInterrupt && !isListening ? (
        <TouchableOpacity
          style={styles.stopBar}
          onPress={handleStop}
          accessibilityRole="button"
          accessibilityLabel="Donna unterbrechen">
          <Text style={styles.stopBarIcon}>⏹</Text>
          <Text style={styles.stopBarText}>Stopp</Text>
        </TouchableOpacity>
      ) : null}

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

      {/* Freisprechen: sichtbarer, leicht erreichbarer Hinweis (antippen = ausschalten) */}
      {micAvailable && conversationMode && !isListening && !canInterrupt && kbHeight === 0 ? (
        <TouchableOpacity
          style={styles.convHint}
          onPress={toggleConversationMode}
          onLongPress={toggleVoiceBargeIn}
          accessibilityRole="button"
          accessibilityLabel="Gesprächsmodus ist an, antippen zum Beenden">
          <Text style={styles.convHintText}>Gesprächsmodus an — einfach reden · antippen zum Beenden</Text>
        </TouchableOpacity>
      ) : null}

      {/* Input row: kein Nav-Bar-Padding wenn Tastatur offen (Tastatur deckt es ab) */}
      <View style={[styles.inputRow, {paddingBottom: kbHeight > 0 ? 8 : insets.bottom + 8}]}>
        {micAvailable && (
          <MicButton active={isListening} interrupting={canInterrupt} onPress={handleVoicePress} />
        )}
        <TextInput
          style={styles.input}
          value={inputText}
          onChangeText={handleInputChange}
          placeholder={isListening ? 'Zuhören…' : 'Nachricht eingeben…'}
          placeholderTextColor={P.muted}
          returnKeyType="send"
          onSubmitEditing={handleSend}
          onFocus={() => { if (isListening) { cancelListeningAny().catch(() => {}); } }}
          selectionColor={P.accent}
        />
        <TouchableOpacity
          style={[
            styles.sendButton,
            (!inputText.trim() || isListening) && styles.sendButtonDisabled,
          ]}
          onPress={handleSend}
          disabled={!inputText.trim() || isListening}
          accessibilityLabel="Nachricht senden">
          <Text
            style={[
              styles.sendIcon,
              (!inputText.trim() || isListening) && styles.sendIconDisabled,
            ]}>
            ➤
          </Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

const makeStyles = (P: Theme) => StyleSheet.create({
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
  headerRight: {flexDirection: 'row', alignItems: 'center', gap: 0},
  // Touchziel mindestens 48dp
  newChatBtn: {minWidth: 48, minHeight: 48, padding: 4, alignItems: 'center', justifyContent: 'center'},
  // Barrierefreiheit: Touchziel mindestens 48dp (inkl. hitSlop)
  setupBtn: {minWidth: 48, minHeight: 48, alignItems: 'center', justifyContent: 'center'},
  setupWarnDot: {
    position: 'absolute',
    top: 6,
    right: 6,
    width: 10,
    height: 10,
    borderRadius: 5,
    backgroundColor: P.warning,
  },
  newChatIcon: {fontSize: 18},
  convModeActive: {
    backgroundColor: P.accentSoftStrong,
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
    marginLeft: 8,
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: P.ok,
  },

  // DONNA-189: Phi-3 Mini Download-Banner
  phiBanner: {
    backgroundColor: P.accentSoft,
    borderBottomWidth: 1,
    borderBottomColor: P.accentBorder,
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
    color: P.onAccent,
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
    color: P.muted,
    fontStyle: 'italic',
    marginTop: 2,
    marginLeft: 4,
  },

  // Freisprechen: dezenter Hinweis (Touchziel >= 48dp)
  convHint: {
    alignSelf: 'center',
    minHeight: 48,
    justifyContent: 'center',
    paddingHorizontal: 16,
  },
  convHintText: {color: P.muted, fontSize: 13},

  // Barge-in: Stopp-Taste (Touchziel >= 48dp)
  stopBar: {
    alignSelf: 'center',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    minHeight: 48,
    minWidth: 120,
    paddingHorizontal: 20,
    marginVertical: 4,
    borderRadius: 24,
    borderWidth: 1,
    borderColor: P.accent,
    backgroundColor: P.accentSoftStrong,
  },
  stopBarIcon: {color: P.accent, fontSize: 16},
  stopBarText: {color: P.accent, fontSize: 15, fontWeight: '700'},

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
    borderColor: P.accentBorder,
    borderRadius: 22,
    paddingHorizontal: 16,
    paddingVertical: 10,
    fontSize: 15,
    maxHeight: 120,
    color: P.text,
    backgroundColor: P.card,
  },
  sendButton: {
    width: 48,
    height: 48,
    borderRadius: 24,
    backgroundColor: P.accent,
    justifyContent: 'center',
    alignItems: 'center',
  },
  sendButtonDisabled: {
    backgroundColor: P.accentSoftStrong,
  },
  sendIcon: {
    color: P.onAccent,
    fontSize: 16,
    fontWeight: '700',
  },
  // deaktiviert: sichtbares Icon (onAccent auf der getoenten Flaeche hatte nur ~1,3:1)
  sendIconDisabled: {color: P.disabledText},
});

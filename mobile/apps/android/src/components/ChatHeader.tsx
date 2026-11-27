import React from 'react';
import {StyleSheet, Text, TouchableOpacity, View} from 'react-native';
import {FONT, FONT_FAMILY, SPACE, TOUCH_MIN, useTheme, useThemedStyles} from '../theme';
import type {Theme} from '../theme';
import type {OrbDisplayState} from '../theme/orbState';
import {statusText} from './DonnaStateOrb';
import {ChatOrb} from './chat/ChatOrb';
import {Icon} from './Icon';
import type {IconName} from './Icon';
import {ConversationToggle} from './chat/ConversationToggle';

export interface ChatHeaderProps {
  /** Oberer Systemrand (Statusleiste) in dp. */
  topInset: number;
  /** `idle` = nur "Donna", `chat` = kleiner Orb + "Donna" + Zustand in Worten. */
  variant?: 'idle' | 'chat';
  orbState?: OrbDisplayState;
  /** false: Orb gemountet, aber verdeckt (Gespraechsansicht) -> keine Animation. */
  orbActive?: boolean;
  ttsEnabled: boolean;
  onToggleTts: () => void;
  /** Gespraechsmodus-Umschalter im Kopf (nur Chat-Ansicht; im Leerzustand sitzt er in der Bedienleiste). */
  showConversationToggle: boolean;
  conversationMode: boolean;
  onToggleConversation: () => void;
  /** Langer Druck auf den Gespraechs-Umschalter: Unterbrechen per Stimme ein/aus. */
  onToggleBargeIn: () => void;
  onOpenHistory: () => void;
  /** Einstellungen oeffnen (Zahnrad); ohne Callback keine Taste. */
  onOpenSetup?: () => void;
  setupWarning?: boolean;
  /** "Neuer Chat" nur, wenn es Nachrichten gibt. */
  showNewChat: boolean;
  onNewChat: () => void;
}

const HIT_SLOP = {top: SPACE.sm, bottom: SPACE.sm, left: SPACE.sm, right: SPACE.sm};
const ORB_SMALL = 36;

function IconButton(p: {
  icon: IconName;
  label: string;
  onPress: () => void;
  dim?: boolean;
  badge?: boolean;
}): React.JSX.Element {
  const styles = useThemedStyles(makeStyles);
  const P = useTheme();
  return (
    <TouchableOpacity
      onPress={p.onPress}
      hitSlop={HIT_SLOP}
      style={[styles.iconBtn, p.dim && styles.dim]}
      accessibilityRole="button"
      accessibilityLabel={p.label}>
      <Icon name={p.icon} size={22} color={P.muted as string} />
      {p.badge ? <View style={styles.warnDot} testID="setup-warning-dot" /> : null}
    </TouchableOpacity>
  );
}

/**
 * Kopf (Entwurf B): kleine Kopfzeile ohne Linie. Links Name (Chat: Orb + Zustand in Worten, Antippen oeffnet den
 * Verlauf), rechts Icon-Tasten. Nur Darstellung: Props rein, Callbacks raus.
 */
export function ChatHeader(p: ChatHeaderProps): React.JSX.Element {
  const styles = useThemedStyles(makeStyles);
  const variant = p.variant ?? 'idle';
  const orbState = p.orbState ?? 'idle';
  return (
    <View style={[styles.header, {paddingTop: p.topInset + SPACE.sm}]}>
      <View style={styles.left}>
        <TouchableOpacity
          style={styles.leftTouch}
          onPress={p.onOpenHistory}
          hitSlop={HIT_SLOP}
          accessibilityRole="button"
          accessibilityLabel="Verlauf öffnen">
          {variant === 'chat' ? <ChatOrb state={orbState} size={ORB_SMALL} silent active={p.orbActive ?? true} /> : null}
          <Text style={variant === 'chat' ? styles.titleSmall : styles.title}>Donna</Text>
        </TouchableOpacity>
        {/* Zustand in Worten: eigenes zugaengliches Element, Wechsel wird angesagt (frueher Live-Region der Statuspille) */}
        {variant === 'chat' ? (
          <Text style={styles.state} accessibilityLiveRegion="polite" testID="header-state">{statusText(orbState)}</Text>
        ) : null}
      </View>
      <View style={styles.right}>
        {p.showConversationToggle ? (
          <ConversationToggle
            variant="icon"
            active={p.conversationMode}
            onPress={p.onToggleConversation}
            onLongPress={p.onToggleBargeIn}
          />
        ) : null}
        {/* TTS-Toggle (DONNA-36). Stimm-Wahl liegt in der Einrichtung; der Button schaltet nur TTS an/aus. */}
        <IconButton
          icon={p.ttsEnabled ? 'volume' : 'volumeOff'}
          label={p.ttsEnabled ? 'TTS deaktivieren' : 'TTS aktivieren'}
          onPress={p.onToggleTts}
          dim={!p.ttsEnabled}
        />
        {p.showNewChat ? <IconButton icon="newChat" label="Neuer Chat" onPress={p.onNewChat} /> : null}
        {/* Ersteinrichtung erneut oeffnen (Berechtigungen pruefen/nachholen) */}
        {p.onOpenSetup != null ? (
          <IconButton
            icon="settings"
            label={p.setupWarning ? 'Einstellungen öffnen, Einrichtung unvollständig' : 'Einstellungen öffnen'}
            onPress={p.onOpenSetup}
            badge={p.setupWarning}
          />
        ) : null}
      </View>
    </View>
  );
}

const makeStyles = (P: Theme) => StyleSheet.create({
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: SPACE.lg,
    paddingBottom: SPACE.sm,
    backgroundColor: P.bg,
  },
  left: {flexDirection: 'row', alignItems: 'center', gap: SPACE.sm, minHeight: TOUCH_MIN, flexShrink: 1},
  leftTouch: {flexDirection: 'row', alignItems: 'center', gap: SPACE.md, minHeight: TOUCH_MIN},
  right: {flexDirection: 'row', alignItems: 'center', gap: SPACE.xs},
  title: {fontFamily: FONT_FAMILY.display.bold, fontSize: FONT.title, color: P.heading},
  titleSmall: {fontFamily: FONT_FAMILY.display.bold, fontSize: FONT.body, color: P.heading},
  state: {fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.label, color: P.muted},
  iconBtn: {
    minWidth: TOUCH_MIN,
    minHeight: TOUCH_MIN,
    borderRadius: TOUCH_MIN / 2,
    alignItems: 'center',
    justifyContent: 'center',
  },
  dim: {opacity: 0.6},
  warnDot: {
    position: 'absolute',
    top: SPACE.sm,
    right: SPACE.sm,
    width: SPACE.md,
    height: SPACE.md,
    borderRadius: SPACE.md / 2,
    backgroundColor: P.warning,
  },
});

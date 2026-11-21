import React from 'react';
import {StyleSheet, Text, TouchableOpacity, View} from 'react-native';
import {FONT, FONT_FAMILY, FONT_LARGE, RADIUS, SPACE, useTheme, useThemedStyles} from '../../theme';
import type {Theme} from '../../theme';
import type {OrbDisplayState} from '../../theme/orbState';
import {statusText} from '../DonnaStateOrb';
import {Icon} from '../Icon';
import {ChatOrb} from './ChatOrb';
import {micLabel} from './chatView';

export interface ConversationViewProps {
  /** Oberer Systemrand in dp. */
  topInset: number;
  /** Unterer Systemrand in dp. */
  bottomInset: number;
  orbState: OrbDisplayState;
  /** "Du" oder "Donna" (conversationTranscript) */
  speaker: string;
  /** Mitschrift, gross */
  transcript: string;
  /** Orb antippen: beim Zuhoeren "Fertig", sonst Donna unterbrechen und sprechen (wie die Mikrotaste). */
  onOrbPress: () => void;
  /** Mikrofon ist offen (Label der Orb-Taste). */
  listening: boolean;
  /** Donna denkt/spricht (Label der Orb-Taste). */
  interrupting: boolean;
  /** "Tippen": zurueck in die Chat-Ansicht mit Tastatur. */
  onTyping: () => void;
  /** "Beenden": Gespraechsmodus aus. */
  onEnd: () => void;
}

const ORB_SIZE = 240;
const PILL_HEIGHT = 56;

/**
 * Gespraechsansicht (Entwurf B, P5): reine Darstellung ueber dem vorhandenen Gespraechszustand. Chip "Gespraech
 * laeuft", Mitschrift gross, grosser Orb, Zustandswort; unten "Tippen" (Umriss) und "Beenden" (Mint).
 */
export function ConversationView(p: ConversationViewProps): React.JSX.Element {
  const styles = useThemedStyles(makeStyles);
  const P = useTheme();
  return (
    <View style={[styles.root, {paddingTop: p.topInset + SPACE.sm, paddingBottom: SPACE.xl + p.bottomInset}]} testID="conversation-view">
      <View style={styles.top}>
        <View style={styles.chip}>
          <Text style={styles.chipText}>Gespräch läuft</Text>
        </View>
      </View>
      <View style={styles.main}>
        <View style={styles.transcriptBlock}>
          <Text style={styles.speaker}>{p.speaker}</Text>
          <Text style={styles.transcript} numberOfLines={6} testID="conversation-transcript">{p.transcript}</Text>
        </View>
        <View style={styles.center}>
          <TouchableOpacity
            onPress={p.onOrbPress}
            accessibilityRole="button"
            accessibilityLabel={micLabel(p.listening, p.interrupting)}>
            <ChatOrb state={p.orbState} size={ORB_SIZE} silent />
          </TouchableOpacity>
          <Text style={styles.state} accessibilityLiveRegion="polite" testID="conversation-state">
            {statusText(p.orbState)}
          </Text>
        </View>
      </View>
      <View style={styles.footer}>
        <TouchableOpacity
          style={[styles.pill, styles.pillOutline]}
          onPress={p.onTyping}
          accessibilityRole="button"
          accessibilityLabel="Tippen">
          <Icon name="keyboard" size={20} color={P.text as string} />
          <Text style={styles.pillText}>Tippen</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.pill, styles.pillMint]}
          onPress={p.onEnd}
          accessibilityRole="button"
          accessibilityLabel="Beenden">
          <Icon name="close" size={20} color={P.onAction as string} />
          <Text style={styles.pillMintText}>Beenden</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

const makeStyles = (P: Theme) => StyleSheet.create({
  root: {...StyleSheet.absoluteFillObject, backgroundColor: P.bg, paddingHorizontal: SPACE.lg, zIndex: 10},
  top: {minHeight: 48, flexDirection: 'row', alignItems: 'center'},
  chip: {
    minHeight: 32,
    paddingHorizontal: SPACE.md,
    borderRadius: RADIUS.pill,
    backgroundColor: P.accentContainer,
    justifyContent: 'center',
  },
  chipText: {fontFamily: FONT_FAMILY.sans.bold, fontSize: FONT.label, color: P.onAccentContainer},
  main: {flex: 1, gap: SPACE.xxl, paddingTop: SPACE.xl},
  transcriptBlock: {gap: SPACE.md},
  speaker: {fontFamily: FONT_FAMILY.sans.bold, fontSize: FONT.label, color: P.accent},
  transcript: {fontFamily: FONT_FAMILY.display.bold, fontSize: FONT_LARGE.speech, lineHeight: 38, color: P.heading},
  center: {flex: 1, alignItems: 'center', justifyContent: 'center', gap: SPACE.lg},
  state: {fontFamily: FONT_FAMILY.sans.bold, fontSize: FONT_LARGE.lead, color: P.text},
  footer: {flexDirection: 'row', gap: SPACE.md},
  pill: {
    flex: 1,
    height: PILL_HEIGHT,
    borderRadius: PILL_HEIGHT / 2,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: SPACE.sm,
  },
  pillOutline: {borderWidth: 1, borderColor: P.controlBorder},
  pillMint: {backgroundColor: P.action},
  pillText: {fontFamily: FONT_FAMILY.sans.bold, fontSize: FONT.body, color: P.text},
  pillMintText: {fontFamily: FONT_FAMILY.sans.bold, fontSize: FONT.body, color: P.onAction},
});

import React from 'react';
import {StyleSheet, Text, TouchableOpacity, View} from 'react-native';
import {FONT, FONT_FAMILY, RADIUS, SPACE, TOUCH_MIN, useTheme, useThemedStyles} from '../../theme';
import type {Theme} from '../../theme';
import {Icon} from '../Icon';
import {ConversationToggle} from './ConversationToggle';
import {MicActionButton} from './MicActionButton';

export interface IdleActionBarProps {
  micAvailable: boolean;
  isListening: boolean;
  canInterrupt: boolean;
  onTyping: () => void;
  onVoicePress: () => void;
  conversationMode: boolean;
  onToggleConversation: () => void;
  onToggleBargeIn: () => void;
  /** Unterer Systemrand in dp. */
  bottomInset: number;
}

const MIC_BIG = 64;

/** Schwebende Bedienleiste des Leerzustands: Tippen, grosses Mint-Mikrofon, Gespraech-Umschalter. */
export function IdleActionBar(p: IdleActionBarProps): React.JSX.Element {
  const styles = useThemedStyles(makeStyles);
  const P = useTheme();
  return (
    <View style={[styles.wrap, {paddingBottom: SPACE.lg + p.bottomInset}]}>
      <View style={styles.bar}>
        <TouchableOpacity
          style={styles.typeBtn}
          onPress={p.onTyping}
          accessibilityRole="button"
          accessibilityLabel="Tippen">
          <Icon name="keyboard" size={20} color={P.text as string} />
          <Text style={styles.typeLabel}>Tippen</Text>
        </TouchableOpacity>
        {p.micAvailable ? (
          <MicActionButton active={p.isListening} interrupting={p.canInterrupt} onPress={p.onVoicePress} size={MIC_BIG} />
        ) : null}
        {p.micAvailable ? (
          <ConversationToggle
            variant="pill"
            active={p.conversationMode}
            onPress={p.onToggleConversation}
            onLongPress={p.onToggleBargeIn}
          />
        ) : null}
      </View>
    </View>
  );
}

const makeStyles = (P: Theme) => StyleSheet.create({
  wrap: {paddingHorizontal: SPACE.lg},
  bar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: SPACE.sm,
    paddingHorizontal: SPACE.md,
    borderRadius: RADIUS.card,
    backgroundColor: P.card,
    borderWidth: 1,
    borderColor: P.border,
  },
  typeBtn: {
    minWidth: 96,
    height: TOUCH_MIN,
    paddingHorizontal: SPACE.lg,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: SPACE.sm,
  },
  typeLabel: {fontFamily: FONT_FAMILY.sans.semibold, fontSize: FONT.body, color: P.text},
});

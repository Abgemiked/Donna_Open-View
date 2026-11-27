import React from 'react';
import {StyleSheet, Text, TouchableOpacity} from 'react-native';
import {FONT, FONT_FAMILY, SPACE, TOUCH_MIN, useTheme, useThemedStyles} from '../../theme';
import type {Theme} from '../../theme';
import {Icon} from '../Icon';
import {conversationLabel} from './chatView';

export interface ConversationToggleProps {
  active: boolean;
  onPress: () => void;
  /** Langer Druck: Unterbrechen per Stimme ein/aus. */
  onLongPress: () => void;
  /** `pill` = Symbol + Wort "Gespraech" (Leerzustand), `icon` = nur Symbol (Chat-Kopf). */
  variant: 'pill' | 'icon';
}

const HIT = {top: SPACE.sm, bottom: SPACE.sm, left: SPACE.sm, right: SPACE.sm};

/** Umschalter fuer den Gespraechsmodus; Zustand ueber `accessibilityState.selected` und die getoente Flaeche. */
export function ConversationToggle({active, onPress, onLongPress, variant}: ConversationToggleProps): React.JSX.Element {
  const styles = useThemedStyles(makeStyles);
  const P = useTheme();
  const color = (active ? P.onAccentContainer : P.text) as string;
  return (
    <TouchableOpacity
      onPress={onPress}
      onLongPress={onLongPress}
      hitSlop={HIT}
      accessibilityRole="button"
      accessibilityLabel={conversationLabel(active)}
      accessibilityHint="Lange drücken schaltet das Unterbrechen per Stimme ein oder aus"
      accessibilityState={{selected: active}}
      style={[variant === 'pill' ? styles.pill : styles.icon, active && styles.active]}>
      <Icon name="wave" size={20} color={color} />
      {variant === 'pill' ? <Text style={[styles.label, {color}]}>Gespräch</Text> : null}
    </TouchableOpacity>
  );
}

const makeStyles = (P: Theme) => StyleSheet.create({
  pill: {
    minWidth: 96,
    height: TOUCH_MIN,
    paddingHorizontal: SPACE.lg,
    borderRadius: TOUCH_MIN / 2,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: SPACE.sm,
  },
  icon: {
    minWidth: TOUCH_MIN,
    minHeight: TOUCH_MIN,
    borderRadius: TOUCH_MIN / 2,
    alignItems: 'center',
    justifyContent: 'center',
  },
  active: {backgroundColor: P.accentContainer},
  label: {fontFamily: FONT_FAMILY.sans.semibold, fontSize: FONT.body},
});

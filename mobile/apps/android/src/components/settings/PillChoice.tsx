/**
 * #134 P6: Auswahl als Pillen-Reihe, genau eine ist gewaehlt (`radio`). Tippziel >= TOUCH_MIN.
 * `stacked` stellt die Pillen untereinander (lange Beschriftungen, z. B. Modellnamen).
 */
import React, {useMemo} from 'react';
import {StyleSheet, Text, TouchableOpacity, View} from 'react-native';
import {FONT, FONT_FAMILY, RADIUS, SPACE, TOUCH_MIN, useTheme} from '../../theme';
import type {Theme} from '../../theme';

export interface PillOption<V extends string | number | boolean> {
  value: V;
  label: string;
  testID?: string;
  accessibilityLabel?: string;
}

export interface PillChoiceProps<V extends string | number | boolean> {
  options: readonly PillOption<V>[];
  value: V;
  onChange: (value: V) => void;
  disabled?: boolean;
  stacked?: boolean;
}

export function PillChoice<V extends string | number | boolean>({
  options,
  value,
  onChange,
  disabled = false,
  stacked = false,
}: PillChoiceProps<V>): React.JSX.Element {
  const c = useTheme();
  const styles = useMemo(() => makeStyles(c), [c]);
  return (
    <View style={stacked ? styles.column : styles.row} accessibilityRole="radiogroup">
      {options.map(o => {
        const selected = o.value === value;
        return (
          <TouchableOpacity
            key={String(o.value)}
            testID={o.testID}
            style={[
              styles.pill,
              !stacked && styles.pillFlex,
              selected && styles.pillSelected,
              disabled && styles.pillDisabled,
            ]}
            onPress={() => onChange(o.value)}
            disabled={disabled}
            accessibilityRole="radio"
            accessibilityState={{selected, disabled}}
            accessibilityLabel={o.accessibilityLabel ?? o.label}>
            <Text style={[styles.text, selected && styles.textSelected]}>{o.label}</Text>
          </TouchableOpacity>
        );
      })}
    </View>
  );
}

function makeStyles(c: Theme) {
  return StyleSheet.create({
    row: {flexDirection: 'row', gap: SPACE.sm},
    column: {flexDirection: 'column', gap: SPACE.sm},
    pill: {
      minHeight: TOUCH_MIN,
      borderRadius: RADIUS.pill,
      borderWidth: 1,
      borderColor: c.controlBorder,
      alignItems: 'center',
      justifyContent: 'center',
      paddingHorizontal: SPACE.lg,
      paddingVertical: SPACE.sm, // umbrochener Text bei grosser Schrift klebt nicht am Rand
    },
    pillFlex: {flex: 1},
    pillSelected: {borderColor: c.accent, backgroundColor: c.accent},
    pillDisabled: {opacity: 0.5},
    text: {color: c.text, fontFamily: FONT_FAMILY.sans.semibold, fontSize: FONT.label, textAlign: 'center'},
    textSelected: {color: c.onAccent, fontFamily: FONT_FAMILY.sans.bold},
  });
}

export default PillChoice;

/**
 * #134 P6: Zeile mit Titel, Hinweistext und Schalter. Die ganze Zeile ist die Taste (`switch`, >= TOUCH_MIN hoch).
 */
import React, {useMemo} from 'react';
import {StyleSheet, Text, TouchableOpacity, View} from 'react-native';
import {FONT, FONT_FAMILY, RADIUS, SPACE, TOUCH_MIN, useTheme} from '../../theme';
import type {Theme} from '../../theme';

export interface SettingSwitchProps {
  title: string;
  hint?: string;
  value: boolean;
  onChange: (value: boolean) => void;
  testID?: string;
  accessibilityLabel?: string;
}

export function SettingSwitch({
  title,
  hint,
  value,
  onChange,
  testID,
  accessibilityLabel,
}: SettingSwitchProps): React.JSX.Element {
  const c = useTheme();
  const styles = useMemo(() => makeStyles(c), [c]);
  return (
    <TouchableOpacity
      testID={testID}
      style={styles.row}
      onPress={() => onChange(!value)}
      accessibilityRole="switch"
      accessibilityState={{checked: value}}
      accessibilityLabel={accessibilityLabel ?? title}
      accessibilityHint={hint}>
      <View style={styles.texts}>
        <Text style={styles.title}>{title}</Text>
        {hint != null && <Text style={styles.hint}>{hint}</Text>}
      </View>
      <View style={[styles.track, value && styles.trackOn]} importantForAccessibility="no-hide-descendants">
        <View style={[styles.thumb, value && styles.thumbOn]} />
      </View>
    </TouchableOpacity>
  );
}

function makeStyles(c: Theme) {
  return StyleSheet.create({
    row: {flexDirection: 'row', alignItems: 'center', gap: SPACE.lg, minHeight: TOUCH_MIN},
    texts: {flex: 1, gap: SPACE.xs},
    title: {color: c.text, fontFamily: FONT_FAMILY.sans.bold, fontSize: FONT.body},
    hint: {color: c.muted, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.label},
    track: {
      width: 56,
      height: 32,
      padding: SPACE.xs,
      borderRadius: RADIUS.pill,
      borderWidth: 1,
      borderColor: c.controlBorder,
      justifyContent: 'center',
      alignItems: 'flex-start',
    },
    trackOn: {backgroundColor: c.accent, borderColor: c.accent, alignItems: 'flex-end'},
    thumb: {width: 20, height: 20, borderRadius: RADIUS.pill, backgroundColor: c.controlBorder},
    thumbOn: {backgroundColor: c.onAccent},
  });
}

export default SettingSwitch;

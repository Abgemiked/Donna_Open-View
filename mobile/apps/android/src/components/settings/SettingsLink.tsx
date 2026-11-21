/**
 * #134 P6: Eintrag mit Titel, Hinweis und Pfeil, der zu einem anderen Bildschirm fuehrt
 * ("Einrichtung erneut oeffnen", "Einstellungen oeffnen").
 */
import React, {useMemo} from 'react';
import {StyleSheet, Text, TouchableOpacity, View} from 'react-native';
import {FONT, FONT_FAMILY, SPACE, TOUCH_MIN, useTheme} from '../../theme';
import type {Theme} from '../../theme';
import {Icon} from '../Icon';

export interface SettingsLinkProps {
  title: string;
  hint?: string;
  onPress: () => void;
  testID?: string;
}

export function SettingsLink({title, hint, onPress, testID}: SettingsLinkProps): React.JSX.Element {
  const c = useTheme();
  const styles = useMemo(() => makeStyles(c), [c]);
  return (
    <TouchableOpacity
      testID={testID}
      style={styles.row}
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={title}
      accessibilityHint={hint}>
      <View style={styles.texts}>
        <Text style={styles.title}>{title}</Text>
        {hint != null && <Text style={styles.hint}>{hint}</Text>}
      </View>
      <Icon name="chevronRight" size={24} color={c.muted as string} />
    </TouchableOpacity>
  );
}

function makeStyles(c: Theme) {
  return StyleSheet.create({
    row: {flexDirection: 'row', alignItems: 'center', gap: SPACE.lg, minHeight: TOUCH_MIN},
    texts: {flex: 1, gap: SPACE.xs},
    title: {color: c.text, fontFamily: FONT_FAMILY.sans.bold, fontSize: FONT.body},
    hint: {color: c.muted, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.label},
  });
}

export default SettingsLink;

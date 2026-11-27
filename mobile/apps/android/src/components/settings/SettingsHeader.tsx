/**
 * #134 P6: Kopf der Einstellungen/Einrichtung: Zurueck-Icon-Taste (>= TOUCH_MIN) und Titel in Bricolage Grotesque.
 */
import React, {useMemo} from 'react';
import {StyleSheet, Text, TouchableOpacity, View} from 'react-native';
import {FONT, FONT_FAMILY, RADIUS, SPACE, TOUCH_MIN, useTheme} from '../../theme';
import type {Theme} from '../../theme';
import {Icon} from '../Icon';

export interface SettingsHeaderProps {
  title: string;
  onBack: () => void;
  /** Beschriftung der Zurueck-Taste fuer Screenreader. */
  backLabel?: string;
  /** Oberer Abstand (Statusleiste). */
  topInset?: number;
  /** Icon der Taste (Standard "back"; die Einrichtung nutzt "close"). */
  icon?: 'back' | 'close';
}

export function SettingsHeader({
  title,
  onBack,
  backLabel = 'Zurück',
  topInset = 0,
  icon = 'back',
}: SettingsHeaderProps): React.JSX.Element {
  const c = useTheme();
  const styles = useMemo(() => makeStyles(c), [c]);
  return (
    <View style={[styles.header, {paddingTop: topInset + SPACE.md}]}>
      <TouchableOpacity
        testID="settings-back"
        style={styles.back}
        onPress={onBack}
        accessibilityRole="button"
        accessibilityLabel={backLabel}>
        <Icon name={icon} size={24} color={c.heading as string} />
      </TouchableOpacity>
      <Text style={styles.title} accessibilityRole="header">
        {title}
      </Text>
    </View>
  );
}

function makeStyles(c: Theme) {
  return StyleSheet.create({
    header: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: SPACE.xs,
      paddingHorizontal: SPACE.lg,
      paddingBottom: SPACE.sm,
    },
    back: {
      width: TOUCH_MIN,
      height: TOUCH_MIN,
      borderRadius: RADIUS.pill,
      alignItems: 'center',
      justifyContent: 'center',
    },
    title: {flex: 1, color: c.heading, fontFamily: FONT_FAMILY.display.bold, fontSize: FONT.display},
  });
}

export default SettingsHeader;

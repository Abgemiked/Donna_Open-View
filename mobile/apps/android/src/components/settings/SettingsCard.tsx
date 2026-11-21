/**
 * #134 P6: Gruppierte Karte der Einstellungen. Optionaler Abschnittsname als ruhige kleine Zeile ueber der Karte;
 * die Kinder werden durch Haarlinien getrennt (Entwurf B-Einstellungen). Rundung `RADIUS.card`.
 */
import React, {useMemo} from 'react';
import {StyleSheet, Text, View} from 'react-native';
import {FONT, FONT_FAMILY, RADIUS, SPACE, useTheme} from '../../theme';
import type {Theme} from '../../theme';

export interface SettingsCardProps {
  /** Abschnittsname ueber der Karte (z. B. "Stimme"). */
  title?: string;
  children: React.ReactNode;
  testID?: string;
}

export function SettingsCard({title, children, testID}: SettingsCardProps): React.JSX.Element {
  const c = useTheme();
  const styles = useMemo(() => makeStyles(c), [c]);
  const items = React.Children.toArray(children).filter(Boolean);
  return (
    <View style={styles.section} testID={testID}>
      {title != null && (
        <Text style={styles.sectionTitle} accessibilityRole="header">
          {title}
        </Text>
      )}
      <View style={styles.card}>
        {items.map((child, i) => (
          <View key={i} style={[styles.item, i > 0 && styles.itemDivider]}>
            {child}
          </View>
        ))}
      </View>
    </View>
  );
}

function makeStyles(c: Theme) {
  return StyleSheet.create({
    section: {gap: SPACE.sm},
    sectionTitle: {
      marginLeft: SPACE.xs,
      color: c.muted,
      fontFamily: FONT_FAMILY.sans.bold,
      fontSize: FONT.label,
    },
    card: {
      borderRadius: RADIUS.card,
      backgroundColor: c.card,
      borderWidth: 1,
      borderColor: c.border,
      overflow: 'hidden',
    },
    item: {padding: SPACE.gutter, gap: SPACE.md},
    itemDivider: {borderTopWidth: 1, borderTopColor: c.border},
  });
}

export default SettingsCard;

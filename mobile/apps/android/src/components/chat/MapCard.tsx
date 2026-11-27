import React from 'react';
import {View, Text, TouchableOpacity, StyleSheet, Linking} from 'react-native';
import type {MapCardData} from '@donna/shared';
import {Icon} from '../Icon';
import {useTheme, useThemedStyles, SPACE, TOUCH_MIN} from '../../theme';
import type {Theme} from '../../theme';
import {makeInfoCardStyles} from '../../screens/cardStyles';

// ─── MapCard ─────────────────────────────────────────────────────────────────
// Optik: gleiche Kartenfläche wie Wetter/Kalender, Ort-Icon (Icon-Satz) in getöntem Badge,
// Ziel als Titel + Chevron. Verhalten unverändert: Tipp öffnet data.maps_url.
export function MapCard({data}: {data: MapCardData}): React.JSX.Element {
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
          <Icon name="map" size={22} color={theme.accent as string} />
        </View>
        <View style={mapStyles.textCol}>
          <Text style={mapStyles.eyebrow}>In Google Maps öffnen</Text>
          <Text style={mapStyles.query} numberOfLines={2}>{data.query}</Text>
        </View>
        <Icon name="chevronRight" size={22} color={theme.accent as string} />
      </View>
    </TouchableOpacity>
  );
}
const makeMapStyles = (P: Theme) => {
  const I = makeInfoCardStyles(P);
  return StyleSheet.create({
    // Kompakter als Wetter/Kalender (eine Zeile), aber gleiche Rundung und Fläche.
    card: {...I.surface, minHeight: TOUCH_MIN, justifyContent: 'center'},
    row: {flexDirection: 'row', alignItems: 'center', gap: SPACE.md},
    badge: I.badge,
    textCol: {flex: 1},
    eyebrow: {...I.eyebrow, color: P.accent},
    query: {...I.title, marginTop: SPACE.xs},
  });
};

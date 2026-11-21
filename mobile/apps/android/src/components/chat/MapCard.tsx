import React from 'react';
import {View, Text, TouchableOpacity, StyleSheet, Linking} from 'react-native';
import type {MapCardData} from '@donna/shared';
import {PinIcon, ChevronRightIcon} from '../../components/CardIcons';
import {useTheme, useThemedStyles} from '../../theme';
import type {Theme} from '../../theme';
import {makeInfoCardStyles} from '../../screens/cardStyles';

// ─── MapCard ─────────────────────────────────────────────────────────────────
// Optik: gleiche Kartenfläche wie Wetter/Kalender (One-UI-Look), Ort-Icon in getöntem Badge,
// Ziel als Titel + Chevron-Button. Verhalten unverändert: Tipp öffnet data.maps_url.
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
          <PinIcon size={22} color={theme.accent} />
        </View>
        <View style={mapStyles.textCol}>
          <Text style={mapStyles.eyebrow}>In Google Maps öffnen</Text>
          <Text style={mapStyles.query} numberOfLines={2}>{data.query}</Text>
        </View>
        <View style={mapStyles.chevron}>
          <ChevronRightIcon size={20} color={theme.accent} />
        </View>
      </View>
    </TouchableOpacity>
  );
}
const makeMapStyles = (P: Theme) => {
  const I = makeInfoCardStyles(P);
  return StyleSheet.create({
    // Kompakter als Wetter/Kalender (eine Zeile), aber gleicher Radius/Schatten/Rahmen.
    card: {...I.surface, paddingVertical: 14, paddingHorizontal: 16, minHeight: 72, justifyContent: 'center'},
    row: {flexDirection: 'row', alignItems: 'center', gap: 14},
    badge: {...I.badge, width: 44, height: 44, borderRadius: 22},
    textCol: {flex: 1},
    eyebrow: {...I.eyebrow, color: P.accent, fontWeight: '600'},
    query: {...I.title, marginTop: 1},
    chevron: {...I.badge, width: 36, height: 36, borderRadius: 18},
  });
};


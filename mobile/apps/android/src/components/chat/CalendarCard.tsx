import React from 'react';
import {View, Text, StyleSheet} from 'react-native';
import type {CalendarCardData} from '@donna/shared';
import {useThemedStyles, FONT, FONT_FAMILY, SPACE, TOUCH_MIN} from '../../theme';
import {makeInfoCardStyles} from '../../screens/cardStyles';
import type {Theme} from '../../theme';

// ─── CalendarCard ─────────────────────────────────────────────────────────────
// DONNA — Kalender-Karte (Stufe 1, read-only). Gleiche Kartenfläche/Rundung/Rahmen
// wie WeatherCard/MapCard (geteiltes System aus cardStyles.makeInfoCardStyles).
// Zeigt die anstehenden Termine als Liste mit Akzentbalken und Zeitangabe.
// Nur Anzeige — keine Aktion, kein Tap-Handler (die Terminerstellung läuft separat über
// die PendingActionCard mit create_event, die hier bewusst NICHT angefasst wird).
export function CalendarCard({data}: {data: CalendarCardData}): React.JSX.Element {
  const calStyles = useThemedStyles(makeCalendarStyles);
  const events = data?.events ?? [];
  if (events.length === 0) {
    // Defensive: leere Karten sendet das Backend nicht — aber falls doch, nichts rendern.
    return <View />;
  }
  return (
    <View style={calStyles.card} testID="calendar-card" accessibilityLabel="Anstehende Termine" accessibilityRole="list">
      {events.map((ev, i) => (
        <View
          key={i}
          style={[calStyles.eventRow, i < events.length - 1 && calStyles.eventRowDivider]}>
          <View style={calStyles.whenCol}>
            <Text style={calStyles.whenLabel}>{ev.time || ev.when_label}</Text>
            {ev.time ? <Text style={calStyles.timeLabel}>{ev.when_label}</Text> : null}
          </View>
          <View style={calStyles.detailCol}>
            <Text style={calStyles.title} numberOfLines={2}>{ev.title}</Text>
          </View>
          {ev.location ? (
            <Text style={calStyles.location} numberOfLines={1}>{ev.location}</Text>
          ) : null}
        </View>
      ))}
    </View>
  );
}
const makeCalendarStyles = (P: Theme) => {
  const I = makeInfoCardStyles(P);
  return StyleSheet.create({
  // gemeinsame Kartenflaeche der Info-Karten (Rundung, Theme-Flaeche); Liste mit Trennlinien, Polster in den Zeilen
  card: {...I.surface, padding: 0},
  eventRow: {flexDirection: 'row', alignItems: 'center', gap: SPACE.md, paddingHorizontal: SPACE.gutter, paddingVertical: SPACE.lg, minHeight: TOUCH_MIN},
  eventRowDivider: {borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: P.border},
  whenCol: {minWidth: 56, justifyContent: 'center'},
  whenLabel: {color: P.accent, fontFamily: FONT_FAMILY.sans.bold, fontSize: FONT.body},
  timeLabel: {color: P.muted, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.caption},
  detailCol: {flex: 1, justifyContent: 'center'},
  title: {color: P.text, fontFamily: FONT_FAMILY.sans.semibold, fontSize: FONT.body},
  location: {color: P.muted, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.label, flexShrink: 1, maxWidth: '36%'},
  });
};

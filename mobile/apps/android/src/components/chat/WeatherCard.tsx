import React from 'react';
import {View, Text, ScrollView, StyleSheet} from 'react-native';
import type {WeatherCardData} from '@donna/shared';
import {weatherKind, weatherTint, gradientStops, glyphPalette, isNightHour, hourFromLabel, currentHourIndex, averagePrecip, hourLabelA11y} from '../../utils/weatherLook';
import {WeatherGlyph, DropIcon, WindIcon} from '../../components/WeatherGlyph';
import {useTheme, useThemedStyles, FONT, FONT_FAMILY, FONT_LARGE, SPACE, TOUCH_MIN} from '../../theme';
import type {Theme} from '../../theme';
import {makeInfoCardStyles, INFO_CARD} from '../../screens/cardStyles';

// ─── WeatherCard ──────────────────────────────────────────────────────────────
// Ruhiger Aufbau (#134 P8): Ort/Zeit klein, Temperatur groß, Stundenleiste als Kacheln, Details als Zeilen.
// Dezenter zustandsabhängiger Verlauf (ohne Bibliothek), Wetter-Glyphen aus WeatherGlyph (farblich über Theme/Palette).
// Datenvertrag (WeatherCardData + hourly) unverändert.
interface HourlyEntry {time: string; temp_c: number; icon: string; precip_pct: number}

/** Weicher Verlauf aus gestapelten Streifen (oben getönt -> unten transparent). */
function WeatherBackdrop({stops}: {stops: string[]}): React.JSX.Element {
  return (
    <View style={StyleSheet.absoluteFill} pointerEvents="none" testID="weather-backdrop">
      {stops.map((c, i) => (
        <View key={i} style={{flex: 1, backgroundColor: c}} />
      ))}
    </View>
  );
}

export function WeatherCard({data}: {data: WeatherCardData & {hourly?: HourlyEntry[]}}): React.JSX.Element {
  // Hell/Dunkel aus dem Theme (gilt auch bei gewaehlter Darstellung, nicht nur beim Systemschema)
  const dark = useTheme().scheme === 'dark';
  const weatherStyles = useThemedStyles(makeWeatherStyles);
  const hourly: HourlyEntry[] = (data as any).hourly ?? [];
  const nowH = new Date().getHours();
  const night = isNightHour(nowH);
  const kind = weatherKind(data.condition_icon, data.condition, night);
  const stops = gradientStops(weatherTint(kind, dark));
  const glyphs = glyphPalette(dark);
  // Aktuelle Stunde -> passenden Hourly-Slot markieren
  const currentIdx = currentHourIndex(hourly, nowH);
  const precip = averagePrecip(hourly);
  const weekday = new Date().toLocaleDateString('de-DE', {weekday: 'long'});
  return (
    <View style={weatherStyles.card} testID="weather-card">
      <WeatherBackdrop stops={stops} />
      {/* Ort und Zeit klein (Satzschreibung: Wochentag · Ort) */}
      <Text style={weatherStyles.location} numberOfLines={1}>
        {weekday} · {data.location}
      </Text>
      {/* Temperatur groß + Zustand, rechts das Wetter-Symbol */}
      <View style={weatherStyles.topRow}>
        <View style={weatherStyles.topText}>
          <Text style={weatherStyles.temp}>{data.temp_c}°</Text>
          <Text style={weatherStyles.condition}>{data.condition}</Text>
        </View>
        <WeatherGlyph kind={kind} size={64} palette={glyphs} testID="weather-glyph" />
      </View>
      {/* Höchst/Tief als ruhige Pillen */}
      <View style={weatherStyles.hiLoRow}>
        <Text style={weatherStyles.hiLoItem}>
          <Text style={weatherStyles.arrowUp}>Hoch </Text>{data.temp_max}°
        </Text>
        <Text style={weatherStyles.hiLoItem}>
          <Text style={weatherStyles.arrowDown}>Tief </Text>{data.temp_min}°
        </Text>
      </View>
      {/* Stundenleiste als Kacheln */}
      {hourly.length > 0 && (
        <ScrollView horizontal showsHorizontalScrollIndicator={false} style={weatherStyles.hourlyScroll}>
          {hourly.map((h, i) => {
            const isCurrent = i === currentIdx;
            const hh = hourFromLabel(h.time);
            return (
              <View
                key={i}
                accessible
                accessibilityLabel={hourLabelA11y(h, isCurrent)}
                style={[
                  weatherStyles.hourlyItem,
                  isCurrent && weatherStyles.hourlyItemCurrent,
                ]}>
                <Text style={[weatherStyles.hourlyTime, isCurrent && weatherStyles.hourlyTimeCurrent]}>
                  {isCurrent ? 'Jetzt' : h.time}
                </Text>
                <WeatherGlyph
                  kind={weatherKind(h.icon, null, hh !== null && isNightHour(hh))}
                  size={28}
                  palette={glyphs}
                />
                <Text style={weatherStyles.hourlyPrecip}>{h.precip_pct} %</Text>
                <Text style={[weatherStyles.hourlyTemp, isCurrent && weatherStyles.hourlyTempCurrent]}>
                  {h.temp_c}°
                </Text>
              </View>
            );
          })}
        </ScrollView>
      )}
      {/* Details als Zeilen mit Trennlinien: Beschriftung links, Wert rechts */}
      <View testID="weather-stats">
        <View style={weatherStyles.row} accessible accessibilityLabel={`Gefühlt, ${data.feels_like_c} Grad`}>
          <Text style={weatherStyles.rowLabel}>Gefühlt</Text>
          <Text style={weatherStyles.rowValue}>{data.feels_like_c}°</Text>
        </View>
        <View style={[weatherStyles.row, weatherStyles.rowDivider]} accessible accessibilityLabel={`Luftfeuchte, ${data.humidity} Prozent`}>
          <View style={weatherStyles.rowLead}>
            <DropIcon size={14} color={glyphs.drop} />
            <Text style={weatherStyles.rowLabel}>Luftfeuchte</Text>
          </View>
          <Text style={weatherStyles.rowValue}>{data.humidity} %</Text>
        </View>
        <View style={[weatherStyles.row, weatherStyles.rowDivider]} accessible accessibilityLabel={`Wind, ${data.wind_kmh} Kilometer pro Stunde`}>
          <View style={weatherStyles.rowLead}>
            <WindIcon size={14} color={glyphs.line} />
            <Text style={weatherStyles.rowLabel}>Wind</Text>
          </View>
          <Text style={weatherStyles.rowValue}>{data.wind_kmh} km/h</Text>
        </View>
        {precip !== null && (
          <View style={[weatherStyles.row, weatherStyles.rowDivider]} accessible accessibilityLabel={`Niederschlag, ${precip} Prozent`}>
            <View style={weatherStyles.rowLead}>
              <WeatherGlyph kind="rain" size={16} palette={glyphs} />
              <Text style={weatherStyles.rowLabel}>Niederschlag</Text>
            </View>
            <Text style={weatherStyles.rowValue}>{precip} %</Text>
          </View>
        )}
      </View>
    </View>
  );
}
const makeWeatherStyles = (P: Theme) => {
  const I = makeInfoCardStyles(P);
  return StyleSheet.create({
    card: {...I.surface},
    location: {...I.eyebrow, marginBottom: SPACE.md},
    topRow: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      marginBottom: SPACE.md,
    },
    topText: {flex: 1},
    temp: {color: P.heading, fontFamily: FONT_FAMILY.display.bold, fontSize: FONT_LARGE.temp, lineHeight: 64},
    condition: {...I.title, marginTop: SPACE.xs},
    hiLoRow: {flexDirection: 'row', gap: SPACE.sm, marginBottom: SPACE.lg},
    hiLoItem: {...I.pill, color: P.text, fontFamily: FONT_FAMILY.sans.semibold, fontSize: FONT.label},
    arrowUp: {color: P.accent, fontFamily: FONT_FAMILY.sans.bold},
    arrowDown: {color: P.accent2, fontFamily: FONT_FAMILY.sans.bold},
    hourlyScroll: {marginBottom: SPACE.lg},
    hourlyItem: {
      alignItems: 'center',
      marginRight: SPACE.sm,
      minWidth: 64,
      paddingVertical: SPACE.md,
      paddingHorizontal: SPACE.sm,
      borderRadius: INFO_CARD.innerRadius,
      gap: SPACE.xs,
    },
    hourlyItemCurrent: {backgroundColor: P.accentSoftStrong},
    hourlyTime: {color: P.muted, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.label},
    hourlyTimeCurrent: {color: P.accent2, fontFamily: FONT_FAMILY.sans.bold},
    hourlyPrecip: {color: P.muted, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.caption},
    hourlyTemp: {color: P.text, fontFamily: FONT_FAMILY.sans.semibold, fontSize: FONT.body},
    hourlyTempCurrent: {fontFamily: FONT_FAMILY.sans.bold},
    // Detailwerte als Zeilen (Beschriftung links, Wert rechts), durch Haarlinien getrennt
    row: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      minHeight: TOUCH_MIN,
      paddingVertical: SPACE.sm,
    },
    rowDivider: {borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: P.border},
    rowLead: {flexDirection: 'row', alignItems: 'center', gap: SPACE.sm},
    rowLabel: {color: P.muted, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.body},
    rowValue: {color: P.text, fontFamily: FONT_FAMILY.sans.semibold, fontSize: FONT.body},
  });
};

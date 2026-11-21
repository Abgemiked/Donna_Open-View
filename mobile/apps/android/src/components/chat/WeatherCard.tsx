import React from 'react';
import {View, Text, ScrollView, StyleSheet} from 'react-native';
import type {WeatherCardData} from '@donna/shared';
import {weatherKind, weatherTint, gradientStops, glyphPalette, isNightHour, hourFromLabel, currentHourIndex, averagePrecip} from '../../utils/weatherLook';
import {WeatherGlyph, DropIcon, WindIcon} from '../../components/WeatherGlyph';
import {useTheme, useThemedStyles} from '../../theme';
import type {Theme} from '../../theme';
import {makeInfoCardStyles, INFO_CARD} from '../../screens/cardStyles';

// ─── WeatherCard ──────────────────────────────────────────────────────────────
// Optik-Update: zustandsabhängiger, dezenter Verlauf (ohne Bibliothek), konsistenter Icon-Satz statt Emoji,
// klarere Hierarchie. Datenvertrag (WeatherCardData + hourly) unverändert.
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
      {/* Kopfzeile: Wochentag · Ort (Satzschreibung) */}
      <Text style={weatherStyles.location} numberOfLines={1}>
        {weekday} · {data.location}
      </Text>
      {/* Temperatur (groß) + Icon */}
      <View style={weatherStyles.topRow}>
        <View style={{flex: 1}}>
          <Text style={weatherStyles.temp}>{data.temp_c}°</Text>
          <Text style={weatherStyles.feels}>Gefühlt {data.feels_like_c}°</Text>
          <Text style={weatherStyles.condition}>{data.condition}</Text>
        </View>
        <WeatherGlyph kind={kind} size={68} palette={glyphs} testID="weather-glyph" />
      </View>
      {/* Höchst/Tief als eigene, klare Zeile */}
      <View style={weatherStyles.hiLoRow}>
        <Text style={weatherStyles.hiLoItem}>
          <Text style={weatherStyles.arrowUp}>Hoch </Text>{data.temp_max}°
        </Text>
        <Text style={weatherStyles.hiLoItem}>
          <Text style={weatherStyles.arrowDown}>Tief </Text>{data.temp_min}°
        </Text>
      </View>
      {/* Stundenvorschau (wie Gemini) */}
      {hourly.length > 0 && (
        <>
          <View style={weatherStyles.divider} />
          <ScrollView horizontal showsHorizontalScrollIndicator={false} style={weatherStyles.hourlyScroll}>
            {hourly.map((h, i) => {
              const isCurrent = i === currentIdx;
              const hh = hourFromLabel(h.time);
              return (
                <View key={i} style={[
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
        </>
      )}
      {/* Details: Luftfeuchte · Wind · Niederschlag (eigene Werte, nicht ins Fließtext-subInfo gequetscht) */}
      <View style={weatherStyles.statRow} testID="weather-stats">
        <View style={weatherStyles.stat}>
          <View style={weatherStyles.statValueRow}>
            <DropIcon size={14} color={glyphs.drop} />
            <Text style={weatherStyles.statValue}>{data.humidity} %</Text>
          </View>
          <Text style={weatherStyles.statLabel}>Luftfeuchte</Text>
        </View>
        <View style={weatherStyles.stat}>
          <View style={weatherStyles.statValueRow}>
            <WindIcon size={14} color={glyphs.line} />
            <Text style={weatherStyles.statValue}>{data.wind_kmh} km/h</Text>
          </View>
          <Text style={weatherStyles.statLabel}>Wind</Text>
        </View>
        {precip !== null && (
          <View style={weatherStyles.stat}>
            <View style={weatherStyles.statValueRow}>
              <WeatherGlyph kind="rain" size={16} palette={glyphs} />
              <Text style={weatherStyles.statValue}>{precip} %</Text>
            </View>
            <Text style={weatherStyles.statLabel}>Niederschlag</Text>
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
    location: {...I.eyebrow, marginBottom: 12},
    topRow: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      marginBottom: 16,
    },
    temp: {color: P.text, fontSize: 64, fontWeight: '200', lineHeight: 68},
    feels: {...I.caption, marginTop: 2},
    condition: {...I.title, marginTop: 8},
    hiLoRow: {flexDirection: 'row', gap: 10},
    hiLoItem: {
      ...I.pill,
      color: P.text,
      fontSize: 14,
      fontWeight: '600',
    },
    arrowUp: {color: P.accent, fontWeight: '700'},
    arrowDown: {color: P.accent2, fontWeight: '700'},
    divider: {...I.divider, marginTop: 18, marginBottom: 12},
    hourlyScroll: {marginBottom: 16},
    hourlyItem: {
      alignItems: 'center',
      marginRight: 8,
      minWidth: 60,
      paddingVertical: 10,
      paddingHorizontal: 8,
      borderRadius: INFO_CARD.innerRadius,
      gap: 4,
    },
    hourlyItemCurrent: {
      backgroundColor: P.accentSoftStrong,
    },
    hourlyTime: {color: P.muted, fontSize: 13},
    hourlyTimeCurrent: {color: P.accent2, fontWeight: '700'},
    hourlyPrecip: {color: P.muted, fontSize: 12},
    hourlyTemp: {color: P.text, fontSize: 15, fontWeight: '500'},
    hourlyTempCurrent: {fontWeight: '700'},
    // Details als eigene, getönte Kachel (One-UI-Gruppierung) statt loser Zeile mit Trennlinie
    statRow: {
      flexDirection: 'row',
      marginTop: 18,
      paddingVertical: 14,
      paddingHorizontal: 8,
      borderRadius: INFO_CARD.innerRadius,
      backgroundColor: P.accentSoft,
    },
    stat: {flex: 1, alignItems: 'center', gap: 2},
    statValueRow: {flexDirection: 'row', alignItems: 'center', gap: 6},
    statValue: {color: P.text, fontSize: 14, fontWeight: '600'},
    statLabel: {color: P.muted, fontSize: 13},
  });
};


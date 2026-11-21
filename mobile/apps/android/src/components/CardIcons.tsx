/**
 * CardIcons — kleine, konsistente Linien-Icons für die Info-Karten (Kalender, Karte/Ort, Chevron).
 * Wie WeatherGlyph aus einfachen Views gezeichnet (keine Emoji, keine Icon-Bibliothek, kein SVG);
 * Farben kommen immer aus dem Theme (ColorValue → auch Material-You-PlatformColor).
 * Rein dekorativ → für Screenreader ausgeblendet.
 */
import React from 'react';
import {View, type ColorValue} from 'react-native';

interface IconProps {
  size: number;
  color: ColorValue;
}

const HIDDEN = {importantForAccessibility: 'no-hide-descendants' as const, accessibilityElementsHidden: true};

/** Kalenderblatt: Rahmen, ausgefüllte Kopfleiste, zwei Ringe, zwei Tage-Punkte. */
export function CalendarIcon({size: S, color}: IconProps): React.JSX.Element {
  const stroke = Math.max(1.5, S * 0.09);
  const bodyTop = S * 0.14;
  const bodyH = S * 0.82;
  const headH = S * 0.28;
  const dot = S * 0.14;
  return (
    <View style={{width: S, height: S}} testID="icon-calendar" {...HIDDEN}>
      <View
        style={{
          position: 'absolute', left: S * 0.06, top: bodyTop, width: S * 0.88, height: bodyH,
          borderRadius: S * 0.2, borderWidth: stroke, borderColor: color, overflow: 'hidden',
        }}>
        <View style={{height: headH - stroke, backgroundColor: color}} />
      </View>
      {[0.28, 0.64].map(x => (
        <View
          key={x}
          style={{
            position: 'absolute', left: S * x, top: 0, width: stroke, height: S * 0.26,
            borderRadius: stroke / 2, backgroundColor: color,
          }}
        />
      ))}
      <View style={{position: 'absolute', left: S * 0.24, top: S * 0.58, width: dot, height: dot, borderRadius: dot / 2, backgroundColor: color}} />
      <View style={{position: 'absolute', left: S * 0.58, top: S * 0.58, width: dot, height: dot, borderRadius: dot / 2, backgroundColor: color}} />
    </View>
  );
}

/** Ortsmarke: Tropfen-Umriss (Spitze unten) mit Mittelpunkt. */
export function PinIcon({size: S, color}: IconProps): React.JSX.Element {
  const d = S * 0.62;
  const stroke = Math.max(1.5, S * 0.1);
  const dot = d * 0.36;
  return (
    <View style={{width: S, height: S}} testID="icon-pin" {...HIDDEN}>
      {/* Gruppe leicht nach oben, damit die Spitze optisch in der Box sitzt */}
      <View style={{width: S, height: S, alignItems: 'center', justifyContent: 'center', transform: [{translateY: -S * 0.06}]}}>
        <View
          style={{
            width: d, height: d, borderWidth: stroke, borderColor: color,
            borderTopLeftRadius: d / 2, borderTopRightRadius: d / 2, borderBottomLeftRadius: d / 2, borderBottomRightRadius: 0,
            transform: [{rotate: '-45deg'}],
          }}
        />
        <View style={{position: 'absolute', width: dot, height: dot, borderRadius: dot / 2, backgroundColor: color}} />
      </View>
    </View>
  );
}

/** Chevron nach rechts (zwei abgerundete Balken, Spitze rechts), als Ersatz für das Textzeichen „›“. */
export function ChevronRightIcon({size: S, color}: IconProps): React.JSX.Element {
  const stroke = Math.max(2, S * 0.14);
  const len = S * 0.46;
  const h = len * Math.SQRT1_2; // Halbdiagonale-Ausdehnung je Balken
  const apexX = S * 0.62;
  const cy = S / 2;
  const bar = (centerY: number, rotate: string) => ({
    position: 'absolute' as const,
    left: apexX - h / 2 - stroke / 2,
    top: centerY - len / 2,
    width: stroke,
    height: len,
    borderRadius: stroke / 2,
    backgroundColor: color,
    transform: [{rotate}],
  });
  return (
    <View style={{width: S, height: S}} testID="icon-chevron" {...HIDDEN}>
      <View style={bar(cy - h / 2, '-45deg')} />
      <View style={bar(cy + h / 2, '45deg')} />
    </View>
  );
}

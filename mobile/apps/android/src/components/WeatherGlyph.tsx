/**
 * WeatherGlyph — konsistenter Wetter-Icon-Satz aus einfachen Formen (keine Emoji, keine Icon-Bibliothek).
 * Gezeichnet mit Views; Farben kommen aus glyphPalette (hell/dunkel).
 */
import React from 'react';
import {View, StyleSheet} from 'react-native';
import type {GlyphPalette, WeatherKind} from '../utils/weatherLook';

interface GlyphProps {
  kind: WeatherKind;
  size: number;
  palette: GlyphPalette;
  testID?: string;
}

const abs = StyleSheet.absoluteFillObject;

function Disc({d, left, top, color, opacity = 1}: {d: number; left: number; top: number; color: string; opacity?: number}) {
  return <View style={{position: 'absolute', width: d, height: d, borderRadius: d / 2, left, top, backgroundColor: color, opacity}} />;
}

function Sun({d, left, top, color}: {d: number; left: number; top: number; color: string}) {
  // d = Durchmesser des gesamten Sonnen-Symbols (Kern + Strahlen) in einer Box der Größe d bei (left, top)
  const core = d * 0.46;
  const rays = Array.from({length: 8}, (_, i) => i * 45);
  const rayW = Math.max(1.5, d * 0.07);
  const rayH = d * 0.15;
  const offset = d * 0.375;
  return (
    <View style={{position: 'absolute', width: d, height: d, left, top}} pointerEvents="none">
      <Disc d={core} left={(d - core) / 2} top={(d - core) / 2} color={color} />
      {rays.map(a => (
        <View
          key={a}
          style={{
            position: 'absolute',
            width: rayW,
            height: rayH,
            borderRadius: rayW / 2,
            left: d / 2 - rayW / 2,
            top: d / 2 - rayH / 2,
            backgroundColor: color,
            transform: [{rotate: `${a}deg`}, {translateY: -offset}],
          }}
        />
      ))}
    </View>
  );
}

/** Wolke in einer Box (left, top, Breite w). */
function Cloud({left, top, w, color}: {left: number; top: number; w: number; color: string}) {
  const h = w * 0.62;
  return (
    <View style={{position: 'absolute', left, top, width: w, height: h}} pointerEvents="none">
      <View style={{position: 'absolute', left: 0, top: h * 0.42, width: w, height: h * 0.58, borderRadius: h * 0.29, backgroundColor: color}} />
      <Disc d={w * 0.44} left={w * 0.14} top={h * 0.16} color={color} />
      <Disc d={w * 0.54} left={w * 0.36} top={0} color={color} />
    </View>
  );
}

function Moon({d, left, top, color}: {d: number; left: number; top: number; color: string}) {
  return (
    <View style={{position: 'absolute', width: d, height: d, left, top}} pointerEvents="none">
      <Disc d={d} left={0} top={0} color={color} />
      <Disc d={d * 0.22} left={d * 0.24} top={d * 0.26} color="rgba(0,0,0,0.10)" />
      <Disc d={d * 0.14} left={d * 0.58} top={d * 0.52} color="rgba(0,0,0,0.10)" />
    </View>
  );
}

function Bar({w, h, left, top, color, rotate = 0}: {w: number; h: number; left: number; top: number; color: string; rotate?: number}) {
  return (
    <View
      style={{
        position: 'absolute', width: w, height: h, borderRadius: Math.min(w, h) / 2, left, top, backgroundColor: color,
        transform: [{rotate: `${rotate}deg`}],
      }}
    />
  );
}

export function WeatherGlyph({kind, size: S, palette: C, testID}: GlyphProps): React.JSX.Element {
  let content: React.ReactNode;
  switch (kind) {
    case 'sun':
      content = <Sun d={S} left={0} top={0} color={C.sun} />;
      break;
    case 'moon':
      content = <Moon d={S * 0.62} left={S * 0.19} top={S * 0.19} color={C.moon} />;
      break;
    case 'partly':
      content = (
        <>
          <Sun d={S * 0.66} left={S * 0.02} top={0} color={C.sun} />
          <Cloud left={S * 0.18} top={S * 0.36} w={S * 0.78} color={C.cloud} />
        </>
      );
      break;
    case 'partlyNight':
      content = (
        <>
          <Moon d={S * 0.42} left={S * 0.12} top={S * 0.04} color={C.moon} />
          <Cloud left={S * 0.18} top={S * 0.36} w={S * 0.78} color={C.cloud} />
        </>
      );
      break;
    case 'rain':
      content = (
        <>
          <Cloud left={S * 0.08} top={S * 0.08} w={S * 0.84} color={C.cloud} />
          {[0.26, 0.46, 0.66].map((x, i) => (
            <Bar key={i} w={S * 0.07} h={S * 0.2} left={S * x} top={S * 0.66 - (i % 2) * S * 0.04} color={C.drop} rotate={18} />
          ))}
        </>
      );
      break;
    case 'storm':
      content = (
        <>
          <Cloud left={S * 0.08} top={S * 0.06} w={S * 0.84} color={C.cloud} />
          <Bar w={S * 0.09} h={S * 0.22} left={S * 0.44} top={S * 0.6} color={C.bolt} rotate={22} />
          <Bar w={S * 0.09} h={S * 0.18} left={S * 0.5} top={S * 0.74} color={C.bolt} rotate={-30} />
        </>
      );
      break;
    case 'snow':
      content = (
        <>
          <Cloud left={S * 0.08} top={S * 0.08} w={S * 0.84} color={C.cloud} />
          {[0.28, 0.5, 0.72].map((x, i) => (
            <Disc key={i} d={S * 0.1} left={S * x - S * 0.05} top={S * (0.72 + (i % 2) * 0.08)} color={C.snow} />
          ))}
        </>
      );
      break;
    case 'fog':
      content = (
        <>
          <Bar w={S * 0.7} h={S * 0.09} left={S * 0.15} top={S * 0.24} color={C.line} />
          <Bar w={S * 0.82} h={S * 0.09} left={S * 0.09} top={S * 0.45} color={C.line} />
          <Bar w={S * 0.58} h={S * 0.09} left={S * 0.21} top={S * 0.66} color={C.line} />
        </>
      );
      break;
    case 'cloud':
    default:
      content = <Cloud left={S * 0.06} top={S * 0.2} w={S * 0.88} color={C.cloud} />;
  }
  return (
    <View
      testID={testID}
      style={{width: S, height: S}}
      importantForAccessibility="no-hide-descendants"
      accessibilityElementsHidden>
      <View style={abs}>{content}</View>
    </View>
  );
}

/** Kleiner Tropfen (Luftfeuchte). */
export function DropIcon({size, color}: {size: number; color: string}): React.JSX.Element {
  const d = size * 0.66;
  return (
    <View style={{width: size, height: size, alignItems: 'center', justifyContent: 'center'}} importantForAccessibility="no-hide-descendants">
      <View
        style={{
          width: d, height: d, backgroundColor: color,
          borderTopLeftRadius: d / 2, borderTopRightRadius: 0, borderBottomLeftRadius: d / 2, borderBottomRightRadius: d / 2,
          transform: [{rotate: '-45deg'}, {translateY: size * 0.06}],
        }}
      />
    </View>
  );
}

/** Wind: drei gestaffelte Linien. */
export function WindIcon({size, color}: {size: number; color: string}): React.JSX.Element {
  const h = Math.max(1.5, size * 0.1);
  return (
    <View style={{width: size, height: size}} importantForAccessibility="no-hide-descendants">
      <Bar w={size * 0.8} h={h} left={size * 0.05} top={size * 0.26} color={color} />
      <Bar w={size * 0.62} h={h} left={size * 0.2} top={size * 0.46} color={color} />
      <Bar w={size * 0.74} h={h} left={size * 0.05} top={size * 0.66} color={color} />
    </View>
  );
}

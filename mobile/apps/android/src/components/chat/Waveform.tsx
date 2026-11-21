import React, {useRef, useEffect} from 'react';
import {View, StyleSheet, Animated, Easing} from 'react-native';
import {useThemedStyles} from '../../theme';
import {useReduceMotion} from '../../hooks/useReduceMotion';
import type {Theme} from '../../theme';

// ─── Waveform (28 animated bars) ─────────────────────────────────────────────
const BAR_COUNT = 28;
export function Waveform({active}: {active: boolean}): React.JSX.Element {
  const waveStyles = useThemedStyles(makeWaveStyles);
  const bars = useRef(
    Array.from({length: BAR_COUNT}, () => new Animated.Value(0.15)),
  ).current;

  const reduceMotion = useReduceMotion();

  useEffect(() => {
    // "Animationen entfernen" (und solange das Ergebnis noch offen ist): ruhige, statische Balken
    if (reduceMotion !== false) {
      bars.forEach(b => b.setValue(active ? 0.5 : 0.15));
      return undefined;
    }
    if (!active) {
      bars.forEach(b => Animated.timing(b, {toValue: 0.15, duration: 200, useNativeDriver: false}).start());
      return;
    }
    const animations = bars.map((b, i) => {
      const duration = 300 + ((i * 37) % 400);
      return Animated.loop(
        Animated.sequence([
          Animated.timing(b, {toValue: 0.15 + Math.random() * 0.8, duration, useNativeDriver: false, easing: Easing.inOut(Easing.ease)}),
          Animated.timing(b, {toValue: 0.1  + Math.random() * 0.3, duration, useNativeDriver: false, easing: Easing.inOut(Easing.ease)}),
        ]),
      );
    });
    animations.forEach(a => a.start());
    return () => animations.forEach(a => a.stop());
  }, [active, bars, reduceMotion]);

  return (
    <View style={waveStyles.row}>
      {bars.map((b, i) => (
        <Animated.View
          key={i}
          style={[
            waveStyles.bar,
            {
              height: b.interpolate({inputRange: [0, 1], outputRange: [4, 40]}),
              opacity: active ? b.interpolate({inputRange: [0, 1], outputRange: [0.4, 1]}) : 0.25,
            },
          ]}
        />
      ))}
    </View>
  );
}
const makeWaveStyles = (P: Theme) => StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    height: 48,
    gap: 3,
    paddingHorizontal: 16,
    marginVertical: 8,
  },
  bar: {
    width: 3,
    borderRadius: 2,
    backgroundColor: P.accent,
  },
});


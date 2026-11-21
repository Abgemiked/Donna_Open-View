import React, {useRef, useEffect} from 'react';
import {View, StyleSheet, Animated} from 'react-native';
import {useThemedStyles} from '../../theme';
import {useReduceMotion} from '../../hooks/useReduceMotion';
import {DonnaAvatar} from '../../components/DonnaAvatar';
import type {Theme} from '../../theme';

// ─── TypingIndicator ──────────────────────────────────────────────────────────
export function TypingIndicator(): React.JSX.Element {
  const typingStyles = useThemedStyles(makeTypingStyles);
  const dots = useRef([
    new Animated.Value(0),
    new Animated.Value(0),
    new Animated.Value(0),
  ]).current;

  const reduceMotion = useReduceMotion();

  useEffect(() => {
    // "Animationen entfernen" (und solange das Ergebnis noch offen ist): statische Punkte
    if (reduceMotion !== false) {
      dots.forEach(d => d.setValue(1));
      return undefined;
    }
    const anims = dots.map((d, i) =>
      Animated.loop(
        Animated.sequence([
          Animated.delay(i * 150),
          Animated.timing(d, {toValue: 1, duration: 350, useNativeDriver: true}),
          Animated.timing(d, {toValue: 0, duration: 350, useNativeDriver: true}),
          Animated.delay(450 - i * 150),
        ]),
      ),
    );
    anims.forEach(a => a.start());
    return () => anims.forEach(a => a.stop());
  }, [dots, reduceMotion]);

  return (
    <View style={typingStyles.row}>
      <DonnaAvatar size={24} />
      <View style={typingStyles.bubble}>
        {dots.map((d, i) => (
          <Animated.View
            key={i}
            style={[typingStyles.dot, {opacity: d.interpolate({inputRange: [0, 1], outputRange: [0.3, 1]})}]}
          />
        ))}
      </View>
    </View>
  );
}
const makeTypingStyles = (P: Theme) => StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 12,
    marginLeft: 4,
  },
  bubble: {
    flexDirection: 'row',
    backgroundColor: P.card,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: P.donnaBorder,
    paddingHorizontal: 14,
    paddingVertical: 12,
    marginLeft: 8,
    gap: 5,
    alignItems: 'center',
  },
  dot: {
    width: 6,
    height: 6,
    borderRadius: 3,
    backgroundColor: P.accent,
  },
});


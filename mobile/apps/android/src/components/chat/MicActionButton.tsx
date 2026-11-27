import React, {useEffect, useRef} from 'react';
import {Animated, StyleSheet, TouchableOpacity} from 'react-native';
import {MOTION, useTheme, useThemedStyles} from '../../theme';
import type {Theme} from '../../theme';
import {useReduceMotion} from '../../hooks/useReduceMotion';
import {Icon} from '../Icon';
import {micLabel} from './chatView';

export interface MicActionButtonProps {
  /** Mikrofon offen (Taste wird zum Stopp). */
  active: boolean;
  /** Donna spricht/antwortet: die Taste unterbricht dann. */
  interrupting?: boolean;
  onPress: () => void;
  /** Kantenlaenge in dp (Leerzustand 64, Chat-Leiste 48). */
  size: number;
}

/** Mint-Mikrofon (die eine Hauptaktion je Ansicht). Pulsiert beim Zuhoeren, ausser "Animationen entfernen" ist an. */
export function MicActionButton({active, interrupting = false, onPress, size}: MicActionButtonProps): React.JSX.Element {
  const styles = useThemedStyles(makeStyles);
  const P = useTheme();
  const reduceMotion = useReduceMotion();
  const scale = useRef(new Animated.Value(1)).current;
  useEffect(() => {
    if (active && reduceMotion === false) {
      const anim = Animated.loop(
        Animated.sequence([
          Animated.timing(scale, {toValue: 1.08, duration: MOTION.base * 2, useNativeDriver: true}),
          Animated.timing(scale, {toValue: 1, duration: MOTION.base * 2, useNativeDriver: true}),
        ]),
      );
      anim.start();
      // DONNA-38: Cleanup verhindert "Animated node does not exist"-Crash
      return () => {
        anim.stop();
        scale.stopAnimation();
      };
    }
    scale.stopAnimation();
    scale.setValue(1);
    return undefined;
  }, [active, reduceMotion, scale]);

  return (
    <TouchableOpacity
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={micLabel(active, interrupting)}
      accessibilityState={{selected: active}}>
      <Animated.View
        style={[styles.btn, {width: size, height: size, borderRadius: size / 2}, {transform: [{scale}]}]}>
        <Icon name={active ? 'stop' : 'mic'} size={Math.round(size * 0.44)} color={P.onAction as string} />
      </Animated.View>
    </TouchableOpacity>
  );
}

const makeStyles = (P: Theme) => StyleSheet.create({
  btn: {
    backgroundColor: P.action,
    justifyContent: 'center',
    alignItems: 'center',
  },
});

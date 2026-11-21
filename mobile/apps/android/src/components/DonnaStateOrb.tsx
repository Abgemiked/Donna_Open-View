/**
 * Zustandsanzeige im Chat (#122 Paket D, Design #134 P3): weicher, langsam drehender Farbkranz (react-native-svg,
 * `orb/OrbWreath`) mit ruhigem Kern und Strich-Icon je Zustand (Ruhe/Sprechen/Denken `wave`, Zuhoeren `mic`,
 * Fehler `alert`). Was gezeigt wird, entscheidet die reine Abbildung `orb/orbAppearance.ts` + `theme/orbState.ts`;
 * diese Datei animiert nur (Animated, useNativeDriver: nur transform/opacity, keine neue Abhaengigkeit).
 *
 * - Kranz dreht in Ruhe sehr langsam (14 s), aktiv schneller; Puls/Schuetteln wie bisher nach `orbState.ts`.
 * - Zustandswechsel und Unmount stoppen alle Schleifen (DONNA-38: nie Knoten ohne View).
 * - "Animationen entfernen" -> alles statisch (Farbe, Icon, Text bleiben).
 */
import React, {useEffect, useLayoutEffect, useMemo, useRef} from 'react';
import {Animated, Easing, StyleSheet, Text, View} from 'react-native';
import {useReduceMotion} from '../hooks/useReduceMotion';
import {FONT, RADIUS, SPACE, useTheme, useThemedStyles} from '../theme';
import type {Theme} from '../theme';
import {ORB_LOOK, ORB_TIMING, orbVisual} from '../theme/orbState';
import type {OrbDisplayState} from '../theme/orbState';
import {Icon} from './Icon';
import {OrbWreath} from './orb/OrbWreath';
import {ORB_LIGHT_RINGS} from './orb/orbColors';
import {orbAppearance, orbCoreColors, shouldAnimate} from './orb/orbAppearance';

/** Deckkraft der sichtbaren Statuspille (leicht durchscheinend, Text bleibt lesbar). */
const STATUS_OPACITY = 0.94;
/** Schuettel-Amplitude beim Fehler (dp) und Stuetzstellen der abklingenden Schwingung (wie OrbMath.shakeOffset). */
const SHAKE_AMPLITUDE = 6;
const SHAKE_STEPS = 16;
const SHAKE_INPUT = Array.from({length: SHAKE_STEPS + 1}, (_, i) => i / SHAKE_STEPS);
const SHAKE_OUTPUT = SHAKE_INPUT.map(t => SHAKE_AMPLITUDE * Math.sin(2 * Math.PI * 3 * t) * (1 - t));
/** Anteil des Pulses an der Kranz-Deckkraft (Kranz atmet leicht mit). */
const WREATH_PULSE_DEPTH = 0.2;
/** Groessen relativ zur Kantenlaenge: Kern (Dunkel gross, Hell klein mit Ringen), Ringe, Icon. */
const CORE_DARK = 0.74;
const CORE_LIGHT = 0.46;
const RINGS_LIGHT = [0.78, 0.62] as const;
const ICON_DARK = 0.24;
const ICON_LIGHT = 0.22;

function periodFor(state: OrbDisplayState): number {
  switch (state) {
    case 'thinking': return ORB_TIMING.thinkingPeriodMs;
    case 'speaking': return ORB_TIMING.speakingPeriodMs;
    default: return ORB_TIMING.listeningPeriodMs;
  }
}

export interface DonnaStateOrbProps {
  state: OrbDisplayState;
  /** Kantenlaenge in dp (Standard 120 wie der bisherige Orb). */
  size?: number;
  /** true: Orb ist Zierde neben einem Text, der den Zustand schon nennt -> kein eigenes Screenreader-Label. */
  silent?: boolean;
  /**
   * false: Orb steht (keine Drehung, kein Puls, kein Schuetteln); Views bleiben gemountet. Fuer dauerhaft gemountete,
   * unsichtbare Orbs (Hoerfenster, Statuszeile). Standard true.
   */
  active?: boolean;
}

export function DonnaStateOrb({state, size = 120, silent = false, active = true}: DonnaStateOrbProps): React.JSX.Element {
  const P = useTheme();
  const styles = useThemedStyles(makeStyles);
  const reduceMotion = useReduceMotion();
  // null = Ergebnis der Bedienungshilfen noch offen -> statisch, bis es da ist (keine Schleife vor dem Ergebnis)
  const animated = shouldAnimate(active, reduceMotion);
  const visual = orbVisual(state, animated);
  const look = ORB_LOOK[state];
  const app = orbAppearance(state, animated);
  const colors = orbCoreColors(P, state);
  const phase = useRef(new Animated.Value(0)).current;
  const shake = useRef(new Animated.Value(0)).current;
  const spin = useRef(new Animated.Value(0)).current;
  // Drehwinkel ohne Sprung: Beim Wechsel der Umlaufdauer (oder Stopp) wird der aktuelle Winkel per Uhr berechnet
  // (nativ gelaufene Werte liessen sich nur asynchron lesen) und als Versatz uebernommen; die Schleife startet bei 0.
  const clock = useRef<{ms: number | null; t0: number; off: number}>({ms: app.spinMs, t0: Date.now(), off: 0});
  if (clock.current.ms !== app.spinMs) {
    const c = clock.current;
    const frac = c.ms === null ? c.off : (c.off + (Date.now() - c.t0) / c.ms) % 1;
    clock.current = {ms: app.spinMs, t0: Date.now(), off: frac};
  }
  const spinOffset = clock.current.off;

  // Puls (Zuhoeren/Denken/Sprechen) bzw. einmaliges Schuetteln (Fehler)
  useEffect(() => {
    phase.setValue(0);
    shake.setValue(0);
    if (visual.motion === 'none') {
      return undefined;
    }
    if (visual.motion === 'shake') {
      const once = Animated.timing(shake, {
        toValue: 1,
        duration: ORB_TIMING.errorShakeMs,
        easing: Easing.linear,
        useNativeDriver: true,
      });
      once.start();
      return () => {
        once.stop();
        shake.stopAnimation();
      };
    }
    const half = periodFor(state) / 2;
    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(phase, {toValue: 1, duration: half, easing: Easing.inOut(Easing.sin), useNativeDriver: true}),
        Animated.timing(phase, {toValue: 0, duration: half, easing: Easing.inOut(Easing.sin), useNativeDriver: true}),
      ]),
    );
    loop.start();
    // DONNA-38: Cleanup verhindert "Animated node does not exist" beim Zustandswechsel/Unmount
    return () => {
      loop.stop();
      phase.stopAnimation();
    };
  }, [state, visual.motion, phase, shake]);

  // Drehung des Kranzes: gleichmaessig, nur wenn der Zustand sie verlangt. Layout-Effekt: Reset und neuer Versatz
  // greifen im selben Commit, vor dem Zeichnen (kein Zwischenbild mit Sprung).
  useLayoutEffect(() => {
    spin.setValue(0);
    if (app.spinMs === null) {
      return undefined;
    }
    const loop = Animated.loop(
      Animated.timing(spin, {toValue: 1, duration: app.spinMs, easing: Easing.linear, useNativeDriver: true}),
    );
    loop.start();
    return () => {
      loop.stop();
      spin.stopAnimation();
    };
  }, [app.spinMs, spin]);

  const pulsing = visual.motion !== 'none' && visual.motion !== 'shake';
  // Interpolationsknoten stabil halten (nicht bei jedem Render neu anlegen)
  const scale = useMemo(
    () => phase.interpolate({inputRange: [0, 1], outputRange: [look.scaleMin, look.scaleMax]}),
    [phase, look.scaleMin, look.scaleMax],
  );
  const translateX = useMemo(() => shake.interpolate({inputRange: SHAKE_INPUT, outputRange: SHAKE_OUTPUT}), [shake]);
  const rotate = useMemo(
    () => spin.interpolate({inputRange: [0, 1], outputRange: [`${spinOffset * 360}deg`, `${spinOffset * 360 + 360}deg`]}),
    [spin, spinOffset],
  );
  const wreathOpacity = useMemo(
    () => (pulsing
      ? phase.interpolate({
          inputRange: [0, 1],
          outputRange: [app.wreathOpacity * (1 - WREATH_PULSE_DEPTH), app.wreathOpacity],
        })
      : app.wreathOpacity),
    [phase, pulsing, app.wreathOpacity],
  );
  const dark = P.scheme === 'dark';
  const core = size * (dark ? CORE_DARK : CORE_LIGHT);
  const iconSize = Math.round(size * (dark ? ICON_DARK : ICON_LIGHT));

  return (
    <View
      style={[styles.container, {width: size, height: size}]}
      accessible={!silent}
      accessibilityLabel={silent ? undefined : statusText(state)}
      importantForAccessibility={silent ? 'no-hide-descendants' : 'yes'}
      testID="donna-state-orb">
      <Animated.View
        style={[styles.fill, {transform: [{translateX}, {scale: pulsing ? scale : 1}]}]}
        testID="donna-orb-body">
        {app.wreathVisible ? (
          <Animated.View
            style={[styles.fill, {opacity: wreathOpacity, transform: [{rotate}]}]}
            testID="donna-orb-wreath-spin">
            <OrbWreath size={size} />
          </Animated.View>
        ) : null}
        {colors.rings
          ? RINGS_LIGHT.map((f, i) => (
              <View
                key={f}
                style={[styles.ring, {width: size * f, height: size * f, borderRadius: size * f / 2, backgroundColor: ORB_LIGHT_RINGS[i]}]}
              />
            ))
          : null}
        <View
          style={[styles.core, {width: core, height: core, borderRadius: core / 2, backgroundColor: colors.fill, borderColor: colors.border}]}
          testID="donna-orb-core">
          <Icon name={app.icon} size={iconSize} color={colors.icon} />
        </View>
      </Animated.View>
    </View>
  );
}

/**
 * Kurztext fuer die Statuszeile: der Text aus der Zustandstabelle (orbState.ts), identisch zum Overlay. Fehler: "Das hat
 * nicht geklappt." — der Chat bietet dort keine Wiederholen-Aktion an, also wird keine versprochen.
 * (Der Fehlerzustand ist im Chat derzeit NICHT verdrahtet, siehe ChatScreen `mapOrbState`.)
 */
export function statusText(state: OrbDisplayState): string {
  return orbVisual(state, true).label;
}

/**
 * Kompakte Statuszeile (kleiner Orb + Zustandstext) fuer Sprechen und Fehler, als Pille UEBER der Liste (absolut
 * positioniert, nimmt kein Layout ein; der Aufrufer legt sie in einen hoehenlosen Wrapper direkt ueber die
 * Eingabezeile). Ruhe/Denken/Hoeren: opacity 0, aber dauerhaft gemountet. Die Live-Region ist konstant
 * eingebaut (nie umgeschaltet, damit der Wechsel zuverlaessig angesagt wird); angesagt wird nur der Fehler:
 * der Fehlertext ist ein eigener Text, der nur dann Inhalt hat, wenn der Zustand `error` ist. Der Sprech-Text ist fuer
 * den Screenreader ausgeblendet (Donna ist dann ohnehin hoerbar). Denken zeigt der Chat ueber den TypingIndicator.
 */
export function DonnaStatusRow({state}: {state: OrbDisplayState}): React.JSX.Element {
  const styles = useThemedStyles(makeStyles);
  return (
    <View
      style={[styles.statusRow, {opacity: state === 'speaking' || state === 'error' ? STATUS_OPACITY : 0}]}
      pointerEvents="none"
      accessibilityLiveRegion="polite"
      testID="donna-status-row">
      <DonnaStateOrb state={state} size={40} silent active={state === 'speaking' || state === 'error'} />
      <Text style={styles.statusText} importantForAccessibility="no" testID="donna-status-speaking">
        {state === 'speaking' ? statusText('speaking') : ''}
      </Text>
      <Text style={styles.statusText} importantForAccessibility="yes" testID="donna-status-error">
        {state === 'error' ? statusText('error') : ''}
      </Text>
    </View>
  );
}

const makeStyles = (P: Theme) => StyleSheet.create({
  container: {
    justifyContent: 'center',
    alignItems: 'center',
    alignSelf: 'center',
  },
  fill: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    justifyContent: 'center',
    alignItems: 'center',
  },
  ring: {
    position: 'absolute',
  },
  core: {
    borderWidth: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  // Pille ueber der Liste: absolut, am unteren Rand des Wrappers (direkt ueber der Eingabezeile), ohne eigene Hoehe
  statusRow: {
    position: 'absolute',
    bottom: SPACE.sm,
    alignSelf: 'center',
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACE.sm,
    paddingVertical: SPACE.xs,
    paddingLeft: SPACE.sm,
    paddingRight: SPACE.lg,
    borderRadius: RADIUS.pill,
    borderWidth: 1,
    borderColor: P.border,
    backgroundColor: P.surface,
  },
  statusText: {color: P.muted, fontSize: FONT.label},
});

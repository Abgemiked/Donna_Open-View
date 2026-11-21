/**
 * Reine Abbildung Zustand + Farbwelt -> Darstellung des Orbs (#134 P3). Keine React-Abhaengigkeit.
 * Zustaende, Zeiten und Bewegungsarten bleiben die aus `theme/orbState.ts`; hier kommen nur Icon, Kranz und
 * Kernfarben dazu.
 */
import type {IconName} from '../../theme/iconPaths';
import type {Theme} from '../../theme';
import {orbToneColor, orbVisual} from '../../theme/orbState';
import type {OrbDisplayState, OrbMotion} from '../../theme/orbState';

export interface OrbAppearance {
  icon: IconName;
  /** Farbkranz sichtbar (auch im Fehlerfall, dort stark gedaempft). */
  wreathVisible: boolean;
  /** Grunddeckkraft des Kranzes (0..1). */
  wreathOpacity: number;
  /** Umlaufdauer des Kranzes in ms; null = steht (Fehler oder "Animationen entfernen"). */
  spinMs: number | null;
  /** Bewegungsart des Zustands (nach "Animationen entfernen" = none). */
  motion: OrbMotion;
}

interface StateLook {
  icon: IconName;
  wreathOpacity: number;
  spinMs: number | null;
}

/** Ruhe dreht sehr langsam (14 s, "lebendige" Ruhe), aktive Zustaende schneller. */
const LOOKS: Readonly<Record<OrbDisplayState, StateLook>> = {
  idle: {icon: 'wave', wreathOpacity: 0.75, spinMs: 14_000},
  listening: {icon: 'mic', wreathOpacity: 0.9, spinMs: 8_000},
  thinking: {icon: 'wave', wreathOpacity: 0.85, spinMs: 5_000},
  speaking: {icon: 'wave', wreathOpacity: 0.9, spinMs: 6_000},
  error: {icon: 'alert', wreathOpacity: 0.3, spinMs: null},
};

/**
 * Darf der Orb animieren? Nur wenn er aktiv (sichtbar/gebraucht) ist UND die Bedienungshilfen "Animationen entfernen"
 * nicht gesetzt sind. `reduceMotion === null` (Ergebnis noch offen) zaehlt als "nicht animieren".
 * Dauerhaft gemountete, unsichtbare Orbs (Hoerfenster, Statuszeile) setzen `active=false` und kosten keinen Akku.
 */
export function shouldAnimate(active: boolean, reduceMotion: boolean | null): boolean {
  return active && reduceMotion === false;
}

export function orbAppearance(state: OrbDisplayState, animationsEnabled: boolean): OrbAppearance {
  const look = LOOKS[state];
  return {
    icon: look.icon,
    wreathVisible: true,
    wreathOpacity: look.wreathOpacity,
    spinMs: animationsEnabled ? look.spinMs : null,
    motion: orbVisual(state, animationsEnabled).motion,
  };
}

export interface OrbCoreColors {
  fill: string;
  border: string;
  /** Strichfarbe des Icons im Kern. */
  icon: string;
  /** Helle Ringe hinter dem Kern (nur Hell). */
  rings: boolean;
}

type CoreTheme = Pick<Theme, 'scheme' | 'card' | 'border' | 'heading' | 'accentSolid' | 'onAccentSolid' | 'accent' | 'accent2' | 'muted' | 'orbError'>;

/**
 * Dunkel: Flaeche der Palette (`card`) mit feinem Rand, weisses Icon. Hell: Lavendel-Kern (`accentSolid`) mit hellen
 * Ringen. Fehler: Rand in der Fehlerfarbe (Bernstein) in beiden Welten; im hellen Kern bleibt das Icon weiss (Kontrast).
 */
export function orbCoreColors(P: CoreTheme, state: OrbDisplayState): OrbCoreColors {
  const dark = P.scheme === 'dark';
  const error = state === 'error';
  const errorColor = orbToneColor(P, 'error') as string;
  if (dark) {
    return {
      fill: P.card as string,
      border: error ? errorColor : (P.border as string),
      icon: error ? errorColor : (P.heading as string),
      rings: false,
    };
  }
  return {
    fill: P.accentSolid as string,
    border: error ? errorColor : (P.accentSolid as string),
    icon: P.onAccentSolid as string,
    rings: true,
  };
}

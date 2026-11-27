/**
 * Zustandsanzeige der App (#122 Paket D): reine Abbildung Zustand -> Anzeige, mit denselben fuenf Zustaenden,
 * Farbrollen, Texten und Zykluszeiten wie das Overlay (Kotlin: OrbVisual.kt / OrbMath.kt / DonnaOrbView.kt).
 * `designTokens.test.ts` vergleicht diese Tabellen mit dem Kotlin-Quelltext.
 *
 * Keine React-/Android-Abhaengigkeit ausser dem Theme-Typ; die View ist `components/DonnaStateOrb.tsx`.
 */
import type {ColorValue} from 'react-native';
import type {Theme} from './palettes';

export type OrbDisplayState = 'listening' | 'thinking' | 'speaking' | 'idle' | 'error';
export const ORB_STATES: readonly OrbDisplayState[] = ['listening', 'thinking', 'speaking', 'idle', 'error'];

/** Farbrolle (Kotlin: OrbTone). Die echten Farben liefert das Theme (Verweis auf Palette-Eintraege). */
export type OrbTone = 'accent' | 'accent2' | 'muted' | 'error';

/** Bewegungsart (Kotlin: OrbMotion). `none` = statisch. */
export type OrbMotion = 'none' | 'level' | 'breathe' | 'speakPulse' | 'shake';

export interface OrbVisual {
  tone: OrbTone;
  motion: OrbMotion;
  /** Zustandstext (auch Screenreader-Label). */
  label: string;
}

/** Wie OrbVisuals.of (Kotlin), Texte kurz und ohne Anleitung (#134 P7). */
export const ORB_VISUALS: Readonly<Record<OrbDisplayState, OrbVisual>> = {
  listening: {tone: 'accent', motion: 'level', label: 'Ich höre zu'},
  thinking: {tone: 'accent2', motion: 'breathe', label: 'Donna denkt nach'},
  speaking: {tone: 'accent', motion: 'speakPulse', label: 'Donna spricht'},
  idle: {tone: 'muted', motion: 'none', label: 'Bereit'},
  error: {tone: 'error', motion: 'shake', label: 'Das hat nicht geklappt'},
};

/** Zykluszeiten in ms (Kotlin: OrbMath). `listeningLevel`: Der Chat hat keinen Mikrofonpegel -> fester mittlerer Pegel. */
export const ORB_TIMING = {
  listeningPeriodMs: 2_400,
  thinkingPeriodMs: 1_600,
  speakingPeriodMs: 1_000,
  errorShakeMs: 480,
  listeningLevel: 0.5,
} as const;

export interface OrbLook {
  /** Kernskalierung am Anfang / Hoehepunkt des Zyklus (Kotlin: OrbMath.coreScale). */
  scaleMin: number;
  scaleMax: number;
  /** Staerke des Leuchtens (Kotlin: OrbMath.glowStrength). */
  glowMin: number;
  glowMax: number;
}

const LISTEN_BASE = 1 + 0.2 * ORB_TIMING.listeningLevel;
export const ORB_LOOK: Readonly<Record<OrbDisplayState, OrbLook>> = {
  listening: {scaleMin: LISTEN_BASE, scaleMax: LISTEN_BASE + 0.03, glowMin: 0.45 + 0.55 * ORB_TIMING.listeningLevel, glowMax: 0.45 + 0.55 * ORB_TIMING.listeningLevel},
  thinking: {scaleMin: 1, scaleMax: 1.06, glowMin: 0.55, glowMax: 0.55},
  speaking: {scaleMin: 1, scaleMax: 1.1, glowMin: 0.5, glowMax: 0.85},
  idle: {scaleMin: 1, scaleMax: 1, glowMin: 0.25, glowMax: 0.25},
  error: {scaleMin: 1, scaleMax: 1, glowMin: 0.5, glowMax: 0.5},
};

export interface OrbInputs {
  listening: boolean;
  speaking: boolean;
  thinking: boolean;
  error: boolean;
}

/**
 * Chat-Zustaende -> Anzeige-Zustand. Vorrang: Zuhoeren (Mikrofon offen) > Fehler > Sprechen > Denken > Ruhe.
 * Sprechen schlaegt Denken, weil Donna beim Streamen schon spricht, waehrend die Antwort noch laedt (Overlay: SPEAKING).
 */
export function mapOrbState(i: OrbInputs): OrbDisplayState {
  if (i.listening) {return 'listening';}
  if (i.error) {return 'error';}
  if (i.speaking) {return 'speaking';}
  if (i.thinking) {return 'thinking';}
  return 'idle';
}

/** Anzeige eines Zustands; ohne Animationen ("Animationen entfernen") keine Bewegung, Farbe und Text bleiben. */
export function orbVisual(state: OrbDisplayState, animationsEnabled: boolean): OrbVisual {
  const v = ORB_VISUALS[state];
  return animationsEnabled ? v : {...v, motion: 'none'};
}

/** Farbrolle -> Palette-Eintrag (Bernstein fuer Fehler = `orbError`, wie `donna_orb_error` im Overlay). */
export function orbToneColor(P: Pick<Theme, 'accent' | 'accent2' | 'muted' | 'orbError'>, tone: OrbTone): ColorValue {
  switch (tone) {
    case 'accent': return P.accent;
    case 'accent2': return P.accent2;
    case 'muted': return P.muted;
    case 'error': return P.orbError;
  }
}

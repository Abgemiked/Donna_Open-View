/**
 * Zentrale Theme-Quelle der Donna-Android-App (#134 P1, neues Design).
 *
 * Zwei feste Farbwelten: Dunkel „Pflaume" und Hell. Material You (Systemfarben ab Android 12) entfaellt
 * bewusst (Mike, 2026-11-20); es gibt keine Plattformfarben mehr, alle Tokens sind Strings.
 * Hell/Dunkel folgt dem System oder der gespeicherten Wahl (themeChoice.ts / ThemeContext).
 *
 * Kotlin-Seite (VoiceInputActivity): res/values/colors.xml (hell), res/values-night/colors.xml (dunkel).
 * theme.test.ts prueft die Paritaet gegen die XML-Dateien und den Kontrast (>= 4,5:1) beider Paletten.
 *
 * Rollen: `accent` ist die Akzentfarbe fuer TEXT, Icons und Links auf bg/card (dunkel Lavendel hell #b3a8ff, hell
 * #4b3bc9); `accentSolid` (#6c5ce7) ist die FLAECHE (Taste, Orb) mit `onAccentSolid`-Schrift. `action`/`onAction` =
 * Mint fuer die Hauptaktion (Mikrofon). `accentContainer`/`onAccentContainer` = tonale Flaeche (Nutzer-Blase).
 *
 * WICHTIG: Alpha-Farben (accentSoft*, accentBorder*) sind eigene Tokens, weil sich Farben nicht verketten lassen
 * (kein `P.accent + '22'`).
 */
import type {ColorValue} from 'react-native';
import {normalizeScheme, type ColorSchemeName} from './themeChoice';

export type {ColorSchemeName};
export {normalizeScheme};

export interface ThemeColors {
  /** Seitenhintergrund */
  bg: ColorValue;
  /** Header, Eingabeleiste, Sheets */
  surface: ColorValue;
  /** Eingefasste/vertiefte Flaeche (Felder, Gruppen) */
  surfaceSunken: ColorValue;
  /** Karten, Bubbles, Eingabefeld */
  card: ColorValue;
  /** Haarlinien, Trenner */
  border: ColorValue;
  /** Rand von Bedienelementen (Felder, Schalter), als Grafik >= 3:1 */
  controlBorder: ColorValue;
  /** Hauptakzent als Text/Icon/Link (Buttons mit Text, Titel) */
  accent: ColorValue;
  /** Sekundaerakzent (Ueberschriften, Chip-Labels) */
  accent2: ColorValue;
  /** Akzent-FLAECHE (Taste, Orb): Lavendel #6c5ce7, Schrift darauf `onAccentSolid` */
  accentSolid: ColorValue;
  onAccentSolid: ColorValue;
  /** Tonale Akzent-Flaeche (Nutzer-Blase, aktive Pille) und ihre Schrift */
  accentContainer: ColorValue;
  onAccentContainer: ColorValue;
  /** Hauptaktion (Mikrofon): Mint, Schrift `onAction` */
  action: ColorValue;
  onAction: ColorValue;
  /** Ueberschriften */
  heading: ColorValue;
  /** Fliesstext */
  text: ColorValue;
  /** Sekundaertext (≥ 4,5:1 auf bg/surface/card) */
  muted: ColorValue;
  /** Text/Icon auf einer accent-Flaeche */
  onAccent: ColorValue;
  /** Fehlertext/-symbol (≥ 4,5:1 auf bg/card) */
  danger: ColorValue;
  userBubble: ColorValue;
  userBorder: ColorValue;
  donnaBubble: ColorValue;
  donnaBorder: ColorValue;
  /** Getoente Flaeche (Akzent, schwach) */
  accentSoft: ColorValue;
  /** Getoente Flaeche (Akzent, staerker: Chips, aktive Buttons) */
  accentSoftStrong: ColorValue;
  /** Akzent-Rahmen, dezent */
  accentBorder: ColorValue;
  /** Akzent-Rahmen, kraeftig (Chips) */
  accentBorderStrong: ColorValue;
  /** Status "ok" (Punkt, Haekchen) und Warnung */
  ok: ColorValue;
  warning: ColorValue;
  /** Fehlerfarbe der Zustandsanzeige (Bernstein) — identisch zu `donna_orb_error` des Overlays (donna_orb.xml) */
  orbError: ColorValue;
  /** Deaktivierte Flaeche / deaktivierter Text (Onboarding) */
  disabled: ColorValue;
  disabledText: ColorValue;
  /** Ideen-Karten (bewusst anderer Farbton als die Akzent-Karten) */
  ideaAccent: ColorValue;
  ideaAccentDim: ColorValue;
  ideaBorder: ColorValue;
  /** Abdunklung hinter Modals/Sheets — in beiden Modi gleich */
  scrim: ColorValue;
}

export type Theme = ThemeColors & {
  scheme: ColorSchemeName;
};

/** Hex-/rgba-Werte; Textfarben sind bewusst opak (verlaessliche Kontrastpruefung). */
export const PALETTE_DARK: Readonly<Record<keyof ThemeColors, string>> = {
  bg: '#14061b',
  surface: '#24102f',
  surfaceSunken: '#1d0b27',
  card: '#24102f',
  border: '#4a3560',
  controlBorder: '#8f86a8',
  accent: '#b3a8ff',
  accent2: '#d9d3ff',
  accentSolid: '#6c5ce7',
  onAccentSolid: '#ffffff',
  accentContainer: '#3a2a6e',
  onAccentContainer: '#ffffff',
  action: '#34e0b2',
  onAction: '#04241e',
  heading: '#ffffff',
  text: '#ffffff',
  muted: '#bdb4d2',
  onAccent: '#14061b',
  danger: '#fbbf24',
  userBubble: '#3a2a6e',
  userBorder: 'rgba(179,168,255,0.35)',
  donnaBubble: '#24102f',
  donnaBorder: '#4a3560',
  accentSoft: 'rgba(179,168,255,0.12)',
  accentSoftStrong: 'rgba(179,168,255,0.22)',
  accentBorder: 'rgba(179,168,255,0.30)',
  accentBorderStrong: 'rgba(179,168,255,0.85)',
  ok: '#34e0b2',
  warning: '#fbbf24',
  orbError: '#fbbf24',
  disabled: '#2f1c3b',
  disabledText: '#9a90b2',
  ideaAccent: '#a78bfa',
  ideaAccentDim: 'rgba(167,139,250,0.15)',
  ideaBorder: 'rgba(167,139,250,0.30)',
  scrim: 'rgba(0,0,0,0.55)',
};

export const PALETTE_LIGHT: Readonly<Record<keyof ThemeColors, string>> = {
  bg: '#f6f5fb',
  surface: '#ffffff',
  surfaceSunken: '#eeecf7',
  card: '#ffffff',
  border: '#e6e3f2',
  controlBorder: '#6b6880',
  accent: '#4b3bc9',
  accent2: '#352297',
  accentSolid: '#6c5ce7',
  onAccentSolid: '#ffffff',
  accentContainer: '#e3deff',
  onAccentContainer: '#352297',
  action: '#34e0b2',
  onAction: '#04241e',
  heading: '#15131f',
  text: '#2a2740',
  muted: '#5c5878',
  onAccent: '#ffffff',
  danger: '#be123c',
  userBubble: '#e3deff',
  userBorder: 'rgba(75,59,201,0.35)',
  donnaBubble: '#ffffff',
  donnaBorder: '#e6e3f2',
  accentSoft: 'rgba(75,59,201,0.10)',
  accentSoftStrong: 'rgba(75,59,201,0.18)',
  accentBorder: 'rgba(75,59,201,0.30)',
  accentBorderStrong: 'rgba(75,59,201,0.75)',
  ok: '#166534',
  warning: '#92400e',
  orbError: '#b45309',
  disabled: '#e6e3f2',
  disabledText: '#6b6880',
  ideaAccent: '#6d28d9',
  ideaAccentDim: 'rgba(109,40,217,0.10)',
  ideaBorder: 'rgba(109,40,217,0.30)',
  scrim: 'rgba(0,0,0,0.55)',
};

/** Alte Namen (bis P8 „Aufraeumen"): zeigen auf die neuen Paletten, damit bestehende Tests/Imports weiterlaufen. */
export const PULSE_DARK = PALETTE_DARK;
export const PULSE_LIGHT = PALETTE_LIGHT;

const cache = new Map<ColorSchemeName, Theme>();

/** Liefert das (gecachte, referenzstabile) Theme. Unbekanntes Schema -> dunkel. */
export function getTheme(scheme: string | null | undefined): Theme {
  const s = normalizeScheme(scheme);
  let theme = cache.get(s);
  if (!theme) {
    theme = {...(s === 'dark' ? PALETTE_DARK : PALETTE_LIGHT), scheme: s};
    cache.set(s, theme);
  }
  return theme;
}

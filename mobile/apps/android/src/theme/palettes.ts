/**
 * Zentrale Theme-Quelle der Donna-Android-App (UX-01, One UI 9).
 *
 * Statt einer festen dunklen "Pulse"-Palette folgt die App dem System:
 *  - Dark/Light über das System-Farbschema (useColorScheme),
 *  - Akzent-/Flächenfarben ab Android 12 (API 31) aus dem Material-You-System-Palette
 *    (PlatformColor('@android:color/system_*')),
 *  - darunter (oder wenn keine Dynamic Colors): die bisherige Pulse-Cyan-Palette als Fallback,
 *    jetzt mit sauber lesbarer Light-Variante.
 *
 * Kotlin-Seite (VoiceInputActivity): res/values/colors.xml (Light-Fallback), values-night (Dark-Fallback),
 * values-v31 / values-night-v31 (Dynamic Colors). Die Fallback-Werte sind identisch zu den Paletten hier;
 * theme.test.ts prüft die Parität gegen die XML-Dateien.
 *
 * Tonwert-Zuordnung der Material-You-Rampen: 0=100 · 10=99 · 50=95 · 100=90 · 200=80 · 300=70 · 400=60 ·
 * 500=50 · 600=40 · 700=30 · 800=20 · 900=10 · 1000=0.
 *
 * WICHTIG: PlatformColor kennt keinen Alpha-Kanal und lässt sich nicht verketten
 * (kein `P.accent + '22'`). Für getönte Flächen gibt es deshalb eigene Tokens (accentSoft*, accentBorder*).
 */
import {ColorValue, Platform, PlatformColor} from 'react-native';

export type ColorSchemeName = 'light' | 'dark';

export interface ThemeColors {
  /** Seitenhintergrund */
  bg: ColorValue;
  /** Header, Eingabeleiste, Sheets */
  surface: ColorValue;
  /** Karten, Bubbles, Eingabefeld */
  card: ColorValue;
  /** Haarlinien, Trenner */
  border: ColorValue;
  /** Hauptakzent (Buttons, Titel, Links) */
  accent: ColorValue;
  /** Sekundärakzent (Überschriften, Chip-Labels) */
  accent2: ColorValue;
  /** Fließtext */
  text: ColorValue;
  /** Sekundärtext (≥ 4,5:1 auf bg/surface/card) */
  muted: ColorValue;
  /** Text/Icon auf einer accent-Fläche */
  onAccent: ColorValue;
  userBubble: ColorValue;
  userBorder: ColorValue;
  donnaBubble: ColorValue;
  donnaBorder: ColorValue;
  /** Getönte Fläche (Akzent, schwach) */
  accentSoft: ColorValue;
  /** Getönte Fläche (Akzent, stärker: Chips, aktive Buttons) */
  accentSoftStrong: ColorValue;
  /** Akzent-Rahmen, dezent */
  accentBorder: ColorValue;
  /** Akzent-Rahmen, kräftig (Chips) */
  accentBorderStrong: ColorValue;
  /** Status "ok" (Punkt, Häkchen) und Warnung */
  ok: ColorValue;
  warning: ColorValue;
  /** Deaktivierte Fläche / deaktivierter Text (Onboarding) */
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
  /** true = Farben stammen aus der Material-You-System-Palette */
  dynamic: boolean;
};

// ─── Pulse-Fallback (bisherige Palette, Dark bleibt nah am bisherigen Look) ────────────────
/** Hex-/rgba-Werte; Textfarben sind bewusst opak (verlässliche Kontrastprüfung). */
export const PULSE_DARK: Readonly<Record<keyof ThemeColors, string>> = {
  bg: '#03090f',
  surface: '#080f1a',
  card: '#0d1626',
  border: 'rgba(56,189,248,0.14)',
  accent: '#38bdf8',
  accent2: '#7dd3fc',
  text: '#e0f2fe',
  muted: '#8fa5b5',
  onAccent: '#03090f',
  userBubble: '#0c2a3e',
  userBorder: 'rgba(56,189,248,0.35)',
  donnaBubble: '#0d1626',
  donnaBorder: 'rgba(56,189,248,0.14)',
  accentSoft: 'rgba(56,189,248,0.12)',
  accentSoftStrong: 'rgba(56,189,248,0.22)',
  accentBorder: 'rgba(56,189,248,0.30)',
  accentBorderStrong: 'rgba(56,189,248,0.85)',
  ok: '#22c55e',
  warning: '#fbbf24',
  disabled: '#16283d',
  disabledText: '#7b8d9b',
  ideaAccent: '#a78bfa',
  ideaAccentDim: 'rgba(167,139,250,0.15)',
  ideaBorder: 'rgba(167,139,250,0.30)',
  scrim: 'rgba(0,0,0,0.55)',
};

export const PULSE_LIGHT: Readonly<Record<keyof ThemeColors, string>> = {
  bg: '#f4f8fb',
  surface: '#ffffff',
  card: '#eaf1f7',
  border: 'rgba(3,105,161,0.20)',
  accent: '#0369a1',
  accent2: '#075985',
  text: '#0b1d2e',
  muted: '#4a6275',
  onAccent: '#ffffff',
  userBubble: '#d3e8f6',
  userBorder: 'rgba(3,105,161,0.35)',
  donnaBubble: '#eaf1f7',
  donnaBorder: 'rgba(3,105,161,0.20)',
  accentSoft: 'rgba(3,105,161,0.10)',
  accentSoftStrong: 'rgba(3,105,161,0.18)',
  accentBorder: 'rgba(3,105,161,0.30)',
  accentBorderStrong: 'rgba(3,105,161,0.75)',
  ok: '#166534',
  warning: '#92400e',
  disabled: '#dbe5ec',
  disabledText: '#6b7e8c',
  ideaAccent: '#6d28d9',
  ideaAccentDim: 'rgba(109,40,217,0.10)',
  ideaBorder: 'rgba(109,40,217,0.30)',
  scrim: 'rgba(0,0,0,0.55)',
};

// ─── Dynamic Colors (Android 12+ / API 31+) ────────────────────────────────────────────
const sys = (name: string): ColorValue => PlatformColor(`@android:color/${name}`);

function dynamicColors(scheme: ColorSchemeName): ThemeColors {
  const base = scheme === 'dark' ? PULSE_DARK : PULSE_LIGHT;
  if (scheme === 'dark') {
    return {
      ...base,
      bg: sys('system_neutral1_900'),
      surface: sys('system_neutral1_900'),
      card: sys('system_neutral1_800'),
      border: sys('system_neutral2_700'),
      accent: sys('system_accent1_200'),
      accent2: sys('system_accent1_100'),
      text: sys('system_neutral1_100'),
      muted: sys('system_neutral2_300'),
      onAccent: sys('system_accent1_800'),
      userBubble: sys('system_accent1_700'),
      userBorder: sys('system_accent1_600'),
      donnaBubble: sys('system_neutral1_800'),
      donnaBorder: sys('system_neutral2_700'),
      accentSoft: sys('system_accent1_800'),
      accentSoftStrong: sys('system_accent1_700'),
      accentBorder: sys('system_accent1_600'),
      accentBorderStrong: sys('system_accent1_200'),
      disabled: sys('system_neutral1_700'),
      disabledText: sys('system_neutral2_400'),
    };
  }
  return {
    ...base,
    bg: sys('system_neutral1_10'),
    surface: sys('system_neutral1_50'),
    card: sys('system_neutral1_100'),
    border: sys('system_neutral2_200'),
    accent: sys('system_accent1_600'),
    accent2: sys('system_accent1_700'),
    text: sys('system_neutral1_900'),
    muted: sys('system_neutral2_700'),
    onAccent: sys('system_neutral1_0'),
    userBubble: sys('system_accent1_100'),
    userBorder: sys('system_accent1_300'),
    donnaBubble: sys('system_neutral1_100'),
    donnaBorder: sys('system_neutral2_200'),
    accentSoft: sys('system_accent1_100'),
    accentSoftStrong: sys('system_accent1_200'),
    accentBorder: sys('system_accent1_300'),
    accentBorderStrong: sys('system_accent1_600'),
    disabled: sys('system_neutral1_200'),
    disabledText: sys('system_neutral2_500'),
  };
}

/** Dynamic Colors gibt es nur auf Android ab 12 (API 31). */
export function supportsDynamicColors(): boolean {
  if (Platform.OS !== 'android') {
    return false;
  }
  const v = typeof Platform.Version === 'number' ? Platform.Version : parseInt(String(Platform.Version), 10);
  return Number.isFinite(v) && v >= 31;
}

/** Unbekanntes/fehlendes System-Schema (null) → dunkel, wie bisher. */
export function normalizeScheme(scheme: string | null | undefined): ColorSchemeName {
  return scheme === 'light' ? 'light' : 'dark';
}

const cache = new Map<string, Theme>();

/**
 * Liefert das (gecachte, referenzstabile) Theme. `dynamic` wird nur berücksichtigt, wenn das Gerät
 * es kann — ein erzwungenes `true` auf API < 31 fällt auf Pulse zurück.
 */
export function getTheme(
  scheme: string | null | undefined,
  dynamic: boolean = supportsDynamicColors(),
): Theme {
  const s = normalizeScheme(scheme);
  const useDynamic = dynamic && supportsDynamicColors();
  const key = `${s}:${useDynamic ? 'dyn' : 'pulse'}`;
  let theme = cache.get(key);
  if (!theme) {
    const colors = useDynamic ? dynamicColors(s) : s === 'dark' ? PULSE_DARK : PULSE_LIGHT;
    theme = {...colors, scheme: s, dynamic: useDynamic};
    cache.set(key, theme);
  }
  return theme;
}

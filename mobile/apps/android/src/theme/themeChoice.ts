/**
 * Darstellung Hell / Dunkel / System (#134 P1) — nur Logik, rein und ohne React-Native-Imports.
 *
 * Die gespeicherte Wahl liegt NATIV als Pref `theme_mode` in `donna_overlay_prefs` (Kotlin: ThemeChoice.kt, EINE
 * Quelle der Wahrheit); die App liest/schreibt sie ueber src/native/ttsEngine.ts (getThemeMode/setThemeMode).
 * Overlay (LiveBubbleService) und Sperrbildschirm-Activity lesen dieselbe Pref und wenden sie an (ThemeChoice.nightOverride).
 */
export type ColorSchemeName = 'light' | 'dark';
export type ThemeChoice = 'system' | 'light' | 'dark';

export const THEME_CHOICES: readonly ThemeChoice[] = ['system', 'light', 'dark'];
/** Identisch mit ThemeChoice.DEFAULT in ThemeChoice.kt. */
export const THEME_CHOICE_DEFAULT: ThemeChoice = 'system';

/** Unbekannt/fehlend (auch aelteres natives Modul ohne die Methode) -> "system". */
export function normalizeThemeChoice(raw: unknown): ThemeChoice {
  return raw === 'light' || raw === 'dark' || raw === 'system' ? raw : THEME_CHOICE_DEFAULT;
}

/** Unbekanntes/fehlendes System-Schema (null) -> dunkel, wie bisher. */
export function normalizeScheme(scheme: string | null | undefined): ColorSchemeName {
  return scheme === 'light' ? 'light' : 'dark';
}

/** Eine feste Wahl gewinnt, "system" folgt dem System-Schema. */
export function resolveScheme(choice: ThemeChoice, system: string | null | undefined): ColorSchemeName {
  if (choice === 'light' || choice === 'dark') {
    return choice;
  }
  return normalizeScheme(system);
}

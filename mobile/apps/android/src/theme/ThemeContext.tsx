/**
 * Theme-Zugriff für alle Screens: useTheme() + useThemedStyles().
 *
 * Ohne ThemeProvider arbeitet useTheme() trotzdem (liest das System-Schema selbst) — Komponenten sind
 * damit auch isoliert testbar. Der Provider im App-Root ist die einzige Stelle, die ein Schema erzwingen
 * kann (z. B. für Tests/Vorschau).
 */
import React, {createContext, useContext, useMemo} from 'react';
import {useColorScheme} from 'react-native';
import {getTheme, type ColorSchemeName, type Theme} from './palettes';

const ThemeContext = createContext<Theme | null>(null);

export interface ThemeProviderProps {
  children: React.ReactNode;
  /** Schema erzwingen (Tests/Vorschau); sonst folgt der Provider dem System. */
  forceScheme?: ColorSchemeName;
}

export function ThemeProvider({children, forceScheme}: ThemeProviderProps): React.JSX.Element {
  const system = useColorScheme();
  const theme = useMemo(() => getTheme(forceScheme ?? system), [forceScheme, system]);
  return <ThemeContext.Provider value={theme}>{children}</ThemeContext.Provider>;
}

export function useTheme(): Theme {
  const ctx = useContext(ThemeContext);
  const system = useColorScheme();
  return useMemo(() => ctx ?? getTheme(system), [ctx, system]);
}

/**
 * Baut Styles aus dem aktuellen Theme neu, sobald sich Schema/Dynamic Colors ändern.
 * `factory` MUSS eine stabile Referenz sein (Modul-Konstante), sonst wird bei jedem Render neu gebaut.
 *
 *   const makeStyles = (P: Theme) => StyleSheet.create({box: {backgroundColor: P.card}});
 *   const styles = useThemedStyles(makeStyles);
 */
export function useThemedStyles<T>(factory: (theme: Theme) => T): T {
  const theme = useTheme();
  return useMemo(() => factory(theme), [factory, theme]);
}

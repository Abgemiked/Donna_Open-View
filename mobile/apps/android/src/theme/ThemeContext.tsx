/**
 * Theme-Zugriff fuer alle Screens: useTheme() + useThemedStyles() + useThemeChoice().
 *
 * Ohne ThemeProvider arbeitet useTheme() trotzdem (liest das System-Schema selbst) — Komponenten sind
 * damit auch isoliert testbar. Der Provider im App-Root liest die gespeicherte Wahl (Hell/Dunkel/System, nativ
 * gespeichert, siehe themeChoice.ts) und ist die einzige Stelle, die ein Schema erzwingen kann.
 */
import React, {createContext, useCallback, useContext, useEffect, useMemo, useRef, useState} from 'react';
import {useColorScheme} from 'react-native';
import {getThemeMode, hasNativeThemeMode, setThemeMode} from '../native/ttsEngine';
import {getTheme, type ColorSchemeName, type Theme} from './palettes';
import {THEME_CHOICE_DEFAULT, resolveScheme, type ThemeChoice} from './themeChoice';

/** Hoechstwartezeit auf die gespeicherte Darstellungswahl, danach `ready` auch ohne Antwort. */
const READY_TIMEOUT_MS = 1_500;

const ThemeContext = createContext<Theme | null>(null);

export interface ThemeChoiceApi {
  choice: ThemeChoice;
  /**
   * true, sobald die gespeicherte Wahl geladen ist (oder es keine native Methode gibt). Das erste Rendern bleibt
   * synchron; die App haelt den Startbildschirm bis `ready` (utils/startGate.ts), damit nichts von hell auf dunkel
   * umspringt. Antwortet die native Abfrage nicht, wird `ready` nach 1,5 s trotzdem true (Wahl dann "system").
   */
  ready: boolean;
  /** Wirkt sofort und wird nativ gespeichert (Overlay liest sie ab P7). */
  setThemeChoice: (choice: ThemeChoice) => Promise<void>;
}
const ThemeChoiceContext = createContext<ThemeChoiceApi | null>(null);
const NO_PROVIDER: ThemeChoiceApi = {
  choice: THEME_CHOICE_DEFAULT,
  ready: true,
  setThemeChoice: async () => undefined,
};

export interface ThemeProviderProps {
  children: React.ReactNode;
  /** Schema erzwingen (Tests/Vorschau); sonst gilt die gespeicherte Wahl bzw. das System. */
  forceScheme?: ColorSchemeName;
}

export function ThemeProvider({children, forceScheme}: ThemeProviderProps): React.JSX.Element {
  const system = useColorScheme();
  const [choice, setChoice] = useState<ThemeChoice>(THEME_CHOICE_DEFAULT);
  // Ohne native Methode gibt es nichts zu laden -> sofort bereit (kein Warten in Tests/alten APKs).
  const [ready, setReady] = useState<boolean>(() => !hasNativeThemeMode());
  // Zaehler "letzte Anfrage gewinnt": jede Nutzerwahl erhoeht ihn; Antworten mit altem Stand werden verworfen.
  // Eine Nutzerwahl gewinnt immer gegen das anfaengliche Laden (userChose).
  const requestId = useRef(0);
  const userChose = useRef(false);

  useEffect(() => {
    let alive = true;
    // Loest die native Abfrage nie auf, darf der Start nicht haengen: nach READY_TIMEOUT_MS gilt "system".
    const timer = setTimeout(() => {
      if (alive) {
        setReady(true);
      }
    }, READY_TIMEOUT_MS);
    void getThemeMode().then(saved => {
      if (alive) {
        if (!userChose.current) {
          setChoice(saved);
        }
        setReady(true);
      }
    });
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, []);

  const setThemeChoice = useCallback(async (next: ThemeChoice) => {
    userChose.current = true;
    const id = ++requestId.current;
    setChoice(next); // sofort sichtbar; die native Pref ist die gespeicherte Quelle
    const applied = await setThemeMode(next);
    if (id === requestId.current) {
      setChoice(applied);
    }
  }, []);

  const theme = useMemo(
    () => getTheme(forceScheme ?? resolveScheme(choice, system)),
    [forceScheme, choice, system],
  );
  const api = useMemo(() => ({choice, ready, setThemeChoice}), [choice, ready, setThemeChoice]);
  return (
    <ThemeChoiceContext.Provider value={api}>
      <ThemeContext.Provider value={theme}>{children}</ThemeContext.Provider>
    </ThemeChoiceContext.Provider>
  );
}

export function useTheme(): Theme {
  const ctx = useContext(ThemeContext);
  const system = useColorScheme();
  return useMemo(() => ctx ?? getTheme(system), [ctx, system]);
}

/** Gespeicherte Darstellungs-Wahl und ihr Setter (ohne Provider: "system", Setter ohne Wirkung). */
export function useThemeChoice(): ThemeChoiceApi {
  return useContext(ThemeChoiceContext) ?? NO_PROVIDER;
}

/**
 * Baut Styles aus dem aktuellen Theme neu, sobald sich das Schema aendert.
 * `factory` MUSS eine stabile Referenz sein (Modul-Konstante), sonst wird bei jedem Render neu gebaut.
 *
 *   const makeStyles = (P: Theme) => StyleSheet.create({box: {backgroundColor: P.card}});
 *   const styles = useThemedStyles(makeStyles);
 */
export function useThemedStyles<T>(factory: (theme: Theme) => T): T {
  const theme = useTheme();
  return useMemo(() => factory(theme), [factory, theme]);
}

/**
 * Design-Token der Donna-App (#122 Paket D). Referenz ist das Overlay (Kotlin): die Werte stehen bewusst
 * doppelt - hier und in `android/app/src/main/res/values/dimens.xml` - und `designTokens.test.ts` vergleicht
 * beide Quellen. Farben stehen in `palettes.ts` (eigener Paritaetstest).
 *
 * Einheiten: dp bzw. sp (React Native rechnet Zahlen als dp, `fontSize` skaliert mit der Systemschrift).
 */

/** Radien. `input` 16 / `row` 18 / `card` 28 (#134 P1, neues Design). `pill` = voll gerundete Tasten/Felder (bei 48 dp Hoehe ein Halbkreis). */
export const RADIUS = {sm: 8, md: 14, lg: 22, pill: 28, input: 16, row: 18, card: 28} as const;

/** Abstaende (4-dp-Raster). */
export const SPACE = {xs: 4, sm: 8, md: 12, lg: 16, xl: 24, gutter: 20, xxl: 32} as const;

/** Schriftgroessen. `label` (13) ist die Groesse der Tasten-/Statustexte im Overlay. */
export const FONT = {caption: 12, label: 13, body: 15, title: 18, display: 26} as const;

/** Bewegung in ms. `orbCycle` = Grundtakt des Orbs (DonnaOrbView). */
export const MOTION = {fast: 150, base: 300, orbCycle: 1000} as const;

/** Kleinstes Tippziel in dp. */
export const TOUCH_MIN = 48;

/**
 * Schriften (#134 P1): statische Schnitte, je Gewicht eine Datei. Unter Android waehlt React Native die Datei ueber den
 * Familiennamen (`assets/fonts/<name>.ttf`), deshalb steht das Gewicht im Namen und `fontWeight` entfaellt.
 * Kotlin-Gegenstueck: `DonnaFonts.kt` (`res/font/<name>.ttf`); `fonts.test.ts` vergleicht Token, Dateien und Kotlin.
 * Bricolage Grotesque = Ueberschriften/Zustandswort, Nunito Sans = Text. Beide SIL OFL (Lizenz in assets/fonts).
 * In P1 nur bereitgestellt, noch nicht flaechig eingesetzt.
 */
export const FONT_FAMILY = {
  display: {bold: 'bricolage_grotesque_bold', extrabold: 'bricolage_grotesque_extrabold'},
  sans: {regular: 'nunito_sans_regular', semibold: 'nunito_sans_semibold', bold: 'nunito_sans_bold'},
} as const;

/**
 * #134 P4/P5 (Chat-Strang): grosse Schriftgroessen der Leer- und Gespraechsansicht (Entwurf B).
 * `hero` = Begruessung, `speech` = Mitschrift im Gespraech, `lead` = Donnas Antworttext im Chat.
 */
export const FONT_LARGE = {lead: 17, speech: 30, hero: 38} as const;

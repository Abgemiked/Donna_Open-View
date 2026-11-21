/**
 * Gemeinsame Styles der Bestätigungs-/Auswahl-Karten (PendingActionCard, Kontakt-Picker, Navigations-Karten).
 * Vorher feste iOS-Grautöne (#1c1c1e) — jetzt aus dem zentralen Theme (Dark/Light/Dynamic Colors).
 * #122 Paket D: Radien, Abstände und Schriftgrößen aus den Token (theme/tokens.ts) — gleiche Werte wie die Karten im Overlay.
 */
import {StyleSheet} from 'react-native';
import {FONT, RADIUS, SPACE, TOUCH_MIN} from '../theme/tokens';
import type {Theme} from '../theme';

export const makeCardStyles = (P: Theme) =>
  StyleSheet.create({
    cardOuter: {
      backgroundColor: P.card,
      borderRadius: RADIUS.md,
      borderWidth: 1,
      borderColor: P.border,
      marginHorizontal: SPACE.sm,
      marginVertical: SPACE.sm,
      overflow: 'hidden',
    },
    cardInner: {padding: SPACE.lg, paddingBottom: SPACE.md},
    divider: {height: 1, backgroundColor: P.border, marginVertical: SPACE.md},
    fieldLabel: {color: P.muted, fontSize: FONT.label, fontWeight: '400', marginBottom: SPACE.xs},
    fieldValue: {color: P.text, fontSize: FONT.title, fontWeight: '500', lineHeight: 24},
    warnText: {color: P.warning, fontSize: FONT.caption, marginTop: SPACE.md, fontStyle: 'italic'},
    btnRow: {
      flexDirection: 'row',
      backgroundColor: P.surface,
      borderTopWidth: 1,
      borderTopColor: P.border,
    },
    // Touchziel ≥ 48dp
    btn: {flex: 1, minHeight: TOUCH_MIN, paddingVertical: SPACE.md, alignItems: 'center', justifyContent: 'center'},
    btnDisabled: {opacity: 0.4},
    btnText: {color: P.text, fontSize: FONT.body, fontWeight: '400'},
    btnTextPrimary: {color: P.accent, fontSize: FONT.body, fontWeight: '600'},
    btnDivider: {width: 1, backgroundColor: P.border},
  });

// ─── Info-Karten (Wetter / Maps / Kalender) — gemeinsames One-UI-/Gemini-Designsystem ───────────
// BEWUSST getrennt von makeCardStyles: Das ist die kompakte Bestätigungs-/Auswahl-Karte
// (PendingActionCard, Kontakt-Picker, Navigations-Karten) und bleibt unverändert. Die Info-Karten sind reine
// Anzeige-/Link-Karten und teilen sich dieses System: großzügiger Radius, weiche Elevation, klare
// Typo-Stufen, getönte Icon-Badges. Alle Farben kommen aus dem Theme (Light/Dark/Dynamic Colors).

/** Layout-Konstanten des Info-Karten-Systems (dp). */
export const INFO_CARD = {
  /** Außenradius der Karte (wie die Karten im Overlay: 14 dp) */
  radius: RADIUS.md,
  /** Radius innerer Kacheln (Stunden-Slot, Statistik-Panel) */
  innerRadius: RADIUS.sm,
  /** Radius von Pills/Chips */
  pillRadius: RADIUS.md,
  padding: SPACE.lg,
  marginBottom: SPACE.md,
  /** Android-Schatten-Höhe */
  elevation: 3,
  /** Icon-Badge (Kreis) */
  badge: 40,
} as const;

/** Schattenfarbe: neutrales Schwarz (kein Palettenton) — Elevation wirkt in beiden Modi, im Dark nur dezent. */
const SHADOW_COLOR = '#000000';

export const makeInfoCardStyles = (P: Theme) =>
  StyleSheet.create({
    /** Kartenfläche: hell = Surface (hebt sich vom Seitengrund ab), dunkel = Card (heller als der Grund). */
    surface: {
      backgroundColor: P.scheme === 'light' ? P.surface : P.card,
      borderRadius: INFO_CARD.radius,
      borderWidth: StyleSheet.hairlineWidth,
      borderColor: P.border,
      padding: INFO_CARD.padding,
      marginBottom: INFO_CARD.marginBottom,
      overflow: 'hidden',
      elevation: INFO_CARD.elevation,
      shadowColor: SHADOW_COLOR,
      shadowOpacity: 0.12,
      shadowRadius: 12,
      shadowOffset: {width: 0, height: 4},
    },
    /** Kopfzeile: Icon-Badge + Beschriftung */
    header: {flexDirection: 'row', alignItems: 'center', gap: SPACE.md, marginBottom: SPACE.lg},
    badge: {
      width: INFO_CARD.badge,
      height: INFO_CARD.badge,
      borderRadius: INFO_CARD.badge / 2,
      backgroundColor: P.accentSoft,
      alignItems: 'center',
      justifyContent: 'center',
    },
    /** Typo-Stufen: eyebrow (Kontext) < caption < body < title */
    eyebrow: {color: P.muted, fontSize: FONT.label, fontWeight: '500', letterSpacing: 0.2},
    title: {color: P.text, fontSize: FONT.title, fontWeight: '600', lineHeight: 24},
    body: {color: P.text, fontSize: FONT.body, fontWeight: '500', lineHeight: 21},
    caption: {color: P.muted, fontSize: FONT.label, lineHeight: 18},
    divider: {height: StyleSheet.hairlineWidth, backgroundColor: P.border, marginVertical: SPACE.lg},
    /** Pill (Hoch/Tief, Zeit) */
    pill: {
      paddingHorizontal: SPACE.md,
      paddingVertical: SPACE.xs,
      borderRadius: INFO_CARD.pillRadius,
      overflow: 'hidden',
      backgroundColor: P.accentSoft,
    },
  });

export const makePickerStyles = (P: Theme) =>
  StyleSheet.create({
    row: {
      minHeight: TOUCH_MIN,
      paddingVertical: SPACE.md,
      paddingHorizontal: SPACE.sm,
      borderBottomWidth: 1,
      borderBottomColor: P.border,
    },
    name: {color: P.text, fontSize: FONT.body, fontWeight: '500'},
    number: {color: P.muted, fontSize: FONT.label, marginTop: 2},
  });

export const makeFallbackStyles = (P: Theme) =>
  StyleSheet.create({
    input: {
      backgroundColor: P.surface,
      borderRadius: RADIUS.sm,
      borderWidth: 1,
      borderColor: P.border,
      paddingHorizontal: SPACE.lg,
      paddingVertical: SPACE.md,
      color: P.text,
      fontSize: FONT.body,
      marginTop: SPACE.xs,
    },
  });

/**
 * Gemeinsame Styles der Bestätigungs-/Auswahl-Karten (PendingActionCard, Kontakt-Picker, Navigations-Karten).
 * Vorher feste iOS-Grautöne (#1c1c1e) — jetzt aus dem zentralen Theme (Dark/Light/Dynamic Colors).
 */
import {StyleSheet} from 'react-native';
import type {Theme} from '../theme';

export const makeCardStyles = (P: Theme) =>
  StyleSheet.create({
    cardOuter: {
      backgroundColor: P.card,
      borderRadius: 16,
      borderWidth: 1,
      borderColor: P.border,
      marginHorizontal: 8,
      marginVertical: 6,
      overflow: 'hidden',
    },
    cardInner: {padding: 18, paddingBottom: 14},
    divider: {height: 1, backgroundColor: P.border, marginVertical: 12},
    fieldLabel: {color: P.muted, fontSize: 13, fontWeight: '400', marginBottom: 4},
    fieldValue: {color: P.text, fontSize: 22, fontWeight: '500', lineHeight: 28},
    warnText: {color: P.warning, fontSize: 12, marginTop: 12, fontStyle: 'italic'},
    btnRow: {
      flexDirection: 'row',
      backgroundColor: P.surface,
      borderTopWidth: 1,
      borderTopColor: P.border,
    },
    // Touchziel ≥ 48dp
    btn: {flex: 1, minHeight: 48, paddingVertical: 14, alignItems: 'center', justifyContent: 'center'},
    btnDisabled: {opacity: 0.4},
    btnText: {color: P.text, fontSize: 15, fontWeight: '400'},
    btnTextPrimary: {color: P.accent, fontSize: 15, fontWeight: '600'},
    btnDivider: {width: 1, backgroundColor: P.border},
  });

// ─── Info-Karten (Wetter / Maps / Kalender) — gemeinsames One-UI-/Gemini-Designsystem ───────────
// BEWUSST getrennt von makeCardStyles: Das ist die kompakte Bestätigungs-/Auswahl-Karte
// (PendingActionCard, Kontakt-Picker, Navigations-Karten) und bleibt unverändert. Die Info-Karten sind reine
// Anzeige-/Link-Karten und teilen sich dieses System: großzügiger Radius, weiche Elevation, klare
// Typo-Stufen, getönte Icon-Badges. Alle Farben kommen aus dem Theme (Light/Dark/Dynamic Colors).

/** Layout-Konstanten des Info-Karten-Systems (dp). */
export const INFO_CARD = {
  /** Außenradius der Karte (One UI: ~28dp) */
  radius: 28,
  /** Radius innerer Kacheln (Stunden-Slot, Statistik-Panel) */
  innerRadius: 20,
  /** Radius von Pills/Chips */
  pillRadius: 14,
  padding: 20,
  marginBottom: 12,
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
    header: {flexDirection: 'row', alignItems: 'center', gap: 12, marginBottom: 16},
    badge: {
      width: INFO_CARD.badge,
      height: INFO_CARD.badge,
      borderRadius: INFO_CARD.badge / 2,
      backgroundColor: P.accentSoft,
      alignItems: 'center',
      justifyContent: 'center',
    },
    /** Typo-Stufen: eyebrow (Kontext) < caption < body < title */
    eyebrow: {color: P.muted, fontSize: 13, fontWeight: '500', letterSpacing: 0.2},
    title: {color: P.text, fontSize: 17, fontWeight: '600', lineHeight: 24},
    body: {color: P.text, fontSize: 15, fontWeight: '500', lineHeight: 21},
    caption: {color: P.muted, fontSize: 13, lineHeight: 18},
    divider: {height: StyleSheet.hairlineWidth, backgroundColor: P.border, marginVertical: 16},
    /** Pill (Hoch/Tief, Zeit) */
    pill: {
      paddingHorizontal: 12,
      paddingVertical: 6,
      borderRadius: INFO_CARD.pillRadius,
      overflow: 'hidden',
      backgroundColor: P.accentSoft,
    },
  });

export const makePickerStyles = (P: Theme) =>
  StyleSheet.create({
    row: {
      minHeight: 48,
      paddingVertical: 12,
      paddingHorizontal: 8,
      borderBottomWidth: 1,
      borderBottomColor: P.border,
    },
    name: {color: P.text, fontSize: 16, fontWeight: '500'},
    number: {color: P.muted, fontSize: 13, marginTop: 2},
  });

export const makeFallbackStyles = (P: Theme) =>
  StyleSheet.create({
    input: {
      backgroundColor: P.surface,
      borderRadius: 10,
      borderWidth: 1,
      borderColor: P.border,
      paddingHorizontal: 14,
      paddingVertical: 10,
      color: P.text,
      fontSize: 15,
      marginTop: 4,
    },
  });

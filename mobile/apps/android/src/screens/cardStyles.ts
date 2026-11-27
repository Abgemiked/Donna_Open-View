/**
 * Gemeinsame Styles der Karten im Chat (#134 P8, neues Design).
 * - `makeCardStyles`: Bestaetigungs-/Auswahl-Karten (PendingActionCard, Kontakt-Picker, Navigations-, Ideen-Karten):
 *   Rundung `card`, Innenabstand `gutter`, Felder als Label/Wert-Paare mit Haarlinien, darunter Aktions-Pillen
 *   (eine gefuellte Hauptaktion in Mint `action`, sonst Umriss).
 * - `makeInfoCardStyles`: Anzeige-Karten (Wetter, Karte, Termine): gleiche Rundung und Flaeche, ohne Schatten.
 * Alle Werte aus Token (theme/tokens.ts) und Theme (theme/palettes.ts), beide Farbwelten.
 */
import {StyleSheet} from 'react-native';
import {FONT, FONT_FAMILY, RADIUS, SPACE, TOUCH_MIN} from '../theme/tokens';
import type {Theme} from '../theme';

export const makeCardStyles = (P: Theme) =>
  StyleSheet.create({
    cardOuter: {
      backgroundColor: P.card,
      borderRadius: RADIUS.card,
      borderWidth: 1,
      borderColor: P.border,
      marginHorizontal: SPACE.sm,
      marginVertical: SPACE.sm,
      overflow: 'hidden',
    },
    cardInner: {padding: SPACE.gutter, paddingBottom: SPACE.md},
    divider: {height: StyleSheet.hairlineWidth, backgroundColor: P.border, marginVertical: SPACE.md},
    fieldLabel: {color: P.muted, fontFamily: FONT_FAMILY.sans.semibold, fontSize: FONT.label, marginBottom: SPACE.xs},
    fieldValue: {color: P.text, fontFamily: FONT_FAMILY.sans.semibold, fontSize: FONT.title, lineHeight: 26},
    pickList: {maxHeight: 240},
    fieldGap: {marginBottom: SPACE.sm},
    warnText: {color: P.warning, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.label, marginTop: SPACE.md},
    btnRow: {
      flexDirection: 'row',
      flexWrap: 'wrap',
      gap: SPACE.sm,
      paddingHorizontal: SPACE.gutter,
      paddingBottom: SPACE.gutter,
    },
    // Aktions-Pille, Tippziel >= 48
    btn: {
      flexGrow: 1,
      minHeight: TOUCH_MIN,
      minWidth: TOUCH_MIN,
      paddingHorizontal: SPACE.lg,
      borderRadius: RADIUS.pill,
      borderWidth: 1,
      borderColor: P.controlBorder,
      alignItems: 'center',
      justifyContent: 'center',
    },
    // die EINE Hauptaktion der Karte: gefuellt in Mint
    btnPrimary: {backgroundColor: P.action, borderColor: P.action},
    // gesperrt: ungefuellt mit Haarlinie, Schrift `muted` (>= 4,5:1 auf der Karte, auch hell; `disabledText` erreicht hell nur ~4,2:1), kein Dimmen: der Text traegt eine Information
    btnDisabled: {borderColor: P.border},
    btnTextDisabled: {color: P.muted, fontFamily: FONT_FAMILY.sans.semibold, fontSize: FONT.body},
    btnText: {color: P.text, fontFamily: FONT_FAMILY.sans.semibold, fontSize: FONT.body},
    btnTextPrimary: {color: P.onAction, fontFamily: FONT_FAMILY.sans.bold, fontSize: FONT.body},
  });

// ─── Info-Karten (Wetter / Maps / Kalender) ───────────────────────────────────
// BEWUSST getrennt von makeCardStyles: reine Anzeige-/Link-Karten. Gleiche Rundung wie alle Karten, keine Schatten
// (Entwurf B: Flaeche + Haarlinie), klare Typo-Stufen, getoente Icon-Badges. Alle Farben aus dem Theme.

/** Layout-Konstanten des Info-Karten-Systems (dp). */
export const INFO_CARD = {
  /** Aussenradius der Karte */
  radius: RADIUS.card,
  /** Radius innerer Kacheln (Stunden-Slot, Zeilen-Block) */
  innerRadius: RADIUS.lg,
  /** Radius von Pillen */
  pillRadius: RADIUS.pill,
  padding: SPACE.gutter,
  marginBottom: SPACE.md,
  /** Icon-Badge (Kreis) */
  badge: 44,
} as const;

export const makeInfoCardStyles = (P: Theme) =>
  StyleSheet.create({
    /** Kartenflaeche: hell = Surface (hebt sich vom Seitengrund ab), dunkel = Card (heller als der Grund). */
    surface: {
      backgroundColor: P.scheme === 'light' ? P.surface : P.card,
      borderRadius: INFO_CARD.radius,
      borderWidth: 1,
      borderColor: P.border,
      padding: INFO_CARD.padding,
      marginBottom: INFO_CARD.marginBottom,
      overflow: 'hidden',
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
    eyebrow: {color: P.muted, fontFamily: FONT_FAMILY.sans.semibold, fontSize: FONT.label},
    title: {color: P.text, fontFamily: FONT_FAMILY.sans.semibold, fontSize: FONT.title, lineHeight: 26},
    body: {color: P.text, fontFamily: FONT_FAMILY.sans.semibold, fontSize: FONT.body, lineHeight: 22},
    caption: {color: P.muted, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.label, lineHeight: 18},
    divider: {height: StyleSheet.hairlineWidth, backgroundColor: P.border, marginVertical: SPACE.lg},
    /** Pille (Hoch/Tief, Zeit) */
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
      borderBottomWidth: StyleSheet.hairlineWidth,
      borderBottomColor: P.border,
    },
    name: {color: P.text, fontFamily: FONT_FAMILY.sans.semibold, fontSize: FONT.body},
    number: {color: P.muted, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.label, marginTop: SPACE.xs},
  });

export const makeFallbackStyles = (P: Theme) =>
  StyleSheet.create({
    input: {
      backgroundColor: P.surface,
      borderRadius: RADIUS.row,
      borderWidth: 1,
      borderColor: P.controlBorder,
      paddingHorizontal: SPACE.lg,
      paddingVertical: SPACE.md,
      color: P.text,
      fontFamily: FONT_FAMILY.sans.regular,
      fontSize: FONT.body,
      marginTop: SPACE.xs,
    },
  });

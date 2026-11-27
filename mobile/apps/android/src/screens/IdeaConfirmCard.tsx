/**
 * IdeaConfirmCard — DONNA-115 Ideen-Bestätigungs-Karte
 *
 * Zeigt eine strukturierte Vorschau einer erkannten Idee mit:
 *  - Titel
 *  - Kurzbeschreibung
 *  - Tags als Chips
 *  - Pillen: "Speichern" (mit Idee-Icon, Hauptaktion) / "Nein"
 *
 * Styling: gleiche Karten-Styles wie PendingActionCard (#134 P8). Aktionen als Pillen, Tippziel >= 48,
 * die Hauptaktion (Speichern) gefüllt in Mint.
 */
import React from 'react';
import {View, Text, TouchableOpacity, StyleSheet, ScrollView} from 'react-native';
import type {IdeaConfirmPayload, IdeaUpdatePayload} from '@donna/shared';
import {Icon} from '../components/Icon';
import {FONT, FONT_FAMILY, RADIUS, SPACE, useTheme, useThemedStyles} from '../theme';
import type {Theme} from '../theme';
import {makeCardStyles} from './cardStyles';

// Ideen-Karten teilen die Karten-Styles; nur Rand, Kopfzeile und Tags tragen den Ideen-Ton (ideaAccent).

// ─── IdeaConfirmCard ──────────────────────────────────────────────────────────

interface IdeaConfirmCardProps {
  idea: IdeaConfirmPayload;
  onConfirm: () => void;
  onReject: () => void;
}

export function IdeaConfirmCard({
  idea,
  onConfirm,
  onReject,
}: IdeaConfirmCardProps): React.JSX.Element {
  const styles = useThemedStyles(makeStyles);
  const theme = useTheme();
  return (
    <View style={styles.cardOuter}>
      {/* Header-Streifen */}
      <View style={styles.headerRow}>
        <Icon name="idea" size={18} color={theme.ideaAccent as string} />
        <Text style={styles.headerLabel}>Idee erkannt — speichern?</Text>
      </View>

      <View style={styles.cardInner}>
        {/* Titel */}
        <Text style={styles.fieldLabel}>Titel</Text>
        <Text style={styles.fieldValue}>{idea.title}</Text>

        {/* Beschreibung */}
        {idea.description ? (
          <>
            <View style={styles.divider} />
            <Text style={styles.fieldLabel}>Beschreibung</Text>
            <Text style={styles.descText}>{idea.description}</Text>
          </>
        ) : null}

        {/* Tags als Chips */}
        {idea.tags && idea.tags.length > 0 ? (
          <>
            <View style={styles.divider} />
            <Text style={styles.fieldLabel}>Tags</Text>
            <ScrollView
              horizontal
              showsHorizontalScrollIndicator={false}
              style={styles.tagRow}
              contentContainerStyle={styles.tagRowContent}>
              {idea.tags.map((tag, i) => (
                <View key={`${tag}-${i}`} style={styles.tagChip}>
                  <Text style={styles.tagText}>#{tag}</Text>
                </View>
              ))}
            </ScrollView>
          </>
        ) : null}
      </View>

      {/* Buttons */}
      <View style={styles.btnRow}>
        <TouchableOpacity
          onPress={onConfirm}
          style={[styles.btn, styles.btnPrimary]}
          accessibilityLabel="Idee speichern"
          accessibilityRole="button">
          <View style={styles.btnContent}>
            <Icon name="idea" size={18} color={theme.onAction as string} />
            <Text style={styles.btnPrimaryText}>Speichern</Text>
          </View>
        </TouchableOpacity>
        <TouchableOpacity
          onPress={onReject}
          style={styles.btn}
          accessibilityLabel="Idee nicht speichern"
          accessibilityRole="button">
          <Text style={styles.btnSecondaryText}>Nein</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

// ─── IdeaUpdateCard ────────────────────────────────────────────────────────────

interface IdeaUpdateCardProps {
  idea: IdeaUpdatePayload;
  onConfirm: () => void;
  onReject: () => void;
}

export function IdeaUpdateCard({
  idea,
  onConfirm,
  onReject,
}: IdeaUpdateCardProps): React.JSX.Element {
  const styles = useThemedStyles(makeStyles);
  const theme = useTheme();
  return (
    <View style={styles.cardOuter}>
      <View style={styles.headerRow}>
        <Icon name="link" size={18} color={theme.ideaAccent as string} />
        <Text style={styles.headerLabel}>Ergänzung zu bestehender Idee?</Text>
      </View>

      <View style={styles.cardInner}>
        <Text style={styles.fieldLabel}>Bestehende Idee</Text>
        <Text style={styles.fieldValue}>{idea.title}</Text>
        <View style={styles.divider} />
        <Text style={styles.hintText}>
          Gehört deine aktuelle Eingabe zu dieser Idee? Wenn ja, wird sie als Update angehängt.
        </Text>
      </View>

      <View style={styles.btnRow}>
        <TouchableOpacity
          onPress={onConfirm}
          style={[styles.btn, styles.btnPrimary]}
          accessibilityLabel="Ja, als Ergänzung anhängen"
          accessibilityRole="button">
          <Text style={styles.btnPrimaryText}>Ja, gehört dazu</Text>
        </TouchableOpacity>
        <TouchableOpacity
          onPress={onReject}
          style={styles.btn}
          accessibilityLabel="Nein, separate Idee"
          accessibilityRole="button">
          <Text style={styles.btnSecondaryText}>Nein</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────
// Basis = gemeinsame Karten-Styles (cardStyles.makeCardStyles); hier nur Ideen-Besonderheiten (Kopfzeile, Tags).

const makeStyles = (P: Theme) => {
  const C = makeCardStyles(P);
  return StyleSheet.create({
    cardOuter: {...C.cardOuter, borderColor: P.ideaBorder},
    headerRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: SPACE.sm,
      paddingHorizontal: SPACE.gutter,
      paddingTop: SPACE.lg,
    },
    headerLabel: {color: P.ideaAccent, fontFamily: FONT_FAMILY.sans.bold, fontSize: FONT.label},
    cardInner: C.cardInner,
    divider: C.divider,
    fieldLabel: C.fieldLabel,
    fieldValue: C.fieldValue,
    descText: {color: P.text, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.body, lineHeight: 22},
    hintText: {color: P.muted, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.label, lineHeight: 19},
    tagRow: {flexGrow: 0},
    tagRowContent: {flexDirection: 'row', gap: SPACE.sm, paddingTop: SPACE.xs},
    tagChip: {
      backgroundColor: P.ideaAccentDim,
      borderRadius: RADIUS.pill,
      paddingHorizontal: SPACE.md,
      paddingVertical: SPACE.xs,
      borderWidth: 1,
      borderColor: P.ideaBorder,
    },
    tagText: {color: P.ideaAccent, fontFamily: FONT_FAMILY.sans.semibold, fontSize: FONT.caption},
    btnRow: C.btnRow,
    btn: C.btn,
    btnPrimary: C.btnPrimary,
    btnContent: {flexDirection: 'row', alignItems: 'center', justifyContent: 'center', columnGap: SPACE.sm},
    btnPrimaryText: C.btnTextPrimary,
    btnSecondaryText: C.btnText,
  });
};

/**
 * IdeaConfirmCard — DONNA-115 Ideen-Bestätigungs-Karte
 *
 * Zeigt eine strukturierte Vorschau einer erkannten Idee mit:
 *  - Titel
 *  - Kurzbeschreibung
 *  - Tags als Chips
 *  - Buttons: "💡 Speichern" (primary) / "Nein" (secondary)
 *
 * Styling: konsistent mit PendingActionCard (dunkles Theme, Donna-Akzent).
 * Buttons: groß + gut erreichbar mit einem Daumen (ADHS-freundlich).
 */
import React from 'react';
import {View, Text, TouchableOpacity, StyleSheet, ScrollView} from 'react-native';
import type {IdeaConfirmPayload, IdeaUpdatePayload} from '@donna/shared';
import {useThemedStyles} from '../theme';
import type {Theme} from '../theme';

// Farben aus der zentralen Theme-Quelle (src/theme); Ideen-Karten behalten ihren eigenen Violett-Ton.

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
  return (
    <View style={styles.cardOuter}>
      {/* Header-Streifen */}
      <View style={styles.headerRow}>
        <Text style={styles.headerIcon}>💡</Text>
        <Text style={styles.headerLabel}>Idee erkannt — speichern?</Text>
      </View>

      <View style={styles.cardInner}>
        {/* Titel */}
        <Text style={styles.fieldLabel}>Titel</Text>
        <Text style={styles.fieldValue} numberOfLines={2}>{idea.title}</Text>

        {/* Beschreibung */}
        {idea.description ? (
          <>
            <View style={styles.divider} />
            <Text style={styles.fieldLabel}>Beschreibung</Text>
            <Text style={styles.descText} numberOfLines={4}>{idea.description}</Text>
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
          <Text style={styles.btnPrimaryText}>💡 Speichern</Text>
        </TouchableOpacity>
        <View style={styles.btnDivider} />
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
  return (
    <View style={styles.cardOuter}>
      <View style={styles.headerRow}>
        <Text style={styles.headerIcon}>🔗</Text>
        <Text style={styles.headerLabel}>Ergänzung zu bestehender Idee?</Text>
      </View>

      <View style={styles.cardInner}>
        <Text style={styles.fieldLabel}>Bestehende Idee</Text>
        <Text style={styles.fieldValue} numberOfLines={2}>{idea.title}</Text>
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
        <View style={styles.btnDivider} />
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

const makeStyles = (P: Theme) => StyleSheet.create({
  cardOuter: {
    backgroundColor: P.card,
    borderRadius: 16,
    marginHorizontal: 8,
    marginVertical: 6,
    overflow: 'hidden',
    borderWidth: 1,
    borderColor: P.ideaBorder,
  },
  headerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 18,
    paddingVertical: 10,
    backgroundColor: P.ideaAccentDim,
    borderBottomWidth: 1,
    borderBottomColor: P.ideaBorder,
    gap: 8,
  },
  headerIcon: {
    fontSize: 18,
  },
  headerLabel: {
    color: P.ideaAccent,
    fontSize: 13,
    fontWeight: '600',
    letterSpacing: 0.3,
  },
  cardInner: {
    padding: 18,
    paddingBottom: 14,
  },
  divider: {
    height: 1,
    backgroundColor: P.border,
    marginVertical: 12,
  },
  fieldLabel: {
    color: P.muted,
    fontSize: 12,
    fontWeight: '400',
    marginBottom: 4,
    textTransform: 'uppercase',
    letterSpacing: 0.5,
  },
  fieldValue: {
    color: P.text,
    fontSize: 20,
    fontWeight: '500',
    lineHeight: 26,
  },
  descText: {
    color: P.text,
    fontSize: 14,
    lineHeight: 20,
  },
  hintText: {
    color: P.muted,
    fontSize: 13,
    lineHeight: 19,
    fontStyle: 'italic',
  },
  tagRow: {
    flexGrow: 0,
  },
  tagRowContent: {
    flexDirection: 'row',
    gap: 8,
    paddingTop: 4,
  },
  tagChip: {
    backgroundColor: P.ideaAccentDim,
    borderRadius: 20,
    paddingHorizontal: 12,
    paddingVertical: 5,
    borderWidth: 1,
    borderColor: P.ideaBorder,
  },
  tagText: {
    color: P.ideaAccent,
    fontSize: 12,
    fontWeight: '500',
  },
  btnRow: {
    flexDirection: 'row',
    backgroundColor: P.surface,
    borderTopWidth: 1,
    borderTopColor: P.border,
  },
  btn: {
    flex: 1,
    paddingVertical: 16,
    alignItems: 'center',
    justifyContent: 'center',
  },
  btnPrimary: {
    backgroundColor: P.ideaAccentDim,
  },
  btnPrimaryText: {
    color: P.ideaAccent,
    fontSize: 15,
    fontWeight: '600',
  },
  btnSecondaryText: {
    color: P.muted,
    fontSize: 15,
    fontWeight: '400',
  },
  btnDivider: {
    width: 1,
    backgroundColor: P.border,
  },
});

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

// ─── Theme-Tokens (identisch mit ChatScreen P-Objekt) ────────────────────────
const P = {
  bg: '#03090f',
  surface: '#080f1a',
  card: '#0d1626',
  border: 'rgba(56,189,248,0.12)',
  accent: '#38bdf8',
  accent2: '#7dd3fc',
  text: '#e0f2fe',
  muted: 'rgba(224,242,254,0.45)',
  cardOuter: '#1c1c1e',
  cardInner: '#161618',
  // Donna-Lila für Ideen-Karten (unterscheidbar von blauen Action-Karten)
  ideaAccent: '#a78bfa',    // violet-400
  ideaAccentDim: 'rgba(167,139,250,0.15)',
  ideaBorder: 'rgba(167,139,250,0.30)',
};

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

const styles = StyleSheet.create({
  cardOuter: {
    backgroundColor: P.cardOuter,
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
    backgroundColor: 'rgba(255,255,255,0.08)',
    marginVertical: 12,
  },
  fieldLabel: {
    color: 'rgba(255,255,255,0.55)',
    fontSize: 12,
    fontWeight: '400',
    marginBottom: 4,
    textTransform: 'uppercase',
    letterSpacing: 0.5,
  },
  fieldValue: {
    color: '#ffffff',
    fontSize: 20,
    fontWeight: '500',
    lineHeight: 26,
  },
  descText: {
    color: 'rgba(224,242,254,0.80)',
    fontSize: 14,
    lineHeight: 20,
  },
  hintText: {
    color: 'rgba(224,242,254,0.60)',
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
    backgroundColor: P.cardInner,
    borderTopWidth: 1,
    borderTopColor: 'rgba(255,255,255,0.06)',
  },
  btn: {
    flex: 1,
    paddingVertical: 16,
    alignItems: 'center',
    justifyContent: 'center',
  },
  btnPrimary: {
    backgroundColor: 'rgba(167,139,250,0.12)',
  },
  btnPrimaryText: {
    color: P.ideaAccent,
    fontSize: 15,
    fontWeight: '600',
  },
  btnSecondaryText: {
    color: 'rgba(255,255,255,0.55)',
    fontSize: 15,
    fontWeight: '400',
  },
  btnDivider: {
    width: 1,
    backgroundColor: 'rgba(255,255,255,0.08)',
  },
});

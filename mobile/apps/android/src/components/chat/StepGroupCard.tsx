import React from 'react';
import {View, Text, TouchableOpacity} from 'react-native';
import type {DonnaAction} from '@donna/shared';
import {useThemedStyles} from '../../theme';
import {makeCardStyles} from '../../screens/cardStyles';
import {
  describeStep, STATE_LABEL, summaryTitle, canUndoAny, type StepResult,
} from '../../utils/stepGroup';

/**
 * #186 G1: Sammelkarte für Mehrfachbefehle. Phase "offer": Schritte nummeriert in gesprochener Reihenfolge,
 * "Ausführen"/"Abbrechen" (vorher läuft nichts). Phase "running": nur Hinweis, die Stufe-2-Karte kommt als eigene
 * Bestätigung. Phase "result": "N von M angestoßen" mit einer Zeile je Schritt und "Alles rückgängig".
 */
export type StepGroupPhase = 'offer' | 'running' | 'result';

export function StepGroupCard({
  phase,
  steps,
  already,
  results,
  undoLines,
  undoDone,
  onRun,
  onCancel,
  onUndoAll,
  onClose,
}: {
  phase: StepGroupPhase;
  steps: ReadonlyArray<DonnaAction>;
  /** Schon vor der Karte gelaufene Geräteschritte derselben Antwort (stehen in gesprochener Reihenfolge davor). */
  already: ReadonlyArray<StepResult>;
  results: ReadonlyArray<StepResult>;
  undoLines: ReadonlyArray<string>;
  undoDone: boolean;
  onRun: () => void;
  onCancel: () => void;
  onUndoAll: () => void;
  onClose: () => void;
}): React.JSX.Element {
  const cardStyles = useThemedStyles(makeCardStyles);
  const alreadyCount = already.length;

  if (phase === 'result') {
    const all = [...already, ...results];
    return (
      <View style={cardStyles.cardOuter}>
        <View style={cardStyles.cardInner}>
          <Text style={cardStyles.fieldLabel}>Mehrere Schritte</Text>
          <Text style={[cardStyles.fieldValue, cardStyles.fieldGap]}>{summaryTitle(all)}</Text>
          {all.map((r, i) => (
            <View key={`${r.label}-${i}`}>
              {i > 0 && <View style={cardStyles.divider} />}
              <Text style={cardStyles.fieldLabel}>{`${i + 1}. ${STATE_LABEL[r.state]}`}</Text>
              <Text style={cardStyles.fieldValue}>{r.label}</Text>
              {r.note ? <Text style={cardStyles.warnText}>{r.note}</Text> : null}
            </View>
          ))}
          {undoLines.length > 0 ? <View style={cardStyles.divider} /> : null}
          {undoLines.map((l, i) => (
            <Text key={`u-${i}`} style={cardStyles.fieldLabel}>{l}</Text>
          ))}
        </View>
        <View style={cardStyles.btnRow}>
          {canUndoAny(all) && !undoDone ? (
            <TouchableOpacity onPress={onUndoAll} accessibilityRole="button" style={cardStyles.btn}>
              <Text style={cardStyles.btnText}>Alles rückgängig</Text>
            </TouchableOpacity>
          ) : null}
          <TouchableOpacity onPress={onClose} accessibilityRole="button" style={cardStyles.btn}>
            <Text style={cardStyles.btnText}>Schließen</Text>
          </TouchableOpacity>
        </View>
      </View>
    );
  }

  return (
    <View style={cardStyles.cardOuter}>
      <View style={cardStyles.cardInner}>
        <Text style={cardStyles.fieldLabel}>Mehrere Schritte — erst nach deinem Tipp</Text>
        {already.map((r, i) => (
          <View key={`a-${i}`}>
            {i > 0 && <View style={cardStyles.divider} />}
            <Text style={cardStyles.fieldLabel}>{`${i + 1}. ${STATE_LABEL[r.state]} (schon vorher)`}</Text>
            <Text style={cardStyles.fieldValue}>{r.label}</Text>
          </View>
        ))}
        {steps.map((st, i) => (
          <View key={`s-${i}`}>
            {(i > 0 || alreadyCount > 0) && <View style={cardStyles.divider} />}
            <Text style={cardStyles.fieldLabel}>{`${alreadyCount + i + 1}.`}</Text>
            <Text style={cardStyles.fieldValue}>{describeStep(st)}</Text>
          </View>
        ))}
        {phase === 'running' ? (
          <Text style={cardStyles.fieldLabel}>Läuft … Nachrichten, Anrufe und Termine bestätigst du einzeln.</Text>
        ) : null}
      </View>
      {phase === 'offer' ? (
        <View style={cardStyles.btnRow}>
          <TouchableOpacity onPress={onRun} accessibilityRole="button" style={[cardStyles.btn, cardStyles.btnPrimary]}>
            <Text style={cardStyles.btnTextPrimary}>Ausführen</Text>
          </TouchableOpacity>
          <TouchableOpacity onPress={onCancel} accessibilityRole="button" style={cardStyles.btn}>
            <Text style={cardStyles.btnText}>Abbrechen</Text>
          </TouchableOpacity>
        </View>
      ) : null}
    </View>
  );
}

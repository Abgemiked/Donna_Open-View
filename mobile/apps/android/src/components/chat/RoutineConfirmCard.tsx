import React from 'react';
import {View, Text, TouchableOpacity} from 'react-native';
import {useThemedStyles} from '../../theme';
import {makeCardStyles} from '../../screens/cardStyles';
import {
  HOME_UNDO_NOTE, MODE_CONFIRM, MODE_TITLE, isHomeLabel, type RoutineConfirm,
} from '../../utils/routines';

/**
 * #147 G2: Bestätigungskarte für Routinen (Stufe 2). Zeigt ALLE Schritte in Reihenfolge; bei "ändern" zusätzlich die
 * bisherigen, bei "löschen" die gelöschten Schritte. Erst "Speichern/Ändern/Löschen" speichert (der Server hält nur einen
 * Vorschlag), "Verwerfen" lässt alles, wie es war. Während der Anfrage sind beide Tasten gesperrt (kein Doppel-Tipp).
 */
export function RoutineConfirmCard({
  routine,
  busy,
  error,
  onConfirm,
  onReject,
}: {
  routine: RoutineConfirm;
  busy: boolean;
  error: string | null;
  onConfirm: () => void;
  onReject: () => void;
}): React.JSX.Element {
  const cardStyles = useThemedStyles(makeCardStyles);
  const shown = routine.mode === 'delete' ? routine.oldSteps : routine.steps;
  const hasHome = routine.mode !== 'delete' && routine.steps.some(isHomeLabel);
  const listLabel = routine.mode === 'delete' ? 'Das wird gelöscht' : routine.mode === 'update' ? 'Neu' : 'Schritte';
  return (
    <View style={cardStyles.cardOuter} testID="routine-confirm-card">
      <View style={cardStyles.cardInner}>
        <Text style={cardStyles.fieldLabel}>{MODE_TITLE[routine.mode]}</Text>
        <Text style={cardStyles.fieldValue}>{routine.name}</Text>
        {routine.mode === 'update' && routine.oldSteps.length > 0 ? (
          <>
            <View style={cardStyles.divider} />
            <Text style={cardStyles.fieldLabel}>Bisher</Text>
            {routine.oldSteps.map((s, i) => (
              <Text key={`o-${i}`} style={cardStyles.fieldValue}>{`${i + 1}. ${s}`}</Text>
            ))}
          </>
        ) : null}
        <View style={cardStyles.divider} />
        <Text style={cardStyles.fieldLabel}>{listLabel}</Text>
        {shown.map((s, i) => (
          <Text key={`s-${i}`} style={cardStyles.fieldValue}>{`${i + 1}. ${s}`}</Text>
        ))}
        {hasHome ? <Text style={cardStyles.warnText}>{HOME_UNDO_NOTE}</Text> : null}
        {error ? <Text style={cardStyles.warnText}>{error}</Text> : null}
      </View>
      <View style={cardStyles.btnRow}>
        <TouchableOpacity
          onPress={onConfirm}
          disabled={busy}
          accessibilityRole="button"
          accessibilityState={{disabled: busy}}
          style={[cardStyles.btn, cardStyles.btnPrimary]}>
          <Text style={cardStyles.btnTextPrimary}>{MODE_CONFIRM[routine.mode]}</Text>
        </TouchableOpacity>
        <TouchableOpacity onPress={onReject} disabled={busy} accessibilityRole="button" style={cardStyles.btn}>
          <Text style={cardStyles.btnText}>Verwerfen</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

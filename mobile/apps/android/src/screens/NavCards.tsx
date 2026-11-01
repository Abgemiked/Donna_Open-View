/**
 * Karten für Navigations-Rückfragen des Backends (Geocoding):
 *  - NavDisambiguateCard: mehrere Treffer → ein Tipp auf einen Treffer wählt das Ziel
 *  - NavNotFoundCard:     kein Treffer → freundliche Meldung
 * `navType` entscheidet nur über die Beschriftung; das Ausführen (Tesla-Intent bzw. Handy-Navigation)
 * übernimmt ChatScreen im onPick-Handler.
 */
import React from 'react';
import {ScrollView, Text, TouchableOpacity, View} from 'react-native';
import {useThemedStyles} from '../theme';
import {makeCardStyles, makePickerStyles} from './cardStyles';
import {str} from '../utils/navActions';
import type {NavOption, NavType} from '../utils/navActions';

export interface NavDisambiguateCardProps {
  query: string;
  options: NavOption[];
  navType: NavType;
  onPick: (option: NavOption) => void;
  onCancel: () => void;
}

export function NavDisambiguateCard({
  query,
  options,
  navType,
  onPick,
  onCancel,
}: NavDisambiguateCardProps): React.JSX.Element {
  const cardStyles = useThemedStyles(makeCardStyles);
  const pickerStyles = useThemedStyles(makePickerStyles);
  const tesla = navType === 'tesla_navigate';
  return (
    <View style={cardStyles.cardOuter} testID="nav-disambiguate-card">
      <View style={cardStyles.cardInner}>
        <Text style={cardStyles.fieldLabel}>{tesla ? 'Ziel für den Tesla wählen' : 'Ziel wählen'}</Text>
        <Text style={[cardStyles.fieldValue, {fontSize: 18, marginBottom: 8}]}>
          Mehrere Treffer für „{str(query)}“
        </Text>
        <ScrollView style={{maxHeight: 240}}>
          {options.map((o, i) => (
            <TouchableOpacity
              key={`${o.label}-${i}`}
              onPress={() => onPick(o)}
              style={pickerStyles.row}
              accessibilityRole="button"
              accessibilityLabel={tesla ? `An Tesla senden: ${o.label}` : `Navigation starten: ${o.label}`}>
              <Text style={pickerStyles.name} numberOfLines={2}>{o.label}</Text>
              {tesla ? <Text style={pickerStyles.number}>Tippen = an Tesla senden</Text> : null}
            </TouchableOpacity>
          ))}
        </ScrollView>
      </View>
      <View style={cardStyles.btnRow}>
        <TouchableOpacity onPress={onCancel} style={cardStyles.btn} accessibilityRole="button">
          <Text style={cardStyles.btnText}>Abbrechen</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

export interface NavNotFoundCardProps {
  message: string;
  onClose: () => void;
}

export function NavNotFoundCard({message, onClose}: NavNotFoundCardProps): React.JSX.Element {
  const safeMessage = str(message);
  const cardStyles = useThemedStyles(makeCardStyles);
  return (
    <View style={cardStyles.cardOuter} testID="nav-not-found-card">
      <View style={cardStyles.cardInner}>
        <Text style={cardStyles.fieldLabel}>Ziel nicht gefunden</Text>
        <Text style={[cardStyles.fieldValue, {fontSize: 18}]}>{safeMessage}</Text>
      </View>
      <View style={cardStyles.btnRow}>
        <TouchableOpacity onPress={onClose} style={cardStyles.btn} accessibilityRole="button">
          <Text style={cardStyles.btnText}>OK</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

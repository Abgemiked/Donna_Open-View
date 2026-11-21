import React from 'react';
import {StyleSheet, Text, TouchableOpacity, View} from 'react-native';
import {FONT, FONT_FAMILY, FONT_LARGE, RADIUS, SPACE, useTheme, useThemedStyles} from '../../theme';
import type {Theme} from '../../theme';
import type {OrbDisplayState} from '../../theme/orbState';
import {statusText} from '../DonnaStateOrb';
import {ChatOrb} from './ChatOrb';
import {Icon} from '../Icon';

export interface IdleScreenProps {
  /** z. B. "Guten Abend." (greetingForHour) */
  greeting: string;
  orbState: OrbDisplayState;
  /** false: Orb verdeckt (Gespraechsansicht darueber) -> keine Animation. */
  orbActive?: boolean;
  suggestions: readonly string[];
  /** Antippen einer Vorschlagszeile sendet ihren Text. */
  onSuggestion: (text: string) => void;
}

const ORB_SIZE = 200;

/** Leerzustand (Entwurf B): grosse Begruessung links, Zustandswort, Orb mittig, zwei Vorschlagszeilen. */
export function IdleScreen({greeting, orbState, orbActive = true, suggestions, onSuggestion}: IdleScreenProps): React.JSX.Element {
  const styles = useThemedStyles(makeStyles);
  const P = useTheme();
  return (
    <View style={styles.container} testID="idle-screen">
      <View style={styles.greetingBlock}>
        <Text style={styles.greeting} accessibilityRole="header">{`${greeting}\nWas brauchst du?`}</Text>
        <Text style={styles.state}>{statusText(orbState)}</Text>
      </View>
      <View style={styles.orbWrap}>
        <ChatOrb state={orbState} size={ORB_SIZE} silent active={orbActive} />
      </View>
      <View style={styles.suggestions}>
        {suggestions.map(s => (
          <TouchableOpacity
            key={s}
            style={styles.suggestion}
            onPress={() => onSuggestion(s)}
            accessibilityRole="button"
            accessibilityLabel={s}>
            <Text style={styles.suggestionText}>{s}</Text>
            <Icon name="chevronRight" size={18} color={P.accent as string} />
          </TouchableOpacity>
        ))}
      </View>
    </View>
  );
}

const makeStyles = (P: Theme) => StyleSheet.create({
  container: {flex: 1, paddingHorizontal: SPACE.lg, paddingTop: SPACE.lg, gap: SPACE.xl},
  greetingBlock: {gap: SPACE.sm},
  greeting: {
    fontFamily: FONT_FAMILY.display.extrabold,
    fontSize: FONT_LARGE.hero,
    lineHeight: 42,
    color: P.heading,
  },
  state: {fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.body, lineHeight: 22, color: P.muted},
  orbWrap: {flex: 1, alignItems: 'center', justifyContent: 'center'},
  suggestions: {gap: SPACE.sm},
  suggestion: {
    minHeight: 52,
    paddingHorizontal: SPACE.gutter,
    borderWidth: 1,
    borderColor: P.border,
    borderRadius: RADIUS.row,
    backgroundColor: P.card,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  suggestionText: {fontFamily: FONT_FAMILY.sans.semibold, fontSize: FONT.body, color: P.text},
});

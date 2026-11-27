import React from 'react';
import {View, Text, TouchableOpacity, StyleSheet} from 'react-native';
import {sendFeedback} from '@donna/shared';
import {useThemedStyles, useTheme, FONT, FONT_FAMILY, RADIUS, SPACE, TOUCH_MIN} from '../../theme';
import {Icon} from '../Icon';
import type {Theme} from '../../theme';

// ─── FeedbackRow ─────────────────────────────────────────────────────────────
// DONNA-139: Erweitert um messageId + userMessage für LTM-Integration.
// Der Backend-Endpoint schreibt bei Bewertung eine mem0-Memory — Donna lernt
// was Mike als hilfreich empfindet.
export function FeedbackRow({
  content,
  sessionId,
  messageId,
  userMessage,
}: {
  content: string;
  sessionId: string;
  messageId?: string;
  userMessage?: string;
}): React.JSX.Element {
  const fbStyles = useThemedStyles(makeFeedbackStyles);
  const theme = useTheme();
  const [sent, setSent] = React.useState<'positive' | 'negative' | null>(null);

  const rate = async (rating: 'positive' | 'negative') => {
    if (sent) return;
    setSent(rating);
    await sendFeedback(
      sessionId,
      rating,
      content.slice(0, 200),
      messageId,
      content,
      userMessage,
    );
  };

  return (
    <View style={fbStyles.row}>
      <TouchableOpacity
        onPress={() => rate('positive')}
        disabled={!!sent}
        style={[fbStyles.btn, sent === 'positive' && fbStyles.btnActive]}
        accessibilityRole="button"
        accessibilityLabel="Antwort war hilfreich"
        >
        <Icon name="thumbUp" size={18} color={(sent === 'positive' ? theme.accent : theme.muted) as string} />
      </TouchableOpacity>
      <TouchableOpacity
        onPress={() => rate('negative')}
        disabled={!!sent}
        style={[fbStyles.btn, sent === 'negative' && fbStyles.btnActive]}
        accessibilityRole="button"
        accessibilityLabel="Antwort war nicht hilfreich"
        >
        <Icon name="thumbDown" size={18} color={(sent === 'negative' ? theme.accent : theme.muted) as string} />
      </TouchableOpacity>
      {sent && <Text style={fbStyles.thanks}>Danke!</Text>}
    </View>
  );
}
const makeFeedbackStyles = (P: Theme) => StyleSheet.create({
  row: {flexDirection: 'row', alignItems: 'center', gap: SPACE.xs, marginTop: SPACE.xs},
  btn: {minWidth: TOUCH_MIN, minHeight: TOUCH_MIN, borderRadius: RADIUS.pill, alignItems: 'center', justifyContent: 'center'},
  btnActive: {backgroundColor: P.accentSoft},
  thanks: {color: P.muted, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.label, marginLeft: SPACE.xs},
});


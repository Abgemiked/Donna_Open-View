import React from 'react';
import {View, Text, TouchableOpacity, StyleSheet} from 'react-native';
import {useThemedStyles, useTheme} from '../../theme';
import {Icon} from '../Icon';
import type {Theme} from '../../theme';

// ─── ReplyPreview ─────────────────────────────────────────────────────────────
export function ReplyPreview({text, onCancel}: {text: string; onCancel: () => void}): React.JSX.Element {
  const replyStyles = useThemedStyles(makeReplyStyles);
  const theme = useTheme();
  return (
    <View style={replyStyles.wrap}>
      <View style={replyStyles.bar} />
      <Text style={replyStyles.text} numberOfLines={2}>{text}</Text>
      <TouchableOpacity
        onPress={onCancel}
        hitSlop={{top: 14, bottom: 14, left: 14, right: 14}}
        accessibilityRole="button"
        accessibilityLabel="Antwort abbrechen">
        <Icon name="close" size={18} color={theme.muted as string} />
      </TouchableOpacity>
    </View>
  );
}
const makeReplyStyles = (P: Theme) => StyleSheet.create({
  wrap: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: P.accentSoft,
    borderTopWidth: 1,
    borderTopColor: P.border,
    paddingHorizontal: 14,
    paddingVertical: 8,
    gap: 10,
  },
  bar: {width: 3, borderRadius: 2, backgroundColor: P.accent, alignSelf: 'stretch'},
  text: {flex: 1, color: P.muted, fontSize: 12, fontStyle: 'italic'},
  close: {color: P.muted, fontSize: 16},
});


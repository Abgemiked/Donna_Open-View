import React from 'react';
import {View, Text, TouchableOpacity, StyleSheet} from 'react-native';
import {useThemedStyles, useTheme, FONT, FONT_FAMILY, RADIUS, SPACE, TOUCH_MIN} from '../../theme';
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
        style={replyStyles.close}
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
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: P.border,
    paddingLeft: SPACE.lg,
    paddingRight: SPACE.xs,
    paddingVertical: SPACE.xs,
    gap: SPACE.md,
  },
  bar: {width: SPACE.xs, borderRadius: RADIUS.sm, backgroundColor: P.accent, alignSelf: 'stretch'},
  text: {flex: 1, color: P.muted, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.label},
  close: {minWidth: TOUCH_MIN, minHeight: TOUCH_MIN, borderRadius: RADIUS.pill, alignItems: 'center', justifyContent: 'center'},
});


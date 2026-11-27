import React from 'react';
import {View, Text, TouchableOpacity, Modal, StyleSheet, Share} from 'react-native';
import {useThemedStyles, useTheme, FONT, FONT_FAMILY, RADIUS, SPACE, TOUCH_MIN} from '../../theme';
import {Icon} from '../Icon';
import type {Theme} from '../../theme';

// ─── MessageActionSheet — Aktionsblatt (#134 P8): Rundung card, Zeilen >= 48, Theme-Token ──────
export interface ActionSheetState {
  visible: boolean;
  content: string;
  isAssistant: boolean;
  replyFn?: () => void;
}
export function MessageActionSheet({
  state,
  onClose,
}: {
  state: ActionSheetState;
  onClose: () => void;
}): React.JSX.Element {
  const asStyles = useThemedStyles(makeActionSheetStyles);
  const theme = useTheme();
  const handleCopy = () => {
    Share.share({message: state.content}).catch(() => {});
    onClose();
  };
  const handleReply = () => {
    state.replyFn?.();
    onClose();
  };
  return (
    <Modal visible={state.visible} transparent animationType="slide" onRequestClose={onClose}>
      <TouchableOpacity style={asStyles.overlay} activeOpacity={1} onPress={onClose}>
        <View style={asStyles.sheet}>
          {/* Handle */}
          <View style={asStyles.handle} />
          {/* Vorschau */}
          <Text style={asStyles.preview} numberOfLines={2}>{state.content}</Text>
          <View style={asStyles.divider} />
          {/* Aktionen */}
          <TouchableOpacity style={asStyles.item} onPress={handleCopy} accessibilityRole="button" accessibilityLabel="Kopieren">
            <View style={asStyles.itemIcon}><Icon name="copy" size={20} color={theme.text as string} /></View>
            <Text style={asStyles.itemText}>Kopieren</Text>
          </TouchableOpacity>
          {state.isAssistant && (
            <TouchableOpacity style={asStyles.item} onPress={handleReply} accessibilityRole="button" accessibilityLabel="Antworten">
              <View style={asStyles.itemIcon}><Icon name="reply" size={20} color={theme.text as string} /></View>
              <Text style={asStyles.itemText}>Antworten</Text>
            </TouchableOpacity>
          )}
          <View style={asStyles.divider} />
          <TouchableOpacity style={asStyles.cancelItem} onPress={onClose} accessibilityRole="button" accessibilityLabel="Abbrechen">
            <Text style={asStyles.cancelText}>Abbrechen</Text>
          </TouchableOpacity>
        </View>
      </TouchableOpacity>
    </Modal>
  );
}
const makeActionSheetStyles = (P: Theme) => StyleSheet.create({
  overlay: {flex: 1, backgroundColor: P.scrim, justifyContent: 'flex-end'},
  sheet: {
    backgroundColor: P.surface,
    borderTopLeftRadius: RADIUS.card,
    borderTopRightRadius: RADIUS.card,
    borderWidth: 1,
    borderColor: P.border,
    paddingBottom: SPACE.xxl,
    paddingTop: SPACE.md,
  },
  handle: {
    width: SPACE.xxl, height: SPACE.xs, borderRadius: RADIUS.sm,
    backgroundColor: P.border,
    alignSelf: 'center', marginBottom: SPACE.lg,
  },
  preview: {
    color: P.muted, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.label,
    paddingHorizontal: SPACE.gutter, marginBottom: SPACE.md, lineHeight: 18,
  },
  divider: {height: StyleSheet.hairlineWidth, backgroundColor: P.border, marginVertical: SPACE.xs},
  item: {
    flexDirection: 'row', alignItems: 'center',
    minHeight: TOUCH_MIN, paddingHorizontal: SPACE.xl, paddingVertical: SPACE.md, gap: SPACE.lg,
  },
  itemIcon: {width: SPACE.xl, alignItems: 'center'},
  itemText: {color: P.text, fontFamily: FONT_FAMILY.sans.semibold, fontSize: FONT.body},
  cancelItem: {minHeight: TOUCH_MIN, paddingHorizontal: SPACE.xl, paddingVertical: SPACE.md, alignItems: 'center', justifyContent: 'center'},
  cancelText: {color: P.muted, fontFamily: FONT_FAMILY.sans.semibold, fontSize: FONT.body},
});


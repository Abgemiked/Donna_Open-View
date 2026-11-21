import React from 'react';
import {View, Text, TouchableOpacity, Modal, StyleSheet, Share} from 'react-native';
import {useThemedStyles, useTheme} from '../../theme';
import {Icon} from '../Icon';
import type {Theme} from '../../theme';

// ─── MessageActionSheet — Pulse-styled bottom sheet ──────────────────────────
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
          <TouchableOpacity style={asStyles.item} onPress={handleCopy}>
            <View style={asStyles.itemIcon}><Icon name="copy" size={20} color={theme.text as string} /></View>
            <Text style={asStyles.itemText}>Kopieren</Text>
          </TouchableOpacity>
          {state.isAssistant && (
            <TouchableOpacity style={asStyles.item} onPress={handleReply}>
              <View style={asStyles.itemIcon}><Icon name="reply" size={20} color={theme.text as string} /></View>
              <Text style={asStyles.itemText}>Antworten</Text>
            </TouchableOpacity>
          )}
          <View style={asStyles.divider} />
          <TouchableOpacity style={asStyles.cancelItem} onPress={onClose}>
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
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    borderWidth: 1,
    borderColor: P.border,
    paddingBottom: 32,
    paddingTop: 12,
  },
  handle: {
    width: 40, height: 4, borderRadius: 2,
    backgroundColor: P.border,
    alignSelf: 'center', marginBottom: 16,
  },
  preview: {
    color: P.muted, fontSize: 13, fontStyle: 'italic',
    paddingHorizontal: 20, marginBottom: 12, lineHeight: 18,
  },
  divider: {height: 1, backgroundColor: P.border, marginVertical: 4},
  item: {
    flexDirection: 'row', alignItems: 'center',
    paddingHorizontal: 24, paddingVertical: 16, gap: 16,
  },
  itemIcon: {width: 28, alignItems: 'center'},
  itemText: {color: P.text, fontSize: 16},
  cancelItem: {paddingHorizontal: 24, paddingVertical: 16, alignItems: 'center'},
  cancelText: {color: P.muted, fontSize: 15},
});


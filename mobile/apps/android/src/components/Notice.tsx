/**
 * #134 P8: Einheitlicher Hinweis-Baustein fuer Info, Warnung und Fehler (Banner, Statusleisten, Fehlerseiten).
 * Icon + Text (nie Farbe allein), optional eine Aktion als Pille und eine Schliessen-Taste. Rundung `RADIUS.row`,
 * alle Farben aus dem Theme, Tippziele >= TOUCH_MIN.
 */
import React, {useMemo} from 'react';
import {StyleSheet, Text, TouchableOpacity, View} from 'react-native';
import {FONT, FONT_FAMILY, RADIUS, SPACE, TOUCH_MIN, useTheme} from '../theme';
import type {Theme} from '../theme';
import {Icon} from './Icon';
import {noticeAnnounce, noticeVisual, type NoticeKind} from './noticeModel';

export interface NoticeProps {
  kind: NoticeKind;
  text: string;
  /** Beschriftung der Aktions-Pille (z. B. "Erneut versuchen"); ohne `onAction` wird keine Pille gezeigt. */
  actionLabel?: string;
  onAction?: () => void;
  /** Mit Handler erscheint eine Schliessen-Taste ("Hinweis schliessen"). */
  onDismiss?: () => void;
  /** Fortschrittshinweis (z. B. Download mit Prozent): wird nicht als Live-Region angesagt. */
  progress?: boolean;
  testID?: string;
}

export function Notice({kind, text, actionLabel, onAction, onDismiss, progress, testID}: NoticeProps): React.JSX.Element {
  const c = useTheme();
  const styles = useMemo(() => makeStyles(c), [c]);
  const v = noticeVisual(kind);
  const tone = c[v.tone] as string;
  const announce = noticeAnnounce(kind, progress);
  return (
    <View
      style={[styles.box, {borderColor: tone}]}
      testID={testID}
      accessibilityRole={announce.role}
      accessibilityLiveRegion={announce.liveRegion}>
      {/* Icon + Text bilden EINE Vorlesestelle ("Fehler: ..."); die Tasten bleiben einzeln erreichbar */}
      <View style={styles.content} accessible accessibilityLabel={`${v.spoken}: ${text}`}>
        <Icon name={v.icon} size={22} color={tone} />
        <Text style={styles.text}>{text}</Text>
      </View>
      {onAction != null && actionLabel != null ? (
        <TouchableOpacity style={styles.action} onPress={onAction} accessibilityRole="button" accessibilityLabel={actionLabel}>
          <Text style={styles.actionText}>{actionLabel}</Text>
        </TouchableOpacity>
      ) : null}
      {onDismiss != null ? (
        <TouchableOpacity style={styles.close} onPress={onDismiss} accessibilityRole="button" accessibilityLabel="Hinweis schließen">
          <Icon name="close" size={20} color={c.muted as string} />
        </TouchableOpacity>
      ) : null}
    </View>
  );
}

function makeStyles(c: Theme) {
  return StyleSheet.create({
    box: {
      flexDirection: 'row',
      alignItems: 'center',
      flexWrap: 'wrap',
      gap: SPACE.md,
      marginHorizontal: SPACE.lg,
      marginVertical: SPACE.sm,
      paddingVertical: SPACE.sm,
      paddingLeft: SPACE.lg,
      paddingRight: SPACE.sm,
      minHeight: TOUCH_MIN,
      borderRadius: RADIUS.row,
      borderWidth: 1,
      backgroundColor: c.card,
    },
    content: {flex: 1, minWidth: 140, flexDirection: 'row', alignItems: 'center', gap: SPACE.md},
    text: {flex: 1, color: c.text, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.label},
    action: {
      minHeight: TOUCH_MIN,
      minWidth: TOUCH_MIN,
      paddingHorizontal: SPACE.lg,
      borderRadius: RADIUS.pill,
      borderWidth: 1,
      borderColor: c.controlBorder,
      alignItems: 'center',
      justifyContent: 'center',
    },
    actionText: {color: c.text, fontFamily: FONT_FAMILY.sans.semibold, fontSize: FONT.label},
    close: {minHeight: TOUCH_MIN, minWidth: TOUCH_MIN, alignItems: 'center', justifyContent: 'center'},
  });
}

export default Notice;

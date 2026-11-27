import React, {useState, useCallback, useEffect, useMemo} from 'react';
import {View, Text, TouchableOpacity, SectionList, Modal, ActivityIndicator, StyleSheet} from 'react-native';
import {fetchSessions, fetchSessionMessages, getApiToken} from '@donna/shared';
import type {SessionInfo, SessionMessage} from '@donna/shared';
import {FONT, FONT_FAMILY, RADIUS, SPACE, TOUCH_MIN, useTheme, useThemedStyles} from '../../theme';
import type {Theme} from '../../theme';
import {Icon} from '../Icon';
import {Notice} from '../Notice';
import {historyNoticeKind} from '../noticeModel';
import {groupSessionsByDay, timeLabel} from './historyGroups';

// ─── Verlauf (#134 P8) ────────────────────────────────────────────────────────
// Eigene Seite im neuen Stil: Kopf mit Schliessen-Icon, Gespraeche nach Tagen getrennt, Zeilen mit Rundung `row`,
// Leerzustand mit kurzem Text, "Neuer Chat" als Pille. Laden/Auswaehlen unveraendert.
export function HistoryPanel({
  visible, onClose, onLoadSession, onNewChat, topInset = 0, bottomInset = 0,
}: {
  visible: boolean;
  onClose: () => void;
  onLoadSession: (msgs: SessionMessage[], sessionId: string) => void;
  /** Mit Handler erscheint die Pille "Neuer Chat" (Verlauf schliesst, Chat startet leer). */
  onNewChat?: () => void;
  topInset?: number;
  bottomInset?: number;
}): React.JSX.Element {
  const P = useTheme();
  const hStyles = useThemedStyles(makeHistoryStyles);
  const [sessions, setSessions] = useState<SessionInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadingSession, setLoadingSession] = useState<string | null>(null);
  const [fetchError, setFetchError] = useState<'no_token' | 'network' | null>(null);

  const loadSessions = useCallback(async () => {
    // DONNA-114: Auth-Token-Guard — ohne Token liefert das Backend 401
    // und fetchSessions() gibt still [] zurück. Stattdessen Fehlerzustand anzeigen.
    if (!getApiToken()) {
      setFetchError('no_token');
      setLoading(false);
      return;
    }
    setFetchError(null);
    setLoading(true);
    try {
      const s = await fetchSessions();
      setSessions(s);
    } catch {
      setFetchError('network');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!visible) return;
    loadSessions();
  }, [visible, loadSessions]);

  const handleSession = async (sid: string) => {
    setLoadingSession(sid);
    const msgs = await fetchSessionMessages(sid);
    setLoadingSession(null);
    onLoadSession(msgs, sid);
    onClose();
  };

  // `visible` als Abhaengigkeit: "Heute" wird bei jedem Oeffnen neu berechnet (Tageswechsel bei offener App)
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const sections = useMemo(() => groupSessionsByDay(sessions, new Date()), [sessions, visible]);

  return (
    <Modal visible={visible} animationType="slide" onRequestClose={onClose}>
      <View style={[hStyles.page, {paddingTop: topInset, paddingBottom: bottomInset}]} testID="history-panel">
        <View style={hStyles.header}>
          <Text style={hStyles.title} accessibilityRole="header">Gesprächsverlauf</Text>
          <TouchableOpacity
            style={hStyles.closeBtn}
            onPress={onClose}
            accessibilityRole="button"
            accessibilityLabel="Verlauf schließen">
            <Icon name="close" size={24} color={P.heading as string} />
          </TouchableOpacity>
        </View>
        {loading ? (
          <ActivityIndicator color={P.accent} style={hStyles.loader} />
        ) : fetchError !== null ? (
          <Notice
            kind={historyNoticeKind(fetchError)}
            text={fetchError === 'no_token'
              ? 'Noch nicht angemeldet. Bitte kurz warten und erneut öffnen.'
              : 'Keine Verbindung. Der Verlauf ist gerade nicht verfügbar.'}
            actionLabel="Erneut laden"
            onAction={loadSessions}
            testID="history-error"
          />
        ) : sessions.length === 0 ? (
          <View style={hStyles.emptyWrap} testID="history-empty">
            <Icon name="history" size={32} color={P.muted as string} />
            <Text style={hStyles.empty}>Keine Gespräche in den letzten 24 Stunden.</Text>
          </View>
        ) : (
          <SectionList
            sections={sections}
            keyExtractor={s => s.session_id}
            stickySectionHeadersEnabled={false}
            contentContainerStyle={hStyles.listContent}
            renderSectionHeader={({section}) => (
              <Text style={hStyles.dayTitle} accessibilityRole="header">{section.title}</Text>
            )}
            ItemSeparatorComponent={() => <View style={hStyles.gap} />}
            renderItem={({item}) => {
              const busy = loadingSession === item.session_id;
              return (
                <TouchableOpacity
                  style={hStyles.sessionRow}
                  onPress={() => handleSession(item.session_id)}
                  disabled={busy}
                  accessibilityRole="button"
                  accessibilityLabel={`Gespräch von ${timeLabel(item.started_at)} Uhr, ${item.message_count} Nachrichten: ${item.preview}`}>
                  <View style={hStyles.sessionLeft}>
                    <Text style={hStyles.sessionTime}>{timeLabel(item.started_at)}</Text>
                    <Text style={hStyles.sessionPreview} numberOfLines={2}>{item.preview}</Text>
                  </View>
                  {busy
                    ? <ActivityIndicator size="small" color={P.accent} />
                    : <Text style={hStyles.sessionCount}>{item.message_count}</Text>}
                  <Icon name="chevronRight" size={20} color={P.muted as string} />
                </TouchableOpacity>
              );
            }}
          />
        )}
        {onNewChat ? (
          <View style={hStyles.footer}>
            <TouchableOpacity
              style={hStyles.newChat}
              onPress={() => { onClose(); onNewChat(); }}
              accessibilityRole="button"
              accessibilityLabel="Neuer Chat">
              <Icon name="newChat" size={20} color={P.onAction as string} />
              <Text style={hStyles.newChatText}>Neuer Chat</Text>
            </TouchableOpacity>
          </View>
        ) : null}
      </View>
    </Modal>
  );
}

const makeHistoryStyles = (P: Theme) => StyleSheet.create({
  page: {flex: 1, backgroundColor: P.bg},
  header: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingLeft: SPACE.gutter, paddingRight: SPACE.sm, paddingVertical: SPACE.md,
  },
  title: {color: P.heading, fontFamily: FONT_FAMILY.display.bold, fontSize: FONT.display},
  closeBtn: {
    minWidth: TOUCH_MIN, minHeight: TOUCH_MIN, borderRadius: RADIUS.pill,
    alignItems: 'center', justifyContent: 'center',
  },
  loader: {marginTop: SPACE.xxl},
  emptyWrap: {flex: 1, alignItems: 'center', justifyContent: 'center', gap: SPACE.md, paddingHorizontal: SPACE.xl},
  empty: {color: P.muted, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.body, textAlign: 'center'},
  listContent: {paddingHorizontal: SPACE.gutter, paddingBottom: SPACE.xl},
  dayTitle: {
    color: P.muted, fontFamily: FONT_FAMILY.sans.bold, fontSize: FONT.label,
    marginTop: SPACE.lg, marginBottom: SPACE.sm, marginLeft: SPACE.xs,
  },
  gap: {height: SPACE.sm},
  sessionRow: {
    flexDirection: 'row', alignItems: 'center', gap: SPACE.md,
    minHeight: TOUCH_MIN, paddingVertical: SPACE.md, paddingHorizontal: SPACE.lg,
    borderRadius: RADIUS.row, backgroundColor: P.card, borderWidth: 1, borderColor: P.border,
  },
  sessionLeft: {flex: 1, gap: SPACE.xs},
  sessionTime: {color: P.accent, fontFamily: FONT_FAMILY.sans.bold, fontSize: FONT.label},
  sessionPreview: {color: P.text, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.body},
  sessionCount: {color: P.muted, fontFamily: FONT_FAMILY.sans.semibold, fontSize: FONT.label},
  footer: {paddingHorizontal: SPACE.gutter, paddingVertical: SPACE.lg, alignItems: 'center'},
  newChat: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: SPACE.sm,
    minHeight: TOUCH_MIN, paddingHorizontal: SPACE.xl, borderRadius: RADIUS.pill, backgroundColor: P.action,
  },
  newChatText: {color: P.onAction, fontFamily: FONT_FAMILY.sans.bold, fontSize: FONT.body},
});

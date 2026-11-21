import React, {useState, useCallback, useEffect} from 'react';
import {View, Text, TouchableOpacity, FlatList, Modal, ActivityIndicator, StyleSheet} from 'react-native';
import {fetchSessions, fetchSessionMessages, getApiToken} from '@donna/shared';
import type {SessionInfo, SessionMessage} from '@donna/shared';
import {useTheme, useThemedStyles} from '../../theme';
import {Icon} from '../Icon';
import type {Theme} from '../../theme';

// ─── History Panel ────────────────────────────────────────────────────────────
export function HistoryPanel({
  visible, onClose, onLoadSession,
}: {
  visible: boolean;
  onClose: () => void;
  onLoadSession: (msgs: SessionMessage[]) => void;
}): React.JSX.Element {
  const P = useTheme();
  const hStyles = useThemedStyles(makeHistoryStyles);
  const theme = useTheme();
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
    onLoadSession(msgs);
    onClose();
  };

  const fmtTime = (ts: number) => {
    const d = new Date(ts * 1000);
    const now = new Date();
    const diffH = (now.getTime() - d.getTime()) / 3600000;
    if (diffH < 24) return d.toLocaleTimeString('de-DE', {hour: '2-digit', minute: '2-digit'});
    return d.toLocaleDateString('de-DE', {day: '2-digit', month: '2-digit'}) + ' ' +
           d.toLocaleTimeString('de-DE', {hour: '2-digit', minute: '2-digit'});
  };

  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}>
      <TouchableOpacity style={hStyles.backdrop} activeOpacity={1} onPress={onClose} />
      <View style={hStyles.panel}>
        <View style={hStyles.handle} />
        <View style={hStyles.header}>
          <Text style={hStyles.title} accessibilityRole="header">Gesprächsverlauf</Text>
          <TouchableOpacity
            onPress={onClose}
            hitSlop={{top: 14, bottom: 14, left: 14, right: 14}}
            accessibilityRole="button"
            accessibilityLabel="Verlauf schließen">
            <Icon name="close" size={20} color={theme.muted as string} />
          </TouchableOpacity>
        </View>
        {loading ? (
          <ActivityIndicator color={P.accent} style={{marginTop: 32}} />
        ) : fetchError === 'no_token' ? (
          <View style={hStyles.errorWrap}>
            <Text style={hStyles.empty}>Nicht authentifiziert — bitte kurz warten und erneut öffnen.</Text>
            <TouchableOpacity style={hStyles.retryBtn} onPress={loadSessions}>
              <Text style={hStyles.retryText}>Erneut laden</Text>
            </TouchableOpacity>
          </View>
        ) : fetchError === 'network' ? (
          <View style={hStyles.errorWrap}>
            <Text style={hStyles.empty}>Verbindungsfehler — kein Verlauf verfügbar.</Text>
            <TouchableOpacity style={hStyles.retryBtn} onPress={loadSessions}>
              <Text style={hStyles.retryText}>Erneut laden</Text>
            </TouchableOpacity>
          </View>
        ) : sessions.length === 0 ? (
          <Text style={hStyles.empty}>Keine Gespräche in den letzten 24h</Text>
        ) : (
          <FlatList
            data={sessions}
            keyExtractor={s => s.session_id}
            contentContainerStyle={{paddingHorizontal: 16, paddingBottom: 24}}
            renderItem={({item}) => (
              <TouchableOpacity
                style={hStyles.sessionRow}
                onPress={() => handleSession(item.session_id)}
                disabled={loadingSession === item.session_id}
              >
                <View style={hStyles.sessionLeft}>
                  <Text style={hStyles.sessionTime}>{fmtTime(item.started_at)}</Text>
                  <Text style={hStyles.sessionPreview} numberOfLines={2}>{item.preview}</Text>
                </View>
                <View style={hStyles.sessionRight}>
                  {loadingSession === item.session_id
                    ? <ActivityIndicator size="small" color={P.accent} />
                    : <Text style={hStyles.sessionCount}>{item.message_count} Nachr.</Text>
                  }
                  <Text style={hStyles.chevron}>›</Text>
                </View>
              </TouchableOpacity>
            )}
          />
        )}
      </View>
    </Modal>
  );
}

const makeHistoryStyles = (P: Theme) => StyleSheet.create({
  backdrop: {flex: 1, backgroundColor: P.scrim},
  panel: {
    backgroundColor: P.card,
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    maxHeight: '75%',
    borderTopWidth: 1,
    borderColor: P.border,
  },
  handle: {
    width: 36, height: 4, borderRadius: 2,
    backgroundColor: P.border,
    alignSelf: 'center', marginTop: 10,
  },
  header: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingHorizontal: 20, paddingVertical: 14,
    borderBottomWidth: 1, borderBottomColor: P.border,
  },
  title: {color: P.accent, fontSize: 15, fontWeight: '700'},
  close: {color: P.muted, fontSize: 18},
  empty: {color: P.muted, textAlign: 'center', marginTop: 32, fontSize: 14, paddingHorizontal: 24},
  errorWrap: {alignItems: 'center', paddingTop: 24},
  retryBtn: {marginTop: 16, paddingHorizontal: 20, paddingVertical: 10, borderRadius: 8, borderWidth: 1, borderColor: P.accent},
  retryText: {color: P.accent, fontSize: 13, fontWeight: '600'},
  sessionRow: {
    flexDirection: 'row', alignItems: 'center',
    paddingVertical: 14, borderBottomWidth: 1, borderBottomColor: P.border,
    gap: 12,
  },
  sessionLeft: {flex: 1, gap: 4},
  sessionTime: {color: P.accent2, fontSize: 11, fontWeight: '600'},
  sessionPreview: {color: P.text, fontSize: 13},
  sessionRight: {alignItems: 'flex-end', gap: 4},
  sessionCount: {color: P.muted, fontSize: 11},
  chevron: {color: P.accent, fontSize: 20},
});


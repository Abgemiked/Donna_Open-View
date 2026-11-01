/**
 * LiveBubbleSettings (A2, Increment 1) — Schalter für die schwebende Live-Bubble in der Einrichtung.
 *
 * An/Aus startet bzw. stoppt den LiveBubbleService (schwebender Chat-Head über allen Apps). Fehlt das
 * Overlay-Recht, zeigt die Karte einen Hinweis (das Recht wird im Overlay-Schritt der Einrichtung erteilt).
 * Ohne natives Modul (Tests/andere Plattformen) rendert die Komponente nichts.
 */
import React, {useCallback, useEffect, useMemo, useState} from 'react';
import {StyleSheet, Text, TouchableOpacity, View} from 'react-native';
import {useTheme} from '../theme';
import type {Theme} from '../theme';
import {
  canDrawOverlay,
  hideLiveBubble,
  isLiveBubbleAvailable,
  isLiveBubbleRunning,
  showLiveBubble,
} from '../native/liveBubble';

const MIN_TOUCH = 48;

export default function LiveBubbleSettings(): React.JSX.Element | null {
  const c = useTheme();
  const styles = useMemo(() => makeStyles(c), [c]);
  const [on, setOn] = useState(false);
  const [overlayOk, setOverlayOk] = useState(true);

  useEffect(() => {
    let alive = true;
    void isLiveBubbleRunning().then(r => {
      if (alive) {
        setOn(r);
      }
    });
    void canDrawOverlay().then(ok => {
      if (alive) {
        setOverlayOk(ok);
      }
    });
    return () => {
      alive = false;
    };
  }, []);

  const enable = useCallback(() => {
    void showLiveBubble().then(ok => {
      setOn(ok);
      if (!ok) {
        void canDrawOverlay().then(setOverlayOk);
      }
    });
  }, []);

  const disable = useCallback(() => {
    hideLiveBubble();
    setOn(false);
  }, []);

  if (!isLiveBubbleAvailable()) {
    return null;
  }

  return (
    <View style={styles.box} testID="live-bubble-settings">
      <Text style={styles.title} accessibilityRole="header">
        Live-Bubble
      </Text>
      <Text style={styles.desc}>
        Eine schwebende Blase über allen Apps. Tippen öffnet Donna, Verschieben per Finger, langes Drücken schließt sie.
      </Text>
      <View style={styles.optionRow}>
        {([['an', true], ['aus', false]] as Array<[string, boolean]>).map(([label, val]) => {
          const selected = on === val;
          return (
            <TouchableOpacity
              key={label}
              testID={`live-bubble-${label}`}
              style={[styles.option, selected && styles.optionSelected]}
              onPress={() => (val ? enable() : disable())}
              accessibilityRole="radio"
              accessibilityState={{selected}}
              accessibilityLabel={`Live-Bubble ${label}`}>
              <Text style={[styles.optionText, selected && styles.optionTextSelected]}>
                {val ? 'An' : 'Aus'}
              </Text>
            </TouchableOpacity>
          );
        })}
      </View>
      {!overlayOk && (
        <Text style={[styles.desc, {color: c.warning}]} testID="live-bubble-overlay-hint">
          Dafür braucht Donna die Berechtigung „Über anderen Apps anzeigen" — bitte im Schritt „Overlay" erteilen.
        </Text>
      )}
    </View>
  );
}

function makeStyles(c: Theme) {
  return StyleSheet.create({
    box: {
      marginTop: 12,
      padding: 16,
      borderRadius: 16,
      backgroundColor: c.card,
      borderWidth: 1,
      borderColor: c.border,
    },
    title: {color: c.text, fontSize: 16, fontWeight: '700'},
    desc: {color: c.muted, fontSize: 13, lineHeight: 18, marginTop: 4},
    optionRow: {flexDirection: 'row', gap: 8, marginTop: 8},
    option: {
      flex: 1,
      minHeight: MIN_TOUCH,
      borderRadius: 24,
      borderWidth: 1.5,
      borderColor: c.border,
      alignItems: 'center',
      justifyContent: 'center',
    },
    optionSelected: {borderColor: c.accent, backgroundColor: c.accent},
    optionText: {color: c.text, fontSize: 14, fontWeight: '600'},
    optionTextSelected: {color: c.onAccent},
  });
}

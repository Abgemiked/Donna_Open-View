/**
 * VoiceSettings — Einstellung „Stimme" (Voice-Batch, A/B-Test Edge ↔ Piper-lokal).
 *
 * Sitzt im Einrichtungs-Bildschirm (⚙️ im Chat bzw. Onboarding). Zeigt:
 *  - den Umschalter Edge (online, Backend-/tts) ↔ Piper (lokal, on-device) — die jeweils andere Engine ist Fallback,
 *  - die Edge-Stimme (Katja / Seraphina, beide weiblich),
 *  - den EAGER-Download des Piper-Modells (de_DE-kerstin-low) mit sichtbarem Fortschritt und „Erneut versuchen".
 * Ohne natives Piper-Modul (Tests, andere Plattformen) rendert die Komponente nichts.
 */
import React, {useCallback, useEffect, useMemo, useState} from 'react';
import {StyleSheet, Text, TouchableOpacity, View} from 'react-native';
import {useTheme} from '../theme';
import type {Theme} from '../theme';
import {usePiperModel} from '../hooks/usePiperModel';
import {
  EDGE_VOICES,
  EDGE_VOICE_DEFAULT,
  ENGINE_LABELS,
  TTS_ENGINE_DEFAULT,
  getEdgeVoice,
  getTtsEngine,
  isPiperAvailable,
  setEdgeVoice,
  setTtsEngine,
  type TtsEngine,
} from '../native/ttsEngine';

const MIN_TOUCH = 48;

export default function VoiceSettings(): React.JSX.Element | null {
  const c = useTheme();
  const styles = useMemo(() => makeStyles(c), [c]);
  const piper = usePiperModel(true);
  const [engine, setEngineState] = useState<TtsEngine>(TTS_ENGINE_DEFAULT);
  const [voice, setVoiceState] = useState<string>(EDGE_VOICE_DEFAULT);

  useEffect(() => {
    let alive = true;
    void getTtsEngine().then(e => alive && setEngineState(e));
    void getEdgeVoice().then(v => alive && setVoiceState(v));
    return () => {
      alive = false;
    };
  }, []);

  const chooseEngine = useCallback((next: TtsEngine) => {
    setEngineState(next); // sofort sichtbar; nativ bestätigt die Antwort
    void setTtsEngine(next).then(setEngineState);
  }, []);

  const chooseVoice = useCallback((id: string) => {
    setVoiceState(id);
    void setEdgeVoice(id).then(setVoiceState);
  }, []);

  if (!isPiperAvailable()) {
    return null;
  }

  // Ein freundlicher Satz je Zustand; technische Fehlertexte stehen nur im Log (usePiperModel)
  const piperLine =
    piper.status === 'ready'
      ? 'Piper ist bereit (Kerstin, lokal).'
      : piper.status === 'downloading'
        ? `Piper wird geladen (${piper.progress} %) — bis dahin spricht Edge.`
        : piper.error
          ? 'Piper konnte nicht geladen werden — Edge spricht weiter. Bitte später erneut versuchen.'
          : piper.metered
            ? 'Piper lädt automatisch nur im WLAN. Mit „Jetzt laden“ startest du den Download (ca. 64 MB).'
            : 'Piper ist noch nicht geladen.';

  return (
    <View style={styles.box} testID="voice-settings">
      <Text style={styles.title} accessibilityRole="header">
        Stimme (A/B-Test)
      </Text>
      <Text style={styles.desc}>
        Vergleiche zwei weibliche Stimmen. Fällt die gewählte aus (kein Netz, Modell fehlt), springt automatisch die
        andere ein.
      </Text>

      <View style={styles.optionRow}>
        {(['edge', 'piper'] as TtsEngine[]).map(e => {
          const selected = engine === e;
          return (
            <TouchableOpacity
              key={e}
              testID={`voice-engine-${e}`}
              style={[styles.option, selected && styles.optionSelected]}
              onPress={() => chooseEngine(e)}
              accessibilityRole="radio"
              accessibilityState={{selected}}
              accessibilityLabel={`Stimme ${ENGINE_LABELS[e]}`}>
              <Text style={[styles.optionText, selected && styles.optionTextSelected]}>{ENGINE_LABELS[e]}</Text>
            </TouchableOpacity>
          );
        })}
      </View>

      <Text style={styles.sub}>Edge-Stimme</Text>
      <View style={styles.optionRow}>
        {EDGE_VOICES.map(v => {
          const selected = voice === v.id;
          return (
            <TouchableOpacity
              key={v.id}
              testID={`voice-edge-${v.label.toLowerCase()}`}
              style={[styles.option, selected && styles.optionSelected]}
              onPress={() => chooseVoice(v.id)}
              accessibilityRole="radio"
              accessibilityState={{selected}}
              accessibilityLabel={`Edge-Stimme ${v.label}`}>
              <Text style={[styles.optionText, selected && styles.optionTextSelected]}>{v.label}</Text>
            </TouchableOpacity>
          );
        })}
      </View>

      <Text
        style={[styles.status, piper.error != null && {color: c.warning}, piper.status === 'ready' && {color: c.ok}]}
        accessibilityLiveRegion="polite"
        testID="voice-piper-status">
        {piperLine}
      </Text>
      {piper.status === 'downloading' && (
        <View style={styles.track} accessibilityRole="progressbar" accessibilityValue={{min: 0, max: 100, now: piper.progress}}>
          <View style={[styles.fill, {width: `${piper.progress}%`}]} />
        </View>
      )}
      {piper.status === 'not_downloaded' && (
        <TouchableOpacity
          style={styles.retry}
          onPress={piper.retry}
          accessibilityRole="button"
          accessibilityLabel="Piper-Modell laden">
          <Text style={styles.retryText}>{piper.error ? 'Erneut versuchen' : 'Jetzt laden'}</Text>
        </TouchableOpacity>
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
    sub: {color: c.text, fontSize: 14, fontWeight: '600', marginTop: 12},
    optionRow: {flexDirection: 'row', gap: 8, marginTop: 8},
    option: {
      flex: 1,
      minHeight: MIN_TOUCH,
      borderRadius: 24,
      borderWidth: 1.5,
      borderColor: c.border,
      alignItems: 'center',
      justifyContent: 'center',
      paddingHorizontal: 12,
    },
    optionSelected: {borderColor: c.accent, backgroundColor: c.accent},
    optionText: {color: c.text, fontSize: 14, fontWeight: '600'},
    optionTextSelected: {color: c.onAccent},
    status: {color: c.muted, fontSize: 13, lineHeight: 18, marginTop: 12},
    track: {height: 6, borderRadius: 3, backgroundColor: c.disabled, overflow: 'hidden', marginTop: 6},
    fill: {height: 6, borderRadius: 3, backgroundColor: c.ok},
    retry: {
      minHeight: MIN_TOUCH,
      borderRadius: 24,
      borderWidth: 1.5,
      borderColor: c.accent,
      alignItems: 'center',
      justifyContent: 'center',
      marginTop: 8,
    },
    retryText: {color: c.accent, fontSize: 15, fontWeight: '700'},
  });
}

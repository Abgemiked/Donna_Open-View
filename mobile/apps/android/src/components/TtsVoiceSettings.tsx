/**
 * TtsVoiceSettings (TB4) — serverseitige Stimm-/Modell-Wahl für Donnas Sprachausgabe (ElevenLabs).
 *
 * Sitzt im Einrichtungs-Bildschirm. Zeigt die aktuelle Stimme (Eva / Elisabeth) + das Modell
 * (v4 Turbo / v4 / Multilingual v2), liest den Stand beim Öffnen per GET /settings/tts und speichert
 * per POST /settings/tts (gilt ab der nächsten TTS-Synthese). Ersetzt das alte „Icon lange drücken".
 *
 * Idiom wie VoiceSettings: Karte + Pill-/Radio-Rows (kein Picker-Package), Theme-Tokens, 48px-Touch.
 */
import React, {useCallback, useEffect, useMemo, useState} from 'react';
import {StyleSheet, Text, TouchableOpacity, View} from 'react-native';
import {useTheme} from '../theme';
import type {Theme} from '../theme';
import {
  getTtsSettings,
  saveTtsSettings,
  TTS_MODELS,
  TTS_MODEL_DEFAULT,
  TTS_VOICES,
  TTS_VOICE_DEFAULT,
} from '@donna/shared';

const MIN_TOUCH = 48;

type Status = 'idle' | 'loading' | 'saving' | 'saved' | 'error' | 'loadError';

export default function TtsVoiceSettings(): React.JSX.Element {
  const c = useTheme();
  const styles = useMemo(() => makeStyles(c), [c]);
  const [voice, setVoice] = useState<string>(TTS_VOICE_DEFAULT);
  const [model, setModel] = useState<string>(TTS_MODEL_DEFAULT);
  const [status, setStatus] = useState<Status>('loading');
  // Zuletzt vom Server geladener/gespeicherter Stand (null = noch unbekannt). Steuert das Dirty-Gate:
  // solange unbekannt (z. B. Ladefehler), gilt jede Auswahl als speicherbar → kein Aussperren.
  const [savedVoice, setSavedVoice] = useState<string | null>(null);
  const [savedModel, setSavedModel] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    void getTtsSettings().then(s => {
      if (!alive) {
        return;
      }
      if (s) {
        setVoice(s.voice_id);
        setModel(s.model_id);
        setSavedVoice(s.voice_id);
        setSavedModel(s.model_id);
        setStatus('idle');
      } else {
        // Laden fehlgeschlagen (offline/401/5xx): Default-Auswahl bleibt sichtbar, aber als NICHT gespeichert
        // markiert (savedVoice/Model = null) → der Nutzer kann trotzdem speichern.
        setStatus('loadError');
      }
    });
    return () => {
      alive = false;
    };
  }, []);

  const busy = status === 'loading' || status === 'saving';
  const dirty = voice !== savedVoice || model !== savedModel;

  // Auswahl ändern: stale „Gespeichert"/„Fehlgeschlagen"-Status zurücksetzen.
  const clearStaleStatus = useCallback(
    () => setStatus(s => (s === 'saved' || s === 'error' ? 'idle' : s)),
    [],
  );
  const chooseVoice = useCallback(
    (id: string) => {
      setVoice(id);
      clearStaleStatus();
    },
    [clearStaleStatus],
  );
  const chooseModel = useCallback(
    (id: string) => {
      setModel(id);
      clearStaleStatus();
    },
    [clearStaleStatus],
  );

  const onSave = useCallback(() => {
    setStatus('saving');
    void saveTtsSettings(voice, model).then(ok => {
      if (ok) {
        setSavedVoice(voice);
        setSavedModel(model);
        setStatus('saved');
      } else {
        setStatus('error');
      }
    });
  }, [voice, model]);

  const statusLine =
    status === 'loading'
      ? 'Lade aktuelle Stimme …'
      : status === 'saving'
        ? 'Speichere …'
        : status === 'saved'
          ? 'Gespeichert — gilt ab der nächsten Antwort.'
          : status === 'error'
            ? 'Speichern fehlgeschlagen — bitte erneut versuchen.'
            : status === 'loadError'
              ? 'Aktuelle Stimme konnte nicht geladen werden — deine Auswahl wird beim Speichern gesetzt.'
              : 'Gilt für alle gesprochenen Antworten. Läuft das Guthaben leer, spricht automatisch die Ersatzstimme.';

  return (
    <View style={styles.box} testID="tts-settings">
      <Text style={styles.title} accessibilityRole="header">
        Stimme von Donna
      </Text>
      <Text style={styles.desc}>
        Wähle Donnas Stimme und Qualität. Die Einstellung liegt auf dem Server und gilt ab der nächsten Antwort (für alle Geräte).
      </Text>

      <Text style={styles.sub}>Stimme</Text>
      <View style={styles.optionRow}>
        {TTS_VOICES.map(v => {
          const selected = voice === v.id;
          return (
            <TouchableOpacity
              key={v.id}
              testID={`tts-voice-${v.label.toLowerCase()}`}
              style={[styles.option, selected && styles.optionSelected, busy && styles.optionDisabled]}
              onPress={() => chooseVoice(v.id)}
              disabled={busy}
              accessibilityRole="radio"
              accessibilityState={{selected}}
              accessibilityLabel={`Stimme ${v.label}`}>
              <Text style={[styles.optionText, selected && styles.optionTextSelected]}>{v.label}</Text>
            </TouchableOpacity>
          );
        })}
      </View>

      <Text style={styles.sub}>Modell</Text>
      <View style={styles.optionColumn}>
        {TTS_MODELS.map(m => {
          const selected = model === m.id;
          return (
            <TouchableOpacity
              key={m.id}
              testID={`tts-model-${m.id}`}
              style={[styles.optionWide, selected && styles.optionSelected, busy && styles.optionDisabled]}
              onPress={() => chooseModel(m.id)}
              disabled={busy}
              accessibilityRole="radio"
              accessibilityState={{selected}}
              accessibilityLabel={`Modell ${m.label}`}>
              <Text style={[styles.optionText, selected && styles.optionTextSelected]}>{m.label}</Text>
            </TouchableOpacity>
          );
        })}
      </View>

      <TouchableOpacity
        testID="tts-save"
        style={[styles.save, (!dirty || busy) && styles.saveDisabled]}
        onPress={onSave}
        disabled={!dirty || busy}
        accessibilityRole="button"
        accessibilityState={{disabled: !dirty || busy}}
        accessibilityLabel="Stimme speichern">
        <Text style={styles.saveText}>Speichern</Text>
      </TouchableOpacity>

      <Text
        style={[
          styles.status,
          (status === 'error' || status === 'loadError') && {color: c.warning},
          status === 'saved' && {color: c.ok},
        ]}
        accessibilityLiveRegion="polite"
        testID="tts-status">
        {statusLine}
      </Text>
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
    optionColumn: {flexDirection: 'column', gap: 8, marginTop: 8},
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
    optionWide: {
      minHeight: MIN_TOUCH,
      borderRadius: 24,
      borderWidth: 1.5,
      borderColor: c.border,
      alignItems: 'center',
      justifyContent: 'center',
      paddingHorizontal: 12,
    },
    optionSelected: {borderColor: c.accent, backgroundColor: c.accent},
    optionDisabled: {opacity: 0.5},
    optionText: {color: c.text, fontSize: 14, fontWeight: '600'},
    optionTextSelected: {color: c.onAccent},
    save: {
      minHeight: MIN_TOUCH,
      borderRadius: 24,
      backgroundColor: c.accent,
      alignItems: 'center',
      justifyContent: 'center',
      marginTop: 14,
    },
    saveDisabled: {opacity: 0.5},
    saveText: {color: c.onAccent, fontSize: 15, fontWeight: '700'},
    status: {color: c.muted, fontSize: 13, lineHeight: 18, marginTop: 10},
  });
}

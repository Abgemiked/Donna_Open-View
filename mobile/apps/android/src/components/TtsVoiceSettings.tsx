/**
 * TtsVoiceSettings (TB4) — serverseitige Stimm-/Modell-Wahl für Donnas Sprachausgabe (ElevenLabs).
 *
 * Sitzt im Einrichtungs-Bildschirm. Zeigt die aktuelle Stimme (Eva / Elisabeth) + das Modell
 * (v4 Turbo / v4 / Multilingual v2), liest den Stand beim Öffnen per GET /settings/tts und speichert
 * per POST /settings/tts (gilt ab der nächsten TTS-Synthese). Ersetzt das alte „Icon lange drücken".
 *
 * Darstellung (P6, Refs 134) über die Bausteine aus components/settings (Karte, Pillen), Logik unverändert.
 */
import React, {useCallback, useEffect, useMemo, useState} from 'react';
import {StyleSheet, Text, TouchableOpacity, View} from 'react-native';
import {FONT, FONT_FAMILY, RADIUS, SPACE, TOUCH_MIN, useTheme} from '../theme';
import {PillChoice, SettingsCard} from './settings';
import type {Theme} from '../theme';
import {
  getTtsSettings,
  saveTtsSettings,
  TTS_MODELS,
  TTS_MODEL_DEFAULT,
  TTS_VOICES,
  TTS_VOICE_DEFAULT,
} from '@donna/shared';


type Status = 'idle' | 'loading' | 'saving' | 'saved' | 'error' | 'loadError';

export interface TtsVoiceSettingsProps {
  /** Abschnittsname über der Karte. */
  cardTitle?: string;
}

export default function TtsVoiceSettings({cardTitle}: TtsVoiceSettingsProps = {}): React.JSX.Element {
  const c = useTheme();
  const styles = useMemo(() => makeStyles(c), [c]);
  const [voice, setVoice] = useState<string>(TTS_VOICE_DEFAULT);
  const [model, setModel] = useState<string>(TTS_MODEL_DEFAULT);
  const [status, setStatus] = useState<Status>('loading');
  // Zuletzt vom Server geladener/gespeicherter Stand (null = noch unbekannt). Steuert das Dirty-Gate:
  // solange unbekannt (z. B. Ladefehler), gilt jede Auswahl als speicherbar -> kein Aussperren.
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
        // markiert (savedVoice/Model = null) -> der Nutzer kann trotzdem speichern.
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
    <View testID="tts-settings">
      <SettingsCard title={cardTitle}>
        <View style={styles.group}>
          <Text style={styles.sub} accessibilityRole="header">
            Stimme von Donna
          </Text>
          <Text style={styles.desc}>
            Wähle Donnas Stimme und Qualität. Die Einstellung liegt auf dem Server und gilt ab der nächsten Antwort (für
            alle Geräte).
          </Text>
          <PillChoice
            disabled={busy}
            options={TTS_VOICES.map(v => ({
              value: v.id,
              label: v.label,
              testID: `tts-voice-${v.label.toLowerCase()}`,
              accessibilityLabel: `Stimme ${v.label}`,
            }))}
            value={voice}
            onChange={chooseVoice}
          />
        </View>

        <View style={styles.group}>
          <Text style={styles.sub}>Modell</Text>
          <PillChoice
            stacked
            disabled={busy}
            options={TTS_MODELS.map(m => ({
              value: m.id,
              label: m.label,
              testID: `tts-model-${m.id}`,
              accessibilityLabel: `Modell ${m.label}`,
            }))}
            value={model}
            onChange={chooseModel}
          />
        </View>

        <View style={styles.group}>
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
      </SettingsCard>
    </View>
  );
}

function makeStyles(c: Theme) {
  return StyleSheet.create({
    group: {gap: SPACE.sm},
    desc: {color: c.muted, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.label},
    sub: {color: c.text, fontFamily: FONT_FAMILY.sans.bold, fontSize: FONT.body},
    save: {
      minHeight: TOUCH_MIN,
      borderRadius: RADIUS.pill,
      backgroundColor: c.accent,
      alignItems: 'center',
      justifyContent: 'center',
    },
    saveDisabled: {opacity: 0.5},
    saveText: {color: c.onAccent, fontFamily: FONT_FAMILY.sans.bold, fontSize: FONT.body},
    status: {color: c.muted, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.label},
  });
}

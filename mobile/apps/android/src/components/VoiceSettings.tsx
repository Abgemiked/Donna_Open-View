/**
 * VoiceSettings — Einstellungen zu Stimme und Gespräch (Voice-Batch, A/B-Test Edge <-> Piper-lokal).
 *
 * Sitzt im Einstellungs-Bildschirm (#134 P6, Zahnrad im Chat). `section` wählt 'voice' (Sprechtempo, Engine,
 * Edge-Stimme, Piper-Modell), 'conversation' (Weiterhören, Sprechpause, Unterbrechen, Schwebende Blase) oder 'all'.
 * Darstellung über die Bausteine aus components/settings; Lade-/Speicherlogik und Brückenaufrufe unverändert:
 *  - Umschalter Edge (online, Backend-/tts) <-> Piper (lokal, on-device) — die jeweils andere Engine ist Fallback,
 *  - die Edge-Stimme (Katja / Seraphina, beide weiblich),
 *  - der EAGER-Download des Piper-Modells (de_DE-kerstin-low) mit sichtbarem Fortschritt und „Erneut versuchen".
 * Ohne natives Piper-Modul (Tests, andere Plattformen) rendert die Komponente nichts.
 */
import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {StyleSheet, Text, TouchableOpacity, View} from 'react-native';
import {FONT, FONT_FAMILY, RADIUS, SPACE, TOUCH_MIN, useTheme} from '../theme';
import type {Theme} from '../theme';
import {usePiperModel} from '../hooks/usePiperModel';
import {PillChoice, SettingSwitch, SettingsCard} from './settings';
import {
  BARGE_IN_SENSITIVITY_DEFAULT,
  BARGE_IN_SENSITIVITY_STEPS,
  EDGE_VOICES,
  EDGE_VOICE_DEFAULT,
  ENGINE_LABELS,
  FOLLOW_UP_WINDOW_DEFAULT,
  FOLLOW_UP_WINDOW_STEPS,
  SILENCE_PROFILE_DEFAULT,
  SILENCE_PROFILE_STEPS,
  SPEECH_RATE_DEFAULT,
  SPEECH_RATE_STEPS,
  TTS_ENGINE_DEFAULT,
  getEdgeVoice,
  FLOATING_BUBBLE_DEFAULT,
  getBargeInSensitivity,
  getFloatingBubble,
  getFollowUpWindow,
  getSilenceProfile,
  getSpeechRate,
  getTtsEngine,
  isPiperAvailable,
  setBargeInSensitivity,
  setEdgeVoice,
  setFloatingBubble,
  setFollowUpWindow,
  setSilenceProfile,
  setSpeechRate,
  setTtsEngine,
  type BargeInSensitivityKey,
  type SilenceProfileKey,
  type TtsEngine,
} from '../native/ttsEngine';
import {getVoiceBargeIn, setVoiceBargeIn, VOICE_BARGE_IN_DEFAULT} from '../native/voiceBargeIn';

export interface VoiceSettingsProps {
  section?: 'voice' | 'conversation' | 'all';
  /** Abschnittsname über der ersten Karte (z. B. "Stimme"). */
  cardTitle?: string;
}

export default function VoiceSettings({section = 'all', cardTitle}: VoiceSettingsProps = {}): React.JSX.Element | null {
  const c = useTheme();
  const styles = useMemo(() => makeStyles(c), [c]);
  const showVoice = section !== 'conversation';
  const showConversation = section !== 'voice';
  // Eager-Download nur dort, wo der Modellstatus gezeigt wird
  const piper = usePiperModel(showVoice);
  const [engine, setEngineState] = useState<TtsEngine>(TTS_ENGINE_DEFAULT);
  const [voice, setVoiceState] = useState<string>(EDGE_VOICE_DEFAULT);
  const [rate, setRateState] = useState<number>(SPEECH_RATE_DEFAULT);
  const rateRequest = useRef(0); // nur die Antwort der letzten Auswahl zählt
  const [windowS, setWindowState] = useState<number>(FOLLOW_UP_WINDOW_DEFAULT);
  const windowRequest = useRef(0); // nur die Antwort der letzten Auswahl zählt
  const [silence, setSilenceState] = useState<SilenceProfileKey>(SILENCE_PROFILE_DEFAULT);
  const silenceRequest = useRef(0);
  const [bargeIn, setBargeInState] = useState<boolean>(VOICE_BARGE_IN_DEFAULT);
  const bargeInRequest = useRef(0);
  const [sensitivity, setSensitivityState] = useState<BargeInSensitivityKey>(BARGE_IN_SENSITIVITY_DEFAULT);
  const sensitivityRequest = useRef(0);
  const [bubble, setBubbleState] = useState<boolean>(FLOATING_BUBBLE_DEFAULT);
  const bubbleRequest = useRef(0);

  useEffect(() => {
    let alive = true;
    void getTtsEngine().then(e => alive && setEngineState(e));
    void getEdgeVoice().then(v => alive && setVoiceState(v));
    void getSpeechRate().then(r => alive && setRateState(r));
    void getFollowUpWindow().then(w => alive && setWindowState(w));
    void getSilenceProfile().then(k => alive && setSilenceState(k));
    void getBargeInSensitivity().then(k => alive && setSensitivityState(k));
    void getVoiceBargeIn().then(b => alive && setBargeInState(b));
    void getFloatingBubble().then(b => alive && setBubbleState(b));
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

  const chooseRate = useCallback((value: number) => {
    setRateState(value); // sofort sichtbar; wirkt ab der nächsten Antwort
    const id = ++rateRequest.current;
    void setSpeechRate(value).then(applied => {
      if (id === rateRequest.current) {
        setRateState(applied); // veraltete Antworten (schnelles Doppeltippen) ignorieren
      }
    });
  }, []);

  const chooseWindow = useCallback((value: number) => {
    setWindowState(value); // sofort sichtbar; wirkt ab dem nächsten Weiterhören
    const id = ++windowRequest.current;
    void setFollowUpWindow(value).then(applied => {
      if (id === windowRequest.current) {
        setWindowState(applied); // veraltete Antworten (schnelles Doppeltippen) ignorieren
      }
    });
  }, []);

  const chooseSilence = useCallback((key: SilenceProfileKey) => {
    setSilenceState(key); // sofort sichtbar; wirkt ab dem nächsten Zuhören
    const id = ++silenceRequest.current;
    void setSilenceProfile(key).then(applied => {
      if (id === silenceRequest.current) {
        setSilenceState(applied); // veraltete Antworten (schnelles Doppeltippen) ignorieren
      }
    });
  }, []);

  const chooseSensitivity = useCallback((key: BargeInSensitivityKey) => {
    setSensitivityState(key); // sofort sichtbar; wirkt ab der nächsten Antwort
    const id = ++sensitivityRequest.current;
    void setBargeInSensitivity(key).then(applied => {
      if (id === sensitivityRequest.current) {
        setSensitivityState(applied); // veraltete Antworten (schnelles Doppeltippen) ignorieren
      }
    });
  }, []);

  const chooseBargeIn = useCallback((on: boolean) => {
    setBargeInState(on); // sofort sichtbar; die native Pref ist die einzige Quelle
    const id = ++bargeInRequest.current;
    void setVoiceBargeIn(on).then(applied => {
      if (id === bargeInRequest.current) {
        setBargeInState(applied);
      }
    });
  }, []);

  const chooseBubble = useCallback((on: boolean) => {
    setBubbleState(on); // sofort sichtbar; die native Pref ist die einzige Quelle
    const id = ++bubbleRequest.current;
    void setFloatingBubble(on).then(applied => {
      if (id === bubbleRequest.current) {
        setBubbleState(applied);
      }
    });
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
      {showVoice && (
        <SettingsCard title={cardTitle}>
          <View style={styles.group}>
            <Text style={styles.sub}>Sprechtempo</Text>
            <PillChoice
              options={SPEECH_RATE_STEPS.map(r => ({
                value: r.value,
                label: r.label,
                testID: `voice-rate-${r.value}`,
                accessibilityLabel: `Sprechtempo ${r.label}`,
              }))}
              value={rate}
              onChange={chooseRate}
            />
            <Text style={styles.desc}>Gilt ab der nächsten Antwort für alle Stimmen.</Text>
          </View>

          <View style={styles.group}>
            <Text style={styles.sub}>Ersatzstimme (A/B-Test)</Text>
            <Text style={styles.desc}>
              Vergleiche zwei weibliche Stimmen. Fällt die gewählte aus (kein Netz, Modell fehlt), springt automatisch
              die andere ein.
            </Text>
            <PillChoice
              options={(['edge', 'piper'] as TtsEngine[]).map(e => ({
                value: e,
                label: ENGINE_LABELS[e],
                testID: `voice-engine-${e}`,
                accessibilityLabel: `Stimme ${ENGINE_LABELS[e]}`,
              }))}
              value={engine}
              onChange={chooseEngine}
            />
            <Text style={styles.sub}>Edge-Stimme</Text>
            <PillChoice
              options={EDGE_VOICES.map(v => ({
                value: v.id,
                label: v.label,
                testID: `voice-edge-${v.label.toLowerCase()}`,
                accessibilityLabel: `Edge-Stimme ${v.label}`,
              }))}
              value={voice}
              onChange={chooseVoice}
            />
          </View>

          <View style={styles.group}>
            <Text
              style={[styles.status, piper.error != null && {color: c.warning}, piper.status === 'ready' && {color: c.ok}]}
              accessibilityLiveRegion="polite"
              testID="voice-piper-status">
              {piperLine}
            </Text>
            {piper.status === 'downloading' && (
              <View
                style={styles.track}
                accessibilityRole="progressbar"
                accessibilityValue={{min: 0, max: 100, now: piper.progress}}>
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
        </SettingsCard>
      )}

      {showConversation && (
        <SettingsCard title={showVoice ? 'Gespräch' : cardTitle}>
          <View style={styles.group}>
            <Text style={styles.sub}>Weiterhören nach der Antwort</Text>
            <PillChoice
              options={FOLLOW_UP_WINDOW_STEPS.map(w => ({
                value: w.seconds,
                label: w.label,
                testID: `voice-window-${w.seconds}`,
                accessibilityLabel: `Weiterhören nach der Antwort ${w.label}`,
              }))}
              value={windowS}
              onChange={chooseWindow}
            />
            <Text style={styles.desc}>
              So lange hört Donna nach ihrer Antwort weiter zu. Sagst du nichts, endet das Gespräch ruhig. Gilt ab dem
              nächsten Gespräch.
            </Text>
          </View>

          <View style={styles.group}>
            <Text style={styles.sub}>Sprechpause</Text>
            <PillChoice
              options={SILENCE_PROFILE_STEPS.map(p => ({
                value: p.key,
                label: p.label,
                testID: `voice-silence-${p.key}`,
                accessibilityLabel: `Sprechpause ${p.label}`,
              }))}
              value={silence}
              onChange={chooseSilence}
            />
            <Text style={styles.desc}>
              So lange darfst du beim Sprechen Luft holen, bevor Donna dich für fertig hält. „Lang“ ist am besten, wenn
              du beim Reden nachdenkst, „Kurz“ antwortet schneller. Gilt ab dem nächsten Zuhören.
            </Text>
          </View>

          <SettingSwitch
            testID="voice-bargein-switch"
            title="Unterbrechen per Stimme"
            hint="Du kannst Donna unterbrechen, indem du einfach reinredest. Mit Kopfhörern klappt das am zuverlässigsten. Gilt ab der nächsten Antwort."
            value={bargeIn}
            onChange={chooseBargeIn}
          />

          {bargeIn && (
            <View style={styles.group}>
              <Text style={styles.sub}>Empfindlichkeit</Text>
              <PillChoice
                options={BARGE_IN_SENSITIVITY_STEPS.map(p => ({
                  value: p.key,
                  label: p.label,
                  testID: `voice-sensitivity-${p.key}`,
                  accessibilityLabel: `Empfindlichkeit ${p.label}`,
                }))}
                value={sensitivity}
                onChange={chooseSensitivity}
              />
              <Text style={styles.desc}>
                Niedrig: nur deutliche, nahe Stimme unterbricht Donna. Mittel: normale Sprache unterbricht, Geräusche aus
                dem Nebenraum nicht. Hoch: schon leise Sprache unterbricht. Gilt ab der nächsten Antwort.
              </Text>
            </View>
          )}

          <SettingSwitch
            testID="voice-bubble-switch"
            title="Schwebende Blase"
            hint="Aus: Beim Start erscheint nur das Panel. An: Zusätzlich schwebt die Blase mit. Gilt ab dem nächsten Start."
            value={bubble}
            onChange={chooseBubble}
          />
        </SettingsCard>
      )}
    </View>
  );
}

function makeStyles(c: Theme) {
  return StyleSheet.create({
    box: {gap: SPACE.xl},
    group: {gap: SPACE.sm},
    desc: {color: c.muted, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.label},
    sub: {color: c.text, fontFamily: FONT_FAMILY.sans.bold, fontSize: FONT.body},
    status: {color: c.muted, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.label},
    track: {height: 6, borderRadius: 3, backgroundColor: c.disabled, overflow: 'hidden'},
    fill: {height: 6, borderRadius: 3, backgroundColor: c.ok},
    retry: {
      minHeight: TOUCH_MIN,
      borderRadius: RADIUS.pill,
      borderWidth: 1,
      borderColor: c.accent,
      alignItems: 'center',
      justifyContent: 'center',
    },
    retryText: {color: c.accent, fontFamily: FONT_FAMILY.sans.bold, fontSize: FONT.body},
  });
}

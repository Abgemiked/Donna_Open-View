/**
 * SettingsScreen (#134 P6) — eigener Einstellungs-Bildschirm, aus dem Einrichtungs-Overlay gelöst.
 *
 * Abschnitte: Darstellung (Hell / Dunkel / System über `setThemeChoice`, wirkt sofort), Stimme (Stimmenwahl,
 * Sprechtempo, Ersatzstimme), Gespräch (Weiterhören, Sprechpause, Unterbrechen, Schwebende Blase) und der Einstieg
 * „Einrichtung erneut öffnen". Die Lade-/Speicherlogik bleibt in VoiceSettings/TtsVoiceSettings.
 * Die Verdrahtung (Zahnrad im Chat öffnet diesen Bildschirm) liegt im Chat-Strang bzw. in der Zusammenführung.
 */
import React, {useEffect, useMemo} from 'react';
import {BackHandler, ScrollView, StatusBar, StyleSheet, Text, TouchableOpacity, View} from 'react-native';
import {useSafeAreaInsets} from 'react-native-safe-area-context';
import {FONT, FONT_FAMILY, RADIUS, SPACE, TOUCH_MIN, useTheme, useThemeChoice} from '../theme';
import {Icon} from '../components/Icon';
import {showSetupIncompleteEntry} from '../utils/overlayNav';
import type {Theme, ThemeChoice} from '../theme';
import {PillChoice, SettingsCard, SettingsHeader, SettingsLink} from '../components/settings';
import VoiceSettings from '../components/VoiceSettings';
import TtsVoiceSettings from '../components/TtsVoiceSettings';

export interface SettingsScreenProps {
  /** Einstellungen verlassen (Zurück-Taste und Hardware-Zurück). */
  onClose: () => void;
  /** Einrichtung (OnboardingScreen, mode="rerun") öffnen. */
  onOpenSetup: () => void;
  /** Eine Pflicht-Berechtigung fehlt: ganz oben erscheint der Eintrag "Einrichtung unvollständig". */
  setupIncomplete?: boolean;
}

const THEME_OPTIONS: {value: ThemeChoice; label: string}[] = [
  {value: 'light', label: 'Hell'},
  {value: 'dark', label: 'Dunkel'},
  {value: 'system', label: 'System'},
];

export default function SettingsScreen({
  onClose,
  onOpenSetup,
  setupIncomplete = false,
}: SettingsScreenProps): React.JSX.Element {
  const c = useTheme();
  const styles = useMemo(() => makeStyles(c), [c]);
  const insets = useSafeAreaInsets();
  const {choice, setThemeChoice} = useThemeChoice();

  useEffect(() => {
    const handler = BackHandler.addEventListener('hardwareBackPress', () => {
      onClose();
      return true;
    });
    return () => handler.remove();
  }, [onClose]);

  return (
    <View style={styles.root} testID="settings-screen">
      <StatusBar
        barStyle={c.scheme === 'light' ? 'dark-content' : 'light-content'}
        backgroundColor="transparent"
        translucent
      />
      <SettingsHeader title="Einstellungen" onBack={onClose} topInset={insets.top} />
      <ScrollView
        style={styles.scroll}
        contentContainerStyle={[styles.content, {paddingBottom: insets.bottom + SPACE.xl}]}
        keyboardShouldPersistTaps="handled">
        {showSetupIncompleteEntry(setupIncomplete) && (
          <TouchableOpacity
            testID="settings-setup-incomplete"
            style={styles.incomplete}
            onPress={onOpenSetup}
            accessibilityRole="button"
            accessibilityLabel="Einrichtung unvollständig"
            accessibilityHint="Es fehlt eine nötige Berechtigung. Tippen, um die Einrichtung zu öffnen.">
            <Icon name="alert" size={24} color={c.warning as string} />
            <View style={styles.incompleteTexts}>
              <Text style={styles.incompleteTitle}>Einrichtung unvollständig</Text>
              <Text style={styles.desc}>Eine nötige Berechtigung fehlt. Tippen zum Nachholen.</Text>
            </View>
            <Icon name="chevronRight" size={24} color={c.muted as string} />
          </TouchableOpacity>
        )}

        <SettingsCard title="Darstellung" testID="settings-appearance">
          <View style={styles.group}>
            <PillChoice
              options={THEME_OPTIONS.map(o => ({
                value: o.value,
                label: o.label,
                testID: `settings-theme-${o.value}`,
                accessibilityLabel: `Darstellung ${o.label}`,
              }))}
              value={choice}
              onChange={next => void setThemeChoice(next)}
            />
            <Text style={styles.desc}>Gilt auch für das Overlay ab dem nächsten Start.</Text>
          </View>
        </SettingsCard>

        <View style={styles.stack}>
          <TtsVoiceSettings cardTitle="Stimme" />
          {/* einmal gemountet (Karten Sprechtempo/Ersatzstimme und "Gespräch"): jede Preference wird nur einmal gelesen */}
          <VoiceSettings section="all" />
        </View>

        <SettingsCard title="Einrichtung">
          <SettingsLink
            testID="settings-open-setup"
            title="Einrichtung erneut öffnen"
            hint="Berechtigungen prüfen und nachholen."
            onPress={onOpenSetup}
          />
        </SettingsCard>
      </ScrollView>
    </View>
  );
}

function makeStyles(c: Theme) {
  return StyleSheet.create({
    root: {flex: 1, backgroundColor: c.bg},
    scroll: {flex: 1},
    content: {paddingHorizontal: SPACE.lg, gap: SPACE.xl},
    stack: {gap: SPACE.md},
    incomplete: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: SPACE.md,
      minHeight: TOUCH_MIN,
      padding: SPACE.gutter,
      borderRadius: RADIUS.card,
      backgroundColor: c.card,
      borderWidth: 2,
      borderColor: c.warning,
    },
    incompleteTexts: {flex: 1, gap: SPACE.xs},
    incompleteTitle: {color: c.text, fontFamily: FONT_FAMILY.sans.bold, fontSize: FONT.body},
    group: {gap: SPACE.sm},
    desc: {color: c.muted, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.label},
  });
}

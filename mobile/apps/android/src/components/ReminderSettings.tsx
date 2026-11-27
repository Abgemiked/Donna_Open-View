/**
 * ReminderSettings (#153) — Abschnitt "Erinnerungen" im Einstellungs-Bildschirm.
 *
 * Erinnerungen vom Server kommen immer als Benachrichtigung und, bei gesperrtem Handy, als Popup mit "Erledigt" und
 * "Später". Hier: der Schalter "Darf Nicht stören durchbrechen" (Standard aus; braucht zusätzlich die Systemfreigabe
 * für den Kanal) und der Hinweis auf die Sonderberechtigung für das Popup. Ohne native Brücke rendert die Komponente nichts.
 */
import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {AppState, StyleSheet, Text, View} from 'react-native';
import {FONT, FONT_FAMILY, SPACE, useTheme} from '../theme';
import type {Theme} from '../theme';
import {SettingSwitch, SettingsCard, SettingsLink} from './settings';
import {
  getReminderState,
  isRemindersAvailable,
  openFullScreenSettings,
  openReminderChannelSettings,
  setReminderDndBypass,
} from '../native/reminders';
import type {ReminderState} from '../native/reminders';

// fullScreenAllowed startet true: der Hinweis erscheint erst, wenn das Gerät "nicht erlaubt" meldet
const INITIAL: ReminderState = {dndBypass: false, dndGranted: false, fullScreenAllowed: true};

export default function ReminderSettings(): React.JSX.Element | null {
  const c = useTheme();
  const styles = useMemo(() => makeStyles(c), [c]);
  const [state, setState] = useState<ReminderState>(INITIAL);
  const request = useRef(0); // nur die Antwort der letzten Auswahl zählt

  useEffect(() => {
    let alive = true;
    void getReminderState().then(s => {
      if (alive && request.current === 0) {
        setState(s);
      }
    });
    // Rückkehr aus den Systemeinstellungen: Freigaben neu lesen (sonst bliebe ein veralteter Hinweis stehen)
    const sub = AppState.addEventListener('change', next => {
      if (next !== 'active') {
        return;
      }
      const seen = request.current;
      void getReminderState().then(s => {
        if (alive && request.current === seen) {
          setState(s);
        }
      });
    });
    return () => {
      alive = false;
      sub?.remove?.();
    };
  }, []);

  const choose = useCallback((value: boolean) => {
    setState(prev => ({...prev, dndBypass: value}));
    const id = ++request.current;
    void setReminderDndBypass(value).then(next => {
      if (id === request.current) {
        // Die Antwort der Brücke ist die Wahrheit für den Schalter; ihre Vollbild-Angabe ersetzt die alte nur, wenn sie "erlaubt" sagt
        setState(prev => ({...next, fullScreenAllowed: next.fullScreenAllowed || prev.fullScreenAllowed}));
      }
    });
  }, []);

  if (!isRemindersAvailable()) {
    return null;
  }

  return (
    <View testID="reminder-settings">
      <SettingsCard title="Erinnerungen">
        <Text style={styles.desc}>
          Erinnerungen von Donna kommen immer als Benachrichtigung mit „Erledigt“ und „Später“. Bei gesperrtem Handy
          erscheint zusätzlich ein Popup, ohne dass du entsperren musst, sofern Android es erlaubt (siehe unten).
        </Text>
        <SettingSwitch
          testID="reminder-dnd-bypass"
          title="Darf „Nicht stören“ durchbrechen"
          hint="Standardmäßig aus."
          value={state.dndBypass}
          onChange={choose}
        />
        <Text testID="reminder-dnd-hint" style={styles.desc}>
          Der Schalter legt einen eigenen Benachrichtigungskanal für Erinnerungen an. Ob Android ihn bei „Nicht stören“
          wirklich durchlässt, bestimmst du in den Systemeinstellungen dieses Kanals; die Systemfreigabe kann Donna nicht
          selbst erteilen. Schaltest du aus, wird der Kanal wieder entfernt.
        </Text>
        {state.dndBypass && !state.dndGranted && (
          <SettingsLink
            testID="reminder-dnd-grant"
            title="Kanal in den Systemeinstellungen freigeben"
            hint="Noch nicht freigegeben: bei „Nicht stören“ bleiben Erinnerungen stumm."
            onPress={() => void openReminderChannelSettings()}
          />
        )}
        {!state.fullScreenAllowed && (
          <SettingsLink
            testID="reminder-fullscreen-grant"
            title="Popup über dem Sperrbildschirm erlauben"
            hint="Android braucht dafür deine Freigabe für Vollbild-Benachrichtigungen. Ohne sie bleibt es bei der normalen Benachrichtigung."
            onPress={() => void openFullScreenSettings()}
          />
        )}
        {state.fullScreenAllowed && (
          <SettingsLink
            testID="reminder-fullscreen-check"
            title="Vollbild-Berechtigung prüfen"
            hint="Öffnet die Systemseite: dort siehst du, ob das Popup über dem Sperrbildschirm erlaubt ist."
            onPress={() => void openFullScreenSettings()}
          />
        )}
      </SettingsCard>
    </View>
  );
}

function makeStyles(c: Theme) {
  return StyleSheet.create({
    desc: {color: c.muted, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.label, paddingBottom: SPACE.xs},
  });
}

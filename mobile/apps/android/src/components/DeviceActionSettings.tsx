/**
 * DeviceActionSettings (#147 Paket A) — Abschnitt „Donna darf selbst …" im Einstellungs-Bildschirm.
 *
 * Je Aktionsart ein Schalter (Taschenlampe, Lautstärke, Apps, Einstellungen, Wecker/Timer, Helligkeit, Nicht stören,
 * Sperren, Screenshot, Kamera). Standard: alle aus. Bei Arten mit Sonderrecht (#147 Paket F) zeigt eine Zeile den Status
 * und, wenn es fehlt, einen Knopf zur passenden Systemseite (nur auf Tipp). Die native Pref ist die einzige Quelle; die
 * eingeschalteten Arten gehen mit jeder
 * Anfrage ans Backend. Ohne native Brücke (Tests, andere Plattformen) rendert die Komponente nichts.
 */
import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {AppState, StyleSheet, Text, TouchableOpacity, View} from 'react-native';
import {FONT, FONT_FAMILY, SPACE, useTheme} from '../theme';
import type {Theme} from '../theme';
import {SettingSwitch, SettingsCard} from './settings';
import {
  DEVICE_ACTION_DEFAULT,
  DEVICE_ACTION_KINDS,
  DEVICE_ACTION_PERMISSIONS,
  getDevicePermissionState,
  getDeviceActionKinds,
  isDeviceActionsAvailable,
  openDevicePermissionSettings,
  requestScreenshotPreviewPermission,
  setDeviceActionKind,
} from '../native/deviceActions';

export default function DeviceActionSettings(): React.JSX.Element | null {
  const c = useTheme();
  const styles = useMemo(() => makeStyles(c), [c]);
  const [on, setOn] = useState<Record<string, boolean>>({});
  const requests = useRef<Record<string, number>>({}); // nur die Antwort der letzten Auswahl je Art zählt
  const [perm, setPerm] = useState<Record<string, string>>({}); // Status des Sonderrechts je Art
  const [openFailed, setOpenFailed] = useState<Record<string, boolean>>({}); // Systemseite ging nicht auf -> Hinweistext

  // Status lesen (beim Öffnen und wenn Mike aus den Systemeinstellungen zurückkommt). Nur lesen, nichts erteilen.
  useEffect(() => {
    let alive = true;
    const load = () => {
      for (const key of Object.keys(DEVICE_ACTION_PERMISSIONS)) {
        void getDevicePermissionState(key).then(s => {
          if (alive) {
            setPerm(prev => ({...prev, [key]: s}));
          }
        });
      }
    };
    load();
    const sub = AppState.addEventListener('change', next => {
      if (next === 'active') {
        load();
      }
    });
    return () => {
      alive = false;
      sub.remove();
    };
  }, []);

  useEffect(() => {
    let alive = true;
    void getDeviceActionKinds().then(keys => {
      if (alive) {
        // Eine Umschaltung vor der Ladeantwort (neuer als die gelesene Pref) bleibt erhalten
        setOn(prev => {
          const next: Record<string, boolean> = Object.fromEntries(keys.map(k => [k, true]));
          for (const k of Object.keys(requests.current)) {
            if (k in prev) {
              next[k] = prev[k];
            }
          }
          return next;
        });
      }
    });
    return () => {
      alive = false;
    };
  }, []);

  const choose = useCallback((key: string, value: boolean) => {
    setOn(prev => ({...prev, [key]: value})); // sofort sichtbar; nativ bestätigt die Antwort
    if (key === 'screenshot' && value) {
      void requestScreenshotPreviewPermission(); // #219: nur für die Vorschau; abgelehnt = Screenshot ohne Vorschau
    }
    const id = (requests.current[key] = (requests.current[key] ?? 0) + 1);
    void setDeviceActionKind(key, value).then(applied => {
      if (id === requests.current[key]) {
        setOn(prev => ({...prev, [key]: applied}));
      }
    });
  }, []);

  if (!isDeviceActionsAvailable()) {
    return null;
  }

  return (
    <View testID="device-action-settings">
      <SettingsCard title="Donna darf selbst …">
        <Text style={styles.desc}>
          Eingeschaltet erledigt Donna das, ohne dass du antippen musst. Alles ist standardmäßig aus. Nachrichten,
          Anrufe, Zahlungen und Smart Home bleiben davon unberührt.
        </Text>
        {DEVICE_ACTION_KINDS.map(k => (
          <View key={k.key}>
            <SettingSwitch
              testID={`device-action-${k.key}`}
              title={k.label}
              hint={k.hint}
              value={on[k.key] ?? DEVICE_ACTION_DEFAULT}
              onChange={v => choose(k.key, v)}
            />
            {k.key in DEVICE_ACTION_PERMISSIONS && (perm[k.key] === 'granted' || perm[k.key] === 'missing') ? (
              <View style={styles.permRow} testID={`device-action-perm-${k.key}`}>
                <Text style={styles.permText}>
                  {DEVICE_ACTION_PERMISSIONS[k.key]}: {perm[k.key] === 'granted' ? 'erteilt' : 'fehlt noch'}
                </Text>
                {perm[k.key] === 'missing' ? (
                  <TouchableOpacity
                    accessibilityRole="button"
                    accessibilityLabel={`${DEVICE_ACTION_PERMISSIONS[k.key]} in den Systemeinstellungen öffnen`}
                    testID={`device-action-perm-open-${k.key}`}
                    onPress={() => {
                      void openDevicePermissionSettings(k.key).then(opened => setOpenFailed(prev => ({...prev, [k.key]: !opened})));
                    }}
                    style={styles.permButton}>
                    <Text style={styles.permButtonText}>Einstellungen öffnen</Text>
                  </TouchableOpacity>
                ) : null}
              </View>
            ) : null}
            {openFailed[k.key] ? (
              <Text style={styles.permText} testID={`device-action-perm-hint-${k.key}`}>
                Die Systemseite ließ sich nicht öffnen. Öffne sie bitte selbst in den Android-Einstellungen.
              </Text>
            ) : null}
          </View>
        ))}
      </SettingsCard>
    </View>
  );
}

function makeStyles(c: Theme) {
  return StyleSheet.create({
    desc: {color: c.muted, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.label, paddingBottom: SPACE.xs},
    permRow: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingBottom: SPACE.xs},
    permText: {flex: 1, color: c.muted, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.label},
    permButton: {paddingVertical: SPACE.xs, paddingHorizontal: SPACE.sm, minHeight: 44, justifyContent: 'center'},
    permButtonText: {color: c.text, fontFamily: FONT_FAMILY.sans.semibold, fontSize: FONT.label, textDecorationLine: 'underline'},
  });
}

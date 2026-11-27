/**
 * RoutineSettings (#147 G2) — Abschnitt "Routinen" im Einstellungs-Bildschirm.
 *
 * Zeigt Donnas gespeicherte Routinen (Name, Schritte), "Starten" (Tipp-Auslöser: derselbe Satz wie gesprochen, der Server
 * führt die festen Schritte ohne Modell aus) und "Löschen" (mit Rückfrage), dazu die letzten Änderungen. Anlegen und
 * Ändern geht nur per Sprache mit Bestätigungskarte im Chat. Die Aktionsarten-Schalter und Sperren gelten bei jedem Lauf.
 */
import React, {useCallback, useEffect, useMemo, useState} from 'react';
import {Alert, StyleSheet, Text, TouchableOpacity, View} from 'react-native';
import * as shared from '@donna/shared';
import {FONT, FONT_FAMILY, RADIUS, SPACE, TOUCH_MIN, useTheme} from '../theme';
import type {Theme} from '../theme';
import {SettingsCard} from './settings';

type Row = {id: number; name: string; steps: string[]};
type Change = {ts: string; op: string; name: string; steps: number};

const OP: Record<string, string> = {create: 'angelegt', update: 'geändert', delete: 'gelöscht'};

export default function RoutineSettings({onRun}: {onRun?: (name: string) => void}): React.JSX.Element {
  const c = useTheme();
  const styles = useMemo(() => makeStyles(c), [c]);
  const [rows, setRows] = useState<Row[] | null>(null);
  const [log, setLog] = useState<Change[]>([]);
  const [max, setMax] = useState(20);

  const load = useCallback(async () => {
    try {
      const list = await shared.fetchRoutines?.();
      if (!list) { setRows(null); return; }
      setRows(list.routines);
      setLog(list.changelog.slice(0, 5));
      setMax(list.max_routines);
    } catch {
      setRows(null);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const remove = useCallback((r: Row) => {
    Alert.alert(`Routine „${r.name}“ löschen?`, 'Das lässt sich nicht rückgängig machen.', [
      {text: 'Abbrechen', style: 'cancel'},
      {
        text: 'Löschen',
        style: 'destructive',
        onPress: () => { void shared.deleteRoutine(r.id).then(() => load()); },
      },
    ]);
  }, [load]);

  return (
    <View testID="routine-settings">
      <SettingsCard title="Routinen">
        <Text style={styles.desc}>
          Eine Routine ist eine feste Abfolge von bis zu 8 Schritten, z. B. „Streammodus“. Sag „Leg eine Routine
          Schlafenszeit an: …“, dann zeigt Donna alle Schritte zur Bestätigung. Jeder Schritt braucht weiter den
          Schalter seiner Aktionsart; gesperrte Schritte werden ausgelassen.
        </Text>
        {rows === null ? (
          <Text testID="routine-unavailable" style={styles.desc}>Die Routinen sind gerade nicht erreichbar.</Text>
        ) : rows.length === 0 ? (
          <Text testID="routine-empty" style={styles.desc}>Noch keine Routine (höchstens {max}).</Text>
        ) : (
          rows.map(r => (
            <View key={r.id} style={styles.row} testID={`routine-row-${r.id}`}>
              <Text style={styles.name}>{r.name}</Text>
              {r.steps.map((s, i) => (
                <Text key={`${r.id}-${i}`} style={styles.step}>{`${i + 1}. ${s}`}</Text>
              ))}
              <View style={styles.btns}>
                {onRun ? (
                  <TouchableOpacity
                    testID={`routine-run-${r.id}`}
                    style={styles.btn}
                    accessibilityRole="button"
                    onPress={() => onRun(r.name)}>
                    <Text style={styles.btnText}>Starten</Text>
                  </TouchableOpacity>
                ) : null}
                <TouchableOpacity
                  testID={`routine-delete-${r.id}`}
                  style={styles.btn}
                  accessibilityRole="button"
                  onPress={() => remove(r)}>
                  <Text style={styles.btnText}>Löschen</Text>
                </TouchableOpacity>
              </View>
            </View>
          ))
        )}
        {log.length > 0 ? (
          <View testID="routine-log">
            <Text style={styles.desc}>Letzte Änderungen</Text>
            {log.map((l, i) => (
              <Text key={`${l.ts}-${i}`} style={styles.step}>{`${l.name} ${OP[l.op] ?? l.op}`}</Text>
            ))}
          </View>
        ) : null}
      </SettingsCard>
    </View>
  );
}

function makeStyles(c: Theme) {
  return StyleSheet.create({
    desc: {color: c.muted, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.label},
    row: {gap: SPACE.xs, paddingVertical: SPACE.sm},
    name: {color: c.text, fontFamily: FONT_FAMILY.sans.bold, fontSize: FONT.body},
    step: {color: c.text, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.label},
    btns: {flexDirection: 'row', gap: SPACE.sm, marginTop: SPACE.xs},
    btn: {
      minHeight: TOUCH_MIN,
      paddingHorizontal: SPACE.lg,
      justifyContent: 'center',
      borderRadius: RADIUS.card,
      borderWidth: 1,
      borderColor: c.muted,
    },
    btnText: {color: c.text, fontFamily: FONT_FAMILY.sans.bold, fontSize: FONT.label},
  });
}

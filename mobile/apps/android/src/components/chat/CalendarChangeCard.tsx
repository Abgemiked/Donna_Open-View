import React, {useCallback, useEffect, useRef, useState} from 'react';
import {PermissionsAndroid, Text, TouchableOpacity, View} from 'react-native';
import type {DonnaAction} from '@donna/shared';
import {useThemedStyles} from '../../theme';
import {makeCardStyles} from '../../screens/cardStyles';
import {
  applyCalendarChange, openCalendarPermissionSettings, planCalendarChange, planSelectedCalendarChange,
  type CalendarCandidate, type CalendarPlan,
} from '../../native/calendarEdit';

/**
 * #147 Paket H: Terminkarte (Stufe 2). Das Gerät sucht den Termin, die Karte zeigt Vorher/Nachher bzw. den zu löschenden
 * Termin; erst der Tipp auf die Hauptaktion schreibt. Mehrere Treffer: Auswahl. Serie: nur "dieser Termin"; die ganze
 * Serie nur nach einer zweiten, ausdrücklichen Bestätigung. Löschen sagt, dass es nicht rückgängig geht. Fehlende
 * Berechtigung: Hinweis mit Knopf (nie still). Ohne Tipp ändert sich nichts.
 */
type View_ =
  | {k: 'loading'}
  | {k: 'plan'; plan: CalendarPlan}
  | {k: 'series'; plan: Extract<CalendarPlan, {status: 'ready'}>}
  | {k: 'busy'}
  | {k: 'done'; text: string}
  | {k: 'cancelled'};

// Überlebt ein erneutes Einblenden der Karte (Scrollen), nicht den App-Neustart (dann prüft das Gerät erneut).
const finished = new Map<string, View_>();

type BtnStyles = ReturnType<typeof makeCardStyles>;
function Btn({s, label, primary, onPress, a11y}: {s: BtnStyles; label: string; primary?: boolean; onPress: () => void; a11y?: string}): React.JSX.Element {
  return (
    <TouchableOpacity onPress={onPress} accessibilityRole="button" accessibilityLabel={a11y} style={[s.btn, primary && s.btnPrimary]}>
      <Text style={primary ? s.btnTextPrimary : s.btnText}>{label}</Text>
    </TouchableOpacity>
  );
}

const MAX_FINISHED = 50;
const OP_LABEL = {move: 'Verschieben', rename: 'Umbenennen', delete: 'Löschen'} as const;
const OP_TITLE = {move: 'Termin verschieben', rename: 'Termin umbenennen', delete: 'Termin löschen'} as const;

export function CalendarChangeCard({action}: {action: DonnaAction}): React.JSX.Element {
  const s = useThemedStyles(makeCardStyles);
  const key = String((action as Record<string, unknown>).card_id ?? (action as Record<string, unknown>).action_id ?? '');
  const rawState = (action as Record<string, unknown>).card_state;
  const closed = rawState === 'expired' || rawState === 'rejected' || rawState === 'done' ? rawState : undefined;   // 'open' = offen
  const busy = useRef(false);   // Doppeltipp: nur ein Schreibvorgang
  const [view, setView] = useState<View_>(() => (key && finished.get(key)) || {k: 'loading'});

  const set = useCallback((v: View_) => {
    if (key && (v.k === 'done' || v.k === 'cancelled')) {
      if (finished.size >= MAX_FINISHED) { finished.delete(finished.keys().next().value as string); }
      finished.set(key, v);
    }
    setView(v);
  }, [key]);

  const plan = useCallback(async () => {
    setView({k: 'loading'});
    try {
      setView({k: 'plan', plan: await planCalendarChange(action)});
    } catch {
      setView({k: 'plan', plan: {status: 'failed', message: 'Das hat nicht geklappt — ich habe nichts bestätigt.'}});
    }
  }, [action]);

  useEffect(() => {
    if (view.k === 'loading' && !closed) {
      plan();
    }
    // nur beim ersten Anzeigen suchen (lesen, nichts schreiben)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (closed === 'expired' || closed === 'rejected' || closed === 'done') {
    const label = closed === 'expired' ? 'Abgelaufen' : closed === 'done' ? 'Erledigt' : 'Abgelehnt';
    return (
      <View style={s.cardOuter}><View style={s.cardInner}>
        <Text style={s.fieldLabel}>Termin ändern</Text>
        <Text style={s.fieldValue}>{label}</Text>
      </View></View>
    );
  }

  const grantAndRetry = async () => {
    try {
      const r = await PermissionsAndroid.requestMultiple([
        PermissionsAndroid.PERMISSIONS.READ_CALENDAR, PermissionsAndroid.PERMISSIONS.WRITE_CALENDAR,
      ]);
      const ok = Object.values(r).every(v => v === PermissionsAndroid.RESULTS.GRANTED);
      if (ok) {
        await plan();
      } else {
        await openCalendarPermissionSettings();   // nur nach Tipp auf den Knopf, nie von selbst
      }
    } catch {
      await openCalendarPermissionSettings();
    }
  };

  const confirm = async (p: Extract<CalendarPlan, {status: 'ready'}>, scope: 'instance' | 'series') => {
    if (busy.current) { return; }
    busy.current = true;
    setView({k: 'busy'});
    let r: CalendarPlan;
    try {
      r = await applyCalendarChange(action, p, scope, scope === 'series');
    } catch {
      r = {status: 'failed', message: 'Das hat nicht geklappt — ich habe nichts bestätigt.'};
    }
    busy.current = false;
    if (r.status === 'ok') {
      set({k: 'done', text: r.message});
    } else {
      setView({k: 'plan', plan: r});
    }
  };

  const choose = async (c: CalendarCandidate) => {
    setView({k: 'loading'});
    try {
      setView({k: 'plan', plan: await planSelectedCalendarChange(action, c)});
    } catch {
      setView({k: 'plan', plan: {status: 'failed', message: 'Das hat nicht geklappt — ich habe nichts bestätigt.'}});
    }
  };

  if (view.k === 'loading' || view.k === 'busy') {
    return (
      <View style={s.cardOuter}><View style={s.cardInner}>
        <Text style={s.fieldLabel}>Kalender</Text>
        <Text accessibilityLiveRegion="polite" style={s.fieldValue}>{view.k === 'busy' ? 'Wird geändert …' : 'Ich suche den Termin …'}</Text>
      </View></View>
    );
  }
  if (view.k === 'done') {
    return (
      <View style={s.cardOuter}><View style={s.cardInner}>
        <Text style={s.fieldLabel}>Kalender</Text>
        <Text accessibilityLiveRegion="polite" style={s.fieldValue}>{view.text}</Text>
      </View></View>
    );
  }
  if (view.k === 'cancelled') {
    return (
      <View style={s.cardOuter}><View style={s.cardInner}>
        <Text style={s.fieldLabel}>Kalender</Text>
        <Text style={s.fieldValue}>Nichts geändert.</Text>
      </View></View>
    );
  }
  if (view.k === 'series') {
    const p = view.plan;
    return (
      <View style={s.cardOuter}>
        <View style={s.cardInner}>
          <Text style={s.fieldLabel}>Ganze Serie</Text>
          <Text style={s.fieldValue}>{p.before.title}</Text>
          <Text style={s.warnText}>
            {p.op === 'delete'
              ? 'Wirklich die GANZE Serie löschen? Alle Termine der Serie sind weg. Das lässt sich nicht rückgängig machen.'
              : 'Wirklich die GANZE Serie umbenennen? Das betrifft alle Termine der Serie.'}
          </Text>
        </View>
        <View style={s.btnRow}>
          <Btn s={s} primary label={p.op === 'delete' ? 'Ja, ganze Serie löschen' : 'Ja, ganze Serie umbenennen'}
            onPress={() => confirm(p, 'series')} />
          <Btn s={s} label="Zurück" onPress={() => setView({k: 'plan', plan: p})} />
        </View>
      </View>
    );
  }

  const p = view.plan;
  if (p.status === 'ready') {
    return (
      <View style={s.cardOuter}>
        <View style={s.cardInner}>
          <Text style={s.fieldLabel}>{OP_TITLE[p.op]}</Text>
          <Text style={s.fieldLabel}>{p.op === 'delete' ? 'Dieser Termin' : 'Vorher'}</Text>
          <Text style={s.fieldValue}>{p.before.title}</Text>
          <Text style={s.fieldLabel}>{p.before.when}</Text>
          {p.op !== 'delete' ? (
            <>
              <View style={s.divider} />
              <Text style={s.fieldLabel}>Nachher</Text>
              <Text style={s.fieldValue}>{p.after.title}</Text>
              <Text style={s.fieldLabel}>{p.after.when}</Text>
            </>
          ) : null}
          {p.recurring ? <Text style={s.warnText}>Das ist ein Serientermin — geändert wird nur dieser eine Termin.</Text> : null}
          {p.irreversible ? <Text style={s.warnText}>{p.warning ?? 'Löschen kann Donna nicht rückgängig machen.'}</Text> : null}
        </View>
        <View style={s.btnRow}>
          <Btn s={s} primary label={p.recurring ? `${OP_LABEL[p.op]}: nur dieser Termin` : OP_LABEL[p.op]} onPress={() => confirm(p, 'instance')} />
          {p.recurring && p.seriesAllowed ? <Btn s={s} label="Ganze Serie …" onPress={() => setView({k: 'series', plan: p})} /> : null}
          <Btn s={s} label="Abbrechen" onPress={() => set({k: 'cancelled'})} />
        </View>
      </View>
    );
  }
  if (p.status === 'choose') {
    return (
      <View style={s.cardOuter}>
        <View style={s.cardInner}>
          <Text style={s.fieldLabel}>Welchen Termin meinst du?</Text>
          {p.candidates.map((c, i) => (
            <View key={`${c.eventId}-${c.beginMs}`}>
              {i > 0 ? <View style={s.divider} /> : null}
              <Text style={s.fieldValue}>{c.title}</Text>
              <Text style={s.fieldLabel}>{c.own ? c.when : `${c.when} · gehört jemand anderem — den ändere ich nicht`}</Text>
              {c.own ? <Btn s={s} label="Diesen" a11y={`Diesen: ${c.title}, ${c.when}`} onPress={() => choose(c)} /> : null}
            </View>
          ))}
        </View>
        <View style={s.btnRow}>
          <Btn s={s} label="Abbrechen" onPress={() => set({k: 'cancelled'})} />
        </View>
      </View>
    );
  }
  // none | refused | needs_permission | rejected | disabled | failed | ok
  return (
    <View style={s.cardOuter}>
      <View style={s.cardInner}>
        <Text style={s.fieldLabel}>Kalender</Text>
        <Text accessibilityLiveRegion="polite" style={s.fieldValue}>{p.message}</Text>
      </View>
      <View style={s.btnRow}>
        {p.status === 'needs_permission' ? <Btn s={s} primary label="Berechtigung erteilen" onPress={grantAndRetry} /> : null}
        <Btn s={s} label="Schließen" onPress={() => set({k: 'cancelled'})} />
      </View>
    </View>
  );
}

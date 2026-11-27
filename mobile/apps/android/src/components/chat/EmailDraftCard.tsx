import React, {useCallback, useRef, useState} from 'react';
import {Text, TouchableOpacity, View} from 'react-native';
import type {DonnaAction} from '@donna/shared';
import {useThemedStyles} from '../../theme';
import {makeCardStyles} from '../../screens/cardStyles';
import {fieldOf, openEmailDraft} from '../../native/emailDraft';

/**
 * #147 Paket I: E-Mail-Entwurf (Stufe 2). Die Karte zeigt Empfänger, Betreff und Text VOLLSTÄNDIG (nichts gekürzt).
 * Erst Mikes Tipp auf „Entwurf öffnen“ öffnet die Mail-App; es gibt nie einen Senden-Knopf, die App sendet nie selbst.
 * Ohne Tipp passiert nichts.
 */
type View_ = {k: 'preview'} | {k: 'busy'} | {k: 'done'; text: string} | {k: 'error'; text: string; retry: boolean} | {k: 'cancelled'};

// Überlebt ein erneutes Einblenden der Karte (Scrollen), nicht den App-Neustart (das Gerät merkt sich die Kennung selbst).
const finished = new Map<string, View_>();
const MAX_FINISHED = 50;

type BtnStyles = ReturnType<typeof makeCardStyles>;
function Btn({s, label, primary, onPress}: {s: BtnStyles; label: string; primary?: boolean; onPress: () => void}): React.JSX.Element {
  return (
    <TouchableOpacity onPress={onPress} accessibilityRole="button" style={[s.btn, primary && s.btnPrimary]}>
      <Text style={primary ? s.btnTextPrimary : s.btnText}>{label}</Text>
    </TouchableOpacity>
  );
}

export function EmailDraftCard({action}: {action: DonnaAction}): React.JSX.Element {
  const s = useThemedStyles(makeCardStyles);
  const src = action as unknown as Record<string, unknown>;
  const key = String(src.card_id ?? src.action_id ?? '');
  const rawState = src.card_state;
  const closed = rawState === 'expired' || rawState === 'rejected' || rawState === 'done' ? rawState : undefined;
  const busy = useRef(false);   // Doppeltipp: nur ein Öffnen
  const [view, setView] = useState<View_>(() => (key && finished.get(key)) || {k: 'preview'});

  const set = useCallback((v: View_) => {
    if (key && (v.k === 'done' || v.k === 'cancelled')) {
      if (finished.size >= MAX_FINISHED) { finished.delete(finished.keys().next().value as string); }
      finished.set(key, v);
    }
    setView(v);
  }, [key]);

  const open = async () => {
    if (busy.current) { return; }
    busy.current = true;
    setView({k: 'busy'});
    const r = await openEmailDraft(action);
    busy.current = false;
    if (r.status === 'ok') {
      set({k: 'done', text: r.message});
    } else {
      setView({k: 'error', text: r.message, retry: r.status === 'failed'});
    }
  };

  const status = (text: string, live = false) => (
    <View style={s.cardOuter}><View style={s.cardInner}>
      <Text style={s.fieldLabel}>E-Mail-Entwurf</Text>
      <Text accessibilityLiveRegion={live ? 'polite' : undefined} style={s.fieldValue}>{text}</Text>
    </View></View>
  );

  if (closed) {
    return status(closed === 'expired' ? 'Abgelaufen' : closed === 'done' ? 'Entwurf geöffnet — gesendet hast du noch nichts.' : 'Abgelehnt');
  }
  if (view.k === 'busy') { return status('Ich öffne den Entwurf …', true); }
  if (view.k === 'done') { return status(view.text, true); }
  if (view.k === 'cancelled') { return status('Nichts geöffnet.'); }
  if (view.k === 'error') {
    return (
      <View style={s.cardOuter}>
        <View style={s.cardInner}>
          <Text style={s.fieldLabel}>E-Mail-Entwurf</Text>
          <Text accessibilityLiveRegion="polite" style={s.fieldValue}>{view.text}</Text>
        </View>
        <View style={s.btnRow}>
          {view.retry ? <Btn s={s} primary label="Nochmal versuchen" onPress={open} /> : null}
          <Btn s={s} label="Schließen" onPress={() => set({k: 'cancelled'})} />
        </View>
      </View>
    );
  }

  const to = fieldOf(action, 'to');
  const subject = fieldOf(action, 'subject');
  const body = fieldOf(action, 'body');
  return (
    <View style={s.cardOuter}>
      <View style={s.cardInner}>
        <Text style={s.fieldLabel}>E-Mail-Entwurf</Text>
        <Text style={s.fieldLabel}>An</Text>
        <Text style={s.fieldValue}>{to || 'Kein Empfänger — den wählst du in der Mail-App'}</Text>
        <Text style={s.fieldLabel}>Betreff</Text>
        <Text style={s.fieldValue}>{subject || '(ohne Betreff)'}</Text>
        <Text style={s.fieldLabel}>Text</Text>
        <Text style={s.fieldValue}>{body || '(ohne Text)'}</Text>
        <Text style={s.warnText}>Das öffnet nur einen Entwurf in deiner Mail-App. Gesendet wird nichts.</Text>
      </View>
      <View style={s.btnRow}>
        <Btn s={s} primary label="Entwurf öffnen" onPress={open} />
        <Btn s={s} label="Abbrechen" onPress={() => set({k: 'cancelled'})} />
      </View>
    </View>
  );
}

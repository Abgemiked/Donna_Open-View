import React, {useState} from 'react';
import {View, Text, TextInput, TouchableOpacity, ScrollView, StyleSheet, Linking} from 'react-native';
import type {DonnaAction} from '@donna/shared';
import {type ContactMatch} from '../../native/ContactsBridge';
import {contactActionFields} from '../../utils/actionSummary';
import {useTheme, useThemedStyles, FONT, FONT_FAMILY, RADIUS, SPACE} from '../../theme';
import {Icon} from '../Icon';
import type {IconName} from '../Icon';
import type {Theme} from '../../theme';
import {makeCardStyles, makePickerStyles, makeFallbackStyles} from '../../screens/cardStyles';
import {NAV_DISAMBIGUATE, NAV_NOT_FOUND, TESLA_NAVIGATE, getNavTarget, str} from '../../utils/navActions';

// ─── ActionList ────────────────────────────────────────────────────────────────
// DONNA-Welle1 Task 5/6: Zeigt vom Backend emittierte DONNA_ACTION-Events als
// Hinweis-Chips unter der Donna-Bubble. Welle 1 = nur visuell + open_url klickbar.
// Volle Action-Handler (create_event, set_alarm, navigate, …) kommen in Welle 2.
// DONNA-Welle3: Bestätigungs-Karte statt direkter Ausführung. ActionList ruft
// onAction(action) auf -> ChatScreen zeigt PendingActionCard -> User bestätigt ->
// Ausführung via AlarmModule (native Intent mit SKIP_UI=true) oder Linking.
function formatEventTime(iso: string | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  const dayMs = 24 * 60 * 60 * 1000;
  const today = new Date(); today.setHours(0,0,0,0);
  const target = new Date(d); target.setHours(0,0,0,0);
  const diff = Math.round((target.getTime() - today.getTime()) / dayMs);
  let dayLabel: string;
  if (diff === 0) dayLabel = 'Heute';
  else if (diff === 1) dayLabel = 'Morgen';
  else if (diff === -1) dayLabel = 'Gestern';
  else dayLabel = d.toLocaleDateString('de-DE', {weekday: 'short', day: '2-digit', month: '2-digit'});
  const time = d.toLocaleTimeString('de-DE', {hour: '2-digit', minute: '2-digit'});
  return `${dayLabel}, ${time}`;
}

function getActionConfirmLabel(action: DonnaAction): string {
  switch (action.type) {
    case 'set_alarm': return `Wecker für ${(action.time as string) ?? ''} stellen?`;
    case 'set_timer': return `Timer für ${(action.minutes as number) ?? ''} Minuten starten?`;
    case 'create_event': return `"${(action.title as string) ?? 'Termin'}" in Kalender eintragen?`;
    case 'navigate': return `Navigation zu ${str(action.destination)} starten?`;
    case TESLA_NAVIGATE: return `Ziel an Tesla senden: ${getNavTarget(action)}?`;
    case 'call': return `${(action.number as string) ?? ''} anrufen?`;
    case 'sms': return `SMS an ${(action.number as string) ?? ''} senden?`;
    case 'open_url': return `Link öffnen: ${(action.title as string) ?? (action.url as string) ?? ''}?`;
    default: return `Aktion ausführen?`;
  }
}

// ─── Reichere Pending-Action-Karte mit typ-spezifischen Detail-Layouts ───────
// Zeigt fuer Termin/Wecker/Anruf/Nachricht eine Detail-Vorschau mit allen
// relevanten Feldern. Anschliessend Bestaetigen/Abbrechen.
export function PendingActionCard({
  action,
  contact,
  onConfirm,
  onCancel,
}: {
  action: DonnaAction;
  contact: ContactMatch | null;
  onConfirm: () => void;
  onCancel: () => void;
}): React.JSX.Element {
  const cardStyles = useThemedStyles(makeCardStyles);
  // Bixby-Stil: Label/Wert-Paare mit Trennstrichen. Pro Action-Typ andere Felder.
  const renderFields = (): Array<{label: string; value: string; multiline?: boolean; warn?: boolean}> => {
    switch (action.type) {
      case 'create_event': {
        const fields: Array<{label: string; value: string; multiline?: boolean}> = [];
        const title = (action.title as string) ?? 'Termin';
        fields.push({label: 'Titel', value: title});
        const start = action.start as string | undefined;
        const end = action.end as string | undefined;
        if (start) {
          const endTxt = end ? ` – ${new Date(end).toLocaleTimeString('de-DE', {hour:'2-digit', minute:'2-digit'})}` : '';
          fields.push({label: 'Wann', value: `${formatEventTime(start)}${endTxt}`});
        }
        const location = action.location as string | undefined;
        if (location) fields.push({label: 'Ort', value: location});
        return fields;
      }
      case 'set_alarm': {
        const fields = [{label: 'Uhrzeit', value: (action.time as string) ?? '--:--'}];
        const label = (action.label as string) ?? '';
        if (label) fields.push({label: 'Bezeichnung', value: label});
        return fields;
      }
      case 'set_timer': {
        const mins = (action.minutes as number) ?? 0;
        const fields = [{label: 'Dauer', value: `${mins} Minuten`}];
        const label = (action.label as string) ?? '';
        if (label) fields.push({label: 'Bezeichnung', value: label});
        return fields;
      }
      case 'navigate':
        return [{label: 'Ziel', value: str(action.destination)}];
      case TESLA_NAVIGATE: {
        // Aufgeloeste Adresse (Geocoding) wird gesendet; das gesprochene Ziel steht darueber, wenn es abweicht.
        const target = getNavTarget(action);
        const spoken = str(action.destination);
        const fields: Array<{label: string; value: string}> = [{label: 'Ziel an Tesla senden', value: target}];
        if (spoken && spoken !== target) fields.unshift({label: 'Du sagtest', value: spoken});
        return fields;
      }
      case 'call':
      case 'sms':
      case 'whatsapp':
        // BLOCKER 4 (informierte Bestätigung): IMMER Empfänger + Nummer + (bei Nachricht) Text,
        // Nummer aus dem Payload wird sichtbar markiert. Einzige Kontrolle vor dem Auto-Send.
        return contactActionFields(
          {type: action.type, name: action.name, number: action.number, message: action.message},
          contact,
        );
      case 'open_url':
        return [{label: 'Link', value: (action.title as string) ?? (action.url as string) ?? ''}];
      case 'play_music': {
        const fields = [{label: 'Musik', value: (action.query as string) ?? ''}];
        const service = (action.service as string) ?? '';
        if (service) fields.push({label: 'Dienst', value: service});
        return fields;
      }
      default:
        return [{label: 'Aktion', value: getActionConfirmLabel(action)}];
    }
  };

  const fields = renderFields();
  const noNumber = (action.type === 'call' || action.type === 'sms' || action.type === 'whatsapp') &&
    !contact && !((action.number as string)?.length);
  // tesla_navigate ohne Ziel: nichts zu senden -> Bestaetigen sperren (wie "Kontakt fehlt")
  const noTarget = action.type === TESLA_NAVIGATE && !getNavTarget(action);
  const confirmDisabled = noNumber || noTarget;
  const noContactWarn = !contact && (action.name as string) &&
    (action.type === 'call' || action.type === 'sms' || action.type === 'whatsapp');

  return (
    <View style={cardStyles.cardOuter}>
      <View style={cardStyles.cardInner}>
        {fields.map((f, i) => (
          <View key={`${f.label}-${i}`}>
            {i > 0 && <View style={cardStyles.divider} />}
            <Text style={f.warn ? [cardStyles.fieldLabel, cardStyles.warnText] : cardStyles.fieldLabel}>{f.label}</Text>
            {/* BLOCKER 2: Nachricht (multiline) NICHT kürzen — Mike muss vor „Ja" den vollen Text sehen,
                der auch vollständig gesendet wird. Nur Kurzfelder bleiben auf 2 Zeilen begrenzt. */}
            <Text style={cardStyles.fieldValue} numberOfLines={f.multiline ? undefined : 2}>{f.value}</Text>
          </View>
        ))}
        {noContactWarn ? (
          <Text style={cardStyles.warnText}>„{str(action.name)}“ nicht in Kontakten gefunden</Text>
        ) : null}
      </View>
      <View style={cardStyles.btnRow}>
        <TouchableOpacity
          onPress={onConfirm}
          disabled={confirmDisabled}
          accessibilityRole="button"
          accessibilityState={{disabled: confirmDisabled}}
          style={[cardStyles.btn, confirmDisabled && cardStyles.btnDisabled]}>
          <Text style={confirmDisabled ? cardStyles.btnText : cardStyles.btnTextPrimary}>
            {noNumber
              ? 'Kontakt fehlt'
              : noTarget
                ? 'Ziel fehlt'
                : action.type === TESLA_NAVIGATE
                  ? 'An Tesla senden'
                  : 'Bestätigen'}
          </Text>
        </TouchableOpacity>
        <View style={cardStyles.btnDivider} />
        <TouchableOpacity onPress={onCancel} style={cardStyles.btn} accessibilityRole="button">
          <Text style={cardStyles.btnText}>Abbrechen</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

// Contact-Picker — wird angezeigt wenn mehrere Kontakte zum Namen passen
export function ContactPickerCard({
  query,
  matches,
  onPick,
  onCancel,
}: {
  query: string;
  matches: ContactMatch[];
  onPick: (c: ContactMatch) => void;
  onCancel: () => void;
}): React.JSX.Element {
  const cardStyles = useThemedStyles(makeCardStyles);
  const pickerStyles = useThemedStyles(makePickerStyles);
  return (
    <View style={cardStyles.cardOuter}>
      <View style={cardStyles.cardInner}>
        <Text style={cardStyles.fieldLabel}>Kontakt wählen</Text>
        <Text style={[cardStyles.fieldValue, {fontSize: 18, marginBottom: 8}]}>Mehrere Treffer für „{str(query)}“</Text>
        <ScrollView style={{maxHeight: 240}}>
          {matches.map((m, i) => (
            <TouchableOpacity
              key={`${m.contactId}-${i}`}
              onPress={() => onPick(m)}
              style={pickerStyles.row}>
              <Text style={pickerStyles.name}>{m.name}</Text>
              <Text style={pickerStyles.number}>{m.number}</Text>
            </TouchableOpacity>
          ))}
        </ScrollView>
      </View>
      <View style={cardStyles.btnRow}>
        <TouchableOpacity onPress={onCancel} style={cardStyles.btn}>
          <Text style={cardStyles.btnText}>Abbrechen</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}
// Kontakt nicht gefunden — zeigt fuzzy Vorschläge + Freitexteingabe
interface ContactFallbackCardProps {
  originalName: string;
  suggestions: ContactMatch[];
  onSelect: (contact: ContactMatch) => void;
  onRetry: (name: string) => void;
  onCancel: () => void;
}
export function ContactFallbackCard({
  originalName,
  suggestions,
  onSelect,
  onRetry,
  onCancel,
}: ContactFallbackCardProps): React.JSX.Element {
  const P = useTheme();
  const cardStyles = useThemedStyles(makeCardStyles);
  const pickerStyles = useThemedStyles(makePickerStyles);
  const fallbackStyles = useThemedStyles(makeFallbackStyles);
  const [inputText, setInputText] = useState('');
  return (
    <View style={cardStyles.cardOuter}>
      <View style={cardStyles.cardInner}>
        <Text style={cardStyles.fieldLabel}>Kontakt nicht gefunden</Text>
        <Text style={[cardStyles.fieldValue, {fontSize: 18, marginBottom: 8}]}>
          „{str(originalName)}“ — wen meinst du?
        </Text>
        {suggestions.length > 0 && (
          <ScrollView style={{maxHeight: 200}}>
            {suggestions.map((m, i) => (
              <TouchableOpacity
                key={`${m.contactId ?? i}-fallback`}
                onPress={() => onSelect(m)}
                style={pickerStyles.row}>
                <Text style={pickerStyles.name}>{m.name}</Text>
                <Text style={pickerStyles.number}>{m.number}</Text>
              </TouchableOpacity>
            ))}
          </ScrollView>
        )}
        {suggestions.length > 0 && <View style={cardStyles.divider} />}
        <TextInput
          style={fallbackStyles.input}
          placeholder="anderen Namen eingeben…"
          placeholderTextColor={P.muted}
          value={inputText}
          onChangeText={setInputText}
          autoCorrect={false}
        />
      </View>
      <View style={cardStyles.btnRow}>
        <TouchableOpacity
          onPress={() => onRetry(inputText)}
          style={[cardStyles.btn, inputText.trim().length < 2 && cardStyles.btnDisabled]}
          disabled={inputText.trim().length < 2}>
          <Text style={cardStyles.btnText}>Suchen</Text>
        </TouchableOpacity>
        <View style={cardStyles.btnDivider} />
        <TouchableOpacity onPress={onCancel} style={cardStyles.btn}>
          <Text style={cardStyles.btnText}>Abbrechen</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}
export function ActionList({actions, onAction}: {actions: DonnaAction[]; onAction?: (a: DonnaAction) => void}): React.JSX.Element {
  const actionStyles = useThemedStyles(makeActionStyles);
  const P = useTheme();
  const labels: Record<string, string> = {
    create_event: 'Termin',
    set_alarm: 'Alarm',
    set_timer: 'Timer',
    navigate: 'Navigation',
    [TESLA_NAVIGATE]: 'An Tesla',
    [NAV_DISAMBIGUATE]: 'Ziel wählen',
    [NAV_NOT_FOUND]: 'Ziel nicht gefunden',
    call: 'Anruf',
    sms: 'SMS',
    whatsapp: 'WhatsApp',
    play_music: 'Musik',
    note: 'Notiz',
    open_url: 'Link',
  };
  // Strich-Icons statt Emoji (#134); Anruf = phone, SMS/WhatsApp = message
  const icons: Record<string, IconName> = {
    create_event: 'calendar',
    set_alarm: 'history',
    set_timer: 'history',
    navigate: 'map',
    [TESLA_NAVIGATE]: 'map',
    [NAV_DISAMBIGUATE]: 'map',
    [NAV_NOT_FOUND]: 'alert',
    call: 'phone',
    sms: 'message',
    whatsapp: 'message',
    play_music: 'wave',
    note: 'copy',
    open_url: 'link',
  };
  const interactiveTypes = new Set([
    'set_alarm', 'set_timer', 'create_event', 'navigate', TESLA_NAVIGATE, NAV_DISAMBIGUATE, NAV_NOT_FOUND,
    'call', 'sms', 'whatsapp', 'open_url', 'play_music',
  ]);
  return (
    <View style={actionStyles.row}>
      {actions.map((a, i) => {
        const label = labels[a.type] ?? a.type;
        const iconName: IconName = icons[a.type] ?? 'idea';
        // Beschreibung pro Action-Typ
        let desc = '';
        if (a.type === 'create_event') {
          desc = `${(a.title as string) ?? ''} ${(a.start as string) ?? ''}`.trim();
        } else if (a.type === 'set_alarm') {
          desc = `${(a.time as string) ?? ''} ${(a.label as string) ?? ''}`.trim();
        } else if (a.type === 'set_timer') {
          desc = `${a.minutes ?? ''}min ${(a.label as string) ?? ''}`.trim();
        } else if (a.type === 'navigate' || a.type === TESLA_NAVIGATE) {
          desc = getNavTarget(a);
        } else if (a.type === NAV_DISAMBIGUATE || a.type === NAV_NOT_FOUND) {
          desc = str(a.query);
        } else if (a.type === 'open_url') {
          desc = (a.title as string) ?? (a.url as string) ?? '';
        } else if (a.type === 'play_music') {
          desc = (a.query as string) ?? '';
        } else if (a.type === 'call') {
          desc = (a.number as string) ?? '';
        } else if (a.type === 'sms') {
          desc = `${(a.number as string) ?? ''} ${(a.message as string) ?? ''}`.trim();
        }
        const interactive = interactiveTypes.has(a.type);
        return (
          <TouchableOpacity
            key={`${a.type}-${i}`}
            onPress={interactive ? () => { onAction?.(a); } : undefined}
            disabled={!interactive}
            activeOpacity={interactive ? 0.7 : 1}
            style={actionStyles.chip}>
            <Icon name={iconName} size={18} color={P.text as string} />
            <Text style={actionStyles.chipLabel}>{label}</Text>
            {desc ? <Text style={actionStyles.chipDesc} numberOfLines={1}>{desc}</Text> : null}
          </TouchableOpacity>
        );
      })}
    </View>
  );
}
const makeActionStyles = (P: Theme) => StyleSheet.create({
  row: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    marginTop: SPACE.sm,
    gap: SPACE.sm,
  },
  // Aktions-Pille (Entwurf B): Umriss, voll gerundet, Tippziel >= 48
  chip: {
    borderColor: P.border,
    borderWidth: 1,
    borderRadius: RADIUS.pill,
    paddingHorizontal: SPACE.lg,
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACE.sm,
    maxWidth: '100%',
    minHeight: 48,
    minWidth: 48,
  },
  chipLabel: {color: P.text, fontFamily: FONT_FAMILY.sans.semibold, fontSize: FONT.body},
  chipDesc: {color: P.muted, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.label, flexShrink: 1},
});


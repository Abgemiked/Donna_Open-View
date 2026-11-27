/**
 * #155 Karten im Verlauf: Zustand einer Karte in der Nachrichtenliste vermerken.
 * Reine Logik: fuehrt nichts aus, meldet nichts (das macht der Aufrufer ueber setCardState).
 */
import type {ChatMessage, DonnaAction} from '@donna/shared';

/** Neue Liste, in der die Karte mit `cardId` den Zustand `state` traegt; ohne Treffer dieselbe Liste. */
export function withCardState(
  messages: ChatMessage[],
  cardId: string | undefined,
  state: 'done' | 'rejected',
): ChatMessage[] {
  if (!cardId) { return messages; }
  let changed = false;
  const next = messages.map(m => {
    if (!m.actions?.some(a => a.card_id === cardId)) { return m; }
    changed = true;
    return {...m, actions: m.actions.map(a => (a.card_id === cardId ? {...a, card_state: state} : a))};
  });
  return changed ? next : messages;
}

/** Eine Karte ist nur bestaetigbar, solange sie offen ist (frisch aus dem Stream: ohne Zustand). */
export function isCardOpen(action: DonnaAction): boolean {
  return action.card_state == null || action.card_state === 'open';
}

/** Rueckmeldetexte der Ausfuehrung, die einen Fehlschlag meinen (Karte bleibt dann offen und kann erneut bestaetigt werden). */
export function isFailureText(text: string): boolean {
  return /^(Fehler|Keine Nummer|Kein Ziel|Kein gültiger Link|Tesla-|Senden an Tesla fehlgeschlagen)/.test(text);
}

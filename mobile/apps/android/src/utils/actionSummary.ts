/**
 * actionSummary — B2 BLOCKER 4 (informierte Bestätigung, WICHTIGSTE Safety).
 *
 * Mit Auto-Send entfällt Mikes bisheriges Sicherheitsnetz (er sah Empfänger + Text in WhatsApp und
 * tippte selbst). Die Bestätigungskarte ist jetzt die EINZIGE Kontrolle vor dem Senden. Darum MUSS
 * sie bei call/sms/whatsapp den tatsächlichen Empfänger INKL. NUMMER UND den vollständigen
 * Nachrichtentext zeigen — und kennzeichnen, wenn die Nummer aus dem Payload (nicht aus dem
 * Adressbuch) stammt. Gesendet/angerufen wird an die ANGEZEIGTE Nummer.
 *
 * Reine Funktion (kein RN) → in actionSummary.test.ts testbar; von PendingActionCard genutzt.
 */

export interface SummaryField {
  label: string;
  value: string;
  multiline?: boolean;
  /** true = sicherheitsrelevanter Hinweis (Nummer nicht aus Kontakten aufgelöst). */
  warn?: boolean;
}

export interface ContactLike {
  name?: string | null;
  number?: string | null;
}

export interface ContactAction {
  type: string;
  name?: unknown;
  number?: unknown;
  message?: unknown;
}

const s = (v: unknown): string => (typeof v === 'string' ? v : v == null ? '' : String(v));

/** Nur für call/sms/whatsapp. Liefert Empfänger + Nummer (immer, wenn vorhanden) + Nachricht. */
export function contactActionFields(action: ContactAction, contact: ContactLike | null): SummaryField[] {
  const name = (contact?.name ?? s(action.name)) || '';
  const number = (contact?.number ?? s(action.number)) || '';
  const msg = s(action.message);
  // Nummer aus Adressbuch aufgelöst (contact) ODER nur aus dem Payload (Donnas Vorschlag)?
  const fromPayload = !contact && number.length > 0;

  const fields: SummaryField[] = [];
  if (name) fields.push({label: 'Empfänger', value: name});
  if (number) {
    fields.push({
      label: fromPayload ? 'Nummer (aus Anfrage, nicht aus Kontakten)' : 'Nummer',
      value: number,
      warn: fromPayload,
    });
  }
  if ((action.type === 'whatsapp' || action.type === 'sms') && msg) {
    fields.push({label: 'Nachricht', value: msg, multiline: true});
  }
  return fields;
}

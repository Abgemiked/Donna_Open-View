/**
 * E-Mail-Entwurf öffnen (#147 Paket I): Brücke zum nativen Modul `EmailDraft`.
 *
 * Das Gerät prüft die Karte des Servers noch einmal selbst (genau eine Adresse oder keine, Längen, keine Steuerzeichen)
 * und öffnet NUR einen Entwurf in der Mail-App. Gesendet wird nie. Fehlt die Brücke oder kommt Unsinn zurück, gibt es
 * eine ehrliche Meldung statt eines Erfolgs. Empfänger, Betreff und Text werden nie geloggt.
 */
import {NativeModules} from 'react-native';
import type {DonnaAction} from '@donna/shared';

export const EMAIL_DRAFT = 'email_draft';

export const isEmailDraft = (a: DonnaAction | undefined | null): boolean => a?.type === EMAIL_DRAFT;

export type EmailDraftStatus = 'ok' | 'unsupported' | 'rejected' | 'disabled' | 'failed';
export interface EmailDraftResult {
  status: EmailDraftStatus;
  message: string;
}

const FAILED_TEXT = 'Das hat nicht geklappt — ich habe nichts geöffnet.';
const UNAVAILABLE_TEXT = 'Das kann ich auf diesem Gerät gerade nicht ausführen.';

interface EmailDraftNative {
  open?(requestJson: string): Promise<string>;
}
const native = (): EmailDraftNative | undefined => (NativeModules as {EmailDraft?: EmailDraftNative}).EmailDraft;

const WIRE_FIELDS = ['type', 'to', 'subject', 'body', 'action_id', 'card_id'] as const;

/** Nur die Felder der Server-Karte zum Gerät (die App hängt z. B. `card_session`/`card_state` an). */
export function wireOf(action: DonnaAction): string {
  const src = action as unknown as Record<string, unknown>;
  const out: Record<string, unknown> = {};
  for (const k of WIRE_FIELDS) {
    if (src[k] !== undefined) {
      out[k] = src[k];
    }
  }
  return JSON.stringify(out);
}

/** Ein Feld der Karte als Text (unbekannt/leer = ''). */
export const fieldOf = (action: DonnaAction, k: 'to' | 'subject' | 'body'): string => {
  const v = (action as unknown as Record<string, unknown>)[k];
  return typeof v === 'string' ? v : '';
};

/** Antwort des Geräts streng lesen (fail-closed: alles Unklare ist `failed`). */
export function parseResult(raw: unknown): EmailDraftResult {
  try {
    const o = JSON.parse(String(raw)) as Record<string, unknown>;
    const st = o.status;
    if ((st === 'ok' || st === 'unsupported' || st === 'rejected' || st === 'disabled' || st === 'failed')
      && typeof o.message === 'string' && o.message.length > 0) {
      return {status: st, message: o.message};
    }
  } catch {
    // fällt durch
  }
  return {status: 'failed', message: FAILED_TEXT};
}

/** Nach dem Tipp auf „Entwurf öffnen“. */
export async function openEmailDraft(action: DonnaAction): Promise<EmailDraftResult> {
  const fn = native()?.open;
  if (typeof fn !== 'function') {
    return {status: 'failed', message: UNAVAILABLE_TEXT};
  }
  try {
    return parseResult(await fn(wireOf(action)));
  } catch {
    return {status: 'failed', message: FAILED_TEXT};
  }
}

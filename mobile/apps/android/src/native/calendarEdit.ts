/**
 * Termine ändern/löschen (#147 Paket H): Brücke zum nativen Modul `CalendarEdit`.
 *
 * Suchen, Prüfen (nur eigene Termine, Serien, Mehrdeutigkeit) und Schreiben passieren NATIV auf dem Gerät mit der
 * Laufzeitberechtigung „Kalender“. Hier wird nur die Karte des Servers (Werte aus Mikes Satz) durchgereicht und die
 * Antwort des Geräts streng gelesen. Fehlt die Brücke oder kommt Unsinn zurück, gibt es eine ehrliche Meldung statt eines
 * Erfolgs. Kalenderinhalte werden nie geloggt.
 */
import {NativeModules} from 'react-native';
import type {DonnaAction} from '@donna/shared';

export const CALENDAR_CHANGE = 'calendar_change';

export const isCalendarChange = (a: DonnaAction | undefined | null): boolean => a?.type === CALENDAR_CHANGE;

export type CalendarOp = 'move' | 'rename' | 'delete';

export interface PlanSide {
  title: string;
  when: string;
}

export interface CalendarCandidate {
  eventId: number;
  beginMs: number;
  title: string;
  when: string;
  own: boolean;
  recurring: boolean;
}

export type CalendarPlan =
  | {status: 'ready'; op: CalendarOp; eventId: number; recurring: boolean; seriesAllowed: boolean;
     irreversible: boolean; warning?: string;
     before: PlanSide & {beginMs: number; endMs: number}; after: PlanSide}
  | {status: 'choose'; candidates: CalendarCandidate[]}
  | {status: 'none' | 'refused' | 'needs_permission' | 'rejected' | 'disabled' | 'failed' | 'ok'; message: string};

interface CalendarEditNative {
  plan?(requestJson: string): Promise<string>;
  planSelected?(requestJson: string, eventId: number, beginMs: number): Promise<string>;
  apply?(requestJson: string, eventId: number, beginMs: number, expectedTitle: string, expectedEndMs: number,
         scope: string, seriesConfirmed: boolean): Promise<string>;
  getPermissionState?(): Promise<string>;
  openPermissionSettings?(): Promise<boolean>;
}

const native = (): CalendarEditNative | undefined =>
  (NativeModules as {CalendarEdit?: CalendarEditNative}).CalendarEdit;

const UNAVAILABLE: CalendarPlan = {status: 'failed', message: 'Das kann ich auf diesem Gerät gerade nicht ausführen.'};
const FAILED_TEXT = 'Das hat nicht geklappt — ich habe nichts bestätigt.';

const WIRE_FIELDS = ['type', 'op', 'query', 'on_date', 'on_time', 'new_date', 'new_time', 'new_title',
  'series_mentioned', 'from_all_day', 'action_id', 'card_id'] as const;

/** Nur die Felder der Server-Karte zum Gerät (die App hängt z. B. `card_session`/`card_state` an; das Gerät lehnt Unbekanntes ab). */
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

const isStr = (v: unknown): v is string => typeof v === 'string';
const isNum = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v);

/** Antwort des Geräts streng lesen (fail-closed: alles Unklare ist `failed`). */
export function parsePlan(raw: unknown): CalendarPlan {
  try {
    const o = JSON.parse(String(raw)) as Record<string, unknown>;
    const st = o.status;
    if (st === 'ready') {
      const b = o.before as Record<string, unknown> | undefined;
      const a = o.after as Record<string, unknown> | undefined;
      const op = o.op;
      if (!b || !a || (op !== 'move' && op !== 'rename' && op !== 'delete') || !isNum(o.eventId)
        || !isStr(b.title) || !isStr(b.when) || !isNum(b.beginMs) || !isNum(b.endMs) || !isStr(a.title) || !isStr(a.when)) {
        return {status: 'failed', message: FAILED_TEXT};
      }
      return {
        status: 'ready', op, eventId: o.eventId, recurring: o.recurring === true,
        seriesAllowed: o.seriesAllowed === true, irreversible: o.irreversible === true || op === 'delete',
        warning: isStr(o.warning) ? o.warning : undefined,
        before: {title: b.title, when: b.when, beginMs: b.beginMs, endMs: b.endMs},
        after: {title: a.title, when: a.when},
      };
    }
    if (st === 'choose' && Array.isArray(o.candidates)) {
      const cands: CalendarCandidate[] = [];
      for (const c of o.candidates as Array<Record<string, unknown>>) {
        if (!isNum(c.eventId) || !isNum(c.beginMs) || !isStr(c.title) || !isStr(c.when)) {
          return {status: 'failed', message: FAILED_TEXT};
        }
        cands.push({eventId: c.eventId, beginMs: c.beginMs, title: c.title, when: c.when, own: c.own === true, recurring: c.recurring === true});
      }
      return cands.length > 1 ? {status: 'choose', candidates: cands} : {status: 'failed', message: FAILED_TEXT};
    }
    if ((st === 'none' || st === 'refused' || st === 'needs_permission' || st === 'rejected' || st === 'disabled'
      || st === 'failed' || st === 'ok') && isStr(o.message)) {
      return {status: st, message: o.message};
    }
  } catch {
    // fällt durch
  }
  return {status: 'failed', message: FAILED_TEXT};
}

export async function planCalendarChange(action: DonnaAction): Promise<CalendarPlan> {
  const fn = native()?.plan;
  if (typeof fn !== 'function') {
    return UNAVAILABLE;
  }
  try {
    return parsePlan(await fn(wireOf(action)));
  } catch {
    return {status: 'failed', message: FAILED_TEXT};
  }
}

export async function planSelectedCalendarChange(action: DonnaAction, c: CalendarCandidate): Promise<CalendarPlan> {
  const fn = native()?.planSelected;
  if (typeof fn !== 'function' || !c.own) {
    return c.own ? UNAVAILABLE : {status: 'refused', message: 'Das ist kein Termin von dir — den ändere ich nicht.'};
  }
  try {
    return parsePlan(await fn(wireOf(action), c.eventId, c.beginMs));
  } catch {
    return {status: 'failed', message: FAILED_TEXT};
  }
}

/** Nach dem Tipp. `seriesConfirmed` NUR nach der zweiten, ausdrücklichen Bestätigung. */
export async function applyCalendarChange(
  action: DonnaAction,
  plan: Extract<CalendarPlan, {status: 'ready'}>,
  scope: 'instance' | 'series',
  seriesConfirmed: boolean,
): Promise<CalendarPlan> {
  const fn = native()?.apply;
  if (typeof fn !== 'function') {
    return UNAVAILABLE;
  }
  try {
    return parsePlan(await fn(wireOf(action), plan.eventId, plan.before.beginMs, plan.before.title,
      plan.before.endMs, scope, scope === 'series' && seriesConfirmed));
  } catch {
    return {status: 'failed', message: FAILED_TEXT};
  }
}

export async function openCalendarPermissionSettings(): Promise<boolean> {
  try {
    return (await native()?.openPermissionSettings?.()) === true;
  } catch {
    return false;
  }
}

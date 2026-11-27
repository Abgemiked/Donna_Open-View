/**
 * #147 G2: Routinen — reine Logik (ohne React, ohne native Aufrufe).
 *
 * Der Server schickt nach "Leg eine Routine ... an" EINEN Vorschlag als Aktion
 * `{"type":"routine_confirm","pending_id":"rp_<16hex>","mode":"create|update|delete","name","steps":[...],"old_steps":[...]}`.
 * Gespeichert wird erst nach Mikes Tipp (Server prüft erneut). Die App erfindet nie Schritte und führt hier nichts aus.
 * Auslöser "Tipp": ein Lauf ist ein normaler Chat-Satz an den Server ("Starte die Routine X"), den er ohne Modell auflöst.
 */
import type {DonnaAction} from '@donna/shared';

export const ROUTINE_CONFIRM = 'routine_confirm';
const PENDING_ID = /^rp_[0-9a-f]{16}$/;
const MAX_STEPS = 8;
const MAX_LABEL = 120;
const MAX_NAME = 40;

export type RoutineMode = 'create' | 'update' | 'delete';

export interface RoutineConfirm {
  pendingId: string;
  mode: RoutineMode;
  name: string;
  steps: string[];
  oldSteps: string[];
}

const isLabels = (v: unknown, min: number): v is string[] =>
  Array.isArray(v) && v.length >= min && v.length <= MAX_STEPS
  && v.every(s => typeof s === 'string' && s.length > 0 && s.length <= MAX_LABEL);

export const isRoutineConfirm = (a: DonnaAction | undefined | null): boolean => a?.type === ROUTINE_CONFIRM;

/** Prüft das Server-Ereignis streng (fail-closed): alles Ungültige -> null, dann erscheint keine Karte. */
export function parseRoutineConfirm(a: DonnaAction | undefined | null): RoutineConfirm | null {
  if (!a || a.type !== ROUTINE_CONFIRM) { return null; }
  const pid = a.pending_id;
  const mode = a.mode;
  const name = a.name;
  if (typeof pid !== 'string' || !PENDING_ID.test(pid)) { return null; }
  if (mode !== 'create' && mode !== 'update' && mode !== 'delete') { return null; }
  if (typeof name !== 'string' || name.length === 0 || name.length > MAX_NAME) { return null; }
  const steps = a.steps ?? [];
  const old = a.old_steps ?? [];
  if (!isLabels(steps, mode === 'delete' ? 0 : 1) || !isLabels(old, 0)) { return null; }
  if (mode === 'delete' && steps.length > 0) { return null; }
  return {pendingId: pid, mode, name, steps, oldSteps: old};
}

export const MODE_TITLE: Record<RoutineMode, string> = {
  create: 'Routine anlegen — erst nach deinem Tipp',
  update: 'Routine ändern — erst nach deinem Tipp',
  delete: 'Routine löschen — erst nach deinem Tipp',
};
export const MODE_CONFIRM: Record<RoutineMode, string> = {create: 'Speichern', update: 'Ändern', delete: 'Löschen'};
export const MODE_DONE: Record<RoutineMode, string> = {
  create: 'Routine gespeichert.', update: 'Routine geändert.', delete: 'Routine gelöscht.',
};

/** Hinweis zu Smart-Home-Schritten: sie laufen am Server und gehören nicht zu "Alles rückgängig". */
export const HOME_UNDO_NOTE = 'Schalten am Server läuft sofort und lässt sich nicht mit „Rückgängig“ zurücknehmen.';
export const isHomeLabel = (label: string): boolean => label.startsWith('Schalten:');

/** Satz, mit dem ein Tipp auf "Starten" die Routine auslöst (der Server erkennt ihn ohne Modell). */
export const runPhrase = (name: string): string => `Starte die Routine ${name}`;

type RunListener = (name: string) => void;
let runListener: RunListener | null = null;

/** Der Chat registriert sich hier; ohne Chat (nicht gemountet) geht ein Tipp ins Leere statt später zu laufen. */
export function onRoutineRunRequest(l: RunListener | null): () => void {
  runListener = l;
  return () => { if (runListener === l) { runListener = null; } };
}
export function requestRoutineRun(name: string): boolean {
  if (!runListener || typeof name !== 'string' || !name.trim() || name.length > MAX_NAME) { return false; }
  runListener(name);
  return true;
}

/**
 * #186 G1 (Refs #147, #183): Sammelkarte für Mehrfachbefehle — reine Logik (ohne React, ohne native Aufrufe).
 *
 * Der Server schickt EIN Ereignis `{"type":"step_group","group_id":"g_<16hex>","steps":[...]}` mit allen Schritten ab
 * dem ersten Stufe-2-Teil in gesprochener Reihenfolge. Die App erfindet nie Schritte: ausgeführt wird nur, was in
 * diesem Ereignis steht, und erst nach dem Tipp auf "Ausführen". Ehrliche Sprache: "angestoßen", nie "erledigt".
 */
import type {DonnaAction} from '@donna/shared';
import type {DeviceActionOutcome} from '../native/deviceActions';

export const STEP_GROUP = 'step_group';
/** Karten mit externer App (gleiche Liste wie der Server: EXTERNAL_CARD_TYPES). Höchstens eine je Sammelkarte. */
export const STEP_CARD_TYPES: ReadonlySet<string> = new Set(['sms', 'whatsapp', 'call', 'create_event']);
export const MAX_GROUP_STEPS = 5;
const GROUP_ID = /^g_[0-9a-f]{16}$/;

export type StepState = 'angestossen' | 'braucht_berechtigung' | 'nicht_unterstuetzt' | 'fehlgeschlagen' | 'ausgelassen';

export interface StepResult {
  /** Kurze Beschreibung des Schritts (aus dem Server-Ereignis, nie vom Gerät erfunden). */
  label: string;
  state: StepState;
  /** Zusatz (z. B. Grund, warum das Gate den Schritt abgelehnt hat). */
  note?: string;
  /** Kennung in der Rückgängig-Ablage des Geräts (nur bei zurücknehmbaren Geräteschritten). */
  undoId?: number;
  undoable: boolean;
  /** Stufe-2-Karte (Nachricht/Anruf/Termin): lässt sich nie zurücknehmen. */
  card: boolean;
}

export interface ParsedStepGroup {
  groupId: string;
  steps: DonnaAction[];
}

export const isStepGroup = (a: DonnaAction | undefined | null): boolean => a?.type === STEP_GROUP;
export const isStepCard = (a: DonnaAction | undefined | null): boolean => !!a && STEP_CARD_TYPES.has(a.type);

/**
 * Prüft das Server-Ereignis streng (fail-closed): falsche Kennung, unbekannter Schritt, mehr als eine Karte, zu viele
 * Schritte oder eine fremde group_id in einem Schritt -> null (die ganze Gruppe wird verworfen, nichts läuft).
 */
export function parseStepGroup(a: DonnaAction | undefined | null): ParsedStepGroup | null {
  if (!a || a.type !== STEP_GROUP) { return null; }
  const gid = a.group_id;
  if (typeof gid !== 'string' || !GROUP_ID.test(gid)) { return null; }
  const steps = a.steps;
  if (!Array.isArray(steps) || steps.length === 0 || steps.length > MAX_GROUP_STEPS) { return null; }
  let cards = 0;
  for (const s of steps) {
    if (!s || typeof s !== 'object' || typeof (s as DonnaAction).type !== 'string') { return null; }
    const st = s as DonnaAction;
    if (st.type === 'device_action') {
      if (typeof st.action !== 'string') { return null; }
      if (st.group_id !== undefined && st.group_id !== gid) { return null; }
    } else if (STEP_CARD_TYPES.has(st.type)) {
      cards += 1;
    } else {
      return null;
    }
  }
  return cards <= 1 ? {groupId: gid, steps: steps as DonnaAction[]} : null;
}

const CHANNELS: Record<string, string> = {media: 'Medien', ring: 'Klingelton', alarm: 'Wecker', call: 'Anruf'};
const s = (v: unknown): string => (typeof v === 'string' ? v : '');

/** Kurze Beschreibung eines Schritts für Karte und Ergebniszeile. */
export function describeStep(a: DonnaAction): string {
  if (a.type === 'device_action') {
    switch (a.action) {
      case 'flashlight': return a.state === 'off' ? 'Taschenlampe aus' : 'Taschenlampe an';
      case 'volume': {
        const ch = CHANNELS[s(a.channel)] ?? 'Lautstärke';
        switch (a.mode) {
          case 'up': return `${ch} lauter`;
          case 'down': return `${ch} leiser`;
          case 'mute': return `${ch} stumm`;
          case 'unmute': return `${ch} wieder an`;
          default: return `${ch} auf ${typeof a.percent === 'number' ? a.percent : '?'} Prozent`;
        }
      }
      case 'open_app': return `App öffnen: ${s(a.app)}`;
      case 'open_settings': return `Einstellungen öffnen: ${s(a.page)}`;
      case 'set_timer': return `Timer ${typeof a.minutes === 'number' ? a.minutes : '?'} Minuten`;
      case 'set_alarm': return `Wecker ${s(a.time)} Uhr`;
      case 'set_brightness':
        switch (a.mode) {
          case 'up': return 'Helligkeit heller';
          case 'down': return 'Helligkeit dunkler';
          default: return typeof a.percent === 'number' ? `Helligkeit auf ${a.percent} Prozent` : 'Helligkeit';
        }
      case 'dnd': return a.state === 'off' ? 'Nicht stören aus' : 'Nicht stören an';
      case 'lock_screen': return 'Bildschirm sperren';
      case 'screenshot': return 'Screenshot';
      case 'open_camera': return a.capture === 'video' ? 'Videokamera öffnen' : 'Kamera öffnen';
      default: return 'Geräteaktion';
    }
  }
  const who = s(a.name) || s(a.number);
  switch (a.type) {
    case 'sms': return `SMS an ${who}${s(a.message) ? `: ${s(a.message)}` : ''}`;
    case 'whatsapp': return `WhatsApp an ${who}${s(a.message) ? `: ${s(a.message)}` : ''}`;
    case 'call': return `Anruf an ${who}`;
    case 'create_event': return `Termin: ${s(a.title) || 'Termin'}`;
    default: return 'Aktion';
  }
}

export const STATE_LABEL: Record<StepState, string> = {
  angestossen: 'angestoßen',
  braucht_berechtigung: 'braucht Berechtigung',
  nicht_unterstuetzt: 'nicht unterstützt',
  fehlgeschlagen: 'fehlgeschlagen',
  ausgelassen: 'ausgelassen',
};

/** Ergebnisart des Geräts -> Zustand der Zeile. Vom Gate abgelehnte Schritte (aus, gesperrt, mehrdeutig, ungültig) = ausgelassen. */
export function stateOfOutcome(kind: string): StepState {
  switch (kind) {
    case 'ok': return 'angestossen';
    case 'needs_permission': return 'braucht_berechtigung';
    case 'unsupported': return 'nicht_unterstuetzt';
    case 'disabled':
    case 'blocked':
    case 'rejected':
    case 'ambiguous': return 'ausgelassen';
    default: return 'fehlgeschlagen';
  }
}

export function resultOfOutcome(step: DonnaAction, o: DeviceActionOutcome | undefined): StepResult {
  const label = describeStep(step);
  if (!o) { return {label, state: 'fehlgeschlagen', undoable: false, card: false}; }
  const state = stateOfOutcome(o.kind);
  return {
    label,
    state,
    ...(state === 'ausgelassen' ? {note: o.message} : {}),
    undoable: state === 'angestossen' && o.undoable && o.undoId !== undefined,
    ...(state === 'angestossen' && o.undoable && o.undoId !== undefined ? {undoId: o.undoId} : {}),
    card: false,
  };
}

export type CardOutcome = 'angestossen' | 'fehlgeschlagen' | 'ausgelassen';

export interface StepRunDeps {
  /** Führt EINEN Geräteschritt über den bestehenden Weg aus (Gate und Schalter je Aktionsart prüft das Gerät). */
  runDevice: (step: DonnaAction) => Promise<DeviceActionOutcome[]>;
  /** Zeigt die Stufe-2-Karte über den bestehenden Bestätigungsweg und wartet auf Mikes Entscheidung. */
  offerCard: (step: DonnaAction) => Promise<CardOutcome>;
}

/**
 * Führt die Schritte NACHEINANDER in gesprochener Reihenfolge aus. Ein abgelehnter oder fehlgeschlagener Schritt hält
 * die übrigen nicht auf. Die Stufe-2-Karte steht an ihrer Position (kein eigener Sende-Pfad, kein Auto-Send hier).
 */
export async function runStepGroup(steps: ReadonlyArray<DonnaAction>, deps: StepRunDeps): Promise<StepResult[]> {
  const out: StepResult[] = [];
  for (const step of steps) {
    if (isStepCard(step)) {
      let r: CardOutcome;
      try { r = await deps.offerCard(step); } catch { r = 'fehlgeschlagen'; }
      out.push({label: describeStep(step), state: r, undoable: false, card: true});
    } else if (step.type === 'device_action') {
      let o: DeviceActionOutcome | undefined;
      try { o = (await deps.runDevice(step))[0]; } catch { o = undefined; }
      out.push(resultOfOutcome(step, o));
    }
    // alles andere kommt durch parseStepGroup nie hierher; hier ebenfalls nie etwas ausführen
  }
  return out;
}

export function countStarted(results: ReadonlyArray<StepResult>): {started: number; total: number} {
  return {started: results.filter(r => r.state === 'angestossen').length, total: results.length};
}

export function summaryTitle(results: ReadonlyArray<StepResult>): string {
  const {started, total} = countStarted(results);
  return `${started} von ${total} angestoßen`;
}

export function canUndoAny(results: ReadonlyArray<StepResult>): boolean {
  return results.some(r => r.undoable && r.undoId !== undefined);
}

/**
 * "Alles rückgängig": nimmt die zurücknehmbaren Geräteschritte in UMGEKEHRTER Reihenfolge zurück (je über die
 * Einzel-Ablage des Geräts: Fenster, Einmaligkeit, Schalter bleiben dort geprüft) und nennt ehrlich, was nicht
 * zurückgeht. Ein Fehlschlag eines Schritts hält die übrigen nicht auf.
 */
export async function undoStepGroup(
  results: ReadonlyArray<StepResult>,
  undo: (undoId: number) => Promise<DeviceActionOutcome>,
): Promise<string[]> {
  const lines: string[] = [];
  for (const r of [...results].reverse()) {
    if (r.state !== 'angestossen') { continue; }
    if (r.undoable && r.undoId !== undefined) {
      let msg: string;
      try { msg = (await undo(r.undoId)).message; } catch { msg = 'Das konnte ich nicht zurücknehmen.'; }
      lines.push(`${r.label}: ${msg}`);
    } else if (r.card) {
      lines.push(`${r.label}: Das lässt sich nicht zurückholen (eine gesendete Nachricht, ein Anruf oder ein Termin bleibt, wie er ist).`);
    } else {
      lines.push(`${r.label}: Das lässt sich nicht zurücknehmen.`);
    }
  }
  return lines;
}

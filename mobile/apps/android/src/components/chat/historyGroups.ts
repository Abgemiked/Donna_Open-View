/**
 * #134 P8: Verlauf nach Tagen gruppieren (rein, lokale Zeit). Tagesnamen sind ausgeschrieben und ohne Intl,
 * damit sie auf jedem Geraet und im Test gleich ausfallen.
 */
import type {SessionInfo} from '@donna/shared';

export interface SessionGroup {
  /** Tagesbeginn in lokaler Zeit (ms), stabiler Schluessel. */
  day: number;
  title: string;
  data: SessionInfo[];
}

const WEEKDAYS = ['Sonntag', 'Montag', 'Dienstag', 'Mittwoch', 'Donnerstag', 'Freitag', 'Samstag'];
const MONTHS = [
  'Januar', 'Februar', 'März', 'April', 'Mai', 'Juni',
  'Juli', 'August', 'September', 'Oktober', 'November', 'Dezember',
];

const startOfDay = (d: Date): number => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
const pad2 = (n: number): string => String(n).padStart(2, '0');

/** Tagesschluessel der Gruppe fuer ungueltige/fehlende Zeitstempel ("Älter", am Ende). */
export const UNKNOWN_DAY = Number.NEGATIVE_INFINITY;

export function dayTitle(day: Date, now: Date): string {
  const diffDays = Math.round((startOfDay(now) - startOfDay(day)) / 86_400_000);
  if (diffDays === 0) { return 'Heute'; }
  if (diffDays === 1) { return 'Gestern'; }
  const base = `${WEEKDAYS[day.getDay()]}, ${day.getDate()}. ${MONTHS[day.getMonth()]}`;
  return day.getFullYear() === now.getFullYear() ? base : `${base} ${day.getFullYear()}`;
}

/** Uhrzeit "HH:MM" (lokal) aus Unix-Sekunden. */
export function timeLabel(startedAtSec: number): string {
  if (!Number.isFinite(startedAtSec)) { return '--:--'; }
  const d = new Date(startedAtSec * 1000);
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
}

export function groupSessionsByDay(sessions: SessionInfo[], now: Date = new Date()): SessionGroup[] {
  const valid = sessions.filter(s => Number.isFinite(s.started_at));
  const invalid = sessions.filter(s => !Number.isFinite(s.started_at));
  const sorted = [...valid].sort((a, b) => b.started_at - a.started_at);
  const groups: SessionGroup[] = [];
  for (const s of sorted) {
    const d = new Date(s.started_at * 1000);
    const key = startOfDay(d);
    const last = groups[groups.length - 1];
    if (last && last.day === key) {
      last.data.push(s);
    } else {
      groups.push({day: key, title: dayTitle(d, now), data: [s]});
    }
  }
  if (invalid.length > 0) {
    // stabil: Reihenfolge der Liste bleibt
    groups.push({day: UNKNOWN_DAY, title: 'Älter', data: invalid});
  }
  return groups;
}

/**
 * #134 P8: Wahl und Darstellung der Hinweisart (rein, ohne React). Eine Art ist nie nur Farbe: jede hat ein Icon und ein
 * Sprechwort fuer Screenreader. Die Farbrolle verweist auf einen Palette-Eintrag (keine eigenen Werte).
 */
import type {IconName} from '../theme/iconPaths';

export const NOTICE_KINDS = ['info', 'warning', 'error'] as const;
export type NoticeKind = (typeof NOTICE_KINDS)[number];

export interface NoticeVisual {
  icon: IconName;
  /** Wird dem Text fuer Screenreader vorangestellt ("Fehler: ..."). */
  spoken: string;
  /** Palette-Eintrag fuer Icon und Rand. */
  tone: 'accent' | 'warning' | 'danger';
}

const VISUALS: Record<NoticeKind, NoticeVisual> = {
  info: {icon: 'idea', spoken: 'Hinweis', tone: 'accent'},
  warning: {icon: 'alert', spoken: 'Warnung', tone: 'warning'},
  // anderes Icon als die Warnung: in der dunklen Farbwelt sind warning und danger beide Bernstein (Palette bleibt)
  error: {icon: 'close', spoken: 'Fehler', tone: 'danger'},
};

export interface NoticeAnnounce {
  role: 'alert' | 'text';
  liveRegion: 'none' | 'polite';
}

/**
 * Wie ein Hinweis angesagt wird - jede Meldung hoechstens EINMAL: Fehler per Rolle `alert` (ohne Live-Region),
 * Warnung per Live-Region, Info nie; Fortschrittshinweise (Prozentstand) nie, damit nicht jeder Schritt vorgelesen wird.
 */
export function noticeAnnounce(kind: NoticeKind, progress = false): NoticeAnnounce {
  if (progress) { return {role: 'text', liveRegion: 'none'}; }
  if (kind === 'error') { return {role: 'alert', liveRegion: 'none'}; }
  if (kind === 'warning') { return {role: 'text', liveRegion: 'polite'}; }
  return {role: 'text', liveRegion: 'none'};
}

export function noticeVisual(kind: NoticeKind): NoticeVisual {
  return VISUALS[kind];
}

/** Piper-Stimme: Laden ist nur ein Hinweis; ein Fehler ist eine Warnung, weil Edge einspringt und Donna weiter spricht. */
export function piperNoticeKind(status: 'downloading' | 'not_downloaded', hasError: boolean): NoticeKind {
  return status === 'not_downloaded' && hasError ? 'warning' : 'info';
}

/** Verlauf: ohne Anmeldung (kurz warten, erneut oeffnen) nur eine Warnung; ein Netzfehler ist ein Fehler. */
export function historyNoticeKind(err: 'no_token' | 'network'): NoticeKind {
  return err === 'no_token' ? 'warning' : 'error';
}

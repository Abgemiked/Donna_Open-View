/**
 * Geräte-Aktionen (#147 Paket A): Brücke zum nativen Modul `DeviceActions`.
 *
 * EINE Quelle der Wahrheit für die Schalter ist die native Pref (je Aktionsart, Standard aus; Schlüssel wie
 * DeviceActionKind.kt und das Backend). Ausgeführt wird nativ: Positivliste, Grenzen, Freigabe je Aktionsart und
 * Obergrenze prüft das Gerät noch einmal selbst (fail-closed). Fehlt das native Modul oder schlägt es fehl, gibt es
 * eine ehrliche Meldung statt eines Erfolgs.
 */
import {NativeEventEmitter, NativeModules, PermissionsAndroid, Platform} from 'react-native';
import type {DonnaAction} from '@donna/shared';

export const DEVICE_ACTION_DEFAULT = false;

export interface DeviceActionKindInfo {
  key: string;
  label: string;
  hint: string;
}

export const DEVICE_ACTION_KINDS: ReadonlyArray<DeviceActionKindInfo> = [
  {key: 'flashlight', label: 'Taschenlampe', hint: 'An- und ausschalten.'},
  {key: 'volume', label: 'Lautstärke', hint: 'Medien, Klingelton, Wecker und Anruf lauter, leiser oder auf einen Wert stellen.'},
  {key: 'open_app', label: 'Apps öffnen', hint: 'Eine App starten. Banking-, Zahlungs- und Passwort-Apps öffnet Donna nie.'},
  {key: 'open_settings', label: 'Einstellungen öffnen', hint: 'Eine Einstellungsseite zeigen. Funk schaltet Donna nie selbst.'},
  {key: 'timer_alarm', label: 'Wecker und Timer ohne Rückfrage', hint: 'Stellen, ohne dass sich die Uhr-App öffnet.'},
  {key: 'brightness', label: 'Helligkeit', hint: 'Heller, dunkler oder auf einen Wert stellen. Braucht „Systemeinstellungen ändern“.'},
  {key: 'dnd', label: 'Nicht stören', hint: 'An- und ausschalten (nur Prioritäts-Anrufe und Wecker kommen durch). Braucht den Zugriff auf „Nicht stören“.'},
  {key: 'lock_screen', label: 'Bildschirm sperren', hint: 'Sperrt das Handy. Braucht die Bedienungshilfe von Donna.'},
  {key: 'screenshot', label: 'Screenshot', hint: 'Landet in der Galerie; Donna zeigt dir eine Vorschau (Tipp öffnet das Bild, braucht Zugriff auf Fotos). Hochgeladen wird nichts. Banking- und Passwort-Apps werden nicht aufgenommen. Braucht die Bedienungshilfe von Donna.'},
  {key: 'open_camera', label: 'Kamera öffnen', hint: 'Öffnet nur die Kamera — auslösen musst du selbst.'},
  {key: 'calendar_edit', label: 'Termine ändern und löschen', hint: 'Verschieben, umbenennen, löschen — nur deine eigenen Termine, immer mit Vorher/Nachher und erst nach deinem Tipp. Löschen geht nicht rückgängig. Braucht die Kalender-Berechtigung.'},
  {key: 'email_draft', label: 'E-Mail-Entwurf öffnen', hint: 'Öffnet nur einen Entwurf in der Mail-App. Gesendet wird nie von Donna.'},
];

/** Arten mit Sonderrecht: geführter Hinweis unter dem Schalter (Status + Knopf zur Systemseite, nur auf Tipp). */
export const DEVICE_ACTION_PERMISSIONS: Readonly<Record<string, string>> = {
  brightness: 'Systemeinstellungen ändern',
  dnd: 'Zugriff auf „Nicht stören“',
  lock_screen: 'Bedienungshilfe von Donna',
  screenshot: 'Bedienungshilfe von Donna',
  calendar_edit: 'Kalender-Zugriff (lesen und schreiben)',
};

export type DevicePermissionState = 'granted' | 'missing' | 'n/a';

interface DeviceActionsNative {
  getEnabled?(): Promise<string[]>;
  setEnabled?(key: string, enabled: boolean): Promise<boolean>;
  run?(actionsJson: string): Promise<string>;
  undo?(undoId: number): Promise<string>;
  getPermissionState?(key: string): Promise<string>;
  openPermissionSettings?(key: string): Promise<boolean>;
  openScreenshot?(uri: string): Promise<boolean>;
}

/**
 * Rückmeldung des Geräts zu einer Aktion. `undoable`: es gibt etwas zurückzunehmen; `undoId` ist die Kennung DIESER
 * Aktion (die Taste wirkt nur auf sie; das Gerät hält die letzten 3 Aktionen je 2 Minuten).
 */
export interface DeviceActionOutcome {
  kind: string;
  message: string;
  undoable: boolean;
  undoId?: number;
  /** Die Taste öffnet nur die Uhr-App (Timer/Wecker lassen sich nicht automatisch löschen): sie darf nicht "Rückgängig" heißen. */
  undoOpensClock?: boolean;
}

const native = (): DeviceActionsNative | undefined =>
  (NativeModules as {DeviceActions?: DeviceActionsNative}).DeviceActions;

/** Gibt es die native Brücke überhaupt? (ohne sie zeigt die Oberfläche den Abschnitt nicht) */
export function isDeviceActionsAvailable(): boolean {
  return typeof native()?.getEnabled === 'function';
}

const KNOWN = new Set(DEVICE_ACTION_KINDS.map(k => k.key));

/** Eingeschaltete Arten (Schlüssel). Fehler oder fehlende Brücke -> nichts eingeschaltet. */
export async function getDeviceActionKinds(): Promise<string[]> {
  try {
    const raw = await native()?.getEnabled?.();
    return Array.isArray(raw) ? raw.filter((k): k is string => typeof k === 'string' && KNOWN.has(k)) : [];
  } catch {
    return [];
  }
}

/** Setzt EINE Art; liefert den von nativ bestätigten Zustand (Unbekanntes, Fehler, fehlende Brücke -> false). */
export async function setDeviceActionKind(key: string, enabled: boolean): Promise<boolean> {
  if (!KNOWN.has(key)) {
    return false;
  }
  try {
    return (await native()?.setEnabled?.(key, enabled)) === true;
  } catch {
    return false;
  }
}

/** Status des Sonderrechts je Art (nur lesen). Unbekannt, Fehler, fehlende Brücke -> 'n/a' (kein Knopf, kein Versprechen). */
export async function getDevicePermissionState(key: string): Promise<DevicePermissionState> {
  if (!(key in DEVICE_ACTION_PERMISSIONS)) {
    return 'n/a';
  }
  try {
    const s = await native()?.getPermissionState?.(key);
    return s === 'granted' || s === 'missing' ? s : 'n/a';
  } catch {
    return 'n/a';
  }
}

/** Öffnet die passende Systemseite — nur auf Tipp von Mike (nie automatisch). true = Seite ging auf. */
export async function openDevicePermissionSettings(key: string): Promise<boolean> {
  if (!(key in DEVICE_ACTION_PERMISSIONS)) {
    return false;
  }
  try {
    return (await native()?.openPermissionSettings?.(key)) === true;
  } catch {
    return false;
  }
}

// ── #219 Screenshot-Vorschau ─────────────────────────────────────────────────────────────────────────────────────────
export const SCREENSHOT_SAVED_EVENT = 'DeviceScreenshotSaved';

/** Nur echte MediaStore-Bild-URIs sind Vorschau-fähig (nie eine Adresse aus anderer Quelle anzeigen oder öffnen). */
export function isMediaImageUri(uri: unknown): uri is string {
  return typeof uri === 'string' && /^content:\/\/media\/external(?:_primary)?\/images\/media\/\d{1,18}$/.test(uri);
}

/** Meldet die URI jedes neuen Screenshots (kommt erst nach dem Auslösen, nur wenn das Leserecht erteilt ist). */
export function subscribeScreenshotSaved(onUri: (uri: string) => void): () => void {
  const mod = native();
  if (!mod) {
    return () => undefined;
  }
  try {
    const emitter = new NativeEventEmitter(mod as never);
    const sub = emitter.addListener(SCREENSHOT_SAVED_EVENT, (uri: unknown) => {
      if (isMediaImageUri(uri)) {
        onUri(uri);
      }
    });
    return () => sub.remove();
  } catch {
    return () => undefined;
  }
}

/** Öffnet GENAU dieses Bild in der Galerie (ACTION_VIEW auf die URI). false = nicht geöffnet. */
export async function openScreenshot(uri: string): Promise<boolean> {
  if (!isMediaImageUri(uri)) {
    return false;
  }
  try {
    return (await native()?.openScreenshot?.(uri)) === true;
  } catch {
    return false;
  }
}

/**
 * Bitte um das Leserecht für Bilder, damit Donna die Screenshot-Vorschau finden kann (nur Metadaten/URI; nichts wird
 * hochgeladen). Nur beim Einschalten von „Screenshot“. Abgelehnt = Screenshot geht weiter, nur ohne Vorschau.
 */
export async function requestScreenshotPreviewPermission(): Promise<boolean> {
  try {
    const perm = Number(Platform.Version) >= 33
      ? PermissionsAndroid.PERMISSIONS.READ_MEDIA_IMAGES
      : PermissionsAndroid.PERMISSIONS.READ_EXTERNAL_STORAGE;
    if (await PermissionsAndroid.check(perm)) {
      return true;
    }
    return (await PermissionsAndroid.request(perm)) === PermissionsAndroid.RESULTS.GRANTED;
  } catch {
    return false;
  }
}

export function isDeviceAction(a: DonnaAction | undefined | null): boolean {
  return a?.type === 'device_action';
}

const FAILED = 'Das konnte ich nicht ausführen.';
const failedOutcome = (): DeviceActionOutcome => ({kind: 'failed', message: FAILED, undoable: false});

const toOutcome = (r: unknown): DeviceActionOutcome | null => {
  const o = r as {kind?: unknown; message?: unknown; undoable?: unknown; undoId?: unknown; undoOpensClock?: unknown} | null;
  if (!o || typeof o.message !== 'string' || o.message.length === 0) {
    return null;
  }
  const undoId = typeof o.undoId === 'number' ? o.undoId : undefined;
  return {
    kind: typeof o.kind === 'string' ? o.kind : 'failed',
    message: o.message,
    // ohne Kennung kein Rückgängig (die Taste muss an ihre Aktion gebunden sein)
    undoable: o.undoable === true && undoId !== undefined,
    ...(undoId !== undefined ? {undoId} : {}),
    ...(o.undoOpensClock === true ? {undoOpensClock: true} : {}),
  };
};

/**
 * Antworttexte, die nur das ANSTOSSEN beschreiben ("Ich stoße den Timer für 5 Minuten an"). Aus dem Verlauf darf daraus
 * nie wieder eine Bestätigungskarte werden (ein Tipp würde den Timer ein zweites Mal setzen).
 */
export function looksLikeDeviceAckText(text: string): boolean {
  return /\b(?:sto(?:ß|ss)e|angesto(?:ß|ss)en)\b/i.test(text) && !/antippen/i.test(text);
}
// #208: das Gerät lehnt unbekannte Felder ab. useChat hängt `card_session` (#155) an JEDE Server-Aktion; ohne diesen
// Filter wurde jede Chat-Geräteaktion mit "Das kann ich so nicht ausführen." abgelehnt (und nie gemeldet).
const DEVICE_WIRE_FIELDS = ['type', 'action', 'state', 'channel', 'mode', 'percent', 'app', 'page', 'minutes', 'time',
  'label', 'capture', 'action_id', 'group_id'] as const;

/** Nur die Felder der Server-Aktion, die das Gerät kennt (nie App-interne Zusätze wie `card_session`/`card_state`). */
export function deviceWireOf(action: DonnaAction): Record<string, unknown> {
  const src = action as unknown as Record<string, unknown>;
  const out: Record<string, unknown> = {};
  for (const k of DEVICE_WIRE_FIELDS) {
    if (src[k] !== undefined) {
      out[k] = src[k];
    }
  }
  return out;
}

/**
 * Führt die Geräte-Aktionen einer Antwort aus (Chat-Weg, dasselbe native Modul wie das Overlay).
 * Liefert je Aktion die kurze Rückmeldung des Geräts. Nicht-Geräte-Aktionen werden nie weitergereicht.
 */
export async function runDeviceActions(actions: ReadonlyArray<DonnaAction>): Promise<DeviceActionOutcome[]> {
  const mine = actions.filter(isDeviceAction);
  if (mine.length === 0) {
    return [];
  }
  const run = native()?.run;
  if (typeof run !== 'function') {
    return [failedOutcome()];
  }
  try {
    const raw = await run(JSON.stringify(mine.map(deviceWireOf)));
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) {
      return [failedOutcome()];
    }
    const outcomes = parsed.map(toOutcome).filter((o): o is DeviceActionOutcome => o !== null);
    return outcomes.length > 0 ? outcomes : [failedOutcome()];
  } catch {
    return [failedOutcome()];
  }
}

/**
 * Nimmt die Geräte-Aktion mit der Kennung [undoId] zurück (nur diese; das Gerät prüft Freigabe, Zeitgrenze und
 * Reihenfolge erneut). Ohne Kennung nie.
 */
export async function undoDeviceAction(undoId: number): Promise<DeviceActionOutcome> {
  try {
    const undo = native()?.undo;
    if (typeof undo !== 'function' || typeof undoId !== 'number') {
      return failedOutcome();
    }
    const o = toOutcome(JSON.parse(await undo(undoId)));
    return o ? {...o, undoable: false} : failedOutcome();
  } catch {
    return failedOutcome();
  }
}

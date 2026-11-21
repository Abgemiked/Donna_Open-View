/**
 * DonnaSetup — TypeScript-Wrapper für das native DonnaSetupModule.kt
 * (Ersteinrichtungs-Assistent / Onboarding).
 *
 * Enthält den Schritt-Katalog (Texte, Gruppen, Abhängigkeiten) und die reine Gate-Logik.
 * Schlüssel sind identisch mit SetupSteps.kt.
 *
 * NativeModules wird bewusst erst beim Aufruf gelesen (nicht beim Import),
 * damit Tests das Modul pro Test setzen/entfernen können.
 */
import {NativeModules, Platform} from 'react-native';

export type SetupGroup = 'required' | 'features' | 'advanced';

export type SetupStepKey =
  | 'mic'
  | 'notifications'
  | 'overlay'
  | 'assistant'
  | 'battery'
  | 'exact_alarm'
  | 'location'
  | 'location_bg'
  | 'contacts'
  | 'phone'
  | 'calendar'
  | 'camera'
  | 'activity'
  | 'accessibility'
  | 'notif_listener'
  | 'usage_stats'
  | 'health';

export interface SetupStepDef {
  key: SetupStepKey;
  group: SetupGroup;
  title: string;
  /** Ein Satz: wofür Donna das braucht. */
  description: string;
  /** Zusätzlicher Hinweis (z. B. "ausgegraut?"), optional. */
  hint?: string;
  /** Schritt wird erst freigeschaltet, wenn dieser Schritt erledigt ist. */
  dependsOn?: SetupStepKey;
  /** Eigener Text, wenn der Schritt als "blocked" gemeldet wird (statt "Abgelehnt …"). */
  blockedLabel?: string;
  /** Invasiv: Einrichten verlangt eine bewusste Bestätigung. */
  confirm?: {title: string; message: string};
}

export const SETUP_GROUP_TITLES: Record<SetupGroup, string> = {
  required: 'Pflicht',
  features: 'Für Funktionen (empfohlen)',
  advanced: 'Erweitert (optional)',
};

export const SETUP_GROUP_SUBTITLES: Record<SetupGroup, string> = {
  required: 'Ohne diese Schritte kann Donna nicht zuverlässig arbeiten.',
  features: 'Schaltet einzelne Funktionen frei. Du kannst Schritte überspringen.',
  advanced: 'Weitreichende Zugriffe. Bitte jeden Schritt einzeln und bewusst bestätigen.',
};

export const SETUP_STEPS: readonly SetupStepDef[] = [
  // ── Pflicht ────────────────────────────────────────────────────────────────
  {
    key: 'mic',
    group: 'required',
    title: 'Mikrofon',
    description: 'Damit du mit Donna sprechen kannst.',
  },
  {
    key: 'notifications',
    group: 'required',
    title: 'Benachrichtigungen',
    description: 'Damit Donna dir Nachrichten und Erinnerungen schicken kann.',
  },
  {
    key: 'overlay',
    group: 'required',
    title: 'Über anderen Apps anzeigen',
    description: 'Damit sich Donna per Seitentaste über jeder App öffnen lässt.',
  },
  {
    key: 'assistant',
    group: 'required',
    title: 'Standard-Assistent',
    description: 'Damit die Seitentaste Donna statt Bixby startet.',
  },
  {
    key: 'battery',
    group: 'required',
    title: 'Akku-Optimierung aus',
    description: 'Damit Donna im Hintergrund erreichbar bleibt und Push-Nachrichten ankommen.',
  },
  // ── Für Funktionen ─────────────────────────────────────────────────────────
  {
    key: 'exact_alarm',
    group: 'features',
    title: 'Exakte Wecker',
    description: 'Damit sich Donna nach einem Beenden zuverlässiger neu starten kann.',
  },
  {
    key: 'location',
    group: 'features',
    title: 'Standort',
    description:
      'Für Wetter, Navigation und Orts-Erinnerungen. Dein Standort wird alle 5 Minuten an deinen Donna-Server übertragen.',
    hint: 'Bitte „Genau“ wählen – „Ungefähr“ reicht nicht.',
  },
  {
    key: 'location_bg',
    group: 'features',
    title: 'Standort im Hintergrund',
    description: 'Für Erinnerungen, wenn du einen Ort erreichst oder verlässt.',
    hint: 'Auf der nächsten Seite „Immer zulassen“ wählen.',
    dependsOn: 'location',
    blockedLabel: 'Noch nicht auf „Immer“ gestellt',
  },
  {
    key: 'contacts',
    group: 'features',
    title: 'Kontakte',
    description: 'Damit Donna Namen in Anrufe und Nachrichten umwandeln kann.',
  },
  {
    key: 'phone',
    group: 'features',
    title: 'Telefon',
    description: 'Damit Donna auf Wunsch Anrufe starten kann.',
  },
  {
    key: 'calendar',
    group: 'features',
    title: 'Kalender',
    description: 'Damit Donna Termine lesen und eintragen kann.',
  },
  {
    key: 'camera',
    group: 'features',
    title: 'Kamera',
    description: 'Für die Bildanalyse („Was ist das?“).',
  },
  {
    key: 'activity',
    group: 'features',
    title: 'Körperliche Aktivität',
    description: 'Damit Donna erkennt, ob du gehst, läufst oder fährst.',
  },
  // ── Erweitert ──────────────────────────────────────────────────────────────
  {
    key: 'accessibility',
    group: 'advanced',
    title: 'Eingabehilfe (Bildschirmtexte lesen)',
    description:
      'Donna liest automatisch den sichtbaren Text aus ausgewählten Apps (u. a. WhatsApp, YouTube, TikTok, Instagram, News) und sendet ihn an deinen Donna-Server.',
    hint: 'Falls ausgegraut: App-Info → Drei-Punkte-Menü → „Eingeschränkte Einstellungen zulassen“.',
    confirm: {
      title: 'Eingabehilfe aktivieren?',
      message:
        'Donna liest damit automatisch den sichtbaren Text aus ausgewählten Apps – darunter WhatsApp (auch Chat-Inhalte), YouTube, TikTok, Instagram, Facebook, Discord, Reddit und News-Apps – ' +
        'und sendet bis zu 2000 Zeichen davon (höchstens alle 45 Sekunden pro App) an deinen Donna-Server. ' +
        'Ausgenommen sind Passwortfelder sowie bekannte Banking- und Passwort-Manager-Apps.\n\n' +
        'Das ist eine weitreichende Erlaubnis. Im nächsten Schritt aktivierst du „Donna“ in den Eingabehilfe-Einstellungen.',
    },
  },
  {
    key: 'notif_listener',
    group: 'advanced',
    title: 'Benachrichtigungszugriff',
    description:
      'Donna kann deine eingehenden Benachrichtigungen (App, Titel, Text) sehen; die letzten 20 liegen nur im Arbeitsspeicher. Zusätzlich wird der gerade laufende Titel/Interpret deiner Medien-App alle 5 Minuten an deinen Donna-Server übertragen.',
    hint: 'Falls ausgegraut: App-Info → Drei-Punkte-Menü → „Eingeschränkte Einstellungen zulassen“.',
    confirm: {
      title: 'Benachrichtigungszugriff erlauben?',
      message:
        'Donna sieht damit die Benachrichtigungen deiner Apps (Absender, Titel, Text) und merkt sich die letzten 20 nur im Arbeitsspeicher – nichts wird dauerhaft gespeichert. ' +
        'Ausgenommen sind Passwort-Manager, Authenticator-Apps und System-Benachrichtigungen. ' +
        'Dieselbe Freigabe erlaubt Donna außerdem, den gerade laufenden Titel und Interpreten deiner Medien-App zu erkennen; diese Angabe wird alle 5 Minuten an deinen Donna-Server übertragen. ' +
        'Im nächsten Schritt schaltest du „Donna“ in den Einstellungen ein.',
    },
  },
  {
    key: 'usage_stats',
    group: 'advanced',
    title: 'Nutzungszugriff',
    description:
      'Donna sieht, welche Apps du zuletzt am meisten genutzt hast. Diese Liste wird alle 5 Minuten an deinen Donna-Server übertragen.',
    confirm: {
      title: 'Nutzungszugriff erlauben?',
      message:
        'Donna erfährt damit, welche Apps du nutzt (die 20 meistgenutzten der letzten 30 Minuten) und überträgt diese Liste alle 5 Minuten an deinen Donna-Server. ' +
        'Im nächsten Schritt schaltest du „Donna“ in den Einstellungen ein.',
    },
  },
  {
    key: 'health',
    group: 'advanced',
    title: 'Gesundheitsdaten (Health Connect)',
    description:
      'Schlaf, Schritte und Ruhepuls fließen in Donnas Tagesüberblick ein und werden einmal täglich an deinen Donna-Server übertragen.',
    confirm: {
      title: 'Gesundheitsdaten freigeben?',
      message:
        'Donna liest Schlaf, Schritte und Ruhepuls einmal täglich aus Health Connect und überträgt sie an deinen Donna-Server. ' +
        'Du bestätigst die Freigabe im nächsten Dialog.',
    },
  },
];

export const ONBOARDING_DONE_KEY = 'onboarding_done';
/** Nutzer hat den Wizard bewusst verschoben ("Später einrichten") — Gate sperrt dann nicht mehr. */
export const ONBOARDING_DEFERRED_KEY = 'onboarding_deferred';
/** JSON-Array der Schritte, die der Nutzer mit "Nicht jetzt" übersprungen hat. */
export const ONBOARDING_SKIPPED_KEY = 'onboarding_skipped';
/** Maximale Wartezeit auf den nativen Status, danach gilt die Prüfung als fehlgeschlagen. */
export const STATUS_TIMEOUT_MS = 5000;

export interface StepStatus {
  granted: boolean;
  /** false = auf diesem Gerät / Android-Version nicht anwendbar. */
  available: boolean;
  /** true = dauerhaft abgelehnt; "Einrichten" öffnet die App-Einstellungen. */
  blocked?: boolean;
}

export type SetupStatus = Partial<Record<SetupStepKey, StepStatus>>;

interface DonnaSetupNative {
  getSetupStatus(): Promise<SetupStatus>;
  requestStep(key: SetupStepKey): Promise<StepStatus & {key: SetupStepKey}>;
  openBixbyGuide(): Promise<void>;
}

function nativeModule(): DonnaSetupNative | undefined {
  if (Platform.OS !== 'android') {
    return undefined;
  }
  return (NativeModules as {DonnaSetupModule?: DonnaSetupNative}).DonnaSetupModule;
}

/** Gibt es das native Setup-Modul (nur Android-App, nicht in Tests/anderen Plattformen)? */
export function isSetupAvailable(): boolean {
  return nativeModule() != null;
}

export async function getSetupStatus(): Promise<SetupStatus> {
  const mod = nativeModule();
  if (!mod) {
    throw new Error('DonnaSetupModule nicht verfügbar');
  }
  return mod.getSetupStatus();
}

/**
 * Status mit Zeitlimit: `null` bei Timeout (z. B. Health-Connect-Hänger), Fehler werden weitergereicht.
 * Verhindert Dauer-Spinner beim App-Start.
 */
export async function getSetupStatusWithTimeout(
  ms: number = STATUS_TIMEOUT_MS,
): Promise<SetupStatus | null> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<null>(resolve => {
    timer = setTimeout(() => resolve(null), ms);
  });
  try {
    return await Promise.race([getSetupStatus(), timeout]);
  } finally {
    if (timer !== undefined) {
      clearTimeout(timer);
    }
  }
}

export async function requestSetupStep(
  key: SetupStepKey,
): Promise<StepStatus & {key: SetupStepKey}> {
  const mod = nativeModule();
  if (!mod) {
    throw new Error('DonnaSetupModule nicht verfügbar');
  }
  return mod.requestStep(key);
}

export async function openBixbyGuide(): Promise<void> {
  await nativeModule()?.openBixbyGuide();
}

// ─── Reine Logik (ohne native Aufrufe, testbar) ────────────────────────────

export function stepsOfGroup(group: SetupGroup): SetupStepDef[] {
  return SETUP_STEPS.filter(s => s.group === group);
}

/** Erledigt = erteilt ODER auf diesem Gerät nicht anwendbar (nur wenn granted gemeldet wird). */
export function isStepDone(status: SetupStatus, key: SetupStepKey): boolean {
  return status[key]?.granted === true;
}

/** Schritt auf diesem Gerät gar nicht möglich (z. B. Health Connect nicht installiert). */
export function isStepUnavailable(status: SetupStatus, key: SetupStepKey): boolean {
  const s = status[key];
  return s != null && !s.available && !s.granted;
}

/** Voraussetzung des Schritts (dependsOn) noch offen? */
export function isStepLocked(status: SetupStatus, def: SetupStepDef): boolean {
  return def.dependsOn != null && !isStepDone(status, def.dependsOn);
}

export function missingRequired(status: SetupStatus): SetupStepDef[] {
  return stepsOfGroup('required').filter(s => !isStepDone(status, s.key));
}

/** Alle Pflicht-Schritte erledigt? Fehlender Status eines Schritts zählt als offen. */
export function isRequiredComplete(status: SetupStatus): boolean {
  return missingRequired(status).length === 0;
}

/** Mit "Nicht jetzt" übersprungen (nur für Funktionen/Erweitert erlaubt, nie für Pflicht). */
export function isStepSkipped(
  status: SetupStatus,
  skipped: readonly SetupStepKey[],
  def: SetupStepDef,
): boolean {
  if (def.group === 'required' || isStepDone(status, def.key)) {
    return false;
  }
  return skipped.includes(def.key) || isSkippedViaDependency(status, skipped, def);
}

/**
 * Wird der Schritt, von dem `def` abhängt, übersprungen (und ist nicht erledigt), gilt `def`
 * automatisch als übersprungen — sonst bliebe z. B. "Alles eingerichtet" unerreichbar, wenn
 * "Standort" übersprungen wird und "Standort im Hintergrund" gesperrt bliebe.
 */
export function isSkippedViaDependency(
  status: SetupStatus,
  skipped: readonly SetupStepKey[],
  def: SetupStepDef,
): boolean {
  const dep = def.dependsOn;
  return dep != null && skipped.includes(dep) && !isStepDone(status, dep);
}

/**
 * Offene Schritte, die für "Alles eingerichtet" zählen: Pflicht + Funktionen (empfohlen).
 * Erledigte, nicht verfügbare und übersprungene Schritte zählen nicht.
 */
export function openCoreSteps(
  status: SetupStatus,
  skipped: readonly SetupStepKey[] = [],
): SetupStepDef[] {
  return SETUP_STEPS.filter(
    s =>
      s.group !== 'advanced' &&
      !isStepDone(status, s.key) &&
      !isStepUnavailable(status, s.key) &&
      !isStepSkipped(status, skipped, s),
  );
}

/** Offene optionale (erweiterte) Schritte — getrennt von "Noch offen" als "Optional" auszuweisen. */
export function openOptionalSteps(
  status: SetupStatus,
  skipped: readonly SetupStepKey[] = [],
): SetupStepDef[] {
  return SETUP_STEPS.filter(
    s =>
      s.group === 'advanced' &&
      !isStepDone(status, s.key) &&
      !isStepUnavailable(status, s.key) &&
      !isStepSkipped(status, skipped, s),
  );
}

/** "Alles eingerichtet": Pflicht und Funktionen erledigt oder bewusst übersprungen (Erweitert zählt nicht). */
export function isAllSetUp(
  status: SetupStatus,
  skipped: readonly SetupStepKey[] = [],
): boolean {
  return openCoreSteps(status, skipped).length === 0;
}

/**
 * Nächster sinnvoller Schritt: zuerst offene Pflicht-Schritte, dann empfohlene.
 * Gesperrte (dependsOn offen), nicht verfügbare und übersprungene Schritte werden übersprungen.
 * Erweiterte Schritte werden NICHT automatisch vorgeschlagen (müssen bewusst gewählt werden).
 */
export function nextStep(
  status: SetupStatus,
  skipped: readonly SetupStepKey[] = [],
): SetupStepDef | undefined {
  return openCoreSteps(status, skipped).find(s => !isStepLocked(status, s));
}

/** Schritte sicher aus dem gespeicherten JSON lesen (unbekannte/kaputte Werte ignorieren). */
export function parseSkipped(raw: string | null | undefined): SetupStepKey[] {
  if (!raw) {
    return [];
  }
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) {
      return [];
    }
    const valid = new Set<string>(SETUP_STEPS.filter(s => s.group !== 'required').map(s => s.key));
    return parsed.filter((k): k is SetupStepKey => typeof k === 'string' && valid.has(k));
  } catch {
    return [];
  }
}

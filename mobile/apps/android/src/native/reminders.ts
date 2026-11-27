/**
 * Erinnerungen (#153): Brücke zum nativen Modul `Reminders`.
 *
 * EINE Quelle der Wahrheit für den Schalter "Darf Nicht stören durchbrechen" ist die native Pref (Standard aus).
 * Der Schalter legt nur einen zweiten Benachrichtigungskanal an; ob Android ihn durchlässt, entscheidet die
 * Systemfreigabe der Nutzerin (`dndGranted`). Fehlt die Brücke oder schlägt sie fehl, gilt der sichere Zustand (alles aus).
 */
import {NativeModules} from 'react-native';

export const REMINDER_DND_DEFAULT = false;

export interface ReminderState {
  /** Schalter in der App */
  dndBypass: boolean;
  /** Android lässt den Kanal tatsächlich durch (Systemfreigabe erteilt) */
  dndGranted: boolean;
  /** Sonderberechtigung Vollbild-Benachrichtigung (Android 14+) erteilt */
  fullScreenAllowed: boolean;
}

interface RemindersNative {
  getState?(): Promise<unknown>;
  setDndBypass?(enabled: boolean): Promise<unknown>;
  openFullScreenSettings?(): Promise<boolean>;
  openChannelSettings?(): Promise<boolean>;
}

const SAFE: ReminderState = {dndBypass: REMINDER_DND_DEFAULT, dndGranted: false, fullScreenAllowed: false};

const native = (): RemindersNative | undefined => (NativeModules as {Reminders?: RemindersNative}).Reminders;

export function isRemindersAvailable(): boolean {
  return typeof native()?.getState === 'function';
}

const toState = (raw: unknown): ReminderState => {
  const o = raw as {dndBypass?: unknown; dndGranted?: unknown; fullScreenAllowed?: unknown} | null;
  return {
    dndBypass: o?.dndBypass === true,
    dndGranted: o?.dndGranted === true,
    fullScreenAllowed: o?.fullScreenAllowed === true,
  };
};

export async function getReminderState(): Promise<ReminderState> {
  try {
    const raw = await native()?.getState?.();
    return raw == null ? {...SAFE} : toState(raw);
  } catch {
    return {...SAFE};
  }
}

/** Setzt den Schalter; liefert den von nativ bestätigten Zustand (Fehler oder fehlende Brücke -> aus). */
export async function setReminderDndBypass(enabled: boolean): Promise<ReminderState> {
  try {
    const raw = await native()?.setDndBypass?.(enabled);
    return raw == null ? {...SAFE} : toState(raw);
  } catch {
    return {...SAFE};
  }
}

export async function openFullScreenSettings(): Promise<boolean> {
  try {
    return (await native()?.openFullScreenSettings?.()) === true;
  } catch {
    return false;
  }
}

export async function openReminderChannelSettings(): Promise<boolean> {
  try {
    return (await native()?.openChannelSettings?.()) === true;
  } catch {
    return false;
  }
}

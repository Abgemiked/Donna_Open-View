/**
 * onboardingGate — entscheidet beim App-Start, ob der Einrichtungs-Assistent vor dem
 * ChatScreen gezeigt wird (App.tsx).
 *
 * Regel: Wizard, wenn das Flag `onboarding_done` fehlt (erster Start / Update, damit der
 * Nutzer die optionalen Schritte einmal sieht) ODER wenn ein Pflicht-Schritt aktuell offen
 * ist (z. B. nach `adb install -r`, das Spezialzugriffe zurücksetzt).
 *
 * Niemand wird ausgesperrt:
 *  - Hat der Nutzer den Wizard bewusst verschoben ("Später einrichten", Flag `onboarding_deferred`),
 *    sperrt das Gate nie wieder; der Wizard ist dann nur noch über ⚙️ im Chat erreichbar.
 *  - Fail-open: Ist das native Modul nicht verfügbar, die Prüfung zu langsam (Timeout) oder
 *    fehlgeschlagen, wird der Chat gezeigt.
 */
import AsyncStorage from '@react-native-async-storage/async-storage';
import {
  ONBOARDING_DEFERRED_KEY,
  ONBOARDING_DONE_KEY,
  ONBOARDING_SKIPPED_KEY,
  STATUS_TIMEOUT_MS,
  getSetupStatusWithTimeout,
  isRequiredComplete,
  isSetupAvailable,
  parseSkipped,
  type SetupStepKey,
} from '../native/DonnaSetup';

/** Einzelnes Flag lesen — ein Storage-Fehler darf die Gate-Entscheidung nicht kippen. */
async function readFlag(key: string): Promise<string | null> {
  try {
    return await AsyncStorage.getItem(key);
  } catch (e) {
    console.warn(`[Onboarding] Flag ${key} nicht lesbar:`, e);
    return null;
  }
}

export async function shouldShowOnboarding(timeoutMs: number = STATUS_TIMEOUT_MS): Promise<boolean> {
  if (!isSetupAvailable()) {
    return false;
  }
  try {
    const deferred = await readFlag(ONBOARDING_DEFERRED_KEY);
    if (deferred === 'true') {
      return false;
    }
    const [done, status] = await Promise.all([
      readFlag(ONBOARDING_DONE_KEY),
      getSetupStatusWithTimeout(timeoutMs),
    ]);
    if (status == null) {
      console.warn('[Onboarding] Status-Timeout — Chat wird gezeigt');
      return false;
    }
    return done !== 'true' || !isRequiredComplete(status);
  } catch (e) {
    console.warn('[Onboarding] Gate-Prüfung fehlgeschlagen — Chat wird gezeigt:', e);
    return false;
  }
}

/**
 * Pflicht-Schritte erledigt: Flag setzen UND "Später einrichten" aufheben. Sonst würde jemand, der
 * zuerst verschoben und später über ⚙️ abgeschlossen hat, nach `adb install -r` (Spezialrechte
 * zurückgesetzt) nie wieder das Gate bekommen.
 */
export async function markOnboardingDone(): Promise<void> {
  try {
    await AsyncStorage.setItem(ONBOARDING_DONE_KEY, 'true');
  } catch (e) {
    console.warn('[Onboarding] Flag konnte nicht gespeichert werden:', e);
  }
  try {
    await AsyncStorage.removeItem(ONBOARDING_DEFERRED_KEY);
  } catch (e) {
    console.warn('[Onboarding] Verschieben-Flag nicht löschbar:', e);
  }
}

/** Fehlt aktuell ein Pflicht-Recht? (für den Warn-Punkt am ⚙️; false bei Fehler/Timeout/ohne Modul) */
export async function hasMissingRequired(): Promise<boolean> {
  if (!isSetupAvailable()) {
    return false;
  }
  try {
    const status = await getSetupStatusWithTimeout(STATUS_TIMEOUT_MS);
    return status != null && !isRequiredComplete(status);
  } catch {
    return false;
  }
}

/** "Später einrichten": Gate sperrt danach nicht mehr (kein onboarding_done!). */
export async function markOnboardingDeferred(): Promise<void> {
  try {
    await AsyncStorage.setItem(ONBOARDING_DEFERRED_KEY, 'true');
  } catch (e) {
    console.warn('[Onboarding] Verschieben konnte nicht gespeichert werden:', e);
  }
}

export async function loadSkippedSteps(): Promise<SetupStepKey[]> {
  return parseSkipped(await readFlag(ONBOARDING_SKIPPED_KEY));
}

export async function saveSkippedSteps(keys: SetupStepKey[]): Promise<void> {
  try {
    await AsyncStorage.setItem(ONBOARDING_SKIPPED_KEY, JSON.stringify(keys));
  } catch (e) {
    console.warn('[Onboarding] Übersprungene Schritte nicht speicherbar:', e);
  }
}

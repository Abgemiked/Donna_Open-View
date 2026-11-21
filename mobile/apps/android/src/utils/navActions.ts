/**
 * Navigations-Actions (navigate / tesla_navigate / navigate_disambiguate / navigate_not_found).
 *
 * Reine Logik ohne UI, damit ChatScreen schlank bleibt und alles ohne Native-Umgebung testbar ist.
 *
 * Backend-Vertrag (backend/app/routes/chat.py, Geocoding vor Navigation):
 *  - navigate / tesla_navigate: { destination, [resolved_address (kurz), nav_address (volle Adresse), lat, lon] }
 *  - navigate_disambiguate:     { nav_type, query, options: [{label (kurz, max. 60 Zeichen), address (volle Adresse), lat, lon}] }
 *  - navigate_not_found:        { nav_type, query }
 * `nav_type` fehlt bei älteren Backends → dann Handy-Navigation ('navigate').
 */
import type {DonnaAction} from '@donna/shared';
import {sendToTesla, TeslaNavError} from '../native/TeslaNav';

export type NavType = 'navigate' | 'tesla_navigate';

export interface NavOption {
  /** Kurzer Anzeigetext (max. 60 Zeichen) — nur für die Karte */
  label: string;
  /** Volle Adresse — DIE wird gesendet (Fallback: label bei älteren Backends) */
  address?: string;
  lat?: number;
  lon?: number;
}

export const NAV_DISAMBIGUATE = 'navigate_disambiguate';
export const NAV_NOT_FOUND = 'navigate_not_found';
export const TESLA_NAVIGATE = 'tesla_navigate';

/** true für Rückfrage-/Fehler-Actions des Geocodings. */
export function isNavChoiceAction(action: DonnaAction): boolean {
  return action.type === NAV_DISAMBIGUATE || action.type === NAV_NOT_FOUND;
}

/** Sicherer Text für <Text>: nur Strings (getrimmt), alles andere → '' (verhindert "Objects are not valid as React child"). */
export function str(v: unknown): string {
  return typeof v === 'string' ? v.trim() : '';
}

/** Ziel-Art der Action: Tesla nur, wenn ausdrücklich so markiert. */
export function navTypeOf(action: DonnaAction): NavType {
  return action.type === TESLA_NAVIGATE || action.nav_type === TESLA_NAVIGATE ? 'tesla_navigate' : 'navigate';
}

/** Anzeige-/Sendeziel: volle Adresse (nav_address) > kurze aufgelöste Adresse > gesprochenes Ziel > Suche. */
export function getNavTarget(action: DonnaAction): string {
  return str(action.nav_address) || str(action.resolved_address) || str(action.destination) || str(action.query);
}

/** Was für einen gewählten Treffer gesendet wird: volle Adresse, sonst das (ggf. gekürzte) Label. */
export function getOptionTarget(option: NavOption): string {
  return str(option.address) || str(option.label);
}

/** Gültige Optionen der Rückfrage (leere Labels fliegen raus, Koordinaten nur wenn numerisch). */
export function parseNavOptions(action: DonnaAction): NavOption[] {
  const raw = action.options;
  if (!Array.isArray(raw)) {
    return [];
  }
  const out: NavOption[] = [];
  for (const o of raw) {
    const label = str((o as {label?: unknown} | null)?.label);
    if (!label) {
      continue;
    }
    const lat = (o as {lat?: unknown}).lat;
    const lon = (o as {lon?: unknown}).lon;
    const address = str((o as {address?: unknown}).address);
    out.push({
      label,
      address: address || undefined,
      lat: typeof lat === 'number' && Number.isFinite(lat) ? lat : undefined,
      lon: typeof lon === 'number' && Number.isFinite(lon) ? lon : undefined,
    });
  }
  return out;
}

/** Freundliche Meldung für navigate_not_found. */
export function notFoundText(action: DonnaAction): string {
  const query = str(action.query);
  const where = navTypeOf(action) === 'tesla_navigate' ? ' für den Tesla' : '';
  return query
    ? `Ich habe „${query}“ nicht gefunden${where}. Nenn mir bitte eine genauere Adresse oder den Ort dazu.`
    : `Ich habe das Ziel nicht gefunden${where}. Nenn mir bitte eine genauere Adresse.`;
}

/**
 * Sendet das Ziel an die Tesla-App und liefert die Chat-Bestätigung (fängt alle Fehler → nie ein throw).
 */
export async function sendTargetToTesla(target: string): Promise<string> {
  try {
    await sendToTesla(target);
    // Intent-Erfolg heisst nur "an die Tesla-App uebergeben" — ob das Auto das Ziel uebernimmt, wissen wir nicht.
    return `An Tesla-App übergeben: ${target.trim()} ✓`;
  } catch (e) {
    const code = e instanceof TeslaNavError ? e.code : 'FAILED';
    switch (code) {
      case 'NOT_INSTALLED':
        return 'Tesla-App nicht installiert — Ziel nicht gesendet';
      case 'EMPTY':
        return 'Kein Ziel für den Tesla angegeben';
      case 'UNAVAILABLE':
        return 'Tesla-Anbindung auf diesem Gerät nicht verfügbar';
      default:
        return 'Senden an Tesla fehlgeschlagen — bitte in der Tesla-App versuchen';
    }
  }
}

/**
 * ContactsBridge — TypeScript-Wrapper fuer das native ContactsModule.kt
 *
 * Liefert Kontakt-Lookup nach Name fuer Donnas Action-Karten.
 * Permission READ_CONTACTS wird via PermissionsAndroid angefragt.
 */
import {NativeModules, PermissionsAndroid} from 'react-native';

const {ContactsModule} = NativeModules as {
  ContactsModule?: {
    hasReadPermission(): Promise<boolean>;
    searchByName(query: string): Promise<ContactMatch[]>;
  };
};

export interface ContactMatch {
  name: string;
  number: string;
  contactId: number;
}

export async function ensureContactsPermission(): Promise<boolean> {
  if (!ContactsModule) return false;
  try {
    const has = await ContactsModule.hasReadPermission();
    if (has) return true;
    const result = await PermissionsAndroid.request(
      PermissionsAndroid.PERMISSIONS.READ_CONTACTS,
      {
        title: 'Kontakte fuer Donna',
        message: 'Donna braucht Zugriff auf deine Kontakte um Anrufe und WhatsApp-Nachrichten an Personen zu schicken.',
        buttonPositive: 'Erlauben',
        buttonNegative: 'Nicht jetzt',
      },
    );
    return result === PermissionsAndroid.RESULTS.GRANTED;
  } catch {
    return false;
  }
}

/**
 * Sucht Kontakte deren Name das Query-Substring enthaelt.
 * Liefert max. 10 sortierte Treffer (exact > startsWith > contains).
 * Bei fehlender Permission oder leerem Query: leeres Array.
 */
export async function searchContactsByName(query: string): Promise<ContactMatch[]> {
  if (!ContactsModule) return [];
  const trimmed = query.trim();
  if (!trimmed) return [];
  const granted = await ensureContactsPermission();
  if (!granted) return [];
  try {
    return await ContactsModule.searchByName(trimmed);
  } catch {
    return [];
  }
}

/**
 * Bequemlichkeit: Liefert besten Match (= erstes Element) wenn EINDEUTIG,
 * sonst null wenn mehrere oder kein Treffer. Eindeutig = nur 1 Treffer ODER
 * der erste Treffer ist exakter Name-Match (case-insensitive).
 */
export async function findBestContact(query: string): Promise<ContactMatch | null> {
  const matches = await searchContactsByName(query);
  if (matches.length === 0) return null;
  if (matches.length === 1) return matches[0];
  const q = query.trim().toLowerCase();
  const exact = matches.find(m => m.name.toLowerCase() === q);
  return exact ?? null;
}

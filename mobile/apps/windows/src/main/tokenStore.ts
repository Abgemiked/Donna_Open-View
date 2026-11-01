/**
 * DONNA-103: Token-Speicher für Windows via electron.safeStorage (DPAPI).
 *
 * Main-Process only — safeStorage ist nur im Main-Prozess verfügbar.
 * Der Renderer erhält den Token via IPC (get-token / set-token).
 */

import { app, safeStorage } from 'electron';
import { join } from 'path';
import { existsSync, readFileSync, writeFileSync, unlinkSync } from 'fs';

const TOKEN_FILE = join(app.getPath('userData'), 'donna_token.enc');

/**
 * Gibt den gespeicherten Token zurück oder null wenn keiner vorhanden.
 */
export async function getToken(): Promise<string | null> {
  try {
    if (!existsSync(TOKEN_FILE)) return null;
    if (!safeStorage.isEncryptionAvailable()) {
      // Fallback: unverschlüsselt (nur in Entwicklungsumgebungen ohne DPAPI)
      return readFileSync(TOKEN_FILE, 'utf8');
    }
    const encrypted = readFileSync(TOKEN_FILE);
    return safeStorage.decryptString(encrypted);
  } catch {
    return null;
  }
}

/**
 * Speichert den Token verschlüsselt via DPAPI.
 */
export async function saveToken(token: string): Promise<void> {
  if (!safeStorage.isEncryptionAvailable()) {
    // Fallback: unverschlüsselt (Entwicklungsumgebung)
    writeFileSync(TOKEN_FILE, token, 'utf8');
    return;
  }
  const encrypted = safeStorage.encryptString(token);
  writeFileSync(TOKEN_FILE, encrypted);
}

/**
 * Gibt true zurück wenn ein Token gespeichert ist.
 */
export async function hasToken(): Promise<boolean> {
  return existsSync(TOKEN_FILE);
}

/**
 * Löscht den gespeicherten Token (für Re-Pairing).
 */
export async function clearToken(): Promise<void> {
  try {
    if (existsSync(TOKEN_FILE)) {
      unlinkSync(TOKEN_FILE);
    }
  } catch {
    // ignore
  }
}

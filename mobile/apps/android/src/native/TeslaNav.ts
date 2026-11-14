/**
 * TeslaNav — TypeScript-Wrapper für das native TeslaNavModule.kt
 *
 * Sendet ein Navigationsziel LAUTLOS ans Auto: expliziter ACTION_SEND-Intent (text/plain) an
 * com.teslamotors.tesla / com.tesla.share.ShareActivity. Die Tesla-App zeigt kurz "an Tesla gesendet".
 */
import {NativeModules} from 'react-native';

export type TeslaNavErrorCode = 'NOT_INSTALLED' | 'EMPTY' | 'UNAVAILABLE' | 'FAILED';

export class TeslaNavError extends Error {
  readonly code: TeslaNavErrorCode;
  constructor(code: TeslaNavErrorCode, message: string) {
    super(message);
    this.name = 'TeslaNavError';
    this.code = code;
  }
}

interface TeslaNavNative {
  sendToTesla(text: string): Promise<boolean>;
}

/** Native Reject-Codes (TeslaNavModule.kt) → JS-Fehlercodes. */
function mapNativeCode(code: unknown): TeslaNavErrorCode {
  switch (code) {
    case 'TESLA_NOT_INSTALLED':
      return 'NOT_INSTALLED';
    case 'EMPTY_TEXT':
      return 'EMPTY';
    default:
      return 'FAILED';
  }
}

/**
 * Schickt `text` (Adresse/Ziel) an die Tesla-App. Resolved bei Erfolg, wirft sonst TeslaNavError.
 */
export async function sendToTesla(text: string): Promise<void> {
  const mod = (NativeModules as {TeslaNavModule?: TeslaNavNative}).TeslaNavModule;
  if (!mod) {
    throw new TeslaNavError('UNAVAILABLE', 'Tesla-Modul nicht verfügbar');
  }
  const trimmed = (text ?? '').trim();
  if (!trimmed) {
    throw new TeslaNavError('EMPTY', 'Kein Ziel angegeben');
  }
  try {
    await mod.sendToTesla(trimmed);
  } catch (e) {
    const code = mapNativeCode((e as {code?: unknown} | null)?.code);
    throw new TeslaNavError(code, (e as {message?: string} | null)?.message ?? 'Senden an Tesla fehlgeschlagen');
  }
}

/**
 * voiceBargeIn — „Unterbrechen per Stimme“ (#122 Paket C): EINE Quelle der Wahrheit.
 *
 * Die Einstellung liegt nativ in den SharedPreferences (PiperTTS.getVoiceBargeIn/setVoiceBargeIn, Schlüssel nur in
 * Kotlin: VoiceBargeInSetting). Blase, Overlay, Einstellungen und der Langdruck im Chat lesen/schreiben dieselbe Pref.
 * Wer den Wert im laufenden Chat braucht, abonniert Änderungen ([subscribeVoiceBargeIn]).
 *
 * Ein vorhandener AsyncStorage-Wert aus der Zeit vor Paket C ('@donna_voice_bargein') wird EINMAL übernommen — und nur,
 * wenn nativ noch nichts gespeichert ist (ein nativ gesetzter Wert gewinnt immer) — und danach ignoriert (Marker).
 * Ohne natives Modul (Tests, andere Hosts) bleibt AsyncStorage der Ersatz — die Funktion fällt nie aus.
 */
import AsyncStorage from '@react-native-async-storage/async-storage';
import {
  hasNativeVoiceBargeIn,
  nativeVoiceBargeInIsSet,
  readNativeVoiceBargeIn,
  writeNativeVoiceBargeIn,
} from './ttsEngine';

/** Standard AN (Opt-out) — identisch mit VoiceBargeInSetting.DEFAULT in ConversationLogic.kt. EINZIGE Definition in der App. */
export const VOICE_BARGE_IN_DEFAULT = true;
/** Alter Speicherort (vor Paket C). */
export const LEGACY_VOICE_BARGE_IN_KEY = '@donna_voice_bargein';
/** Marker: der alte Wert wurde übernommen und wird nicht mehr gelesen. */
export const MIGRATED_VOICE_BARGE_IN_KEY = '@donna_voice_bargein_migrated';

type Listener = (enabled: boolean) => void;
const listeners = new Set<Listener>();

/** Änderungen melden (nach erfolgreichem Schreiben). Rückgabe: abmelden. */
export function subscribeVoiceBargeIn(listener: Listener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

function notify(enabled: boolean): void {
  listeners.forEach(l => {
    try {
      l(enabled);
    } catch {
      /* ein fehlerhafter Hörer stört die anderen nicht */
    }
  });
}

/** Opt-out: nur ein ausdrücklich gespeichertes „false“ schaltet aus. */
function fromLegacy(val: string | null | undefined): boolean {
  return val === null || val === undefined ? VOICE_BARGE_IN_DEFAULT : val !== 'false';
}

let adoption: Promise<void> | null = null;

/** Alten Wert einmalig nativ übernehmen — parallele Aufrufe teilen EINE Ausführung. */
function adoptLegacyOnce(): Promise<void> {
  if (!adoption) {
    adoption = (async () => {
      try {
        if ((await AsyncStorage.getItem(MIGRATED_VOICE_BARGE_IN_KEY)) === 'true') {
          return;
        }
        // Nativ schon gesetzt (z. B. per Overlay-Langdruck)? Dann gewinnt dieser Wert immer.
        if (!(await nativeVoiceBargeInIsSet())) {
          const legacy = await AsyncStorage.getItem(LEGACY_VOICE_BARGE_IN_KEY);
          if (legacy === 'true' || legacy === 'false') {
            await writeNativeVoiceBargeIn(legacy === 'true');
          }
        }
        await AsyncStorage.setItem(MIGRATED_VOICE_BARGE_IN_KEY, 'true');
      } catch {
        /* nächster Versuch später: Marker bleibt aus */
      }
    })().finally(() => {
      adoption = null;
    });
  }
  return adoption;
}

export async function getVoiceBargeIn(): Promise<boolean> {
  if (!hasNativeVoiceBargeIn()) {
    try {
      return fromLegacy(await AsyncStorage.getItem(LEGACY_VOICE_BARGE_IN_KEY));
    } catch {
      return VOICE_BARGE_IN_DEFAULT;
    }
  }
  await adoptLegacyOnce();
  try {
    return await readNativeVoiceBargeIn();
  } catch {
    return VOICE_BARGE_IN_DEFAULT;
  }
}

/** @return der TATSÄCHLICH gültige Wert (bei Fehlschlag der bisherige, nicht der gewünschte). */
export async function setVoiceBargeIn(enabled: boolean): Promise<boolean> {
  if (!hasNativeVoiceBargeIn()) {
    try {
      await AsyncStorage.setItem(LEGACY_VOICE_BARGE_IN_KEY, String(enabled));
    } catch {
      return VOICE_BARGE_IN_DEFAULT;
    }
    notify(enabled);
    return enabled;
  }
  await adoptLegacyOnce(); // eine laufende Übernahme zuerst beenden: die ausdrückliche Wahl gewinnt
  try {
    const applied = await writeNativeVoiceBargeIn(enabled);
    try {
      await AsyncStorage.setItem(MIGRATED_VOICE_BARGE_IN_KEY, 'true'); // erst NACH erfolgreichem Schreiben
    } catch {
      /* ignorieren */
    }
    notify(applied);
    return applied;
  } catch {
    try {
      return await readNativeVoiceBargeIn();
    } catch {
      return VOICE_BARGE_IN_DEFAULT;
    }
  }
}

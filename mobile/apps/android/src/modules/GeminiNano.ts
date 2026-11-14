/**
 * DONNA-157: Gemini Nano via Android AICore
 * On-device Inferenz ohne Cloud-Roundtrip (~40% der Anfragen).
 * isAvailable() gibt false zurück solange AICore SDK nicht vollständig integriert ist.
 */
import {NativeModules, Platform} from 'react-native';

const {GeminiNano: Native} = NativeModules;

export const GeminiNano = {
  isAvailable: async (): Promise<boolean> => {
    if (Platform.OS !== 'android' || !Native) return false;
    try {
      return await Native.isAvailable();
    } catch {
      return false;
    }
  },

  generate: async (prompt: string, maxTokens = 256): Promise<string> => {
    if (!Native) throw new Error('GeminiNano module not found');
    return await Native.generate(prompt, maxTokens);
  },
};

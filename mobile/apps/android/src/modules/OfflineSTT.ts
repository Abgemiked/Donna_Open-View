/**
 * DONNA-159: Offline-STT via Android SpeechRecognizer + Samsung Gauss NPU
 * Nutzt createOnDeviceSpeechRecognizer() ab API 31 für garantiert offline Erkennung.
 * Fallback auf Online-STT wenn nicht verfügbar.
 */
import {NativeEventEmitter, NativeModules, Platform} from 'react-native';

const {OfflineSTT: Native} = NativeModules;
const emitter = Native ? new NativeEventEmitter(Native) : null;

export type STTResultHandler = (text: string) => void;
export type STTErrorHandler = (code: number) => void;

export const OfflineSTT = {
  isAvailable: async (): Promise<boolean> => {
    if (Platform.OS !== 'android' || !Native) return false;
    try {
      return await Native.isAvailable();
    } catch {
      return false;
    }
  },

  start: (locale = 'de-DE'): Promise<boolean> => {
    if (!Native) return Promise.resolve(false);
    return Native.startListening(locale);
  },

  stop: (): Promise<boolean> => {
    if (!Native) return Promise.resolve(false);
    return Native.stopListening();
  },

  onResult: (cb: STTResultHandler) => {
    if (!emitter) return {remove: () => {}};
    return emitter.addListener('OfflineSTT.onResult', (e: {text: string}) =>
      cb(e.text),
    );
  },

  onPartial: (cb: STTResultHandler) => {
    if (!emitter) return {remove: () => {}};
    return emitter.addListener('OfflineSTT.onPartial', (e: {text: string}) =>
      cb(e.text),
    );
  },

  onError: (cb: STTErrorHandler) => {
    if (!emitter) return {remove: () => {}};
    return emitter.addListener('OfflineSTT.onError', (e: {code: number}) =>
      cb(e.code),
    );
  },
};

/**
 * NativeSideButtonModule — TypeScript Interface für das native SideButtonModule
 *
 * Samsung S25 Ultra Side-Key Events:
 * - 'onSideButtonPress'       → Single Press → App aktivieren
 * - 'onSideButtonDoublePress' → Double Press → Voice-Input (Stub, DONNA-15)
 */
import {NativeModules, NativeEventEmitter, type EmitterSubscription} from 'react-native';

const {SideButtonModule} = NativeModules;

export interface SideButtonEvent {
  action: 'press' | 'double_press';
}

const emitter = SideButtonModule != null
  ? new NativeEventEmitter(SideButtonModule)
  : null;

export const NativeSideButtonModule = {
  /** Registriert einen Handler für Single-Press (App aktivieren). */
  onPress(handler: (event: SideButtonEvent) => void): EmitterSubscription | null {
    return emitter?.addListener('onSideButtonPress', handler) ?? null;
  },

  /** Registriert einen Handler für Double-Press (Voice-Input Trigger). */
  onDoublePress(handler: (event: SideButtonEvent) => void): EmitterSubscription | null {
    return emitter?.addListener('onSideButtonDoublePress', handler) ?? null;
  },

  /** Gibt an ob das native Modul verfügbar ist (nur auf Samsung-Gerät). */
  isAvailable(): boolean {
    return SideButtonModule != null;
  },
};

/**
 * DONNA-189 Phase 2: Phi-3 Mini INT4 via ONNX Runtime GenAI
 * On-device Inferenz für mittlere Komplexität (~1-3s, kein Internet nötig).
 *
 * Stufe 2 im dreistufigen Router:
 *   1. Gemini Nano  — sehr kurz, kontextfrei
 *   2. Phi-3 Mini   — mittlere Komplexität        ← diese Klasse
 *   3. Cloud        — komplex, Memory/Search
 *
 * Phase 2: ORT GenAI AAR eingebunden — generate() nutzt echte Inferenz.
 *   QNN EP (Hexagon NPU) wenn verfügbar, sonst CPU EP.
 *   Streaming via "PhiToken"-Events für Early TTS.
 */
import {NativeModules, Platform} from 'react-native';

const {PhiModule: Native} = NativeModules;

export type PhiModelStatus =
  | 'NOT_DOWNLOADED'
  | 'DOWNLOADING'
  | 'READY'
  | 'ERROR';

export type PhiDownloadResult = {
  success: boolean;
  message: string;
  fileCount?: number;
};

export const PhiModule = {
  /**
   * Gibt true zurück wenn Phi-3 Mini vollständig heruntergeladen und bereit ist.
   * Schnell (nur Datei-Check, kein Netzwerk).
   */
  isAvailable: async (): Promise<boolean> => {
    if (Platform.OS !== 'android' || !Native) return false;
    try {
      return await Native.isAvailable();
    } catch {
      return false;
    }
  },

  /**
   * Liefert den aktuellen Modell-Status.
   * 'NOT_DOWNLOADED' | 'DOWNLOADING' | 'READY' | 'ERROR'
   */
  getModelStatus: async (): Promise<PhiModelStatus> => {
    if (Platform.OS !== 'android' || !Native) return 'NOT_DOWNLOADED';
    try {
      return (await Native.getModelStatus()) as PhiModelStatus;
    } catch {
      return 'ERROR';
    }
  },

  /**
   * Download-Fortschritt in Prozent (0-100).
   * Gibt -1 zurück wenn kein Download läuft.
   */
  getDownloadProgress: async (): Promise<number> => {
    if (Platform.OS !== 'android' || !Native) return -1;
    try {
      return await Native.getDownloadProgress();
    } catch {
      return -1;
    }
  },

  /**
   * Startet den Modell-Download via Android DownloadManager.
   * Download läuft auch wenn App im Hintergrund ist.
   * Lädt nur fehlende Dateien (Resume-fähig nach App-Neustart).
   *
   * Modell: Phi-3-mini-4k-instruct-onnx, cpu-int4-rtn-block-32-acc-level-4
   * Größe: ~2.3 GB (6 Dateien)
   */
  startModelDownload: async (): Promise<PhiDownloadResult> => {
    if (Platform.OS !== 'android' || !Native) {
      return {success: false, message: 'Nur auf Android verfügbar'};
    }
    try {
      return await Native.startModelDownload();
    } catch (e) {
      return {
        success: false,
        message: e instanceof Error ? e.message : 'Unbekannter Fehler',
      };
    }
  },

  /**
   * Bricht den laufenden Download ab und räumt unvollständige Dateien auf.
   */
  cancelDownload: async (): Promise<boolean> => {
    if (Platform.OS !== 'android' || !Native) return false;
    try {
      return await Native.cancelDownload();
    } catch {
      return false;
    }
  },

  /**
   * Generiert eine Antwort on-device via Phi-3 Mini INT4 (ONNX Runtime GenAI).
   *
   * Phase 2: Echte Inferenz via ORT GenAI + QNN EP (Hexagon NPU) / CPU EP.
   * Streaming: Jeder Token wird als "PhiToken"-Event gesendet (Early TTS kompatibel).
   * generate() gibt zusätzlich den vollständigen Text zurück.
   *
   * @param prompt    Vollständiger, bereits formatierter Prompt
   * @param maxTokens Maximale Ausgabe-Token (default 512)
   */
  generate: async (prompt: string, maxTokens = 512): Promise<string> => {
    if (!Native) throw new Error('PhiModule nicht gefunden');
    return await Native.generate(prompt, maxTokens);
  },
};

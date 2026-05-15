/**
 * DONNA-153 Phase 1: Latenz-Logging für Diagnose.
 * Kein UI, nur Console-Logs. Latenz-Labels:
 * - stt_start / stt_end      → Spracheingabe-Latenz
 * - api_start / api_first_token → Zeit bis erste KI-Antwort
 * - tts_start / tts_end      → TTS-Ausgabe-Latenz
 * - roundtrip                → Gesamtlatenz STT-Start → TTS-Ende
 */

const _timestamps: Record<string, number> = {};

export function markLatency(label: string): void {
  _timestamps[label] = Date.now();
}

/**
 * Loggt einen Latenz-Wert in die Konsole (misst nicht selbst).
 * Nutze `markLatency` + `measureLatency` für automatische Messung.
 */
export function logLatency(label: string, ms: number): void {
  console.log(`[Benchmark] ${label}: ${ms}ms`);
}

/** @deprecated Nutze `logLatency` — klarerer Name. */
export const trackLatency = logLatency;

export function measureLatency(startLabel: string, endLabel: string): number {
  const start = _timestamps[startLabel];
  const end = _timestamps[endLabel] ?? Date.now();
  if (!start) return -1;
  const ms = end - start;
  console.log(`[Benchmark] ${startLabel} → ${endLabel}: ${ms}ms`);
  return ms;
}

export function clearLatencyMarks(): void {
  Object.keys(_timestamps).forEach(k => delete _timestamps[k]);
}

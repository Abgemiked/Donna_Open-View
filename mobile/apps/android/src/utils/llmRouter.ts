/**
 * DONNA-153 / DONNA-189: Dreistufiger On-Device / Cloud Router
 *
 * Routing-Logik:
 *   Stufe 1 — Gemini Nano (AICore):  kurz (<= 80 Zeichen), kontextfrei, kein Tool-Use → ~200-400ms
 *   Stufe 2 — Phi-3 Mini (ONNX):     mittlere Komplexität (81-500 Zeichen), kein Internet nötig → ~1-3s
 *   Stufe 3 — Cloud Gemini/Qwen:     komplex, Tool-Use, Memory/Search, > 500 Zeichen
 *
 * Phi-3 Mini: cpu-int4-rtn-block-32-acc-level-4, ~2.3 GB Download on first use.
 * Wird aktiviert sobald Modell heruntergeladen ist (getModelStatus() === 'READY').
 */
import {GeminiNano} from '../modules/GeminiNano';
import {PhiModule} from '../modules/PhiModule';

// ── Availability-Cache (einmalig pro App-Session) ─────────────────────────

let _geminiNanoAvailable: boolean | null = null;
let _phiAvailable: boolean | null = null;

/** Cached einmalig ob Gemini Nano bereit ist. */
export async function ensureGeminiNanoReady(): Promise<boolean> {
  if (_geminiNanoAvailable === null) {
    _geminiNanoAvailable = await GeminiNano.isAvailable();
    console.log('[Router] Gemini Nano on-device:', _geminiNanoAvailable);
  }
  return _geminiNanoAvailable;
}

/** Cached einmalig ob Phi-3 Mini bereit ist. */
export async function ensurePhiReady(): Promise<boolean> {
  if (_phiAvailable === null) {
    _phiAvailable = await PhiModule.isAvailable();
    console.log('[Router] Phi-3 Mini on-device:', _phiAvailable);
  }
  return _phiAvailable;
}

/** Invalidiert beide Caches (z.B. nach Modell-Download abgeschlossen). */
export function invalidateOnDeviceCache(): void {
  _geminiNanoAvailable = null;
  _phiAvailable = null;
}

// ── Routing-Muster ────────────────────────────────────────────────────────

/** Keywords die zwingend Cloud (Tool-Use / RAG / LTM) erfordern. */
const CLOUD_REQUIRED_PATTERNS: RegExp[] = [
  /stream|twitch|live|clip/i,
  /kalender|termin|reminder|erinnere/i,
  /brain|suche|such|finde|memory|gespeichert|merk/i,
  /crm|rechnung|steuer|auftrag/i,
  /mail|email|nachricht schick/i,
  /wetter/i,
];

/** Muster die sicher Stufe 1 (Gemini Nano, on-device kurz) beantwortet werden können. */
const GEMINI_NANO_SHORTCUT_PATTERNS: RegExp[] = [
  /^(ja|nein|ok|danke|hi|hallo|hey|super|gut|cool)\.?$/i,
  /^wie spät|^uhrzeit|^was ist die uhr/i,
  /^rechne?\s+[\d\s+\-*/().]+$/i,
];

/**
 * Bestimmt ob die Anfrage für Gemini Nano geeignet ist (Stufe 1).
 * Kriterien: <= 80 Zeichen, kein Tool-Use, kein Cloud-Keyword.
 */
async function shouldUseGeminiNano(message: string): Promise<boolean> {
  if (!(await ensureGeminiNanoReady())) return false;
  if (CLOUD_REQUIRED_PATTERNS.some(p => p.test(message))) return false;
  if (GEMINI_NANO_SHORTCUT_PATTERNS.some(p => p.test(message))) return true;
  return message.trim().length <= 80;
}

/**
 * Bestimmt ob die Anfrage für Phi-3 Mini geeignet ist (Stufe 2).
 *
 * Kriterien:
 *   - Modell heruntergeladen + initialisiert (getModelStatus === 'READY')
 *   - Kein Cloud-Keyword (kein PII, kein Realtime-Bedarf)
 *   - Länge > 80 Zeichen ODER Gemini Nano hat null/leer zurückgegeben
 *   - Länge <= 500 Zeichen (alles darüber → Cloud)
 *
 * @param message   Die Benutzer-Anfrage
 * @param fromNanoFallback  true wenn Gemini Nano null zurückgegeben hat (auch kurze Texte)
 */
async function shouldUsePhi(message: string, fromNanoFallback = false): Promise<boolean> {
  if (!(await ensurePhiReady())) return false;
  if (CLOUD_REQUIRED_PATTERNS.some(p => p.test(message))) return false;
  const len = message.trim().length;
  if (len > 500) return false; // Zu lang → Cloud
  // Phi-3 übernimmt: >80 Zeichen ODER Gemini Nano hat versagt
  return len > 80 || fromNanoFallback;
}

// ── Routed Response ───────────────────────────────────────────────────────

export type RoutingSource = 'gemini-nano' | 'phi-3-mini' | 'cloud';

export type RoutedResponse = {
  text: string;
  source: RoutingSource;
  latencyMs: number;
};

/**
 * Routet eine Anfrage durch den dreistufigen Entscheidungsbaum.
 *
 * Reihenfolge:
 *   1. Gemini Nano (Stufe 1) — sehr kurz, ~200-400ms
 *   2. Phi-3 Mini  (Stufe 2) — mittlere Komplexität, ~1-3s
 *   3. Cloud       (Stufe 3) — alles andere
 *
 * Jede Stufe fällt bei Fehler transparent auf die nächste zurück.
 */
export async function routedGenerate(
  message: string,
  cloudFallback: (msg: string) => Promise<string>,
): Promise<RoutedResponse> {
  const t0 = Date.now();

  // ── Stufe 1: Gemini Nano ────────────────────────────────────────────────
  let nanoFailed = false;
  if (await shouldUseGeminiNano(message)) {
    try {
      const text = await GeminiNano.generate(message, 256);
      if (text && text.trim().length > 0) {
        return {text, source: 'gemini-nano', latencyMs: Date.now() - t0};
      }
      // Gemini Nano gab leeren String zurück → als Fallback markieren
      nanoFailed = true;
      console.log('[Router] Gemini Nano: leere Antwort, weiter zu Phi-3');
    } catch (e) {
      nanoFailed = true;
      console.warn('[Router] Gemini Nano fehlgeschlagen, weiter zu Phi-3:', e);
    }
  }

  // ── Stufe 2: Phi-3 Mini ─────────────────────────────────────────────────
  // Phi-3 wird aktiviert wenn:
  //   a) Anfrage > 80 Zeichen (mittlere Komplexität) — oder
  //   b) Gemini Nano hat null/leer zurückgegeben (Fallback auch für kurze Texte)
  if (await shouldUsePhi(message, nanoFailed)) {
    try {
      const text = await PhiModule.generate(message, 512);
      if (text && text.trim().length > 0) {
        return {text, source: 'phi-3-mini', latencyMs: Date.now() - t0};
      }
    } catch (e) {
      console.warn('[Router] Phi-3 Mini fehlgeschlagen, weiter zu Cloud:', e);
    }
  }

  // ── Stufe 3: Cloud ──────────────────────────────────────────────────────
  try {
    const text = await cloudFallback(message);
    return {text, source: 'cloud', latencyMs: Date.now() - t0};
  } catch (e) {
    throw new Error(
      `[Router] Cloud-Fallback fehlgeschlagen: ${e instanceof Error ? e.message : String(e)}`,
    );
  }
}

// ── Legacy-Kompatibilität (DONNA-153) ────────────────────────────────────

/**
 * @deprecated Nutze routedGenerate() — gibt jetzt RoutedResponse zurück.
 * Wrapper für Code der noch shouldUseOnDevice() direkt aufruft.
 */
export async function shouldUseOnDevice(message: string): Promise<boolean> {
  return (await shouldUseGeminiNano(message)) || (await shouldUsePhi(message));
}

/** @deprecated Benutze ensureGeminiNanoReady(). */
export const ensureOnDeviceReady = ensureGeminiNanoReady;

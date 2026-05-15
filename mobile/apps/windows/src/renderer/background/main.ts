/**
 * Background Wake-Word-Listener — DONNA-33
 *
 * Ersetzt Web Speech API durch lokale Audioaufnahme + Backend-Endpoint.
 *
 * Ablauf:
 * 1. Mikrofon-Stream anfordern
 * 2. MediaRecorder läuft in 2-Sekunden-Intervallen (kontinuierlich)
 * 3. Jeder Frame wird an POST /wake-word/check gesendet
 * 4. Bei {"match": true} → electron.showOverlay() aufrufen
 * 5. Nach showOverlay: kurze Pause (3 s) um Overlay-Aktivierung nicht doppelt zu triggern
 *
 * Hotword-Enable/Disable via IPC (bestehender Mechanismus bleibt erhalten).
 */

const API_BASE = 'https://your-donna-instance.example.com';

// DONNA-103: Token wird vom Main-Prozess via IPC gesetzt (kein Hardcode mehr).
let API_TOKEN = '';
(window as any).electron?.onApiToken?.((token: string) => {
  API_TOKEN = token;
});
// Beim Start den gespeicherten Token holen
(async () => {
  const token: string | null = await (window as any).electron?.getToken?.();
  if (token) { API_TOKEN = token; }
})();

// Länge eines Audio-Frames in Millisekunden
const FRAME_MS = 2000;

// Pause nach Wake-Word-Erkennung (verhindert sofortige Re-Erkennung)
const COOLDOWN_MS = 3000;

// Minimale Frame-Größe — kleiner = wahrscheinlich Stille
const MIN_FRAME_BYTES = 500;

let enabled = true;
let isRunning = false;
let cooldownActive = false;

let stream: MediaStream | null = null;
let recorder: MediaRecorder | null = null;

// ─── Wake-Word-Check ──────────────────────────────────────────────────────────

async function checkFrame(blob: Blob): Promise<boolean> {
  if (blob.size < MIN_FRAME_BYTES) return false;

  try {
    const form = new FormData();
    const file = new File([blob], 'frame.webm', { type: blob.type || 'audio/webm' });
    form.append('audio', file);

    const res = await fetch(`${API_BASE}/wake-word/check`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${API_TOKEN}` },
      body: form,
      signal: AbortSignal.timeout(5000),
    });

    if (!res.ok) return false;

    const data = await res.json() as { match: boolean; transcript?: string };
    if (data.match) {
      console.log('[Background] Wake-Word erkannt, Transkript:', data.transcript ?? '');
    }
    return data.match === true;

  } catch (err) {
    // Netzwerkfehler / Timeout — Frame still — kein Wake-Word
    console.debug('[Background] Frame-Check Fehler:', err instanceof Error ? err.message : err);
    return false;
  }
}

// ─── Frame-Recording-Schleife ─────────────────────────────────────────────────

function recordFrame(): void {
  if (!enabled || !stream || cooldownActive) return;

  const chunks: Blob[] = [];
  const mimeType = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
    ? 'audio/webm;codecs=opus'
    : 'audio/webm';

  try {
    recorder = new MediaRecorder(stream, { mimeType });
  } catch {
    console.warn('[Background] MediaRecorder-Erstellung fehlgeschlagen');
    scheduleNextFrame(1000);
    return;
  }

  recorder.ondataavailable = (e) => {
    if (e.data.size > 0) chunks.push(e.data);
  };

  recorder.onstop = async () => {
    if (!enabled) return;

    const blob = new Blob(chunks, { type: mimeType });
    const matched = await checkFrame(blob);

    if (matched && !cooldownActive) {
      cooldownActive = true;
      console.log('[Background] Overlay wird geöffnet');
      (window as any).electron?.showOverlay?.();

      setTimeout(() => {
        cooldownActive = false;
        scheduleNextFrame(0);
      }, COOLDOWN_MS);
    } else {
      scheduleNextFrame(0);
    }
  };

  recorder.onerror = () => {
    console.debug('[Background] Recorder-Fehler — nächster Frame');
    scheduleNextFrame(500);
  };

  recorder.start();
  // Frame nach FRAME_MS stoppen → onstop feuert
  setTimeout(() => {
    if (recorder?.state === 'recording') {
      recorder.stop();
    }
  }, FRAME_MS);
}

function scheduleNextFrame(delayMs: number): void {
  if (!enabled || cooldownActive) return;
  setTimeout(recordFrame, delayMs);
}

// ─── Start / Stop ─────────────────────────────────────────────────────────────

async function startWakeWordListener(): Promise<void> {
  if (isRunning) return;
  isRunning = true;

  try {
    stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    console.log('[Background] Mikrofon bereit — Wake-Word-Listener gestartet');
    recordFrame();
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    console.error('[Background] Mikrofon-Zugriff verweigert:', msg);
    isRunning = false;
    // Retry nach 10 s (z.B. wenn Permission noch nicht erteilt)
    setTimeout(startWakeWordListener, 10_000);
  }
}

function stopWakeWordListener(): void {
  enabled = false;
  if (recorder?.state === 'recording') {
    try { recorder.stop(); } catch { /* ignore */ }
  }
  recorder = null;
  stream?.getTracks().forEach(t => t.stop());
  stream = null;
  isRunning = false;
  console.log('[Background] Wake-Word-Listener gestoppt');
}

// ─── IPC: Hotword-Enable/Disable ─────────────────────────────────────────────

(window as any).electron?.onHotwordState?.((newEnabled: boolean) => {
  if (newEnabled && !enabled) {
    enabled = true;
    cooldownActive = false;
    console.log('[Background] Hotword aktiviert');
    if (!isRunning) {
      startWakeWordListener();
    } else {
      scheduleNextFrame(0);
    }
  } else if (!newEnabled && enabled) {
    stopWakeWordListener();
  }
});

// ─── Initialer Start ──────────────────────────────────────────────────────────

startWakeWordListener();

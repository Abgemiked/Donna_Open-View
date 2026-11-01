/**
 * activity_reporter.ts — PC-Heartbeat für Donna (DONNA-94)
 *
 * Sendet alle 5 Minuten einen Heartbeat an /tracking/push sowie sofortige
 * Events bei screen_locked / pc_resume.
 *
 * Datenschutz: NUR der App-Name wird übertragen (kein Fenstertitel, keine URL).
 * active_app bleibt null bis ein natives Modul (z.B. active-win) installiert wird.
 */

import { powerMonitor, BrowserWindow } from 'electron';
import { getToken } from './tokenStore';

// DONNA-103: Token dynamisch aus safeStorage (kein Hardcode mehr).
const API_BASE = 'https://your-donna-instance.example.com';

const HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000; // 5 Minuten

// ──────────────────────────────────────────────────────────────────────────────
// HTTP-Helper
// ──────────────────────────────────────────────────────────────────────────────

async function sendTrackingEvent(type: string, extra: Record<string, unknown> = {}): Promise<void> {
  try {
    const token = await getToken();
    if (!token) return; // Noch nicht gepairt — Events still verwerfen
    await fetch(`${API_BASE}/tracking/push`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ type, ...extra }),
      signal: AbortSignal.timeout(8000),
    });
  } catch {
    // Heartbeat-Fehler werden still ignoriert — kein Einfluss auf App-Betrieb
  }
}

// ──────────────────────────────────────────────────────────────────────────────
// Heartbeat-Payload aufbauen
// ──────────────────────────────────────────────────────────────────────────────

function buildHeartbeatPayload(mainWindow: BrowserWindow): Record<string, unknown> {
  const idle_sec = powerMonitor.getSystemIdleTime();
  const donna_focused = !mainWindow.isDestroyed() && mainWindow.isFocused();

  // active_app: NUR App-Name, KEIN Fenstertitel oder URL (Datenschutz).
  // Erfordert natives Modul (active-win o.ä.) — vorerst null.
  const active_app: string | null = null;

  return {
    heartbeat: {
      device: 'pc',
      idle_sec,
      active_app,
      donna_focused,
    },
  };
}

// ──────────────────────────────────────────────────────────────────────────────
// Öffentliche API
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Startet den PC-Heartbeat-Reporter.
 * Muss nach window-ready (mainWindow existiert) aufgerufen werden.
 */
export function startActivityReporter(mainWindow: BrowserWindow): void {
  // 5-Minuten-Interval-Heartbeat
  setInterval(() => {
    if (mainWindow.isDestroyed()) return;
    const payload = buildHeartbeatPayload(mainWindow);
    sendTrackingEvent('pc_heartbeat', payload);
  }, HEARTBEAT_INTERVAL_MS);

  // Sofortiger initiales Heartbeat beim Start
  const initialPayload = buildHeartbeatPayload(mainWindow);
  sendTrackingEvent('pc_heartbeat', initialPayload);

  // Screen gesperrt
  powerMonitor.on('lock-screen', () => {
    sendTrackingEvent('screen_locked');
  });

  // PC aus Sleep / Hibernate aufgewacht
  powerMonitor.on('resume', () => {
    sendTrackingEvent('pc_resume');
  });
}

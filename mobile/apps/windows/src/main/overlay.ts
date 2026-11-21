import { BrowserWindow, screen } from 'electron';
import { join } from 'path';

let overlayWin: BrowserWindow | null = null;

const HEIGHT: Record<'idle' | 'listening' | 'response', number> = {
  idle: 82,
  listening: 210,
  response: 160,
};

export function createOverlay(): BrowserWindow {
  const { width } = screen.getPrimaryDisplay().workAreaSize;
  const winWidth = 520;
  const x = Math.round((width - winWidth) / 2);

  overlayWin = new BrowserWindow({
    width: winWidth,
    height: HEIGHT.idle,
    x,
    y: 24,
    frame: false,
    transparent: true,
    alwaysOnTop: true,
    skipTaskbar: true,
    resizable: false,
    movable: true,
    show: false,
    webPreferences: {
      preload: join(__dirname, '../preload/index.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });

  // Vollbild-Spiele (z.B. Hearthstone) würden das Overlay sonst verdecken.
  // Level 'screen-saver' ist die höchste Electron-Stufe und überlagert DirectX-Vollbild.
  overlayWin.setAlwaysOnTop(true, 'screen-saver');
  overlayWin.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true });

  if (process.env['VITE_DEV_SERVER_URL']) {
    overlayWin.loadURL(process.env['VITE_DEV_SERVER_URL'] + 'overlay/index.html');
  } else {
    overlayWin.loadFile(join(__dirname, '../renderer/overlay/index.html'));
  }

  overlayWin.on('blur', () => {
    overlayWin?.hide();
  });

  overlayWin.on('closed', () => {
    overlayWin = null;
  });

  return overlayWin;
}

export function showOverlay(state: 'idle' | 'listening' | 'response' = 'idle'): void {
  if (!overlayWin) return;
  resizeOverlay(state);
  overlayWin.showInactive();
  overlayWin.webContents.send('overlay-state', state);
}

export function hideOverlay(): void {
  overlayWin?.hide();
}

export function resizeOverlay(state: 'idle' | 'listening' | 'response'): void {
  if (!overlayWin) return;
  const [currentWidth] = overlayWin.getSize();
  overlayWin.setSize(currentWidth, HEIGHT[state], true);
}

export function destroyOverlay(): void {
  overlayWin?.destroy();
  overlayWin = null;
}

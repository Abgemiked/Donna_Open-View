import { app, BrowserWindow, globalShortcut, ipcMain, shell, session } from 'electron';
import { join } from 'path';
import { createTray, destroyTray } from './tray';
import { createOverlay, showOverlay, hideOverlay, resizeOverlay, destroyOverlay } from './overlay';
import { startActivityReporter } from './activity_reporter';
import { getToken, saveToken, hasToken, clearToken } from './tokenStore';

// Single-instance lock
if (!app.requestSingleInstanceLock()) {
  app.quit();
  process.exit(0);
}

let mainWindow: BrowserWindow | null = null;
let bgWorker: BrowserWindow | null = null;
let isQuitting = false;

function createWindow(): BrowserWindow {
  const win = new BrowserWindow({
    width: 420,
    height: 700,
    minWidth: 360,
    minHeight: 500,
    resizable: true,
    frame: false,
    show: false,
    title: 'Donna',
    backgroundColor: '#1a1a2e',
    webPreferences: {
      preload: join(__dirname, '../preload/index.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });

  if (process.env['VITE_DEV_SERVER_URL']) {
    win.loadURL(process.env['VITE_DEV_SERVER_URL']);
    win.webContents.openDevTools({ mode: 'detach' });
  } else {
    win.loadFile(join(__dirname, '../renderer/index.html'));
  }

  win.once('ready-to-show', () => win.show());

  win.on('close', e => {
    if (!isQuitting) {
      e.preventDefault();
      win.hide();
    }
  });

  win.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: 'deny' };
  });

  return win;
}

// Focus existing window when second instance launched
app.on('second-instance', () => {
  if (mainWindow) {
    if (!mainWindow.isVisible()) mainWindow.show();
    mainWindow.focus();
  }
});

// ─── Pairing Window ───────────────────────────────────────────────────────────

let pairingWindow: BrowserWindow | null = null;

function createPairingWindow(): BrowserWindow {
  const win = new BrowserWindow({
    width: 400,
    height: 300,
    resizable: false,
    frame: true,
    title: 'Donna — Verbinden',
    backgroundColor: '#1a1a2e',
    webPreferences: {
      preload: join(__dirname, '../preload/index.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });

  if (process.env['VITE_DEV_SERVER_URL']) {
    win.loadURL(process.env['VITE_DEV_SERVER_URL'] + '?pairing=1');
  } else {
    win.loadFile(join(__dirname, '../renderer/index.html'), { query: { pairing: '1' } });
  }

  win.once('ready-to-show', () => win.show());
  return win;
}

app.whenReady().then(async () => {
  // Grant microphone + geolocation permissions
  // 'media' = Mikrofon/Kamera (Web Speech API), 'geolocation' = GPS (lat/lon für Backend)
  // Origin-Check: nur eigene App-Origins dürfen sensitive Permissions anfragen
  const ALLOWED_PERMISSIONS = ['media', 'geolocation'];
  const isOwnOrigin = (url: string): boolean =>
    url.startsWith('https://your-donna-instance.example.com') ||
    url.startsWith('file://') ||       // Electron production (loadFile)
    url.startsWith('http://localhost'); // Vite dev server

  session.defaultSession.setPermissionRequestHandler((webContents, permission, callback) => {
    const requestUrl = webContents.getURL();
    if (ALLOWED_PERMISSIONS.includes(permission) && isOwnOrigin(requestUrl)) {
      callback(true);
    } else {
      callback(false);
    }
  });
  session.defaultSession.setPermissionCheckHandler((webContents, permission) => {
    const requestUrl = webContents?.getURL() ?? '';
    return ALLOWED_PERMISSIONS.includes(permission) && isOwnOrigin(requestUrl);
  });

  // DONNA-103: Token aus safeStorage laden und via IPC an Renderer übergeben
  const storedToken = await getToken();
  if (!storedToken) {
    // Kein Token → Pairing-Window zeigen, Hauptfenster noch nicht öffnen
    pairingWindow = createPairingWindow();
    // Hauptfenster wird nach erfolgreichem Pairing via IPC geöffnet
  } else {
    mainWindow = createWindow();
    // Token einmalig an Renderer schicken sobald bereit
    mainWindow.webContents.once('did-finish-load', () => {
      mainWindow?.webContents.send('api-token', storedToken);
    });
  }

  // Background worker for wake-word detection
  bgWorker = new BrowserWindow({
    width: 1, height: 1,
    show: false,
    skipTaskbar: true,
    webPreferences: {
      preload: join(__dirname, '../preload/index.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });
  if (process.env['VITE_DEV_SERVER_URL']) {
    bgWorker.loadURL(process.env['VITE_DEV_SERVER_URL'] + 'background/');
  } else {
    bgWorker.loadFile(join(__dirname, '../renderer/background/index.html'));
  }

  // Create overlay window (hidden)
  createOverlay();

  // Create tray — pass bgWorker for hotword toggle
  createTray(mainWindow, bgWorker);

  // PC-Heartbeat + screen_locked/resume Events (DONNA-94)
  startActivityReporter(mainWindow);

  // Ctrl+Shift+D — toggle window
  globalShortcut.register('CommandOrControl+Shift+D', () => {
    if (!mainWindow) return;
    if (mainWindow.isVisible() && mainWindow.isFocused()) {
      mainWindow.hide();
    } else {
      mainWindow.show();
      mainWindow.focus();
    }
  });

  app.on('activate', () => mainWindow?.show());
});

app.on('window-all-closed', () => {
  if (process.platform === 'darwin') app.quit();
});

app.on('will-quit', () => {
  globalShortcut.unregisterAll();
  destroyTray();
  destroyOverlay();
});

app.on('before-quit', () => {
  isQuitting = true;
});

// ─── IPC handlers ─────────────────────────────────────────────────────────────

ipcMain.handle('toggle-window', () => {
  if (!mainWindow) return;
  if (mainWindow.isVisible()) mainWindow.hide();
  else { mainWindow.show(); mainWindow.focus(); }
});

ipcMain.handle('quit-app', () => {
  isQuitting = true;
  app.quit();
});

ipcMain.handle('get-version', () => app.getVersion());

ipcMain.handle('minimize-to-tray', () => mainWindow?.hide());

ipcMain.handle('maximize-window', () => {
  if (!mainWindow) return;
  if (mainWindow.isMaximized()) mainWindow.unmaximize();
  else mainWindow.maximize();
});

ipcMain.handle('show-overlay', () => showOverlay('idle'));
ipcMain.handle('hide-overlay', () => hideOverlay());
ipcMain.handle('set-overlay-state', (_e, state: string) => {
  resizeOverlay(state as 'idle' | 'listening' | 'response');
});

// Hotword toggle from renderer (if needed)
ipcMain.handle('toggle-hotword', (_e, enabled: boolean) => {
  bgWorker?.webContents.send('hotword-state', enabled);
});

// DONNA-103: Token IPC handlers
ipcMain.handle('get-token', async () => getToken());
ipcMain.handle('has-token', async () => hasToken());
ipcMain.handle('clear-token', async () => clearToken());

// Pairing erfolgreich: Token speichern, Hauptfenster öffnen
ipcMain.handle('pairing-complete', async (_e, token: string) => {
  await saveToken(token);
  pairingWindow?.close();
  pairingWindow = null;
  if (!mainWindow || mainWindow.isDestroyed()) {
    mainWindow = createWindow();
  }
  mainWindow.webContents.once('did-finish-load', () => {
    mainWindow?.webContents.send('api-token', token);
  });
  mainWindow.show();
  mainWindow.focus();
});

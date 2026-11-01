import { Tray, Menu, nativeImage, BrowserWindow, app } from 'electron';
import { join } from 'path';

let tray: Tray | null = null;
let hotwordEnabled = true;

function createTrayIcon(): Electron.NativeImage {
  const assetBase = app.isPackaged
    ? join(process.resourcesPath, 'assets')
    : join(__dirname, '../../assets');

  try {
    const img = nativeImage.createFromPath(join(assetBase, 'tray-icon.png'));
    if (!img.isEmpty()) return img;
  } catch { /* ignore */ }

  const purplePng = 'iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAKklEQVRYw+3BMQEAAADCoPVP7WsIoAAAAAAAAAAAAAAAAAAAAAAAeAMBxAABPCmTlQAAAABJRU5ErkJggg==';
  return nativeImage.createFromDataURL(`data:image/png;base64,${purplePng}`);
}

function buildMenu(win: BrowserWindow, bgWorker: BrowserWindow | null): Electron.Menu {
  return Menu.buildFromTemplate([
    // Header section (non-clickable display)
    {
      label: 'Donna',
      enabled: false,
      icon: createTrayIcon().resize({ width: 16, height: 16 }),
    },
    { type: 'separator' },
    // Primary actions
    {
      label: 'Donna öffnen',
      accelerator: 'CommandOrControl+Shift+D',
      click: () => { win.show(); win.focus(); },
    },
    {
      label: 'Overlay anpinnen',
      click: () => {
        (win as any).electron?.showOverlay?.();
        win.webContents.executeJavaScript('window.electron?.showOverlay?.()').catch(() => {});
      },
    },
    { type: 'separator' },
    // Hotword toggle
    {
      label: 'Hey Donna aktiviert',
      type: 'checkbox',
      checked: hotwordEnabled,
      click: (menuItem) => {
        hotwordEnabled = menuItem.checked;
        bgWorker?.webContents.send('hotword-state', hotwordEnabled);
        // Rebuild menu to reflect new state
        if (tray) tray.setContextMenu(buildMenu(win, bgWorker));
      },
    },
    { type: 'separator' },
    {
      label: 'Beenden',
      click: () => { app.quit(); },
    },
  ]);
}

export function createTray(win: BrowserWindow, bgWorker: BrowserWindow | null = null): Tray {
  tray = new Tray(createTrayIcon());
  tray.setToolTip('Donna');

  tray.setContextMenu(buildMenu(win, bgWorker));

  // Single click → show overlay
  tray.on('click', () => {
    win.webContents.executeJavaScript('window.electron?.showOverlay?.()').catch(() => {});
  });

  return tray;
}

export function destroyTray(): void {
  tray?.destroy();
  tray = null;
}

export function updateTrayHotword(win: BrowserWindow, bgWorker: BrowserWindow | null, enabled: boolean): void {
  hotwordEnabled = enabled;
  if (tray) tray.setContextMenu(buildMenu(win, bgWorker));
}

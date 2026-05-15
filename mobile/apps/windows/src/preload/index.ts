import { contextBridge, ipcRenderer } from 'electron';

contextBridge.exposeInMainWorld('electron', {
  minimizeToTray:  () => ipcRenderer.invoke('minimize-to-tray'),
  toggleWindow:    () => ipcRenderer.invoke('toggle-window'),
  quitApp:         () => ipcRenderer.invoke('quit-app'),
  getVersion:      () => ipcRenderer.invoke('get-version'),
  maximizeWindow:  () => ipcRenderer.invoke('maximize-window'),
  toggleHotword:   (enabled: boolean) => ipcRenderer.invoke('toggle-hotword', enabled),
  // Overlay
  showOverlay:     () => ipcRenderer.invoke('show-overlay'),
  hideOverlay:     () => ipcRenderer.invoke('hide-overlay'),
  setOverlayState: (state: string) => ipcRenderer.invoke('set-overlay-state', state),
  onOverlayState:  (cb: (state: string) => void) => {
    ipcRenderer.on('overlay-state', (_e, state) => cb(state));
  },
  // Hotword state from main process
  onHotwordState: (cb: (enabled: boolean) => void) => {
    ipcRenderer.on('hotword-state', (_e, enabled) => cb(enabled));
  },
  // DONNA-103: Token-Management
  getToken:       () => ipcRenderer.invoke('get-token'),
  hasToken:       () => ipcRenderer.invoke('has-token'),
  clearToken:     () => ipcRenderer.invoke('clear-token'),
  pairingComplete: (token: string) => ipcRenderer.invoke('pairing-complete', token),
  onApiToken:     (cb: (token: string) => void) => {
    ipcRenderer.on('api-token', (_e, token) => cb(token));
  },
});

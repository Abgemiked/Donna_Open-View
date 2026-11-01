import React from 'react';
import {DeviceEventEmitter, NativeModules, Platform} from 'react-native';
import {render, fireEvent, act, waitFor} from '@testing-library/react-native';
import VoiceSettings from '../components/VoiceSettings';
import {
  ensurePiperModel,
  getEdgeVoice,
  getPiperState,
  getTtsEngine,
  isPiperAvailable,
  setEdgeVoice,
  setTtsEngine,
} from '../native/ttsEngine';

/**
 * Voice-Batch: Einstellung „Stimme“ (A/B Edge ↔ Piper-lokal) + eager Piper-Download mit Fortschritt,
 * und die dünne Brücke zum nativen PiperTTSModule.
 */
let piper: Record<string, jest.Mock>;

beforeEach(() => {
  jest.replaceProperty(Platform, 'OS', 'android');
  piper = {
    getTtsEngine: jest.fn().mockResolvedValue('edge'),
    setTtsEngine: jest.fn().mockImplementation(async (e: string) => e),
    getEdgeVoice: jest.fn().mockResolvedValue('de-DE-KatjaNeural'),
    setEdgeVoice: jest.fn().mockImplementation(async (v: string) => v),
    getPiperState: jest.fn().mockResolvedValue({status: 'not_downloaded', progress: 0, error: null}),
    ensureModel: jest.fn().mockResolvedValue('started'),
  };
  (NativeModules as Record<string, unknown>).PiperTTS = piper;
});

afterEach(() => {
  delete (NativeModules as Record<string, unknown>).PiperTTS;
  jest.restoreAllMocks();
});

describe('ttsEngine-Brücke', () => {
  it('ohne natives Modul: nicht verfügbar, sichere Defaults, kein Absturz', async () => {
    delete (NativeModules as Record<string, unknown>).PiperTTS;
    expect(isPiperAvailable()).toBe(false);
    expect(await getTtsEngine()).toBe('edge');
    expect(await setTtsEngine('piper')).toBe('piper');
    expect(await getEdgeVoice()).toBe('de-DE-KatjaNeural');
    expect(await getPiperState()).toEqual({status: 'unavailable', progress: 0, error: null});
    expect(await ensurePiperModel()).toBe('unavailable');
  });

  it('liest/schreibt Engine und Stimme nativ und normalisiert Werte', async () => {
    piper.getTtsEngine.mockResolvedValue('piper');
    expect(await getTtsEngine()).toBe('piper');
    expect(await setTtsEngine('piper')).toBe('piper');
    expect(piper.setTtsEngine).toHaveBeenCalledWith('piper');
    piper.getEdgeVoice.mockResolvedValue('de-DE-ConradNeural');
    expect(await getEdgeVoice()).toBe('de-DE-KatjaNeural');
    expect(await setEdgeVoice('de-DE-SeraphinaMultilingualNeural')).toBe('de-DE-SeraphinaMultilingualNeural');
  });

  it('Native-Fehler fallen auf sichere Defaults zurück', async () => {
    piper.getTtsEngine.mockRejectedValue(new Error('x'));
    piper.getPiperState.mockRejectedValue(new Error('x'));
    piper.ensureModel.mockRejectedValue(new Error('x'));
    expect(await getTtsEngine()).toBe('edge');
    expect((await getPiperState()).status).toBe('unavailable');
    expect(await ensurePiperModel()).toBe('unavailable');
  });

  it('getPiperState begrenzt den Fortschritt auf 0-100', async () => {
    piper.getPiperState.mockResolvedValue({status: 'downloading', progress: 250, error: null});
    expect(await getPiperState()).toEqual({status: 'downloading', progress: 100, error: null});
    piper.getPiperState.mockResolvedValue({status: 'ready', progress: -5, error: null});
    expect((await getPiperState()).progress).toBe(0);
  });
});

describe('VoiceSettings', () => {
  it('rendert nichts, wenn das native Modul fehlt', async () => {
    delete (NativeModules as Record<string, unknown>).PiperTTS;
    const {queryByTestId} = render(<VoiceSettings />);
    await act(async () => {});
    expect(queryByTestId('voice-settings')).toBeNull();
  });

  it('zeigt Umschalter, Standard Edge; Wechsel auf Piper wird nativ gespeichert', async () => {
    const {getByTestId} = render(<VoiceSettings />);
    await act(async () => {});
    expect(getByTestId('voice-engine-edge').props.accessibilityState.selected).toBe(true);
    expect(getByTestId('voice-engine-piper').props.accessibilityState.selected).toBe(false);
    await act(async () => { fireEvent.press(getByTestId('voice-engine-piper')); });
    expect(piper.setTtsEngine).toHaveBeenCalledWith('piper');
    expect(getByTestId('voice-engine-piper').props.accessibilityState.selected).toBe(true);
  });

  it('gespeicherte Wahl (Piper) wird beim Öffnen übernommen', async () => {
    piper.getTtsEngine.mockResolvedValue('piper');
    const {getByTestId} = render(<VoiceSettings />);
    await waitFor(() => expect(getByTestId('voice-engine-piper').props.accessibilityState.selected).toBe(true));
  });

  it('Edge-Stimme Katja/Seraphina umschaltbar', async () => {
    const {getByTestId} = render(<VoiceSettings />);
    await act(async () => {});
    expect(getByTestId('voice-edge-katja').props.accessibilityState.selected).toBe(true);
    await act(async () => { fireEvent.press(getByTestId('voice-edge-seraphina')); });
    expect(piper.setEdgeVoice).toHaveBeenCalledWith('de-DE-SeraphinaMultilingualNeural');
    expect(getByTestId('voice-edge-seraphina').props.accessibilityState.selected).toBe(true);
  });

  it('startet den Piper-Download EAGER beim Öffnen und zeigt den Fortschritt', async () => {
    const {getByTestId} = render(<VoiceSettings />);
    await act(async () => {});
    expect(piper.ensureModel).toHaveBeenCalledTimes(1);
    await act(async () => { DeviceEventEmitter.emit('PiperTTS.downloadProgress', {progress: 33}); });
    expect(getByTestId('voice-piper-status').props.children).toMatch(/33 %.*Edge/);
    await act(async () => { DeviceEventEmitter.emit('PiperTTS.downloadComplete', {}); });
    expect(getByTestId('voice-piper-status').props.children).toMatch(/bereit/);
  });

  it('bereits geladenes Modell: kein erneuter Download', async () => {
    piper.getPiperState.mockResolvedValue({status: 'ready', progress: 100, error: null});
    const {getByTestId} = render(<VoiceSettings />);
    await act(async () => {});
    expect(piper.ensureModel).not.toHaveBeenCalled();
    expect(getByTestId('voice-piper-status').props.children).toMatch(/bereit/);
  });

  it('Download-Fehler zeigt die Meldung und „Erneut versuchen“ startet neu', async () => {
    const {getByTestId, getByText} = render(<VoiceSettings />);
    await act(async () => {});
    await act(async () => { DeviceEventEmitter.emit('PiperTTS.downloadError', {message: 'Kein Netz'}); });
    // humanisiert: ein Satz, KEINE Technik im UI (die steht nur im Log)
    expect(getByTestId('voice-piper-status').props.children).toMatch(/konnte nicht geladen werden/);
    expect(getByTestId('voice-piper-status').props.children).not.toMatch(/Kein Netz/);
    piper.ensureModel.mockClear();
    await act(async () => { fireEvent.press(getByText('Erneut versuchen')); });
    expect(piper.ensureModel).toHaveBeenCalledTimes(1);
    expect(piper.ensureModel).toHaveBeenCalledWith(true);
  });

  it('Auto-Start ohne Zwang (nur WLAN); bei Mobilfunk „Jetzt laden“ mit Erklärung, Klick erzwingt', async () => {
    piper.ensureModel.mockResolvedValueOnce('metered');
    const {getByTestId, getByText} = render(<VoiceSettings />);
    await act(async () => {});
    expect(piper.ensureModel).toHaveBeenCalledWith(false);
    expect(getByTestId('voice-piper-status').props.children).toMatch(/nur im WLAN/);
    piper.ensureModel.mockResolvedValue('started');
    await act(async () => { fireEvent.press(getByText('Jetzt laden')); });
    expect(piper.ensureModel).toHaveBeenLastCalledWith(true);
  });
});

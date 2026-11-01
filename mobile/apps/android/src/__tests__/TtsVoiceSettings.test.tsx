import React from 'react';
import {render, fireEvent, act, waitFor} from '@testing-library/react-native';

/**
 * TB4 — Komponenten-/Verdrahtungs-Test des TTS-Einstellungs-Screens.
 *
 * @donna/shared wird vollständig gemockt: der echte Barrel zieht in Jest native Module
 * (AsyncStorage/notifee). Den echten HTTP-Pfad (fetch → GET/POST /settings/tts mit Body + Bearer)
 * prüft der separate Shared-API-Test (api.test.ts-Umfeld).
 */
const mockGet = jest.fn();
const mockSave = jest.fn();
jest.mock('@donna/shared', () => ({
  TTS_VOICES: [
    {id: 'R24r0IV80Iw9eyByq1ps', label: 'Eva'},
    {id: '3EKymYQPdS1Q9fxZBuq8', label: 'Elisabeth'},
  ],
  TTS_MODELS: [
    {id: 'eleven_v4_turbo', label: 'v4 Turbo (schnell)'},
    {id: 'eleven_v4', label: 'v4 (beste Qualität)'},
    {id: 'eleven_multilingual_v2', label: 'Multilingual v2'},
  ],
  TTS_VOICE_DEFAULT: 'R24r0IV80Iw9eyByq1ps',
  TTS_MODEL_DEFAULT: 'eleven_v4_turbo',
  getTtsSettings: (...a: unknown[]) => mockGet(...a),
  saveTtsSettings: (...a: unknown[]) => mockSave(...a),
}));

import TtsVoiceSettings from '../components/TtsVoiceSettings';

describe('TtsVoiceSettings (TB4)', () => {
  beforeEach(() => {
    mockGet.mockReset();
    mockSave.mockReset();
  });

  it('lädt + zeigt den Serverstand beim Öffnen (getTtsSettings)', async () => {
    mockGet.mockResolvedValueOnce({voice_id: '3EKymYQPdS1Q9fxZBuq8', model_id: 'eleven_v4'});
    const {getByTestId} = render(<TtsVoiceSettings />);
    await waitFor(() =>
      expect(getByTestId('tts-voice-elisabeth').props.accessibilityState.selected).toBe(true),
    );
    expect(getByTestId('tts-model-eleven_v4').props.accessibilityState.selected).toBe(true);
    expect(getByTestId('tts-voice-eva').props.accessibilityState.selected).toBe(false);
    expect(mockGet).toHaveBeenCalledTimes(1);
  });

  it('GET-Fehler (null): loadError-Status, Default-Auswahl sichtbar, Speichern möglich (nicht ausgesperrt)', async () => {
    mockGet.mockResolvedValueOnce(null);
    const {getByTestId} = render(<TtsVoiceSettings />);
    await waitFor(() => expect(getByTestId('tts-status').props.children).toMatch(/konnte nicht geladen werden/));
    expect(getByTestId('tts-voice-eva').props.accessibilityState.selected).toBe(true);
    expect(getByTestId('tts-model-eleven_v4_turbo').props.accessibilityState.selected).toBe(true);
    // savedVoice/Model = null → jede Auswahl ist speicherbar (Dirty), kein Aussperren bei Ladefehler.
    expect(getByTestId('tts-save').props.accessibilityState.disabled).toBe(false);
  });

  it('Änderung nach dem Speichern setzt den „Gespeichert"-Status zurück', async () => {
    mockGet.mockResolvedValueOnce({voice_id: 'R24r0IV80Iw9eyByq1ps', model_id: 'eleven_v4_turbo'});
    mockSave.mockResolvedValueOnce(true);
    const {getByTestId} = render(<TtsVoiceSettings />);
    await waitFor(() => expect(getByTestId('tts-voice-eva').props.accessibilityState.selected).toBe(true));
    await act(async () => {
      fireEvent.press(getByTestId('tts-model-eleven_v4'));
    });
    await act(async () => {
      fireEvent.press(getByTestId('tts-save'));
    });
    await waitFor(() => expect(getByTestId('tts-status').props.children).toMatch(/Gespeichert/));
    await act(async () => {
      fireEvent.press(getByTestId('tts-model-eleven_multilingual_v2'));
    });
    expect(getByTestId('tts-status').props.children).not.toMatch(/Gespeichert/);
  });

  it('Speichern ruft saveTtsSettings mit gewählter Stimme + Modell', async () => {
    mockGet.mockResolvedValueOnce({voice_id: 'R24r0IV80Iw9eyByq1ps', model_id: 'eleven_v4_turbo'});
    mockSave.mockResolvedValueOnce(true);
    const {getByTestId} = render(<TtsVoiceSettings />);
    await waitFor(() => expect(getByTestId('tts-voice-eva').props.accessibilityState.selected).toBe(true));
    await act(async () => {
      fireEvent.press(getByTestId('tts-voice-elisabeth'));
    });
    await act(async () => {
      fireEvent.press(getByTestId('tts-model-eleven_multilingual_v2'));
    });
    await act(async () => {
      fireEvent.press(getByTestId('tts-save'));
    });
    expect(mockSave).toHaveBeenCalledWith('3EKymYQPdS1Q9fxZBuq8', 'eleven_multilingual_v2');
    await waitFor(() => expect(getByTestId('tts-status').props.children).toMatch(/Gespeichert/));
  });

  it('Speichern ohne Änderung deaktiviert, nach Änderung aktiv (Dirty-Gate)', async () => {
    mockGet.mockResolvedValueOnce({voice_id: 'R24r0IV80Iw9eyByq1ps', model_id: 'eleven_v4_turbo'});
    const {getByTestId} = render(<TtsVoiceSettings />);
    await waitFor(() => expect(getByTestId('tts-voice-eva').props.accessibilityState.selected).toBe(true));
    expect(getByTestId('tts-save').props.accessibilityState.disabled).toBe(true);
    await act(async () => {
      fireEvent.press(getByTestId('tts-model-eleven_v4'));
    });
    expect(getByTestId('tts-save').props.accessibilityState.disabled).toBe(false);
  });

  it('Speichern-Fehler zeigt Fehlerstatus', async () => {
    mockGet.mockResolvedValueOnce({voice_id: 'R24r0IV80Iw9eyByq1ps', model_id: 'eleven_v4_turbo'});
    mockSave.mockResolvedValueOnce(false);
    const {getByTestId} = render(<TtsVoiceSettings />);
    await waitFor(() => expect(getByTestId('tts-voice-eva').props.accessibilityState.selected).toBe(true));
    await act(async () => {
      fireEvent.press(getByTestId('tts-model-eleven_v4'));
    });
    await act(async () => {
      fireEvent.press(getByTestId('tts-save'));
    });
    await waitFor(() => expect(getByTestId('tts-status').props.children).toMatch(/fehlgeschlagen/));
  });
});

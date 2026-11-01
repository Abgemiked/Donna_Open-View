import {renderHook, act} from '@testing-library/react-hooks';
import {useVoice} from '../voice/useVoice';
import type {VoiceModuleInterface, VoiceResult, VoiceError} from '../voice/types';

function makeMockModule(): VoiceModuleInterface {
  return {
    onResult: null,
    onError: null,
    onStart: null,
    onEnd: null,
    isAvailable: jest.fn().mockReturnValue(true),
    start: jest.fn().mockResolvedValue(undefined),
    stop: jest.fn().mockResolvedValue(undefined),
    destroy: jest.fn().mockResolvedValue(undefined),
  };
}

describe('useVoice', () => {
  it('starts in idle state', () => {
    const module = makeMockModule();
    const {result} = renderHook(() => useVoice({module}));
    expect(result.current.voiceState).toBe('idle');
    expect(result.current.transcript).toBe('');
    expect(result.current.isAvailable).toBe(true);
  });

  it('transitions to listening state on startListening', async () => {
    const module = makeMockModule();
    const {result} = renderHook(() => useVoice({module}));

    await act(async () => {
      await result.current.startListening();
      module.onStart?.();
    });

    expect(result.current.voiceState).toBe('listening');
  });

  it('updates partialTranscript on partial result', async () => {
    const module = makeMockModule();
    const {result} = renderHook(() => useVoice({module}));

    await act(async () => {
      module.onStart?.();
      const partial: VoiceResult = {transcript: 'Hal', isFinal: false};
      module.onResult?.(partial);
    });

    expect(result.current.partialTranscript).toBe('Hal');
  });

  it('sets final transcript and calls onTranscript callback', async () => {
    const module = makeMockModule();
    const onTranscript = jest.fn();
    const {result} = renderHook(() => useVoice({module, onTranscript}));

    await act(async () => {
      module.onStart?.();
      const final: VoiceResult = {transcript: 'Hallo Donna', isFinal: true, confidence: 0.95};
      module.onResult?.(final);
    });

    expect(result.current.transcript).toBe('Hallo Donna');
    expect(result.current.partialTranscript).toBe('');
    expect(result.current.voiceState).toBe('idle');
    expect(onTranscript).toHaveBeenCalledWith('Hallo Donna');
  });

  it('sets error state on voice error', async () => {
    const module = makeMockModule();
    const {result} = renderHook(() => useVoice({module}));

    await act(async () => {
      const err: VoiceError = {code: 'no_match', message: 'Keine Übereinstimmung'};
      module.onError?.(err);
    });

    expect(result.current.voiceState).toBe('error');
    expect(result.current.error?.code).toBe('no_match');
  });

  it('calls module.stop on stopListening when listening', async () => {
    const module = makeMockModule();
    const {result} = renderHook(() => useVoice({module}));

    await act(async () => {
      await result.current.startListening();
      module.onStart?.();
    });
    await act(async () => {
      await result.current.stopListening();
    });

    expect(module.stop).toHaveBeenCalled();
  });

  it('calls module.destroy on unmount', () => {
    const module = makeMockModule();
    const {unmount} = renderHook(() => useVoice({module}));
    unmount();
    expect(module.destroy).toHaveBeenCalled();
  });
});

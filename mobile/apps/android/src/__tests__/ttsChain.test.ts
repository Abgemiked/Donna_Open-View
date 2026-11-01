import {speakWithFallback, type TtsNatives} from '../utils/ttsChain';
import {engineOrder, normalizeEdgeVoice, normalizeEngine, otherEngine} from '../native/ttsEngine';

/**
 * Voice-Batch: A/B Edge ↔ Piper-lokal — Reihenfolge, gegenseitiger Fallback, System-TTS als letzte Stufe,
 * Abbruch nach Unterbrechung. Reine Logik; der Ton selbst ist nur am Gerät prüfbar.
 */
const VOICE = 'de-DE-KatjaNeural';

function natives(over: {
  edge?: jest.Mock;
  piper?: jest.Mock;
  onDeviceReady?: boolean;
  onDevice?: jest.Mock;
} = {}) {
  const speakViaServer = over.edge ?? jest.fn().mockResolvedValue('ok');
  const speakPiper = over.piper ?? jest.fn().mockResolvedValue('ok');
  const speakOnDevice = over.onDevice ?? jest.fn().mockResolvedValue('ok');
  const n: TtsNatives = {
    PiperTTS: {speakPiper},
    TTSModule: {speakViaServer, isOnDeviceReady: jest.fn().mockResolvedValue(over.onDeviceReady ?? true), speakOnDevice},
  };
  return {n, speakViaServer, speakPiper, speakOnDevice};
}

describe('Engine-Wahl (rein)', () => {
  it('unbekannte Werte fallen auf Edge, die andere Engine ist Fallback', () => {
    expect(normalizeEngine(undefined)).toBe('edge');
    expect(normalizeEngine('kokoro')).toBe('edge');
    expect(normalizeEngine('piper')).toBe('piper');
    expect(otherEngine('edge')).toBe('piper');
    expect(otherEngine('piper')).toBe('edge');
    expect(engineOrder('piper')).toEqual(['piper', 'edge']);
    expect(engineOrder(null)).toEqual(['edge', 'piper']);
  });

  it('nur bekannte weibliche Edge-Stimmen, sonst Katja', () => {
    expect(normalizeEdgeVoice('de-DE-SeraphinaMultilingualNeural')).toBe('de-DE-SeraphinaMultilingualNeural');
    expect(normalizeEdgeVoice('de-DE-ConradNeural')).toBe('de-DE-KatjaNeural');
    expect(normalizeEdgeVoice(undefined)).toBe('de-DE-KatjaNeural');
  });
});

describe('speakWithFallback', () => {
  it('Edge gewählt: nur Edge, mit der gewählten Stimme', async () => {
    const {n, speakViaServer, speakPiper} = natives();
    expect(await speakWithFallback('Hallo.', 'edge', n, {voice: 'de-DE-SeraphinaMultilingualNeural'})).toBe('edge');
    expect(speakViaServer).toHaveBeenCalledWith('Hallo.', 'de-DE-SeraphinaMultilingualNeural');
    expect(speakPiper).not.toHaveBeenCalled();
  });

  it('Piper gewählt: nur Piper', async () => {
    const {n, speakViaServer, speakPiper} = natives();
    expect(await speakWithFallback('Hallo.', 'piper', n, {voice: VOICE})).toBe('piper');
    expect(speakPiper).toHaveBeenCalledWith('Hallo.');
    expect(speakViaServer).not.toHaveBeenCalled();
  });

  it.each([
    ['failed'], ['fallback'], [undefined], [null], ['irgendwas'],
  ])('Edge liefert %p → Piper übernimmt', async (result) => {
    const {n, speakPiper} = natives({edge: jest.fn().mockResolvedValue(result)});
    expect(await speakWithFallback('Hallo.', 'edge', n, {voice: VOICE})).toBe('piper');
    expect(speakPiper).toHaveBeenCalledTimes(1);
  });

  it('Edge wirft (Native-Fehler) → Piper übernimmt', async () => {
    const {n} = natives({edge: jest.fn().mockRejectedValue(new Error('boom'))});
    expect(await speakWithFallback('Hallo.', 'edge', n, {voice: VOICE})).toBe('piper');
  });

  it('Piper nicht bereit ("fallback") → Edge übernimmt', async () => {
    const {n, speakViaServer} = natives({piper: jest.fn().mockResolvedValue('fallback')});
    expect(await speakWithFallback('Hallo.', 'piper', n, {voice: VOICE})).toBe('edge');
    expect(speakViaServer).toHaveBeenCalledTimes(1);
  });

  it('beide scheitern → System-TTS (nur wenn bereit)', async () => {
    const a = natives({edge: jest.fn().mockResolvedValue('failed'), piper: jest.fn().mockResolvedValue('fallback')});
    expect(await speakWithFallback('Hallo.', 'edge', a.n, {voice: VOICE})).toBe('system');
    expect(a.speakOnDevice).toHaveBeenCalledWith('Hallo.');

    const b = natives({
      edge: jest.fn().mockResolvedValue('failed'), piper: jest.fn().mockResolvedValue('fallback'), onDeviceReady: false,
    });
    expect(await speakWithFallback('Hallo.', 'edge', b.n, {voice: VOICE})).toBe('none');
    expect(b.speakOnDevice).not.toHaveBeenCalled();
  });

  it('204 / leerer Text ("live_guard", "empty") zählt als erledigt — keine zweite Stimme', async () => {
    const a = natives({edge: jest.fn().mockResolvedValue('live_guard')});
    expect(await speakWithFallback('**', 'edge', a.n, {voice: VOICE})).toBe('edge');
    expect(a.speakPiper).not.toHaveBeenCalled();
    const b = natives({piper: jest.fn().mockResolvedValue('empty')});
    expect(await speakWithFallback('', 'piper', b.n, {voice: VOICE})).toBe('piper');
    expect(b.speakViaServer).not.toHaveBeenCalled();
  });

  it('fehlende Module: kein Absturz, Ergebnis "none"', async () => {
    expect(await speakWithFallback('Hallo.', 'edge', {}, {voice: VOICE})).toBe('none');
    expect(await speakWithFallback('Hallo.', 'piper', {PiperTTS: {}, TTSModule: {}}, {voice: VOICE})).toBe('none');
  });

  it('älterer nativer Build ohne speakViaServer: speakViaKokoro (mit eigenem System-Fallback)', async () => {
    const speakViaKokoro = jest.fn().mockResolvedValue('ok');
    expect(await speakWithFallback('Hallo.', 'edge', {TTSModule: {speakViaKokoro}}, {voice: VOICE})).toBe('edge');
    expect(speakViaKokoro).toHaveBeenCalledWith('Hallo.');
    const fb = jest.fn().mockResolvedValue('fallback');
    expect(await speakWithFallback('Hallo.', 'edge', {TTSModule: {speakViaKokoro: fb}}, {voice: VOICE})).toBe('system');
  });

  it.each([['piper'], ['edge']] as const)('natives "cancelled" (%s unterbrochen) beendet die Kette ohne Fallback', async (pref) => {
    const cancelled = jest.fn().mockResolvedValue('cancelled');
    const a = natives(pref === 'piper' ? {piper: cancelled} : {edge: cancelled});
    expect(await speakWithFallback('Hallo.', pref, a.n, {voice: VOICE})).toBe('stale');
    // die jeweils andere Engine und System-TTS wurden NICHT gestartet
    const other = pref === 'piper' ? a.speakViaServer : a.speakPiper;
    expect(other).not.toHaveBeenCalled();
    expect(a.speakOnDevice).not.toHaveBeenCalled();
  });

  it('nach einer Unterbrechung startet keine weitere Stufe mehr', async () => {
    let stale = false;
    const edge = jest.fn().mockImplementation(async () => { stale = true; return 'failed'; });
    const {n, speakPiper, speakOnDevice} = natives({edge});
    expect(await speakWithFallback('Hallo.', 'edge', n, {voice: VOICE, isStale: () => stale})).toBe('stale');
    expect(speakPiper).not.toHaveBeenCalled();
    expect(speakOnDevice).not.toHaveBeenCalled();
  });

  it('bereits unterbrochen: gar nichts wird gestartet', async () => {
    const {n, speakViaServer, speakPiper} = natives();
    expect(await speakWithFallback('Hallo.', 'edge', n, {voice: VOICE, isStale: () => true})).toBe('stale');
    expect(speakViaServer).not.toHaveBeenCalled();
    expect(speakPiper).not.toHaveBeenCalled();
  });
});

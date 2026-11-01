import {TtsSequencer} from '../utils/ttsSequencer';

/**
 * Regression (First-Sentence-Skip): Early-TTS sprach den ersten Satz und direkt danach den Rest; der Rest-Aufruf
 * würgte über den Einzel-Player den noch ladenden ersten Satz (Edge-Cloud) ab → Absatz 1 wurde nie gehört.
 * Der Sequencer startet den Rest ERST nach dem echten Ende des ersten Satzes.
 *
 * Simuliert wird der Einzel-Player: jeder speak() ist ein "Start". Das native "ended"-Ereignis kommt als onEnded().
 */
function makePlayer() {
  const spoken: string[] = [];
  const seq = new TtsSequencer(
    (t: string) => {
      spoken.push(t);
    },
    () => {
      spoken.push('__ALL_DONE__');
    },
  );
  return {seq, spoken};
}

describe('TtsSequencer — First-Sentence-Skip-Fix', () => {
  it('spielt ersten Satz und Rest in Reihenfolge, Rest erst NACH dem Ende des ersten (hätte Skip reproduziert)', () => {
    const {seq, spoken} = makePlayer();

    seq.speakFirst('Heute steht um 13 Uhr dein Stream an.');
    // Rest trifft ein, WÄHREND der erste Satz noch läuft (Edge: erster Satz lädt/spielt länger als der Stream dauert)
    seq.queueRemaining('Im Kalender ist sonst nichts. Viel Erfolg.');

    // Kritisch: Der Rest darf NICHT gestartet worden sein, solange der erste Satz läuft.
    expect(spoken).toEqual(['Heute steht um 13 Uhr dein Stream an.']);
    expect(seq.hasPending()).toBe(true);

    // Erster Satz wirklich zu Ende → jetzt startet der Rest.
    const consumed = seq.onEnded();
    expect(consumed).toBe(true); // Ende des ersten Satzes → kein Relisten, Donna spricht weiter
    expect(spoken).toEqual([
      'Heute steht um 13 Uhr dein Stream an.',
      'Im Kalender ist sonst nichts. Viel Erfolg.',
    ]);

    // Ende des Restes → Sequenz fertig, Relisten erlaubt.
    const consumed2 = seq.onEnded();
    expect(consumed2).toBe(false);
    expect(spoken).toEqual([
      'Heute steht um 13 Uhr dein Stream an.',
      'Im Kalender ist sonst nichts. Viel Erfolg.',
      '__ALL_DONE__',
    ]);
  });

  it('spricht JEDEN Satz genau einmal — kein Doppel', () => {
    const {seq, spoken} = makePlayer();
    seq.speakFirst('Satz eins.');
    seq.queueRemaining('Satz zwei.');
    seq.onEnded();
    seq.onEnded();
    const content = spoken.filter(s => s !== '__ALL_DONE__');
    expect(content).toEqual(['Satz eins.', 'Satz zwei.']);
    expect(new Set(content).size).toBe(content.length);
  });

  it('ohne frühen ersten Satz (kurze Antwort) spricht der Rest direkt', () => {
    const {seq, spoken} = makePlayer();
    seq.queueRemaining('Klar, mach ich.');
    expect(spoken).toEqual(['Klar, mach ich.']);
  });

  it('Barge-in/clear verwirft eingereihten Rest — kein verspätetes Nachsprechen', () => {
    const {seq, spoken} = makePlayer();
    seq.speakFirst('Erster Satz.');
    seq.queueRemaining('Rest den Mike nicht mehr hören will.');
    seq.clear();
    const consumed = seq.onEnded();
    expect(consumed).toBe(false);
    expect(spoken).toEqual(['Erster Satz.', '__ALL_DONE__']);
  });

  it('seltener Fall: erster Satz endet BEVOR der Rest eintrifft → Rest spielt trotzdem, genau einmal', () => {
    const {seq, spoken} = makePlayer();
    seq.speakFirst('Erster Satz.');
    const consumed = seq.onEnded(); // erster Satz endet, Rest noch nicht da
    expect(consumed).toBe(false);
    seq.queueRemaining('Verspäteter Rest.');
    expect(spoken).toEqual(['Erster Satz.', '__ALL_DONE__', 'Verspäteter Rest.']);
  });

  it('idempotent: zwei Rest-Quellen (Hook + Completion-Effekt) reihen den Rest nur EINMAL ein', () => {
    const {seq, spoken} = makePlayer();
    seq.speakFirst('Erster Satz.');
    seq.queueRemaining('Rest aus dem Hook.');
    seq.queueRemaining('Rest aus dem Effekt.'); // zweite Quelle — muss ignoriert werden
    seq.onEnded();
    expect(spoken).toEqual(['Erster Satz.', 'Rest aus dem Hook.']);
  });

  it('idempotent: zweiter speakFirst im selben Turn wird ignoriert', () => {
    const {seq, spoken} = makePlayer();
    seq.speakFirst('Erster.');
    seq.speakFirst('Nochmal erster.');
    expect(spoken).toEqual(['Erster.']);
  });

  it('ignoriert leere Chunks', () => {
    const {seq, spoken} = makePlayer();
    seq.speakFirst('   ');
    seq.queueRemaining('');
    expect(spoken).toEqual([]);
  });
});

/**
 * TtsSequencer — spielt den frühen ersten Satz und den Resttext STRIKT nacheinander über den Einzel-Player ab,
 * ohne dass der Rest den noch laufenden ersten Satz abwürgt (First-Sentence-Skip, DONNA-Voice).
 *
 * Hintergrund (Root-Cause): Das native TTSModule.speakViaServer() bricht bei JEDEM neuen Aufruf die laufende
 * Wiedergabe ab (ttsJob.cancel() + stopMediaPlayer()). Early-TTS sprach den ersten Satz und direkt danach den
 * Rest. Über die langsamere Edge-Cloud (~0,4-1,2 s Fetch + Wiedergabe) war der erste Satz noch am Laden/gerade
 * gestartet, als der Rest-Aufruf ihn abbrach → der erste Absatz wurde NIE gehört, die Wiedergabe begann bei
 * Absatz 2. Mit dem schnellen Piper-lokal (~0,1-0,5 s) fiel das kaum auf — mit Edge als Primär-Engine verschwindet
 * der erste Satz zuverlässig.
 *
 * Zusätzlich sprach der Resttext aus ZWEI Quellen gleichzeitig (useChat-Hook-Callback UND der Completion-Effekt
 * in ChatScreen) — zwei konkurrierende Aufrufe auf dem Einzel-Player. Darum ist der Sequencer idempotent:
 * [speakFirst] und [queueRemaining] wirken pro Turn jeweils nur EINMAL, der zweite Aufruf wird ignoriert.
 *
 * Ergebnis: jeder Satz spricht GENAU EINMAL — kein Skip, kein Doppel. Der Rest startet erst nach dem echten Ende
 * des ersten Satzes ("ended"-Ereignis des nativen TTS → [onEnded]). Reine Logik ohne React → Jest-testbar
 * (src/__tests__/ttsSequencer.test.ts). Barge-in/Unterbrechen/neuer Turn: [clear].
 */
export class TtsSequencer {
  /** Ein erster Satz wurde in diesem Turn bereits gestartet (Idempotenz). */
  private firstStarted = false;
  /** Der erste Satz läuft und sein "ended" steht noch aus. */
  private firstActive = false;
  /** Resttext, der auf das Ende des ersten Satzes wartet. */
  private pendingRemaining: string | null = null;
  /** Ein Rest wurde in diesem Turn bereits eingereiht/gesprochen (Idempotenz gegen Doppel-Quellen). */
  private remainingHandled = false;

  constructor(
    /** Spricht EINEN Chunk (startet die Wiedergabe). Identisch mit speakViaKokoro im Chat. */
    private readonly speak: (text: string) => void,
    /** Alles gesprochen — Auslöser für die Relisten-Planung (Freisprechen). */
    private readonly onAllDone: () => void = () => {},
  ) {}

  /** Ersten (frühen) Satz sofort sprechen. Pro Turn nur EINMAL wirksam. */
  speakFirst(text: string): void {
    if (this.firstStarted || !text.trim()) {
      return;
    }
    this.firstStarted = true;
    this.firstActive = true;
    this.speak(text);
  }

  /**
   * Resttext einreihen. Pro Turn nur EINMAL wirksam (erste Quelle gewinnt — Hook-Callback ODER Completion-Effekt).
   * Läuft der erste Satz noch, wird auf dessen "ended" gewartet; sonst (erster Satz schon fertig oder nie gelaufen)
   * direkt gesprochen.
   */
  queueRemaining(text: string): void {
    if (this.remainingHandled || !text.trim()) {
      return;
    }
    this.remainingHandled = true;
    if (this.firstActive) {
      this.pendingRemaining = text; // wartet auf onEnded() des ersten Satzes
    } else {
      this.speak(text);
    }
  }

  /**
   * Die native Wiedergabe hat ein Ende gemeldet.
   * @returns true, wenn dieses "ended" nur das Ende des ERSTEN Satzes war und der Rest jetzt gestartet wurde —
   *          der Aufrufer darf dann NICHT relisten (Donna spricht gleich weiter). false = Sequenz zu Ende.
   */
  onEnded(): boolean {
    if (this.firstActive) {
      this.firstActive = false;
      const next = this.pendingRemaining;
      this.pendingRemaining = null;
      if (next != null) {
        this.speak(next); // zweiter Teil startet JETZT — nicht früher, also kein Abwürgen des ersten
        return true;
      }
    }
    this.onAllDone();
    return false;
  }

  /** Barge-in / Unterbrechen / neuer Turn: Zustand komplett zurücksetzen. */
  clear(): void {
    this.firstStarted = false;
    this.firstActive = false;
    this.pendingRemaining = null;
    this.remainingHandled = false;
  }

  /** Nur für Tests/Diagnose: steht noch Resttext aus? */
  hasPending(): boolean {
    return this.pendingRemaining != null;
  }
}

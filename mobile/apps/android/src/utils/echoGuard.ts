/**
 * Echo-Schutz fürs Freisprechen: erkennt, ob eine erkannte Äußerung nur Donnas eigene Stimme war
 * (aus dem Lautsprecher ins Mikrofon). Spiegelt SpokenTextMatcher.kt (Overlay) — gleiche Regeln.
 *
 * Verhindert Selbstgespräche und Endlosschleifen im Gesprächsmodus (kostet sonst API-Budget).
 */

// Bewusst ohne \p{L}: Hermes-sicher, deckt deutsche Umlaute/ß ab
const TOKEN = /[A-Za-zÀ-ÖØ-öø-ÿ0-9]+/g;

function tokens(text: string): string[] {
  return (text.toLowerCase().match(TOKEN) ?? []).filter(t => t.length >= 2);
}

// Antwort-Partikel: kommen sie in der Äußerung vor, in Donnas Text aber nicht, ist es eine echte Antwort
const ANSWER_WORDS = new Set([
  'ja', 'nein', 'nee', 'jep', 'ok', 'okay', 'klar', 'genau', 'doch', 'gerne', 'bitte', 'danke',
  'stopp', 'stop', 'abbrechen', 'lass', 'nicht',
]);

/**
 * @param bargeIn Äußerung stammt aus einem Voice-Barge-in (Mikrofon war WÄHREND Donnas Ausgabe offen):
 *   niedrigere Schwelle (~0,5), auch ein einzelnes Wort aus Donnas Text zählt als Echo.
 * Beim automatischen Relisten (bargeIn=false): bis 6 Wörter müssen ALLE aus Donnas Text stammen, darüber 85 % —
 * „Ja, das Licht im Wohnzimmer“ (auf „… Licht im Wohnzimmer ausschalten?“) bleibt eine echte Antwort.
 * Wird NUR bei Auto-Relisten/Barge-in angewendet, nicht bei manuellem Mic-Tap (Aufrufer prüft).
 */
export function isEchoOfSpoken(heard: string, spoken: string, bargeIn = false): boolean {
  const h = tokens(heard);
  const s = new Set(tokens(spoken));
  if (h.length === 0 || s.size === 0) {return false;}
  if (h.some(t => ANSWER_WORDS.has(t) && !s.has(t))) {return false;}
  const hits = h.filter(t => s.has(t)).length;
  if (h.length === 1) {return bargeIn && hits === 1;}
  const ratio = hits / h.length;
  if (bargeIn) {return ratio >= 0.5;}
  return h.length <= 6 ? ratio >= 1 : ratio >= 0.85;
}

/**
 * Zählt Selbstunterbrechungen durch Echo — Spiegel von `EchoStrikes` (BargeInLogic.kt), damit App und Live-Bubble
 * gleich reagieren (#53/#54):
 *  - nach `maxStrikes` Fehlauslösern nur eine TEMPORÄRE Pause (`cooldownMs`), danach automatisch wieder scharf —
 *    KEIN Dauer-Aus (das hatte das Unterbrechen prozessweit lahmgelegt),
 *  - alte Strikes klingen ab (`decayMs`): vereinzelte Fehler über die Zeit summieren sich nicht,
 *  - echte Sprache (`reset`) setzt alles zurück.
 * Zeit kommt über `clock` rein (Tests).
 */
export class EchoStrikes {
  private count = 0;
  private lastStrikeAt = 0;
  private pausedUntil = 0;

  constructor(
    private readonly maxStrikes = 3,
    private readonly cooldownMs = 90_000,
    private readonly decayMs = 60_000,
    private readonly clock: () => number = Date.now,
  ) {}

  /** @returns true, wenn Voice-Barge-in jetzt TEMPORÄR pausiert werden soll. */
  record(): boolean {
    const now = this.clock();
    if (this.pausedUntil !== 0 && now >= this.pausedUntil) {
      this.pausedUntil = 0;
      this.count = 0;
      this.lastStrikeAt = 0;
    }
    if (this.count > 0 && now - this.lastStrikeAt > this.decayMs) {
      this.count = 0;
    }
    this.lastStrikeAt = now;
    this.count += 1;
    if (this.count >= this.maxStrikes) {
      this.pausedUntil = now + this.cooldownMs;
      return true;
    }
    return false;
  }

  /** True, solange der Cooldown läuft; danach automatisch wieder scharf. */
  isPaused(): boolean {
    if (this.pausedUntil === 0) {
      return false;
    }
    if (this.clock() >= this.pausedUntil) {
      this.pausedUntil = 0;
      this.count = 0;
      this.lastStrikeAt = 0;
      return false;
    }
    return true;
  }

  get strikes(): number {
    return this.count;
  }

  reset(): void {
    this.count = 0;
    this.lastStrikeAt = 0;
    this.pausedUntil = 0;
  }
}

/**
 * #53 — Darf das Ende einer Barge-in-Sitzung als Echo-Strike zählen? NEIN, wenn die App im Hintergrund ist oder
 * der letzte Zustandswechsel (minimiert/gesperrt/zurück) weniger als `graceMs` zurückliegt: dann endete die
 * Sitzung durch den Wechsel, nicht durch Echo.
 */
export function shouldCountEchoStrike(inBackground: boolean, msSinceLifecycleChange: number, graceMs: number): boolean {
  return !inBackground && msSinceLifecycleChange >= graceMs;
}

/** Hängt gesprochenen Text an (nur die letzten `maxChars` Zeichen behalten). */
export function appendSpoken(current: string, addition: string, maxChars = 2000): string {
  const next = current ? `${current} ${addition}` : addition;
  return next.length > maxChars ? next.slice(next.length - maxChars) : next;
}

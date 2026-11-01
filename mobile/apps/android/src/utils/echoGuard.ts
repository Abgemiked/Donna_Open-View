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

/** Zählt Selbstunterbrechungen durch Echo; nach `maxStrikes` wird Voice-Barge-in abgeschaltet. */
export class EchoStrikes {
  private count = 0;
  constructor(private readonly maxStrikes = 2) {}

  /** @returns true, wenn Voice-Barge-in jetzt abgeschaltet werden soll. */
  record(): boolean {
    this.count += 1;
    return this.count >= this.maxStrikes;
  }

  get strikes(): number {
    return this.count;
  }

  reset(): void {
    this.count = 0;
  }
}

/** Hängt gesprochenen Text an (nur die letzten `maxChars` Zeichen behalten). */
export function appendSpoken(current: string, addition: string, maxChars = 2000): string {
  const next = current ? `${current} ${addition}` : addition;
  return next.length > maxChars ? next.slice(next.length - maxChars) : next;
}

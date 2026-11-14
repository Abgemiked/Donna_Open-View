/**
 * Ja/Nein-Sprachbestätigung für wartende Aktionen (Anruf, Nachricht, Navigation …).
 *
 * Sicherheitsregel: eine Aktion wird NUR bei eindeutigem Ja ausgeführt. Verneinung hat Vorrang, Wörter werden
 * an Wortgrenzen verglichen (kein `includes`: „klar“ steckt nicht in „aufklären“, „mach“ nicht in „Machtwort“),
 * und bei Mehrdeutigkeit („ja, aber nicht jetzt“) passiert NICHTS.
 */

export type Confirmation = 'yes' | 'no' | 'ambiguous' | 'none';

// Wörter in Kleinbuchstaben, Umlaute bleiben; Zerlegung an allem, was kein Buchstabe/Ziffer ist
function words(text: string): string[] {
  return text.toLowerCase().split(/[^a-zäöüß0-9]+/).filter(Boolean);
}

const NO_WORDS = new Set([
  'nein', 'nee', 'nö', 'nicht', 'kein', 'keine', 'keinen', 'abbrechen', 'abbruch', 'stopp', 'stop',
  'lass', 'lieber', 'cancel', 'niemals', 'verwerfen', 'vergiss',
]);

const YES_WORDS = new Set([
  'ja', 'jawohl', 'jep', 'jup', 'yes', 'ok', 'okay', 'klar', 'mach', 'bestätige', 'bestätigen', 'genau',
  'gerne', 'unbedingt', 'passt', 'stimmt', 'sicher',
]);

/** Längere Sätze sind nie eine Bestätigung („Mach das Licht im Wohnzimmer aus“ ist ein neuer Befehl). */
export const MAX_CONFIRM_WORDS = 5;
/** Schwache Ja-Wörter („mach“, „klar“, „ok“ …) zählen nur in ganz kurzen Antworten. */
export const MAX_WEAK_YES_WORDS = 3;

// Eindeutige Zustimmung: zusammen mit einer Verneinung ist die Äußerung mehrdeutig. Schwache Ja-Wörter
// („mach“, „klar“, „ok“) neben einer Verneinung ändern nichts — „nein, mach das nicht“ ist ein Nein.
const STRONG_YES = new Set(['ja', 'jawohl', 'jep', 'jup', 'yes', 'bestätige', 'bestätigen', 'gerne', 'unbedingt']);

/**
 * @returns
 *  - 'no'        eindeutig verneint (Verneinung zuerst) — Aktion verwerfen
 *  - 'yes'       eindeutig bejaht — Aktion ausführen
 *  - 'ambiguous' sowohl Ja- als auch Nein-Wörter („ja, aber nicht jetzt“) — NICHTS tun, Karte bleibt
 *  - 'none'      weder noch — normale Nachricht
 */
export function parseConfirmation(text: string): Confirmation {
  const w = words(text);
  // Nur KURZE Äußerungen sind Bestätigungen — sonst ginge bei offener Karte ein anderer Befehl verloren
  // und die wartende Aktion würde fälschlich ausgeführt/verworfen. Längeres → normale Nachricht, Karte bleibt.
  if (w.length === 0 || w.length > MAX_CONFIRM_WORDS) {return 'none';}
  const hasNo = w.some(x => NO_WORDS.has(x));
  if (hasNo) {return w.some(x => STRONG_YES.has(x)) ? 'ambiguous' : 'no';}
  const strongYes = w.some(x => STRONG_YES.has(x));
  const weakYes = w.length <= MAX_WEAK_YES_WORDS && w.some(x => YES_WORDS.has(x));
  return strongYes || weakYes ? 'yes' : 'none';
}

import {isEchoOfSpoken} from '../utils/echoGuard';

/**
 * B2 BLOCKER 5 — Echo/Selbsttrigger beim Sprach-„Ja".
 *
 * Auto-Send feuert nach einem bestätigenden „Ja". Donnas eigene TTS darf nicht als dieses „Ja"
 * zurückkommen und selbst auslösen. Diese Tests nageln die schützende Echo-Logik fest, die im
 * Voice-Handler VOR parseConfirmation läuft (VoiceInputActivity: Echo-Check vor der Ja/Nein-Parse).
 *
 * Residual (ehrlich): Ein EINZELNES „ja" beim Auto-Relisten (nicht Barge-in) wird NICHT als Echo
 * behandelt (isEchoOfSpoken → false). Darum darf Donnas gesprochene Rückfrage KEIN nacktes „ja/nein"
 * enthalten — dann ist ein gehörtes „ja" garantiert Mikes Antwort. Enthält Donnas Ausgabe das
 * Antwortwort doch, greift der Barge-in-Fall unten.
 */
describe('Echo-Schutz deckt Donnas Confirm-Ausgabe ab (BLOCKER 5)', () => {
  it('Barge-in: mehrwortiges Echo von Donnas „…ja oder nein" wird gefiltert', () => {
    const spoken = 'Soll ich die WhatsApp wirklich senden ja oder nein';
    expect(isEchoOfSpoken('ja oder nein', spoken, true)).toBe(true);
  });

  it('Barge-in: einzelnes „ja", das in Donnas Ausgabe vorkam, wird als Echo gefiltert', () => {
    const spoken = 'Ja genau, ich habe die Nachricht vorbereitet';
    expect(isEchoOfSpoken('ja', spoken, true)).toBe(true);
  });

  it('echtes Mike-„Ja" (nicht in Donnas Ausgabe) bleibt eine Antwort, kein Echo', () => {
    const spoken = 'Ich habe die Nachricht an Ämi vorbereitet. Senden?';
    // „ja" ist ein ANSWER_WORD und steht NICHT in Donnas Text → echte Antwort (kein Echo).
    expect(isEchoOfSpoken('ja', spoken, true)).toBe(false);
    expect(isEchoOfSpoken('ja', spoken, false)).toBe(false);
  });
});

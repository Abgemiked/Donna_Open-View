/**
 * #134 P4/P5: reine Entscheidungen der Chat-Oberflaeche (kein React, keine neue Zustandslogik).
 * Alles hier wird aus vorhandenem Zustand des ChatScreens abgeleitet.
 */

/** Tageszeit-Gruss: 5-10 Uhr Morgen, 11-17 Uhr Tag, sonst Abend (auch nachts). */
export function greetingForHour(hour: number): string {
  const h = ((Math.floor(hour) % 24) + 24) % 24;
  if (h >= 5 && h <= 10) {return 'Guten Morgen.';}
  if (h >= 11 && h <= 17) {return 'Guten Tag.';}
  return 'Guten Abend.';
}

/** Vorschlagszeilen im Leerzustand (neutral; Antippen sendet den Text). */
export const IDLE_SUGGESTIONS: readonly string[] = ['Was steht heute an?', 'Stell einen Timer'];

export type ChatViewKind = 'idle' | 'chat' | 'conversation';

export interface ChatViewInputs {
  hasMessages: boolean;
  conversationMode: boolean;
  micAvailable: boolean;
  isListening: boolean;
  isLoading: boolean;
  ttsActive: boolean;
  /** Sprachzug laeuft (useVoiceTurn, reaktiv): sonst wuerde jede getippte Frage die Gespraechsansicht oeffnen. */
  voiceTurn: boolean;
  /** Nutzer hat "Tippen" gewaehlt: Chat-Ansicht mit Tastatur, bis er wieder spricht. */
  typingOverride: boolean;
  /** Kurze Nachlaufzeit nach dem letzten aktiven Zustand (useHeldTrue): kein Flackern zwischen Sprechen und Wiederzuhoeren. */
  hold?: boolean;
  /** Bestaetigungs-, Kontakt-, Auswahl- oder Ideenkarte offen: bleibt sichtbar und antippbar, die Gespraechsansicht weicht. */
  cardOpen: boolean;
}

/**
 * Welche Ansicht gezeigt wird. Gespraechsansicht nur, solange der Gespraechsmodus aktiv zuhoert, denkt oder spricht
 * UND der Zug ein Sprachzug ist; "Tippen" schaltet sie ab. Ohne Nachrichten (und ohne Gespraech) der Leerzustand.
 */
export function selectChatView(i: ChatViewInputs): ChatViewKind {
  const busy = i.isListening || ((i.isLoading || i.ttsActive) && i.voiceTurn);
  const allowed = i.conversationMode && i.micAvailable && !i.typingOverride && !i.cardOpen;
  if (allowed && (busy || i.hold === true)) {return 'conversation';}
  return i.hasMessages ? 'chat' : 'idle';
}

/** Mikrofon-Taste oder Senden-Icon: Senden, sobald Text im Feld steht. */
export function primaryAction(value: string, isListening: boolean): 'mic' | 'send' {
  return value.trim().length > 0 && !isListening ? 'send' : 'mic';
}

/** "Stopp" nur, solange Donna spricht oder antwortet (und nicht gerade zugehoert wird). */
export function showStopButton(canInterrupt: boolean, isListening: boolean): boolean {
  return canInterrupt && !isListening;
}

/** Beschriftung der Mikrofon-Taste (Screenreader). */
export function micLabel(listening: boolean, interrupting: boolean): string {
  if (listening) {return 'Aufnahme stoppen';}
  return interrupting ? 'Donna unterbrechen und sprechen' : 'Spracheingabe';
}

/** Beschriftung des Gespraechs-Umschalters (wie bisher am Kopf). */
export function conversationLabel(active: boolean): string {
  return active ? 'Gesprächsmodus ausschalten' : 'Gesprächsmodus einschalten';
}

export interface ConversationTranscriptInputs {
  /** Anzeige-Zustand (mapOrbState) */
  state: 'listening' | 'thinking' | 'speaking' | 'idle' | 'error';
  /** laufender Zwischentext der Spracherkennung */
  partial: string;
  lastUserText: string;
  lastAssistantText: string;
  /** Rolle der letzten Nachricht im Verlauf (null = keine). */
  lastMessageRole: 'user' | 'assistant' | null;
}

/**
 * Mitschrift der Gespraechsansicht: beim Zuhoeren der Zwischentext des Nutzers, beim Denken seine letzte Aeusserung,
 * beim Sprechen Donnas Antwort. Nur Auswahl aus vorhandenem Zustand, kein eigener Verlauf.
 */
export function conversationTranscript(i: ConversationTranscriptInputs): {speaker: 'Du' | 'Donna'; text: string} {
  if (i.state === 'listening') {return {speaker: 'Du', text: i.partial.trim()};}
  // Donnas Text nur, wenn die neue Antwort schon im Verlauf steht (sonst waere es die VORIGE Antwort beim fruehen Vorlesen)
  const answer = i.lastAssistantText.trim();
  if (i.state === 'speaking' && i.lastMessageRole === 'assistant' && answer) {return {speaker: 'Donna', text: answer};}
  return {speaker: 'Du', text: i.lastUserText.trim()};
}

/** Tipp-Vorrang ("Tippen" gewaehlt) endet: sobald wieder gehoert wird (egal ueber welchen Weg) oder die Tastatur ohne Text schliesst. */
export function typingPriorityEnds(i: {listeningStarted: boolean; keyboardHidden: boolean; hasText: boolean}): boolean {
  return i.listeningStarted || (i.keyboardHidden && !i.hasText);
}

export interface BarVisibilityInputs {
  hasMessages: boolean;
  typingOpen: boolean;
  canInterrupt: boolean;
  isListening: boolean;
}

/** Grosse Leerzustands-Leiste (mit Gespraech-Umschalter) nur im ruhigen Leerzustand ohne offene Eingabe. */
export function idleBarVisible(i: BarVisibilityInputs): boolean {
  return !(i.hasMessages || i.typingOpen || i.canInterrupt || i.isListening);
}

/** Der Gespraech-Umschalter ist IMMER erreichbar: wo die grosse Leiste ihn nicht zeigt, steht er im Kopf. */
export function headerShowsConversationToggle(i: BarVisibilityInputs & {micAvailable: boolean}): boolean {
  return i.micAvailable && !idleBarVisible(i);
}

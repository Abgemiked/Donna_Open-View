// useChat — Chat Hook mit Karten-Support (WeatherCard, MapCard) + Session-Memory
import {useState, useCallback, useRef, useEffect} from 'react';
import AsyncStorage from '@react-native-async-storage/async-storage';
import {streamChat} from '../api';
import type {ChatMessage, ChatLocation, ChatCard, DonnaAction, IdeaConfirmPayload, IdeaUpdatePayload} from '../api/types';

const SESSION_KEY = '@donna_session_id';

export interface UseChatReturn {
  messages: ChatMessage[];
  isLoading: boolean;
  /** Gesetzt wenn Gemini 429-Rate-Limit aktiv ist — für UI-Statusanzeige. */
  rateLimitStatus: string | null;
  sendMessage: (text: string, replyQuote?: string, client?: string, onEarlyTTS?: (sentence: string) => void) => Promise<void>;
  clearMessages: () => void;
  loadMessages: (msgs: ChatMessage[]) => void;
  setLocation: (loc: ChatLocation | null) => void;
  sessionId: string;
  /** DONNA-198: Erlaubt der JS-Schicht eine externe Session-ID zu setzen (z.B. vom Backend via ntfy). */
  setSessionId: (id: string) => void;
  /**
   * DONNA-198 v3: Startet atomisch einen proaktiven Chat mit Donnas erster Nachricht.
   * Ersetzt clearMessages() + setSessionId() + setTimeout(loadMessages, 50) durch ein
   * einziges synchrones State-Update — keine Race-Condition möglich.
   */
  startProactiveChat: (assistantMessage: string, backendSessionId?: string) => void;
}

/** Einfache Session-ID — stabil für die gesamte App-Session (kein UUID-Package nötig). */
function makeSessionId(): string {
  return `s${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

export function useChat(): UseChatReturn {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [rateLimitStatus, setRateLimitStatus] = useState<string | null>(null);
  const locationRef = useRef<ChatLocation | null>(null);
  // Stabile Session-ID für die Conversation — damit STM die History findet
  // Initialer Platzhalter; useEffect lädt/erstellt die persistierte ID aus AsyncStorage
  const sessionIdRef = useRef<string>(makeSessionId());
  // State-Kopie für Re-Renders (Ref allein löst keinen Re-Render aus)
  const [sessionIdState, setSessionIdState] = useState<string>(() => sessionIdRef.current);

  // Session-ID aus AsyncStorage laden oder neue anlegen und speichern
  useEffect(() => {
    AsyncStorage.getItem(SESSION_KEY).then(stored => {
      if (stored) {
        sessionIdRef.current = stored;
        setSessionIdState(stored);
      } else {
        const newId = sessionIdRef.current;
        AsyncStorage.setItem(SESSION_KEY, newId);
        setSessionIdState(newId);
      }
    });
  }, []);

  const setLocation = useCallback((loc: ChatLocation | null) => {
    locationRef.current = loc;
  }, []);

  const isLoadingRef = useRef(false);

  const sendMessage = useCallback(async (text: string, replyQuote?: string, client?: string, onEarlyTTS?: (sentence: string) => void) => {
    if (isLoadingRef.current) {return;}
    // Optional: Zitierter Text als Kontext (für Reply-Feature)
    const fullText = replyQuote
      ? `[Bezug auf: "${replyQuote.slice(0, 80).trim()}${replyQuote.length > 80 ? '…' : ''}"]\n${text}`
      : text;

    const userMessage: ChatMessage = {role: 'user', content: text};
    setMessages(prev => [...prev, userMessage]);
    isLoadingRef.current = true;
    setIsLoading(true);
    try {
      let result = '';
      let card: ChatCard | undefined;
      // DONNA-Welle1 Task 5: Actions vom Backend sammeln
      const actions: DonnaAction[] = [];
      // Early TTS: ersten vollständigen Satz sofort sprechen statt auf Gesamt-Antwort zu warten
      let earlyTTSSent = false;
      let earlyTTSText = '';
      let ttsBuffer = '';
      // DONNA-115: Ideen-Karten
      let ideaConfirm: IdeaConfirmPayload | undefined;
      let ideaUpdate: IdeaUpdatePayload | undefined;
      await streamChat(
        fullText,
        chunk => {
          if (chunk.type === 'delta' && chunk.content) {
            result += chunk.content;
            if (!earlyTTSSent && onEarlyTTS) {
              ttsBuffer += chunk.content;
              // Ersten vollständigen Satz ab 30 Zeichen extrahieren
              const m = ttsBuffer.match(/^(.{30,}?[.!?])(?:\s|$)/s);
              if (m) {
                earlyTTSSent = true;
                earlyTTSText = m[1].trim();
                queueMicrotask(() => { try { onEarlyTTS(earlyTTSText); } catch (e) { console.warn('[earlyTTS]', e); } });
              }
            }
          } else if (chunk.type === 'card' && chunk.card) {
            card = chunk.card;
          } else if (chunk.type === 'action' && chunk.action) {
            actions.push(chunk.action);
          } else if (chunk.type === 'idea_confirm' && chunk.idea) {
            ideaConfirm = chunk.idea as IdeaConfirmPayload;
          } else if (chunk.type === 'idea_update' && chunk.idea) {
            ideaUpdate = chunk.idea as IdeaUpdatePayload;
          } else if (chunk.type === 'gemini_rate_limited') {
            setRateLimitStatus('Kurz überlastet – Donna versucht es nochmal…');
          } else if (chunk.type === 'done') {
            setRateLimitStatus(null);
          }
        },
        undefined,
        locationRef.current ?? undefined,
        sessionIdRef.current,
        client,
      );
      // DONNA_ACTION + DONNA_IDEA-Marker als Sicherheitsnetz client-side strippen
      // (Backend strippt sie jetzt schon — Welle1 Task 7. Defense in depth.)
      const cleanResult = result
        .replace(/\[DONNA_ACTION:\{[\s\S]*?\}\]/g, '')
        .replace(/\[DONNA_IDEA_CONFIRM:\{[\s\S]*?\}\]/g, '')
        .replace(/\[DONNA_IDEA_UPDATE:\{[\s\S]*?\}\]/g, '')
        .trim();
      // DONNA-146: Resttext nach Early-TTS-Satz sprechen (früher wurde nur erster Satz gesprochen)
      if (onEarlyTTS && cleanResult) {
        if (earlyTTSSent && earlyTTSText && cleanResult.startsWith(earlyTTSText)) {
          const remaining = cleanResult.slice(earlyTTSText.length).trimStart();
          if (remaining.length > 0) {
            queueMicrotask(() => { try { onEarlyTTS(remaining); } catch (e) { console.warn('[remainingTTS]', e); } });
          }
        } else if (!earlyTTSSent) {
          queueMicrotask(() => { try { onEarlyTTS(cleanResult); } catch (e) { console.warn('[remainingTTS]', e); } });
        }
      }
      // Leere Nachrichten nicht anzeigen wenn nur Marker-Stripping den Text eliminiert hat
      // (verhindert leere Bubbles bei reinen Action-Antworten wie "Notiert"-Einträgen)
      if (cleanResult || card || actions.length > 0 || ideaConfirm || ideaUpdate) {
        const assistantMessage: ChatMessage = {
          role: 'assistant',
          content: cleanResult || '',
          card,
          actions: actions.length > 0 ? actions : undefined,
          ideaConfirm,
          ideaUpdate,
        };
        setMessages(prev => [...prev, assistantMessage]);
      }
    } catch (error) {
      console.error('Chat error:', error);
      const errMessage: ChatMessage = {
        role: 'assistant',
        content: `Fehler: ${error instanceof Error ? error.message : 'Unbekannter Fehler'}`,
      };
      setMessages(prev => [...prev, errMessage]);
    } finally {
      isLoadingRef.current = false;
      setIsLoading(false);
      setRateLimitStatus(null);
    }
  }, []);

  const clearMessages = useCallback(() => {
    setMessages([]);
    // Neues Gespräch = neue Session anlegen und persistieren
    const newId = makeSessionId();
    sessionIdRef.current = newId;
    setSessionIdState(newId);
    AsyncStorage.setItem(SESSION_KEY, newId);
  }, []);

  /** Lädt Nachrichten aus einer alten Session in den Chat (read-only Verlauf). */
  const loadMessages = useCallback((msgs: ChatMessage[]) => {
    setMessages(msgs);
  }, []);

  /** DONNA-198: Setzt eine externe Session-ID (z.B. vom Backend via ntfy-Notification).
   *  Muss NACH clearMessages() aufgerufen werden damit die neue ID nicht durch
   *  AsyncStorage-Init überschrieben wird. */
  const setSessionId = useCallback((id: string) => {
    if (!id?.trim()) return;
    sessionIdRef.current = id;
    setSessionIdState(id);
    AsyncStorage.setItem(SESSION_KEY, id);
  }, []);

  /**
   * DONNA-198 v3: Startet atomisch einen proaktiven Chat.
   * Setzt Session-ID + erste Donna-Nachricht in einem einzigen synchronen Aufruf,
   * ohne setTimeout-Race zwischen clearMessages() und loadMessages().
   */
  const startProactiveChat = useCallback((assistantMessage: string, backendSessionId?: string) => {
    if (!assistantMessage?.trim()) return;
    // Session-ID: Backend-ID nehmen wenn vorhanden, sonst neue generieren
    const newId = backendSessionId?.trim() || makeSessionId();
    sessionIdRef.current = newId;
    setSessionIdState(newId);
    AsyncStorage.setItem(SESSION_KEY, newId);
    // Atomisch: messages = [Donnas Nachricht], kein clearMessages() + setTimeout nötig
    setMessages([{role: 'assistant', content: assistantMessage.trim()}]);
  }, []);

  return {messages, isLoading, rateLimitStatus, sendMessage, clearMessages, loadMessages, setLocation, sessionId: sessionIdState, setSessionId, startProactiveChat};
}

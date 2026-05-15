// useStreamingChat — Streaming-aware Chat Hook für Android + Windows
// DONNA-12: Echtzeit-Streaming mit Chunk-by-Chunk UI-Update

import {useState, useCallback, useRef} from 'react';
import {streamChat} from '../api';
import type {ChatMessage} from '../api/types';

export interface UseStreamingChatReturn {
  messages: ChatMessage[];
  isStreaming: boolean;
  streamingContent: string;
  sendMessage: (text: string) => Promise<void>;
  cancelStream: () => void;
  clearMessages: () => void;
}

/**
 * useStreamingChat — React Hook für Echtzeit-Chat-Streaming.
 *
 * Unterschied zu useChat:
 * - streamingContent zeigt den aktuell einlaufenden Text (live)
 * - Nach Abschluss wird der Text als vollständige assistant-Message hinzugefügt
 * - cancelStream() bricht den laufenden Fetch ab
 */
export function useStreamingChat(): UseStreamingChatReturn {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [isStreaming, setIsStreaming] = useState(false);
  const [streamingContent, setStreamingContent] = useState('');
  const abortRef = useRef<AbortController | null>(null);
  // Ref-Guard verhindert Stale-Closure-Race bei schnellen Doppel-Submits
  const isStreamingRef = useRef(false);

  const sendMessage = useCallback(async (text: string) => {
    if (isStreamingRef.current) {return;}

    const userMessage: ChatMessage = {role: 'user', content: text};
    setMessages(prev => [...prev, userMessage]);
    isStreamingRef.current = true;
    setIsStreaming(true);
    setStreamingContent('');

    const controller = new AbortController();
    abortRef.current = controller;

    let accumulated = '';

    try {
      await streamChat(
        text,
        (chunk) => {
          if (chunk.type === 'delta' && chunk.content) {
            accumulated += chunk.content;
            setStreamingContent(accumulated);
          } else if (chunk.type === 'done') {
            if (accumulated) {
              const assistantMessage: ChatMessage = {
                role: 'assistant',
                content: accumulated,
              };
              setMessages(prev => [...prev, assistantMessage]);
            }
            setStreamingContent('');
            isStreamingRef.current = false;
            setIsStreaming(false);
          } else if (chunk.type === 'error') {
            const errorMessage: ChatMessage = {
              role: 'assistant',
              content: `Fehler: ${chunk.error ?? 'Unbekannter Fehler'}`,
            };
            setMessages(prev => [...prev, errorMessage]);
            setStreamingContent('');
            isStreamingRef.current = false;
            setIsStreaming(false);
          }
        },
        controller.signal,
      );
      // Safety-Reset: falls streamChat() endet ohne 'done'-Chunk (z.B. Buffer-Drop)
      if (isStreamingRef.current) {
        if (accumulated) {
          setMessages(prev => [...prev, {role: 'assistant', content: accumulated}]);
        }
        setStreamingContent('');
        isStreamingRef.current = false;
        setIsStreaming(false);
      }
    } catch (err: unknown) {
      if (err instanceof Error && err.name !== 'AbortError') {
        const errorMessage: ChatMessage = {
          role: 'assistant',
          content: 'Verbindungsfehler. Bitte erneut versuchen.',
        };
        setMessages(prev => [...prev, errorMessage]);
      }
      setStreamingContent('');
      isStreamingRef.current = false;
      setIsStreaming(false);
    } finally {
      abortRef.current = null;
    }
  }, []);

  const cancelStream = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    isStreamingRef.current = false;
    setStreamingContent('');
    setIsStreaming(false);
  }, []);

  const clearMessages = useCallback(() => {
    setMessages([]);
    setStreamingContent('');
  }, []);

  return {messages, isStreaming, streamingContent, sendMessage, cancelStream, clearMessages};
}

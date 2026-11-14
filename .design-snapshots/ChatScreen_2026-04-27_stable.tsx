import React, { useState, useRef, useEffect, useCallback } from 'react';
import { streamChat } from '@donna/shared';
import type { WeatherCardData } from '@donna/shared';
import { useVoice } from '../hooks/useVoice';
import { useTTS } from '../hooks/useTTS';

// ─── Types ────────────────────────────────────────────────────────────────────

interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  isStreaming?: boolean;
  weatherCard?: WeatherCardData;
}

// ─── Design tokens (matching design file) ─────────────────────────────────────

const T = {
  bg:       '#1a1a2e',
  bg2:      '#14142a',
  card:     '#2a2a3e',
  border:   '#3a3a4e',
  accent:   '#6200ee',
  accentHi: '#9c4dff',
  glow:     'rgba(98,0,238,0.45)',
  glow2:    'rgba(98,0,238,0.15)',
  text:     '#ffffff',
  textDim:  '#e0e0e0',
  muted:    '#8a8aa0',
  font:     "'Space Grotesk', sans-serif",
  mono:     "'Space Mono', monospace",
};

// ─── DonnaAvatar — exact shape from design file ───────────────────────────────

function DonnaAvatar({ size = 32, animate = false }: { size?: number; animate?: boolean }) {
  const c = T.accentHi;
  return (
    <div style={{ width: size, height: size, display: 'inline-flex', flexShrink: 0 }}>
      <svg width={size} height={size} viewBox="0 0 44 44" fill="none">
        <polygon
          points="22,2 38,11 38,33 22,42 6,33 6,11"
          fill="none" stroke={c} strokeWidth="1.2"
          style={animate ? { animation: 'hexPulse 2s ease-in-out infinite' } : {}}
        />
        <circle cx="22" cy="22" r="11" fill="none" stroke={c} strokeWidth="0.8" opacity="0.5" />
        <path d="M16 15h6c5 0 8 3 8 7s-3 7-8 7h-6V15z" fill={c} opacity="0.15" />
        <path d="M17 16h5c4 0 6.5 2.5 6.5 6s-2.5 6-6.5 6h-5V16z"
          fill="none" stroke={c} strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
        <circle cx="22" cy="22" r="1.5" fill={c} />
      </svg>
    </div>
  );
}

// ─── SVG Icons ────────────────────────────────────────────────────────────────

function MicIcon({ size = 16 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <rect x="9" y="3" width="6" height="11" rx="3" />
      <path d="M5 11a7 7 0 0 0 14 0" />
      <line x1="12" y1="19" x2="12" y2="23" />
      <line x1="8" y1="23" x2="16" y2="23" />
    </svg>
  );
}

function SendIcon({ size = 14 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="white"
      strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
      <line x1="22" y1="2" x2="11" y2="13" />
      <polygon points="22 2 15 22 11 13 2 9 22 2" />
    </svg>
  );
}

function StopIcon({ size = 14 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor">
      <rect x="4" y="4" width="16" height="16" rx="2" />
    </svg>
  );
}

function SpeakerOnIcon({ size = 16 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5" />
      <path d="M15.54 8.46a5 5 0 0 1 0 7.07" />
      <path d="M19.07 4.93a10 10 0 0 1 0 14.14" />
    </svg>
  );
}

function SpeakerOffIcon({ size = 16 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5" />
      <line x1="23" y1="9" x2="17" y2="15" />
      <line x1="17" y1="9" x2="23" y2="15" />
    </svg>
  );
}

// ─── Markdown renderer ────────────────────────────────────────────────────────

function renderMarkdown(text: string): React.ReactNode[] {
  const lines = text.split('\n');
  const result: React.ReactNode[] = [];
  lines.forEach((line, lineIdx) => {
    if (lineIdx > 0) result.push(<br key={`br-${lineIdx}`} />);
    const parts = line.split(/(\*\*[^*]+\*\*)/g);
    parts.forEach((part, partIdx) => {
      if (part.startsWith('**') && part.endsWith('**')) {
        result.push(
          <strong key={`${lineIdx}-${partIdx}`} style={{ color: T.accentHi }}>
            {part.slice(2, -2)}
          </strong>
        );
      } else {
        result.push(<span key={`${lineIdx}-${partIdx}`}>{part}</span>);
      }
    });
  });
  return result;
}

// ─── WeatherCard ──────────────────────────────────────────────────────────────

function WeatherCard({ data }: { data: WeatherCardData }) {
  return (
    <div style={{
      marginTop: 8,
      background: 'rgba(98,0,238,0.1)',
      border: '1px solid rgba(156,77,255,0.25)',
      borderRadius: 10,
      padding: '10px 14px',
      display: 'flex',
      flexDirection: 'column',
      gap: 4,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <span style={{ fontSize: 13, fontWeight: 600, color: T.text }}>{data.location}</span>
        <span style={{ fontSize: 11, color: T.muted }}>{data.condition}</span>
      </div>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
        <span style={{ fontSize: 28, fontWeight: 700, color: T.accentHi }}>{Math.round(data.temp_c)}°</span>
        <span style={{ fontSize: 12, color: T.muted }}>gefühlt {Math.round(data.feels_like_c)}°</span>
      </div>
      <div style={{ display: 'flex', gap: 12, fontSize: 11, color: T.muted }}>
        <span>↓{Math.round(data.temp_min)}° ↑{Math.round(data.temp_max)}°</span>
        <span>💧 {data.humidity}%</span>
        <span>💨 {Math.round(data.wind_kmh)} km/h</span>
      </div>
    </div>
  );
}

// ─── Inject global styles ─────────────────────────────────────────────────────

const styleTag = document.createElement('style');
styleTag.textContent = `
  @keyframes pulseDot { 0%,100%{opacity:1} 50%{opacity:0.3} }
  @keyframes hexPulse { 0%,100%{opacity:.7} 50%{opacity:1} }
  @keyframes fadeUp { from{opacity:0;transform:translateY(8px)} to{opacity:1;transform:translateY(0)} }
  ::-webkit-scrollbar { width: 6px; }
  ::-webkit-scrollbar-track { background: #1a1a2e; }
  ::-webkit-scrollbar-thumb { background: #3a3a4e; border-radius: 3px; }
  ::-webkit-scrollbar-thumb:hover { background: #4a4a6e; }
`;
if (!document.head.querySelector('[data-donna-chat-styles]')) {
  styleTag.setAttribute('data-donna-chat-styles', '');
  document.head.appendChild(styleTag);
}

// ─── Sidebar (lg mode only) ───────────────────────────────────────────────────

function Sidebar({ onNewChat }: { onNewChat: () => void }) {
  const history = [
    { q: 'Team Standup verschieben', t: 'Heute', active: true },
    { q: 'Wetter Berlin', t: 'Heute' },
    { q: 'E-Mail an Felix', t: 'Gestern' },
    { q: 'Projekt-Notizen', t: 'Gestern' },
  ];
  return (
    <div style={{
      width: 240, flexShrink: 0, background: T.bg2,
      borderRight: `1px solid ${T.border}`,
      display: 'flex', flexDirection: 'column', padding: '14px 12px',
    }}>
      <button onClick={onNewChat} style={{
        padding: '10px 12px', borderRadius: 6, background: T.accent, border: 'none',
        color: '#fff', fontSize: 13, fontWeight: 600, cursor: 'pointer',
        display: 'flex', alignItems: 'center', gap: 8, marginBottom: 14,
        boxShadow: `0 0 16px ${T.glow}`, fontFamily: T.font,
      }}>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
          strokeWidth="2.5" strokeLinecap="round">
          <line x1="12" y1="5" x2="12" y2="19" /><line x1="5" y1="12" x2="19" y2="12" />
        </svg>
        Neues Gespräch
      </button>
      <span style={{ fontFamily: T.mono, fontSize: 9, letterSpacing: '0.14em', textTransform: 'uppercase', color: T.accentHi, opacity: 0.85 }}>
        Verlauf
      </span>
      <div style={{ marginTop: 8, display: 'flex', flexDirection: 'column', gap: 3 }}>
        {history.map((c, i) => (
          <div key={i} style={{
            padding: '8px 10px', borderRadius: 5, cursor: 'pointer',
            background: c.active ? T.card : 'transparent',
            borderLeft: c.active ? `2px solid ${T.accentHi}` : '2px solid transparent',
          }}>
            <div style={{ color: T.text, fontSize: 12, fontWeight: c.active ? 500 : 400, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{c.q}</div>
            <div style={{ color: T.muted, fontSize: 10, fontFamily: T.mono, marginTop: 2 }}>{c.t}</div>
          </div>
        ))}
      </div>
    </div>
  );
}

// ─── Component ────────────────────────────────────────────────────────────────

export default function ChatScreen() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [inputText, setInputText] = useState('');
  const [isSending, setIsSending] = useState(false);
  const chatContainerRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const sessionIdRef = useRef<string>(crypto.randomUUID());
  const abortControllerRef = useRef<AbortController | null>(null);
  const locationRef = useRef<{ lat: number; lon: number } | null>(null);
  const lastInputWasVoiceRef = useRef(false);
  const currentMessageRef = useRef('');
  const voice = useVoice();
  const tts = useTTS();

  // Fetch location once on mount (used for weather queries)
  useEffect(() => {
    if (!navigator.geolocation) return;
    navigator.geolocation.getCurrentPosition(
      pos => { locationRef.current = { lat: pos.coords.latitude, lon: pos.coords.longitude }; },
      () => { /* location unavailable — continue without */ },
      { timeout: 5000 },
    );
  }, []);

  const startNewChat = useCallback(() => {
    abortControllerRef.current?.abort();
    tts.stop();
    setMessages([]);
    setInputText('');
    setIsSending(false);
    sessionIdRef.current = crypto.randomUUID();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Responsive sizing — sowohl Width als auch Height (DONNA-71)
  const [containerWidth, setContainerWidth] = useState(420);
  const [containerHeight, setContainerHeight] = useState(800);
  useEffect(() => {
    const obs = new ResizeObserver(entries => {
      for (const e of entries) {
        setContainerWidth(e.contentRect.width);
        setContainerHeight(e.contentRect.height);
      }
    });
    if (containerRef.current) obs.observe(containerRef.current);
    return () => obs.disconnect();
  }, []);

  const size = containerWidth < 380 ? 'sm' : containerWidth >= 760 ? 'lg' : 'md';
  const compact = size === 'sm';
  const wide = size === 'lg';

  // DONNA-71: Continuous responsive scaling — Empty-State-Werte werden linear
  // zur verfügbaren Höhe interpoliert statt in discrete Breakpoints zu springen.
  // Das fühlt sich live-responsive an statt "static".
  // Empty-State braucht ~Avatar + 3*Gap + Headline + Subline + paddingBottom.
  // Verfügbare Höhe ist `containerHeight - ~150px Chrome` (Header + InputBar).
  const availH = Math.max(200, containerHeight - 150);
  // Avatar zwischen 60 (sehr eng) und 180 (großzügig), gemäß availableHeight 250-900px
  const lerp = (min: number, max: number, range: [number, number]) => {
    const [lo, hi] = range;
    const t = Math.max(0, Math.min(1, (availH - lo) / (hi - lo)));
    return Math.round(min + (max - min) * t);
  };
  const emptyAvatar = lerp(60, 180, [250, 900]);
  const emptyHeadline = lerp(16, 36, [250, 900]);
  const emptySubline = lerp(11, 16, [250, 900]);
  const emptyGap = lerp(8, 28, [250, 900]);
  const emptyPaddingBottom = lerp(8, 48, [250, 900]);

  // Scroll to bottom
  const scrollToBottom = useCallback(() => {
    const el = chatContainerRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, []);

  useEffect(() => { scrollToBottom(); }, [messages, scrollToBottom]);

  // Auto-resize textarea
  const handleTextareaChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setInputText(e.target.value);
    const ta = e.target;
    ta.style.height = 'auto';
    ta.style.height = `${Math.min(ta.scrollHeight, 96 + 20)}px`;
  };

  // Voice transcript → send (isVoice=true)
  useEffect(() => {
    if (voice.transcript && !voice.isListening) sendMessage(voice.transcript, true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [voice.transcript, voice.isListening]);

  const sendMessage = useCallback(async (text: string, isVoice = false) => {
    const trimmed = text.trim();
    if (!trimmed || isSending) return;

    lastInputWasVoiceRef.current = isVoice;
    currentMessageRef.current = '';

    const userMsg: Message = { id: crypto.randomUUID(), role: 'user', content: trimmed };
    const assistantMsgId = crypto.randomUUID();
    const assistantMsg: Message = { id: assistantMsgId, role: 'assistant', content: '', isStreaming: true };

    setMessages(prev => [...prev, userMsg, assistantMsg]);
    setInputText('');
    if (textareaRef.current) textareaRef.current.style.height = 'auto';
    setIsSending(true);

    const controller = new AbortController();
    abortControllerRef.current = controller;

    try {
      await streamChat(
        trimmed,
        (chunk) => {
          if (chunk.type === 'delta' && chunk.content) {
            currentMessageRef.current += chunk.content;
            setMessages(prev => prev.map(m =>
              m.id === assistantMsgId ? { ...m, content: m.content + chunk.content } : m
            ));
          } else if (chunk.type === 'done') {
            setMessages(prev => prev.map(m =>
              m.id === assistantMsgId ? { ...m, isStreaming: false } : m
            ));
            if (lastInputWasVoiceRef.current && currentMessageRef.current) {
              tts.speak(currentMessageRef.current);
            }
          } else if (chunk.type === 'error') {
            setMessages(prev => prev.map(m =>
              m.id === assistantMsgId
                ? { ...m, content: `Fehler: ${chunk.error ?? 'Unbekannter Fehler'}`, isStreaming: false }
                : m
            ));
          } else if (chunk.type === 'card' && chunk.card?.card_type === 'weather') {
            setMessages(prev => prev.map(m =>
              m.id === assistantMsgId
                ? { ...m, weatherCard: chunk.card!.data as WeatherCardData }
                : m
            ));
          }
        },
        controller.signal,
        locationRef.current ?? undefined,
        sessionIdRef.current,
        'windows',
      );
    } catch (err: unknown) {
      if (err instanceof Error && err.name === 'AbortError') {
        setMessages(prev => prev.map(m =>
          m.id === assistantMsgId ? { ...m, isStreaming: false } : m
        ));
      } else {
        const msg = err instanceof Error ? err.message : 'Verbindungsfehler';
        setMessages(prev => prev.map(m =>
          m.id === assistantMsgId
            ? { ...m, content: `Fehler: ${msg}`, isStreaming: false }
            : m
        ));
      }
    } finally {
      setIsSending(false);
      abortControllerRef.current = null;
    }
  }, [isSending]);

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      tts.stop();
      sendMessage(inputText, false);
    }
  };

  const btnSize = compact ? 32 : 40;
  const fontSize = compact ? 13 : 14;
  const inputPadding = compact ? '7px 10px' : '10px 14px';
  const msgMaxWidth = compact ? '88%' : '70%';
  // colMaxWidth nur als CAP, NIE als fixe Breite — sonst Overflow bei kleinen Fenstern
  const colMaxWidth = wide ? 760 : compact ? '100%' : '100%';

  return (
    <div
      ref={containerRef}
      style={{
        flex: 1,
        display: 'flex',
        overflow: 'hidden',
        background: T.bg,
        fontFamily: T.font,
      }}
    >
      {/* Sidebar — lg only */}
      {wide && <Sidebar onNewChat={startNewChat} />}

      {/* Main column */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0, overflow: 'hidden' }}>
        {/* Messages */}
        <div ref={chatContainerRef} style={{ flex: 1, minHeight: 0, overflowY: 'auto', display: 'flex', flexDirection: 'column' }}>
          <div style={{
            width: '100%',
            maxWidth: colMaxWidth,
            margin: '0 auto',
            padding: compact ? '10px 12px' : '16px 20px',
            display: 'flex',
            flexDirection: 'column',
            flexGrow: 1,
            gap: compact ? 6 : 8,
            boxSizing: 'border-box',
          }}>
            {messages.length === 0 ? (
              /* DONNA-71: Continuous responsive — alle Werte skalieren linear
                 mit verfügbarer Höhe statt in discrete Breakpoints zu springen. */
              <div style={{
                flex: 1,
                display: 'flex', flexDirection: 'column',
                alignItems: 'center', justifyContent: 'center',
                gap: emptyGap,
                color: T.muted, userSelect: 'none',
                paddingBottom: emptyPaddingBottom,
              }}>
                <DonnaAvatar size={emptyAvatar} animate />
                <div style={{
                  fontSize: emptyHeadline,
                  color: T.textDim,
                  fontWeight: 300,
                  letterSpacing: '-0.02em',
                  textAlign: 'center',
                }}>
                  Hi, ich bin Donna.
                </div>
                <div style={{
                  fontSize: emptySubline,
                  textAlign: 'center',
                  maxWidth: Math.min(containerWidth - 40, 480),
                  lineHeight: 1.5,
                  color: T.muted,
                }}>
                  Schreib etwas oder drück das Mikrofon.
                </div>
              </div>
            ) : (
              /* Spacer pushes messages to bottom */
              <div style={{ flex: 1 }} />
            )}

            {messages.map(msg => {
              if (msg.role === 'user') {
                return (
                  <div key={msg.id} style={{ display: 'flex', justifyContent: 'flex-end', animation: 'fadeUp 0.3s ease' }}>
                    <div style={{
                      maxWidth: msgMaxWidth,
                      padding: compact ? '8px 12px' : '10px 14px',
                      fontSize, lineHeight: 1.5,
                      background: T.accent,
                      color: '#fff',
                      borderRadius: '14px 14px 4px 14px',
                      wordBreak: 'break-word',
                      boxShadow: `0 2px 12px ${T.glow2}`,
                    }}>
                      {renderMarkdown(msg.content)}
                    </div>
                  </div>
                );
              }

              const isError = msg.content.startsWith('Fehler:');
              return (
                <div key={msg.id} style={{ display: 'flex', alignItems: 'flex-start', gap: 8, animation: 'fadeUp 0.3s ease' }}>
                  {!compact && (
                    <div style={{ marginTop: 2, flexShrink: 0 }}>
                      <DonnaAvatar size={24} />
                    </div>
                  )}
                  <div style={{
                    maxWidth: msgMaxWidth,
                    padding: compact ? '8px 12px' : '10px 14px',
                    fontSize, lineHeight: 1.5,
                    background: isError ? '#3e1a1a' : T.card,
                    color: isError ? '#ff6b6b' : T.textDim,
                    borderRadius: '14px 14px 14px 4px',
                    wordBreak: 'break-word',
                    ...(isError ? {} : { borderLeft: `2px solid ${T.accentHi}` }),
                  }}>
                    {renderMarkdown(msg.content)}
                    {msg.isStreaming && msg.content === '' && (
                      <span style={{ color: T.accentHi, fontSize: 13 }}>…</span>
                    )}
                    {msg.isStreaming && msg.content !== '' && (
                      <span style={{
                        display: 'inline-block', width: 6, height: 6,
                        borderRadius: '50%', background: T.accentHi,
                        marginLeft: 4, verticalAlign: 'middle',
                        animation: 'pulseDot 1s infinite',
                      }} />
                    )}
                    {msg.weatherCard && <WeatherCard data={msg.weatherCard} />}
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        {/* Input row */}
        <div style={{ borderTop: `1px solid ${T.border}`, background: T.bg }}>
          {/* Voice error banner */}
          {voice.error && (
            <div style={{
              padding: '4px 16px',
              background: 'rgba(239,68,68,0.12)',
              borderTop: '1px solid rgba(239,68,68,0.25)',
              color: '#ef4444',
              fontSize: 11,
              fontFamily: T.mono,
            }}>
              🎤 {voice.error}
            </div>
          )}
          <div style={{
            width: '100%',
            maxWidth: colMaxWidth,
            margin: '0 auto',
            padding: compact ? '8px 8px' : '12px 16px',
            display: 'flex',
            alignItems: 'flex-end',
            gap: compact ? 6 : 8,
            boxSizing: 'border-box',
            overflow: 'hidden',
          }}>
            {/* TTS Toggle button */}
            <button
              title={tts.ttsEnabled ? 'TTS aktiv — klicken zum Deaktivieren' : 'TTS deaktiviert — klicken zum Aktivieren'}
              onClick={() => tts.setTtsEnabled(!tts.ttsEnabled)}
              style={{
                width: btnSize, height: btnSize, borderRadius: 8, flexShrink: 0,
                background: tts.ttsEnabled ? 'rgba(98,0,238,0.15)' : 'rgba(42,42,62,0.5)',
                border: `1px solid ${tts.ttsEnabled ? T.border : 'rgba(58,58,78,0.5)'}`,
                color: tts.ttsEnabled ? T.accentHi : T.muted,
                cursor: 'pointer',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                opacity: tts.ttsEnabled ? 1 : 0.5,
                transition: 'all 0.15s',
              }}
            >
              {tts.ttsEnabled
                ? <SpeakerOnIcon size={compact ? 14 : 16} />
                : <SpeakerOffIcon size={compact ? 14 : 16} />
              }
            </button>

            {/* Mic / Stop button */}
            <button
              title={voice.isListening ? 'Aufnahme stoppen' : voice.supported ? 'Spracheingabe' : 'Spracheingabe nicht verfügbar'}
              onClick={voice.supported ? (voice.isListening ? voice.stop : voice.start) : undefined}
              disabled={!voice.supported}
              style={{
                width: btnSize, height: btnSize, borderRadius: 8, flexShrink: 0,
                background: voice.isListening ? 'rgba(239,68,68,0.18)' : 'rgba(98,0,238,0.15)',
                border: `1px solid ${voice.isListening ? 'rgba(239,68,68,0.4)' : T.border}`,
                color: voice.isListening ? '#ef4444' : T.accentHi,
                cursor: voice.supported ? 'pointer' : 'not-allowed',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                opacity: voice.supported ? 1 : 0.4,
                transition: 'all 0.15s',
              }}
            >
              {voice.isListening
                ? <StopIcon size={compact ? 12 : 14} />
                : <MicIcon size={compact ? 14 : 16} />
              }
            </button>

            {/* Listening indicator */}
            {voice.isListening && (
              <div style={{
                width: 7, height: 7, borderRadius: '50%',
                background: T.accentHi, flexShrink: 0, alignSelf: 'center',
                animation: 'hexPulse 0.7s infinite',
              }} />
            )}

            {/* Text input */}
            <textarea
              ref={textareaRef}
              value={inputText}
              onChange={handleTextareaChange}
              onKeyDown={handleKeyDown}
              placeholder="Nachricht…"
              rows={1}
              disabled={isSending}
              style={{
                flex: 1,
                minWidth: 0,
                padding: inputPadding,
                borderRadius: 8,
                background: T.card,
                border: `1px solid ${T.border}`,
                color: T.text,
                fontSize,
                lineHeight: 1.5,
                resize: 'none',
                outline: 'none',
                fontFamily: T.font,
                minHeight: btnSize,
                maxHeight: 120,
                overflowY: 'auto',
                boxSizing: 'border-box',
              }}
            />

            {/* Send button */}
            <button
              title="Senden"
              onClick={() => { tts.stop(); sendMessage(inputText, false); }}
              disabled={isSending || !inputText.trim()}
              style={{
                width: btnSize, height: btnSize, borderRadius: 8, flexShrink: 0,
                background: T.accent,
                border: 'none',
                cursor: isSending || !inputText.trim() ? 'not-allowed' : 'pointer',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                boxShadow: isSending || !inputText.trim() ? 'none' : `0 0 12px ${T.glow}`,
                opacity: isSending || !inputText.trim() ? 0.4 : 1,
                transition: 'all 0.15s',
              }}
            >
              <SendIcon size={compact ? 12 : 14} />
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

import React, { useEffect, useState, useRef, useCallback } from 'react';
import { streamChat } from '@donna/shared';
import { useVoice } from '../src/hooks/useVoice';
import { useTTS } from '../src/hooks/useTTS';

// ─── Design Tokens ────────────────────────────────────────────────────────────

const T = {
  accent: '#6200ee',
  accentHi: '#9c4dff',
  glow: 'rgba(98,0,238,0.45)',
  text: '#ffffff',
  textDim: '#e0e0e0',
  muted: '#8a8aa0',
};

type State = 'idle' | 'listening' | 'thinking' | 'response';

// ─── Icons ────────────────────────────────────────────────────────────────────

function DonnaAvatar({ size = 28, color = T.accentHi }: { size?: number; color?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 44 44" fill="none">
      <polygon points="22,2 38,11 38,33 22,42 6,33 6,11" stroke={color} strokeWidth={1.2} fill="none" />
      <circle cx={22} cy={22} r={11} stroke={color} strokeWidth={0.8} opacity={0.5} fill="none" />
      <path d="M18 15 h6 a7 7 0 0 1 0 14 h-6 Z" fill={color} opacity={0.15} />
      <path d="M18 15 h6 a7 7 0 0 1 0 14 h-6 Z" stroke={color} strokeWidth={0.8} fill="none" />
      <circle cx={22} cy={22} r={1.5} fill={color} />
    </svg>
  );
}

function MicIcon({ size = 16, color = T.accentHi }: { size?: number; color?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <rect x="9" y="2" width="6" height="12" rx="3" stroke={color} strokeWidth="1.8" />
      <path d="M5 10a7 7 0 0 0 14 0" stroke={color} strokeWidth="1.8" strokeLinecap="round" />
      <line x1="12" y1="19" x2="12" y2="22" stroke={color} strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  );
}

function SendIcon({ size = 14, color = '#fff' }: { size?: number; color?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <path d="M22 2L11 13" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M22 2L15 22L11 13L2 9L22 2Z" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function SpeakerOnIcon({ size = 14, color = T.accentHi }: { size?: number; color?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color}
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5" />
      <path d="M15.54 8.46a5 5 0 0 1 0 7.07" />
      <path d="M19.07 4.93a10 10 0 0 1 0 14.14" />
    </svg>
  );
}

function SpeakerOffIcon({ size = 14, color = T.muted }: { size?: number; color?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color}
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5" />
      <line x1="23" y1="9" x2="17" y2="15" />
      <line x1="17" y1="9" x2="23" y2="15" />
    </svg>
  );
}

function PinIcon({ size = 12, color = T.muted }: { size?: number; color?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <path d="M12 2L15.09 8.26L22 9.27L17 14.14L18.18 21.02L12 17.77L5.82 21.02L7 14.14L2 9.27L8.91 8.26L12 2Z"
        stroke={color} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

// ─── Keyframe Animations ──────────────────────────────────────────────────────

const ANIMATIONS = `
@keyframes hexPulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.5; transform: scale(0.85); }
}
@keyframes orbBreath {
  0% { box-shadow: 0 0 16px rgba(98,0,238,0.6), 0 0 32px rgba(98,0,238,0.2); }
  100% { box-shadow: 0 0 28px rgba(156,77,255,0.8), 0 0 56px rgba(98,0,238,0.35); }
}
@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}
@keyframes fadeIn {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: translateY(0); }
}
`;

// ─── Drag Handle ──────────────────────────────────────────────────────────────

function DragHandle({
  alwaysOnTop,
  onPinToggle,
  ttsEnabled,
  onTtsToggle,
}: {
  alwaysOnTop: boolean;
  onPinToggle: () => void;
  ttsEnabled: boolean;
  onTtsToggle: () => void;
}) {
  return (
    <div style={{
      height: 24,
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'space-between',
      padding: '0 10px',
      WebkitAppRegion: 'drag',
      flexShrink: 0,
    } as React.CSSProperties}>
      <div style={{ width: 32, height: 3, borderRadius: 2, background: T.accentHi, opacity: 0.7 }} />
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, WebkitAppRegion: 'no-drag' } as React.CSSProperties}>
        <span style={{ fontFamily: 'Space Mono, monospace', fontSize: 8, color: T.muted, letterSpacing: '0.05em' }}>
          ALWAYS ON TOP
        </span>
        <button onClick={onTtsToggle} title={ttsEnabled ? 'TTS aktiv' : 'TTS deaktiviert'} style={{
          background: 'none', border: 'none', cursor: 'pointer', padding: 0,
          display: 'flex', alignItems: 'center', opacity: ttsEnabled ? 1 : 0.4,
        }}>
          {ttsEnabled
            ? <SpeakerOnIcon size={12} color={T.accentHi} />
            : <SpeakerOffIcon size={12} color={T.muted} />
          }
        </button>
        <button onClick={onPinToggle} title="Pin Toggle" style={{
          background: 'none', border: 'none', cursor: 'pointer', padding: 0,
          display: 'flex', alignItems: 'center', opacity: alwaysOnTop ? 1 : 0.4,
        }}>
          <PinIcon size={12} color={alwaysOnTop ? T.accentHi : T.muted} />
        </button>
        <button onClick={() => (window as any).electron?.hideOverlay?.()} title="Schließen" style={{
          background: 'none', border: 'none', cursor: 'pointer', padding: 0,
          color: T.muted, fontSize: 11, lineHeight: 1, display: 'flex', alignItems: 'center',
        }}>
          ✕
        </button>
      </div>
    </div>
  );
}

// ─── Input Row (shared between idle + response) ───────────────────────────────

function InputRow({
  onSend,
  onMic,
  placeholder = 'Frag Donna…',
  small = false,
}: {
  onSend: (text: string) => void;
  onMic: () => void;
  placeholder?: string;
  small?: boolean;
}) {
  const [input, setInput] = useState('');
  const btnSize = small ? 32 : 36;

  const handleSend = () => {
    const t = input.trim();
    if (!t) return;
    onSend(t);
    setInput('');
  };

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      <button onClick={onMic} title="Spracheingabe" style={{
        width: btnSize, height: btnSize, borderRadius: 8, flexShrink: 0,
        background: 'rgba(98,0,238,0.2)', border: '1px solid rgba(156,77,255,0.3)',
        cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center',
      }}>
        <MicIcon size={small ? 14 : 16} />
      </button>
      <input
        value={input}
        onChange={e => setInput(e.target.value)}
        onKeyDown={e => { if (e.key === 'Enter') handleSend(); }}
        placeholder={placeholder}
        style={{
          flex: 1, padding: small ? '7px 12px' : '9px 13px',
          borderRadius: 8, background: 'rgba(0,0,0,0.25)',
          border: '1px solid rgba(255,255,255,0.06)',
          color: 'rgba(255,255,255,0.55)', fontSize: 13,
          fontFamily: 'Space Grotesk, sans-serif', outline: 'none',
        }}
      />
      <button onClick={handleSend} title="Senden" style={{
        width: btnSize, height: btnSize, borderRadius: 8, flexShrink: 0,
        background: T.accent, border: 'none', cursor: 'pointer',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        boxShadow: `0 0 10px ${T.glow}`,
      }}>
        <SendIcon size={small ? 12 : 14} />
      </button>
      <div style={{ borderLeft: '1px solid rgba(255,255,255,0.1)', paddingLeft: 10, flexShrink: 0 }}>
        <span style={{ fontFamily: 'Space Mono, monospace', fontSize: 9, color: T.muted, whiteSpace: 'nowrap' }}>
          Ctrl+⇧+D
        </span>
      </div>
    </div>
  );
}

// ─── Response Bubble ──────────────────────────────────────────────────────────

function ResponseBubble({ text, isStreaming }: { text: string; isStreaming: boolean }) {
  return (
    <div style={{
      display: 'flex', alignItems: 'flex-start', gap: 10,
      animation: 'fadeIn 0.2s ease',
    }}>
      <DonnaAvatar size={24} />
      <div style={{
        flex: 1,
        background: 'rgba(0,0,0,0.0)',
        borderLeft: `2px solid ${T.accentHi}`,
        padding: '6px 12px',
        borderRadius: '2px 10px 10px 2px',
        fontSize: 13,
        color: 'rgba(255,255,255,0.88)',
        lineHeight: 1.55,
        wordBreak: 'break-word',
      }}>
        {text || <span style={{ color: T.muted }}>…</span>}
        {isStreaming && text && (
          <span style={{
            display: 'inline-block', width: 5, height: 5, borderRadius: '50%',
            background: T.accentHi, marginLeft: 4, verticalAlign: 'middle',
            animation: 'hexPulse 0.8s infinite',
          }} />
        )}
      </div>
    </div>
  );
}

// ─── Main App ─────────────────────────────────────────────────────────────────

export default function OverlayApp() {
  const [state, setState] = useState<State>('idle');
  const [alwaysOnTop, setAlwaysOnTop] = useState(true);
  const [responseText, setResponseText] = useState('');
  const [isStreaming, setIsStreaming] = useState(false);
  const sessionIdRef = useRef(crypto.randomUUID());
  const abortRef = useRef<AbortController | null>(null);
  const locationRef = useRef<{ lat: number; lon: number } | null>(null);
  const lastInputWasVoiceRef = useRef(false);
  const currentMessageRef = useRef('');
  const voice = useVoice();
  const tts = useTTS();

  useEffect(() => {
    if (!navigator.geolocation) return;
    navigator.geolocation.getCurrentPosition(
      pos => { locationRef.current = { lat: pos.coords.latitude, lon: pos.coords.longitude }; },
      () => {},
      { timeout: 5000 },
    );
  }, []);

  useEffect(() => {
    (window as any).electron?.onOverlayState?.((s: string) => {
      setState(s as State);
    });
  }, []);

  const handleSend = useCallback(async (text: string, isVoice = false) => {
    abortRef.current?.abort();
    tts.stop();
    const ctrl = new AbortController();
    abortRef.current = ctrl;

    lastInputWasVoiceRef.current = isVoice;
    currentMessageRef.current = '';
    setResponseText('');
    setIsStreaming(true);
    setState('thinking');
    (window as any).electron?.setOverlayState?.('response');

    try {
      await streamChat(
        text,
        (chunk) => {
          if (chunk.type === 'delta' && chunk.content) {
            currentMessageRef.current += chunk.content;
            setResponseText(prev => prev + chunk.content);
            setState('response');
          } else if (chunk.type === 'done') {
            setIsStreaming(false);
            if (lastInputWasVoiceRef.current && currentMessageRef.current) {
              tts.speak(currentMessageRef.current);
            }
          } else if (chunk.type === 'error') {
            setResponseText(`Fehler: ${chunk.error}`);
            setIsStreaming(false);
            setState('response');
          }
        },
        ctrl.signal,
        locationRef.current ?? undefined,
        sessionIdRef.current,
        'windows',
      );
    } catch {
      setIsStreaming(false);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tts]);

  // Auto-send when voice transcript arrives (isVoice=true → TTS nach done)
  useEffect(() => {
    if (voice.transcript && !voice.isListening) {
      handleSend(voice.transcript, true);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [voice.transcript, voice.isListening]);

  const handleMicClick = () => {
    if (voice.isListening) {
      voice.stop();
      setState('idle');
    } else {
      voice.start();
      setState('listening');
      (window as any).electron?.setOverlayState?.('listening');
    }
  };

  const handleClose = () => {
    abortRef.current?.abort();
    tts.stop();
    setState('idle');
    setResponseText('');
    (window as any).electron?.hideOverlay?.();
  };

  return (
    <>
      <style>{ANIMATIONS}</style>
      {/* Outer wrapper — full window, no overflow, no shadow outside bounds */}
      <div style={{
        width: '100vw',
        height: '100vh',
        display: 'flex',
        alignItems: 'flex-start',
        justifyContent: 'center',
        padding: '0 4px 4px',
        boxSizing: 'border-box',
        overflow: 'hidden',
      }}>
        {/* Glass panel — contained within window bounds */}
        <div style={{
          width: '100%',
          borderRadius: '0 0 16px 16px',
          background: 'rgba(22,22,40,0.88)',
          backdropFilter: 'blur(24px) saturate(140%)',
          border: '1px solid rgba(156,77,255,0.22)',
          borderTop: 'none',
          overflow: 'hidden',
          display: 'flex',
          flexDirection: 'column',
          color: T.text,
          fontFamily: 'Space Grotesk, sans-serif',
        }}>
          <DragHandle
            alwaysOnTop={alwaysOnTop}
            onPinToggle={() => setAlwaysOnTop(p => !p)}
            ttsEnabled={tts.ttsEnabled}
            onTtsToggle={() => tts.setTtsEnabled(!tts.ttsEnabled)}
          />

          {/* Content area */}
          <div style={{ padding: '0 12px 12px', display: 'flex', flexDirection: 'column', gap: 10 }}>

            {/* Response bubble — visible in thinking/response state */}
            {(state === 'thinking' || state === 'response') && (
              <ResponseBubble text={responseText} isStreaming={isStreaming} />
            )}

            {/* Voice error */}
            {voice.error && (
              <div style={{ fontSize: 11, color: '#ef4444', fontFamily: 'Space Mono, monospace', padding: '2px 0' }}>
                🎤 {voice.error}
              </div>
            )}

            {/* Listening transcript */}
            {state === 'listening' && (
              <div style={{
                display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 12,
                paddingTop: 8,
              }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <div style={{
                    width: 7, height: 7, borderRadius: '50%', background: T.accentHi,
                    animation: 'hexPulse 1.2s ease-in-out infinite',
                  }} />
                  <span style={{ fontFamily: 'Space Mono, monospace', fontSize: 9, color: T.accentHi, letterSpacing: '0.1em' }}>
                    HÖRE ZU
                  </span>
                </div>
                <div style={{
                  width: 60, height: 60, borderRadius: '50%',
                  background: 'radial-gradient(circle at 40% 35%, #9c4dff, #6200ee 55%, #2e1065)',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  animation: 'orbBreath 1s ease-in-out infinite alternate',
                }}>
                  <DonnaAvatar size={24} color="#ffffff" />
                </div>
              </div>
            )}

            {/* Input row — always visible */}
            <InputRow
              onSend={handleSend}
              onMic={handleMicClick}
              placeholder={state === 'response' ? 'Nachfragen…' : 'Frag Donna…'}
              small={state === 'response'}
            />
          </div>
        </div>
      </div>
    </>
  );
}

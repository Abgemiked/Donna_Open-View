import React, { useState, useEffect } from 'react';
import ChatScreen from './components/ChatScreen';
import { PairingScreen } from './components/PairingScreen';
import { setApiToken } from '@donna/shared';

const T = {
  accent:   '#6200ee',
  accentHi: '#9c4dff',
  font:     "'Space Grotesk', sans-serif",
};

// ─── DonnaAvatar — same shape as design file ─────────────────────────────────

export function DonnaAvatar({ size = 20, color = T.accentHi }: { size?: number; color?: string }) {
  return (
    <div style={{ width: size, height: size, display: 'inline-flex', flexShrink: 0 }}>
      <svg width={size} height={size} viewBox="0 0 44 44" fill="none">
        <polygon points="22,2 38,11 38,33 22,42 6,33 6,11" fill="none" stroke={color} strokeWidth="1.2" />
        <circle cx="22" cy="22" r="11" fill="none" stroke={color} strokeWidth="0.8" opacity="0.5" />
        <path d="M16 15h6c5 0 8 3 8 7s-3 7-8 7h-6V15z" fill={color} opacity="0.15" />
        <path d="M17 16h5c4 0 6.5 2.5 6.5 6s-2.5 6-6.5 6h-5V16z"
          fill="none" stroke={color} strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
        <circle cx="22" cy="22" r="1.5" fill={color} />
      </svg>
    </div>
  );
}

// ─── Windows 11-style title button ───────────────────────────────────────────

function WinBtn({ icon, close, onClick }: { icon: string; close?: boolean; onClick?: () => void }) {
  const [hover, setHover] = useState(false);
  return (
    <button
      style={{
        width: 42, height: 36,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        background: hover ? (close ? '#e81123' : 'rgba(255,255,255,0.15)') : 'transparent',
        border: 'none',
        color: 'rgba(255,255,255,0.85)',
        fontSize: 11,
        cursor: 'pointer',
        padding: 0,
        transition: 'background 0.15s',
        // @ts-ignore
        WebkitAppRegion: 'no-drag',
      }}
      onClick={onClick}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
    >
      {icon}
    </button>
  );
}

// ─── App ──────────────────────────────────────────────────────────────────────

export default function App() {
  const handleMinimize = () => (window as any).electron?.minimizeToTray?.();
  const handleMaximize = () => (window as any).electron?.maximizeWindow?.();
  const handleClose    = () => (window as any).electron?.minimizeToTray?.();

  // DONNA-103: Pairing-Check beim Start
  const isPairingMode = new URLSearchParams(window.location.search).get('pairing') === '1';
  const [isPaired, setIsPaired] = useState(!isPairingMode);

  useEffect(() => {
    // Token vom Main-Prozess empfangen (nach Pairing oder beim Start)
    (window as any).electron?.onApiToken?.((token: string) => {
      setApiToken(token);
      setIsPaired(true);
    });
    // Beim Start Token aus safeStorage holen (falls bereits gepairt)
    if (!isPairingMode) {
      (async () => {
        const token: string | null = await (window as any).electron?.getToken?.();
        if (token) { setApiToken(token); }
      })();
    }
  }, []);

  if (!isPaired || isPairingMode) {
    return <PairingScreen />;
  }

  return (
    <div style={{
      display: 'flex',
      flexDirection: 'column',
      height: '100vh',
      width: '100vw',
      backgroundColor: '#1a1a2e',
      color: '#ffffff',
      fontFamily: T.font,
      overflow: 'hidden',
    }}>
      {/* Draggable title bar */}
      <header style={{
        height: 36,
        minHeight: 36,
        backgroundColor: T.accent,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingLeft: 14,
        paddingRight: 0,
        boxShadow: '0 1px 0 rgba(255,255,255,0.05) inset',
        flexShrink: 0,
        // @ts-ignore
        WebkitAppRegion: 'drag',
        userSelect: 'none',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <DonnaAvatar size={20} color="#fff" />
          <span style={{ color: '#fff', fontSize: 13, fontWeight: 600, letterSpacing: '0.3px' }}>
            Donna
          </span>
        </div>

        <div style={{ display: 'flex', height: '100%' }}>
          <WinBtn icon="—" onClick={handleMinimize} />
          <WinBtn icon="▢" onClick={handleMaximize} />
          <WinBtn icon="✕" close onClick={handleClose} />
        </div>
      </header>

      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        <ChatScreen />
      </div>
    </div>
  );
}

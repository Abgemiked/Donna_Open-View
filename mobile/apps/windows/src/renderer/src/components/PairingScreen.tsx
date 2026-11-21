/**
 * DONNA-103: PairingScreen — Einmaliger Pairing-Dialog für Windows.
 *
 * Wird gezeigt wenn noch kein Token in electron.safeStorage gespeichert ist.
 * Mike gibt seinen 6-stelligen Google-Authenticator-Code ein.
 * Bei Erfolg: Token via IPC an Main-Prozess übergeben → safeStorage → fertig.
 */

import { useState } from 'react';

const API_BASE = 'https://your-donna-instance.example.com';

export function PairingScreen(): JSX.Element {
  const [code, setCode] = useState('');
  const [status, setStatus] = useState<'idle' | 'loading' | 'error' | 'success'>('idle');
  const [errorMsg, setErrorMsg] = useState('');

  async function handlePair() {
    if (code.length !== 6 || !/^\d+$/.test(code)) {
      setErrorMsg('Bitte 6-stelligen Code eingeben');
      setStatus('error');
      return;
    }

    setStatus('loading');
    setErrorMsg('');

    try {
      const res = await fetch(`${API_BASE}/setup/pair`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ totp: code }),
        signal: AbortSignal.timeout(15000),
      });

      if (!res.ok) {
        const body = await res.json().catch(() => ({ detail: `HTTP ${res.status}` })) as { detail?: string };
        setErrorMsg(body.detail ?? `Fehler: HTTP ${res.status}`);
        setStatus('error');
        return;
      }

      const data = await res.json() as { token: string };
      // Token an Main-Prozess übergeben — speichert in safeStorage und öffnet Hauptfenster
      await (window as any).electron?.pairingComplete?.(data.token);
      setStatus('success');

    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setErrorMsg(msg);
      setStatus('error');
    }
  }

  return (
    <div style={{
      display: 'flex',
      flexDirection: 'column',
      alignItems: 'center',
      justifyContent: 'center',
      height: '100vh',
      backgroundColor: '#0d0d1a',
      color: '#e2e8f0',
      fontFamily: 'system-ui, sans-serif',
      padding: '32px',
      boxSizing: 'border-box',
    }}>
      <h1 style={{ fontSize: '24px', marginBottom: '8px', color: '#38bdf8' }}>
        Donna verbinden
      </h1>
      <p style={{ fontSize: '14px', color: '#94a3b8', marginBottom: '32px', textAlign: 'center' }}>
        6-stelligen Code aus Google Authenticator eingeben
      </p>

      <input
        type="text"
        inputMode="numeric"
        maxLength={6}
        value={code}
        onChange={e => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
        onKeyDown={e => { if (e.key === 'Enter') handlePair(); }}
        placeholder="123456"
        disabled={status === 'loading' || status === 'success'}
        style={{
          width: '160px',
          padding: '12px 16px',
          fontSize: '28px',
          letterSpacing: '8px',
          textAlign: 'center',
          backgroundColor: '#1a1a2e',
          color: '#e2e8f0',
          border: '1px solid #334155',
          borderRadius: '8px',
          outline: 'none',
          marginBottom: '16px',
        }}
        autoFocus
      />

      <button
        onClick={handlePair}
        disabled={status === 'loading' || status === 'success'}
        style={{
          padding: '10px 32px',
          fontSize: '16px',
          backgroundColor: status === 'success' ? '#22c55e' : '#38bdf8',
          color: '#0d0d1a',
          border: 'none',
          borderRadius: '8px',
          cursor: status === 'loading' ? 'wait' : 'pointer',
          fontWeight: 'bold',
          marginBottom: '16px',
        }}
      >
        {status === 'loading' ? 'Verbinde …' : status === 'success' ? 'Verbunden!' : 'Verbinden'}
      </button>

      {status === 'error' && (
        <p style={{ color: '#f87171', fontSize: '14px', textAlign: 'center' }}>
          {errorMsg}
        </p>
      )}
    </div>
  );
}

/**
 * Batch 3 (Overlay-Politur + Robustheit): Quelltext-Verträge. Das Verhalten (Layout, Animation, Mikrofon) ist nur
 * am Gerät prüfbar; die reine Logik steckt in den Kotlin-JVM-Tests (SafeContentTest, SseBatch3Test u. a.).
 */
import fs from 'fs';
import path from 'path';

const KT = path.resolve(__dirname, '../../android/app/src/main/java/com/yourcompany/donna');
const read = (f: string) => fs.readFileSync(path.join(KT, f), 'utf8');

describe('Batch 3 — Robustheit/Sicherheit', () => {
  const kt = read('VoiceInputActivity.kt');
  const safe = read('SafeContent.kt');
  const sse = read('SseEvents.kt');
  // Post-B1: Aktionsausführung / Karten / Turn-Rendering leben in overlay/, die Activity delegiert.
  const exec = read('overlay/ActionExecutor.kt');
  const cards = read('overlay/ActionCardBuilder.kt');
  const turn = read('overlay/TurnRenderer.kt');

  it('1: URL-Allowlist (https, geo, google.navigation) + Host-Anzeige + freundliche Fehler', () => {
    expect(safe).toMatch(/ALLOWED_SCHEMES = setOf\("https", "geo", "google\.navigation"\)/);
    expect(safe).toMatch(/uri\.userInfo != null/);
    expect(kt).toMatch(/UrlPolicy\.check\(/);
    expect(kt).toMatch(/Ziel: \$\{checked\.displayTarget\(\)\}/);
    expect(kt).toMatch(/is ActivityNotFoundException -> /);
    expect(kt).toMatch(/is SecurityException -> /);
    // keine ungeprüften ACTION_VIEW-Aufrufe mehr für URLs aus dem Backend
    expect(kt).not.toMatch(/Uri\.parse\(mapsUrl\)/);
    expect(exec).toMatch(/"open_url" -> host\.openCheckedUrl\(UrlPolicy\.check/);
    expect(exec).not.toMatch(/Uri\.parse\(mapsUrl\)/);
    // Karten-Angebot für open_url nur mit erlaubtem Schema
    expect(cards).toMatch(/UrlPolicy\.check\(action\.safeString\("url"\)\) \?: return null/);
    expect(cards).toMatch(/Ziel: \$\{c\.displayTarget\(\)\}/);
  });

  it('2: href-Escape, WhatsApp-Nummer nur Ziffern, call/sms kodiert, JSON-null sicher', () => {
    expect(safe).toMatch(/replace\("\\"", "&quot;"\)/);
    expect(safe).toMatch(/PhoneNumbers/);
    // Post-B1: Intent-Templates + Encoding in overlay/ActionExecutor (ActionIntents), Markdown in overlay/TurnRenderer
    expect(exec).toMatch(/PhoneNumbers\.forWhatsApp\(/);
    expect(exec).toMatch(/"tel:\$\{encode\(number\)\}"/);
    expect(exec).toMatch(/"smsto:\$\{encode\(number\)\}"/);
    expect(exec).toMatch(/ActionIntents\.telUri\(number, enc\)/);
    expect(exec).toMatch(/ActionIntents\.smsUri\(number, enc\)/);
    expect(exec).toMatch(/private val enc: \(String\) -> String = \{ Uri\.encode\(it\) \}/);
    // JSON-null-sichere Extension in jeder Datei, die Backend-JSON liest
    expect(kt).toMatch(/private fun JSONObject\.safeString/);
    expect(exec).toMatch(/private fun JSONObject\.safeString/);
    expect(cards).toMatch(/private fun JSONObject\.safeString/);
    for (const src of [exec, cards]) {
      expect(src).not.toMatch(/\b(action|d|o|obj)\.optString\(/);
    }
    // Activity: kein ungesichertes optString auf Backend-JSON — lückenlos, keine Ausnahme.
    const unsafeInKt = kt.match(/\b(?:action|d|o|obj)\.optString\([^)]*\)/g) ?? [];
    expect(unsafeInKt).toEqual([]);
    expect(kt).toMatch(/JsonSafe\.numText\(d, "temp_c"\)/);
    expect(turn).toMatch(/MarkdownHtml\.convert\(shown\)/);
  });

  it('3: type:done als sauberes Ende, abgeschnittener Stream ist unvollständig, live_guard_corrected ersetzt', () => {
    expect(sse).toMatch(/"done" -> SseEvent\.Done/);
    expect(sse).toMatch(/"live_guard_corrected" -> SseEvent\.Replace/);
    expect(kt).toMatch(/!t\.sawDone && !t\.streamEnded/);
    expect(kt).toMatch(/unvollständig/);
    expect(kt).toMatch(/is SseEvent\.Replace -> replaceText\(t, e\.text\)/);
    // Korrektur verwirft bereits vorgemerkte Sprachausgabe (gesperrte Inhalte nicht weitersprechen)
    expect(kt).toMatch(/t\.pipeline\.stop\(\)[\s\S]{0,120}t\.pipeline = t\.newPipeline\(\)/);
  });

  it('4: Wiedergabe-Watchdog, Gesamtzeitgrenze des Streams, disconnect nicht auf dem UI-Thread', () => {
    expect(kt).toMatch(/Wiedergabe-Zeitüberschreitung/);
    expect(kt).toMatch(/it\.duration\.coerceAtLeast\(1_000\) \+ 4_000L/);
    expect(kt).toMatch(/STREAM_MAX_MS = 240_000L/);
    expect(kt).toMatch(/delay\(STREAM_MAX_MS\)/);
    expect(kt).toMatch(/activeConn\?\.let \{ c -> Thread \{ try \{ c\.disconnect\(\)/);
  });

  it('5: onResults verbraucht die Generation; Leerlauf schließt nach ~60 s; Bildschirm nicht dauerhaft wach', () => {
    expect(kt).toMatch(/override fun onResults\(results: Bundle\?\) \{\s*if \(stale\(\)\) return\s*\/\/[^\n]*\n\s*listenGeneration\+\+/);
    expect(kt).toMatch(/IDLE_CLOSE_MS = 60_000L/);
    expect(kt).toMatch(/idleCloseRunnable/);
    expect(kt).toMatch(/window\.clearFlags\(WindowManager\.LayoutParams\.FLAG_KEEP_SCREEN_ON\)/);
  });
});

describe('Batch 3 — Layout/UX', () => {
  const kt = read('VoiceInputActivity.kt');
  const orb = read('DonnaOrbView.kt');

  it('6: adaptives Panel — Fuß fest, Körper scrollt im Restplatz (keine festen Höhen-Bruchteile mehr)', () => {
    expect(kt).toMatch(/private class AdaptivePanel/);
    expect(kt).toMatch(/MeasureSpec\.makeMeasureSpec\(bodyMax, View\.MeasureSpec\.AT_MOST\)/);
    expect(kt).not.toMatch(/heightPixels \* 0\.\d+f/);
    expect(kt).not.toMatch(/MaxHeightScrollView/);
    expect(kt).toMatch(/adaptivePanel\.topReservePx = insets\.getInsets\(WindowInsetsCompat\.Type\.statusBars\(\)\)\.top/);
  });

  it('7: Enter im Textfeld sendet (IME_ACTION_SEND)', () => {
    expect(kt).toMatch(/imeOptions = EditorInfo\.IME_ACTION_SEND/);
    expect(kt).toMatch(/setOnEditorActionListener/);
  });

  it('8: Orb — Phase am Zustandswechsel, Glow passt in die View, statischer Ring beim Sprechen', () => {
    expect(orb).toMatch(/nowMs - stateSince/);
    expect(orb).toMatch(/base \* 0\.19f/);
    expect(orb).toMatch(/state == OverlayState\.SPEAKING\) \{[\s\S]{0,200}drawCircle\(cx, cy, coreR \* 1\.38f, ringPaint\)/);
    // größter Glow: 2,1 × (0,19 × 1,23 Kernskalierung) ≈ 0,49 × Kantenlänge < 0,5
    expect(2.1 * 0.19 * 1.23).toBeLessThan(0.5);
  });

  it('9: kleine Texte angehoben (kein 10–11 sp), Standort nicht mehr uppercase', () => {
    expect(kt).not.toMatch(/COMPLEX_UNIT_SP, 1[01]f\)/);
    expect(kt).not.toMatch(/location", ""\)\.uppercase\(\)/);
  });
});

import * as fs from 'fs';
import * as path from 'path';

/**
 * Vertrags- & Confirm-Gate-Tests für B2 Auto-Send (Stufe-3-Review R1/R4).
 *
 * Diese Tests scannen den Quelltext, weil sie strukturelle Sicherheits-Invarianten absichern, die
 * ein reiner Laufzeittest nicht erzwingt:
 *  - BLOCKER 1: die JS-Aufruf-Arity MUSS zur Kotlin-@ReactMethod-Signatur passen (sonst
 *    NativeArgumentsParseException, die JS nicht fangen kann).
 *  - R4 Confirm-Gate: performActionTap/requestAutoTap/maybeAutoSend dürfen NUR aus dem
 *    Bestätigungs-Pfad gerufen werden — nie automatisch.
 */

const ANDROID_ROOT = path.join(__dirname, '..', '..');
const KT = (name: string) =>
  path.join(ANDROID_ROOT, 'android', 'app', 'src', 'main', 'java', 'de', 'abgemiked', 'donna', name);
const SRC = (rel: string) => path.join(ANDROID_ROOT, 'src', rel);
const KT_MAIN = path.join(ANDROID_ROOT, 'android', 'app', 'src', 'main');
const TS_SRC = path.join(ANDROID_ROOT, 'src');

const read = (p: string): string => fs.readFileSync(p, 'utf8');

/** Körper einer Funktion/Methode ab der Deklaration per KLAMMERBALANCE (robust ggü. Dateiende). */
function bodyFrom(src: string, declIndex: number): string {
  const open = src.indexOf('{', declIndex);
  if (open < 0) return '';
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') {
      depth--;
      if (depth === 0) return src.slice(declIndex, i + 1);
    }
  }
  return src.slice(declIndex); // unbalanciert → bis Dateiende (konservativ)
}

/** Alle Dateien unter dir, deren Name auf eines der Suffixe endet (rekursiv, ohne build-Ordner). */
function walk(dir: string, exts: string[], skip: (name: string) => boolean = () => false): string[] {
  const out: string[] = [];
  for (const entry of fs.readdirSync(dir, {withFileTypes: true})) {
    if (entry.name === 'build' || entry.name === 'node_modules') continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (skip(entry.name)) continue;
      out.push(...walk(full, exts, skip));
    } else if (exts.some(e => entry.name.endsWith(e))) {
      out.push(full);
    }
  }
  return out;
}

describe('BLOCKER 1 — performActionTap Arity (JS ↔ Kotlin)', () => {
  it('Kotlin performActionTap hat genau 1 Nicht-Promise-Parameter (kind) + Promise', () => {
    const kt = read(KT('AccessibilityBridge.kt'));
    const m = kt.match(/fun\s+performActionTap\s*\(([^)]*)\)/);
    expect(m).toBeTruthy();
    const params = m![1]
      .split(',')
      .map(s => s.trim())
      .filter(Boolean);
    // Erwartet: ["kind: String", "promise: Promise"]
    expect(params).toHaveLength(2);
    expect(params[params.length - 1]).toMatch(/:\s*Promise\b/);
    const nonPromise = params.filter(p => !/:\s*Promise\b/.test(p));
    expect(nonPromise).toHaveLength(1);
    expect(nonPromise[0]).toMatch(/^kind\s*:\s*String/);
  });

  it('autoSend.ts ruft performActionTap mit GENAU 1 Argument', () => {
    const ts = read(SRC('utils/autoSend.ts'));
    // Interface-Signatur: ein Parameter
    const iface = ts.match(/performActionTap\s*\(\s*kind:\s*string\s*\)\s*:/);
    expect(iface).toBeTruthy();
    // Aufruf: bridge.performActionTap(type) — ein Argument, keine Komma-Liste
    const call = ts.match(/\.performActionTap\(([^)]*)\)/);
    expect(call).toBeTruthy();
    const args = call![1].split(',').map(s => s.trim()).filter(Boolean);
    expect(args).toHaveLength(1);
  });

  it('JS-Aufruf-Arity == Kotlin-Nicht-Promise-Arity', () => {
    const kt = read(KT('AccessibilityBridge.kt'));
    const ktParams = kt.match(/fun\s+performActionTap\s*\(([^)]*)\)/)![1]
      .split(',').map(s => s.trim()).filter(Boolean)
      .filter(p => !/:\s*Promise\b/.test(p)).length;
    const ts = read(SRC('utils/autoSend.ts'));
    const jsArgs = ts.match(/\.performActionTap\(([^)]*)\)/)![1]
      .split(',').map(s => s.trim()).filter(Boolean).length;
    expect(jsArgs).toBe(ktParams);
  });
});

describe('R4 — Confirm-Gate (nur nach Bestätigung)', () => {
  it('ChatScreen: triggerAutoSend wird NUR innerhalb von confirmPendingAction gerufen', () => {
    const ts = read(SRC('screens/ChatScreen.tsx'));
    const start = ts.indexOf('const confirmPendingAction');
    const end = ts.indexOf('const cancelPendingAction');
    expect(start).toBeGreaterThan(-1);
    expect(end).toBeGreaterThan(start);
    const body = ts.slice(start, end);
    const callRe = /triggerAutoSend\(/g;
    const total = (ts.match(callRe) || []).length;
    const inBody = (body.match(callRe) || []).length;
    expect(total).toBeGreaterThan(0);
    expect(inBody).toBe(total); // jeder Aufruf liegt im Bestätigungs-Handler
  });

  it('VoiceInputActivity: maybeAutoSend nur im executeAction-Override', () => {
    const kt = read(KT('VoiceInputActivity.kt'));
    // maybeAutoSend-Aufrufe (ohne die Definitionszeile `private fun maybeAutoSend`)
    const callSites = (kt.match(/(?<!fun\s)maybeAutoSend\(/g) || []).length;
    expect(callSites).toBe(1); // genau ein Aufruf
    // dieser Aufruf steht direkt nach actionExecutor.execute(action)
    expect(kt).toMatch(/actionExecutor\.execute\(action\)\s*\n\s*maybeAutoSend\(action\)/);
  });

  it('DonnaAccessibilityService triggert NICHT selbst aus onAccessibilityEvent', () => {
    const kt = read(KT('DonnaAccessibilityService.kt'));
    const evStart = kt.indexOf('override fun onAccessibilityEvent');
    const evEnd = kt.indexOf('\n    // ── DONNA-127', evStart);
    const handler = kt.slice(evStart, evEnd > -1 ? evEnd : evStart + 2000);
    expect(handler).not.toMatch(/requestAutoTap\(|findAndClickSendNode\(|performAction\(/);
  });

  // ── F1: GANZER Baum gescannt — kein versteckter Selbsttrigger-Aufrufer ──────
  it('F1: requestAutoTap wird NUR aus performActionTap und maybeAutoSend gerufen (alle .kt)', () => {
    // nur echte AUFRUFE zählen, die Definition `fun requestAutoTap(` ausnehmen
    const callRe = /(?<!fun\s)requestAutoTap\(/g;
    const offenders: string[] = [];
    for (const file of walk(KT_MAIN, ['.kt'])) {
      const src = read(file);
      const calls = (src.match(callRe) || []).length;
      if (calls === 0) continue;
      const base = path.basename(file);
      if (base === 'AccessibilityBridge.kt') {
        // L2: Aufruf muss innerhalb von performActionTap stehen — Ende per Klammerbalance.
        const body = bodyFrom(src, src.indexOf('fun performActionTap'));
        const inFn = (body.match(callRe) || []).length;
        if (inFn !== calls) offenders.push(`${base}: ${calls - inFn} Aufruf(e) außerhalb performActionTap`);
      } else if (base === 'VoiceInputActivity.kt') {
        const body = bodyFrom(src, src.indexOf('private fun maybeAutoSend'));
        const inFn = (body.match(callRe) || []).length;
        if (inFn !== calls) offenders.push(`${base}: ${calls - inFn} Aufruf(e) außerhalb maybeAutoSend`);
      } else {
        offenders.push(`${base}: ${calls} unerlaubte(r) requestAutoTap-Aufruf(e)`);
      }
    }
    expect(offenders).toEqual([]);
  });

  it('F1: performActionTap wird im JS NUR aus autoSend.ts gerufen (alle .ts/.tsx außer __tests__)', () => {
    const callRe = /performActionTap\(/g;
    const offenders: string[] = [];
    for (const file of walk(TS_SRC, ['.ts', '.tsx'], name => name === '__tests__')) {
      const base = path.basename(file);
      if (base === 'autoSend.ts') continue; // Interface-Definition + einziger Aufruf
      const calls = (read(file).match(callRe) || []).length;
      if (calls > 0) offenders.push(`${path.relative(TS_SRC, file)}: ${calls} performActionTap-Treffer`);
    }
    expect(offenders).toEqual([]);
  });

  it('F1/L1: triggerAutoSend wird NUR aus autoSend.ts (Def) und ChatScreen.tsx (Aufruf) referenziert', () => {
    const re = /triggerAutoSend\(/g;
    const allowed = new Set(['autoSend.ts', 'ChatScreen.tsx']);
    const offenders: string[] = [];
    for (const file of walk(TS_SRC, ['.ts', '.tsx'], name => name === '__tests__')) {
      if (allowed.has(path.basename(file))) continue;
      const hits = (read(file).match(re) || []).length;
      if (hits > 0) offenders.push(`${path.relative(TS_SRC, file)}: ${hits} triggerAutoSend-Treffer`);
    }
    expect(offenders).toEqual([]);
  });
});

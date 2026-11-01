import fs from 'fs';
import path from 'path';
import {Platform} from 'react-native';
import {
  PULSE_DARK,
  PULSE_LIGHT,
  getTheme,
  normalizeScheme,
  supportsDynamicColors,
} from '../theme';

// ─── Hilfen: Farben / Kontrast ────────────────────────────────────────────────
type Rgba = [number, number, number, number];

function parse(c: string): Rgba {
  const hex = /^#([0-9a-f]{6})$/i.exec(c);
  if (hex) {
    const n = parseInt(hex[1], 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255, 1];
  }
  const m = /^rgba\((\d+),(\d+),(\d+),([\d.]+)\)$/.exec(c);
  if (!m) {
    throw new Error(`Unbekanntes Farbformat: ${c}`);
  }
  return [Number(m[1]), Number(m[2]), Number(m[3]), Number(m[4])];
}

/** Legt eine (halb)transparente Farbe auf einen opaken Untergrund. */
function over(fg: string, bg: string): [number, number, number] {
  const [fr, fg_, fb, fa] = parse(fg);
  const [br, bg_, bb] = parse(bg);
  return [fr * fa + br * (1 - fa), fg_ * fa + bg_ * (1 - fa), fb * fa + bb * (1 - fa)];
}

function luminance([r, g, b]: [number, number, number]): number {
  const lin = (v: number) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
}

function contrast(fg: string, bg: string, under: string = bg): number {
  const base = over(bg, under); // Untergrund (z. B. getönte Fläche auf Karte) zuerst auflösen
  const f = over(fg, `#${base.map(v => Math.round(v).toString(16).padStart(2, '0')).join('')}`);
  const l1 = luminance(f);
  const l2 = luminance(base);
  return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
}

const MIN = 4.5; // WCAG AA, Normaltext

describe('Theme — Schema-Auswahl', () => {
  it('normalizeScheme: nur "light" ist hell, alles andere dunkel (wie bisher)', () => {
    expect(normalizeScheme('light')).toBe('light');
    expect(normalizeScheme('dark')).toBe('dark');
    expect(normalizeScheme(null)).toBe('dark');
    expect(normalizeScheme(undefined)).toBe('dark');
    expect(normalizeScheme('unspecified')).toBe('dark');
  });

  it('getTheme liefert die Pulse-Paletten, wenn keine Dynamic Colors verfuegbar sind', () => {
    const dark = getTheme('dark', false);
    const light = getTheme('light', false);
    expect(dark.scheme).toBe('dark');
    expect(dark.dynamic).toBe(false);
    expect(dark.bg).toBe(PULSE_DARK.bg);
    expect(light.scheme).toBe('light');
    expect(light.bg).toBe(PULSE_LIGHT.bg);
    expect(dark.bg).not.toBe(light.bg);
  });

  it('getTheme ist referenzstabil (kein unnoetiges Neu-Rendern)', () => {
    expect(getTheme('dark', false)).toBe(getTheme('dark', false));
    expect(getTheme('light', false)).not.toBe(getTheme('dark', false));
  });

  it('Lila Statusleisten-Farbe und iOS-Grau sind aus den Paletten verschwunden', () => {
    const all = JSON.stringify([PULSE_DARK, PULSE_LIGHT]).toLowerCase();
    expect(all).not.toContain('#6200ee');
    expect(all).not.toContain('#1c1c1e');
  });
});

describe('Theme — Dynamic Colors (Android 12+)', () => {
  const realOS = Platform.OS;
  const realVersion = Platform.Version;
  const setPlatform = (os: string, version: number | string) => {
    Object.defineProperty(Platform, 'OS', {value: os, configurable: true});
    Object.defineProperty(Platform, 'Version', {value: version, configurable: true});
  };
  afterEach(() => setPlatform(realOS, realVersion as number));

  it('API 31+ → Dynamic Colors, darunter und auf iOS → Pulse', () => {
    setPlatform('android', 34);
    expect(supportsDynamicColors()).toBe(true);
    setPlatform('android', 31);
    expect(supportsDynamicColors()).toBe(true);
    setPlatform('android', 30);
    expect(supportsDynamicColors()).toBe(false);
    setPlatform('ios', 17);
    expect(supportsDynamicColors()).toBe(false);
  });

  it('dynamisches Theme nutzt PlatformColor (Objekte), Fallback bleibt String', () => {
    setPlatform('android', 34);
    const dyn = getTheme('dark', true);
    expect(dyn.dynamic).toBe(true);
    expect(typeof dyn.accent).not.toBe('string');
    expect(typeof dyn.bg).not.toBe('string');
    // Tokens ohne Material-You-Pendant bleiben feste, geprueft lesbare Werte
    expect(dyn.ok).toBe(PULSE_DARK.ok);
    expect(dyn.scrim).toBe(PULSE_DARK.scrim);

    setPlatform('android', 30);
    const fallback = getTheme('dark', true); // erzwungenes true wird auf API < 31 ignoriert
    expect(fallback.dynamic).toBe(false);
    expect(fallback.accent).toBe(PULSE_DARK.accent);
  });

  it('dynamisches Hell-/Dunkel-Theme unterscheiden sich', () => {
    setPlatform('android', 34);
    expect(getTheme('light', true).bg).not.toEqual(getTheme('dark', true).bg);
  });
});

describe('Theme — Kontrast ≥ 4,5:1 (Pulse-Fallback, beide Modi)', () => {
  const sets: Array<[string, Readonly<Record<string, string>>]> = [
    ['dark', PULSE_DARK],
    ['light', PULSE_LIGHT],
  ];

  describe.each(sets)('%s', (_name, p) => {
    it('Haupttext auf allen Flaechen', () => {
      for (const surface of ['bg', 'surface', 'card', 'userBubble', 'donnaBubble', 'accentSoftStrong']) {
        expect(contrast(p.text, p[surface], p.card)).toBeGreaterThanOrEqual(MIN);
      }
    });

    it('Sekundaertext (muted) auf bg/surface/card/Bubbles', () => {
      for (const surface of ['bg', 'surface', 'card', 'donnaBubble', 'userBubble']) {
        expect(contrast(p.muted, p[surface], p.card)).toBeGreaterThanOrEqual(MIN);
      }
    });

    it('Akzent als Text/Link auf bg/surface/card', () => {
      for (const surface of ['bg', 'surface', 'card']) {
        expect(contrast(p.accent, p[surface])).toBeGreaterThanOrEqual(MIN);
      }
    });

    it('Akzent2 (Ueberschriften/Chip-Label) auf card und auf Chip-Flaeche', () => {
      expect(contrast(p.accent2, p.card)).toBeGreaterThanOrEqual(MIN);
      expect(contrast(p.accent2, p.accentSoftStrong, p.card)).toBeGreaterThanOrEqual(MIN);
    });

    it('Text auf Akzent-Flaeche (Buttons, Avatar)', () => {
      expect(contrast(p.onAccent, p.accent)).toBeGreaterThanOrEqual(MIN);
    });

    it('Status- und Ideen-Farben auf card', () => {
      expect(contrast(p.ok, p.card)).toBeGreaterThanOrEqual(MIN);
      expect(contrast(p.warning, p.card)).toBeGreaterThanOrEqual(MIN);
      expect(contrast(p.ideaAccent, p.card)).toBeGreaterThanOrEqual(MIN);
    });
  });
});

// ─── Parität RN-Palette ↔ Kotlin colors.xml ───────────────────────────────────
const RES = path.resolve(__dirname, '../../android/app/src/main/res');

function readColors(dir: string): Record<string, string> {
  const xml = fs.readFileSync(path.join(RES, dir, 'colors.xml'), 'utf8');
  const out: Record<string, string> = {};
  for (const m of xml.matchAll(/<color name="donna_([a-z0-9_]+)">([^<]+)<\/color>/g)) {
    out[m[1]] = m[2].trim();
  }
  return out;
}

/** rgba(r,g,b,a) → Android-#AARRGGBB, opake Hexwerte bleiben. */
function toAndroid(c: string): string {
  const [r, g, b, a] = parse(c);
  if (a === 1) {
    return c.toLowerCase();
  }
  const h = (v: number) => Math.round(v).toString(16).padStart(2, '0');
  return `#${h(a * 255)}${h(r)}${h(g)}${h(b)}`;
}

describe('Parität zu res/values*/colors.xml (VoiceInputActivity)', () => {
  const MAP: Record<string, string> = {
    bg: 'bg',
    surface: 'surface',
    card: 'card',
    border: 'border',
    accent: 'accent',
    accent2: 'accent2',
    text: 'text',
    muted: 'muted',
    onAccent: 'on_accent',
    ok: 'ok',
  };

  it.each([
    ['values', PULSE_LIGHT],
    ['values-night', PULSE_DARK],
  ] as const)('%s entspricht der Pulse-Palette', (dir, palette) => {
    const xml = readColors(dir);
    for (const [token, name] of Object.entries(MAP)) {
      expect({token, value: xml[name]}).toEqual({
        token,
        value: toAndroid((palette as Record<string, string>)[token]),
      });
    }
  });

  it.each(['values-v31', 'values-night-v31'])('%s: Dynamic-Colors-Ressourcen existieren fuer alle Tokens', dir => {
    const xml = readColors(dir);
    for (const name of [...Object.values(MAP), 'handle', 'mic_active']) {
      expect(xml[name]).toBeDefined();
    }
    for (const name of ['bg', 'surface', 'card', 'border', 'accent', 'accent2', 'text', 'muted', 'on_accent']) {
      expect(xml[name]).toMatch(/^@android:color\/system_(accent|neutral)[12]_\d+$/);
    }
  });

  it('alle Ressourcen-Sets definieren dieselben Farbnamen', () => {
    const names = (d: string) => Object.keys(readColors(d)).sort();
    const base = names('values');
    expect(names('values-night')).toEqual(base);
    expect(names('values-v31')).toEqual(base);
    expect(names('values-night-v31')).toEqual(base);
  });

  it('Overlay-Theme ist DayNight und Kotlin enthaelt keine festen Pulse-Hexwerte mehr', () => {
    const styles = fs.readFileSync(path.join(RES, 'values/styles.xml'), 'utf8');
    expect(styles).toMatch(/Theme\.DonnaVoiceOverlay"\s+parent="Theme\.AppCompat\.DayNight\.NoActionBar"/);
    const kt = fs.readFileSync(
      path.resolve(__dirname, '../../android/app/src/main/java/com/yourcompany/donna/VoiceInputActivity.kt'),
      'utf8',
    );
    expect(kt).not.toMatch(/Color\.parseColor\(/);
  });
});

describe('App-Root (UX-01: Lila-Streifen + Doppel-Inset)', () => {
  const app = fs.readFileSync(path.resolve(__dirname, '../App.tsx'), 'utf8');

  it('enthaelt keine lila Statusleisten-Farbe und keine festen Hintergrundfarben mehr', () => {
    expect(app).not.toMatch(/#6200ee/i);
    expect(app).not.toMatch(/#03090f/i);
    expect(app).not.toMatch(/#38bdf8/i);
  });

  it('setzt den Top-Inset nicht zusaetzlich per SafeAreaView (ChatScreen-Header macht das bereits)', () => {
    expect(app).not.toMatch(/<SafeAreaView/);
    expect(app).toMatch(/translucent/);
  });

  it('ChatScreen und OnboardingScreen laufen unter einer ErrorBoundary', () => {
    expect(app).toMatch(/<ErrorBoundary[^>]*>\s*<ChatScreen/);
    expect((app.match(/<ErrorBoundary[^>]*>\s*<OnboardingScreen/g) ?? []).length).toBe(2);
  });
});

import {
  weatherKind, weatherTint, gradientStops, glyphPalette, isNightHour, hourFromLabel,
  currentHourIndex, averagePrecip, type WeatherKind,
} from '../utils/weatherLook';
import {PULSE_DARK, PULSE_LIGHT} from '../theme';

const KINDS: WeatherKind[] = ['sun', 'partly', 'cloud', 'rain', 'storm', 'snow', 'fog', 'moon', 'partlyNight'];

// ─── Kontrast-Helfer (WCAG) ──────────────────────────────────────────────────
function hex(c: string): [number, number, number] {
  const m = c.replace('#', '');
  return [parseInt(m.slice(0, 2), 16), parseInt(m.slice(2, 4), 16), parseInt(m.slice(4, 6), 16)];
}
function lum([r, g, b]: [number, number, number]): number {
  const f = (v: number) => {
    const s = v / 255;
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
}
function over(base: [number, number, number], rgb: [number, number, number], a: number): [number, number, number] {
  return [0, 1, 2].map(i => base[i] * (1 - a) + rgb[i] * a) as [number, number, number];
}
function ratio(fg: [number, number, number], bg: [number, number, number]): number {
  const l1 = lum(fg);
  const l2 = lum(bg);
  return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
}

describe('weatherKind', () => {
  it('erkennt die Wetterart aus dem Zustandstext', () => {
    expect(weatherKind(null, 'Klar')).toBe('sun');
    expect(weatherKind(null, 'Überwiegend sonnig')).toBe('partly');
    expect(weatherKind(null, 'Teilweise bewölkt')).toBe('partly');
    expect(weatherKind(null, 'Bewölkt')).toBe('cloud');
    expect(weatherKind(null, 'Leichter Regen')).toBe('rain');
    expect(weatherKind(null, 'Regen und Gewitter')).toBe('storm');
    expect(weatherKind(null, 'Schneefall')).toBe('snow');
    expect(weatherKind(null, 'Nebel')).toBe('fog');
  });
  it('fällt auf das Emoji des Backends zurück (Stundenvorschau hat keinen Text)', () => {
    expect(weatherKind('☀️', undefined)).toBe('sun');
    expect(weatherKind('⛅', undefined)).toBe('partly');
    expect(weatherKind('🌧️', undefined)).toBe('rain');
    expect(weatherKind('⛈️', undefined)).toBe('storm');
    expect(weatherKind('❄️', undefined)).toBe('snow');
    expect(weatherKind('🌫️', undefined)).toBe('fog');
    expect(weatherKind('☁️', undefined)).toBe('cloud');
  });
  it('unbekannt → neutrale Wolke', () => {
    expect(weatherKind('?', 'irgendwas')).toBe('cloud');
    expect(weatherKind(undefined, undefined)).toBe('cloud');
  });
  it('nachts wird aus Sonne Mond', () => {
    expect(weatherKind(null, 'Klar', true)).toBe('moon');
    expect(weatherKind(null, 'Teilweise bewölkt', true)).toBe('partlyNight');
    expect(weatherKind(null, 'Regen', true)).toBe('rain');
  });
  it('Nachtzeit', () => {
    expect(isNightHour(2)).toBe(true);
    expect(isNightHour(12)).toBe(false);
    expect(isNightHour(21)).toBe(true);
    expect(isNightHour(6)).toBe(false);
  });
});

describe('Hintergrund-Verlauf', () => {
  it('läuft von getönt (oben) nach transparent (unten)', () => {
    const stops = gradientStops(weatherTint('sun', true), 12);
    expect(stops).toHaveLength(12);
    const alphas = stops.map(s => parseFloat(s.match(/,([\d.]+)\)$/)![1]));
    expect(alphas[0]).toBeGreaterThan(0.1);
    expect(alphas[alphas.length - 1]).toBe(0);
    for (let i = 1; i < alphas.length; i++) {expect(alphas[i]).toBeLessThanOrEqual(alphas[i - 1]);}
  });
  it('Nacht ist dunkler (stärker getönt, dunkleres Blau) als Tag', () => {
    const day = weatherTint('sun', true);
    const night = weatherTint('moon', true);
    expect(night.alpha).toBeGreaterThan(day.alpha);
    expect(lum(night.rgb)).toBeLessThan(lum(day.rgb));
  });
  it('Klar = kühles Blau, Bewölkt/Regen = Grau-Blau', () => {
    const [r, , b] = weatherTint('sun', false).rgb;
    expect(b).toBeGreaterThan(r + 100);
    const rain = weatherTint('rain', false).rgb;
    expect(Math.abs(rain[2] - rain[0])).toBeLessThan(100);
  });
});

describe('Lesbarkeit auf getönter Karte (beide Themes, stärkste Tönung = oberer Rand)', () => {
  it.each([
    ['dunkel', PULSE_DARK, true],
    ['hell', PULSE_LIGHT, false],
  ] as const)('%s: Text, Sekundärtext und Akzente bleiben ≥ 4,5:1', (_name, P, dark) => {
    for (const kind of KINDS) {
      const t = weatherTint(kind, dark);
      const bg = over(hex(P.card), t.rgb, t.alpha);
      expect({kind, k: 'text', v: ratio(hex(P.text), bg) >= 4.5}).toEqual({kind, k: 'text', v: true});
      expect({kind, k: 'muted', v: ratio(hex(P.muted), bg) >= 4.5}).toEqual({kind, k: 'muted', v: true});
      expect({kind, k: 'accent2', v: ratio(hex(P.accent2), bg) >= 4.5}).toEqual({kind, k: 'accent2', v: true});
    }
  });
});

describe('Icon-Farben', () => {
  it('liefert für hell und dunkel vollständige Paletten', () => {
    for (const dark of [true, false]) {
      const p = glyphPalette(dark);
      for (const key of ['sun', 'cloud', 'drop', 'snow', 'moon', 'bolt', 'line'] as const) {
        expect(p[key]).toMatch(/^#[0-9a-f]{6}$/i);
      }
    }
  });
});

describe('Stundenvorschau', () => {
  const hourly = [
    {time: '9 Uhr', temp_c: 10, icon: '☀️', precip_pct: 0},
    {time: '12 Uhr', temp_c: 14, icon: '⛅', precip_pct: 10},
    {time: '15 Uhr', temp_c: 16, icon: '🌧️', precip_pct: 60},
  ];
  it('Stunde aus Beschriftung', () => {
    expect(hourFromLabel('14 Uhr')).toBe(14);
    expect(hourFromLabel('9:00')).toBe(9);
    expect(hourFromLabel('Jetzt')).toBeNull();
    expect(hourFromLabel('30 Uhr')).toBeNull();
  });
  it('aktueller Slot = letzter Slot nicht nach jetzt', () => {
    expect(currentHourIndex(hourly, 13)).toBe(1);
    expect(currentHourIndex(hourly, 15)).toBe(2);
    expect(currentHourIndex(hourly, 3)).toBe(0);
    expect(currentHourIndex([], 12)).toBe(0);
  });
  it('mittlerer Niederschlag nur mit Stundenvorschau (nichts erfinden)', () => {
    expect(averagePrecip(hourly)).toBe(23);
    expect(averagePrecip([])).toBeNull();
  });
});

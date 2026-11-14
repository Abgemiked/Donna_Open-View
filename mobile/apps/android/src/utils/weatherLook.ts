/**
 * Optik-Logik der Wetterkarte (reine Funktionen, testbar): Wetterart, Hintergrund-Tönung, Icon-Farben,
 * aktuelle Stunde. KEINE Daten werden erfunden — alles leitet sich aus den vorhandenen WeatherCardData-Feldern
 * (condition, condition_icon) und der Stundenvorschau ab.
 */

export type WeatherKind =
  | 'sun' | 'partly' | 'cloud' | 'rain' | 'storm' | 'snow' | 'fog' | 'moon' | 'partlyNight';

/** Nacht: vor 6 und ab 21 Uhr (grobe Näherung, es gibt kein Sonnenaufgangs-Feld im Vertrag). */
export function isNightHour(hour: number): boolean {
  return hour < 6 || hour >= 21;
}

/**
 * Wetterart aus Zustandstext (bevorzugt) und Emoji des Backends. Nachts wird aus Sonne Mond.
 * Unbekanntes → Wolke (neutral).
 */
export function weatherKind(icon?: string | null, condition?: string | null, night = false): WeatherKind {
  const c = (condition ?? '').toLowerCase();
  const e = icon ?? '';
  let kind: WeatherKind | null = null;

  if (c) {
    if (/gewitter/.test(c)) {kind = 'storm';}
    else if (/schnee|graupel|hagel/.test(c)) {kind = 'snow';}
    else if (/regen|schauer|niesel/.test(c)) {kind = 'rain';}
    else if (/nebel|dunst|hochnebel/.test(c)) {kind = 'fog';}
    else if (/teil|heiter|leicht bewölkt|überwiegend sonnig|wechselhaft/.test(c)) {kind = 'partly';}
    else if (/bewölkt|bedeckt|wolken/.test(c)) {kind = 'cloud';}
    else if (/klar|sonnig|sonne/.test(c)) {kind = 'sun';}
  }
  if (!kind && e) {
    if (/[⛈🌩]/u.test(e)) {kind = 'storm';}
    else if (/[❄🌨☃⛄]/u.test(e)) {kind = 'snow';}
    else if (/[🌧🌦☔]/u.test(e)) {kind = 'rain';}
    else if (/🌫/u.test(e)) {kind = 'fog';}
    else if (/[⛅🌤🌥]/u.test(e)) {kind = 'partly';}
    else if (/☁/u.test(e)) {kind = 'cloud';}
    else if (/[☀🌞]/u.test(e)) {kind = 'sun';}
    else if (/[🌙🌕🌑🌛🌜]/u.test(e)) {kind = 'moon';}
  }
  const resolved: WeatherKind = kind ?? 'cloud';
  if (night) {
    if (resolved === 'sun') {return 'moon';}
    if (resolved === 'partly') {return 'partlyNight';}
  }
  return resolved;
}

export interface WeatherTint {
  /** RGB der Tönung */
  rgb: [number, number, number];
  /** Deckkraft am oberen Rand (läuft nach unten auf 0 aus) */
  alpha: number;
}

// Kühles Blau (klar), Grau-Blau (bewölkt/Regen), Indigo (Gewitter), Eisblau (Schnee), Nachtblau (Nacht).
const TINT_RGB: Record<WeatherKind, [number, number, number]> = {
  sun: [56, 150, 255],
  partly: [90, 150, 230],
  cloud: [120, 140, 165],
  rain: [80, 110, 170],
  storm: [95, 85, 150],
  snow: [150, 190, 230],
  fog: [130, 145, 160],
  moon: [30, 40, 110],
  partlyNight: [35, 50, 115],
};

// Deckkraft oben: dezent. Im Dunkelmodus etwas stärker (dunkle Fläche verträgt es), im Hellmodus schwächer,
// damit Text in beiden Themes klar lesbar bleibt (Kontrast wird in weatherLook.test.ts geprüft).
const TINT_ALPHA_DARK: Record<WeatherKind, number> = {
  sun: 0.28, partly: 0.26, cloud: 0.22, rain: 0.26, storm: 0.28, snow: 0.12, fog: 0.16, moon: 0.34, partlyNight: 0.32,
};
const TINT_ALPHA_LIGHT: Record<WeatherKind, number> = {
  sun: 0.16, partly: 0.15, cloud: 0.14, rain: 0.15, storm: 0.15, snow: 0.14, fog: 0.12, moon: 0.08, partlyNight: 0.08,
};

export function weatherTint(kind: WeatherKind, dark: boolean): WeatherTint {
  return {rgb: TINT_RGB[kind], alpha: (dark ? TINT_ALPHA_DARK : TINT_ALPHA_LIGHT)[kind]};
}

/** Streifen für den weichen Verlauf (oben stark → unten transparent), ohne Gradient-Bibliothek. */
export function gradientStops(tint: WeatherTint, steps = 20): string[] {
  const [r, g, b] = tint.rgb;
  const out: string[] = [];
  for (let i = 0; i < steps; i++) {
    const t = steps === 1 ? 0 : i / (steps - 1);
    // weicher Auslauf (leicht nach innen gekrümmt), unten exakt 0
    const a = tint.alpha * Math.pow(1 - t, 1.4);
    out.push(`rgba(${r},${g},${b},${a.toFixed(3)})`);
  }
  return out;
}

export interface GlyphPalette {
  sun: string; cloud: string; drop: string; snow: string; moon: string; bolt: string; line: string;
}

/** Icon-Farben (konsistenter Satz, hell/dunkel). */
export function glyphPalette(dark: boolean): GlyphPalette {
  return dark
    ? {sun: '#fbbf24', cloud: '#cbd5e1', drop: '#60a5fa', snow: '#e0f2fe', moon: '#e2e8f0', bolt: '#fbbf24', line: '#94a3b8'}
    : {sun: '#d98200', cloud: '#7b8fa6', drop: '#2563eb', snow: '#6f9bd1', moon: '#64748b', bolt: '#c26d00', line: '#64748b'};
}

/** "14 Uhr" / "14:00" → 14 (sonst null). */
export function hourFromLabel(label: string | undefined | null): number | null {
  const m = String(label ?? '').match(/^\s*(\d{1,2})/);
  if (!m) {return null;}
  const h = parseInt(m[1], 10);
  return h >= 0 && h <= 23 ? h : null;
}

/** Index des Stunden-Slots, der zur aktuellen Stunde gehört (letzter Slot mit Stunde <= jetzt, sonst 0). */
export function currentHourIndex(hourly: Array<{time: string}>, nowHour: number): number {
  return hourly.reduce((best, h, i) => {
    const hh = hourFromLabel(h.time);
    return hh !== null && hh <= nowHour ? i : best;
  }, 0);
}

/** Mittlere Niederschlagswahrscheinlichkeit der Stundenvorschau (gerundet); null ohne Stundenvorschau. */
export function averagePrecip(hourly: Array<{precip_pct: number}>): number | null {
  if (hourly.length === 0) {return null;}
  return Math.round(hourly.reduce((s, h) => s + (h.precip_pct || 0), 0) / hourly.length);
}

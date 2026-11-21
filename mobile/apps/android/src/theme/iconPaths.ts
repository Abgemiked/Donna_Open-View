/**
 * #134 P2: EINE Quelle der Wahrheit fuer den Icon-Satz (viewBox 0 0 24 24).
 * Strich-Icons (2 px, runde Enden/Ecken, keine Fuellung); Ausnahme: Namen in ICON_FILLED (gefuellt).
 * Kotlin-Vector-Drawables (Overlay) werden aus diesen Pfad-Strings erzeugt - nur reine Pfade, keine rect/circle.
 */
export const ICON_VIEWBOX = 24;

export const ICON_PATHS = {
  mic: ['M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3z', 'M5 11a7 7 0 0 0 14 0M12 18v3'],
  micOff: [
    'M4 4l16 16M9 9v3a3 3 0 0 0 5 2M15 10V6a3 3 0 0 0-5.5-1.6M5 11a7 7 0 0 0 11 5.7M19 11a7 7 0 0 1-.6 2.8M12 18v3',
  ],
  volume: ['M4 10v4h3l5 4V6L7 10H4z', 'M16 9a4 4 0 0 1 0 6M18.5 6.5a8 8 0 0 1 0 11'],
  volumeOff: ['M4 10v4h3l5 4V6L7 10H4z', 'M16 9.5l5 5M21 9.5l-5 5'],
  newChat: ['M12 5H6a2 2 0 0 0-2 2v11a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2v-6', 'M18 3l3 3-8 8-4 1 1-4 8-8z'],
  settings: [
    'M4 7h10M18 7h2M4 17h2M10 17h10',
    'M14 7a2 2 0 1 0 4 0 2 2 0 1 0-4 0z',
    'M6 17a2 2 0 1 0 4 0 2 2 0 1 0-4 0z',
  ],
  send: ['M21 3L10 14', 'M21 3l-7 18-4-7-7-4 18-7z'],
  keyboard: [
    'M5 6h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z',
    'M7 10h.01M11 10h.01M15 10h.01M8 14h8',
  ],
  close: ['M6 6l12 12M18 6L6 18'],
  back: ['M15 5l-7 7 7 7'],
  chevronRight: ['M9 5l7 7-7 7'],
  stop: ['M9 6h6a3 3 0 0 1 3 3v6a3 3 0 0 1-3 3H9a3 3 0 0 1-3-3V9a3 3 0 0 1 3-3z'],
  wave: ['M4 11v2M8 8v8M12 5v14M16 8v8M20 11v2'],
  calendar: [
    'M5 5h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z',
    'M3 10h18',
    'M8 3v4M16 3v4',
  ],
  check: ['M5 12.5l4.5 4.5L19 7.5'],
  alert: ['M12 4L3 19h18L12 4z', 'M12 10v4M12 17h.01'],
  copy: [
    'M11 9h7a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-7a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z',
    'M15 9V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h3',
  ],
  reply: ['M9 14L4 9l5-5', 'M4 9h10a6 6 0 0 1 6 6v4'],
  thumbUp: [
    'M7 11v9H4a1 1 0 0 1-1-1v-7a1 1 0 0 1 1-1h3z',
    'M7 11l4-7a2 2 0 0 1 2 2v4h5.5a2 2 0 0 1 2 2.3l-1 6A2 2 0 0 1 17.5 20H7',
  ],
  thumbDown: [
    'M7 13V4H4a1 1 0 0 0-1 1v7a1 1 0 0 0 1 1h3z',
    'M7 13l4 7a2 2 0 0 0 2-2v-4h5.5a2 2 0 0 0 2-2.3l-1-6A2 2 0 0 0 17.5 4H7',
  ],
  history: ['M3 12a9 9 0 1 0 3-6.7L3 8', 'M3 3v5h5', 'M12 7v5l3 2'],
  link: [
    'M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1',
    'M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1',
  ],
  idea: ['M9 18h6M10 21h4', 'M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2.1h5c0-.9.4-1.6 1-2.1A6 6 0 0 0 12 3z'],
  map: ['M9 4L3 6v14l6-2 6 2 6-2V4l-6 2-6-2z', 'M9 4v14M15 6v14'],
  trash: ['M4 7h16M10 11v6M14 11v6', 'M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12', 'M9 7V4h6v3'],
  plus: ['M12 5v14M5 12h14'],
  phone: ['M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2z'],
  message: ['M5 4h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H11l-5 4v-4H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z', 'M8 9h8M8 12.5h5'],
} as const;

export type IconName = keyof typeof ICON_PATHS;

export const ICON_NAMES = Object.keys(ICON_PATHS) as IconName[];

/** Gefuellte Icons (fill statt stroke); alle anderen sind reine Strich-Icons. */
export const ICON_FILLED: ReadonlySet<IconName> = new Set<IconName>(['stop']);

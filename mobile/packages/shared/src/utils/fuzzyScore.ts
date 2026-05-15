/**
 * Normalisiert einen String: Umlaute→ASCII, lowercase, trim.
 * Ä→a, Ö→o, Ü→u, ä→a, ö→o, ü→u, ß→ss, é/è/ê→e, à/â→a, etc.
 */
export function normalizeStr(s: string): string {
  if (!s) return '';

  // Mapping von Sonderzeichen zu ASCII-Äquivalenten
  const map: Record<string, string> = {
    'Ä': 'a',
    'Ö': 'o',
    'Ü': 'u',
    'ä': 'a',
    'ö': 'o',
    'ü': 'u',
    'ß': 'ss',
    'é': 'e',
    'è': 'e',
    'ê': 'e',
    'à': 'a',
    'â': 'a',
    'ç': 'c',
    'ñ': 'n',
    'í': 'i',
    'ì': 'i',
    'î': 'i',
    'ó': 'o',
    'ò': 'o',
    'ô': 'o',
    'ú': 'u',
    'ù': 'u',
    'û': 'u',
  };

  let result = '';
  for (const char of s) {
    result += map[char] || char;
  }

  return result.toLowerCase().trim();
}

/**
 * Damerau-Levenshtein-Distanz (mit Transpositionen).
 * Gibt Distanz als Integer zurück.
 */
export function editDistance(a: string, b: string): number {
  const maxLen = Math.max(a.length, b.length);

  // Größere Optimierungsgrenzen: nur Strings bis ~100 Zeichen bearbeiten
  if (maxLen > 100) {
    return maxLen; // Zu lang → maximale Distanz
  }

  // Zwei Reihen für Optimierung statt O(n*m) Speicher
  const h: number[] = [];
  const d: number[] = [];

  for (let i = 0; i <= a.length; i++) h[i] = i;

  for (let j = 1; j <= b.length; j++) {
    d[0] = j;

    for (let i = 1; i <= a.length; i++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;

      d[i] = Math.min(
        d[i - 1] + 1, // Insertion
        h[i] + 1, // Deletion
        h[i - 1] + cost // Substitution
      );

      // Transposition (Damerau): wenn a[i-2]==b[j-1] && a[i-1]==b[j-2]
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) {
        d[i] = Math.min(d[i], h[i - 2] + cost);
      }
    }

    // Reihen tauschen
    const tmp = h;
    h.splice(0, h.length, ...d);
    d.splice(0, d.length);
    for (let i = 0; i < h.length; i++) {
      d[i] = tmp[i];
    }
  }

  return h[a.length];
}

/**
 * Normalisierter Ähnlichkeits-Score 0.0–1.0.
 * 1.0 = identisch, 0.0 = maximale Distanz (> 3 → immer 0.0).
 * Vergleicht normalisierte Strings.
 */
export function fuzzyScore(a: string, b: string): number {
  const normA = normalizeStr(a);
  const normB = normalizeStr(b);

  // Identische Strings nach Normalisierung → 1.0
  if (normA === normB) {
    return 1.0;
  }

  // Leere Strings (nach Normalisierung)
  if (!normA && !normB) {
    return 1.0;
  }

  if (!normA || !normB) {
    return 0.0;
  }

  const dist = editDistance(normA, normB);

  // Distanz > 3 → immer 0.0
  if (dist > 3) {
    return 0.0;
  }

  // Normalisierung: max. mögliche Distanz = max(|a|, |b|)
  const maxDist = Math.max(normA.length, normB.length);
  const score = 1.0 - dist / maxDist;

  return Math.max(0.0, score);
}

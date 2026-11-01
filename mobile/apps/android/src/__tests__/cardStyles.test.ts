import {StyleSheet} from 'react-native';
import {getTheme} from '../theme';
import {INFO_CARD, makeCardStyles, makeInfoCardStyles} from '../screens/cardStyles';

describe('cardStyles — Info-Karten-System (B3)', () => {
  it.each(['light', 'dark'] as const)('Info-Karten nutzen nur Theme-Tokens (%s)', scheme => {
    const P = getTheme(scheme, false);
    const s = makeInfoCardStyles(P);
    const surface = StyleSheet.flatten(s.surface);
    expect(surface.backgroundColor).toBe(scheme === 'light' ? P.surface : P.card);
    expect(surface.borderColor).toBe(P.border);
    expect(StyleSheet.flatten(s.badge).backgroundColor).toBe(P.accentSoft);
    expect(StyleSheet.flatten(s.pill).backgroundColor).toBe(P.accentSoft);
    expect(StyleSheet.flatten(s.divider).backgroundColor).toBe(P.border);
    expect(StyleSheet.flatten(s.title).color).toBe(P.text);
    expect(StyleSheet.flatten(s.body).color).toBe(P.text);
    expect(StyleSheet.flatten(s.caption).color).toBe(P.muted);
    expect(StyleSheet.flatten(s.eyebrow).color).toBe(P.muted);
  });

  it('großzügiger Radius, weiche Elevation, Typo-Hierarchie absteigend', () => {
    const s = makeInfoCardStyles(getTheme('dark', false));
    const surface = StyleSheet.flatten(s.surface);
    expect(surface.borderRadius).toBe(INFO_CARD.radius);
    expect(INFO_CARD.radius).toBeGreaterThanOrEqual(24);
    expect(surface.elevation).toBe(INFO_CARD.elevation);
    expect(INFO_CARD.innerRadius).toBeLessThan(INFO_CARD.radius);
    const size = (k: 'title' | 'body' | 'caption') => StyleSheet.flatten(s[k]).fontSize as number;
    expect(size('title')).toBeGreaterThan(size('body'));
    expect(size('body')).toBeGreaterThan(size('caption'));
  });

  it('Touch-Elemente der Badges: mindestens 40dp Kreis', () => {
    const b = StyleSheet.flatten(makeInfoCardStyles(getTheme('light', false)).badge);
    expect(b.width).toBeGreaterThanOrEqual(40);
    expect(b.borderRadius).toBe((b.width as number) / 2);
  });

  // makeCardStyles VOLLSTÄNDIG pinnen (PendingActionCard / Nav-Karten): jede Änderung an Key-Menge oder Wert wird rot.
  const expectedCardStyles = (P: ReturnType<typeof getTheme>) => ({
    cardOuter: {
      backgroundColor: P.card,
      borderRadius: 16,
      borderWidth: 1,
      borderColor: P.border,
      marginHorizontal: 8,
      marginVertical: 6,
      overflow: 'hidden',
    },
    cardInner: {padding: 18, paddingBottom: 14},
    divider: {height: 1, backgroundColor: P.border, marginVertical: 12},
    fieldLabel: {color: P.muted, fontSize: 13, fontWeight: '400', marginBottom: 4},
    fieldValue: {color: P.text, fontSize: 22, fontWeight: '500', lineHeight: 28},
    warnText: {color: P.warning, fontSize: 12, marginTop: 12, fontStyle: 'italic'},
    btnRow: {
      flexDirection: 'row',
      backgroundColor: P.surface,
      borderTopWidth: 1,
      borderTopColor: P.border,
    },
    btn: {flex: 1, minHeight: 48, paddingVertical: 14, alignItems: 'center', justifyContent: 'center'},
    btnDisabled: {opacity: 0.4},
    btnText: {color: P.text, fontSize: 15, fontWeight: '400'},
    btnTextPrimary: {color: P.accent, fontSize: 15, fontWeight: '600'},
    btnDivider: {width: 1, backgroundColor: P.border},
  });

  it.each(['light', 'dark'] as const)('makeCardStyles (PendingActionCard / Nav-Karten) bleibt vollständig unverändert (%s)', scheme => {
    const P = getTheme(scheme, false);
    const c = makeCardStyles(P);
    const expected = expectedCardStyles(P);
    // Key-Menge exakt
    expect(Object.keys(c).sort()).toEqual(Object.keys(expected).sort());
    // jeder Eintrag exakt (flatten -> toEqual)
    for (const key of Object.keys(expected) as (keyof typeof expected)[]) {
      expect({key, style: StyleSheet.flatten(c[key])}).toEqual({key, style: expected[key]});
    }
  });
});

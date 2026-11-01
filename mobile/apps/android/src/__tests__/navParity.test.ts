/**
 * Parität Haupt-Chat ↔ Overlay: dieselben Fixtures wie NavParityTest.kt
 * (android/app/src/test/resources/nav_parity.json). Gleiche Action → gleiches Navigationsziel.
 */
import fs from 'fs';
import path from 'path';
import {getNavTarget} from '../utils/navActions';

jest.mock('../native/TeslaNav', () => ({
  sendToTesla: jest.fn(),
  TeslaNavError: class TeslaNavError extends Error {},
}));

const ROOT = path.resolve(__dirname, '../../android/app/src');
const fixtures = JSON.parse(
  fs.readFileSync(path.join(ROOT, 'test/resources/nav_parity.json'), 'utf8'),
) as {cases: Array<{name: string; action: Record<string, unknown>; expected: string}>};

describe('Navi-Parität Haupt-Chat ↔ Overlay', () => {
  it('Fixtures vorhanden', () => {
    expect(fixtures.cases.length).toBeGreaterThanOrEqual(8);
  });

  it.each(fixtures.cases.map(c => [c.name, c.action, c.expected] as const))(
    'Haupt-Chat: %s',
    (_name, action, expected) => {
      expect(getNavTarget(action as never)).toBe(expected);
    },
  );

  it('das Overlay nutzt dieselbe Prioritätsfolge im Quelltext (nav_address > resolved_address > destination > query)', () => {
    const logic = fs.readFileSync(path.join(ROOT, 'main/java/com/yourcompany/donna/NavActionLogic.kt'), 'utf8');
    const order = ['"nav_address"', '"resolved_address"', '"destination"', '"query"'].map(k => logic.indexOf(`JsonSafe.text(action, ${k})`));
    expect(order.every(i => i >= 0)).toBe(true);
    expect([...order].sort((a, b) => a - b)).toEqual(order);
    const activity = fs.readFileSync(path.join(ROOT, 'main/java/com/yourcompany/donna/VoiceInputActivity.kt'), 'utf8');
    expect(activity).toMatch(/private fun teslaTargetOf\(action: JSONObject\): String = NavActionLogic\.targetOfAction\(action\)/);
    // und der Chat nutzt dieselbe Funktion für Anzeige UND Senden
    const chat = fs.readFileSync(path.resolve(__dirname, '../screens/ChatScreen.tsx'), 'utf8');
    expect(chat).toMatch(/sendTargetToTesla\(getNavTarget\(action\)\)/);
  });
});

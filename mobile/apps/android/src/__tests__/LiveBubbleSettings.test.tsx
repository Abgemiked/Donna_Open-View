import React from 'react';
import {render, fireEvent, act, waitFor} from '@testing-library/react-native';

/**
 * A2 — Live-Bubble-Schalter in der Einrichtung. Native Bridge gemockt; prüft An→show, Aus→hide,
 * und den Overlay-Recht-Hinweis.
 */
const mockShow = jest.fn();
const mockHide = jest.fn();
const mockCanDraw = jest.fn();
const mockIsRunning = jest.fn();
jest.mock('../native/liveBubble', () => ({
  isLiveBubbleAvailable: () => true,
  canDrawOverlay: (...a: unknown[]) => mockCanDraw(...a),
  showLiveBubble: (...a: unknown[]) => mockShow(...a),
  hideLiveBubble: (...a: unknown[]) => mockHide(...a),
  isLiveBubbleRunning: (...a: unknown[]) => mockIsRunning(...a),
}));

import LiveBubbleSettings from '../components/LiveBubbleSettings';

describe('LiveBubbleSettings (A2)', () => {
  beforeEach(() => {
    mockShow.mockReset();
    mockHide.mockReset();
    mockCanDraw.mockReset();
    mockIsRunning.mockReset();
    mockIsRunning.mockResolvedValue(false);
    mockCanDraw.mockResolvedValue(true);
    mockShow.mockResolvedValue(true);
  });

  it('An startet die Bubble (showLiveBubble) und zeigt sie als an', async () => {
    const {getByTestId} = render(<LiveBubbleSettings />);
    await act(async () => {});
    expect(getByTestId('live-bubble-aus').props.accessibilityState.selected).toBe(true);
    await act(async () => {
      fireEvent.press(getByTestId('live-bubble-an'));
    });
    expect(mockShow).toHaveBeenCalled();
    await waitFor(() =>
      expect(getByTestId('live-bubble-an').props.accessibilityState.selected).toBe(true),
    );
  });

  it('Aus stoppt die Bubble (hideLiveBubble)', async () => {
    mockIsRunning.mockResolvedValue(true);
    const {getByTestId} = render(<LiveBubbleSettings />);
    await waitFor(() =>
      expect(getByTestId('live-bubble-an').props.accessibilityState.selected).toBe(true),
    );
    await act(async () => {
      fireEvent.press(getByTestId('live-bubble-aus'));
    });
    expect(mockHide).toHaveBeenCalled();
    expect(getByTestId('live-bubble-aus').props.accessibilityState.selected).toBe(true);
  });

  it('Ohne Overlay-Recht: show liefert false → Hinweis sichtbar, bleibt aus', async () => {
    mockShow.mockResolvedValue(false);
    mockCanDraw.mockResolvedValue(false);
    const {getByTestId} = render(<LiveBubbleSettings />);
    await act(async () => {});
    await act(async () => {
      fireEvent.press(getByTestId('live-bubble-an'));
    });
    await waitFor(() => expect(getByTestId('live-bubble-overlay-hint')).toBeTruthy());
    expect(getByTestId('live-bubble-aus').props.accessibilityState.selected).toBe(true);
  });
});

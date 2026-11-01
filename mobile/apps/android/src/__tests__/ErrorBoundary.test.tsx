import React from 'react';
import {Text} from 'react-native';
import {render, fireEvent, act} from '@testing-library/react-native';
import {ErrorBoundary, ERROR_BOUNDARY_RETRY, ERROR_BOUNDARY_TITLE} from '../components/ErrorBoundary';

let shouldThrow = true;
function Bomb(): React.JSX.Element {
  if (shouldThrow) {
    throw new Error('Objects are not valid as a React child');
  }
  return <Text>alles gut</Text>;
}

describe('ErrorBoundary', () => {
  beforeEach(() => {
    shouldThrow = true;
    jest.spyOn(console, 'error').mockImplementation(() => {});
  });
  afterEach(() => jest.restoreAllMocks());

  it('faengt einen Render-Fehler und zeigt "Etwas lief schief" + "Neu laden"', () => {
    const {getByText, queryByText, getByTestId} = render(
      <ErrorBoundary name="Test">
        <Bomb />
      </ErrorBoundary>,
    );
    expect(getByTestId('error-boundary-fallback')).toBeTruthy();
    expect(getByText(ERROR_BOUNDARY_TITLE)).toBeTruthy();
    expect(getByText(ERROR_BOUNDARY_RETRY)).toBeTruthy();
    expect(queryByText('alles gut')).toBeNull();
  });

  it('"Neu laden" mountet die Kinder neu', async () => {
    const {getByText, queryByTestId} = render(
      <ErrorBoundary>
        <Bomb />
      </ErrorBoundary>,
    );
    shouldThrow = false;
    await act(async () => {
      fireEvent.press(getByText(ERROR_BOUNDARY_RETRY));
    });
    expect(getByText('alles gut')).toBeTruthy();
    expect(queryByTestId('error-boundary-fallback')).toBeNull();
  });

  it('bleibt der Fehler bestehen, erscheint der Fallback erneut (kein Absturz)', async () => {
    const {getByText, getByTestId} = render(
      <ErrorBoundary>
        <Bomb />
      </ErrorBoundary>,
    );
    await act(async () => {
      fireEvent.press(getByText(ERROR_BOUNDARY_RETRY));
    });
    expect(getByTestId('error-boundary-fallback')).toBeTruthy();
  });

  it('rendert gesunde Kinder unveraendert', () => {
    shouldThrow = false;
    const {getByText} = render(
      <ErrorBoundary>
        <Bomb />
      </ErrorBoundary>,
    );
    expect(getByText('alles gut')).toBeTruthy();
  });
});

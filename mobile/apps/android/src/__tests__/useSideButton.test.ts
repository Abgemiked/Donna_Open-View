import {renderHook} from '@testing-library/react-hooks';
import {useSideButton} from '../hooks/useSideButton';
import * as NativeSideButtonModuleImport from '../native/NativeSideButtonModule';

// Mock das native Modul
jest.mock('../native/NativeSideButtonModule', () => ({
  NativeSideButtonModule: {
    isAvailable: jest.fn().mockReturnValue(true),
    onPress: jest.fn().mockReturnValue({remove: jest.fn()}),
    onDoublePress: jest.fn().mockReturnValue({remove: jest.fn()}),
  },
}));

const mockModule = NativeSideButtonModuleImport.NativeSideButtonModule as jest.Mocked<
  typeof NativeSideButtonModuleImport.NativeSideButtonModule
>;

beforeEach(() => {
  jest.clearAllMocks();
  mockModule.isAvailable.mockReturnValue(true);
  mockModule.onPress.mockReturnValue({remove: jest.fn()});
  mockModule.onDoublePress.mockReturnValue({remove: jest.fn()});
});

describe('useSideButton', () => {
  it('registers press and doublePress listeners on mount', () => {
    const onPress = jest.fn();
    const onDoublePress = jest.fn();

    renderHook(() => useSideButton({onPress, onDoublePress}));

    expect(mockModule.onPress).toHaveBeenCalledTimes(1);
    expect(mockModule.onDoublePress).toHaveBeenCalledTimes(1);
  });

  it('removes listeners on unmount', () => {
    const removeMock = jest.fn();
    mockModule.onPress.mockReturnValue({remove: removeMock});
    mockModule.onDoublePress.mockReturnValue({remove: removeMock});

    const {unmount} = renderHook(() =>
      useSideButton({onPress: jest.fn(), onDoublePress: jest.fn()}),
    );

    unmount();
    expect(removeMock).toHaveBeenCalledTimes(2);
  });

  it('does not register listeners when enabled=false', () => {
    renderHook(() =>
      useSideButton({onPress: jest.fn(), enabled: false}),
    );
    expect(mockModule.onPress).not.toHaveBeenCalled();
  });

  it('does not register listeners when native module unavailable', () => {
    mockModule.isAvailable.mockReturnValue(false);

    renderHook(() => useSideButton({onPress: jest.fn()}));
    expect(mockModule.onPress).not.toHaveBeenCalled();
  });

  it('returns isAvailable=true when module present', () => {
    const {result} = renderHook(() => useSideButton({}));
    expect(result.current.isAvailable).toBe(true);
  });

  it('returns isAvailable=false when module absent', () => {
    mockModule.isAvailable.mockReturnValue(false);
    const {result} = renderHook(() => useSideButton({}));
    expect(result.current.isAvailable).toBe(false);
  });
});

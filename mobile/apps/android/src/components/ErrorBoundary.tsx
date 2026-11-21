/**
 * ErrorBoundary — ein Render-Fehler in einem Screen darf die App nicht beenden.
 * Zeigt statt des abgestürzten Teilbaums „Etwas lief schief“ + „Neu laden“ (mountet die Kinder neu).
 */
import React from 'react';
import {StyleSheet, Text, TouchableOpacity, View} from 'react-native';
import {useThemedStyles} from '../theme';
import type {Theme} from '../theme';

export const ERROR_BOUNDARY_TITLE = 'Etwas lief schief';
export const ERROR_BOUNDARY_RETRY = 'Neu laden';

interface Props {
  children: React.ReactNode;
  /** Kurzname für das Log (z. B. "ChatScreen") */
  name?: string;
}

interface State {
  hasError: boolean;
  /** Wird bei „Neu laden“ erhöht → Kinder werden frisch gemountet */
  attempt: number;
}

function Fallback({onRetry}: {onRetry: () => void}): React.JSX.Element {
  const styles = useThemedStyles(makeStyles);
  return (
    <View style={styles.root} testID="error-boundary-fallback" accessibilityRole="alert">
      <Text style={styles.title}>{ERROR_BOUNDARY_TITLE}</Text>
      <TouchableOpacity
        style={styles.button}
        onPress={onRetry}
        accessibilityRole="button"
        accessibilityLabel={ERROR_BOUNDARY_RETRY}>
        <Text style={styles.buttonText}>{ERROR_BOUNDARY_RETRY}</Text>
      </TouchableOpacity>
    </View>
  );
}

export class ErrorBoundary extends React.Component<Props, State> {
  state: State = {hasError: false, attempt: 0};

  static getDerivedStateFromError(): Partial<State> {
    return {hasError: true};
  }

  componentDidCatch(error: unknown): void {
    console.error(`[ErrorBoundary:${this.props.name ?? 'app'}] Render-Fehler:`, error);
  }

  private retry = () => this.setState(s => ({hasError: false, attempt: s.attempt + 1}));

  render(): React.ReactNode {
    if (this.state.hasError) {
      return <Fallback onRetry={this.retry} />;
    }
    return <React.Fragment key={this.state.attempt}>{this.props.children}</React.Fragment>;
  }
}

const makeStyles = (P: Theme) =>
  StyleSheet.create({
    root: {flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24, backgroundColor: P.bg},
    title: {color: P.text, fontSize: 18, fontWeight: '600', marginBottom: 16, textAlign: 'center'},
    button: {
      minHeight: 48,
      minWidth: 48,
      paddingHorizontal: 24,
      borderRadius: 24,
      backgroundColor: P.accent,
      alignItems: 'center',
      justifyContent: 'center',
    },
    buttonText: {color: P.onAccent, fontSize: 15, fontWeight: '600'},
  });

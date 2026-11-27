/**
 * ErrorBoundary — ein Render-Fehler in einem Screen darf die App nicht beenden.
 * Zeigt statt des abgestürzten Teilbaums „Etwas lief schief“ + „Neu laden“ (mountet die Kinder neu).
 */
import React from 'react';
import {StyleSheet, Text, TouchableOpacity, View} from 'react-native';
import {FONT, FONT_FAMILY, RADIUS, SPACE, TOUCH_MIN, useTheme, useThemedStyles} from '../theme';
import type {Theme} from '../theme';
import {Icon} from './Icon';

export const ERROR_BOUNDARY_TITLE = 'Etwas lief schief';
export const ERROR_BOUNDARY_RETRY = 'Neu laden';
export const ERROR_BOUNDARY_HINT = 'Diese Ansicht konnte nicht angezeigt werden.';

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
  const c = useTheme();
  return (
    <View style={styles.root} testID="error-boundary-fallback" accessibilityRole="alert">
      <Icon name="alert" size={40} color={c.danger as string} />
      <Text style={styles.title}>{ERROR_BOUNDARY_TITLE}</Text>
      <Text style={styles.hint}>{ERROR_BOUNDARY_HINT}</Text>
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
    root: {flex: 1, alignItems: 'center', justifyContent: 'center', gap: SPACE.md, padding: SPACE.xl, backgroundColor: P.bg},
    title: {color: P.heading, fontFamily: FONT_FAMILY.display.bold, fontSize: FONT.title, textAlign: 'center'},
    hint: {color: P.muted, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.body, textAlign: 'center', marginBottom: SPACE.md},
    button: {
      minHeight: TOUCH_MIN,
      minWidth: TOUCH_MIN,
      paddingHorizontal: SPACE.xl,
      borderRadius: RADIUS.pill,
      backgroundColor: P.action,
      alignItems: 'center',
      justifyContent: 'center',
    },
    buttonText: {color: P.onAction, fontFamily: FONT_FAMILY.sans.bold, fontSize: FONT.body},
  });

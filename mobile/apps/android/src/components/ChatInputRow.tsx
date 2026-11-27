import React, {useEffect, useRef} from 'react';
import {StyleSheet, Text, TextInput, TouchableOpacity, View} from 'react-native';
import {FONT, FONT_FAMILY, RADIUS, SPACE, TOUCH_MIN, useTheme, useThemedStyles} from '../theme';
import type {Theme} from '../theme';
import {Icon} from './Icon';
import {MicActionButton} from './chat/MicActionButton';
import {primaryAction, showStopButton} from './chat/chatView';

export interface ChatInputRowProps {
  micAvailable: boolean;
  isListening: boolean;
  /** Donna spricht/antwortet: "Stopp" erscheint, die Mikrotaste unterbricht. */
  canInterrupt: boolean;
  onVoicePress: () => void;
  /** "Stopp": Wiedergabe und Stream beenden. */
  onStop: () => void;
  value: string;
  onChangeText: (t: string) => void;
  onSend: () => void;
  /** Fokus im Textfeld (der Bildschirm beendet dann ein laufendes Zuhoeren). */
  onFocus: () => void;
  /** Unterer Systemrand in dp; 0, wenn die Tastatur offen ist (sie deckt ihn ab). */
  bottomInset: number;
  /** Zaehler: jede Erhoehung (auch nach dem Mounten) fokussiert das Feld und oeffnet die Tastatur (nach "Tippen"). */
  focusSignal?: number;
  /** Die Leiste mountet gerade WEGEN "Tippen" (Leerzustand): einmal beim Mounten fokussieren. */
  autoFocus?: boolean;
}

/**
 * Untere Leiste des Chats (Entwurf B, schwebend, Rundung 28): Eingabefeld, "Stopp" nur solange Donna spricht,
 * Mint-Mikrofon bzw. Senden-Symbol, sobald Text im Feld steht. Nur Darstellung, Props rein, Callbacks raus.
 */
export function ChatInputRow(p: ChatInputRowProps): React.JSX.Element {
  const styles = useThemedStyles(makeStyles);
  const P = useTheme();
  // ohne Mikrofon bleibt das Senden-Symbol stehen (dann gesperrt, solange nichts getippt ist)
  const action = p.micAvailable ? primaryAction(p.value, p.isListening) : 'send';
  const sendDisabled = !p.value.trim() || p.isListening;
  const inputRef = useRef<TextInput>(null);
  // Fokus NUR bei einer Aenderung des Signals gegenueber dem letzten behandelten Wert (Start = Wert beim Mounten),
  // sonst wuerde ein spaeteres Mounten (z. B. Mikrofon nach frueherem "Tippen") die Tastatur stehlen.
  const handledSignal = useRef(p.focusSignal ?? 0);
  useEffect(() => {
    const sig = p.focusSignal ?? 0;
    if (sig !== handledSignal.current) {
      handledSignal.current = sig;
      inputRef.current?.focus();
    }
  }, [p.focusSignal]);
  useEffect(() => {
    if (p.autoFocus) {
      inputRef.current?.focus();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return (
    <View style={[styles.wrap, {paddingBottom: SPACE.sm + p.bottomInset}]}>
      <View style={styles.bar}>
        <TextInput
          style={styles.input}
          value={p.value}
          onChangeText={p.onChangeText}
          placeholder={p.isListening ? 'Zuhören…' : 'Nachricht an Donna'}
          placeholderTextColor={P.muted}
          returnKeyType="send"
          onSubmitEditing={p.onSend}
          onFocus={p.onFocus}
          ref={inputRef}
          selectionColor={P.accent}
        />
        {showStopButton(p.canInterrupt, p.isListening) ? (
          <TouchableOpacity
            style={styles.stopBtn}
            onPress={p.onStop}
            accessibilityRole="button"
            accessibilityLabel="Donna unterbrechen">
            <Icon name="stop" size={16} color={P.text as string} />
            <Text style={styles.stopText}>Stopp</Text>
          </TouchableOpacity>
        ) : null}
        {action === 'mic' ? (
          <MicActionButton active={p.isListening} interrupting={p.canInterrupt} onPress={p.onVoicePress} size={TOUCH_MIN} />
        ) : (
          <TouchableOpacity
            style={[styles.sendBtn, sendDisabled && styles.sendBtnDisabled]}
            onPress={p.onSend}
            disabled={sendDisabled}
            accessibilityRole="button"
            accessibilityLabel="Nachricht senden"
            accessibilityState={{disabled: sendDisabled}}>
            <Icon name="send" size={22} color={(sendDisabled ? P.disabledText : P.onAction) as string} />
          </TouchableOpacity>
        )}
      </View>
    </View>
  );
}

const makeStyles = (P: Theme) => StyleSheet.create({
  wrap: {paddingTop: SPACE.sm, paddingHorizontal: SPACE.lg},
  bar: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACE.sm,
    padding: SPACE.sm,
    borderRadius: RADIUS.card,
    backgroundColor: P.card,
    borderWidth: 1,
    borderColor: P.border,
  },
  input: {
    flex: 1,
    minHeight: TOUCH_MIN,
    paddingHorizontal: SPACE.md,
    fontFamily: FONT_FAMILY.sans.regular,
    fontSize: FONT.body,
    maxHeight: 120,
    color: P.text,
  },
  stopBtn: {
    minHeight: TOUCH_MIN,
    paddingHorizontal: SPACE.lg,
    borderWidth: 1,
    borderColor: P.controlBorder,
    borderRadius: TOUCH_MIN / 2,
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACE.sm,
  },
  stopText: {fontFamily: FONT_FAMILY.sans.bold, fontSize: FONT.body, color: P.text},
  sendBtn: {
    width: TOUCH_MIN,
    height: TOUCH_MIN,
    borderRadius: TOUCH_MIN / 2,
    backgroundColor: P.action,
    justifyContent: 'center',
    alignItems: 'center',
  },
  sendBtnDisabled: {backgroundColor: P.disabled},
});

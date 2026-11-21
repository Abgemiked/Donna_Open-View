import React from 'react';
import {View, Text, StyleSheet, Linking} from 'react-native';
import {useThemedStyles} from '../../theme';
import type {Theme} from '../../theme';

// ─── MarkdownText — rendert **bold**, • bullets, URLs klickbar ───────────────
export function MarkdownText({text, style: baseStyle}: {text: string; style?: object}): React.JSX.Element {
  const mdSt = useThemedStyles(makeMdStyles);
  const lines = text.split('\n');
  return (
    <View>
      {lines.map((line, li) => {
        if (!line.trim()) {return <View key={li} style={{height: 5}} />;}
        // Header ## / ###
        if (/^#{1,3} /.test(line)) {
          const content = line.replace(/^#{1,3} /, '');
          return <Text key={li} style={[mdSt.header, baseStyle]}>{content}</Text>;
        }
        // Bullet
        const isBullet = /^[\s]*[*•-] /.test(line);
        const content = isBullet ? '• ' + line.replace(/^[\s]*[*•-] /, '') : line;
        // Inline: **bold** + URLs
        const parts = content.split(/(\*\*[^*]+\*\*|https?:\/\/[^\s]+)/g);
        return (
          <Text key={li} style={[mdSt.base, isBullet && mdSt.bullet, baseStyle]}>
            {parts.map((part, pi) => {
              if (/^\*\*[^*]+\*\*$/.test(part)) {
                return <Text key={pi} style={mdSt.bold}>{part.slice(2, -2)}</Text>;
              }
              if (/^https?:\/\//.test(part)) {
                return (
                  <Text key={pi} style={mdSt.link} onPress={() => Linking.openURL(part)}>
                    {part}
                  </Text>
                );
              }
              return <Text key={pi}>{part.replace(/\*/g, '')}</Text>;
            })}
          </Text>
        );
      })}
    </View>
  );
}
const makeMdStyles = (P: Theme) => StyleSheet.create({
  base: {fontSize: 15, lineHeight: 22, color: P.text, marginBottom: 1},
  bold: {fontWeight: '700', color: P.text},
  bullet: {marginBottom: 3},
  header: {fontSize: 16, fontWeight: '700', color: P.accent2, marginTop: 6, marginBottom: 3, lineHeight: 22},
  link: {color: P.accent, textDecorationLine: 'underline'},
});


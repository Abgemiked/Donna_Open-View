import React from 'react';
import {View, Text, StyleSheet, Linking} from 'react-native';
import {useThemedStyles, FONT, FONT_FAMILY, SPACE} from '../../theme';
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
  base: {fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.body, lineHeight: 22, color: P.text, marginBottom: SPACE.xs},
  bold: {fontFamily: FONT_FAMILY.sans.bold, color: P.text},
  bullet: {marginBottom: SPACE.xs},
  header: {fontFamily: FONT_FAMILY.sans.bold, fontSize: FONT.body, color: P.heading, marginTop: SPACE.sm, marginBottom: SPACE.xs, lineHeight: 22},
  link: {color: P.accent, textDecorationLine: 'underline'},
});


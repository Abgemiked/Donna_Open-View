/**
 * #219: Vorschau des eben aufgenommenen Screenshots im Chat. Tipp öffnet GENAU dieses Bild in der Galerie
 * (native ACTION_VIEW auf die MediaStore-URI). Zeigt nur lokal an; nichts wird hochgeladen.
 */
import React, {useMemo, useState} from 'react';
import {Image, StyleSheet, Text, TouchableOpacity, View} from 'react-native';
import {FONT, FONT_FAMILY, SPACE, useTheme} from '../theme';
import type {Theme} from '../theme';
import {isMediaImageUri, openScreenshot} from '../native/deviceActions';

interface Props {
  uri: string;
  onDismiss: () => void;
}

export default function ScreenshotPreviewCard({uri, onDismiss}: Props): React.JSX.Element | null {
  const c = useTheme();
  const styles = useMemo(() => makeStyles(c), [c]);
  const [failed, setFailed] = useState(false);
  if (!isMediaImageUri(uri)) {
    return null;
  }
  return (
    <View style={styles.card} testID="screenshot-preview">
      <TouchableOpacity
        accessibilityRole="button"
        accessibilityLabel="Screenshot-Vorschau, antippen öffnet das Bild in der Galerie"
        testID="screenshot-preview-open"
        onPress={() => {
          void openScreenshot(uri).then(ok => setFailed(!ok));
        }}>
        <Image source={{uri}} style={styles.image} resizeMode="contain" />
      </TouchableOpacity>
      <View style={styles.row}>
        <Text style={styles.text} accessibilityLiveRegion="polite">{failed ? 'Die Galerie ließ sich nicht öffnen.' : 'Screenshot gespeichert — antippen zum Öffnen'}</Text>
        <TouchableOpacity accessibilityRole="button" accessibilityLabel="Vorschau schließen" testID="screenshot-preview-dismiss" onPress={onDismiss} style={styles.close}>
          <Text style={styles.text}>Schließen</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

function makeStyles(c: Theme) {
  return StyleSheet.create({
    card: {marginHorizontal: SPACE.sm, marginVertical: SPACE.xs, padding: SPACE.xs},
    image: {width: '100%', height: 180, borderRadius: 8},
    row: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingTop: SPACE.xs},
    text: {flex: 1, color: c.muted, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.label},
    close: {minHeight: 44, justifyContent: 'center', paddingHorizontal: SPACE.sm},
  });
}

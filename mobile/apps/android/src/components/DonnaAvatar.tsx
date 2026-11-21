import React from 'react';
import {StyleSheet, View} from 'react-native';
import {useTheme, useThemedStyles} from '../theme';
import {Icon} from './Icon';
import {OrbWreath} from './orb/OrbWreath';
import {ORB_LIGHT_RINGS} from './orb/orbColors';
import {orbAppearance, orbCoreColors} from './orb/orbAppearance';

/** Anteil des Kerns an der Kantenlaenge (Dunkel gross, Hell klein mit hellem Ring) und des Icons. */
const CORE_DARK = 0.74;
const CORE_LIGHT = 0.52;
const ICON = 0.34;

/**
 * Donna-Avatar (#134 P3): kleiner STATISCHER Orb fuer Kopfzeilen und Nachrichtenzeilen (Farbkranz, Kern, `wave`),
 * kein Buchstabe mehr. Keine Animation (viele Instanzen in der Liste).
 */
export function DonnaAvatar({size = 32}: {size?: number}): React.JSX.Element {
  const P = useTheme();
  const styles = useThemedStyles(makeStyles);
  const app = orbAppearance('idle', false);
  const colors = orbCoreColors(P, 'idle');
  const core = size * (P.scheme === 'dark' ? CORE_DARK : CORE_LIGHT);
  const ring = size * 0.74;
  return (
    <View style={[styles.wrap, {width: size, height: size}]} testID="donna-avatar">
      <View style={styles.fill}>
        <OrbWreath size={size} opacity={app.wreathOpacity} />
      </View>
      {colors.rings ? (
        <View style={[styles.center, {width: ring, height: ring, borderRadius: ring / 2, backgroundColor: ORB_LIGHT_RINGS[1]}]} />
      ) : null}
      <View
        style={[styles.center, {width: core, height: core, borderRadius: core / 2, backgroundColor: colors.fill, borderColor: colors.border, borderWidth: 1}]}>
        <Icon name={app.icon} size={Math.max(8, Math.round(size * ICON))} color={colors.icon} strokeWidth={2.4} />
      </View>
    </View>
  );
}

const makeStyles = () => StyleSheet.create({
  wrap: {justifyContent: 'center', alignItems: 'center'},
  fill: {position: 'absolute', top: 0, left: 0, right: 0, bottom: 0},
  center: {position: 'absolute', justifyContent: 'center', alignItems: 'center'},
});

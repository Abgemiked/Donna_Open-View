/**
 * #134 P2: Gemeinsamer Icon-Satz (react-native-svg). Pfade aus theme/iconPaths.ts (eine Quelle der Wahrheit).
 * Icons sind Dekor: die umgebende Taste traegt das Label.
 */
import React from 'react';
import Svg, {Path} from 'react-native-svg';
import {ICON_FILLED, ICON_PATHS, ICON_VIEWBOX} from '../theme/iconPaths';
import type {IconName} from '../theme/iconPaths';

export type {IconName};

export interface IconProps {
  name: IconName;
  size?: number;
  color: string;
  strokeWidth?: number;
}

export function Icon({name, size = 24, color, strokeWidth = 2}: IconProps): React.JSX.Element | null {
  const paths = ICON_PATHS[name] as readonly string[] | undefined;
  if (!paths) {
    return null; // unbekannter Name zur Laufzeit (z. B. Tippfehler aus any/JS): nichts rendern statt abstuerzen
  }
  const filled = ICON_FILLED.has(name);
  return (
    <Svg
      width={size}
      height={size}
      viewBox={`0 0 ${ICON_VIEWBOX} ${ICON_VIEWBOX}`}
      accessibilityElementsHidden
      importantForAccessibility="no"
      testID={`icon-${name}`}>
      {paths.map((d, i) =>
        filled ? (
          <Path key={i} d={d} fill={color} />
        ) : (
          <Path
            key={i}
            d={d}
            fill="none"
            stroke={color}
            strokeWidth={strokeWidth}
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        ),
      )}
    </Svg>
  );
}

export default Icon;

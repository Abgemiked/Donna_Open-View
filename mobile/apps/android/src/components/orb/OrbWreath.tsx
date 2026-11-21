/**
 * Weicher Farbkranz des Orbs (#134 P3): fuenf versetzte Kreise mit Radialverlauf (Farbe -> auslaufende Deckkraft),
 * ueberlagert zum Kranz. Bewusst KEIN Weichzeichner-Filter (in react-native-svg nicht verlaesslich). Rein statisch;
 * die Drehung macht der Aufrufer ueber eine Animated.View.
 */
import React, {memo, useRef} from 'react';
import Svg, {Circle, Defs, RadialGradient, Stop} from 'react-native-svg';
import {ORB_WREATH_COLORS} from './orbColors';

let nextId = 0;

/** Mittelpunktabstand und Radius der Kranzkreise in der 100er-Zeichenflaeche (aussen = 50, Rand des Orbs). */
const DIST = 27;
const RADIUS = 23;

function OrbWreathImpl({size, opacity = 1}: {size: number; opacity?: number}): React.JSX.Element {
  const id = useRef(`orbw${nextId++}`).current;
  const n = ORB_WREATH_COLORS.length;
  return (
    <Svg width={size} height={size} viewBox="0 0 100 100" testID="orb-wreath" opacity={opacity}>
      <Defs>
        {ORB_WREATH_COLORS.map((c, i) => (
          <RadialGradient key={i} id={`${id}-${i}`} cx="50%" cy="50%" r="50%">
            <Stop offset="0" stopColor={c} stopOpacity={1} />
            <Stop offset="0.55" stopColor={c} stopOpacity={0.7} />
            <Stop offset="1" stopColor={c} stopOpacity={0} />
          </RadialGradient>
        ))}
      </Defs>
      {ORB_WREATH_COLORS.map((c, i) => {
        const a = (2 * Math.PI * i) / n - Math.PI / 2;
        return (
          <Circle
            key={c}
            cx={50 + DIST * Math.cos(a)}
            cy={50 + DIST * Math.sin(a)}
            r={RADIUS}
            fill={`url(#${id}-${i})`}
          />
        );
      })}
    </Svg>
  );
}

/** Gleiche Groesse und Deckkraft -> kein Neuzeichnen (die Drehung laeuft ausserhalb ueber eine Animated.View). */
export const OrbWreath = memo(OrbWreathImpl);

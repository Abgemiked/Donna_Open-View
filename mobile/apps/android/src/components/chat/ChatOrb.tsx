import React from 'react';
import {DonnaStateOrb} from '../DonnaStateOrb';
import type {DonnaStateOrbProps} from '../DonnaStateOrb';

/**
 * Orb der Chat-Ansichten. `active={false}` (gemountet, aber verdeckt/unsichtbar): keine Drehung, kein Puls,
 * der Orb bleibt gemountet (DONNA-38).
 */
export function ChatOrb(props: DonnaStateOrbProps): React.JSX.Element {
  return <DonnaStateOrb {...props} />;
}

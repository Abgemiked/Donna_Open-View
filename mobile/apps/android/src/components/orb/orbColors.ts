/**
 * Farben des Orbs (#134 P3) an EINER Stelle. Die Kotlin-Seite (`DonnaOrbView`) nutzt dieselben Werte; bei einer
 * Aenderung hier muss dort nachgezogen werden (Entwurf: Farbkranz aus conic-gradient, Reihenfolge wie unten).
 */

/** Die fuenf Kranzfarben in Umlaufreihenfolge: Violett, Magenta, Gelb, Blau, Indigo. */
export const ORB_WREATH_COLORS = ['#7a3bd1', '#de3e96', '#ffd60a', '#608ce6', '#5b49d6'] as const;

/** Helle Ringe hinter dem Lavendel-Kern (Farbwelt Hell), von aussen nach innen. */
export const ORB_LIGHT_RINGS = ['#eeebff', '#e3deff'] as const;

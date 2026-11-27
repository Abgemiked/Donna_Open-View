package com.yourcompany.donna

/**
 * Reine Regeln fuer den Rand und die schwebende Blase (#133). Ohne Android-Typen, damit JVM-getestet
 * (BubbleLogicTest); LiveBubbleService/RainbowEdgeView setzen sie nur um.
 */

/**
 * Groesse des leuchtenden Rands. Er liegt im Fenster-Rahmen NEBEN dem Panel und darf die Fenstergroesse nie
 * bestimmen: Das Fenster ist WRAP_CONTENT hoch, der Rahmen gibt dem Rand dann nur eine „hoechstens“-Vorgabe
 * (= ganze Bildschirmhoehe). Eine einfache View nimmt die und zoege das Fenster bildschirmhoch. Darum: bei
 * „hoechstens“ nichts beanspruchen; erst die feste Vorgabe des Rahmens (= gemessene Panelgroesse, 2. Durchgang
 * fuer MATCH_PARENT-Kinder) gibt dem Rand seine Groesse.
 */
object EdgeBounds {
    fun measured(exact: Boolean, specSize: Int): Int = if (exact) specSize.coerceAtLeast(0) else 0
}

/**
 * „Schwebende Blase“: Standard AUS (nur das Panel erscheint). Liegt als Boolean in [TtsEngines.PREFS]
 * (donna_overlay_prefs); der Dienst liest den Wert bei jedem Start frisch. Der Schluessel steht NUR hier.
 */
object FloatingBubbleSetting {
    const val PREF_KEY = "floating_bubble"
    const val DEFAULT = false

    fun read(prefs: android.content.SharedPreferences): Boolean =
        try { prefs.getBoolean(PREF_KEY, DEFAULT) } catch (_: Exception) { DEFAULT }

    fun write(prefs: android.content.SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(PREF_KEY, enabled).apply()
    }
}

object BubbleVisibility {
    fun showFloatingBubble(setting: Boolean): Boolean = setting

    /**
     * Es muss immer etwas Sichtbares geben: Panel oder Blase. Fehlen beide (addView-Fehler, Overlay-Recht
     * entzogen), laeuft sonst eine unsichtbare Sitzung mit Mikrofon weiter -> Dienst beenden.
     */
    fun sessionVisible(panelShown: Boolean, bubbleShown: Boolean): Boolean = panelShown || bubbleShown

    /**
     * Ohne Blase fehlt der Tipp-Weg „zurueck ins Zuhoeren“ (Blasen-Tap): dann uebernimmt ihn die Statuszeile im
     * Panel. Im Tippmodus nie (Donna soll waehrend des Schreibens nicht lauschen).
     */
    fun statusTapResumes(bubbleShown: Boolean, typing: Boolean): Boolean = !bubbleShown && !typing

    // #134 P7: Statustexte enthalten keine Anleitung mehr (kein "Tippe die Blase an"), also gibt es nichts mehr
    // umzuschreiben (die fruehere Blasen-Phrase und ihr Umschreiben sind entfallen).
}

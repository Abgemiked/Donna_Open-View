package com.yourcompany.donna.widget

/**
 * #243: welche Darstellung zu welcher Widget-Größe passt (reine Logik, JVM-getestet). Größen in dp, wie sie der
 * Launcher meldet (Android 12+: je Größe aus `OPTION_APPWIDGET_SIZES`, sonst min. Breite / max. Höhe).
 * Klein = kompakt (eine Zeile), größer = mehr Inhalt bzw. Knöpfe.
 */
object WidgetSizing {
    /** Unter dieser Höhe zeigen Listen-Widgets nur eine Zeile (nächster Eintrag) statt Kopf + Liste. */
    const val LIST_MIN_HEIGHT = WidgetFormat.HEADER_DP + 2 * WidgetFormat.ROW_DP

    /** Höchstzahl an Listenzeilen (auch bei sehr hohen Widgets). */
    const val MAX_LIST_ROWS = 8

    /**
     * #252: Ideen-Widget = immer zwei runde 48-dp-Symboltasten (Tastatur, Mikro) rechts; links je nach Breite der Kopf.
     * Platzbedarf Tasten + Innenabstand: 2 × 48 + 8 + 2 × 10 = 124 dp, Glühbirne + Abstand 32 dp.
     */
    const val IDEA_BULB_MIN_WIDTH = 160
    /** Ab dieser Breite passt „Idee“ neben die Glühbirne. */
    const val IDEA_SHORT_TITLE_MIN_WIDTH = 200
    /** Ab dieser Breite passt der volle Titel „Idee festhalten“. */
    const val IDEA_FULL_TITLE_MIN_WIDTH = 280

    /** Kopf links neben den Tasten: nichts, nur Glühbirne, Glühbirne + „Idee“, Glühbirne + „Idee festhalten“. */
    enum class IdeaHeader { NONE, BULB, SHORT_TITLE, FULL_TITLE }

    /** 0 = unbekannte Höhe → Liste (bisheriges Verhalten). */
    fun isCompactList(heightDp: Int): Boolean = heightDp in 1 until LIST_MIN_HEIGHT

    fun listRows(heightDp: Int): Int = WidgetFormat.rowsForHeight(heightDp, MAX_LIST_ROWS)

    /** Höhe spielt keine Rolle: die Zeile wird vertikal zentriert, die Tasten wachsen nie mit. 0 = unbekannt → nur Tasten. */
    fun ideaHeader(widthDp: Int): IdeaHeader = when {
        widthDp < IDEA_BULB_MIN_WIDTH -> IdeaHeader.NONE
        widthDp < IDEA_SHORT_TITLE_MIN_WIDTH -> IdeaHeader.BULB
        widthDp < IDEA_FULL_TITLE_MIN_WIDTH -> IdeaHeader.SHORT_TITLE
        else -> IdeaHeader.FULL_TITLE
    }
}

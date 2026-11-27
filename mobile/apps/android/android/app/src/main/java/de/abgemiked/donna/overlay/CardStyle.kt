package com.yourcompany.donna.overlay

/**
 * Gemeinsamer Stil aller Karten im Overlay (#151, Teil von #134): reine Beschreibung OHNE Android-Typen, JVM-getestet
 * (CardStyleTest). Gebaut wird sie von [CardKit]; Ziel ist EIN Aussehen fuer Bestaetigungs-, Auswahl-, Hinweis-,
 * Wetter-, Karten- und Geraete-Ergebniskarte sowie das Erinnerungs-Popup.
 *
 * Gegenstueck der App ist `src/screens/cardStyles.ts` (Rundung `card`, Innenabstand `gutter`, EINE gefuellte
 * Hauptaktion in Mint `action`, zweite Aktion als Umriss, Schrift Nunito Sans). Radien/Abstaende stehen auch in
 * res/values/dimens.xml, die Rollen unten benennen Farbressourcen, die in values/ UND values-night/ vorhanden sind
 * (Hell/Dunkel entscheidet allein der Darstellungs-Kontext nach [com.yourcompany.donna.ThemeChoice]); CardStyleTest
 * vergleicht beides.
 */
object CardStyle {
    /** Kleinste Tippflaeche (dp), wie PanelLayoutRules.TOUCH_MIN_DP. */
    const val TOUCH_MIN_DP = 48

    /** Radien (dp) und ihre Ressource in dimens.xml. Karten und Tasten tragen dieselbe Rundung wie das Panel. */
    enum class Radius(val dp: Int, val dimenName: String) {
        SM(8, "donna_radius_sm"),
        MD(14, "donna_radius_md"),
        LG(22, "donna_radius_lg"),
        PILL(28, "donna_radius_pill"),
        CARD(28, "donna_radius_card"),
    }

    /** Rundung der Karte / der Tasten. */
    val CARD_RADIUS = Radius.CARD
    val BUTTON_RADIUS = Radius.PILL

    // Abstaende (dp): Karte wie in der App (gutter 20 seitlich/oben), unten etwas knapper (Overlay hat wenig Platz).
    const val PAD_H_DP = 20
    const val PAD_TOP_DP = 20
    const val PAD_BOTTOM_DP = 16
    /** Abstand zwischen zwei Karten. */
    const val CARD_GAP_DP = 8
    /** Abstand zwischen Text und Tastenreihe. */
    const val TEXT_TO_BUTTONS_DP = 16
    /** Abstand zwischen zwei Tasten (neben- und untereinander). */
    const val BUTTON_GAP_DP = 8
    /** Innenabstand einer Taste seitlich; vertikal entsteht er durch die Mindesthoehe (Umbruch erweitert die Taste). */
    const val BUTTON_PAD_H_DP = 20
    const val BUTTON_PAD_V_DP = 12
    /** Mindestbreite einer Taste in der Reihe. */
    const val BUTTON_MIN_WIDTH_DP = 96
    /** Haarlinie zwischen Bereichen. */
    const val DIVIDER_PX = 1

    /** Schriftgroessen (sp) und Schnitt (Name = DonnaFonts.Face.rnName, Nunito Sans bzw. Bricolage Grotesque). */
    enum class Type(val sp: Int, val face: String) {
        /** Haupttext der Karte (Titel der Aktion). */
        TITLE(15, "nunito_sans_semibold"),
        /** Fliesstext. */
        BODY(15, "nunito_sans_regular"),
        /** Beschriftung / Nebentext. */
        CAPTION(13, "nunito_sans_regular"),
        /** Kleine Zeile ueber dem Haupttext (Ort, Kontext). */
        EYEBROW(13, "nunito_sans_semibold"),
        /** Beschriftung der Tasten. */
        BUTTON(15, "nunito_sans_bold"),
        /** Grosse Zahl der Wetterkarte (Bricolage Grotesque, wie FONT_LARGE.temp der App). */
        DISPLAY(56, "bricolage_grotesque_bold"),
    }

    /**
     * Farbrollen. [resName] = Name der Farbressource (values/ hell, values-night/ dunkel). Keine Hex-Werte im Quelltext:
     * die Paletten sind die einzige Quelle.
     */
    enum class Role(val resName: String) {
        /** Flaeche der Karte im Panel: Tasten-Grund des Overlays (hell #eeecf7, dunkel #24102f). */
        FILL("donna_overlay_chip"),
        BORDER("donna_border"),
        TEXT("donna_text"),
        MUTED("donna_muted"),
        /** Symbole und Links auf der Karte. */
        ICON("donna_accent"),
        /** Hauptaktion: Mint, Schrift darauf dunkel (gleich in beiden Farbwelten, wie `action`/`onAction` der App). */
        PRIMARY_FILL("donna_action"),
        PRIMARY_TEXT("donna_on_action"),
        /** Zweite Aktion: Umriss (Rand der Bedienelemente) mit normaler Schrift. */
        SECONDARY_BORDER("donna_control_border"),
        SECONDARY_TEXT("donna_text"),
    }

    /** Ein Rollenpaar Schrift auf Grund, das lesbar sein muss (>= [MIN_CONTRAST] in beiden Farbwelten). */
    val READABLE_PAIRS: List<Pair<Role, Role>> = listOf(
        Role.TEXT to Role.FILL,
        Role.MUTED to Role.FILL,
        Role.ICON to Role.FILL,
        Role.PRIMARY_TEXT to Role.PRIMARY_FILL,
        Role.SECONDARY_TEXT to Role.FILL,
    )
    const val MIN_CONTRAST = 4.5
    /** Rand der zweiten Aktion ist ein Bedienelement: als Grafik >= 3:1 auf der Flaeche. */
    const val MIN_CONTROL_CONTRAST = 3.0

    /** Eine Taste der Karte: Haupt oder Neben. */
    enum class ButtonRole(val fill: Role?, val border: Role?, val text: Role) {
        PRIMARY(Role.PRIMARY_FILL, null, Role.PRIMARY_TEXT),
        SECONDARY(null, Role.SECONDARY_BORDER, Role.SECONDARY_TEXT),
    }

    /** Tastenrolle: genau die eine Hauptaktion ist gefuellt, jede weitere ist Umriss. */
    fun buttonRole(primary: Boolean): ButtonRole = if (primary) ButtonRole.PRIMARY else ButtonRole.SECONDARY

    /** Tippflaeche einer Taste: nie unter [TOUCH_MIN_DP]. */
    fun buttonMinHeightDp(): Int = TOUCH_MIN_DP

    /** Jede Taste nimmt in der Reihe gleich viel Platz (Gewicht), damit lange Beschriftungen umbrechen statt zu ueberlaufen. */
    const val BUTTON_ROW_WEIGHT = 1f
}

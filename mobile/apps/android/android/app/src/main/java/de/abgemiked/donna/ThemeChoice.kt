package com.yourcompany.donna

/**
 * Darstellung Hell / Dunkel / System (#134 P1). Reine Regeln, JVM-getestet (ThemeChoiceTest).
 *
 * Die Wahl liegt als String in [TtsEngines.PREFS] (donna_overlay_prefs) unter [PREF_KEY]; der Schluessel steht NUR hier.
 * Die App liest/schreibt sie ueber PiperTTSModule.getThemeMode/setThemeMode (EINE Quelle der Wahrheit, TS-Gegenstueck:
 * src/theme/themeChoice.ts).
 *
 * Das Overlay (LiveBubbleService) und die Sperrbildschirm-Activity (VoiceInputActivity) wenden die Wahl ueber
 * [nightOverride] an.
 */
object ThemeChoice {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    const val PREF_KEY = "theme_mode"
    const val DEFAULT = SYSTEM

    /** Unbekannt/fehlend -> "system". */
    fun normalize(raw: String?): String = when (raw) {
        LIGHT, DARK, SYSTEM -> raw
        else -> DEFAULT
    }

    /** Eine feste Wahl gewinnt, "system" (und alles Unbekannte) folgt dem System. */
    fun isDark(choice: String?, systemIsDark: Boolean): Boolean = when (normalize(choice)) {
        DARK -> true
        LIGHT -> false
        else -> systemIsDark
    }

    /**
     * true/false = Tag-/Nachtmodus fuer Overlay und Sperrbildschirm-Activity ueberschreiben, null = dem System folgen
     * (Kontext/Delegate bleiben unveraendert).
     */
    fun nightOverride(choice: String?): Boolean? = when (normalize(choice)) {
        DARK -> true
        LIGHT -> false
        else -> null
    }

    /** Konfigurations-uiMode mit gesetztem Nachtfeld (Configuration.UI_MODE_NIGHT_MASK 0x30, NO 0x10, YES 0x20); andere Bits bleiben. */
    fun uiModeWithNight(uiMode: Int, night: Boolean): Int =
        (uiMode and 0x30.inv()) or (if (night) 0x20 else 0x10)

    fun read(prefs: android.content.SharedPreferences): String =
        try { normalize(prefs.getString(PREF_KEY, DEFAULT)) } catch (_: Exception) { DEFAULT }

    fun write(prefs: android.content.SharedPreferences, choice: String) {
        prefs.edit().putString(PREF_KEY, normalize(choice)).apply()
    }
}

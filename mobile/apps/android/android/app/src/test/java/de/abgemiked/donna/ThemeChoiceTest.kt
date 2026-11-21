package com.yourcompany.donna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #134 P1: Auswahl Hell/Dunkel/System (reine Regeln, ohne Android-Typen). Das Overlay wendet sie erst in P7 an. */
class ThemeChoiceTest {
    @Test
    fun `Standard ist system`() {
        assertEquals("system", ThemeChoice.DEFAULT)
        assertEquals("theme_mode", ThemeChoice.PREF_KEY)
    }

    @Test
    fun `normalize akzeptiert nur die drei Werte, sonst system`() {
        assertEquals("light", ThemeChoice.normalize("light"))
        assertEquals("dark", ThemeChoice.normalize("dark"))
        assertEquals("system", ThemeChoice.normalize("system"))
        assertEquals("system", ThemeChoice.normalize(null))
        assertEquals("system", ThemeChoice.normalize(""))
        assertEquals("system", ThemeChoice.normalize("DARK"))
        assertEquals("system", ThemeChoice.normalize("auto"))
    }

    @Test
    fun `feste Wahl schlaegt das System, system folgt ihm`() {
        assertTrue(ThemeChoice.isDark("dark", systemIsDark = false))
        assertFalse(ThemeChoice.isDark("light", systemIsDark = true))
        assertTrue(ThemeChoice.isDark("system", systemIsDark = true))
        assertFalse(ThemeChoice.isDark("system", systemIsDark = false))
        assertTrue(ThemeChoice.isDark(null, systemIsDark = true))
        assertFalse(ThemeChoice.isDark("kaputt", systemIsDark = false))
    }
}

/** #134 P7: Die Wahl bestimmt, ob der Dienst/die Activity den Tag-/Nachtmodus ueberschreibt. */
class ThemeChoiceOverlayTest {
    @Test
    fun `Ueberschreiben nur bei fester Wahl, system laesst das System entscheiden`() {
        assertEquals(true, ThemeChoice.nightOverride("dark"))
        assertEquals(false, ThemeChoice.nightOverride("light"))
        assertEquals(null, ThemeChoice.nightOverride("system"))
        assertEquals(null, ThemeChoice.nightOverride(null))
        assertEquals(null, ThemeChoice.nightOverride("kaputt"))
    }

    @Test
    fun `uiMode behaelt alle anderen Bits und setzt nur das Nachtfeld`() {
        val other = 0x04 or 0x0F // Typ-Bits irgendwo
        val night = ThemeChoice.uiModeWithNight(other or 0x10, night = true)
        assertEquals(other or 0x20, night)
        val day = ThemeChoice.uiModeWithNight(other or 0x20, night = false)
        assertEquals(other or 0x10, day)
        assertEquals(0x20, ThemeChoice.uiModeWithNight(0, night = true))
    }
}

package com.yourcompany.donna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-Unit-Tests für die reine Logik der Tesla-Navigation (TeslaNav, NavActionLogic).
 * Der eigentliche Intent-Versand (startActivity) ist nur auf dem Gerät prüfbar.
 */
class TeslaNavTest {

    // ── Ziel-Konstanten (per ADB verifiziert) ────────────────────────────────

    @Test
    fun `Ziel ist die ShareActivity der Tesla-App`() {
        assertEquals("com.teslamotors.tesla", TeslaNav.TESLA_PACKAGE)
        assertEquals("com.tesla.share.ShareActivity", TeslaNav.TESLA_SHARE_ACTIVITY)
    }

    // ── normalizeText ────────────────────────────────────────────────────────

    @Test
    fun `leeres oder nur aus Leerraum bestehendes Ziel wird nicht gesendet`() {
        assertNull(TeslaNav.normalizeText(null))
        assertNull(TeslaNav.normalizeText(""))
        assertNull(TeslaNav.normalizeText("   \n\t  "))
    }

    @Test
    fun `Adresse wird getrimmt und Leerraum zusammengefasst`() {
        assertEquals("Marienplatz 1, München", TeslaNav.normalizeText("  Marienplatz   1,\nMünchen \t"))
    }

    @Test
    fun `Steuerzeichen werden zu Leerzeichen`() {
        assertEquals("A B", TeslaNav.normalizeText("A\u0000\u0007B"))
    }

    @Test
    fun `sehr lange Ziele werden gekuerzt`() {
        val long = "x".repeat(TeslaNav.MAX_TEXT_LENGTH + 200)
        val out = TeslaNav.normalizeText(long)
        assertEquals(TeslaNav.MAX_TEXT_LENGTH, out?.length)
    }

    // ── NavActionLogic ───────────────────────────────────────────────────────

    @Test
    fun `Tesla nur bei ausdruecklichem tesla_navigate`() {
        assertTrue(NavActionLogic.isTesla("tesla_navigate", null))
        assertTrue(NavActionLogic.isTesla("navigate_disambiguate", "tesla_navigate"))
        assertTrue(NavActionLogic.isTesla("navigate_not_found", "tesla_navigate"))
        assertFalse(NavActionLogic.isTesla("navigate", null))
        assertFalse(NavActionLogic.isTesla("navigate_disambiguate", "navigate"))
        assertFalse("altes Backend ohne nav_type = Handy", NavActionLogic.isTesla("navigate_disambiguate", null))
        assertFalse(NavActionLogic.isTesla(null, null))
    }

    @Test
    fun `Sendeziel bevorzugt die volle Adresse vor der kurzen`() {
        val full = "Marienplatz 1, Altstadt-Lehel, 80331 München, Bayern, Deutschland"
        assertEquals(full, NavActionLogic.targetOf(full, "Marienplatz 1, 80331 München", "Marienplatz", null))
        assertEquals("Marienplatz 1, 80331 München", NavActionLogic.targetOf(null, "Marienplatz 1, 80331 München", "Marienplatz", null))
        assertEquals("Marienplatz", NavActionLogic.targetOf("", "", "Marienplatz", null))
        assertEquals("Marienplatz", NavActionLogic.targetOf(null, null, "  Marienplatz ", "x"))
        assertEquals("Anfrage", NavActionLogic.targetOf(null, null, null, "Anfrage"))
        assertEquals("", NavActionLogic.targetOf(null, null, " ", ""))
    }

    @Test
    fun `Treffer sendet volle Adresse, Label nur als Fallback`() {
        assertEquals("Hauptstraße 1, 10115 Berlin, Deutschland", NavActionLogic.optionTarget("Hauptstraße 1, 10115 Berlin, Deutschland", "Hauptstraße 1, 10115"))
        assertEquals("Hauptstraße 1, 10115", NavActionLogic.optionTarget("  ", "Hauptstraße 1, 10115"))
        assertEquals("Hauptstraße 1, 10115", NavActionLogic.optionTarget(null, "Hauptstraße 1, 10115"))
        assertEquals("", NavActionLogic.optionTarget(null, null))
    }

    @Test
    fun `Zero-Width- und Bidi-Zeichen werden entfernt`() {
        assertEquals("Marien", TeslaNav.normalizeText("Mar\u200Bi\u202Een"))
        assertEquals("Haupt Str", TeslaNav.normalizeText("Haupt\u200F \u2066Str"))
        assertNull("nur unsichtbare Zeichen = leer", TeslaNav.normalizeText("\u200B\u202E\u2060"))
    }

    @Test
    fun `Nicht-gefunden-Meldung ist freundlich und nennt die Suche`() {
        val handy = NavActionLogic.notFoundMessage("Xyz", tesla = false)
        assertTrue(handy.contains("„Xyz“"))
        assertFalse(handy.contains("Tesla"))
        val tesla = NavActionLogic.notFoundMessage("Xyz", tesla = true)
        assertTrue(tesla.contains("Tesla"))
        assertTrue(NavActionLogic.notFoundMessage(null, tesla = false).contains("nicht gefunden"))
    }
}

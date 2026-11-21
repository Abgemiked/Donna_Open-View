package com.yourcompany.donna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-Unit-Tests für die reine Logik des Ersteinrichtungs-Assistenten (SetupSteps).
 * Android-Framework-Aufrufe (Status-Prüfung, Intents) sind in DonnaSetupModule und nur
 * auf dem Gerät prüfbar.
 */
class SetupStepsTest {

    private val pkg = "com.yourcompany.donna"
    private val a11y = "com.yourcompany.donna.DonnaAccessibilityService"

    // ── Schritt-Katalog ──────────────────────────────────────────────────────

    @Test
    fun `alle Schluessel sind eindeutig und Pflicht ist Teilmenge`() {
        assertEquals(SetupSteps.ALL_KEYS.size, SetupSteps.ALL_KEYS.toSet().size)
        assertTrue(SetupSteps.ALL_KEYS.containsAll(SetupSteps.REQUIRED_KEYS))
        assertEquals(17, SetupSteps.ALL_KEYS.size)
        assertEquals(5, SetupSteps.REQUIRED_KEYS.size)
        assertFalse("exact_alarm ist nur empfohlen", SetupSteps.EXACT_ALARM in SetupSteps.REQUIRED_KEYS)
    }

    @Test
    fun `jeder Schluessel ist Runtime-Permission oder Spezialzugriff - nie beides`() {
        val special = setOf(
            SetupSteps.OVERLAY, SetupSteps.ASSISTANT, SetupSteps.BATTERY, SetupSteps.EXACT_ALARM,
            SetupSteps.ACCESSIBILITY, SetupSteps.NOTIF_LISTENER, SetupSteps.USAGE_STATS, SetupSteps.HEALTH,
        )
        for (key in SetupSteps.ALL_KEYS) {
            val runtime = SetupSteps.runtimePermissions(key, 35)
            if (key in special) assertNull("$key sollte Spezialzugriff sein", runtime)
            else assertNotNull("$key sollte Runtime-Permission sein", runtime)
        }
    }

    // ── API-Level-Abhängigkeit ───────────────────────────────────────────────

    @Test
    fun `Benachrichtigungen erst ab Android 13`() {
        assertTrue(SetupSteps.runtimePermissions(SetupSteps.NOTIFICATIONS, 32)!!.isEmpty())
        assertEquals(
            listOf("android.permission.POST_NOTIFICATIONS"),
            SetupSteps.runtimePermissions(SetupSteps.NOTIFICATIONS, 33),
        )
    }

    @Test
    fun `Hintergrund-Standort und Aktivitaetserkennung erst ab Android 10`() {
        assertTrue(SetupSteps.runtimePermissions(SetupSteps.LOCATION_BG, 28)!!.isEmpty())
        assertTrue(SetupSteps.runtimePermissions(SetupSteps.ACTIVITY, 28)!!.isEmpty())
        assertEquals(
            listOf("android.permission.ACCESS_BACKGROUND_LOCATION"),
            SetupSteps.runtimePermissions(SetupSteps.LOCATION_BG, 29),
        )
        assertEquals(
            listOf("android.permission.ACTIVITY_RECOGNITION"),
            SetupSteps.runtimePermissions(SetupSteps.ACTIVITY, 29),
        )
    }

    @Test
    fun `Kalender braucht Lesen und Schreiben, Standort fragt beides an`() {
        assertEquals(
            listOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR"),
            SetupSteps.runtimePermissions(SetupSteps.CALENDAR, 35),
        )
        assertEquals(
            listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
            SetupSteps.runtimePermissions(SetupSteps.LOCATION, 35),
        )
    }

    @Test
    fun `Standort gilt nur mit genauer Position als erteilt`() {
        assertEquals(
            listOf("android.permission.ACCESS_FINE_LOCATION"),
            SetupSteps.requiredForGranted(SetupSteps.LOCATION, 35),
        )
        // alle anderen: sämtliche Permissions des Schritts
        assertEquals(
            SetupSteps.runtimePermissions(SetupSteps.CALENDAR, 35),
            SetupSteps.requiredForGranted(SetupSteps.CALENDAR, 35),
        )
        assertNull(SetupSteps.requiredForGranted(SetupSteps.OVERLAY, 35))
    }

    // ── Dauerhaft abgelehnt ──────────────────────────────────────────────────

    @Test
    fun `App-Einstellungen nur wenn schon gefragt und keine Begruendung mehr`() {
        assertFalse("nie gefragt → Dialog", SetupSteps.shouldOpenAppSettings(false, false))
        assertFalse("einmal abgelehnt, Begründung möglich → Dialog", SetupSteps.shouldOpenAppSettings(true, true))
        assertTrue("gefragt und gesperrt → Einstellungen", SetupSteps.shouldOpenAppSettings(true, false))
        assertFalse(SetupSteps.shouldOpenAppSettings(false, true))
    }

    // ── Eingabehilfe / Benachrichtigungszugriff (Settings.Secure-Liste) ──────

    @Test
    fun `Komponenten-Liste erkennt Kurz- und Langform`() {
        assertTrue(SetupSteps.isComponentEnabled("$pkg/.DonnaAccessibilityService", pkg, a11y))
        assertTrue(SetupSteps.isComponentEnabled("$pkg/$a11y", pkg, a11y))
        assertTrue(SetupSteps.isComponentEnabled("$pkg/.DonnaAccessibilityService", pkg, ".DonnaAccessibilityService"))
    }

    @Test
    fun `Komponenten-Liste mit mehreren Eintraegen`() {
        val list = "com.other/.Svc:$pkg/.DonnaAccessibilityService:com.third/com.third.X"
        assertTrue(SetupSteps.isComponentEnabled(list, pkg, a11y))
        assertFalse(SetupSteps.isComponentEnabled("com.other/.Svc:com.third/com.third.X", pkg, a11y))
    }

    @Test
    fun `Komponenten-Liste leer null oder fremdes Package`() {
        assertFalse(SetupSteps.isComponentEnabled(null, pkg, a11y))
        assertFalse(SetupSteps.isComponentEnabled("", pkg, a11y))
        assertFalse(SetupSteps.isComponentEnabled("   ", pkg, a11y))
        // gleiche Klasse, anderes Package → nicht Donna
        assertFalse(SetupSteps.isComponentEnabled("com.fake/.DonnaAccessibilityService", pkg, a11y))
        // gleiches Package, anderer Service
        assertFalse(SetupSteps.isComponentEnabled("$pkg/.AnderesWas", pkg, a11y))
        // kaputter Eintrag ohne Schrägstrich
        assertFalse(SetupSteps.isComponentEnabled(pkg, pkg, a11y))
    }

    // ── Pflicht-Gate ─────────────────────────────────────────────────────────

    @Test
    fun `Pflicht-Gate verlangt alle fünf Pflicht-Schritte`() {
        val allGranted = SetupSteps.REQUIRED_KEYS.associateWith { true }
        assertTrue(SetupSteps.requiredComplete(allGranted))
        assertFalse(SetupSteps.requiredComplete(allGranted + (SetupSteps.OVERLAY to false)))
        assertFalse(SetupSteps.requiredComplete(allGranted - SetupSteps.MIC))
        assertFalse(SetupSteps.requiredComplete(emptyMap()))
    }

    @Test
    fun `optionale Schritte beeinflussen das Pflicht-Gate nicht`() {
        val granted = SetupSteps.REQUIRED_KEYS.associateWith { true } +
            mapOf(SetupSteps.CAMERA to false, SetupSteps.HEALTH to false)
        assertTrue(SetupSteps.requiredComplete(granted))
    }

    // ── Standort: FINE + COARSE gemeinsam anfragen (Android 12+) ─────────────

    @Test
    fun `Standort fragt immer FINE und COARSE an auch wenn COARSE schon erteilt ist`() {
        // Szenario: Nutzer hat "ungefähr" erlaubt → nur FINE fehlt. Ein FINE-only-Request würde
        // von Android 12+ ignoriert; beide Permissions müssen im selben Request stehen.
        val requested = SetupSteps.permissionsToRequest(
            SetupSteps.LOCATION, 35, listOf("android.permission.ACCESS_FINE_LOCATION"),
        )
        assertEquals(
            listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
            requested,
        )
    }

    @Test
    fun `andere Schritte fragen nur fehlende Permissions an`() {
        assertEquals(
            listOf("android.permission.WRITE_CALENDAR"),
            SetupSteps.permissionsToRequest(SetupSteps.CALENDAR, 35, listOf("android.permission.WRITE_CALENDAR")),
        )
        assertEquals(
            listOf("android.permission.ACCESS_BACKGROUND_LOCATION"),
            SetupSteps.permissionsToRequest(
                SetupSteps.LOCATION_BG, 35, listOf("android.permission.ACCESS_BACKGROUND_LOCATION"),
            ),
        )
    }

    // ── Nutzungszugriff (AppOps-Modus) ───────────────────────────────────────

    @Test
    fun `Nutzungszugriff - ALLOWED erteilt, ERRORED und IGNORED nicht`() {
        assertTrue(SetupSteps.usageStatsGranted(android.app.AppOpsManager.MODE_ALLOWED, false))
        assertFalse(SetupSteps.usageStatsGranted(android.app.AppOpsManager.MODE_IGNORED, true))
        assertFalse(SetupSteps.usageStatsGranted(android.app.AppOpsManager.MODE_ERRORED, true))
    }

    @Test
    fun `Nutzungszugriff - MODE_DEFAULT entscheidet die Permission`() {
        assertTrue(SetupSteps.usageStatsGranted(android.app.AppOpsManager.MODE_DEFAULT, true))
        assertFalse(SetupSteps.usageStatsGranted(android.app.AppOpsManager.MODE_DEFAULT, false))
    }
}

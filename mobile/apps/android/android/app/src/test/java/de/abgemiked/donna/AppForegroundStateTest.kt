package com.yourcompany.donna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #51 — Mutual Exclusion NtfyService (nativ) <-> useNtfyNotifications (JS):
 * beide nutzen dieselbe Bedingung "MainActivity resumed". Hier: Flag + Verdrahtung.
 */
class AppForegroundStateTest {

    @Before
    fun reset() {
        AppForegroundState.onMainPaused()
    }

    @Test
    fun `Start ist nicht im Vordergrund`() {
        assertFalse(AppForegroundState.isMainResumed)
    }

    @Test
    fun `resume dann pause kippt das Flag`() {
        AppForegroundState.onMainResumed()
        assertTrue(AppForegroundState.isMainResumed)
        AppForegroundState.onMainPaused()
        assertFalse(AppForegroundState.isMainResumed)
    }

    @Test
    fun `Vordergrund unterdrueckt nur nicht-persistente Pushes, Hintergrund zeigt immer nativ`() {
        AppForegroundState.onMainResumed()
        assertTrue(PushPayload.suppressInForeground(persistent = false, appInForeground = AppForegroundState.isMainResumed))
        assertFalse(PushPayload.suppressInForeground(persistent = true, appInForeground = AppForegroundState.isMainResumed))
        AppForegroundState.onMainPaused()
        assertFalse(PushPayload.suppressInForeground(persistent = false, appInForeground = AppForegroundState.isMainResumed))
    }

    @Test
    fun `NtfyService nutzt AppForegroundState und nicht mehr den ProcessLifecycleOwner`() {
        val src = java.io.File("src/main/java/com/yourcompany/donna/NtfyService.kt").readText()
        val fn = src.substringAfter("private fun isAppInForeground()").substringBefore("private fun showPushNotification")
        assertTrue(fn.contains("AppForegroundState.isMainResumed"))
        assertFalse(src.contains("ProcessLifecycleOwner.get()"))
    }

    @Test
    fun `MainActivity setzt das Flag in onResume und onPause`() {
        val src = java.io.File("src/main/java/com/yourcompany/donna/MainActivity.kt").readText()
        val resume = src.substringAfter("override fun onResume()").substringBefore("override fun onPause()")
        val pause = src.substringAfter("override fun onPause()").substringBefore("// ── Intent Handling")
        assertTrue(resume.contains("AppForegroundState.onMainResumed()"))
        assertTrue(pause.contains("AppForegroundState.onMainPaused()"))
        // Pause kippt das Flag VOR super.onPause (RN-Host-Pause -> AppState 'background')
        assertEquals(true, pause.indexOf("AppForegroundState.onMainPaused()") < pause.indexOf("super.onPause()"))
    }
}

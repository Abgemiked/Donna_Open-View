package com.yourcompany.donna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #180 — NtfyService darf nicht am Android-15-dataSync-Zeitlimit (~6h/24h) scheitern.
 * Source-Contract (wie PushPayloadTest): kein Geraet noetig. Zeilenenden werden auf LF normalisiert.
 */
class NtfyFgsTimeoutTest {
    private fun read(path: String) = java.io.File(path).readText().replace("\r\n", "\n")

    private val svc = read("src/main/java/com/yourcompany/donna/NtfyService.kt")
    private val manifest = read("src/main/AndroidManifest.xml")

    private fun ntfyServiceBlock(): String {
        val start = manifest.indexOf("android:name=\".NtfyService\"")
        val end = manifest.indexOf("</service>", start)
        return manifest.substring(start, end)
    }

    @Test
    fun `Manifest - NtfyService nutzt specialUse statt dataSync, mit Subtype-Property`() {
        val block = ntfyServiceBlock()
        assertTrue(block.contains("android:foregroundServiceType=\"specialUse\""))
        assertFalse(block.contains("foregroundServiceType=\"dataSync\""))
        assertTrue(block.contains("PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
        assertTrue(manifest.contains("FOREGROUND_SERVICE_SPECIAL_USE"))
    }

    @Test
    fun `Manifest - DATA_SYNC-Permission ist weg`() {
        assertFalse(manifest.contains("FOREGROUND_SERVICE_DATA_SYNC"))
    }

    @Test
    fun `Service - kein dataSync-Typ mehr beim startForeground`() {
        assertFalse(svc.contains("FOREGROUND_SERVICE_TYPE_DATA_SYNC"))
        assertTrue(svc.contains("FOREGROUND_SERVICE_TYPE_SPECIAL_USE"))
    }

    @Test
    fun `Service - onTimeout (API 34 und 35) ruft den Handler, der stoppt, WakeLock freigibt und Neustart plant`() {
        val t35 = svc.substringAfter("override fun onTimeout(startId: Int, fgsType: Int)").substringBefore("}")
        val t34 = svc.substringAfter("override fun onTimeout(startId: Int) {").substringBefore("}")
        assertTrue(t35.contains("handleFgsTimeout(startId)"))
        assertTrue(t34.contains("handleFgsTimeout(startId)"))
        val h = svc.substringAfter("private fun handleFgsTimeout(").substringBefore("\n    /**")
        assertTrue(h.contains("sseJob?.cancel()"))
        assertTrue(h.contains("release()"))
        assertTrue(h.contains("scheduleRestart("))
        assertTrue(h.contains("stopSelf(startId)"))
    }

    @Test
    fun `Service - startForeground-Fehler (StartNotAllowed) wird gefangen und liefert false, kein Crash`() {
        val f = svc.substringAfter("private fun startForegroundCompat(): Boolean").substringBefore("// ── SSE Stream Loop")
        assertTrue(Regex("""catch \(e: IllegalStateException\)[\s\S]*?false""").containsMatchIn(f))
        assertTrue(Regex("""catch \(e: SecurityException\)[\s\S]*?false""").containsMatchIn(f))
    }

    @Test
    fun `onStartCommand - bei Fehlschlag Retry plus stopSelf plus NOT_STICKY, WakeLock erst danach`() {
        val o = svc.substringAfter("override fun onStartCommand").substringBefore("override fun onBind")
        val m = Regex("""if \(!startForegroundCompat\(\)\) \{([\s\S]*?)\n        \}""").find(o)
        assertTrue("Fehlerblock fehlt", m != null)
        val block = m!!.groupValues[1]
        assertTrue(block.contains("RETRY_AFTER_START_FAILURE_MS"))
        assertTrue(block.contains("stopSelf()"))
        assertTrue(block.contains("START_NOT_STICKY"))
        val i = o.indexOf("if (!startForegroundCompat())")
        val w = o.indexOf("newWakeLock")
        assertTrue(i >= 0 && w >= 0 && i < w)
    }

    @Test
    fun `start() faengt Hintergrundstart-Verbot und plant Retry`() {
        val s = svc.substringAfter("fun start(ctx: Context)").substringBefore("fun stop(ctx: Context)")
        assertTrue(s.contains("catch (e: IllegalStateException)"))
        assertTrue(s.contains("scheduleRestart(ctx, NtfyRestartPolicy.RETRY_AFTER_START_FAILURE_MS)"))
    }

    @Test
    fun `scheduleRestart - Alarmsetzung selbst ist abgesichert`() {
        val r = svc.substringAfter("fun scheduleRestart(").substringBefore("// Restart-Delay")
        assertTrue(r.contains("try {") && r.contains("catch (e: Exception)"))
    }

    @Test
    fun `RestartPolicy - Retry nicht schneller als 60s, Timeout-Neustart 10s`() {
        assertEquals(60_000L, NtfyRestartPolicy.RETRY_AFTER_START_FAILURE_MS)
        assertEquals(10_000L, NtfyRestartPolicy.RESTART_AFTER_TIMEOUT_MS)
    }
}

package com.yourcompany.donna.device

import android.content.Context
import android.util.Log
import com.yourcompany.donna.BuildConfig
import com.yourcompany.donna.TokenStore
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * #183 Schritt 0: meldet das Ergebnis einer Geräte-Aktion an den Server (`POST /device-actions/{id}/result`, Bearer wie
 * die übrigen App-Routen). Best effort: asynchron auf eigenem Thread (nie Haupt-/Aufrufer-Thread), eine Wiederholung;
 * scheitert es, bleibt es für den Server ehrlich bei "angestoßen". Gesendet werden nur Code und Ursache, nie Inhalte;
 * das Log nennt weder Kennung noch Werte.
 */
object DeviceActionReporter {
    private const val TAG = "DeviceActionReport"
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "device-action-report").apply { isDaemon = true } }

    fun report(context: Context, actionId: String, kind: ResultKind) {
        val app = context.applicationContext
        val wire = DeviceActionReport.wire(kind)
        try {
            executor.execute {
                var code = post(app, actionId, wire)
                if (DeviceActionReport.shouldRetry(code)) {
                    try { Thread.sleep(1500) } catch (_: InterruptedException) { return@execute }
                    code = post(app, actionId, wire)
                }
                Log.i(TAG, "result=${wire.result} http=${code ?: "none"}")
            }
        } catch (_: Exception) {
            // Executor nicht verfügbar: stilles Aufgeben, der Server bleibt bei "angestoßen"
        }
    }

    private fun post(ctx: Context, actionId: String, wire: DeviceActionReport.Wire): Int? {
        val token = try { TokenStore.getToken(ctx) } catch (_: Exception) { null } ?: return null
        return try {
            val conn = URL("${BuildConfig.DONNA_API_URL}${DeviceActionReport.path(actionId)}").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 4_000
                conn.readTimeout = 4_000
                conn.doOutput = true
                conn.outputStream.use { it.write(DeviceActionReport.body(wire).toByteArray(Charsets.UTF_8)) }
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Meldung nicht gesendet: ${e.javaClass.simpleName}")
            null
        }
    }
}

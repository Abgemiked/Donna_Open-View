package com.yourcompany.donna

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sendet die Gespraechs-Zusammenfassung (#122) genau einmal je Gespraech an `POST /tracking/push`
 * (type="conversation_summary"; nur Zahlen und Endegrund, siehe [ConversationSummary.toPushBody]).
 *
 * Eigener kleiner Sender nach dem Muster von `TrackingService.postJson`: der dortige Weg ist `private` und an den
 * Dienst gebunden, die Blase und das Overlay laufen aber ohne ihn. Blockiert NIE den Aufrufer (eigener Thread);
 * jeder Fehler wird verschluckt — ein Messwert darf das Gespraech nie beeinflussen.
 */
object ConversationSummarySender {
    private const val TAG = "ConvSummary"

    fun send(context: Context, summary: ConversationSummary) {
        val appContext = context.applicationContext
        val body = summary.toPushBody()
        try {
            Thread {
                try {
                    val token = TokenStore.getToken(appContext) ?: return@Thread
                    val conn = URL("${BuildConfig.DONNA_API_URL}/tracking/push").openConnection() as HttpURLConnection
                    try {
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.setRequestProperty("Authorization", "Bearer $token")
                        conn.connectTimeout = 10_000
                        conn.readTimeout = 10_000
                        conn.doOutput = true
                        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                        conn.responseCode // Antwort abwarten, Inhalt ist egal
                    } finally {
                        conn.disconnect()
                    }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Zusammenfassung nicht gesendet: ${e.javaClass.simpleName}")
                }
            }.apply { isDaemon = true; name = "conversation-summary" }.start()
        } catch (_: Throwable) {
            // Thread-Start gescheitert: ignorieren
        }
    }
}

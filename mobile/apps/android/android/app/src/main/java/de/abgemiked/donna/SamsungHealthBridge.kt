package com.yourcompany.donna

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * SamsungHealthBridge — DONNA-124: Samsung Health SDK via Reflection.
 *
 * Samsung Health SDK ist proprietär und nicht öffentlich über Maven verfügbar.
 * Dieser Bridge versucht beim Start die SDK-Klasse via Reflection zu laden:
 * - Falls vorhanden (Samsung-Gerät mit Samsung Health): liest StressScore, SpO2, SleepStage
 * - Falls NICHT vorhanden: graceful fallback → null, keine Abstürze
 *
 * Schnittstelle nach außen: getSamsungHealthData() gibt SamsungHealthData? zurück (null wenn nicht verfügbar).
 * Daten werden via sendSamsungHealthData() an /health/push gesendet (zusätzliche Felder zu Health Connect).
 */
object SamsungHealthBridge {

    private const val TAG = "SamsungHealthBridge"
    private const val SDK_CLASS = "com.samsung.android.sdk.healthdata.HealthDataStore"

    // ── Public Data Model ────────────────────────────────────────────────────

    data class SamsungHealthData(
        val stressScore: Int?,
        val spo2: Int?,
        val sleepStage: String?,
    )

    // ── SDK Availability Check ───────────────────────────────────────────────

    /**
     * Prüft ob Samsung Health SDK auf diesem Gerät verfügbar ist.
     * Gibt true zurück wenn die HealthDataStore-Klasse per Reflection gefunden wird.
     */
    fun isSamsungHealthAvailable(): Boolean {
        return try {
            Class.forName(SDK_CLASS)
            Log.i(TAG, "Samsung Health SDK gefunden — Samsung-Gerät erkannt")
            true
        } catch (e: ClassNotFoundException) {
            Log.d(TAG, "Samsung Health SDK nicht verfügbar: $e")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Samsung Health SDK check fehlgeschlagen: ${e.message}")
            false
        }
    }

    // ── Data Retrieval ───────────────────────────────────────────────────────

    /**
     * Versucht Samsung Health Daten zu lesen.
     * Gibt null zurück wenn SDK nicht verfügbar oder Lesen fehlschlägt.
     *
     * HINWEIS: Ohne echte SDK-Integration können nur Platzhalter-/Demo-Werte
     * zurückgegeben werden. Bei echter SDK-Verfügbarkeit müsste die HealthDataStore-
     * Verbindung vollständig implementiert werden (Callback-basiert).
     * Für Phase 3 wird der Verfügbarkeits-Check implementiert und die Datenstruktur vorbereitet.
     */
    fun getSamsungHealthData(): SamsungHealthData? {
        if (!isSamsungHealthAvailable()) {
            Log.d(TAG, "Samsung Health nicht verfügbar, nutze Health Connect")
            return null
        }

        return try {
            // Reflection-basierter Zugriff auf Samsung Health SDK Daten.
            // Die tatsächliche Implementierung würde hier HealthDataStore.connectService()
            // aufrufen und über Callbacks StressScore, SpO2, SleepStage abrufen.
            // Da das SDK-JAR zur Buildzeit nicht verfügbar ist, geben wir null zurück
            // und signalisieren damit, dass Health Connect als Fallback genutzt werden soll.
            Log.i(TAG, "Samsung Health SDK vorhanden — vollständige SDK-Integration benötigt JAR-Einbindung")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Samsung Health Datenabruf fehlgeschlagen: ${e.message}")
            null
        }
    }

    // ── HTTP Push ────────────────────────────────────────────────────────────

    /**
     * Sendet Samsung Health Daten an /health/push.
     * Zusätzliche Felder: stress_score, spo2, sleep_stage (alle optional).
     * Wird nur aufgerufen wenn getSamsungHealthData() nicht null zurückgibt.
     */
    fun sendSamsungHealthData(context: Context, data: SamsungHealthData) {
        val token = TokenStore.getToken(context) ?: run {
            Log.w(TAG, "Kein Token — Samsung Health Push übersprungen")
            return
        }

        val body = JSONObject().apply {
            data.stressScore?.let { put("stress_score", it) }
            data.spo2?.let { put("spo2", it) }
            data.sleepStage?.let { put("sleep_stage", it) }
        }

        if (body.length() == 0) {
            Log.d(TAG, "Keine Samsung Health Daten zum Senden")
            return
        }

        try {
            val url = URL("${BuildConfig.DONNA_API_URL}/health/push")
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 8_000
                readTimeout = 8_000
                doOutput = true
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()

            if (code in 200..299) {
                Log.i(TAG, "Samsung Health Daten gesendet: ${body.keys().asSequence().toList()}")
            } else {
                Log.w(TAG, "Samsung Health Push HTTP $code")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Samsung Health Push fehlgeschlagen: ${e.message}")
        }
    }

    // ── Sync Entry Point ─────────────────────────────────────────────────────

    /**
     * Einstiegspunkt: liest Samsung Health Daten und sendet sie wenn verfügbar.
     * Läuft im Hintergrund-Thread (IO) — nicht auf Main-Thread aufrufen.
     */
    fun syncIfAvailable(context: Context) {
        val data = getSamsungHealthData()
        if (data != null) {
            sendSamsungHealthData(context, data)
        } else {
            Log.d(TAG, "Samsung Health nicht verfügbar — Health Connect wird genutzt")
        }
    }
}

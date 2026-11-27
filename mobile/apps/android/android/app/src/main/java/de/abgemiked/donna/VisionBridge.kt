package com.yourcompany.donna

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * VisionBridge — DONNA-130: React Native Bridge für Camera/Vision.
 *
 * Stellt JS-Schicht analyzeCurrentView() bereit:
 * 1. Kamera-Permission prüfen
 * 2. Foto aufnehmen via VisionManager (CameraX, kein Auto-Foto)
 * 3. POST /vision/analyze mit base64 + question
 * 4. Promise mit Analyse-Text resolven
 *
 * Datenschutz (DSGVO Art. 5):
 * - Foto wird NUR auf expliziten User-Aufruf aufgenommen
 * - Bild verlässt das Gerät nur für diese eine API-Anfrage
 * - Bild wird weder auf Gerät noch auf Server gespeichert
 * - Maximale Bildgröße: 4MB Base64 (~3MB Original)
 */
class VisionBridge(private val reactContext: ReactApplicationContext)
    : ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "VisionBridge"
        private const val MAX_BASE64_LENGTH = 4 * 1024 * 1024 // 4MB
        private const val HTTP_TIMEOUT_MS = 30_000
    }

    private val visionManager = VisionManager(reactContext)
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun getName(): String = "VisionBridge"

    /**
     * Analysiert das aktuelle Kamerabild mit Gemini Vision.
     *
     * @param question Frage zur Szene (z.B. "Was ist das?" oder "Welcher Raum ist das?")
     *
     * Ablauf:
     * 1. CAMERA-Permission prüfen → reject "PERMISSION_DENIED" falls nicht gewährt
     * 2. Foto aufnehmen via VisionManager (CameraX, kein Speichern)
     * 3. Bildgröße prüfen → reject "IMAGE_TOO_LARGE" falls >4MB Base64
     * 4. POST /vision/analyze → resolve mit Analyse-Text
     *
     * Kamera-Permission muss vom User explizit erteilt worden sein (runtime permission).
     */
    @ReactMethod
    fun analyzeCurrentView(question: String, promise: Promise) {
        // Permission-Check vor allem anderen
        if (!hasCameraPermission()) {
            Log.w(TAG, "analyzeCurrentView: CAMERA Permission nicht erteilt")
            promise.reject(
                "PERMISSION_DENIED",
                "Kamera-Permission nicht erteilt. Bitte in App-Einstellungen aktivieren."
            )
            return
        }

        scope.launch {
            try {
                // Foto aufnehmen — läuft auf IO-Dispatcher via VisionManager
                val currentActivity = reactContext.currentActivity
                if (currentActivity == null) {
                    promise.reject("NO_ACTIVITY", "Keine aktive Activity für CameraX verfügbar.")
                    return@launch
                }

                // LifecycleOwner-Cast: MainActivity implementiert LifecycleOwner via ReactActivity → AppCompatActivity
                val lifecycleOwner = currentActivity as? androidx.lifecycle.LifecycleOwner
                if (lifecycleOwner == null) {
                    promise.reject("NO_LIFECYCLE", "Activity unterstützt kein Lifecycle (LifecycleOwner benötigt).")
                    return@launch
                }

                val base64Image = visionManager.capturePhoto(lifecycleOwner)
                if (base64Image == null) {
                    promise.reject("CAPTURE_FAILED", "Foto konnte nicht aufgenommen werden.")
                    return@launch
                }

                // Größenprüfung (4MB Base64 Limit)
                if (base64Image.length > MAX_BASE64_LENGTH) {
                    Log.w(TAG, "Bild zu groß: ${base64Image.length} Bytes Base64 (Max: $MAX_BASE64_LENGTH)")
                    promise.reject(
                        "IMAGE_TOO_LARGE",
                        "Bild zu groß (${base64Image.length / 1024}KB). Maximale Größe: 4MB."
                    )
                    return@launch
                }

                Log.i(TAG, "analyzeCurrentView: Foto aufgenommen (${base64Image.length} Base64-Zeichen), sende an Backend")

                // Backend-Anfrage
                val analysis = sendToVisionApi(base64Image, question)
                if (analysis != null) {
                    promise.resolve(analysis)
                } else {
                    promise.reject("API_ERROR", "Bildanalyse fehlgeschlagen — Backend nicht erreichbar oder Fehler.")
                }

            } catch (e: Exception) {
                Log.e(TAG, "analyzeCurrentView Fehler: ${e.message}")
                promise.reject("ERROR", "Vision-Analyse fehlgeschlagen: ${e.message}")
            }
        }
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            reactContext,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Sendet Base64-Bild + Frage an POST /vision/analyze.
     * Gibt Analyse-Text zurück oder null bei Fehler.
     *
     * Auth: Bearer-Token aus TokenStore (verschlüsselter Keystore-backed Speicher).
     * Timeout: 30s (Gemini Vision kann etwas dauern).
     */
    private fun sendToVisionApi(imageBase64: String, question: String): String? {
        val token = TokenStore.getToken(reactContext) ?: run {
            Log.w(TAG, "sendToVisionApi: Kein Token — nicht authentifiziert")
            return null
        }

        return try {
            val body = JSONObject().apply {
                put("image_base64", imageBase64)
                put("question", question)
            }.toString()

            val url = URL("${BuildConfig.DONNA_API_URL}/vision/analyze")
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                doOutput = true
            }

            conn.outputStream.use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val json = JSONObject(response)
                val analysis = json.optString("analysis", null)
                Log.i(TAG, "Vision-Analyse erhalten: ${analysis?.take(100)}")
                analysis
            } else {
                val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: ""
                conn.disconnect()
                Log.w(TAG, "Vision API HTTP $responseCode: $errorBody")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendToVisionApi Fehler: ${e.message}")
            null
        }
    }
}

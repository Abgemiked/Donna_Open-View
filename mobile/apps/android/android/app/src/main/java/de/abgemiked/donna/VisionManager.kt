package com.yourcompany.donna

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * VisionManager — DONNA-130: Camera/Vision Unterstützung via CameraX.
 *
 * Nimmt auf Anfrage ein Foto auf und gibt es als Base64-String zurück.
 * Das Bild wird NICHT gespeichert — einmalige Verarbeitung im Arbeitsspeicher.
 *
 * Datenschutz-Maßnahmen (DSGVO Art. 5):
 * - Kein automatisches Foto — nur auf expliziten JS-Aufruf
 * - Bild wird nicht auf dem Gerät gespeichert
 * - Bild wird nur temporär im RAM gehalten (bis Base64-Encoding fertig)
 * - Maximale Bildgröße: 4MB Base64 (~3MB Original) — serverseitig geprüft
 * - Kein Cloud-Backup der Fotos
 *
 * Verwendung: VisionBridge ruft capturePhoto() auf, sendet Base64 an Backend,
 * Backend analysiert via Gemini Vision und gibt Analyse zurück. Bild verlässt
 * das Gerät nur für diese eine Anfrage.
 */
class VisionManager(private val context: Context) {

    companion object {
        private const val TAG = "VisionManager"
        private const val CAPTURE_TIMEOUT_SEC = 10L
    }

    // ── Photo Capture ────────────────────────────────────────────────────────

    /**
     * Nimmt ein Foto via CameraX auf und gibt den Base64-kodierten JPEG-Buffer zurück.
     *
     * @param lifecycleOwner Activity oder Fragment als Lifecycle-Kontext für CameraX.
     * @return Base64-String des Fotos oder null bei Fehler/Timeout.
     *
     * Läuft auf IO-Dispatcher. CameraX-Callback wird auf Main-Thread ausgeführt
     * (ContextCompat.getMainExecutor) und synchronisiert via CountDownLatch.
     *
     * Datenschutz: Bild wird nicht auf Disk geschrieben — nur ImageProxy-Buffer
     * wird Base64-kodiert und direkt zurückgegeben.
     */
    suspend fun capturePhoto(lifecycleOwner: LifecycleOwner): String? {
        return withContext(Dispatchers.IO) {
            try {
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                // CameraX Provider + Bindung auf Main-Thread nötig
                // takePicture(ContextCompat.getMainExecutor): Callback auf Main-Thread
                var result: String? = null
                val latch = CountDownLatch(1)

                imageCapture.takePicture(
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            try {
                                val buffer = image.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                result = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                Log.i(TAG, "Foto aufgenommen: ${bytes.size} Bytes → ${result!!.length} Base64-Zeichen")
                            } catch (e: Exception) {
                                Log.e(TAG, "Foto-Encoding fehlgeschlagen: ${e.message}")
                            } finally {
                                image.close()
                                latch.countDown()
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e(TAG, "Foto-Capture fehlgeschlagen: ${exception.message} (code=${exception.imageCaptureError})")
                            latch.countDown()
                        }
                    }
                )

                val completed = latch.await(CAPTURE_TIMEOUT_SEC, TimeUnit.SECONDS)
                if (!completed) {
                    Log.w(TAG, "Foto-Capture Timeout nach ${CAPTURE_TIMEOUT_SEC}s")
                }
                result
            } catch (e: Exception) {
                Log.w(TAG, "capturePhoto Fehler: ${e.message}")
                null
            }
        }
    }
}

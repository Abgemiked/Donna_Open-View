package com.yourcompany.donna.widget

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Antwort eines Widget-Aufrufs. [code] = [UNREACHABLE] (Verbindung kam nicht zustande, nichts gesendet) oder
 * [UNKNOWN] (POST war gesendet, die Antwort kam nicht — der Server kann schon gehandelt haben).
 */
data class WidgetResponse(val code: Int, val body: String?) {
    val ok get() = code in 200..299
    val unreachable get() = code == UNREACHABLE
    val outcomeUnknown get() = code == UNKNOWN

    companion object {
        const val UNREACHABLE = -1
        const val UNKNOWN = -2
    }
}

/**
 * Welle 3 (#237): kleine HTTP-Hilfe der W3-Widgets (GET/POST JSON an den eigenen Server, mit Zeit- und Größengrenzen,
 * ohne Weiterleitungen). Bewusst eigene Datei statt Änderung am `WidgetRefresher` (parallele Arbeit an W2-Dateien);
 * NIE auf dem Main-Thread aufrufen. Logs nur mit Ausnahmetyp, nie mit Inhalten.
 */
object WidgetHttp {
    private const val TAG = "WidgetHttp"
    const val TIMEOUT_MS = 8_000
    const val BODY_LIMIT = 64 * 1024

    fun get(url: String, bearer: String?): WidgetResponse = call("GET", url, bearer, null)

    fun postJson(url: String, bearer: String, json: String): WidgetResponse = call("POST", url, bearer, json)

    private fun call(method: String, url: String, bearer: String?, json: String?): WidgetResponse {
        // Token nie im Klartext: mit Bearer nur über HTTPS
        if (bearer != null && !url.startsWith("https://")) {
            Log.w(TAG, "kein HTTPS, Abruf mit Token verweigert")
            return WidgetResponse(0, null)
        }
        var sent = false
        return callInner(method, url, bearer, json) { sent = true }.let { (r, e) ->
            when {
                e == null -> r!!
                e is IOException -> {
                    Log.w(TAG, "nicht erreichbar: ${e.javaClass.simpleName}")
                    // nach gesendetem POST ist offen, ob der Server gehandelt hat -> nie als "offline" behandeln
                    WidgetResponse(if (sent) WidgetResponse.UNKNOWN else WidgetResponse.UNREACHABLE, null)
                }
                else -> {
                    Log.w(TAG, "Abruf fehlgeschlagen: ${e.javaClass.simpleName}")
                    WidgetResponse(0, null)
                }
            }
        }
    }

    private fun callInner(
        method: String, url: String, bearer: String?, json: String?, onSent: () -> Unit,
    ): Pair<WidgetResponse?, Exception?> = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
            if (json != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                onSent()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { s ->
                val bytes = s.readLimited(BODY_LIMIT + 1)
                if (bytes.size > BODY_LIMIT) null else String(bytes, Charsets.UTF_8)
            }
            WidgetResponse(code, body) to null
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null to e
    }

    private fun InputStream.readLimited(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

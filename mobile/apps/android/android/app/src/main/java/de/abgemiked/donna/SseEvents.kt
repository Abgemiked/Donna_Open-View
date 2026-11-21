package com.yourcompany.donna

import org.json.JSONObject
import java.io.IOException
import java.io.Reader

/** Ein ausgewertetes Ereignis aus dem /chat-SSE-Stream des Backends. */
sealed class SseEvent {
    data class Delta(val text: String) : SseEvent()
    data class WeatherCard(val data: JSONObject) : SseEvent()
    data class MapCard(val url: String) : SseEvent()
    data class Action(val action: JSONObject) : SseEvent()
    /** Backend-Fehler mitten im Stream (type:"error"); [message] ist eine nutzertaugliche Meldung oder leer. */
    data class Error(val message: String) : SseEvent()
    /** Live-Guard-Korrektur (live_guard_corrected): ERSETZT den bisherigen Text, wird nicht angehängt. */
    data class Replace(val text: String) : SseEvent()
    /** Sauberes Stream-Ende ([DONE] oder {"type":"done"}). Ein Stream ohne dieses Ende ist abgeschnitten. */
    object Done : SseEvent()
}

/**
 * Wertet EINE SSE-Zeile aus (inkrementell, damit der Text schon während des Streams erscheint).
 * Gibt null für Zeilen ohne Nutzdaten zurück (Kommentare, Leerzeilen, andere Felder).
 */
object SseLineParser {

    /** String-Feld, aber JSON-null → "" (Android-optString liefert sonst den Text "null"). */
    private fun str(json: JSONObject, key: String): String =
        if (json.isNull(key)) "" else json.optString(key)

    fun parse(line: String): SseEvent? {
        if (!line.startsWith("data:")) return null
        val data = line.removePrefix("data:").trim()
        if (data.isBlank()) return null
        if (data == "[DONE]") return SseEvent.Done
        return try {
            val json = JSONObject(data)
            when (json.optString("type")) {
                "card" -> {
                    val cardData = json.optJSONObject("data") ?: return null
                    when (json.optString("card_type")) {
                        "weather" -> SseEvent.WeatherCard(cardData)
                        "map" -> str(cardData, "maps_url").takeIf { it.isNotBlank() }?.let { SseEvent.MapCard(it) }
                        else -> null
                    }
                }
                "action" -> json.optJSONObject("action")?.let { SseEvent.Action(it) }
                "error" -> SseEvent.Error(errorMessage(json))
                "done" -> SseEvent.Done
                "live_guard_corrected" -> SseEvent.Replace(str(json, "content"))
                "delta" -> {
                    val c = str(json, "content")
                    // Whitespace-only-Deltas (Leerzeichen/Zeilenumbruch zwischen Tokens) gehören zum Text
                    if (c.isNotEmpty() && !c.startsWith("[error]") && !c.startsWith("[warn]")) SseEvent.Delta(c) else null
                }
                else -> {
                    // Altes Format oder Plain-Text
                    val c = str(json, "content").ifBlank { str(json, "delta") }
                    if (c.isNotBlank()) SseEvent.Delta(c) else null
                }
            }
        } catch (_: Exception) {
            // Kein JSON → als Text behandeln, wenn sinnvoll
            if (!data.startsWith("{") && !data.startsWith("[")) SseEvent.Delta(data) else null
        }
    }

    /**
     * Nutzertaugliche Meldung: bevorzugt "message"; "error" nur, wenn es ein ganzer Satz ist
     * (ein Code wie "claude_empty" oder "generation_failed" ist nichts für den Nutzer).
     */
    private fun errorMessage(json: JSONObject): String {
        val message = str(json, "message").trim()
        if (message.isNotEmpty()) return message
        val error = str(json, "error").trim()
        return if (error.contains(' ')) error else ""
    }
}

/** Zeilenleser mit Obergrenze (Schutz vor UI-Blockade/OOM durch fehlerhafte Server ohne Zeilenumbruch). */
object SseLineReader {
    const val MAX_LINE_CHARS = 64_000

    /** Liest bis \n (oder \r\n). null = Stream-Ende. Wirft [IOException], wenn eine Zeile zu lang ist. */
    fun readLine(reader: Reader, maxChars: Int = MAX_LINE_CHARS): String? {
        val sb = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(c.toChar())
            if (sb.length > maxChars) throw IOException("SSE-Zeile zu lang")
        }
    }
}

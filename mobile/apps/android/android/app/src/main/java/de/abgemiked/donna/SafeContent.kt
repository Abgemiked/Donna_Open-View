package com.yourcompany.donna

import org.json.JSONObject
import java.net.URI

/**
 * Reine Hilfen für sichere Darstellung/Aktionen im Overlay (ohne Android-Laufzeit testbar).
 */

/**
 * URL-Richtlinie: geöffnet werden nur `https:`, `geo:` und `google.navigation:`. Alles andere (http, intent:,
 * file:, content:, javascript:, tel: …) wird abgelehnt. Bei https muss ein Host vorhanden sein; Nutzerinfo vor dem
 * „@“ (https://google.com@evil.example) ist ein klassischer Phishing-Trick und wird abgelehnt.
 */
object UrlPolicy {
    val ALLOWED_SCHEMES = setOf("https", "geo", "google.navigation")

    data class Checked(val url: String, val scheme: String, val host: String?) {
        /** Was Mike vor dem Öffnen sieht („Ziel: maps.google.com“). */
        fun displayTarget(): String = host ?: when (scheme) {
            "geo" -> "Karten-App"
            "google.navigation" -> "Navigation (Google Maps)"
            else -> scheme
        }
    }

    /** @return geprüfte URL oder null, wenn sie nicht geöffnet werden darf. */
    fun check(raw: String?): Checked? {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty() || url.length > 2_000) return null
        if (url.any { it.isISOControl() || it.isWhitespace() }) return null
        val uri = try { URI(url) } catch (_: Exception) { return null }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in ALLOWED_SCHEMES) return null
        if (scheme == "https") {
            val host = uri.host?.lowercase()
            if (host.isNullOrEmpty()) return null
            if (uri.userInfo != null) return null
            return Checked(url, scheme, host)
        }
        // geo: / google.navigation: sind opake URIs ohne Host
        return Checked(url, scheme, null)
    }
}

/** JSON-null-sichere Feldzugriffe: org.json liefert für `null` sonst den TEXT „null“. */
object JsonSafe {
    fun str(o: JSONObject, key: String, default: String = ""): String =
        if (!o.has(key) || o.isNull(key)) default else o.optString(key, default)

    /**
     * Nur echte JSON-Strings (getrimmt), alles andere — null, Zahlen, Objekte, fehlend — wird zu "".
     * Entspricht `str()` in utils/navActions.ts (Haupt-Chat), damit beide Pfade dasselbe Ziel wählen.
     */
    fun text(o: JSONObject, key: String): String = (o.opt(key) as? String)?.trim().orEmpty()

    /** Zahl als Text; fehlend/null → „–“ (nie ein erfundenes „0“). */
    fun numText(o: JSONObject, key: String): String =
        if (!o.has(key) || o.isNull(key)) "–" else o.optInt(key).toString()
}

/** Telefonnummern für Intents. */
object PhoneNumbers {
    /** Nur Ziffern (WhatsApp erwartet `phone=4915112345678` ohne +, Leerzeichen, Klammern, Striche). */
    fun forWhatsApp(raw: String?): String = raw.orEmpty().filter { it.isDigit() }
}

/**
 * Minimales Markdown → HTML für Html.fromHtml (fett, kursiv, Überschriften, Listen, https-Links).
 * Pur, damit die Escape-Regeln testbar sind.
 */
object MarkdownHtml {

    /** Attributwert-/Text-Escape: verhindert Ausbruch aus href="…" und eingeschleustes HTML. */
    fun escapeAttr(s: String): String = s
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    fun convert(text: String): String {
        val lines = text.lines()
        val sb = StringBuilder()
        lines.forEachIndexed { idx, rawLine ->
            var line = rawLine
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
            // Überschriften → fett
            line = line.replace(Regex("^#{1,3}\\s+(.+)$")) { "<b>${it.groupValues[1]}</b>" }
            // Bullet-Listen
            line = line.replace(Regex("^[\\t ]*[*•\\-]\\s+(.+)$")) { "• ${it.groupValues[1]}" }
            // **fett**
            line = line.replace(Regex("\\*\\*([^*]+)\\*\\*")) { "<b>${it.groupValues[1]}</b>" }
            // *kursiv* (einzelne Sterne)
            line = line.replace(Regex("(?<![*])\\*([^*\\n]+)\\*(?![*])")) { "<i>${it.groupValues[1]}</i>" }
            // Verbleibende Sterne entfernen
            line = line.replace(Regex("\\*+"), "")
            // Nur https-Links werden klickbar (Schema-Richtlinie); href ist bereits escaped (&quot; statt ")
            line = line.replace(Regex("https?://[^\\s<]+")) { m ->
                val decoded = m.value.replace("&amp;", "&").replace("&quot;", "\"")
                if (UrlPolicy.check(decoded) != null) "<a href=\"${m.value}\">${m.value}</a>" else m.value
            }
            sb.append(line)
            if (idx < lines.size - 1) sb.append("<br>")
        }
        return sb.toString()
    }
}

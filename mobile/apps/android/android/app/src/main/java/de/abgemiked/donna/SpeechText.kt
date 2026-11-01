package com.yourcompany.donna

/**
 * Textlogik für Streaming-Anzeige und satzweise Sprachausgabe im Overlay (UX-03) —
 * reine Funktionen, JVM-testbar.
 */
object SpeechText {

    private const val ACTION_MARKER = "[DONNA_ACTION:"
    private val ACTION_COMPLETE = Regex("\\[DONNA_ACTION:\\{[^}]*\\}\\]")

    /**
     * Rohtext des Streams → Anzeigetext. Entfernt vollständige Aktions-Marker (Altformat) und hält
     * einen noch unvollständigen Marker am Ende zurück, damit er nie kurz aufblitzt.
     */
    fun displayText(raw: String): String {
        var t = raw.replace(ACTION_COMPLETE, "")
        val i = t.lastIndexOf('[')
        if (i >= 0) {
            val tail = t.substring(i)
            val partialMarker = ACTION_MARKER.startsWith(tail) ||
                (tail.startsWith(ACTION_MARKER) && !tail.contains("}]"))
            if (partialMarker) t = t.substring(0, i)
        }
        return t
    }

    /** Markdown/Links für die Sprachausgabe entfernen. */
    fun cleanForSpeech(text: String): String = text
        .replace(Regex("\\*+"), "")
        .replace(Regex("(?m)^\\s*[-_=]{3,}\\s*$"), "")      // Trennlinien
        .replace(Regex("(?m)^\\s*#{1,6}\\s+"), "")
        .replace(Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)"), "$1")
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("(?m)^\\s*[•\\-]\\s+"), "")
        .replace("•", "")
        .replace(Regex("\\s+"), " ")
        .trim()
}

/**
 * Zerlegt den wachsenden Antworttext in sprechbare Häppchen: das erste, sobald ein Satz mit
 * mindestens [minFirstChars] Zeichen vollständig ist (sofort sprechen), danach satzweise.
 *
 * Arbeitet auf dem KUMULATIVEN Text (Präfix bleibt stabil), merkt sich die bereits ausgegebene Länge.
 */
class SentenceChunker(
    private val minFirstChars: Int = 30,
    private val minNextChars: Int = 24,
    private val maxChars: Int = 220,
) {
    private var consumed = 0
    private var emittedAny = false

    /** Neue, vollständige Häppchen seit dem letzten Aufruf. [final] = Stream zu Ende → Rest ausgeben. */
    fun update(text: String, final: Boolean = false): List<String> {
        val out = mutableListOf<String>()
        while (consumed < text.length) {
            val min = if (emittedAny) minNextChars else minFirstChars
            val end = findChunkEnd(text, consumed, min, final)
            if (end < 0) break
            val chunk = text.substring(consumed, end).trim()
            consumed = end
            if (SpeechText.cleanForSpeech(chunk).isNotEmpty()) {
                out.add(chunk)
                emittedAny = true
            }
        }
        return out
    }

    /** Index (exklusiv) hinter dem Häppchen oder -1, wenn noch kein vollständiges da ist. */
    private fun findChunkEnd(text: String, from: Int, min: Int, final: Boolean): Int {
        var i = from
        while (i < text.length) {
            val c = text[i]
            val isNewline = c == '\n'
            val isStop = c == '.' || c == '!' || c == '?' || c == '…'
            if (isNewline || isStop) {
                // Schließende Anführungszeichen/Klammern gehören noch zum Satz: „Das stimmt.“ (Ende) – wichtig
                var j = i + 1
                if (isStop) while (j < text.length && text[j] in CLOSERS) j++
                // Satzende nur, wenn danach Leerraum/Zeilenende folgt (oder der Text hier endet und final ist)
                val next = if (j < text.length) text[j] else null
                val boundary = when {
                    isNewline -> true
                    next == null -> final
                    next.isWhitespace() -> true
                    else -> false
                }
                if (boundary && (isNewline || c != '.' || !isNonSentencePeriod(text, i))) {
                    if (text.substring(from, j).trim().length >= min || final && j >= text.length) {
                        return j
                    }
                }
            }
            // Überlanger Satz ohne Satzzeichen: am letzten Komma/Leerzeichen schneiden
            if (i - from >= maxChars) {
                val cut = text.lastIndexOf(", ", i).takeIf { it > from + maxChars / 2 }
                    ?: text.lastIndexOf(' ', i).takeIf { it > from + maxChars / 2 }
                if (cut != null) return cut + 1
            }
            i++
        }
        return if (final) text.length else -1
    }

    /** Punkt, der kein Satzende ist: Abkürzung, Ordnungszahl/Datum („am 3. Mai“) oder Dezimalzahl. */
    private fun isNonSentencePeriod(text: String, dot: Int): Boolean {
        // Wort vor dem Punkt
        var s = dot
        while (s > 0 && !text[s - 1].isWhitespace()) s--
        val rawWord = text.substring(s, dot)
        // führende Klammer/Anführungszeichen ignorieren: „(ca.“
        val word = rawWord.trimStart('(', '"', '„', '“', '»', '«', '\'').lowercase()
        if (word.isEmpty()) return false
        if (word.all { it.isDigit() } && word.length <= 2) return true       // „3.“ → Ordnungszahl
        if (word in ABBREVIATIONS) return true
        // „z.B.“, „d.h.“, „u.a.“ → Teile mit einzelnen Buchstaben und Punkten
        if (word.contains('.') && word.split('.').all { it.length <= 1 }) return true
        // „z. B.“ / „d. h.“ / „u. a.“ mit Leerzeichen: einzelner Kleinbuchstabe mit Punkt …
        if (word.length == 1 && word[0].isLetter() && rawWord[0].isLowerCase()) return true
        // … und der folgende einzelne Buchstabe („B.“, „h.“, „a.“, „ä.“) nach einem solchen Kürzel
        if (word.length == 1 && word[0].isLetter() && previousWordIsInitial(text, s)) return true
        return false
    }

    /** Steht vor Position [wordStart] ein Kürzel wie „z.“ / „d.“ / „u.“ / „o.“? */
    private fun previousWordIsInitial(text: String, wordStart: Int): Boolean {
        var e = wordStart
        while (e > 0 && text[e - 1].isWhitespace()) e--
        if (e == wordStart || e < 2 || text[e - 1] != '.') return false
        var b = e - 1
        while (b > 0 && !text[b - 1].isWhitespace()) b--
        val prev = text.substring(b, e - 1)
        return prev.length == 1 && prev[0].isLowerCase()
    }

    private companion object {
        val CLOSERS = setOf('"', '“', '”', '»', '«', ')', '’', '\'', ']')
        val ABBREVIATIONS = setOf(
            "dr", "prof", "nr", "ca", "bzw", "usw", "vgl", "evtl", "ggf", "ggfs", "inkl", "max", "min",
            "str", "tel", "hr", "fr", "sog", "etc", "z.b", "d.h", "u.a", "z.t", "o.ä", "u.u", "s.o", "s.u",
            "mio", "mrd", "std", "abs", "st", "bspw", "zzgl", "ebd", "kap", "evtl", "geb", "gem", "zw",
            "jan", "feb", "mär", "apr", "jun", "jul", "aug", "sep", "sept", "okt", "nov", "dez",
        )
    }
}

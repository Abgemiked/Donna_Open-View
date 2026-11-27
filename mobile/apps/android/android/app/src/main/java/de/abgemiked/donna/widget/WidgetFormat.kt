package com.yourcompany.donna.widget

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Welle 2 (#236): gemeinsame reine Hilfen der Daten-Widgets (Zeitbeschriftung, Zeilenzahl, Textsäuberung).
 * Keine Android-Abhängigkeit → als JVM-Test prüfbar.
 */
object WidgetFormat {
    private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DM: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.")
    val DAYS_SHORT = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

    /** Höhe der Kopfzeile und einer Listenzeile in dp (muss zu den Layouts passen). */
    const val HEADER_DP = 44
    const val ROW_DP = 36

    /** Wie viele Zeilen passen in [heightDp] (mindestens 1, höchstens [max]). 0/unbekannt → [fallback]. */
    fun rowsForHeight(heightDp: Int, max: Int, fallback: Int = 3): Int {
        if (heightDp <= 0) return fallback.coerceIn(1, max)
        return ((heightDp - HEADER_DP) / ROW_DP).coerceIn(1, max)
    }

    /** "14:30" heute, "morgen 14:30", "Fr 14:30" innerhalb einer Woche, sonst "28.11. 14:30". Vergangenes heute: Uhrzeit. */
    fun whenLabel(atMs: Long, nowMs: Long, zone: ZoneId): String {
        val at = Instant.ofEpochMilli(atMs).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(today, at.toLocalDate())
        val hm = at.format(HM)
        return when {
            days <= 0L -> if (days < 0L) "${at.format(DM)} $hm" else hm
            days == 1L -> "morgen $hm"
            days < 7L -> "${DAYS_SHORT[at.dayOfWeek.value - 1]} $hm"
            else -> "${at.format(DM)} $hm"
        }
    }

    /** "heute", "morgen" oder Wochentag-Kurzform für ein Datum relativ zu [today]. */
    fun dayWord(date: LocalDate, today: LocalDate): String = when (ChronoUnit.DAYS.between(today, date)) {
        0L -> "heute"
        1L -> "morgen"
        else -> DAYS_SHORT[date.dayOfWeek.value - 1]
    }

    /** Fremden Text säubern: Steuer-/Formatzeichen raus, Leerraum normalisieren, auf [limit] kürzen (mit …). */
    fun clean(text: String?, limit: Int, stripBrackets: Boolean = false): String {
        if (text.isNullOrEmpty()) return ""
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val type = Character.getType(ch)
            when {
                type == Character.FORMAT.toInt() -> Unit
                stripBrackets && ch in "[]{}<>`" -> Unit
                type == Character.CONTROL.toInt() || type == Character.LINE_SEPARATOR.toInt() ||
                    type == Character.PARAGRAPH_SEPARATOR.toInt() -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        val norm = sb.toString().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        return if (norm.length <= limit) norm else norm.substring(0, (limit - 1).coerceAtLeast(0)).trimEnd() + "…"
    }

    /** Ist ein Cache-Stand älter als [maxAgeMs]? (0 = nie geladen → veraltet). */
    fun isStale(loadedAtMs: Long, nowMs: Long, maxAgeMs: Long): Boolean = loadedAtMs <= 0L || nowMs - loadedAtMs > maxAgeMs

    fun hm(atMs: Long, zone: ZoneId): String = Instant.ofEpochMilli(atMs).atZone(zone).format(HM)
}

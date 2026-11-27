package com.yourcompany.donna.widget

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Ein Termin-Vorkommen aus dem Handy-Kalender (Ganztägige: begin/end in UTC-Mitternacht, wie im Provider). */
data class WidgetEvent(
    val eventId: Long,
    val beginMs: Long,
    val endMs: Long,
    val title: String,
    val allDay: Boolean,
    /** #243: Personen-Tag („Mike“, „Ämi“, „Mike + Ämi“ …), nur Namen, nie Adressen; leer = kein Tag. */
    val tag: String = "",
)

/** Eine Widget-Zeile: Zeitbeschriftung + Titel + was der Tipp öffnet. */
data class EventRow(val label: String, val title: String, val event: WidgetEvent)

/**
 * #240: reine Logik des Kalender-Widgets — die nächsten Termine von heute/morgen auswählen und beschriften.
 * Ganztägige Termine liegen im Provider in UTC; sie werden über ihr Datum (nicht die Uhrzeit) eingeordnet.
 */
object CalendarWidgetLogic {
    const val TITLE_MAX = 80
    const val TAG_MAX = 32
    private const val CACHE_MAX = 30

    /** Lesefenster für den Provider: gestern 0 Uhr bis übermorgen 24 Uhr (Puffer für Ganztägige in UTC). */
    fun window(nowMs: Long, zone: ZoneId): Pair<Long, Long> {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() to
            today.plusDays(3).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun utcDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

    /**
     * Termine, die heute oder morgen stattfinden und noch nicht vorbei sind. Ganztägige des Tages zuerst, dann nach
     * Beginn. Laufende Termine stehen mit "bis HH:mm" vorne. Leere Titel → "Termin".
     */
    fun select(events: List<WidgetEvent>, nowMs: Long, zone: ZoneId, max: Int): List<EventRow> {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val tomorrow = today.plusDays(1)
        val dayAfter = today.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        data class Keyed(val day: LocalDate, val allDayFirst: Int, val begin: Long, val row: EventRow)
        val keyed = ArrayList<Keyed>()
        for (e in events) {
            val title = WidgetFormat.clean(e.title, TITLE_MAX).ifEmpty { "Termin" }
            if (e.allDay) {
                val start = utcDate(e.beginMs)
                val endExcl = utcDate(e.endMs).let { if (it.isAfter(start)) it else start.plusDays(1) }
                val day = when {
                    !today.isBefore(start) && today.isBefore(endExcl) -> today
                    !tomorrow.isBefore(start) && tomorrow.isBefore(endExcl) -> tomorrow
                    else -> null
                } ?: continue
                val label = if (day == today) "ganztägig" else "morgen"
                keyed += Keyed(day, 0, e.beginMs, EventRow(label, title, e))
            } else {
                if (e.endMs <= nowMs && e.endMs > e.beginMs) continue // vorbei
                if (e.endMs <= e.beginMs && e.beginMs < nowMs) continue // ohne Dauer und vorbei
                if (e.beginMs >= dayAfter) continue
                val ongoing = e.beginMs <= nowMs
                val day = if (ongoing) today else Instant.ofEpochMilli(e.beginMs).atZone(zone).toLocalDate()
                if (day.isBefore(today) || day.isAfter(tomorrow)) continue
                val endDay = Instant.ofEpochMilli(e.endMs).atZone(zone).toLocalDate()
                val label = when {
                    !ongoing -> WidgetFormat.whenLabel(e.beginMs, nowMs, zone)
                    endDay.isAfter(today) -> "bis ${WidgetFormat.dayWord(endDay, today)}"
                    else -> "bis ${WidgetFormat.hm(e.endMs, zone)}"
                }
                keyed += Keyed(day, 1, if (ongoing) nowMs else e.beginMs, EventRow(label, title, e))
            }
        }
        return keyed.sortedWith(compareBy({ it.day }, { it.allDayFirst }, { it.begin }, { it.row.title }))
            .map { it.row }.take(max.coerceAtLeast(0))
    }

    fun toCache(items: List<WidgetEvent>): String = JSONArray().also { arr ->
        items.sortedBy { it.beginMs }.take(CACHE_MAX).forEach {
            arr.put(
                JSONObject().put("id", it.eventId).put("b", it.beginMs).put("e", it.endMs)
                    .put("t", WidgetFormat.clean(it.title, TITLE_MAX)).put("a", it.allDay)
                    .put("g", WidgetFormat.clean(it.tag, TAG_MAX)),
            )
        }
    }.toString()

    fun fromCache(raw: String?): List<WidgetEvent> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                if (!o.has("id") || !o.has("b")) null
                else WidgetEvent(o.optLong("id"), o.optLong("b"), o.optLong("e"), o.optString("t", ""), o.optBoolean("a"), o.optString("g", ""))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

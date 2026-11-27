package com.yourcompany.donna.widget

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class StreamEntry(val from: LocalTime, val to: LocalTime?, val activity: String)
data class StreamDay(val dayOfWeek: Int, val status: String, val entries: List<StreamEntry>, val note: String)
data class StreamPlan(val weekStart: LocalDate?, val days: List<StreamDay>)

/** Was das Stream-Widget zeigt: Überschrift + eine Detailzeile. */
data class StreamView(val title: String, val detail: String)

/**
 * #241: reine Logik des Stream-Widgets. Quelle ist die öffentliche Seite `https://your-donna-instance.example.com/api/schedule`
 * (gleiches Format wie `backend/app/services/schedule_service.py`):
 * `{"week_start":"2026-11-23","days":[{"day_of_week":0,"status":"stream|streamfrei|ausfall",
 *   "entries":[{"from":"19:30","to":"","activity":"…"}],"note":null}]}` — day_of_week 0 = Montag.
 * Fremder Text wird gesäubert und gekürzt; ein fehlendes "to" gilt als [DEFAULT_HOURS] Stunden Stream.
 */
object StreamWidgetLogic {
    val ZONE: ZoneId = ZoneId.of("Europe/Berlin")
    const val DEFAULT_HOURS = 4L
    const val ACTIVITY_MAX = 60
    const val NOTE_MAX = 60
    const val BODY_MAX = 64 * 1024

    fun parse(body: String?): StreamPlan? {
        if (body.isNullOrBlank() || body.length > BODY_MAX) return null
        val root = try { JSONObject(body) } catch (_: Exception) { return null }
        val arr = root.optJSONArray("days") ?: return null
        val weekStart = try { LocalDate.parse(root.optString("week_start", "")) } catch (_: Exception) { null }
        val days = ArrayList<StreamDay>()
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val dowRaw = d.opt("day_of_week")
            // kein Default: ein fehlender Tag darf nicht als Montag zählen (wie im Backend)
            if (dowRaw !is Int || dowRaw !in 0..6) continue
            val entries = ArrayList<StreamEntry>()
            val ea = d.optJSONArray("entries")
            if (ea != null) {
                for (j in 0 until ea.length()) {
                    val e = ea.optJSONObject(j) ?: continue
                    val from = time(e.optString("from", "")) ?: continue
                    entries += StreamEntry(
                        from, time(e.optString("to", "")),
                        WidgetFormat.clean(e.optString("activity", ""), ACTIVITY_MAX, stripBrackets = true),
                    )
                }
            }
            val note = if (d.isNull("note")) "" else WidgetFormat.clean(d.optString("note", ""), NOTE_MAX, stripBrackets = true)
            days += StreamDay(dowRaw, d.optString("status", ""), entries.sortedBy { it.from }, note)
        }
        return StreamPlan(weekStart, days)
    }

    /** "19:30" / "9:05" → Zeit, sonst null. */
    fun time(s: String): LocalTime? {
        val m = Regex("^\\s*(\\d{1,2}):(\\d{2})\\s*$").find(s) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
    }

    /** Datum eines Plan-Tages: über week_start, sonst das nächste Vorkommen ab heute (heute oder später). */
    private fun dateOf(plan: StreamPlan, day: StreamDay, today: LocalDate): LocalDate =
        plan.weekStart?.plusDays(day.dayOfWeek.toLong())
            ?: today.plusDays(((day.dayOfWeek - (today.dayOfWeek.value - 1)) % 7 + 7) % 7L)

    private fun endOf(date: LocalDate, e: StreamEntry): ZonedDateTime {
        val start = date.atTime(e.from).atZone(ZONE)
        val to = e.to ?: return start.plusHours(DEFAULT_HOURS)
        val end = date.atTime(to).atZone(ZONE)
        return if (end.isAfter(start)) end else end.plusDays(1) // über Mitternacht
    }

    private fun slots(entries: List<StreamEntry>): String = entries.joinToString(" · ") { e ->
        val t = e.from.toString()
        if (e.activity.isEmpty()) t else "$t ${e.activity}"
    }

    /** Anzeige zum Zeitpunkt [now] (wird beim Zeichnen aus dem Cache neu berechnet). */
    fun view(plan: StreamPlan?, now: ZonedDateTime): StreamView {
        if (plan == null) return StreamView("Streamplan", "Noch nicht geladen")
        val nowB = now.withZoneSameInstant(ZONE)
        val today = nowB.toLocalDate()
        val dated = plan.days.map { dateOf(plan, it, today) to it }.sortedBy { it.first }

        val todayDay = dated.firstOrNull { it.first == today }?.second
        val todayRunning = todayDay?.takeIf { it.status == "stream" }?.entries
            ?.filter { endOf(today, it).isAfter(nowB) }.orEmpty()
        if (todayRunning.isNotEmpty()) return StreamView("Heute Stream", slots(todayRunning))
        // Stream von gestern läuft über Mitternacht noch (z. B. 21:30 ohne Ende → bis 01:30)
        val yesterday = today.minusDays(1)
        val lateRunning = dated.firstOrNull { it.first == yesterday }?.second?.takeIf { it.status == "stream" }?.entries
            ?.filter { endOf(yesterday, it).isAfter(nowB) }.orEmpty()
        if (lateRunning.isNotEmpty()) return StreamView("Stream läuft noch", slots(lateRunning))

        val next = dated.firstOrNull { (date, d) ->
            date.isAfter(today) && d.status == "stream" && d.entries.isNotEmpty()
        }
        val nextText = next?.let { (date, d) -> "${WidgetFormat.dayWord(date, today)} ${slots(d.entries)}" }

        if (todayDay?.status == "ausfall") {
            val reason = if (todayDay.note.isNotEmpty()) "Ausfall (${todayDay.note})" else "Ausfall"
            return StreamView("Heute kein Stream", if (nextText != null) "$reason · Nächster: $nextText" else reason)
        }
        if (nextText != null) return StreamView("Nächster Stream", nextText.replaceFirstChar { it.uppercase() })
        val planOutdated = plan.weekStart != null && !plan.weekStart.plusDays(7).isAfter(today)
        return StreamView(
            "Kein Stream geplant",
            if (planOutdated) "Der neue Streamplan ist noch nicht da" else "Diese Woche kommt kein Stream mehr",
        )
    }

    /**
     * #243: Liste für das große Stream-Widget — die kommenden Programmpunkte der Woche ab jetzt (laufende zuerst,
     * auch der über Mitternacht laufende von gestern), Ausfall-Tage als eigene Zeile. Höchstens [max] Zeilen.
     */
    fun upcoming(plan: StreamPlan?, now: ZonedDateTime, max: Int): List<StreamRow> {
        if (plan == null || max <= 0) return emptyList()
        val nowB = now.withZoneSameInstant(ZONE)
        val today = nowB.toLocalDate()
        val yesterday = today.minusDays(1)
        val out = ArrayList<StreamRow>()
        // dateOf liefert ohne week_start nie "gestern" — dann gibt es keinen Übertrag von gestern (wie in view())
        val dated = plan.days.map { dateOf(plan, it, today) to it }.sortedBy { it.first }
        for ((date, d) in dated) {
            if (date.isBefore(yesterday)) continue
            when (d.status) {
                "stream" -> for (e in d.entries) {
                    val end = endOf(date, e)
                    if (!end.isAfter(nowB)) continue
                    val running = !date.atTime(e.from).atZone(ZONE).isAfter(nowB)
                    val label = if (running) "läuft" else "${WidgetFormat.dayWord(date, today)} ${e.from}"
                    out += StreamRow(label, e.activity.ifEmpty { "Stream" })
                }
                "ausfall" -> if (!date.isBefore(today)) {
                    out += StreamRow(WidgetFormat.dayWord(date, today), if (d.note.isNotEmpty()) "kein Stream (${d.note})" else "kein Stream")
                }
            }
        }
        return out.take(max)
    }
}

/** Eine Zeile im großen Stream-Widget (laufender Programmpunkt: Label „läuft“). */
data class StreamRow(val label: String, val text: String)

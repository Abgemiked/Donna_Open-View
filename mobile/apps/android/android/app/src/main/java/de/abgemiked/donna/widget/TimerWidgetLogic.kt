package com.yourcompany.donna.widget

import org.json.JSONArray
import org.json.JSONObject

/** Ein von Donna gestellter Timer: Ablaufzeitpunkt (Wanduhr, ms) + Beschriftung. */
data class WidgetTimer(val endsAtMs: Long, val label: String)

/**
 * #247: reine Logik des Timer-Widgets. Die Uhr-App gibt Timer nicht heraus; deshalb merkt sich Donna beim Stellen
 * die Ablaufzeit selbst. GRENZE: Wird der Timer in der (Samsung-)Uhr-App abgebrochen, pausiert oder verlängert,
 * erfährt Donna das nicht — das Widget zählt dann bis zur ursprünglichen Ablaufzeit weiter und blendet ihn danach aus.
 */
object TimerWidgetLogic {
    const val MAX_TIMERS = 5
    const val LABEL_MAX = 40
    /** Längster Timer, den Donna stellt (Server/Parser: 1..1440 min); mehr wird nicht gemerkt. */
    const val MAX_MINUTES = 24 * 60

    fun active(list: List<WidgetTimer>, nowMs: Long): List<WidgetTimer> =
        list.filter { it.endsAtMs > nowMs }.sortedBy { it.endsAtMs }

    /** Neuen Timer anhängen: Abgelaufene fallen weg, höchstens [MAX_TIMERS] (die bald ablaufenden bleiben). */
    fun add(list: List<WidgetTimer>, nowMs: Long, minutes: Int, label: String): List<WidgetTimer> {
        if (minutes !in 1..MAX_MINUTES) return active(list, nowMs)
        val t = WidgetTimer(nowMs + minutes * 60_000L, WidgetFormat.clean(label, LABEL_MAX, stripBrackets = true))
        return active(list + t, nowMs).take(MAX_TIMERS)
    }

    fun next(list: List<WidgetTimer>, nowMs: Long): Long? = active(list, nowMs).firstOrNull()?.endsAtMs

    fun toJson(list: List<WidgetTimer>): String = JSONArray().apply {
        list.take(MAX_TIMERS).forEach { put(JSONObject().put("e", it.endsAtMs).put("l", it.label)) }
    }.toString()

    fun fromJson(s: String?): List<WidgetTimer> {
        if (s.isNullOrBlank()) return emptyList()
        val arr = try { JSONArray(s) } catch (_: Exception) { return emptyList() }
        val out = ArrayList<WidgetTimer>()
        for (i in 0 until minOf(arr.length(), MAX_TIMERS)) {
            val o = arr.optJSONObject(i) ?: continue
            val e = o.optLong("e", 0L)
            if (e <= 0L) continue
            out += WidgetTimer(e, WidgetFormat.clean(o.optString("l", ""), LABEL_MAX, stripBrackets = true))
        }
        return out
    }

    /** Zeilen je Höhe (dp): niedrig 1, mittel 2, hoch 3. */
    fun rowsFor(heightDp: Int): Int = when {
        heightDp >= 150 -> 3
        heightDp >= 100 -> 2
        else -> 1
    }
}

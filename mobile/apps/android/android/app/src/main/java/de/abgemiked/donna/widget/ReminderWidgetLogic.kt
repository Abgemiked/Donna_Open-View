package com.yourcompany.donna.widget

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Eine offene Erinnerung, wie sie das Widget zeigt (Kennung als Text wie in [com.yourcompany.donna.reminder.Reply]). */
data class WidgetReminder(val id: String, val text: String, val dueMs: Long)

/**
 * #239: reine Logik des Erinnerungen-Widgets — Antwort von `GET /reminders` lesen, Cache (de)serialisieren,
 * die nächsten Einträge auswählen. Keine Android-Abhängigkeit außer org.json.
 */
object ReminderWidgetLogic {
    const val TEXT_MAX = 120
    private const val CACHE_MAX = 20

    /**
     * `{"reminders":[{"id":1,"text":"…","due_utc":"2026-11-27T10:00:00Z","tz":"Europe/Berlin"}]}` → Liste.
     * Kaputte Einträge werden übersprungen; ist die Hülle selbst kaputt → null (Aufrufer behält den alten Cache).
     */
    fun parse(body: String?): List<WidgetReminder>? {
        if (body.isNullOrBlank()) return null
        val arr = try { JSONObject(body).optJSONArray("reminders") } catch (_: Exception) { null } ?: return null
        val out = ArrayList<WidgetReminder>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = idOf(o.opt("id")) ?: continue
            val text = WidgetFormat.clean(o.optString("text", ""), TEXT_MAX)
            if (text.isEmpty()) continue
            val due = parseUtc(o.optString("due_utc", "")) ?: continue
            out += WidgetReminder(id, text, due)
        }
        return out.sortedWith(compareBy({ it.dueMs }, { it.id })).take(CACHE_MAX)
    }

    /** Nur positive ganze Zahlen als Kennung (gleiche Regel wie der Server-Pfad `/reminders/{id}`). */
    private fun idOf(v: Any?): String? {
        val n = when (v) {
            is Int -> v.toLong()
            is Long -> v
            is String -> v.toLongOrNull()
            else -> null
        } ?: return null
        return if (n in 1..Int.MAX_VALUE.toLong()) n.toString() else null
    }

    fun parseUtc(s: String): Long? = try { Instant.parse(s).toEpochMilli() } catch (_: Exception) { null }

    fun toCache(items: List<WidgetReminder>): String = JSONArray().also { arr ->
        items.forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text).put("due", it.dueMs)) }
    }.toString()

    fun fromCache(raw: String?): List<WidgetReminder> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id", "")
                if (id.isEmpty() || !o.has("due")) null else WidgetReminder(id, o.optString("text", ""), o.optLong("due"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Angezeigte Einträge: bereits als erledigt gemerkte raus, nach Fälligkeit, höchstens [max]. */
    fun visible(items: List<WidgetReminder>, answered: Collection<String>, max: Int): List<WidgetReminder> =
        items.filter { it.id !in answered }.sortedWith(compareBy({ it.dueMs }, { it.id })).take(max.coerceAtLeast(0))

    /** Sofortige Anzeige nach "Später": Fälligkeit auf jetzt + Minuten (der nächste Abruf liefert den Server-Stand). */
    fun snoozed(items: List<WidgetReminder>, id: String, minutes: Int, nowMs: Long): List<WidgetReminder> =
        items.map { if (it.id == id) it.copy(dueMs = nowMs + minutes * 60_000L) else it }

    /**
     * „Später“ nur für fällige Einträge: der Server setzt beim Verschieben `due = jetzt + Minuten` — bei einer noch
     * nicht fälligen Erinnerung würde das ihre eigentliche Zeit vorziehen und überschreiben. Künftige: nur Erledigt.
     */
    fun canSnooze(r: WidgetReminder, nowMs: Long): Boolean = r.dueMs <= nowMs

    /** Wie lange eine per Push zugestellte Kennung aus Abrufen herausgefiltert wird (Server meldet sie kurz noch offen). */
    const val DELIVERED_KEEP_MS = 10 * 60_000L

    /** Merkliste zugestellter Kennungen `id:ms,id:ms` — abgelaufene fallen beim Schreiben raus. */
    fun deliveredAdd(raw: String?, id: String, nowMs: Long): String =
        (deliveredMap(raw, nowMs) + (id to nowMs)).entries.joinToString(",") { "${it.key}:${it.value}" }

    fun deliveredActive(raw: String?, nowMs: Long): Set<String> = deliveredMap(raw, nowMs).keys

    private fun deliveredMap(raw: String?, nowMs: Long): Map<String, Long> =
        raw.orEmpty().split(',').mapNotNull { part ->
            val i = part.lastIndexOf(':')
            if (i <= 0) return@mapNotNull null
            val at = part.substring(i + 1).toLongOrNull() ?: return@mapNotNull null
            if (nowMs - at > DELIVERED_KEEP_MS || at > nowMs + DELIVERED_KEEP_MS) null else part.substring(0, i) to at
        }.toMap()

    /** Entfernt einen Eintrag (erledigt, oder per Push gerade zugestellt). */
    fun without(items: List<WidgetReminder>, id: String): List<WidgetReminder> = items.filter { it.id != id }
}

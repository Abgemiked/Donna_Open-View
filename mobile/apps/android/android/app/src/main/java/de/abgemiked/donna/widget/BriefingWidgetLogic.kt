package com.yourcompany.donna.widget

import org.json.JSONObject

/** Was die Briefing-Kachel zeigt: Hauptzeile + Nebenzeile (kann leer sein). */
data class BriefingView(val headline: String, val detail: String)

/**
 * #246: reine Logik der Tagesbriefing-Kachel. Quelle `GET /briefing` (backend/app/routes/briefing.py).
 * Auf dem Homescreen (für jeden sichtbar) erscheinen NUR neutrale Nutzungszahlen: Streak, heutige Gespräche,
 * aktivste Tageszeit. Bewusst NICHT: Stimmung (`recent_mood`), der Fließtext `text` (enthält die Stimmung) und
 * Gesundheitsdaten (Schlaf/Rhythmus liefert die Route ohnehin nicht). Gecacht wird nur die gekürzte Ansicht.
 */
object BriefingWidgetLogic {
    const val BODY_MAX = 64 * 1024
    private val TIME_LABEL = mapOf("morning" to "morgens", "afternoon" to "nachmittags", "evening" to "abends", "night" to "nachts")

    fun parse(body: String?): BriefingView? {
        if (body.isNullOrBlank() || body.length > BODY_MAX) return null
        val root = try { JSONObject(body) } catch (_: Exception) { return null }
        if (!root.has("streak")) return null
        val streak = root.optInt("streak", 0).coerceIn(0, 100_000)
        val today = root.optInt("today_count", 0).coerceIn(0, 100_000)
        val total = root.optInt("total_30d", 0).coerceIn(0, 31)
        val peak = if (root.isNull("peak_time")) null else TIME_LABEL[root.optString("peak_time", "")]
        val headline = when {
            streak >= 2 -> "$streak Tage in Folge"
            streak == 1 -> "Tag 1 – guter Start"
            else -> "Schön, dass du da bist"
        }
        val parts = ArrayList<String>()
        if (today > 0) parts += if (today == 1) "heute 1 Gespräch" else "heute $today Gespräche"
        if (total > 0) parts += "$total von 30 Tagen"
        if (peak != null) parts += "aktiv meist $peak"
        val detail = parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
        return BriefingView(headline, detail)
    }

    /** Cache-Form (nur die Ansicht, keine Rohdaten). */
    fun toCache(v: BriefingView): String = JSONObject().put("h", v.headline).put("d", v.detail).toString()

    fun fromCache(s: String?): BriefingView? {
        if (s.isNullOrBlank()) return null
        val o = try { JSONObject(s) } catch (_: Exception) { return null }
        val h = WidgetFormat.clean(o.optString("h", ""), 60)
        if (h.isEmpty()) return null
        return BriefingView(h, WidgetFormat.clean(o.optString("d", ""), 120))
    }

    /** Schmal (unter 200 dp, bekannt) = nur die Hauptzeile. */
    fun compact(widthDp: Int): Boolean = widthDp in 1 until 200

    /** Abruf fällig? 30 min nach Erfolg, 5 min nach Fehlversuch (wie das Stream-Widget). */
    fun due(nowMs: Long, okAtMs: Long, triedAtMs: Long): Boolean =
        nowMs - okAtMs > 30 * 60_000L && nowMs - triedAtMs > 5 * 60_000L
}

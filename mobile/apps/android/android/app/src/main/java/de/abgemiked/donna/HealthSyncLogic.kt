package com.yourcompany.donna

import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * HealthSyncLogic — reine Entscheidungs-/Format-Logik des Health-Connect-Syncs (#182).
 * Bewusst ohne Health-Connect-/Android-Klassen, damit sie als JVM-Unit-Test prüfbar ist.
 *
 * Server-Vertrag (additiv; ein alter Server ignoriert unbekannte Felder):
 *  sleep_hours, sleep_start, sleep_end (ISO-8601 mit Offset), steps_today + steps_date,
 *  steps_yesterday + steps_yesterday_date, resting_hr, sync_key. Fehlende Werte = JSON null.
 */
object HealthSyncLogic {

    /** Schlafsitzung; awakeSeconds = Summe der Wach-Phasen innerhalb der Sitzung. */
    data class Sleep(
        val start: Instant,
        val end: Instant,
        val awakeSeconds: Long = 0,
        val origin: String? = null,
    )

    data class Snapshot(
        val sleep: Sleep?,
        val stepsToday: Long?,
        val stepsYesterday: Long?,
        val restingHr: Int?,
    )

    /** Zuletzt erfolgreich gesendeter Stand (SharedPreferences). */
    data class SyncState(val lastPushDate: String?, val lastSleepKey: String?)

    const val SLEEP_WINDOW_HOURS = 36L
    const val DIAG_WINDOW_DAYS = 7L
    private const val STALE_AFTER_DAYS = 3L

    /** Ab dieser Dauer gilt eine Sitzung als Hauptschlaf (kürzere = Nickerchen). */
    private const val MAIN_SLEEP_MIN_SECONDS = 3 * 3600L

    /**
     * Jüngste bereits beendete Hauptschlaf-Sitzung (>= 3 h) im Fenster; gibt es nur kürzere,
     * die längste davon. Nach Ende statt nach Dauer, damit die neue Nacht die gestrige ablöst.
     */
    fun pickMainSleep(sessions: List<Sleep>, now: Instant, windowHours: Long = SLEEP_WINDOW_HOURS): Sleep? {
        val from = now.minus(Duration.ofHours(windowHours))
        val valid = sessions.filter { !it.end.isAfter(now) && it.end.isAfter(from) && it.end.isAfter(it.start) }
        val main = valid.filter { Duration.between(it.start, it.end).seconds >= MAIN_SLEEP_MIN_SECONDS }
        return if (main.isNotEmpty()) main.maxByOrNull { it.end }
        else valid.maxByOrNull { Duration.between(it.start, it.end).seconds }
    }

    /** Schlafdauer ohne Wachphasen in Stunden (2 Nachkommastellen) — null wenn nicht ableitbar. */
    fun sleepHours(s: Sleep?): Double? {
        if (s == null) return null
        val secs = Duration.between(s.start, s.end).seconds - s.awakeSeconds
        if (secs <= 0) return null
        return Math.round(secs / 3600.0 * 100.0) / 100.0
    }

    /**
     * Idempotenz-Schlüssel je Sitzung: Start | Ende (UTC-Epoch-Sekunden, zonenunabhängig) —
     * eine nachträglich aktualisierte Sitzung (anderes Ende) wird genau einmal erneut gesendet.
     */
    @Suppress("UNUSED_PARAMETER")
    fun sleepKey(s: Sleep, zone: ZoneId): String = "${s.start.epochSecond}|${s.end.epochSecond}"

    /**
     * Push höchstens 1× je Kalendertag und 1× je neuer Schlafsitzung:
     * erster Kontakt des Tages ODER eine noch nicht gesendete Sitzung.
     */
    fun shouldPush(state: SyncState, sleepKey: String?, today: String): Boolean {
        if (state.lastPushDate != today) return true
        return sleepKey != null && sleepKey != state.lastSleepKey
    }

    fun buildPayload(snap: Snapshot, zone: ZoneId, today: String): JSONObject {
        val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val yesterday = java.time.LocalDate.parse(today).minusDays(1).toString()
        val hours = sleepHours(snap.sleep)
        return JSONObject().apply {
            put("sleep_hours", hours ?: JSONObject.NULL)
            put("sleep_start", snap.sleep?.start?.atZone(zone)?.format(fmt) ?: JSONObject.NULL)
            put("sleep_end", snap.sleep?.end?.atZone(zone)?.format(fmt) ?: JSONObject.NULL)
            put("steps_today", snap.stepsToday ?: JSONObject.NULL)
            put("steps_date", today)
            put("steps_yesterday", snap.stepsYesterday ?: JSONObject.NULL)
            put("steps_yesterday_date", yesterday)
            // Ruhepuls nur wenn echt gemessen (0 = fehlend)
            put("resting_hr", snap.restingHr?.takeIf { it > 0 } ?: JSONObject.NULL)
            put("sync_key", snap.sleep?.let { sleepKey(it, zone) } ?: "day:$today")
        }
    }

    /**
     * Ehrliche Meldung für die Einstellungen; null = alles in Ordnung (frischer Schlaf vorhanden).
     * [sessions] = Schlafsitzungen der letzten [DIAG_WINDOW_DAYS] Tage.
     */
    fun diagnosis(sessions: List<Sleep>, now: Instant, zone: ZoneId): String? {
        val last = sessions.maxByOrNull { it.end }
        if (last == null) {
            return "Keine Schlafdaten in Health Connect. Prüfe in Samsung Health → Einstellungen → " +
                "Health Connect, ob Schlaf übertragen wird (falls du Uhr/Ring getragen hast)."
        }
        if (last.end.isBefore(now.minus(Duration.ofDays(STALE_AFTER_DAYS)))) {
            val d = last.end.atZone(zone).toLocalDate()
            val datum = "%02d.%02d.".format(d.dayOfMonth, d.monthValue)
            return "Der letzte Schlaf in Health Connect ist vom $datum. Falls du Uhr/Ring getragen hast: " +
                "Samsung Health → Einstellungen → Health Connect prüfen."
        }
        return null
    }
}

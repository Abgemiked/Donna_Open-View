package com.yourcompany.donna.calendaredit

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * #147 Paket H — Termine ändern, umbenennen, löschen: REINE Logik (kein Android-Aufruf, kein Kalenderzugriff).
 * Alles Kalenderbezogene läuft über [CalendarGateway]; im Test steht dort ein Fake mit erfundenen Terminen.
 *
 * Grundsätze (fail-closed): nur eigene Termine (Organisator = Mike bzw. eigener Kalender mit Schreibrecht), nie die
 * Termine anderer; bei mehreren Treffern eine Auswahl statt Raten; Serien nur "dieser Termin", die ganze Serie nur nach
 * einer zweiten, ausdrücklichen Bestätigung; Löschen ist nicht rückholbar; Werte (Titel, Tag, Uhrzeit) kommen aus der
 * Anfrage des Servers (= aus Mikes eigenen Worten), nie aus dem Kalender; vor dem Schreiben wird alles erneut gelesen
 * und mit dem verglichen, was die Karte gezeigt hat.
 */

enum class EditOp(val key: String) {
    MOVE("move"), RENAME("rename"), DELETE("delete");

    companion object {
        fun fromKey(key: String?): EditOp? = values().firstOrNull { it.key == key }
    }
}

data class EditRequest(
    val op: EditOp,
    val query: List<String>,
    val onDate: LocalDate?,
    val onTime: LocalTime?,
    val newDate: LocalDate?,
    val newTime: LocalTime?,
    val newTitle: String?,
    val seriesMentioned: Boolean,
    /** Mike nannte den Termin "ganztägig": es zählen nur ganztägige Termine (Filter). */
    val fromAllDay: Boolean = false,
)

object EditRequestParser {
    const val CARD_TYPE = "calendar_change"
    private const val MAX_TOKENS = 6
    private const val MAX_TITLE = 100
    private val TOKEN = Regex("^[a-z0-9]{3,40}$")

    /** Strenge Prüfung der Karte vom Server. Unbekannte Felder, falsche Typen, fehlende Werte -> null (nichts passiert). */
    fun parse(json: JSONObject): EditRequest? {
        val allowed = setOf(
            "type", "op", "query", "on_date", "on_time", "new_date", "new_time", "new_title", "series_mentioned", "from_all_day",
            "action_id", "card_id", "card_state", "card_session",
        )
        if (json.keys().asSequence().any { it !in allowed }) return null
        if (json.optString("type") != CARD_TYPE) return null
        val op = EditOp.fromKey(json.optString("op")) ?: return null
        val arr = json.optJSONArray("query") ?: return null
        if (arr.length() !in 1..MAX_TOKENS) return null
        val query = (0 until arr.length()).map { arr.opt(it) as? String ?: return null }
        if (query.any { !TOKEN.matches(it) }) return null
        fun date(k: String): LocalDate? = if (json.isNull(k) || !json.has(k)) null
        else runCatching { LocalDate.parse(json.getString(k)) }.getOrNull()
        fun time(k: String): LocalTime? = if (json.isNull(k) || !json.has(k)) null
        else runCatching { LocalTime.parse(json.getString(k), DateTimeFormatter.ofPattern("HH:mm")) }.getOrNull()
        // vorhandene, aber ungültige Werte -> ablehnen (nie still ignorieren)
        for (k in listOf("on_date", "new_date")) if (json.has(k) && date(k) == null) return null
        for (k in listOf("on_time", "new_time")) if (json.has(k) && time(k) == null) return null
        val title = if (json.has("new_title")) (json.opt("new_title") as? String)?.trim() ?: return null else null
        if (title != null && (title.isEmpty() || title.length > MAX_TITLE || title.any { it.isISOControl() })) return null
        val req = EditRequest(
            op, query, date("on_date"), time("on_time"), date("new_date"), time("new_time"), title,
            json.optBoolean("series_mentioned", false), json.optBoolean("from_all_day", false),
        )
        // Pflichtwerte je Aktionsart; nicht passende Werte -> ablehnen
        return when (op) {
            EditOp.MOVE -> req.takeIf { (it.newDate != null || it.newTime != null) && it.newTitle == null }
            EditOp.RENAME -> req.takeIf { it.newTitle != null && it.newDate == null && it.newTime == null }
            EditOp.DELETE -> req.takeIf { it.newTitle == null && it.newDate == null && it.newTime == null }
        }
    }
}

/** Ein Termin (genauer: ein Vorkommen) aus dem Kalender des Geräts. Nur lesen; nie loggen. */
data class CalEvent(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val beginMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val organizer: String?,
    val isOrganizer: Boolean?,          // Events.IS_ORGANIZER, null = unbekannt
    val calendarOwner: String?,         // Calendars.OWNER_ACCOUNT
    val calendarAccount: String?,       // Calendars.ACCOUNT_NAME
    val accessLevel: Int,               // Calendars.CALENDAR_ACCESS_LEVEL
    val recurring: Boolean,             // Teil einer Serie
    val exception: Boolean = false,     // eigene Ausnahme-Zeile einer Serie (ORIGINAL_ID gesetzt): nie 'ganze Serie'
)

interface CalendarGateway {
    fun hasPermission(): Boolean
    /** Vorkommen im Zeitfenster [fromMs, toMs); sichtbare, am Gerät synchronisierte Kalender. */
    fun instances(fromMs: Long, toMs: Long): List<CalEvent>
    /** Aktueller Stand genau dieses Vorkommens (oder null, wenn es nicht mehr existiert). */
    fun load(eventId: Long, beginMs: Long): CalEvent?
    /** Anzahl sichtbarer und synchronisierter Kalender (nur für die ehrliche Meldung bei "nicht gefunden"). */
    fun syncedCalendarCount(): Int
    /** [allDay] nur beim Umwandeln ganztägig -> mit Uhrzeit (false) gesetzt, sonst null (unverändert). */
    fun updateEvent(eventId: Long, beginMs: Long?, endMs: Long?, title: String?, tz: String?, allDay: Boolean? = null): Boolean
    /** Nur dieses Vorkommen einer Serie ändern (Ausnahme); [origBeginMs] ist der Beginn des Vorkommens. */
    fun changeInstance(eventId: Long, origBeginMs: Long, beginMs: Long?, endMs: Long?, title: String?): Boolean
    fun cancelInstance(eventId: Long, origBeginMs: Long): Boolean
    fun deleteEvent(eventId: Long): Boolean
}

object EditAccess {
    const val CAL_ACCESS_EDITOR = 600

    /**
     * "Eigener Termin": Schreibrecht (Editor/Besitzer) UND Mike ist Organisator. Ist Mike nicht Organisator (Einladung
     * von jemand anderem) oder unbekannt, wer es ist -> nein. Ohne Organisator-Angabe (lokal erstellt) zählt nur ein
     * Kalender, der Mike gehört (Besitzer-Konto vorhanden).
     */
    fun isOwn(e: CalEvent): Boolean {
        if (e.accessLevel < CAL_ACCESS_EDITOR) return false
        if (e.isOrganizer == false) return false   // ausdrücklich nicht Organisator: nie, auch nicht per Namensvergleich
        // IS_ORGANIZER rechnet der Provider gegen den KALENDERBESITZER, nicht gegen Mikes Konto: in geteilten,
        // delegierten und Gruppenkalendern ist er oft 1. Darum gilt IMMER zuerst: nur im EIGENEN Konto-Kalender.
        val account = e.calendarAccount?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val owner = e.calendarOwner?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (account.isEmpty() || owner != account) return false
        if (e.isOrganizer == true) return true
        val org = e.organizer?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return org.isEmpty() || org == account
    }
}

object Fold {
    fun of(s: String): String {
        val low = s.lowercase(Locale.ROOT)
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        return low.replace(Regex("[^a-z0-9]+"), " ").trim()
    }
}

enum class RefusalReason {
    FOREIGN, NO_PERMISSION, NO_CHANGE, PAST, NONEXISTENT_TIME, AMBIGUOUS_TIME, ALL_DAY_TIME, SERIES_MOVE, TOO_MANY, CHANGED, GONE, FAILED,
}

data class Summary(val title: String, val beginMs: Long, val endMs: Long, val allDay: Boolean, val recurring: Boolean)

data class Candidate(
    val eventId: Long,
    val beginMs: Long,
    val title: String,
    val allDay: Boolean,
    val own: Boolean,           // nur eigene sind wählbar; fremde stehen zur Klarheit in der Liste
    val recurring: Boolean,
)

data class Plan(
    val op: EditOp,
    val eventId: Long,
    val before: Summary,
    val newTitle: String?,
    val newBeginMs: Long?,
    val newEndMs: Long?,
    val recurring: Boolean,
    /** Serie nur auf ausdrückliche zweite Bestätigung; Verschieben der ganzen Serie gibt es nicht. */
    val seriesAllowed: Boolean,
    val irreversible: Boolean,
    /** Ganztägiger Termin bekommt eine Uhrzeit (wird zum Termin mit Zeit, Dauer 1 Stunde). */
    val toTimed: Boolean = false,
)

sealed class PlanResult {
    object NeedsPermission : PlanResult()
    data class NoMatch(val syncedCalendars: Int) : PlanResult()
    data class Choose(val candidates: List<Candidate>) : PlanResult()
    data class Ready(val plan: Plan) : PlanResult()
    data class Refused(val reason: RefusalReason) : PlanResult()
}

enum class Scope { INSTANCE, SERIES }

enum class ApplyOutcome { OK, NEEDS_PERMISSION, REFUSED, FAILED }

data class ApplyResult(val outcome: ApplyOutcome, val refusal: RefusalReason? = null)

class CalendarEditService(
    private val gateway: CalendarGateway,
    private val zone: ZoneId,
    private val nowMs: () -> Long,
) {
    companion object {
        const val WINDOW_PAST_DAYS = 1L
        const val WINDOW_FUTURE_DAYS = 366L
        const val MAX_CANDIDATES = 5
        const val MAX_MATCHES_SCAN = 40
        const val DEFAULT_TIMED_MS = 3_600_000L
    }

    private fun matches(e: CalEvent, req: EditRequest): Boolean {
        val title = Fold.of(e.title)
        // Suchbegriffe nur als Wortanfang ("zahnarzt" trifft "Zahnarzt Kontrolle", "tom" nicht "Automesse")
        val words = title.split(' ')
        if (title.isEmpty() || req.query.any { q -> words.none { it.startsWith(q) } }) return false
        if (req.fromAllDay && !e.allDay) return false
        val begin = Instant.ofEpochMilli(e.beginMs)
        if (req.onDate != null) {
            val d = if (e.allDay) begin.atZone(ZoneOffset.UTC).toLocalDate() else begin.atZone(zone).toLocalDate()
            if (d != req.onDate) return false
        }
        if (req.onTime != null) {
            if (e.allDay) return false
            val t = begin.atZone(zone).toLocalTime().withSecond(0).withNano(0)
            if (t != req.onTime) return false
        }
        return true
    }

    fun plan(req: EditRequest): PlanResult {
        if (!gateway.hasPermission()) return PlanResult.NeedsPermission
        val now = nowMs()
        val from = now - WINDOW_PAST_DAYS * 86_400_000L
        val to = now + WINDOW_FUTURE_DAYS * 86_400_000L
        val sorted = gateway.instances(from, to).filter { matches(it, req) }
            .distinctBy { it.eventId to it.beginMs }.sortedBy { it.beginMs }
        // ohne Tag/Uhrzeit: je Serie nur das nächste Vorkommen (sonst sprengen tägliche Serien die Trefferliste)
        val hits = if (req.onDate == null && req.onTime == null) {
            sorted.filter { !it.recurring }.plus(sorted.filter { it.recurring }.distinctBy { it.eventId }).sortedBy { it.beginMs }
        } else sorted
        if (hits.isEmpty()) return PlanResult.NoMatch(gateway.syncedCalendarCount())
        if (hits.size > MAX_MATCHES_SCAN) return PlanResult.Refused(RefusalReason.TOO_MANY)
        if (hits.size == 1) return planFor(hits[0], req)
        // mehrere Treffer (auch fremde): Auswahlkarte, nie raten; fremde sind nicht wählbar
        if (hits.size > MAX_CANDIDATES) return PlanResult.Refused(RefusalReason.TOO_MANY)
        return PlanResult.Choose(
            hits.map { Candidate(it.eventId, it.beginMs, it.title, it.allDay, EditAccess.isOwn(it), it.recurring) },
        )
    }

    /** Nach der Wahl in der Auswahlkarte: derselbe Weg wie bei einem eindeutigen Treffer (frisch gelesen, geprüft). */
    fun planSelected(req: EditRequest, eventId: Long, beginMs: Long): PlanResult {
        if (!gateway.hasPermission()) return PlanResult.NeedsPermission
        val e = gateway.load(eventId, beginMs) ?: return PlanResult.Refused(RefusalReason.GONE)
        if (!matches(e, req)) return PlanResult.Refused(RefusalReason.CHANGED)
        return planFor(e, req)
    }

    private fun planFor(e: CalEvent, req: EditRequest): PlanResult {
        if (!EditAccess.isOwn(e)) return PlanResult.Refused(RefusalReason.FOREIGN)
        val before = Summary(e.title, e.beginMs, e.endMs, e.allDay, e.recurring)
        return when (req.op) {
            EditOp.DELETE -> PlanResult.Ready(
                Plan(req.op, e.eventId, before, null, null, null, e.recurring, seriesAllowed = e.recurring && !e.exception, irreversible = true),
            )
            EditOp.RENAME -> {
                if (req.newTitle == e.title) return PlanResult.Refused(RefusalReason.NO_CHANGE)
                PlanResult.Ready(Plan(req.op, e.eventId, before, req.newTitle, null, null, e.recurring, e.recurring && !e.exception, false))
            }
            EditOp.MOVE -> planMove(e, req, before)
        }
    }

    /** Ganztägig: nur der Tag zählt (Beginn ist 00:00 UTC), sonst wäre "heute" immer vergangen. */
    private fun isPast(beginMs: Long, allDay: Boolean): Boolean =
        if (allDay) Instant.ofEpochMilli(beginMs).atZone(ZoneOffset.UTC).toLocalDate() < Instant.ofEpochMilli(nowMs()).atZone(zone).toLocalDate()
        else beginMs < nowMs()

    private fun planMove(e: CalEvent, req: EditRequest, before: Summary): PlanResult {
        var toTimed = false
        val (newBegin, newEnd) = if (e.allDay && req.newTime == null) {
            val oldDate = Instant.ofEpochMilli(e.beginMs).atZone(ZoneOffset.UTC).toLocalDate()
            val target = req.newDate ?: return PlanResult.Refused(RefusalReason.NO_CHANGE)
            val shift = target.toEpochDay() - oldDate.toEpochDay()
            if (shift == 0L) return PlanResult.Refused(RefusalReason.NO_CHANGE)
            (e.beginMs + shift * 86_400_000L) to (e.endMs + shift * 86_400_000L)
        } else {
            // Ganztägig + Uhrzeit: nur für Einzeltermine (eine Serie bekommt keine Uhrzeit pro Vorkommen)
            if (e.allDay && e.recurring) return PlanResult.Refused(RefusalReason.ALL_DAY_TIME)
            toTimed = e.allDay
            val oldDate = if (e.allDay) Instant.ofEpochMilli(e.beginMs).atZone(ZoneOffset.UTC).toLocalDate()
            else Instant.ofEpochMilli(e.beginMs).atZone(zone).toLocalDate()
            val old = Instant.ofEpochMilli(e.beginMs).atZone(zone)
            val date = req.newDate ?: oldDate
            val time = req.newTime ?: old.toLocalTime().withSecond(0).withNano(0)
            val target = ZonedDateTime.of(date, time, zone)
            // Zeitumstellung: gibt es die Wanduhrzeit nicht (Lücke), verschiebt Java sie -> nie raten, ablehnen
            if (target.toLocalDate() != date || target.toLocalTime() != time) {
                return PlanResult.Refused(RefusalReason.NONEXISTENT_TIME)
            }
            if (zone.rules.getValidOffsets(java.time.LocalDateTime.of(date, time)).size > 1) {
                return PlanResult.Refused(RefusalReason.AMBIGUOUS_TIME)   // Herbst: Stunde gibt es zweimal
            }
            val duration = if (toTimed) DEFAULT_TIMED_MS else e.endMs - e.beginMs
            target.toInstant().toEpochMilli() to (target.toInstant().toEpochMilli() + duration)
        }
        if (newBegin == e.beginMs && !toTimed) return PlanResult.Refused(RefusalReason.NO_CHANGE)
        if (isPast(newBegin, e.allDay && !toTimed)) return PlanResult.Refused(RefusalReason.PAST)
        return PlanResult.Ready(Plan(EditOp.MOVE, e.eventId, before, null, newBegin, newEnd, e.recurring, seriesAllowed = false, irreversible = false, toTimed = toTimed))
    }

    /**
     * Schreibt NACH Mikes Tipp. Liest alles frisch; weicht der Termin von dem ab, was die Karte gezeigt hat (Beginn, Titel),
     * wird nichts geschrieben. [scope] SERIES nur, wenn der Plan es erlaubt UND [seriesConfirmed] (zweite Bestätigung).
     */
    fun apply(plan: Plan, scope: Scope, seriesConfirmed: Boolean): ApplyResult {
        if (!gateway.hasPermission()) return ApplyResult(ApplyOutcome.NEEDS_PERMISSION)
        val e = gateway.load(plan.eventId, plan.before.beginMs) ?: return refused(RefusalReason.GONE)
        if (e.title != plan.before.title || e.beginMs != plan.before.beginMs || e.endMs != plan.before.endMs) {
            return refused(RefusalReason.CHANGED)
        }
        if (!EditAccess.isOwn(e)) return refused(RefusalReason.FOREIGN)
        if (plan.newBeginMs != null && isPast(plan.newBeginMs, e.allDay && !plan.toTimed)) return refused(RefusalReason.PAST)
        val series = scope == Scope.SERIES
        if (series && (!e.recurring || e.exception || !plan.seriesAllowed || !seriesConfirmed)) return refused(RefusalReason.SERIES_MOVE)
        val ok = try {
            when (plan.op) {
                EditOp.DELETE -> when {
                    series -> gateway.deleteEvent(e.eventId)
                    e.recurring -> gateway.cancelInstance(e.eventId, e.beginMs)
                    else -> gateway.deleteEvent(e.eventId)
                }
                EditOp.RENAME -> if (series || !e.recurring) {
                    gateway.updateEvent(e.eventId, null, null, plan.newTitle, null)
                } else {
                    gateway.changeInstance(e.eventId, e.beginMs, null, null, plan.newTitle)
                }
                EditOp.MOVE -> if (series) return refused(RefusalReason.SERIES_MOVE)
                else if (e.recurring) gateway.changeInstance(e.eventId, e.beginMs, plan.newBeginMs, plan.newEndMs, null)
                else if (plan.toTimed) gateway.updateEvent(e.eventId, plan.newBeginMs, plan.newEndMs, null, zone.id, allDay = false)
                else gateway.updateEvent(e.eventId, plan.newBeginMs, plan.newEndMs, null, null)
            }
        } catch (_: SecurityException) {
            return ApplyResult(ApplyOutcome.NEEDS_PERMISSION)
        } catch (_: Exception) {
            false
        }
        return if (ok) ApplyResult(ApplyOutcome.OK) else ApplyResult(ApplyOutcome.FAILED, RefusalReason.FAILED)
    }

    /**
     * Der Weg nach dem Tipp: Plan NEU aus der Anfrage und dem frisch gelesenen Termin bauen (nichts vom Bildschirm
     * wird als Wahrheit übernommen) und mit dem vergleichen, was die Karte gezeigt hat ([expected]).
     */
    fun applyFromRequest(
        req: EditRequest, eventId: Long, beginMs: Long, expected: Summary, scope: Scope, seriesConfirmed: Boolean,
    ): ApplyResult {
        return when (val p = planSelected(req, eventId, beginMs)) {
            PlanResult.NeedsPermission -> ApplyResult(ApplyOutcome.NEEDS_PERMISSION)
            is PlanResult.Refused -> refused(p.reason)
            is PlanResult.Ready -> {
                val b = p.plan.before
                if (b.title != expected.title || b.beginMs != expected.beginMs || b.endMs != expected.endMs) {
                    refused(RefusalReason.CHANGED)
                } else {
                    apply(p.plan, scope, seriesConfirmed)
                }
            }
            else -> refused(RefusalReason.CHANGED)
        }
    }

    private fun refused(r: RefusalReason) = ApplyResult(ApplyOutcome.REFUSED, r)
}

/** Feste deutsche Texte (keine Termindaten außer dem, was die Karte ohnehin dem Nutzer zeigt). */
object CalendarEditMessages {
    private val DAY = DateTimeFormatter.ofPattern("EEE, d. MMM yyyy", Locale.GERMAN)
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

    fun when_(beginMs: Long, endMs: Long, allDay: Boolean, zone: ZoneId): String {
        if (allDay) {
            val d = Instant.ofEpochMilli(beginMs).atZone(ZoneOffset.UTC).toLocalDate()
            return "${DAY.format(d)}, ganztägig"
        }
        val b = Instant.ofEpochMilli(beginMs).atZone(zone)
        val e = Instant.ofEpochMilli(endMs).atZone(zone)
        return "${DAY.format(b)}, ${CLOCK.format(b)}–${CLOCK.format(e)} Uhr"
    }

    fun refusal(r: RefusalReason): String = when (r) {
        RefusalReason.FOREIGN -> "Das ist kein Termin von dir (jemand anderes hat ihn angelegt oder du darfst ihn nicht ändern). Den ändere ich nicht."
        RefusalReason.NO_PERMISSION -> "Dafür fehlt Donna der Kalender-Zugriff."
        RefusalReason.NO_CHANGE -> "Der Termin ist schon so."
        RefusalReason.PAST -> "Das Ziel liegt in der Vergangenheit — das ändere ich nicht."
        RefusalReason.NONEXISTENT_TIME -> "Diese Uhrzeit gibt es an dem Tag nicht (Zeitumstellung). Sag mir eine andere."
        RefusalReason.AMBIGUOUS_TIME -> "Diese Uhrzeit gibt es an dem Tag zweimal (Zeitumstellung). Sag mir eine andere."
        RefusalReason.ALL_DAY_TIME -> "Das ist ein ganztägiger Termin — eine Uhrzeit setze ich dort nicht."
        RefusalReason.SERIES_MOVE -> "Die ganze Serie verschiebe ich nicht — nur diesen einen Termin."
        RefusalReason.TOO_MANY -> "Dazu passen zu viele Termine. Sag mir den Titel genauer."
        RefusalReason.CHANGED -> "Der Termin hat sich inzwischen geändert — ich habe nichts geändert. Sag es noch einmal."
        RefusalReason.GONE -> "Den Termin gibt es nicht mehr — ich habe nichts geändert."
        RefusalReason.FAILED -> "Das hat nicht geklappt — ich habe nichts bestätigt."
    }

    fun noMatch(syncedCalendars: Int): String =
        "Ich finde dazu keinen Termin in den Kalendern, die am Handy synchronisiert sind ($syncedCalendars)."

    fun needsPermission(): String =
        "Dafür braucht Donna die Berechtigung „Kalender“ (lesen und schreiben). Du kannst sie in den Einstellungen unter „Donna darf selbst …“ erteilen."

    fun deleteWarning(): String = "Löschen kann Donna nicht rückgängig machen."

    fun done(op: EditOp, series: Boolean): String = when (op) {
        EditOp.MOVE -> "Termin verschoben."
        EditOp.RENAME -> if (series) "Serie umbenannt." else "Termin umbenannt."
        EditOp.DELETE -> if (series) "Serie gelöscht." else "Termin gelöscht."
    }
}

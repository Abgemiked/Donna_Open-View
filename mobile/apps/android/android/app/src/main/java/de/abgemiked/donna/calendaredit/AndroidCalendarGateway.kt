package com.yourcompany.donna.calendaredit

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import android.provider.CalendarContract.Events

/**
 * #147 Paket H — echter Kalenderzugriff (ContentResolver, Laufzeitrecht READ_CALENDAR + WRITE_CALENDAR). Nur Kalender,
 * die am Gerät sichtbar und synchronisiert sind. Logik und Entscheidungen stehen in [CalendarEditService]; hier wird nur
 * gelesen/geschrieben. Nie Inhalte loggen. Die Schreibwege für Serien-Ausnahmen sind am Gerät zu prüfen (Abnahme).
 */
class AndroidCalendarGateway(private val context: Context) : CalendarGateway {
    private val cr: ContentResolver get() = context.contentResolver

    override fun hasPermission(): Boolean =
        listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private data class Cal(val owner: String?, val account: String?, val access: Int)

    private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)

    private fun calendars(): Map<Long, Cal> {
        val out = HashMap<Long, Cal>()
        val proj = arrayOf(
            CalendarContract.Calendars._ID, CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        cr.query(
            CalendarContract.Calendars.CONTENT_URI, proj,
            "${CalendarContract.Calendars.VISIBLE}=1 AND ${CalendarContract.Calendars.SYNC_EVENTS}=1", null, null,
        )?.use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = Cal(c.str(1), c.str(2), c.getInt(3))
        }
        return out
    }

    override fun syncedCalendarCount(): Int = try { calendars().size } catch (_: Exception) { 0 }

    private val instProj = arrayOf(
        CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
        Events.TITLE, Events.ALL_DAY, Events.ORGANIZER, Events.CALENDAR_ID, Events.RRULE, Events.RDATE,
        Events.ORIGINAL_ID, Events.IS_ORGANIZER,
    )

    override fun instances(fromMs: Long, toMs: Long): List<CalEvent> {
        val cals = calendars()
        val out = ArrayList<CalEvent>()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, fromMs)
            ContentUris.appendId(it, toMs)
        }.build()
        cr.query(uri, instProj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val cal = cals[c.getLong(6)] ?: continue
                val recurring = !c.str(7).isNullOrBlank() || !c.str(8).isNullOrBlank() || !c.isNull(9)
                out += CalEvent(
                    eventId = c.getLong(0), calendarId = c.getLong(6), title = c.str(3).orEmpty(),
                    beginMs = c.getLong(1), endMs = c.getLong(2), allDay = c.getInt(4) == 1,
                    organizer = c.str(5), isOrganizer = if (c.isNull(10)) null else c.getInt(10) == 1,
                    calendarOwner = cal.owner, calendarAccount = cal.account, accessLevel = cal.access,
                    recurring = recurring, exception = !c.isNull(9),
                )
            }
        }
        return out
    }

    override fun load(eventId: Long, beginMs: Long): CalEvent? =
        instances(beginMs, beginMs + 1).firstOrNull { it.eventId == eventId && it.beginMs == beginMs }

    private fun originalId(eventId: Long): Long? =
        cr.query(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), arrayOf(Events.ORIGINAL_ID), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    private fun masterTimezone(eventId: Long): String? =
        cr.query(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), arrayOf(Events.EVENT_TIMEZONE), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.str(0) else null }

    override fun updateEvent(eventId: Long, beginMs: Long?, endMs: Long?, title: String?, tz: String?, allDay: Boolean?): Boolean {
        val v = ContentValues()
        if (allDay != null) v.put(Events.ALL_DAY, if (allDay) 1 else 0)
        if (beginMs != null) v.put(Events.DTSTART, beginMs)
        if (endMs != null) v.put(Events.DTEND, endMs)
        if (title != null) v.put(Events.TITLE, title)
        if (tz != null) v.put(Events.EVENT_TIMEZONE, tz)
        if (v.size() == 0) return false
        return cr.update(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), v, null, null) == 1
    }

    override fun changeInstance(eventId: Long, origBeginMs: Long, beginMs: Long?, endMs: Long?, title: String?): Boolean {
        // ein Vorkommen, das schon eine Ausnahme ist, hat eine eigene Zeile: direkt ändern
        if (originalId(eventId) != null) return updateEvent(eventId, beginMs, endMs, title, null)
        val cur = load(eventId, origBeginMs) ?: return false
        val v = ContentValues().apply {
            put(Events.ORIGINAL_INSTANCE_TIME, origBeginMs)
            put(Events.TITLE, title ?: cur.title)
            put(Events.DTSTART, beginMs ?: cur.beginMs)
            put(Events.DTEND, endMs ?: cur.endMs)
            put(Events.ALL_DAY, if (cur.allDay) 1 else 0)
            // Zeitzone des Masters übernehmen (Ganztägige: UTC); ohne sie rechnet der Provider falsch
            put(Events.EVENT_TIMEZONE, masterTimezone(eventId) ?: if (cur.allDay) "UTC" else java.util.TimeZone.getDefault().id)
            put(Events.ORIGINAL_ALL_DAY, if (cur.allDay) 1 else 0)
        }
        return cr.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, eventId), v) != null
    }

    override fun cancelInstance(eventId: Long, origBeginMs: Long): Boolean {
        if (originalId(eventId) != null) {
            val v = ContentValues().apply { put(Events.STATUS, Events.STATUS_CANCELED) }
            return cr.update(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), v, null, null) == 1
        }
        val v = ContentValues().apply {
            put(Events.ORIGINAL_INSTANCE_TIME, origBeginMs)
            put(Events.STATUS, Events.STATUS_CANCELED)
        }
        return cr.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, eventId), v) != null
    }

    override fun deleteEvent(eventId: Long): Boolean =
        cr.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null) == 1
}

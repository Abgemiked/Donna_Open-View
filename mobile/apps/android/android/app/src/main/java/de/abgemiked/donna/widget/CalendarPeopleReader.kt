package com.yourcompany.donna.widget

import android.content.Context
import android.database.Cursor
import android.provider.CalendarContract
import android.util.Log
import com.yourcompany.donna.calendaredit.CalEvent

/**
 * #243: liest für das Termine-Widget, wer Termine erstellt hat und wer beteiligt ist (nur READ_CALENDAR), und bildet
 * daraus über [CalendarPeopleLogic] den Personen-Tag. Adressen werden nur im Speicher abgeglichen — in den Widget-Cache
 * gelangt nur der fertige Tag mit Namen. Nie Inhalte loggen.
 */
object CalendarPeopleReader {
    private const val TAG = "CalendarPeople"
    private const val MAX_EVENTS = 60

    /** Einstellung (optional): Adresse und Anzeigename der Partnerin; ohne Adresse wird ein geteilter Kalender erkannt. */
    const val PREF_PARTNER_EMAIL = "partner_calendar_email"
    const val PREF_PARTNER_NAME = "partner_calendar_name"

    private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)

    /** Tag je Termin-Kennung; bei jedem Fehler leer (Widget zeigt dann einfach keinen Tag). */
    fun tags(ctx: Context, events: List<CalEvent>): Map<Long, String> = try {
        val cr = ctx.contentResolver
        val accounts = ArrayList<String?>()
        val owners = ArrayList<String?>()
        cr.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.OWNER_ACCOUNT),
            "${CalendarContract.Calendars.VISIBLE}=1", null, null,
        )?.use { c -> while (c.moveToNext()) { accounts += c.str(0); owners += c.str(1) } }
        val mike = CalendarPeopleLogic.mikeAddresses(accounts)
        val partnerEmail = WidgetCache.str(ctx, PREF_PARTNER_EMAIL)?.takeIf { it.isNotBlank() }
            ?: CalendarPeopleLogic.detectPartner(owners, mike)
        val partnerName = CalendarPeopleLogic.displayName(WidgetCache.str(ctx, PREF_PARTNER_NAME))

        val ids = events.map { it.eventId }.distinct().take(MAX_EVENTS)
        val attendees = HashMap<Long, MutableList<Attendee>>()
        if (ids.isNotEmpty()) {
            cr.query(
                CalendarContract.Attendees.CONTENT_URI,
                arrayOf(CalendarContract.Attendees.EVENT_ID, CalendarContract.Attendees.ATTENDEE_EMAIL, CalendarContract.Attendees.ATTENDEE_NAME),
                "${CalendarContract.Attendees.EVENT_ID} IN (${ids.joinToString(",") { "?" }})",
                ids.map { it.toString() }.toTypedArray(), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val email = c.str(1) ?: continue
                    attendees.getOrPut(c.getLong(0)) { ArrayList() } += Attendee(email, c.str(2))
                }
            }
        }
        events.associate { e ->
            e.eventId to CalendarPeopleLogic.tag(
                e.organizer, e.calendarOwner, attendees[e.eventId].orEmpty(), mike, partnerEmail, partnerName,
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "Personen nicht gelesen: ${e.javaClass.simpleName}")
        emptyMap()
    }
}

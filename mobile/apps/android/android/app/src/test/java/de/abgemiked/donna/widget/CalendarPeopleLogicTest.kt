package com.yourcompany.donna.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #243: Personen-Tag im Termine-Widget (wer hat erstellt, wer ist beteiligt). Adressen sind frei erfunden. */
class CalendarPeopleLogicTest {
    private val mike = setOf("mike.test@example.org")
    private val partner = "partnerin@example.net"
    private val name = "Ämi"

    private fun tag(organizer: String?, attendees: List<String> = emptyList(), owner: String? = null, partnerEmail: String? = partner) =
        CalendarPeopleLogic.tag(organizer, owner, attendees.map { Attendee(it, null) }, mike, partnerEmail, name)

    @Test fun creatorAlone() {
        assertEquals("Mike", tag("mike.test@example.org"))
        assertEquals("Mike", tag("MIKE.TEST@example.org")) // Groß-/Kleinschreibung egal
        assertEquals("Ämi", tag(partner))
    }

    @Test fun creatorWithOtherAsAttendee() {
        assertEquals("Mike + Ämi", tag("mike.test@example.org", listOf("mike.test@example.org", partner)))
        assertEquals("Ämi + Mike", tag(partner, listOf(partner, "Mike.Test@example.org")))
    }

    @Test fun noOrganizerFallsBackToCalendarOwner() {
        assertEquals("Mike", tag(null, owner = "mike.test@example.org"))
        assertEquals("Ämi", tag("", owner = partner))
        assertEquals("Mike", tag(null, owner = null)) // lokaler Kalender ohne Angaben = Mikes Handy
        assertEquals("Mike", tag("My calendar")) // Gerätekalender ohne Adresse
        assertEquals("", tag(null, owner = "abc@group.calendar.google.com")) // Gruppenkalender ohne Organizer
    }

    @Test fun groupCalendarsHaveNoTag() {
        assertEquals("", tag("de.german#holiday@group.v.calendar.google.com"))
        assertEquals("", tag("abc123@group.calendar.google.com"))
        assertTrue(CalendarPeopleLogic.isGroupAddress("x@import.calendar.google.com"))
        assertFalse(CalendarPeopleLogic.isGroupAddress(partner))
    }

    @Test fun otherOrganizerShowsShortNameNeverEmail() {
        assertEquals("Kollege", tag("kollege.meier@firma.example"))
        val withName = CalendarPeopleLogic.tag(
            "kmeier@firma.example", null, listOf(Attendee("kmeier@firma.example", "Klaus Meier")), mike, partner, name,
        )
        assertEquals("Klaus", withName)
        assertFalse(withName.contains("@"))
    }

    @Test fun configuredNameNeverShowsAddress() {
        assertEquals("Ämi", CalendarPeopleLogic.displayName(null))
        assertEquals("Ämi", CalendarPeopleLogic.displayName("ae@example.net"))
        assertEquals("Ami", CalendarPeopleLogic.displayName("<Ami>"))
        assertEquals("K", CalendarPeopleLogic.shortName("k.meier@firma.example", "x@y.de"))
    }

    @Test fun withoutPartnerOnlyCreator() {
        assertEquals("Mike", tag("mike.test@example.org", listOf(partner), partnerEmail = null))
        assertEquals("Partnerin", tag(partner, listOf("mike.test@example.org"), partnerEmail = null))
    }

    @Test fun mikeAddressesAndPartnerDetection() {
        val accounts = listOf("mike.test@example.org", "mike.test@example.org", "Local", null)
        val me = CalendarPeopleLogic.mikeAddresses(accounts)
        assertEquals(setOf("mike.test@example.org"), me)
        // genau ein fremder Personen-Kalender (geteilter Kalender) → Partnerin erkannt
        val owners = listOf("mike.test@example.org", "Partnerin@Example.net", "de.german#holiday@group.v.calendar.google.com")
        assertEquals(partner, CalendarPeopleLogic.detectPartner(owners, me))
        // zwei fremde Personen → keine Vermutung
        assertNull(CalendarPeopleLogic.detectPartner(owners + "dritte@example.com", me))
        assertNull(CalendarPeopleLogic.detectPartner(listOf("mike.test@example.org"), me))
    }

    @Test fun cacheKeepsTag() {
        val items = listOf(WidgetEvent(1, 10L, 20L, "A", false, "Mike + Ämi"), WidgetEvent(2, 30L, 40L, "B", true))
        assertEquals(items, CalendarWidgetLogic.fromCache(CalendarWidgetLogic.toCache(items)))
    }
}

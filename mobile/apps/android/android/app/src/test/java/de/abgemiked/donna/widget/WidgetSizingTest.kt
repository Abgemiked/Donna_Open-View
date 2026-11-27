package com.yourcompany.donna.widget

import com.yourcompany.donna.idea.IdeaDictationLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** #243: Größenwahl der Widgets, große Stream-Liste, Ideen-Diktat-Entscheidungen. */
class WidgetSizingTest {
    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")

    @Test fun listCompactOnlyForOneRowHeight() {
        assertTrue(WidgetSizing.isCompactList(70))
        assertTrue(WidgetSizing.isCompactList(WidgetSizing.LIST_MIN_HEIGHT - 1))
        assertFalse(WidgetSizing.isCompactList(WidgetSizing.LIST_MIN_HEIGHT))
        assertFalse(WidgetSizing.isCompactList(0)) // unbekannt → Liste wie bisher
    }

    @Test fun listRowsGrowWithHeight() {
        assertEquals(2, WidgetSizing.listRows(WidgetSizing.LIST_MIN_HEIGHT))
        assertEquals(5, WidgetSizing.listRows(WidgetFormat.HEADER_DP + 5 * WidgetFormat.ROW_DP + 10))
        assertEquals(WidgetSizing.MAX_LIST_ROWS, WidgetSizing.listRows(2000))
    }

    @Test fun ideaHeaderByWidth() {
        assertEquals(WidgetSizing.IdeaHeader.NONE, WidgetSizing.ideaHeader(0)) // unbekannt → nur Tasten
        assertEquals(WidgetSizing.IdeaHeader.NONE, WidgetSizing.ideaHeader(150))
        assertEquals(WidgetSizing.IdeaHeader.BULB, WidgetSizing.ideaHeader(170))
        assertEquals(WidgetSizing.IdeaHeader.SHORT_TITLE, WidgetSizing.ideaHeader(240))
        assertEquals(WidgetSizing.IdeaHeader.FULL_TITLE, WidgetSizing.ideaHeader(300))
    }

    private val plan = """{"week_start":"2026-11-23","days":[
        {"day_of_week":0,"status":"stream","entries":[{"from":"19:30","to":"","activity":"Gaming"}],"note":null},
        {"day_of_week":4,"status":"stream","entries":[{"from":"14:00","to":"16:00","activity":"Hearthstone"},{"from":"19:30","to":"","activity":"Spieleabend"}],"note":null},
        {"day_of_week":5,"status":"ausfall","entries":[],"note":"Urlaub"},
        {"day_of_week":6,"status":"stream","entries":[{"from":"19:30","to":"","activity":""}],"note":null}]}"""

    @Test fun streamUpcomingListsWeekFromNow() {
        val fri15 = ZonedDateTime.of(2026, 11, 27, 15, 0, 0, 0, berlin)
        val rows = StreamWidgetLogic.upcoming(StreamWidgetLogic.parse(plan), fri15, 8)
        assertEquals(listOf("läuft", "heute 19:30", "morgen", "So 19:30"), rows.map { it.label })
        assertEquals(listOf("Hearthstone", "Spieleabend", "kein Stream (Urlaub)", "Stream"), rows.map { it.text })
        assertEquals(2, StreamWidgetLogic.upcoming(StreamWidgetLogic.parse(plan), fri15, 2).size)
    }

    @Test fun streamUpcomingCarriesLateStreamAndEndsWithWeek() {
        val fri23 = ZonedDateTime.of(2026, 11, 27, 23, 0, 0, 0, berlin) // Freitag 19:30 ohne Ende → bis 23:30
        assertEquals("läuft", StreamWidgetLogic.upcoming(StreamWidgetLogic.parse(plan), fri23, 8).first().label)
        val late = """{"week_start":"2026-11-23","days":[{"day_of_week":4,"status":"stream","entries":[{"from":"22:00","to":"","activity":"Late"}]}]}"""
        val sat0030 = ZonedDateTime.of(2026, 11, 28, 0, 30, 0, 0, berlin) // Freitag 22:00 + 4 h → läuft über Mitternacht
        assertEquals(listOf("läuft"), StreamWidgetLogic.upcoming(StreamWidgetLogic.parse(late), sat0030, 8).map { it.label })
        val nextMon = ZonedDateTime.of(2026, 11, 30, 12, 0, 0, 0, berlin)
        assertTrue(StreamWidgetLogic.upcoming(StreamWidgetLogic.parse(plan), nextMon, 8).isEmpty())
        assertTrue(StreamWidgetLogic.upcoming(null, nextMon, 8).isEmpty())
    }

    @Test fun dictationErrors() {
        assertEquals(IdeaDictationLogic.TEXT_NOTHING, IdeaDictationLogic.errorText(IdeaDictationLogic.ERROR_NO_MATCH))
        assertEquals(IdeaDictationLogic.TEXT_NOTHING, IdeaDictationLogic.errorText(IdeaDictationLogic.ERROR_SPEECH_TIMEOUT))
        assertEquals(IdeaDictationLogic.TEXT_PERMISSION, IdeaDictationLogic.errorText(IdeaDictationLogic.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals(IdeaDictationLogic.TEXT_NET, IdeaDictationLogic.errorText(IdeaDictationLogic.ERROR_NETWORK))
        assertEquals(IdeaDictationLogic.TEXT_LANGUAGE, IdeaDictationLogic.errorText(IdeaDictationLogic.ERROR_LANGUAGE_UNAVAILABLE))
        assertEquals(IdeaDictationLogic.TEXT_HICCUP, IdeaDictationLogic.errorText(99))
        assertTrue(IdeaDictationLogic.shouldRetryOffline(IdeaDictationLogic.ERROR_NETWORK_TIMEOUT, triedOffline = false))
        assertFalse(IdeaDictationLogic.shouldRetryOffline(IdeaDictationLogic.ERROR_NETWORK, triedOffline = true))
        assertFalse(IdeaDictationLogic.shouldRetryOffline(IdeaDictationLogic.ERROR_NO_MATCH, triedOffline = false))
        assertEquals("Idee", IdeaDictationLogic.salvage("  Idee "))
        assertNull(IdeaDictationLogic.salvage("   "))
    }

    @Test fun voiceStartExtra() {
        assertTrue(DonnaEntries.isVoiceStart("voice"))
        assertFalse(DonnaEntries.isVoiceStart("text"))
        assertFalse(DonnaEntries.isVoiceStart(null))
    }
}

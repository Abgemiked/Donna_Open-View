package com.yourcompany.donna.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Welle 2 (#236/#239/#240/#241): reine Logik der Daten-Widgets. */
class WidgetLogicTest {
    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")

    // Freitag, 27.11.2026, 10:00 Berlin
    private val now = ZonedDateTime.of(2026, 11, 27, 10, 0, 0, 0, berlin)
    private val nowMs = now.toInstant().toEpochMilli()
    private fun at(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, berlin).toInstant().toEpochMilli()

    // ── WidgetFormat ─────────────────────────────────────────────

    @Test fun rowsFollowHeight() {
        assertEquals(3, WidgetFormat.rowsForHeight(0, 4))
        assertEquals(1, WidgetFormat.rowsForHeight(50, 4))
        assertEquals(3, WidgetFormat.rowsForHeight(WidgetFormat.HEADER_DP + 3 * WidgetFormat.ROW_DP, 4))
        assertEquals(4, WidgetFormat.rowsForHeight(400, 4))
    }

    @Test fun whenLabelRelativeDays() {
        assertEquals("14:30", WidgetFormat.whenLabel(at(27, 14, 30), nowMs, berlin))
        assertEquals("morgen 09:05", WidgetFormat.whenLabel(at(28, 9, 5), nowMs, berlin))
        assertEquals("Mo 08:00", WidgetFormat.whenLabel(at(30, 8), nowMs, berlin))
        assertEquals("05.12. 08:00", WidgetFormat.whenLabel(ZonedDateTime.of(2026, 12, 5, 8, 0, 0, 0, berlin).toInstant().toEpochMilli(), nowMs, berlin))
    }

    @Test fun cleanStripsControlAndShortens() {
        assertEquals("a b", WidgetFormat.clean("a\n​b", 10))
        assertEquals("Spiel", WidgetFormat.clean("[Spiel]", 10, stripBrackets = true))
        assertEquals("abcd…", WidgetFormat.clean("abcdefgh", 5))
        assertEquals("", WidgetFormat.clean(null, 5))
    }

    // ── Erinnerungen ─────────────────────────────────────────────

    @Test fun reminderParseSortsAndSkipsBroken() {
        val body = """{"reminders":[
            {"id":2,"text":"Zweite","due_utc":"2026-11-27T12:00:00Z","tz":"Europe/Berlin"},
            {"id":1,"text":"Erste","due_utc":"2026-11-27T10:00:00Z","tz":"Europe/Berlin"},
            {"id":-3,"text":"kaputt","due_utc":"2026-11-27T10:00:00Z"},
            {"id":4,"text":"","due_utc":"2026-11-27T10:00:00Z"},
            {"id":5,"text":"ohne Zeit","due_utc":"gestern"}]}"""
        val list = ReminderWidgetLogic.parse(body)!!
        assertEquals(listOf("1", "2"), list.map { it.id })
        assertEquals("Erste", list[0].text)
    }

    @Test fun reminderParseBrokenEnvelopeIsNull() {
        assertNull(ReminderWidgetLogic.parse("<html>"))
        assertNull(ReminderWidgetLogic.parse("{}"))
        assertEquals(0, ReminderWidgetLogic.parse("""{"reminders":[]}""")!!.size)
    }

    @Test fun reminderVisibleFiltersAnsweredAndLimits() {
        val items = (1..6).map { WidgetReminder(it.toString(), "T$it", it * 1000L) }
        val v = ReminderWidgetLogic.visible(items, listOf("1", "3"), 3)
        assertEquals(listOf("2", "4", "5"), v.map { it.id })
    }

    @Test fun reminderCacheRoundTripAndSnooze() {
        val items = listOf(WidgetReminder("1", "A", 1000L), WidgetReminder("2", "B", 2000L))
        assertEquals(items, ReminderWidgetLogic.fromCache(ReminderWidgetLogic.toCache(items)))
        val s = ReminderWidgetLogic.snoozed(items, "1", 10, 5000L)
        assertEquals(5000L + 600_000L, s.first { it.id == "1" }.dueMs)
        assertEquals(listOf("2", "1"), ReminderWidgetLogic.visible(s, emptyList(), 4).map { it.id })
        assertEquals(listOf("2"), ReminderWidgetLogic.without(items, "1").map { it.id })
        assertTrue(ReminderWidgetLogic.fromCache("kaputt").isEmpty())
    }

    @Test fun snoozeOnlyForDueReminders() {
        // Review-Rat: "Später" darf eine künftige Erinnerung nicht auf jetzt+10 vorziehen
        assertTrue(ReminderWidgetLogic.canSnooze(WidgetReminder("1", "A", nowMs - 1), nowMs))
        assertTrue(ReminderWidgetLogic.canSnooze(WidgetReminder("1", "A", nowMs), nowMs))
        assertEquals(false, ReminderWidgetLogic.canSnooze(WidgetReminder("1", "A", at(28, 9)), nowMs))
    }

    @Test fun deliveredIdsExpire() {
        var raw = ReminderWidgetLogic.deliveredAdd(null, "7", nowMs)
        raw = ReminderWidgetLogic.deliveredAdd(raw, "8", nowMs + 60_000L)
        assertEquals(setOf("7", "8"), ReminderWidgetLogic.deliveredActive(raw, nowMs + 120_000L))
        val later = nowMs + ReminderWidgetLogic.DELIVERED_KEEP_MS + 30_000L
        assertEquals(setOf("8"), ReminderWidgetLogic.deliveredActive(raw, later))
        assertTrue(ReminderWidgetLogic.deliveredActive("kaputt,:5,x:y", nowMs).isEmpty())
    }

    @Test fun whenLabelAcrossDstAndPast() {
        // Zeitumstellung 25.10.2026 (Sommer → Winter): 24.10. 22:00 → 25.10. 09:00 ist "morgen"
        val sat = ZonedDateTime.of(2026, 10, 24, 22, 0, 0, 0, berlin).toInstant().toEpochMilli()
        val sun = ZonedDateTime.of(2026, 10, 25, 9, 0, 0, 0, berlin).toInstant().toEpochMilli()
        assertEquals("morgen 09:00", WidgetFormat.whenLabel(sun, sat, berlin))
        assertEquals("26.11. 08:00", WidgetFormat.whenLabel(at(26, 8), nowMs, berlin))
        assertTrue(WidgetFormat.isStale(0L, nowMs, 1000L))
        assertEquals(false, WidgetFormat.isStale(nowMs - 500L, nowMs, 1000L))
    }

    // ── Kalender ─────────────────────────────────────────────────

    @Test fun calendarOngoingMultiDayAndOvernight() {
        val events = listOf(
            WidgetEvent(1, at(26, 9), at(29, 18), "Messe", false),
            WidgetEvent(2, at(27, 23, 30), at(28, 1), "Nachtschicht", false),
        )
        val rows = CalendarWidgetLogic.select(events, nowMs, berlin, 4)
        assertEquals(listOf("bis So", "23:30"), rows.map { it.label })
        val nightNow = at(28, 0, 30)
        assertEquals("bis 01:00", CalendarWidgetLogic.select(events, nightNow, berlin, 4).first { it.title == "Nachtschicht" }.label)
    }

    private fun utcMidnight(day: Int) = LocalDate.of(2026, 11, day).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()

    @Test fun calendarSelectsTodayAndTomorrowOnly() {
        val events = listOf(
            WidgetEvent(1, at(27, 8), at(27, 9), "vorbei", false),
            WidgetEvent(2, at(27, 9, 30), at(27, 11), "läuft", false),
            WidgetEvent(3, at(27, 15), at(27, 16), "Nachmittag", false),
            WidgetEvent(4, at(28, 9), at(28, 10), "Morgen früh", false),
            WidgetEvent(5, at(29, 9), at(29, 10), "Übermorgen", false),
            WidgetEvent(6, utcMidnight(27), utcMidnight(28), "Feiertag", true),
            WidgetEvent(7, utcMidnight(28), utcMidnight(29), "", true),
        )
        val rows = CalendarWidgetLogic.select(events, nowMs, berlin, 10)
        assertEquals(listOf("Feiertag", "läuft", "Nachmittag", "Termin", "Morgen früh"), rows.map { it.title })
        assertEquals(listOf("ganztägig", "bis 11:00", "15:00", "morgen", "morgen 09:00"), rows.map { it.label })
        assertEquals(2, CalendarWidgetLogic.select(events, nowMs, berlin, 2).size)
    }

    @Test fun calendarMultiDayAllDayCoversToday() {
        val e = WidgetEvent(9, utcMidnight(25), utcMidnight(30), "Urlaub", true)
        val rows = CalendarWidgetLogic.select(listOf(e), nowMs, berlin, 4)
        assertEquals(1, rows.size)
        assertEquals("ganztägig", rows[0].label)
    }

    @Test fun calendarCacheRoundTrip() {
        val items = listOf(WidgetEvent(1, 10L, 20L, "A", false), WidgetEvent(2, 30L, 40L, "B", true))
        assertEquals(items, CalendarWidgetLogic.fromCache(CalendarWidgetLogic.toCache(items)))
        assertTrue(CalendarWidgetLogic.fromCache("[{]").isEmpty())
    }

    // ── Stream ───────────────────────────────────────────────────

    // Woche 23.–29.11.2026 (echtes Format von your-donna-instance.example.com/api/schedule)
    private val plan = """{"week_start":"2026-11-23","week_end":"2026-11-29","days":[
        {"day_of_week":0,"status":"stream","entries":[{"from":"19:30","to":"","activity":"Gaming & Quatschen"}],"note":null},
        {"day_of_week":1,"status":"streamfrei","entries":[],"note":null},
        {"day_of_week":2,"status":"stream","entries":[{"from":"20:30","to":"","activity":"Gaming & Quatschen"}],"note":null},
        {"day_of_week":3,"status":"streamfrei","entries":[],"note":null},
        {"day_of_week":4,"status":"stream","entries":[{"from":"19:30","to":"","activity":"Community-Spieleabend"}],"note":null},
        {"day_of_week":5,"status":"streamfrei","entries":[],"note":null},
        {"day_of_week":6,"status":"stream","entries":[{"from":"19:30","to":"22:00","activity":"Gaming & Quatschen"}],"note":null}]}"""

    @Test fun streamParse() {
        val p = StreamWidgetLogic.parse(plan)!!
        assertEquals(LocalDate.of(2026, 11, 23), p.weekStart)
        assertEquals(7, p.days.size)
        assertEquals(LocalTime.of(19, 30), p.days[0].entries[0].from)
        assertNull(p.days[0].entries[0].to)
        assertNull(StreamWidgetLogic.parse("nicht json"))
        assertNull(StreamWidgetLogic.parse("""{"week_start":"2026-11-23"}"""))
    }

    @Test fun streamTodayShowsSchedule() {
        val v = StreamWidgetLogic.view(StreamWidgetLogic.parse(plan), now) // Freitag 10:00
        assertEquals("Heute Stream", v.title)
        assertEquals("19:30 Community-Spieleabend", v.detail)
        // läuft noch um 22:00 (kein Ende angegeben → 4 h)
        assertEquals("Heute Stream", StreamWidgetLogic.view(StreamWidgetLogic.parse(plan), now.withHour(22)).title)
    }

    @Test fun streamAfterTodayShowsNext() {
        val v = StreamWidgetLogic.view(StreamWidgetLogic.parse(plan), now.withHour(23).withMinute(45))
        assertEquals("Nächster Stream", v.title)
        assertEquals("So 19:30 Gaming & Quatschen", v.detail)
        val tue = StreamWidgetLogic.view(StreamWidgetLogic.parse(plan), ZonedDateTime.of(2026, 11, 24, 12, 0, 0, 0, berlin))
        assertEquals("Morgen 20:30 Gaming & Quatschen", tue.detail)
    }

    @Test fun streamStillRunningAfterMidnight() {
        // Freitag 21:30 ohne Ende → 4 h → läuft Samstag 00:30 noch, um 02:00 nicht mehr
        val body = """{"week_start":"2026-11-23","days":[
            {"day_of_week":4,"status":"stream","entries":[{"from":"21:30","to":"","activity":"Late"}],"note":null},
            {"day_of_week":6,"status":"stream","entries":[{"from":"19:30","to":"","activity":""}],"note":null}]}"""
        val satEarly = ZonedDateTime.of(2026, 11, 28, 0, 30, 0, 0, berlin)
        val v = StreamWidgetLogic.view(StreamWidgetLogic.parse(body), satEarly)
        assertEquals("Stream läuft noch", v.title)
        assertEquals("21:30 Late", v.detail)
        assertEquals("Nächster Stream", StreamWidgetLogic.view(StreamWidgetLogic.parse(body), satEarly.withHour(2)).title)
    }

    @Test fun streamEndOfWeekAndOutdatedPlan() {
        val sunLate = ZonedDateTime.of(2026, 11, 29, 23, 0, 0, 0, berlin) // Sonntag nach 22:00
        assertEquals("Kein Stream geplant", StreamWidgetLogic.view(StreamWidgetLogic.parse(plan), sunLate).title)
        val nextMon = ZonedDateTime.of(2026, 11, 30, 12, 0, 0, 0, berlin)
        val v = StreamWidgetLogic.view(StreamWidgetLogic.parse(plan), nextMon)
        assertEquals("Der neue Streamplan ist noch nicht da", v.detail)
    }

    @Test fun streamAusfallWithReasonAndNext() {
        val body = """{"week_start":"2026-11-23","days":[
            {"day_of_week":4,"status":"ausfall","entries":[],"note":"Urlaub\n<b>"},
            {"day_of_week":6,"status":"stream","entries":[{"from":"19:30","to":"","activity":""}],"note":null}]}"""
        val v = StreamWidgetLogic.view(StreamWidgetLogic.parse(body), now)
        assertEquals("Heute kein Stream", v.title)
        assertEquals("Ausfall (Urlaub b) · Nächster: So 19:30", v.detail)
    }

    @Test fun streamIgnoresBadDaysAndTimes() {
        val body = """{"days":[{"status":"stream","entries":[{"from":"19:30"}]},
            {"day_of_week":"4","status":"stream","entries":[{"from":"19:30"}]},
            {"day_of_week":4,"status":"stream","entries":[{"from":"25:99"},{"from":"18:00","to":"17:00","activity":"Nacht"}]}]}"""
        val p = StreamWidgetLogic.parse(body)!!
        assertEquals(1, p.days.size)
        assertNull(p.weekStart)
        assertEquals(1, p.days[0].entries.size)
        // ohne week_start: Tag relativ zur aktuellen Woche; Ende vor Beginn = über Mitternacht
        val v = StreamWidgetLogic.view(p, now.withHour(23))
        assertEquals("Heute Stream", v.title)
        assertEquals("18:00 Nacht", v.detail)
        assertEquals("Noch nicht geladen", StreamWidgetLogic.view(null, now).detail)
    }
}

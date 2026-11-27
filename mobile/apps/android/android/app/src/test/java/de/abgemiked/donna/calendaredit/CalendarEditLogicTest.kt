package com.yourcompany.donna.calendaredit

import com.yourcompany.donna.device.DeviceActionKind
import com.yourcompany.donna.device.PermissionFacts
import com.yourcompany.donna.device.SystemPermissions
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * #147 Paket H — Termine ändern/löschen (Gerät). Komplett mit einem FAKE-Kalender: erfundene Termine, keine echten
 * Kalenderinhalte, kein Android. Deckt ab: Anfrageprüfung, nur eigene Termine, Auswahl bei Mehrdeutigkeit, Serien,
 * Löschen, Zeitzone/Sommerzeit, Vergleich mit der Karte vor dem Schreiben, Schalter/Berechtigung.
 */
class CalendarEditLogicTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private fun ms(y: Int, m: Int, d: Int, h: Int, mi: Int = 0): Long =
        ZonedDateTime.of(y, m, d, h, mi, 0, 0, berlin).toInstant().toEpochMilli()

    private val now = ms(2026, 11, 23, 10)

    private class Fake(var events: MutableList<CalEvent>, var permitted: Boolean = true) : CalendarGateway {
        val writes = mutableListOf<String>()
        override fun hasPermission() = permitted
        override fun instances(fromMs: Long, toMs: Long) = events.filter { it.beginMs in fromMs until toMs || it.endMs > fromMs && it.beginMs < toMs }
        override fun load(eventId: Long, beginMs: Long) = events.firstOrNull { it.eventId == eventId && it.beginMs == beginMs }
        override fun syncedCalendarCount() = 2
        override fun updateEvent(eventId: Long, beginMs: Long?, endMs: Long?, title: String?, tz: String?, allDay: Boolean?): Boolean {
            writes += "update:$eventId:$beginMs:$endMs:$title" + (if (allDay != null) ":allDay=$allDay:tz=$tz" else ""); return true
        }
        override fun changeInstance(eventId: Long, origBeginMs: Long, beginMs: Long?, endMs: Long?, title: String?): Boolean {
            writes += "instance:$eventId:$origBeginMs:$beginMs:$endMs:$title"; return true
        }
        override fun cancelInstance(eventId: Long, origBeginMs: Long): Boolean { writes += "cancel:$eventId:$origBeginMs"; return true }
        override fun deleteEvent(eventId: Long): Boolean { writes += "delete:$eventId"; return true }
    }

    private fun ev(
        id: Long, title: String, begin: Long, end: Long = begin + 3_600_000L, own: Boolean = true,
        recurring: Boolean = false, allDay: Boolean = false, access: Int = 700, organizer: String? = "mike@example.test",
        isOrganizer: Boolean? = null, owner: String? = "mike@example.test",
    ) = CalEvent(
        id, 1, title, begin, end, allDay, if (own) organizer else "chef@example.test", if (own) isOrganizer else false,
        owner, owner, access, recurring,
    )

    private fun svc(f: Fake, clock: Long = now) = CalendarEditService(f, berlin) { clock }

    private fun req(
        op: EditOp, vararg q: String, onDate: LocalDate? = null, onTime: LocalTime? = null, newDate: LocalDate? = null,
        newTime: LocalTime? = null, title: String? = null, series: Boolean = false, fromAllDay: Boolean = false,
    ) = EditRequest(op, q.toList(), onDate, onTime, newDate, newTime, title, series, fromAllDay)

    private fun ready(r: PlanResult): Plan = (r as PlanResult.Ready).plan

    // ── Anfrage vom Server: strenge Prüfung ──────────────────────────────────────────────────────
    private fun card(vararg kv: Pair<String, Any>) = JSONObject().put("type", "calendar_change").also { j ->
        kv.forEach { (k, v) -> j.put(k, if (v is List<*>) JSONArray(v) else v) }
    }

    @Test fun `gueltige Karte wird gelesen`() {
        val r = EditRequestParser.parse(card("op" to "move", "query" to listOf("zahnarzt"), "new_date" to "2026-11-26", "new_time" to "15:00", "action_id" to "da_0123456789abcdef"))!!
        assertEquals(EditOp.MOVE, r.op)
        assertEquals(LocalDate.of(2026, 11, 26), r.newDate)
        assertEquals(LocalTime.of(15, 0), r.newTime)
    }

    @Test fun `Karte mit Fehlern wird abgelehnt`() {
        val bad = listOf(
            card("op" to "move", "query" to listOf("zahnarzt")),                                         // Ziel fehlt
            card("op" to "delete", "query" to listOf("zahnarzt"), "new_time" to "15:00"),               // Wert passt nicht zur Art
            card("op" to "rename", "query" to listOf("zahnarzt")),                                      // Titel fehlt
            card("op" to "rename", "query" to listOf("zahnarzt"), "new_title" to "A", "new_time" to "10:00"),
            card("op" to "wipe", "query" to listOf("zahnarzt")),                                         // unbekannte Art
            card("op" to "delete", "query" to emptyList<String>()),                                      // kein Suchbegriff
            card("op" to "delete", "query" to listOf("zu")),                                             // zu kurz
            card("op" to "delete", "query" to listOf("Zahn Arzt!")),                                     // nicht normalisiert
            card("op" to "delete", "query" to listOf("a1b", "a2b", "a3b", "a4b", "a5b", "a6b", "a7b")),
            card("op" to "move", "query" to listOf("zahnarzt"), "new_date" to "2026-13-40"),
            card("op" to "move", "query" to listOf("zahnarzt"), "new_time" to "25:00"),
            card("op" to "delete", "query" to listOf("zahnarzt"), "extra" to "x"),                       // unbekanntes Feld
            card("op" to "rename", "query" to listOf("zahnarzt"), "new_title" to "  "),
        )
        bad.forEachIndexed { i, j -> assertNull("Fall $i", EditRequestParser.parse(j)) }
        assertNull(EditRequestParser.parse(JSONObject().put("type", "device_action").put("op", "delete")))
    }

    // ── nur eigene Termine ───────────────────────────────────────────────────────────────────────
    @Test fun `eigener Termin wird bereit, Vorher und Nachher stimmen, Dauer bleibt`() {
        val e = ev(1, "Zahnarzt Kontrolle", ms(2026, 11, 25, 9), ms(2026, 11, 25, 10, 30))
        val p = ready(svc(Fake(mutableListOf(e))).plan(req(EditOp.MOVE, "zahnarzt", newDate = LocalDate.of(2026, 11, 26), newTime = LocalTime.of(15, 0))))
        assertEquals(ms(2026, 11, 26, 15), p.newBeginMs)
        assertEquals(ms(2026, 11, 26, 16, 30), p.newEndMs)
        assertEquals(e.beginMs, p.before.beginMs)
        assertFalse(p.irreversible)
    }

    @Test fun `nur Zeit oder nur Tag aendert den anderen Teil nicht`() {
        val e = ev(1, "Zahnarzt", ms(2026, 11, 25, 9))
        val f = Fake(mutableListOf(e))
        assertEquals(ms(2026, 11, 25, 15), ready(svc(f).plan(req(EditOp.MOVE, "zahnarzt", newTime = LocalTime.of(15, 0)))).newBeginMs)
        assertEquals(ms(2026, 11, 27, 9), ready(svc(f).plan(req(EditOp.MOVE, "zahnarzt", newDate = LocalDate.of(2026, 11, 27)))).newBeginMs)
    }

    @Test fun `fremder Termin wird abgelehnt und nie geschrieben`() {
        val f = Fake(mutableListOf(ev(1, "Zahnarzt Praxis", ms(2026, 11, 25, 9), own = false)))
        assertEquals(PlanResult.Refused(RefusalReason.FOREIGN), svc(f).plan(req(EditOp.DELETE, "zahnarzt")))
        assertEquals(PlanResult.Refused(RefusalReason.FOREIGN), svc(f).plan(req(EditOp.MOVE, "zahnarzt", newTime = LocalTime.of(15, 0))))
        assertEquals(PlanResult.Refused(RefusalReason.FOREIGN), svc(f).plan(req(EditOp.RENAME, "zahnarzt", title = "Neu")))
        assertTrue(f.writes.isEmpty())
    }

    @Test fun `Kalender ohne Schreibrecht und Einladungen sind nicht eigen`() {
        val readOnly = ev(1, "Feiertag Test", ms(2026, 11, 25, 9), access = 200)
        val invited = ev(2, "Besprechung Test", ms(2026, 11, 25, 9), organizer = "chef@example.test", isOrganizer = false)
        val ownByFlag = ev(3, "Planung Test", ms(2026, 11, 25, 9), organizer = "team@example.test", isOrganizer = true)
        val localNoOrg = ev(4, "Notiz Lokal", ms(2026, 11, 25, 9), organizer = null)
        val noOwner = ev(5, "Unbekannt Test", ms(2026, 11, 25, 9), organizer = null, owner = null)
        assertFalse(EditAccess.isOwn(readOnly))
        assertFalse(EditAccess.isOwn(invited))
        assertTrue(EditAccess.isOwn(ownByFlag))
        assertTrue(EditAccess.isOwn(localNoOrg))
        assertFalse(EditAccess.isOwn(noOwner))
        assertFalse(EditAccess.isOwn(ev(6, "x", 0, organizer = "fremd@example.test", isOrganizer = null, own = true)))
        // Delegation: Kalender gehört jemand anderem, Organisator = Kalenderbesitzer, IS_ORGANIZER = 0 -> nie eigen
        assertFalse(EditAccess.isOwn(ev(7, "x", 0, organizer = "amie@example.test", isOrganizer = false, owner = "amie@example.test")))
        // Besitzer-Konto ungleich Konto (geteilter Kalender), IS_ORGANIZER unbekannt -> nie eigen
        assertFalse(EditAccess.isOwn(ev(8, "x", 0, organizer = "amie@example.test", isOrganizer = null).copy(calendarOwner = "amie@example.test", calendarAccount = "mike@example.test")))
        // geteilter Kalender, Provider meldet IS_ORGANIZER=1 (gerechnet gegen den Kalenderbesitzer) -> trotzdem nie eigen
        assertFalse(EditAccess.isOwn(ev(10, "x", 0, organizer = "amie@example.test", isOrganizer = true).copy(calendarOwner = "amie@example.test", calendarAccount = "mike@example.test")))
        assertFalse(EditAccess.isOwn(ev(9, "x", 0, organizer = null, isOrganizer = null).copy(calendarOwner = "amie@example.test", calendarAccount = "mike@example.test")))
    }

    // ── Auswahl bei Mehrdeutigkeit ───────────────────────────────────────────────────────────────
    @Test fun `mehrere Treffer geben eine Auswahl statt zu raten`() {
        val f = Fake(mutableListOf(
            ev(1, "Zahnarzt Kontrolle", ms(2026, 11, 25, 9)),
            ev(2, "Zahnarzt Reinigung", ms(2026, 11, 27, 9)),
            ev(3, "Zahnarzt Praxis Frau Muster", ms(2026, 11, 28, 9), own = false),
        ))
        val r = svc(f).plan(req(EditOp.DELETE, "zahnarzt")) as PlanResult.Choose
        assertEquals(listOf(1L, 2L, 3L), r.candidates.map { it.eventId })
        assertEquals(listOf(true, true, false), r.candidates.map { it.own })     // fremder Termin ist nicht wählbar
        assertTrue(f.writes.isEmpty())
    }

    @Test fun `Auswahl eines fremden Termins wird abgelehnt`() {
        val f = Fake(mutableListOf(ev(2, "Zahnarzt Reinigung", ms(2026, 11, 27, 9)), ev(3, "Zahnarzt Fremd", ms(2026, 11, 28, 9), own = false)))
        assertEquals(PlanResult.Refused(RefusalReason.FOREIGN), svc(f).planSelected(req(EditOp.DELETE, "zahnarzt"), 3, ms(2026, 11, 28, 9)))
        assertNotNull(ready(svc(f).planSelected(req(EditOp.DELETE, "zahnarzt"), 2, ms(2026, 11, 27, 9))))
    }

    @Test fun `Filter Tag und Uhrzeit machen den Treffer eindeutig`() {
        val f = Fake(mutableListOf(ev(1, "Zahnarzt", ms(2026, 11, 25, 9)), ev(2, "Zahnarzt", ms(2026, 11, 27, 14))))
        assertEquals(2L, ready(svc(f).plan(req(EditOp.DELETE, "zahnarzt", onDate = LocalDate.of(2026, 11, 27)))).eventId)
        assertEquals(1L, ready(svc(f).plan(req(EditOp.DELETE, "zahnarzt", onTime = LocalTime.of(9, 0)))).eventId)
    }

    @Test fun `Suchbegriff trifft nur Wortanfaenge`() {
        val f = Fake(mutableListOf(ev(1, "Automesse Besuch", ms(2026, 11, 25, 9))))
        assertTrue(svc(f).plan(req(EditOp.DELETE, "tom")) is PlanResult.NoMatch)
        assertEquals(1L, ready(svc(f).plan(req(EditOp.DELETE, "automesse"))).eventId)
        assertEquals(1L, ready(svc(f).plan(req(EditOp.DELETE, "auto"))).eventId)
    }

    @Test fun `zu viele Treffer und kein Treffer`() {
        val many = (1L..8L).map { ev(it, "Training $it", ms(2026, 11, 24, 8) + it * 86_400_000L) }.toMutableList()
        assertEquals(PlanResult.Refused(RefusalReason.TOO_MANY), svc(Fake(many)).plan(req(EditOp.DELETE, "training")))
        assertEquals(PlanResult.NoMatch(2), svc(Fake(mutableListOf())).plan(req(EditOp.DELETE, "zahnarzt")))
    }

    @Test fun `Termine anderer Dienste ohne passenden Titel werden nicht beruehrt`() {
        // Suchbegriffe kommen nur aus der Anfrage; ein Treffer verlangt ALLE Begriffe im Titel
        val f = Fake(mutableListOf(ev(1, "Zahnarzt", ms(2026, 11, 25, 9))))
        assertTrue(svc(f).plan(req(EditOp.DELETE, "zahnarzt", "kontrolle")) is PlanResult.NoMatch)
    }

    // ── Serien ───────────────────────────────────────────────────────────────────────────────────
    @Test fun `Serie nur dieser Termin - Loeschen nimmt nur das Vorkommen`() {
        val e = ev(7, "Yoga Kurs", ms(2026, 11, 25, 18), recurring = true)
        val f = Fake(mutableListOf(e))
        val p = ready(svc(f).plan(req(EditOp.DELETE, "yoga")))
        assertTrue(p.recurring && p.seriesAllowed && p.irreversible)
        assertEquals(ApplyOutcome.OK, svc(f).apply(p, Scope.INSTANCE, false).outcome)
        assertEquals(listOf("cancel:7:${e.beginMs}"), f.writes)
    }

    @Test fun `ganze Serie nur mit zweiter Bestaetigung`() {
        val e = ev(7, "Yoga Kurs", ms(2026, 11, 25, 18), recurring = true)
        val f = Fake(mutableListOf(e))
        val p = ready(svc(f).plan(req(EditOp.DELETE, "yoga", series = true)))
        assertEquals(RefusalReason.SERIES_MOVE, svc(f).apply(p, Scope.SERIES, false).refusal)   // ohne zweite Bestaetigung
        assertTrue(f.writes.isEmpty())
        assertEquals(ApplyOutcome.OK, svc(f).apply(p, Scope.SERIES, true).outcome)
        assertEquals(listOf("delete:7"), f.writes)
    }

    @Test fun `Serie verschieben gibt es nur fuer einen Termin`() {
        val e = ev(7, "Yoga Kurs", ms(2026, 11, 25, 18), recurring = true)
        val f = Fake(mutableListOf(e))
        val p = ready(svc(f).plan(req(EditOp.MOVE, "yoga", newTime = LocalTime.of(19, 0))))
        assertFalse(p.seriesAllowed)
        assertEquals(RefusalReason.SERIES_MOVE, svc(f).apply(p, Scope.SERIES, true).refusal)
        assertEquals(ApplyOutcome.OK, svc(f).apply(p, Scope.INSTANCE, false).outcome)
        assertEquals("instance:7:${e.beginMs}:${ms(2026, 11, 25, 19)}:${ms(2026, 11, 25, 20)}:null", f.writes.single())
    }

    @Test fun `Ausnahme-Zeile einer Serie darf nie als ganze Serie geaendert werden`() {
        val e = ev(7, "Standup", ms(2026, 11, 25, 9), recurring = true).copy(exception = true)
        val f = Fake(mutableListOf(e))
        val p = ready(svc(f).plan(req(EditOp.DELETE, "standup")))
        assertFalse(p.seriesAllowed)
        assertEquals(RefusalReason.SERIES_MOVE, svc(f).apply(p.copy(seriesAllowed = true), Scope.SERIES, true).refusal)
        assertTrue(f.writes.isEmpty())
    }

    @Test fun `Serie bei einem Einzeltermin wird abgelehnt`() {
        val f = Fake(mutableListOf(ev(1, "Zahnarzt", ms(2026, 11, 25, 9))))
        val p = ready(svc(f).plan(req(EditOp.DELETE, "zahnarzt")))
        assertFalse(p.seriesAllowed)
        assertEquals(RefusalReason.SERIES_MOVE, svc(f).apply(p, Scope.SERIES, true).refusal)
        assertTrue(f.writes.isEmpty())
    }

    // ── Loeschen / Umbenennen ────────────────────────────────────────────────────────────────────
    @Test fun `Loeschen ist als nicht rueckholbar gekennzeichnet und loescht erst nach apply`() {
        val e = ev(1, "Friseur", ms(2026, 11, 25, 9))
        val f = Fake(mutableListOf(e))
        val p = ready(svc(f).plan(req(EditOp.DELETE, "friseur")))
        assertTrue(p.irreversible)
        assertTrue(f.writes.isEmpty())                                  // planen schreibt nie
        assertEquals(ApplyOutcome.OK, svc(f).apply(p, Scope.INSTANCE, false).outcome)
        assertEquals(listOf("delete:1"), f.writes)
        assertTrue(CalendarEditMessages.deleteWarning().contains("nicht rückgängig"))
    }

    @Test fun `Umbenennen und gleicher Titel`() {
        val f = Fake(mutableListOf(ev(1, "Lesekreis", ms(2026, 11, 25, 19))))
        val p = ready(svc(f).plan(req(EditOp.RENAME, "lesekreis", title = "Buchclub")))
        assertEquals("Buchclub", p.newTitle)
        assertEquals(PlanResult.Refused(RefusalReason.NO_CHANGE), svc(f).plan(req(EditOp.RENAME, "lesekreis", title = "Lesekreis")))
        assertEquals(ApplyOutcome.OK, svc(f).apply(p, Scope.INSTANCE, false).outcome)
        assertEquals("update:1:null:null:Buchclub", f.writes.single())
    }

    // ── Zeitzone und Sommerzeit ──────────────────────────────────────────────────────────────────
    @Test fun `Sommerzeit - Verschieben ueber den Wechsel behaelt Wanduhr und Dauer`() {
        // 24.10.2026 09:00-10:00 (MESZ) -> 26.10.2026 09:00 (MEZ): Abstand 49 Stunden, Dauer bleibt 1 Stunde
        val e = ev(1, "Zahnarzt", ms(2026, 10, 24, 9))
        val p = ready(svc(Fake(mutableListOf(e)), clock = ms(2026, 10, 20, 9)).plan(req(EditOp.MOVE, "zahnarzt", newDate = LocalDate.of(2026, 10, 26))))
        assertEquals(ms(2026, 10, 26, 9), p.newBeginMs)
        assertEquals(49 * 3_600_000L, p.newBeginMs!! - e.beginMs)
        assertEquals(3_600_000L, p.newEndMs!! - p.newBeginMs!!)
    }

    @Test fun `Sommerzeit - nicht existierende Uhrzeit wird nie geraten`() {
        // 29.03.2026: 02:00-03:00 gibt es nicht
        val e = ev(1, "Zahnarzt", ms(2026, 3, 27, 9))
        val r = svc(Fake(mutableListOf(e)), clock = ms(2026, 3, 20, 9))
            .plan(req(EditOp.MOVE, "zahnarzt", newDate = LocalDate.of(2026, 3, 29), newTime = LocalTime.of(2, 30)))
        assertEquals(PlanResult.Refused(RefusalReason.NONEXISTENT_TIME), r)
    }

    @Test fun `Sommerzeit - doppelte Stunde wird abgelehnt statt geraten`() {
        val e = ev(1, "Zahnarzt", ms(2026, 10, 23, 9))
        val r = svc(Fake(mutableListOf(e)), clock = ms(2026, 10, 20, 9))
            .plan(req(EditOp.MOVE, "zahnarzt", newDate = LocalDate.of(2026, 10, 25), newTime = LocalTime.of(2, 30)))
        assertEquals(PlanResult.Refused(RefusalReason.AMBIGUOUS_TIME), r)
    }

    @Test fun `Ganztaegig - Tag verschiebbar, Uhrzeit nur fuer Einzeltermine`() {
        val day = ZonedDateTime.of(2026, 11, 25, 0, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val e = ev(1, "Urlaub Test", day, day + 86_400_000L, allDay = true)
        val f = Fake(mutableListOf(e))
        val rec = ev(2, "Urlaub Serie", day, day + 86_400_000L, allDay = true, recurring = true)
        assertEquals(PlanResult.Refused(RefusalReason.ALL_DAY_TIME),
            svc(Fake(mutableListOf(rec))).plan(req(EditOp.MOVE, "urlaub", newTime = LocalTime.of(9, 0))))
        val p = ready(svc(f).plan(req(EditOp.MOVE, "urlaub", newDate = LocalDate.of(2026, 11, 27))))
        assertEquals(day + 2 * 86_400_000L, p.newBeginMs)
    }

    @Test fun `Ganztaegig auf Uhrzeit am selben Tag wird zum Termin mit Zeit, Dauer eine Stunde`() {
        val day = ZonedDateTime.of(2026, 11, 27, 0, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val e = ev(1, "Krankmeldung verlaengern", day, day + 86_400_000L, allDay = true)
        val f = Fake(mutableListOf(e))
        val r = req(EditOp.MOVE, "krankmeldung", "verlaengern", onDate = LocalDate.of(2026, 11, 27), newTime = LocalTime.of(13, 0), fromAllDay = true)
        val p = ready(svc(f).plan(r))
        assertTrue(p.toTimed)
        assertEquals(ms(2026, 11, 27, 13), p.newBeginMs)
        assertEquals(ms(2026, 11, 27, 14), p.newEndMs)
        val res = svc(f).apply(p, Scope.INSTANCE, false)
        assertEquals(ApplyOutcome.OK, res.outcome)
        assertEquals("update:1:${ms(2026, 11, 27, 13)}:${ms(2026, 11, 27, 14)}:null:allDay=false:tz=Europe/Berlin", f.writes.single())
    }

    @Test fun `from_all_day filtert auf ganztaegige Termine`() {
        val timed = ev(1, "Krankmeldung verlaengern", ms(2026, 11, 27, 9))
        val r = req(EditOp.MOVE, "krankmeldung", newTime = LocalTime.of(13, 0), fromAllDay = true)
        assertTrue(svc(Fake(mutableListOf(timed))).plan(r) is PlanResult.NoMatch)
    }

    @Test fun `Karte mit from_all_day wird gelesen`() {
        val j = card("op" to "move", "query" to org.json.JSONArray(listOf("krankmeldung")), "new_time" to "13:00", "from_all_day" to true)
        assertTrue(EditRequestParser.parse(j)!!.fromAllDay)
    }

    @Test fun `Ganztaegig auf heute ist nicht vergangen`() {
        val today = ZonedDateTime.of(2026, 11, 23, 0, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val e = ev(1, "Urlaub Test", today + 86_400_000L, today + 2 * 86_400_000L, allDay = true)
        val p = ready(svc(Fake(mutableListOf(e))).plan(req(EditOp.MOVE, "urlaub", newDate = LocalDate.of(2026, 11, 23))))
        assertEquals(today, p.newBeginMs)
    }

    @Test fun `Tägliche Serie ohne Datum bietet nur das naechste Vorkommen`() {
        val evs = (1..30).map { ev(7, "Standup", ms(2026, 11, 24, 9) + it * 86_400_000L, recurring = true) }.toMutableList()
        val p = ready(svc(Fake(evs)).plan(req(EditOp.DELETE, "standup")))
        assertEquals(evs[0].beginMs, p.before.beginMs)
    }

    @Test fun `Ziel in der Vergangenheit und gleiche Zeit werden abgelehnt`() {
        val e = ev(1, "Zahnarzt", ms(2026, 11, 25, 9))
        val f = Fake(mutableListOf(e))
        assertEquals(PlanResult.Refused(RefusalReason.PAST), svc(f).plan(req(EditOp.MOVE, "zahnarzt", newDate = LocalDate.of(2026, 11, 20))))
        assertEquals(PlanResult.Refused(RefusalReason.NO_CHANGE), svc(f).plan(req(EditOp.MOVE, "zahnarzt", newTime = LocalTime.of(9, 0))))
    }

    // ── Vergleich mit der Karte, Berechtigung ────────────────────────────────────────────────────
    @Test fun `Termin hat sich seit der Karte geaendert oder ist weg - nichts wird geschrieben`() {
        val e = ev(1, "Zahnarzt", ms(2026, 11, 25, 9))
        val f = Fake(mutableListOf(e))
        val r = req(EditOp.DELETE, "zahnarzt")
        val shown = Summary("Zahnarzt", e.beginMs, e.endMs, false, false)
        // Titel hat sich geaendert
        f.events[0] = e.copy(title = "Zahnarzt verlegt")
        assertEquals(RefusalReason.CHANGED, svc(f).applyFromRequest(req(EditOp.DELETE, "zahnarzt"), 1, e.beginMs, shown, Scope.INSTANCE, false).refusal)
        // Zeit hat sich geaendert (anderer Beginn -> gone)
        f.events[0] = e.copy(beginMs = e.beginMs + 3_600_000L, endMs = e.endMs + 3_600_000L)
        assertEquals(RefusalReason.GONE, svc(f).applyFromRequest(r, 1, e.beginMs, shown, Scope.INSTANCE, false).refusal)
        // Termin ist inzwischen einem anderen zugeordnet (Organisator gewechselt)
        f.events[0] = e.copy(organizer = "chef@example.test", isOrganizer = false)
        assertEquals(RefusalReason.FOREIGN, svc(f).applyFromRequest(r, 1, e.beginMs, shown, Scope.INSTANCE, false).refusal)
        assertTrue(f.writes.isEmpty())
        // unveraendert -> schreibt
        f.events[0] = e
        assertEquals(ApplyOutcome.OK, svc(f).applyFromRequest(r, 1, e.beginMs, shown, Scope.INSTANCE, false).outcome)
        assertEquals(listOf("delete:1"), f.writes)
    }

    @Test fun `ohne Kalender-Berechtigung nichts lesen oder schreiben`() {
        val f = Fake(mutableListOf(ev(1, "Zahnarzt", ms(2026, 11, 25, 9))), permitted = false)
        assertEquals(PlanResult.NeedsPermission, svc(f).plan(req(EditOp.DELETE, "zahnarzt")))
        val p = Plan(EditOp.DELETE, 1, Summary("Zahnarzt", 0, 0, false, false), null, null, null, false, false, true)
        assertEquals(ApplyOutcome.NEEDS_PERMISSION, svc(f).apply(p, Scope.INSTANCE, false).outcome)
        assertTrue(f.writes.isEmpty())
        assertTrue(CalendarEditMessages.needsPermission().contains("Kalender"))
    }

    @Test fun `Sicherheitsfehler beim Schreiben wird als fehlende Berechtigung gemeldet`() {
        val e = ev(1, "Zahnarzt", ms(2026, 11, 25, 9))
        val f = object : CalendarGateway by Fake(mutableListOf(e)) {
            override fun deleteEvent(eventId: Long): Boolean = throw SecurityException("x")
        }
        val p = ready(CalendarEditService(f, berlin) { now }.plan(req(EditOp.DELETE, "zahnarzt")))
        assertEquals(ApplyOutcome.NEEDS_PERMISSION, CalendarEditService(f, berlin) { now }.apply(p, Scope.INSTANCE, false).outcome)
    }

    // ── Schalter und Berechtigungs-Hinweis ───────────────────────────────────────────────────────
    @Test fun `Schalter calendar_edit standardmaessig aus und Berechtigung als Hinweis`() {
        assertEquals("calendar_edit", DeviceActionKind.CALENDAR_EDIT.key)
        assertEquals("device_action_calendar_edit", DeviceActionKind.CALENDAR_EDIT.prefKey)
        assertFalse(DeviceActionKind.DEFAULT_ENABLED)
        val none = PermissionFacts(sdk = 35, canWriteSettings = false, policyAccess = false, accessibilityActive = false)
        assertEquals("missing", SystemPermissions.stateOf(DeviceActionKind.CALENDAR_EDIT, none))
        assertEquals("granted", SystemPermissions.stateOf(DeviceActionKind.CALENDAR_EDIT, none.copy(calendarGranted = true)))
    }
}

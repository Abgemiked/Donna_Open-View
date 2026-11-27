package com.yourcompany.donna.reminder

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #153 App-Teil: Erkennen, Anzeigeform, Kanal, Spaeter-Stufen, Anfragen und Wiederholungs-Warteschlange (reine Logik). */
class ReminderLogicTest {

    private fun push(vararg tags: String, msg: String = "Text"): JSONObject =
        JSONObject().put("title", "Erinnerung").put("message", msg).put("tags", JSONArray(tags.toList()))

    // ── Erkennen ────────────────────────────────────────────────────────────
    @Test fun `Tag donna_reminder mit rid wird erkannt`() {
        val r = ReminderLogic.fromJson(push("donna_reminder", "rid=42"))!!
        assertEquals("42", r.id)
        assertEquals("Text", r.text)
    }

    @Test fun `ohne Tag keine Erinnerung`() {
        assertNull(ReminderLogic.fromJson(push("donna_persistent", "rid=42")))
        assertNull(ReminderLogic.fromJson(JSONObject().put("message", "x")))
    }

    @Test fun `Tag ohne rid ist Erinnerung ohne Kennung`() {
        val r = ReminderLogic.fromJson(push("donna_reminder"))!!
        assertNull(r.id)
    }

    @Test fun `unsichere oder leere rid wird verworfen`() {
        assertNull(ReminderLogic.fromJson(push("donna_reminder", "rid=../x"))!!.id)
        assertNull(ReminderLogic.fromJson(push("donna_reminder", "rid="))!!.id)
        assertNull(ReminderLogic.fromJson(push("donna_reminder", "rid=1 2"))!!.id)
    }

    @Test fun `Tags gross und mit Leerraum werden erkannt`() {
        assertEquals("7", ReminderLogic.fromJson(push(" DONNA_REMINDER ", "RID=7"))!!.id)
    }

    @Test fun `leerer Text ist keine anzeigbare Erinnerung`() {
        assertNull(ReminderLogic.fromJson(push("donna_reminder", "rid=1", msg = "  ")))
    }

    @Test fun `Benachrichtigungs-Kennung ist stabil je rid und liegt im eigenen Bereich`() {
        val a = ReminderLogic.notificationId("42")
        assertEquals(a, ReminderLogic.notificationId("42"))
        assertNotEquals(a, ReminderLogic.notificationId("43"))
        assertTrue(a in 30_000..39_999)
        assertTrue(ReminderLogic.notificationId(null) in 30_000..39_999)
    }

    // ── Anzeigeform ─────────────────────────────────────────────────────────
    @Test fun `gesperrt mit Vollbild-Berechtigung zeigt das Popup`() {
        assertEquals(Presentation.FULLSCREEN_POPUP, ReminderLogic.presentation(deviceLocked = true, canUseFullScreen = true, headsUpAllowed = true))
    }

    @Test fun `gesperrt ohne Vollbild-Berechtigung faellt auf Heads-up zurueck`() {
        assertEquals(Presentation.HEADS_UP, ReminderLogic.presentation(true, false, true))
    }

    @Test fun `entsperrt zeigt immer Heads-up, auch mit Berechtigung`() {
        assertEquals(Presentation.HEADS_UP, ReminderLogic.presentation(false, true, true))
        assertEquals(Presentation.HEADS_UP, ReminderLogic.presentation(false, false, true))
    }

    @Test fun `Kanal vom Nutzer herabgestuft heisst nur Benachrichtigung`() {
        assertEquals(Presentation.NOTIFICATION_ONLY, ReminderLogic.presentation(false, true, false))
        assertEquals(Presentation.NOTIFICATION_ONLY, ReminderLogic.presentation(true, true, false))
    }

    // ── Kanal ───────────────────────────────────────────────────────────────
    @Test fun `Standard ist der Kanal ohne Durchbrechen`() {
        assertEquals(ReminderLogic.CHANNEL_ID, ReminderLogic.channelId(dndBypass = false))
        assertFalse(ReminderLogic.DND_BYPASS_DEFAULT)
    }

    @Test fun `nur mit Schalter der zweite Kanal`() {
        assertEquals(ReminderLogic.CHANNEL_DND_ID, ReminderLogic.channelId(true))
        assertNotEquals(ReminderLogic.CHANNEL_ID, ReminderLogic.CHANNEL_DND_ID)
        assertFalse(ReminderLogic.channelBypassesDnd(ReminderLogic.CHANNEL_ID))
        assertTrue(ReminderLogic.channelBypassesDnd(ReminderLogic.CHANNEL_DND_ID))
    }

    // ── Spaeter ─────────────────────────────────────────────────────────────
    @Test fun `Spaeter-Stufen 10 30 60, Standard 10`() {
        assertEquals(listOf(10, 30, 60), ReminderLogic.SNOOZE_STEPS)
        assertEquals(10, ReminderLogic.SNOOZE_DEFAULT)
    }

    @Test fun `unbekannte Stufe wird zur Standardstufe`() {
        assertEquals(30, ReminderLogic.snoozeMinutes(30))
        assertEquals(10, ReminderLogic.snoozeMinutes(5))
        assertEquals(10, ReminderLogic.snoozeMinutes(null))
        assertEquals("1 Std", ReminderLogic.snoozeLabel(60))
        assertEquals("30 Min", ReminderLogic.snoozeLabel(30))
    }

    // ── Anfragen ────────────────────────────────────────────────────────────
    @Test fun `Erledigt-Anfrage`() {
        val q = ReminderLogic.request(Reply(ReplyKind.DONE, "42", null))!!
        assertEquals("/reminders/42/done", q.path)
        assertNull(q.body)
    }

    @Test fun `Spaeter-Anfrage traegt Minuten`() {
        val q = ReminderLogic.request(Reply(ReplyKind.SNOOZE, "42", 30))!!
        assertEquals("/reminders/42/snooze", q.path)
        assertEquals(30, JSONObject(q.body!!).getInt("minutes"))
    }

    @Test fun `unsichere Kennung ergibt keine Anfrage`() {
        assertNull(ReminderLogic.request(Reply(ReplyKind.DONE, "../x", null)))
        assertNull(ReminderLogic.request(Reply(ReplyKind.DONE, "", null)))
    }

    @Test fun `Ergebnis nach Antwortcode`() {
        assertEquals(Outcome.SENT, ReminderLogic.outcome(200))
        assertEquals(Outcome.SENT, ReminderLogic.outcome(204))
        assertEquals(Outcome.GONE, ReminderLogic.outcome(404))
        assertEquals(Outcome.GONE, ReminderLogic.outcome(409))
        assertEquals(Outcome.RETRY, ReminderLogic.outcome(500))
        assertEquals(Outcome.RETRY, ReminderLogic.outcome(503))
        assertEquals(Outcome.RETRY, ReminderLogic.outcome(null))
    }

    @Test fun `dauerhafte Fehler sind eigene Klasse und blockieren nicht`() {
        for (c in listOf(400, 401, 403, 422)) assertEquals(Outcome.PERMANENT, ReminderLogic.outcome(c))
    }

    @Test fun `404 und 409 raeumen auf, die Erinnerung haengt nicht fest`() {
        assertTrue(ReminderLogic.clearsNotification(Outcome.SENT))
        assertTrue(ReminderLogic.clearsNotification(Outcome.GONE))
        assertFalse(ReminderLogic.clearsNotification(Outcome.RETRY))
        assertFalse(ReminderLogic.clearsNotification(Outcome.PERMANENT))
    }

    @Test fun `dauerhafter Fehler rueckt nach hinten und wird nach N Versuchen verworfen`() {
        var q = ReminderLogic.queueAdd(null, Reply(ReplyKind.DONE, "1", null))
        q = ReminderLogic.queueAdd(q, Reply(ReplyKind.DONE, "2", null))
        var r = ReminderLogic.queueFail(q, Reply(ReplyKind.DONE, "1", null))
        assertFalse(r.dropped)
        val items = ReminderLogic.queueParse(r.raw)
        assertEquals(listOf("2", "1"), items.map { it.id })
        assertEquals(1, items.last().attempts)
        repeat(ReminderLogic.MAX_ATTEMPTS - 2) { r = ReminderLogic.queueFail(r.raw, ReminderLogic.queueParse(r.raw).first { it.id == "1" }) }
        assertFalse(r.dropped)
        r = ReminderLogic.queueFail(r.raw, ReminderLogic.queueParse(r.raw).first { it.id == "1" })
        assertTrue(r.dropped)
        assertEquals(listOf("2"), ReminderLogic.queueParse(r.raw).map { it.id })
    }

    @Test fun `Fehler eines nicht mehr vorhandenen Eintrags aendert nichts`() {
        val q = ReminderLogic.queueAdd(null, Reply(ReplyKind.DONE, "1", null))
        val r = ReminderLogic.queueFail(q, Reply(ReplyKind.DONE, "9", null))
        assertFalse(r.dropped)
        assertEquals(listOf("1"), ReminderLogic.queueParse(r.raw).map { it.id })
    }

    @Test fun `Entfernen nach Senden nur wenn der Eintrag noch derselbe ist`() {
        // gesendet wurde Spaeter; inzwischen ersetzt eine neuere Antwort Erledigt den Eintrag
        var q = ReminderLogic.queueAdd(null, Reply(ReplyKind.SNOOZE, "1", 10))
        q = ReminderLogic.queueAdd(q, Reply(ReplyKind.DONE, "1", null))
        val after = ReminderLogic.queueRemoveIfSame(q, Reply(ReplyKind.SNOOZE, "1", 10))
        assertEquals(listOf(Reply(ReplyKind.DONE, "1", null)), ReminderLogic.queueParse(after))
        val gone = ReminderLogic.queueRemoveIfSame(after, Reply(ReplyKind.DONE, "1", null, attempts = 3))
        assertTrue(ReminderLogic.queueParse(gone).isEmpty())
    }

    @Test fun `bereits erledigte Erinnerung wird nicht erneut gezeigt, Merkliste ist begrenzt`() {
        var a: String? = null
        assertTrue(ReminderLogic.shouldShow(Reminder("5", "x"), a))
        a = ReminderLogic.answeredAdd(a, "5")
        assertFalse(ReminderLogic.shouldShow(Reminder("5", "x"), a))
        assertTrue(ReminderLogic.shouldShow(Reminder("6", "x"), a))
        assertTrue(ReminderLogic.shouldShow(Reminder(null, "x"), a))
        for (i in 100..160) a = ReminderLogic.answeredAdd(a, "$i")
        assertEquals(ReminderLogic.ANSWERED_MAX, ReminderLogic.answeredParse(a).size)
        assertTrue(ReminderLogic.shouldShow(Reminder("5", "x"), a))
        assertFalse(ReminderLogic.shouldShow(Reminder("160", "x"), a))
        assertTrue(ReminderLogic.answeredParse("kaputt").isEmpty())
    }

    @Test fun `Erinnerung ohne Kennung bekommt eigene Benachrichtigungs-Kennung je Zaehler`() {
        val a = ReminderLogic.notificationIdAnonymous(1)
        assertNotEquals(a, ReminderLogic.notificationIdAnonymous(2))
        assertTrue(a in 40_000..40_999)
        assertTrue(ReminderLogic.notificationIdAnonymous(-5) in 40_000..40_999)
    }

    // ── Warteschlange ───────────────────────────────────────────────────────
    @Test fun `Warteschlange speichert und liest zurueck`() {
        var q = ReminderLogic.queueAdd("[]", Reply(ReplyKind.DONE, "1", null))
        q = ReminderLogic.queueAdd(q, Reply(ReplyKind.SNOOZE, "2", 30))
        val items = ReminderLogic.queueParse(q)
        assertEquals(listOf(Reply(ReplyKind.DONE, "1", null), Reply(ReplyKind.SNOOZE, "2", 30)), items)
    }

    @Test fun `je Erinnerung hoechstens eine offene Antwort, die neueste gewinnt`() {
        var q = ReminderLogic.queueAdd(null, Reply(ReplyKind.SNOOZE, "1", 10))
        q = ReminderLogic.queueAdd(q, Reply(ReplyKind.DONE, "1", null))
        assertEquals(listOf(Reply(ReplyKind.DONE, "1", null)), ReminderLogic.queueParse(q))
    }

    @Test fun `kaputte Warteschlange ergibt leere Liste`() {
        assertTrue(ReminderLogic.queueParse("kaputt").isEmpty())
        assertTrue(ReminderLogic.queueParse(null).isEmpty())
        assertTrue(ReminderLogic.queueParse("[{\"kind\":\"x\",\"id\":\"1\"}]").isEmpty())
    }

    @Test fun `Warteschlange ist begrenzt`() {
        var q: String? = null
        for (i in 1..80) q = ReminderLogic.queueAdd(q, Reply(ReplyKind.DONE, "$i", null))
        val items = ReminderLogic.queueParse(q)
        assertEquals(ReminderLogic.QUEUE_MAX, items.size)
        assertEquals("80", items.last().id)
    }

    @Test fun `Eintrag entfernen`() {
        var q = ReminderLogic.queueAdd(null, Reply(ReplyKind.DONE, "1", null))
        q = ReminderLogic.queueAdd(q, Reply(ReplyKind.DONE, "2", null))
        assertEquals(listOf("2"), ReminderLogic.queueParse(ReminderLogic.queueRemove(q, "1")).map { it.id })
    }

    // ── Popup-Abstand zur Systemleiste (Abnahme: Tasten unter der Navigationsleiste) ──
    @Test fun `Popup-Rand unten addiert die Systemleiste zum Grundabstand`() {
        assertEquals(24, ReminderLogic.popupBottomMargin(systemBarBottom = 0, imeBottom = 0, base = 24))
        assertEquals(24 + 48, ReminderLogic.popupBottomMargin(systemBarBottom = 48, imeBottom = 0, base = 24))
    }

    @Test fun `Popup-Rand unten folgt der Tastatur, wenn sie hoeher ist als die Systemleiste`() {
        assertEquals(24 + 300, ReminderLogic.popupBottomMargin(systemBarBottom = 48, imeBottom = 300, base = 24))
    }

    @Test fun `Popup-Rand unten nie negativ`() {
        assertEquals(0, ReminderLogic.popupBottomMargin(systemBarBottom = -5, imeBottom = -1, base = 0))
    }
}

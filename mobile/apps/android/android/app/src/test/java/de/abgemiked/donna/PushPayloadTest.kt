package com.yourcompany.donna

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-Unit-Tests fuer die Push-Nachricht (Notification-Fix): Morgen-Briefing aufklappbar und als Extra im
 * contentIntent, damit der Tap den Chat damit seedet statt leer zu oeffnen.
 */
class PushPayloadTest {

    @Test
    fun `parse liest Titel, Text und Session aus dem ntfy-JSON`() {
        val p = PushPayload.parse(JSONObject("""{"event":"message","title":"Guten Morgen","message":"Heute 21 Grad.","session_id":"abc"}"""))
        assertNotNull(p)
        assertEquals("Guten Morgen", p!!.title)
        assertEquals("Heute 21 Grad.", p.body)
        assertEquals("abc", p.sessionId)
    }

    @Test
    fun `body-Feld ist Ersatz fuer message, Standardtitel Donna`() {
        val p = PushPayload.parse(JSONObject("""{"body":"Nur Body"}"""))!!
        assertEquals("Donna", p.title)
        assertEquals("Nur Body", p.body)
        assertNull(p.sessionId)
    }

    @Test
    fun `leere oder fehlende Nachricht wird ignoriert`() {
        assertNull(PushPayload.parse(JSONObject("""{"title":"x"}""")))
        assertNull(PushPayload.parse(JSONObject("""{"message":"   "}""")))
    }

    @Test
    fun `lange Briefings werden fuer das Intent-Extra begrenzt`() {
        val long = "a".repeat(PushPayload.MAX_BODY_CHARS + 500)
        val p = PushPayload.parse(JSONObject().put("message", long))!!
        assertEquals(PushPayload.MAX_BODY_CHARS, p.body.length)
    }

    @Test
    fun `proactiveJson ist genau das Format, das die JS-Schicht liest`() {
        val j = JSONObject(PushPayload.proactiveJson("Briefing", "s1"))
        assertEquals("Briefing", j.getString("message"))
        assertEquals("s1", j.getString("session_id"))
        val noSession = JSONObject(PushPayload.proactiveJson("Briefing", null))
        assertTrue(!noSession.has("session_id"))
        assertTrue(!JSONObject(PushPayload.proactiveJson("Briefing", "  ")).has("session_id"))
    }

    @Test
    fun `proactiveJson traegt Titel und Nonce fuer den Notification-Tap-Kontext`() {
        val j = JSONObject(PushPayload.proactiveJson("Heute 21 Grad.", "s1", "Morgen-Briefing", "1699999999-7"))
        assertEquals("Heute 21 Grad.", j.getString("message"))
        assertEquals("Morgen-Briefing", j.getString("title"))
        assertEquals("1699999999-7", j.getString("nonce"))
        assertEquals("s1", j.getString("session_id"))
    }

    @Test
    fun `proactiveJson laesst Pseudo-Titel Donna und leere Felder weg`() {
        val j = JSONObject(PushPayload.proactiveJson("Body", null, "Donna", null))
        assertTrue("Default-Titel 'Donna' ist kein echter Titel", !j.has("title"))
        assertTrue(!j.has("nonce"))
        assertTrue(!j.has("session_id"))
        val blankTitle = JSONObject(PushPayload.proactiveJson("Body", null, "  ", ""))
        assertTrue(!blankTitle.has("title"))
        assertTrue(!blankTitle.has("nonce"))
    }

    @Test
    fun `proactiveJson bleibt gueltiges JSON bei Umlauten, Sonderzeichen und Zeilenumbruechen`() {
        val body = "Guten Morgen Mike!\nHeute: 21°C, \"Sonnig\" & windig — Ämpfänger\tTab \\ Backslash"
        val j = JSONObject(PushPayload.proactiveJson(body, "s1", "Morgen-Briefing für Mike", "n1"))
        assertEquals(body, j.getString("message"))
        assertEquals("Morgen-Briefing für Mike", j.getString("title"))
    }

    @Test
    fun `proactiveJson mit leerem Body liefert leeres message-Feld ohne Crash`() {
        val j = JSONObject(PushPayload.proactiveJson("", null, "T", "n"))
        assertEquals("", j.getString("message"))
        // Titel/Nonce bleiben erhalten, auch wenn der Body leer ist (Absicherung, falls jemals leer durchkommt)
        assertEquals("T", j.getString("title"))
        assertEquals("n", j.getString("nonce"))
    }

    @Test
    fun `newNonce liefert bei jedem Aufruf einen anderen Wert`() {
        val a = PushPayload.newNonce()
        val b = PushPayload.newNonce()
        assertTrue(a.isNotBlank())
        assertTrue("jeder Tap ein eigener Nonce", a != b)
    }

    @Test
    fun `NtfyService - aufklappbar (BigTextStyle) und Titel plus Text plus Nonce im contentIntent`() {
        val src = java.io.File("src/main/java/com/yourcompany/donna/NtfyService.kt").readText()
        assertTrue(src.contains("NotificationCompat.BigTextStyle().bigText(body)"))
        assertTrue(src.contains("putExtra(\"donna_proactive_message\", body)"))
        // Notification-Tap-Kontext: Titel + Nonce gehoeren mit in den Tap-Intent
        assertTrue(src.contains("putExtra(\"donna_proactive_title\", title)"))
        assertTrue(src.contains("putExtra(\"donna_proactive_nonce\", nonce)"))
        assertTrue(src.contains("putExtra(\"open_new_chat\", true)"))
        // Seed in SharedPreferences traegt Titel + Nonce
        assertTrue(src.contains("PushPayload.proactiveJson(body, sessionId, title, nonce)"))
    }

    // ── B4: persistenter Push-Channel (#4-nativ) ─────────────────────────────

    @Test
    fun `B4 - Tag donna_persistent im ntfy-JSON markiert die Push als persistent`() {
        val p = PushPayload.parse(JSONObject("""{"event":"message","message":"Video hochladen","tags":["donna_persistent","bell"]}"""))!!
        assertTrue(p.persistent)
        // case-insensitiv + getrimmt
        assertTrue(PushPayload.isPersistent(JSONObject("""{"tags":[" DONNA_Persistent "]}""")))
    }

    @Test
    fun `B4 - ohne Tag, mit anderen Tags oder kaputtem tags-Feld bleibt die Push normal`() {
        assertFalse(PushPayload.parse(JSONObject("""{"message":"x"}"""))!!.persistent)
        assertFalse(PushPayload.parse(JSONObject("""{"message":"x","tags":["robot","tada"]}"""))!!.persistent)
        assertFalse(PushPayload.parse(JSONObject("""{"message":"x","tags":[]}"""))!!.persistent)
        // tags kein Array (z. B. String) -> kein Crash, normal
        assertFalse(PushPayload.parse(JSONObject("""{"message":"x","tags":"donna_persistent"}"""))!!.persistent)
        // Nicht-String-Eintraege -> kein Crash, normal
        assertFalse(PushPayload.isPersistent(JSONObject("""{"tags":[1,null,{"a":1}]}""")))
        // Teilstring ist KEIN Treffer
        assertFalse(PushPayload.isPersistent(JSONObject("""{"tags":["donna_persistent_not"]}""")))
    }

    @Test
    fun `B4 - persistent hat eigenen Channel, ongoing und KEIN autoCancel`() {
        val s = PushPayload.notificationSpec(persistent = true)
        assertEquals("donna_reminders", s.channelId)
        assertTrue(s.ongoing)
        assertFalse(s.autoCancel)
        assertTrue(s.reminderCategory)
    }

    @Test
    fun `B4 - normaler Push bleibt unveraendert Channel donna_push, autoCancel, nicht ongoing`() {
        val s = PushPayload.notificationSpec(persistent = false)
        assertEquals("donna_push", s.channelId)
        assertFalse(s.ongoing)
        assertTrue(s.autoCancel)
        assertFalse(s.reminderCategory)
        assertTrue(PushPayload.CHANNEL_PUSH_ID != PushPayload.CHANNEL_REMINDER_ID)
    }

    @Test
    fun `B4 - Foreground unterdrueckt nur normale Pushes, persistente nie`() {
        assertTrue(PushPayload.suppressInForeground(persistent = false, appInForeground = true))
        assertFalse(PushPayload.suppressInForeground(persistent = false, appInForeground = false))
        assertFalse(PushPayload.suppressInForeground(persistent = true, appInForeground = true))
        assertFalse(PushPayload.suppressInForeground(persistent = true, appInForeground = false))
    }

    @Test
    fun `B4 - Proaktiv-Seed nur im Hintergrund (DONNA-247 unveraendert)`() {
        assertTrue(PushPayload.shouldSaveProactiveSeed(appInForeground = false))
        assertFalse(PushPayload.shouldSaveProactiveSeed(appInForeground = true))
    }

    @Test
    fun `B4 - NtfyService verdrahtet Spec, Reminder-Channel und Foreground-Regel`() {
        val src = java.io.File("src/main/java/com/yourcompany/donna/NtfyService.kt").readText()
        assertTrue(src.contains("PushPayload.notificationSpec(parsed.persistent)"))
        assertTrue(src.contains("NotificationCompat.Builder(this, spec.channelId)"))
        assertTrue(src.contains(".setOngoing(spec.ongoing)"))
        assertTrue(src.contains(".setAutoCancel(spec.autoCancel)"))
        assertTrue(src.contains("PushPayload.suppressInForeground(parsed.persistent, appInForeground)"))
        assertTrue(src.contains("\"Donna Erinnerungen\""))
        // kein hartkodiertes setAutoCancel(true) mehr (sonst waere persistent wirkungslos)
        assertFalse(src.contains(".setAutoCancel(true)"))
    }

    @Test
    fun `MainActivity seedet Titel plus Body plus Nonce aus dem Tap-Extra`() {
        val src = java.io.File("src/main/java/com/yourcompany/donna/MainActivity.kt").readText()
        val fn = src.substringAfter("private fun emitProactiveChatEventIfNeeded").substringBefore("private fun scheduleProactiveChatEmit")
        assertTrue(fn.contains("ProactiveMessageModule.save(applicationContext, payload)"))
        // erst speichern, dann emittieren
        assertTrue(fn.indexOf("ProactiveMessageModule.save") < fn.indexOf("scheduleProactiveChatEmit(payload"))
        // Titel + Nonce werden aus dem Tap-Intent gelesen und in den Payload uebernommen
        assertTrue(fn.contains("getStringExtra(\"donna_proactive_title\")"))
        assertTrue(fn.contains("getStringExtra(\"donna_proactive_nonce\")"))
        assertTrue(fn.contains("PushPayload.proactiveJson(proactiveMsg, sessionId, proactiveTitle, nonce)"))
        // Extras werden nach dem Verbrauch entfernt (kein Doppel-Seed bei Activity-Recreation)
        assertTrue(fn.contains("intent.removeExtra(\"donna_proactive_message\")"))
        assertTrue(fn.contains("intent.removeExtra(\"donna_proactive_title\")"))
        assertTrue(fn.contains("intent.removeExtra(\"donna_proactive_nonce\")"))
    }
}

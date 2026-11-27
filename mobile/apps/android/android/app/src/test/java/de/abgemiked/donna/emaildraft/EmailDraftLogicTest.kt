package com.yourcompany.donna.emaildraft

import com.yourcompany.donna.device.DeviceActionKind
import com.yourcompany.donna.device.ResultKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailDraftLogicTest {
    private val AID = "da_0123456789abcdef"

    private fun req(to: Any? = "max@example.de", subject: Any? = "Hallo", body: Any? = "Text", extra: Map<String, Any?> = emptyMap()) =
        JSONObject().put("type", "email_draft").put("to", to).put("subject", subject).put("body", body).put("action_id", AID)
            .also { o -> extra.forEach { (k, v) -> o.put(k, v) } }

    private class Store : DoneStore {
        val ids = mutableSetOf<String>()
        override fun contains(id: String) = id in ids
        override fun mark(id: String) { ids += id }
    }

    @Test fun `gueltige Adressen`() {
        for (a in listOf("a@b.de", "max.mustermann@example.com", "x+tag@mail.example.org", "a_b-c@sub.domain.co")) {
            assertTrue(a, EmailDraftValidator.isSingleAddress(a))
        }
    }

    @Test fun `ungueltige Adressen - Liste, Leerraum, Smuggling`() {
        val bad = listOf(
            "a@b.de,c@d.de", "a@b.de;c@d.de", "a@b.de c@d.de", " a@b.de", "a@b.de ", "a@b.de\n", "a@b.de\r\nBcc: x@y.de",
            "a@b@c.de", "a@b.de?cc=x@y.de", "a@b.de?subject=x", "a@b.de&bcc=x", "@b.de", "a@", "a@b", "a@b.", "a@.de",
            "a..b@c.de", ".a@c.de", "a.@c.de", "a@-b.de", "a@b-.de", "a@b.d", "Max <a@b.de>", "a%40b.de", "ä@b.de", "a@b.de/",
            "a@b_c.de", "a@b.1", "a b@c.de", "a@b.de\u0000",
        )
        for (a in bad) assertFalse(a.replace("\n", "\\n").replace("\r", "\\r"), EmailDraftValidator.isSingleAddress(a))
    }

    @Test fun `Laengen der Adresse`() {
        assertTrue(EmailDraftValidator.isSingleAddress("a".repeat(64) + "@b.de"))
        assertFalse(EmailDraftValidator.isSingleAddress("a".repeat(65) + "@b.de"))
        val long = "a@" + "b".repeat(60) + "." + "c".repeat(60) + "." + "d".repeat(60) + "." + "e".repeat(60) + "." + "f".repeat(60) + ".de"
        assertTrue(long.length > 254)
        assertFalse(EmailDraftValidator.isSingleAddress(long))
    }

    @Test fun `Betreff - CRLF-Injektion und Laenge`() {
        assertFalse(EmailDraftValidator.isValidSubject("Hallo\r\nBcc: x@y.de"))
        assertFalse(EmailDraftValidator.isValidSubject("Hallo\nBcc: x@y.de"))
        assertFalse(EmailDraftValidator.isValidSubject("Hallo\rx"))
        assertFalse(EmailDraftValidator.isValidSubject("a\u0000b"))
        assertFalse(EmailDraftValidator.isValidSubject("a\u2028b"))
        assertTrue(EmailDraftValidator.isValidSubject("a".repeat(200)))
        assertFalse(EmailDraftValidator.isValidSubject("a".repeat(201)))
    }

    @Test fun `Text - Umbrueche erlaubt, Laenge und Steuerzeichen`() {
        assertTrue(EmailDraftValidator.isValidBody("Zeile 1\nZeile 2\r\n\tEinzug"))
        assertTrue(EmailDraftValidator.isValidBody("a".repeat(4000)))
        assertFalse(EmailDraftValidator.isValidBody("a".repeat(4001)))
        assertFalse(EmailDraftValidator.isValidBody("a\u0000b"))
    }

    @Test fun `Parser - gueltig, leerer Empfaenger erlaubt`() {
        assertEquals(EmailDraft("max@example.de", "Hallo", "Text"), EmailDraftParser.parse(req()))
        assertEquals(EmailDraft("", "Hallo", "Text"), EmailDraftParser.parse(req(to = "")))
        assertEquals(EmailDraft("", "", "nur Text"), EmailDraftParser.parse(req(to = "", subject = "", body = "nur Text")))
        assertEquals(EmailDraft("", "nur Betreff", ""), EmailDraftParser.parse(req(to = "", subject = "nur Betreff", body = "")))
    }

    @Test fun `Parser - mindestens Betreff oder Text`() {
        assertNull(EmailDraftParser.parse(req(subject = "", body = "")))
        assertNull(EmailDraftParser.parse(req(subject = "  ", body = "\n")))
        assertNull(EmailDraftParser.parse(JSONObject().put("type", "email_draft").put("action_id", AID)))
    }

    @Test fun `Parser - Smuggling, Listen und Fremdfelder werden abgelehnt`() {
        assertNull(EmailDraftParser.parse(req(to = "a@b.de?cc=x@y.de")))
        assertNull(EmailDraftParser.parse(req(to = "a@b.de,c@d.de")))
        assertNull(EmailDraftParser.parse(req(subject = "x\r\nBcc: y@z.de")))
        assertNull(EmailDraftParser.parse(req(extra = mapOf("cc" to "x@y.de"))))
        assertNull(EmailDraftParser.parse(req(extra = mapOf("bcc" to "x@y.de"))))
        assertNull(EmailDraftParser.parse(req(extra = mapOf("attachments" to "x"))))
        assertNull(EmailDraftParser.parse(req(extra = mapOf("card_session" to "s"))))
        assertNull(EmailDraftParser.parse(req(to = 5)))
        assertNull(EmailDraftParser.parse(req(subject = JSONObject())))
        assertNull(EmailDraftParser.parse(req().put("type", "calendar_change")))
    }

    @Test fun `Intent - nacktes mailto, Extras nur bei Inhalt`() {
        val s = EmailDraftIntent.spec(EmailDraft("max@example.de", "Hallo", "Text"))
        assertEquals("android.intent.action.SENDTO", s.action)
        assertEquals("mailto:", s.dataUri)
        assertEquals(listOf("max@example.de"), s.email)
        assertEquals("Hallo", s.subject)
        assertEquals("Text", s.text)
        val n = EmailDraftIntent.spec(EmailDraft("", "", "nur Text"))
        assertEquals("mailto:", n.dataUri)
        assertNull(n.email)
        assertNull(n.subject)
        assertEquals("nur Text", n.text)
    }

    @Test fun `Service - ok meldet ok und merkt die Kennung`() {
        val st = Store()
        var calls = 0
        val o = EmailDraftService.execute(true, req(), st) { calls++; LaunchResult.STARTED }
        assertEquals("ok", o.status)
        assertEquals(ResultKind.OK, o.kind)
        assertEquals("Entwurf geöffnet — gesendet hast du noch nichts.", o.message)
        assertEquals(1, calls)
        assertTrue(AID in st.ids)
    }

    @Test fun `Service - Einmal-Garantie, zweiter Tipp oeffnet nicht noch einmal und meldet ok`() {
        val st = Store()
        var calls = 0
        EmailDraftService.execute(true, req(), st) { calls++; LaunchResult.STARTED }
        val o = EmailDraftService.execute(true, req(), st) { calls++; LaunchResult.STARTED }
        assertEquals(1, calls)
        assertEquals("ok", o.status)
        assertEquals(ResultKind.OK, o.kind)
        assertFalse(o.message.contains("gesendet:", true))
    }

    @Test fun `Service - ohne Kennung nie ausfuehren, Karten-Kennung genuegt`() {
        var calls = 0
        val noId = req().apply { remove("action_id") }
        assertEquals(ResultKind.REJECTED, EmailDraftService.execute(true, noId, Store()) { calls++; LaunchResult.STARTED }.kind)
        assertEquals(0, calls)
        val card = noId.put("card_id", "abc-123-def")
        assertEquals(ResultKind.OK, EmailDraftService.execute(true, card, Store()) { calls++; LaunchResult.STARTED }.kind)
        assertEquals(1, calls)
        val badAid = req().put("action_id", "da_xyz")
        assertEquals(ResultKind.REJECTED, EmailDraftService.execute(true, badAid, Store()) { calls++; LaunchResult.STARTED }.kind)
    }

    @Test fun `Service - keine Mail-App ist unsupported und verbraucht die Karte nicht`() {
        val st = Store()
        val o = EmailDraftService.execute(true, req(), st) { LaunchResult.NO_APP }
        assertEquals("unsupported", o.status)
        assertEquals(ResultKind.UNSUPPORTED, o.kind)
        assertEquals("Keine Mail-App gefunden.", o.message)
        assertTrue(st.ids.isEmpty())
        assertEquals("ok", EmailDraftService.execute(true, req(), st) { LaunchResult.STARTED }.status)
    }

    @Test fun `Service - Fehler und Ausnahme sind failed`() {
        assertEquals(ResultKind.FAILED, EmailDraftService.execute(true, req(), Store()) { LaunchResult.ERROR }.kind)
        assertEquals(ResultKind.FAILED, EmailDraftService.execute(true, req(), Store()) { throw IllegalStateException("x") }.kind)
    }

    @Test fun `Service - Schalter aus oder ungueltig startet nichts`() {
        var calls = 0
        val l = { _: EmailDraft -> calls++; LaunchResult.STARTED }
        assertEquals(ResultKind.DISABLED, EmailDraftService.execute(false, req(), Store(), l).kind)
        assertEquals(ResultKind.REJECTED, EmailDraftService.execute(true, req(to = "a@b.de,c@d.de"), Store(), l).kind)
        assertEquals(0, calls)
    }

    @Test fun `Texte sagen nie gesendet oder erledigt`() {
        val all = listOf(
            EmailDraftMessages.opened(), EmailDraftMessages.alreadyOpened(), EmailDraftMessages.noMailApp(),
            EmailDraftMessages.rejected(), EmailDraftMessages.failed(),
        )
        for (m in all) {
            assertFalse(m, m.contains("erledigt", true))
            assertFalse(m, Regex("(?<!nicht)\\bgesendet\\b(?! hast)", RegexOption.IGNORE_CASE).containsMatchIn(m))
        }
    }

    @Test fun `Art ist nur im Chat, Standard aus, eigener Schluessel`() {
        assertEquals("email_draft", DeviceActionKind.EMAIL_DRAFT.key)
        assertEquals("device_action_email_draft", DeviceActionKind.EMAIL_DRAFT.prefKey)
        assertTrue(DeviceActionKind.EMAIL_DRAFT in DeviceActionKind.CHAT_ONLY)
    }
}

package com.yourcompany.donna

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Batch 3: sichere URLs, escapte Links, JSON-null-sichere Karten, SSE-Ende/Korrektur. */
class SafeContentTest {

    // ── UrlPolicy ────────────────────────────────────────────────────────────

    @Test
    fun `erlaubt https, geo und google-navigation`() {
        val maps = UrlPolicy.check("https://maps.google.com/?q=Berlin")
        assertNotNull(maps)
        assertEquals("maps.google.com", maps!!.host)
        assertEquals("maps.google.com", maps.displayTarget())
        assertNotNull(UrlPolicy.check("geo:49.1,10.7?q=YOUR_HOME_CITY"))
        assertEquals("Karten-App", UrlPolicy.check("geo:49.1,10.7")!!.displayTarget())
        assertNotNull(UrlPolicy.check("google.navigation:q=Nuernberg&mode=d"))
    }

    @Test
    fun `lehnt fremde Schemata ab`() {
        for (u in listOf(
            "http://example.com", "intent://scan/#Intent;scheme=zxing;end", "file:///sdcard/x", "content://contacts/1",
            "javascript:alert(1)", "tel:+49123", "sms:+49123", "market://details?id=x", "ftp://x.de", "data:text/html,hi",
            "android-app://de.x", "whatsapp://send?phone=1",
        )) {
            assertNull("sollte abgelehnt werden: $u", UrlPolicy.check(u))
        }
    }

    @Test
    fun `lehnt Phishing-Tricks, kaputte und leere URLs ab`() {
        assertNull(UrlPolicy.check("https://google.com@evil.example/login"))   // Nutzerinfo vor dem @
        assertNull(UrlPolicy.check("https:///nohost"))
        assertNull(UrlPolicy.check("https://exa mple.com"))                       // Leerzeichen
        assertNull(UrlPolicy.check("https://a.de/\nSet-Cookie: x"))
        assertNull(UrlPolicy.check("https://x.de/\"onmouseover=\""))            // Anfuehrungszeichen → ungueltiger URI
        assertNull(UrlPolicy.check(""))
        assertNull(UrlPolicy.check(null))
        assertNull(UrlPolicy.check("kein link"))
        assertNull(UrlPolicy.check("https://x.de/" + "a".repeat(2_100)))
    }

    @Test
    fun `Schema-Vergleich ist unabhaengig von Gross-Kleinschreibung`() {
        assertNotNull(UrlPolicy.check("HTTPS://Maps.Google.com/x"))
        assertNull(UrlPolicy.check("HTTP://example.com"))
    }

    // ── MarkdownHtml ─────────────────────────────────────────────────────────

    @Test
    fun `Anfuehrungszeichen im Link brechen das href-Attribut nicht aus`() {
        val html = MarkdownHtml.convert("Siehe https://example.com/pfad?x=\"onclick=\"alert(1)")
        assertFalse("kein rohes Anfuehrungszeichen im Link: $html", html.contains("onclick=\"alert"))
        assertFalse(html.contains("href=\"https://example.com/pfad?x=\""))
    }

    @Test
    fun `nur https-Links werden klickbar - http und fremde bleiben Text`() {
        assertTrue(MarkdownHtml.convert("https://example.com/a").contains("<a href=\"https://example.com/a\">"))
        assertFalse(MarkdownHtml.convert("http://example.com/a").contains("<a "))
        assertFalse(MarkdownHtml.convert("https://google.com@evil.example/x").contains("<a "))
    }

    @Test
    fun `HTML im Text wird escaped`() {
        val html = MarkdownHtml.convert("<script>alert(1)</script> & \"Zitat\"")
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("&amp;"))
        assertTrue(html.contains("&quot;Zitat&quot;"))
    }

    @Test
    fun `Markdown-Grundformen bleiben erhalten`() {
        assertEquals("<b>Titel</b>", MarkdownHtml.convert("# Titel"))
        assertEquals("• Punkt", MarkdownHtml.convert("- Punkt"))
        assertEquals("<b>fett</b> und <i>kursiv</i>", MarkdownHtml.convert("**fett** und *kursiv*"))
        assertEquals("a<br>b", MarkdownHtml.convert("a\nb"))
    }

    @Test
    fun `Link-Ende am Satzzeichen und & im Link werden korrekt escaped`() {
        val html = MarkdownHtml.convert("Mehr: https://example.com/a?x=1&y=2")
        assertTrue(html.contains("href=\"https://example.com/a?x=1&amp;y=2\""))
    }

    // ── JsonSafe / Telefonnummern ────────────────────────────────────────────

    @Test
    fun `JSON-null wird nie als Text null angezeigt`() {
        val o = JSONObject("""{"location":null,"condition":"Klar","temp_c":null,"humidity":63}""")
        assertEquals("", JsonSafe.str(o, "location"))
        assertEquals("Ort unbekannt", JsonSafe.str(o, "location", "Ort unbekannt"))
        assertEquals("Klar", JsonSafe.str(o, "condition"))
        assertEquals("", JsonSafe.str(o, "fehlt"))
        assertEquals("–", JsonSafe.numText(o, "temp_c"))
        assertEquals("–", JsonSafe.numText(o, "fehlt"))
        assertEquals("63", JsonSafe.numText(o, "humidity"))
    }

    @Test
    fun `WhatsApp-Nummer nur Ziffern`() {
        assertEquals("4915112345678", PhoneNumbers.forWhatsApp("+49 (151) 123-456 78"))
        assertEquals("", PhoneNumbers.forWhatsApp(null))
        // Anhaengsel wie „&text=…“ kann keine Parameter einschleusen: es bleiben nur Ziffern
        assertEquals("4915112345678", PhoneNumbers.forWhatsApp("+4915112345678&text=boese"))
    }
}

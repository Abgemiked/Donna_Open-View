package com.yourcompany.donna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-Unit-Tests für die zeilenweise SSE-Auswertung (UX-03: inkrementelles Lesen statt erst nach Stream-Ende). */
class SseLineParserTest {

    private fun delta(line: String): String = (SseLineParser.parse(line) as SseEvent.Delta).text

    @Test
    fun `Delta-Zeile liefert den Text, auch mit fuehrendem Leerzeichen`() {
        assertEquals(" Hallo", delta("""data: {"type":"delta","content":" Hallo"}"""))
        assertEquals("Hallo", delta("""data:{"type":"delta","content":"Hallo"}"""))
    }

    @Test
    fun `Whitespace-Delta gehoert zum Text`() {
        assertEquals("\n", delta("data: {\"type\":\"delta\",\"content\":\"\\n\"}"))
    }

    @Test
    fun `Fehler- und Warn-Deltas werden nicht angezeigt`() {
        assertNull(SseLineParser.parse("""data: {"type":"delta","content":"[error] boom"}"""))
        assertNull(SseLineParser.parse("""data: {"type":"delta","content":"[warn] hmm"}"""))
    }

    @Test
    fun `Altes Format mit content oder delta wird als Text gelesen`() {
        assertEquals("alt", delta("""data: {"content":"alt"}"""))
        assertEquals("alt2", delta("""data: {"delta":"alt2"}"""))
    }

    @Test
    fun `Klartext ohne JSON wird als Text gelesen, kaputtes JSON nicht`() {
        assertEquals("einfach Text", delta("data: einfach Text"))
        assertNull(SseLineParser.parse("data: {kaputt"))
    }

    @Test
    fun `DONE, Kommentare und Leerzeilen`() {
        assertTrue(SseLineParser.parse("data: [DONE]") is SseEvent.Done)
        assertNull(SseLineParser.parse(": keep-alive"))
        assertNull(SseLineParser.parse(""))
        assertNull(SseLineParser.parse("event: message"))
        assertNull(SseLineParser.parse("data: "))
    }

    @Test
    fun `Karten und Aktionen`() {
        val weather = SseLineParser.parse(
            """data: {"type":"card","card_type":"weather","data":{"location":"YOUR_HOME_CITY","temp_c":14}}""",
        ) as SseEvent.WeatherCard
        assertEquals("YOUR_HOME_CITY", weather.data.optString("location"))

        val map = SseLineParser.parse(
            """data: {"type":"card","card_type":"map","data":{"maps_url":"https://maps.example/x"}}""",
        ) as SseEvent.MapCard
        assertEquals("https://maps.example/x", map.url)

        val action = SseLineParser.parse(
            """data: {"type":"action","action":{"type":"tesla_navigate","destination":"Nuernberg"}}""",
        ) as SseEvent.Action
        assertEquals("tesla_navigate", action.action.optString("type"))

        assertNull(SseLineParser.parse("""data: {"type":"card","card_type":"unbekannt","data":{}}"""))
        assertNull(SseLineParser.parse("""data: {"type":"card","card_type":"map","data":{"maps_url":""}}"""))
        assertNotNull(SseLineParser.parse("""data: {"type":"action","action":{"type":"call"}}"""))
    }

    @Test
    fun `error-Event liefert die nutzertaugliche Backend-Meldung`() {
        val e = SseLineParser.parse(
            """data: {"type":"error","error":"claude_empty","message":"Ich hab gerade keine Antwort zustande gebracht, frag nochmal."}""",
        ) as SseEvent.Error
        assertEquals("Ich hab gerade keine Antwort zustande gebracht, frag nochmal.", e.message)
    }

    @Test
    fun `error-Event ohne message nutzt nur ganze Saetze, keine Codes`() {
        val sentence = SseLineParser.parse("""data: {"type":"error","error":"Verbindung zu Claude abgebrochen."}""") as SseEvent.Error
        assertEquals("Verbindung zu Claude abgebrochen.", sentence.message)
        val code = SseLineParser.parse("""data: {"type":"error","error":"generation_failed"}""") as SseEvent.Error
        assertEquals("", code.message)
    }

    @Test
    fun `JSON-null in content oder delta wird nie als Text null angezeigt`() {
        assertNull(SseLineParser.parse("""data: {"type":"delta","content":null}"""))
        assertNull(SseLineParser.parse("""data: {"content":null}"""))
        assertNull(SseLineParser.parse("""data: {"delta":null}"""))
        assertEquals("ok", (SseLineParser.parse("""data: {"content":null,"delta":"ok"}""") as SseEvent.Delta).text)
        assertNull(SseLineParser.parse("""data: {"type":"card","card_type":"map","data":{"maps_url":null}}"""))
    }

    @Test
    fun `Zeilenleser trennt bei LF und CRLF und begrenzt die Zeilenlaenge`() {
        val r = java.io.StringReader("data: a\r\ndata: b\n\nrest")
        assertEquals("data: a", SseLineReader.readLine(r))
        assertEquals("data: b", SseLineReader.readLine(r))
        assertEquals("", SseLineReader.readLine(r))
        assertEquals("rest", SseLineReader.readLine(r))
        assertNull(SseLineReader.readLine(r))

        val endless = java.io.StringReader("x".repeat(500))
        try {
            SseLineReader.readLine(endless, maxChars = 100)
            org.junit.Assert.fail("IOException erwartet")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }
}

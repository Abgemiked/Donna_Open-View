package com.yourcompany.donna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-Unit-Tests für Streaming-Text und satzweise Sprachausgabe (UX-03): Marker, Bereinigung, Satz-Zerlegung. */
class SpeechTextTest {

    // ── displayText ──────────────────────────────────────────────────────────

    @Test
    fun `vollstaendige Aktions-Marker werden entfernt`() {
        val raw = "Ich stelle den Wecker. [DONNA_ACTION:{\"type\":\"set_alarm\"}]"
        assertEquals("Ich stelle den Wecker. ", SpeechText.displayText(raw))
    }

    @Test
    fun `unvollstaendiger Aktions-Marker am Ende blitzt nie auf`() {
        assertEquals("Hallo ", SpeechText.displayText("Hallo ["))
        assertEquals("Hallo ", SpeechText.displayText("Hallo [DONNA_AC"))
        assertEquals("Hallo ", SpeechText.displayText("Hallo [DONNA_ACTION:{\"type\":\"cal"))
    }

    @Test
    fun `normale eckige Klammern bleiben erhalten`() {
        assertEquals("Das ist [wichtig] und gut", SpeechText.displayText("Das ist [wichtig] und gut"))
        assertEquals("Liste [1]", SpeechText.displayText("Liste [1]"))
    }

    // ── cleanForSpeech ───────────────────────────────────────────────────────

    @Test
    fun `Markdown und Links werden fuer die Sprachausgabe entfernt`() {
        assertEquals("Das ist wichtig.", SpeechText.cleanForSpeech("**Das** ist *wichtig*."))
        assertEquals("Siehe Wetter fuer morgen", SpeechText.cleanForSpeech("Siehe [Wetter](https://wetter.de/x) fuer morgen"))
        assertEquals("Mehr dazu", SpeechText.cleanForSpeech("Mehr dazu https://example.com/foo"))
        assertEquals("Titel", SpeechText.cleanForSpeech("## Titel"))
        assertEquals("Eins Zwei", SpeechText.cleanForSpeech("• Eins\n- Zwei"))
    }

    @Test
    fun `reine Zierzeichen ergeben leeren Sprechtext`() {
        assertEquals("", SpeechText.cleanForSpeech("***"))
        assertEquals("", SpeechText.cleanForSpeech("---"))
        assertEquals("", SpeechText.cleanForSpeech("   "))
    }

    // ── SentenceChunker ──────────────────────────────────────────────────────

    /** Text in kleinen Häppchen einspeisen wie ein Stream und alle ausgegebenen Chunks sammeln. */
    private fun streamIn(text: String, step: Int = 5, chunker: SentenceChunker = SentenceChunker()): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            i = minOf(text.length, i + step)
            out += chunker.update(text.substring(0, i), final = false)
        }
        out += chunker.update(text, final = true)
        return out
    }

    @Test
    fun `erster ausreichend langer Satz wird sofort ausgegeben, bevor der Stream endet`() {
        val c = SentenceChunker()
        // Satzende noch ohne Leerraum danach → noch nicht
        assertTrue(c.update("Heute wird es sonnig und warm.", final = false).isEmpty())
        // erstes Zeichen des naechsten Satzes ist da → der erste Satz geht sofort raus
        assertEquals(listOf("Heute wird es sonnig und warm."), c.update("Heute wird es sonnig und warm. Am", final = false))
        // und wird nicht ein zweites Mal ausgegeben
        assertTrue(c.update("Heute wird es sonnig und warm. Am Abend", final = false).isEmpty())
    }

    @Test
    fun `zwei Saetze ergeben zwei Chunks, alles wird genau einmal ausgegeben`() {
        val text = "Heute wird es sonnig und warm. Am Abend ziehen Wolken auf. Morgen regnet es dann den ganzen Tag lang."
        val chunks = streamIn(text)
        assertEquals(
            listOf(
                "Heute wird es sonnig und warm.",
                "Am Abend ziehen Wolken auf.",
                "Morgen regnet es dann den ganzen Tag lang.",
            ),
            chunks,
        )
    }

    @Test
    fun `zu kurzer erster Satz wird mit dem naechsten zusammengefasst`() {
        val chunks = streamIn("Ja. Das mache ich gerne fuer dich, sehr gern sogar.")
        assertEquals(listOf("Ja. Das mache ich gerne fuer dich, sehr gern sogar."), chunks)
    }

    @Test
    fun `Dezimalzahl, Ordnungszahl und Abkuerzung sind kein Satzende`() {
        val chunks = streamIn("Es sind heute 21.5 Grad draussen und es ist am 3. Mai ziemlich warm, z.B. im Sueden.")
        assertEquals(1, chunks.size)
        val chunks2 = streamIn("Frag bitte Dr. Meier wegen des Termins am Montag, ca. um zehn Uhr morgens.")
        assertEquals(1, chunks2.size)
    }

    @Test
    fun `Zeilenumbruch beendet einen Listenpunkt`() {
        val text = "Das steht heute auf deiner Liste:\n- Zahnarzt um zehn Uhr dreissig\n- Einkaufen gehen\n"
        assertEquals(
            listOf("Das steht heute auf deiner Liste:", "- Zahnarzt um zehn Uhr dreissig", "- Einkaufen gehen"),
            streamIn(text),
        )
    }

    @Test
    fun `Rest ohne Satzzeichen wird bei final ausgegeben`() {
        val c = SentenceChunker()
        assertTrue(c.update("Das ist ein ziemlich langer Satz ohne Punkt am Ende", final = false).isEmpty())
        assertEquals(listOf("Das ist ein ziemlich langer Satz ohne Punkt am Ende"),
            c.update("Das ist ein ziemlich langer Satz ohne Punkt am Ende", final = true))
    }

    @Test
    fun `ueberlanger Satz ohne Satzzeichen wird an Komma oder Leerzeichen geteilt`() {
        val long = (1..60).joinToString(" ") { "wort$it" }
        val c = SentenceChunker(maxChars = 120)
        val chunks = c.update(long, final = false)
        assertTrue("geteilt erwartet", chunks.isNotEmpty())
        assertTrue(chunks.all { it.length <= 130 })
    }

    @Test
    fun `Zierzeilen ohne Sprechtext erzeugen keinen Chunk`() {
        val chunks = streamIn("***\n---\nHallo, ich bin Donna und helfe dir sehr gerne weiter.")
        assertEquals(1, chunks.count { SpeechText.cleanForSpeech(it).contains("Donna") })
        assertTrue(chunks.none { SpeechText.cleanForSpeech(it).isEmpty() })
    }

    @Test
    fun `Satzende direkt am Textende zaehlt erst bei final oder mit folgendem Leerraum`() {
        val c = SentenceChunker()
        val t = "Das ist ein schoener langer Satz fuer den Test."
        assertTrue("noch kein Leerraum danach → noch warten", c.update(t, final = false).isEmpty())
        assertEquals(listOf(t), c.update(t, final = true))
    }

    @Test
    fun `Mio, Mrd, Std und Monatskuerzel sind kein Satzende`() {
        assertEquals(1, streamIn("Das Projekt kostet etwa 2,5 Mio. Euro und dauert ungefaehr 3 Std. insgesamt.").size)
        assertEquals(1, streamIn("Der Umsatz lag bei 1,2 Mrd. Euro im vergangenen Geschaeftsjahr insgesamt.").size)
        assertEquals(1, streamIn("Am 12. Okt. und am 3. Jan. ist der Laden leider ganz geschlossen heute.").size)
    }

    @Test
    fun `z B mit Leerzeichen und d h mit Leerzeichen sind kein Satzende`() {
        assertEquals(1, streamIn("Es gibt viele Obstsorten, z. B. Aepfel und Birnen, die man essen kann.").size)
        assertEquals(1, streamIn("Das ist sehr teuer, d. h. wir sollten lieber noch einmal darueber nachdenken.").size)
        assertEquals(1, streamIn("Zu den Gaesten zaehlten u. a. der Buergermeister und sein ganzes Team heute.").size)
    }

    @Test
    fun `Satzende vor schliessendem Anfuehrungszeichen oder Klammer wird erkannt`() {
        val chunks = streamIn("Er sagte: \u201eDas ist ein wirklich guter Plan fuer uns.\u201c Danach gingen wir alle gemeinsam los.")
        assertEquals(2, chunks.size)
        assertTrue(chunks[0].endsWith("uns.\u201c"))
        val paren = streamIn("Das ist alles ganz einfach zu erklaeren und zu verstehen (siehe oben). Danach geht es weiter im Text.")
        assertEquals(2, paren.size)
        assertTrue(paren[0].endsWith("oben)."))
    }

    @Test
    fun `ein echtes Satzende nach einem einzelnen Grossbuchstaben bleibt ein Satzende`() {
        // „Option B.“ ist KEIN Kuerzel nach z./d./u. → Satzende
        assertEquals(2, streamIn("Ich nehme am Ende doch lieber die Option B. Danach rufe ich dich kurz an.").size)
    }
}

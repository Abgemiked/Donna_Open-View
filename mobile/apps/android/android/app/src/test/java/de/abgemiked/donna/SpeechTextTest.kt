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
        // size==1 bewacht ECHT: ergäbe die Abkürzung/Zahl eine Satzgrenze, käme ein zweiter Chunk. Jede Abkürzung
        // steht >=16 Zeichen tief (sonst würde minFirstChars sie ohnehin verdecken), und alle Kommas liegen
        // < firstClauseChars(60), also greift der First-Chunk-Komma-Break NICHT und verfälscht die Zählung nicht.
        assertEquals("Ordnungszahl 3.", 1, streamIn("Wir sehen uns am 3. Mai beim Fest.").size)
        assertEquals("Abkuerzung Dr.", 1, streamIn("Ich schreibe gleich Dr. Meier an.").size)
        assertEquals("Abkuerzung ca.", 1, streamIn("Der Weg dauert gut ca. zehn Minuten.").size)
        assertEquals("z.B. ohne Leerzeichen", 1, streamIn("Ich esse gerne Obst, z.B. Aepfel.").size)
        assertEquals("z. B. mit Leerzeichen", 1, streamIn("Es gibt viel Obst, z. B. Birnen hier.").size)
        assertEquals("Mio. plus Dezimalkomma", 1, streamIn("Das Vorhaben kostet rund 2,5 Mio. Euro.").size)
    }

    @Test
    fun `kurzer erster Satz ab 16 Zeichen geht frueh raus (First-Chunk-Tune)`() {
        val c = SentenceChunker()
        // 19 Zeichen, Satzende + folgendes Zeichen → sofort raus. Mit dem alten minFirstChars=30 wäre er
        // noch mit dem nächsten Satz verschmolzen und erst später gesprochen worden (TTS-Latenz).
        assertEquals(listOf("Klar, mach ich das."), c.update("Klar, mach ich das. Und", final = false))
    }

    @Test
    fun `langer Eroeffnungssatz ohne fruehes Satzende wird am Komma gekappt (First-Chunk-Tune)`() {
        // >60 Zeichen ohne Satzende, dann Komma → erstes /tts-Fragment geht früh raus, statt auf den Punkt zu warten.
        // Das frühe Komma („Also pass auf,“, 13 Zeichen) kappt NICHT (unter der Schwelle) — erst das späte.
        val text = "Also pass auf, ich erklaere dir das jetzt ganz in Ruhe und wirklich ganz genau, damit es klar wird."
        val first = SentenceChunker().update(text, final = false)
        assertTrue("erstes Fragment kommt früh", first.isNotEmpty())
        assertTrue("Fragment endet am (späten) Komma", first.first().trimEnd().endsWith(","))
        // Bewacht die Schwelle firstClauseChars=60: es wird am SPÄTEN Komma (nach „genau") gekappt, NICHT am
        // frühen „Also pass auf,“ (13 Zeichen). Mit firstClauseChars=0 würde dieser contains-Check fehlschlagen.
        assertTrue("am späten Komma nach ~60 Zeichen", first.first().contains("genau,"))
        assertTrue("Fragment ist deutlich kürzer als der ganze Satz", first.first().length < text.length - 15)
    }

    @Test
    fun `kurzer erster Satz mit fruehem Komma bleibt ein Chunk (kein Zerhacken)`() {
        // Komma bei ~13 Zeichen < firstClauseChars(60) → KEIN Klausel-Break; der Satz bleibt zusammen.
        // Schützt gegen Zerstückelung UND gegen eine versehentliche firstClauseChars=0-Regression.
        val text = "Also pass auf, ich erklaere es dir."
        val c = SentenceChunker()
        assertTrue("vor dem Satzende (ohne Leerraum danach) noch nichts", c.update(text, final = false).isEmpty())
        assertEquals(listOf(text), c.update(text, final = true))
    }

    @Test
    fun `nur der erste Chunk wird am Komma gekappt, spaetere Saetze nicht`() {
        // clauseBreak gilt nur für den ERSTEN Chunk. Ein langer zweiter Satz mit spätem Komma (>60) darf NICHT
        // am Komma zerteilt werden.
        val first = "Kurzer Start hier."
        val second = "Und jetzt kommt ein wirklich sehr langer zweiter Satz mit einem Komma, das spaet steht."
        val chunks = SentenceChunker().update("$first $second", final = true)
        assertEquals(first, chunks.first())
        assertEquals("zweiter Satz bleibt EIN Chunk", 1, chunks.drop(1).size)
        assertTrue(chunks[1].contains("Komma, das spaet steht"))
    }

    @Test
    fun `Dezimalkomma ohne Leerraum kappt auch jenseits der Schwelle nicht`() {
        // >60 Zeichen, dann ein Dezimalkomma OHNE folgenden Leerraum („1,5“) → kein Klausel-Break (Leerraum-Pflicht).
        val text = "Der sehr ausfuehrliche erste Satz hier zieht sich lange hin und nennt 1,5 Liter."
        assertTrue("kein Break am Dezimalkomma → vor final nichts", SentenceChunker().update(text, final = false).isEmpty())
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

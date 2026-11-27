package com.yourcompany.donna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #226: Nachhören nach dem Erkenner-Ende — Fenster-Wahl, Zusammensetzen, Grenzen. */
class SpeechContinuationTest {
    private val long = SilenceProfile.LONG

    @Test
    fun `kurze Befehle werden sofort gesendet`() {
        for (t in listOf("Licht an", "Mach das Licht aus", "Wie spät ist es?", "weiter", "tschüss", "ja")) {
            assertEquals(t, 0L, SpeechContinuation.graceMs(t, long))
        }
    }

    @Test
    fun `trennbare Verbpartikel gelten nicht als unvollstaendig`() {
        assertFalse(SpeechContinuation.isIncomplete("Licht an"))
        assertFalse(SpeechContinuation.isIncomplete("Mach den Fernseher aus"))
        assertFalse(SpeechContinuation.isIncomplete("Ruf Mama auf"))
    }

    @Test
    fun `unvollstaendiger Satz bekommt das laengste Fenster, auch wenn kurz`() {
        assertTrue(SpeechContinuation.isIncomplete("Ich wollte dich fragen, ob"))
        assertTrue(SpeechContinuation.isIncomplete("Erinner mich an die Wäsche und"))
        assertTrue(SpeechContinuation.isIncomplete("Ähm also ich hab ähm"))
        assertTrue(SpeechContinuation.isIncomplete("Kannst du bitte das Licht im"))
        assertTrue(SpeechContinuation.isIncomplete("Ich hab überlegt,"))
        assertTrue(SpeechContinuation.isIncomplete("Ich weiß nicht..."))
        assertEquals(long.graceIncompleteMs, SpeechContinuation.graceMs("Kannst du bitte das Licht im", long))
        assertEquals(long.graceIncompleteMs, SpeechContinuation.graceMs("Ich wollte dir sagen dass", long))
    }

    @Test
    fun `fertige Befehle auf Partikel oder Pronomen bekommen kein Unvollstaendig-Fenster`() {
        // Review-Rat #226: „ein/zu/mit/denn/dann“ beenden fertige Befehle; Artikel/Pronomen erst bei langen Sätzen
        for (t in listOf(
            "Schalt das Licht ein", "Mach die Rollläden zu", "Nimm das mit", "Was ist das", "Erklär mir das",
            "Speicher das", "Was machst du denn", "Bis dann", "Wie spät ist es", "Mach das Licht aus",
        )) {
            assertFalse(t, SpeechContinuation.isIncomplete(t))
            assertEquals(t, 0L, SpeechContinuation.graceMs(t, long))
        }
        // längerer Befehl: höchstens das mittlere Fenster, nie das lange Unvollständig-Fenster
        assertTrue(SpeechContinuation.graceMs("Schalte das Licht im Wohnzimmer aus", long) <= long.graceMediumMs)
        assertTrue(SpeechContinuation.isIncomplete("Ich wollte dir noch erzählen von dem"))
    }

    @Test
    fun `mittel- und lange Saetze bekommen gestaffelte Fenster`() {
        assertEquals(long.graceMediumMs, SpeechContinuation.graceMs("Kannst du mir morgen früh Bescheid geben", long))
        assertEquals(
            long.graceLongMs,
            SpeechContinuation.graceMs("Ich wollte dir noch erzählen was heute beim Stream passiert ist", long),
        )
    }

    @Test
    fun `wer schon weitergesprochen hat bekommt mindestens das lange Fenster`() {
        assertEquals(long.graceLongMs, SpeechContinuation.graceMs("noch was", long, segmentsSoFar = 1))
    }

    @Test
    fun `leerer Text hat kein Fenster`() {
        assertEquals(0L, SpeechContinuation.graceMs("", long))
        assertEquals(0L, SpeechContinuation.graceMs("   ", long))
        assertFalse(SpeechContinuation.isIncomplete(""))
    }

    @Test
    fun `Profile sind monoton und kurz ist kuerzer als lang`() {
        for (p in SilenceProfile.values()) {
            assertTrue(p.key, p.graceMediumMs in 1 until p.graceLongMs)
            assertTrue(p.key, p.graceLongMs < p.graceIncompleteMs)
        }
        assertTrue(SilenceProfile.SHORT.graceLongMs < SilenceProfile.NORMAL.graceLongMs)
        assertTrue(SilenceProfile.NORMAL.graceLongMs < SilenceProfile.LONG.graceLongMs)
        // Ziel #226: Denkpausen von 2–3 s überstehen (Erkenner-Pause + Nachlauf) im Standardprofil
        assertTrue(SilenceProfile.DEFAULT.completeMs + SilenceProfile.DEFAULT.graceLongMs >= 3_000L)
    }

    @Test
    fun `join haengt an und verdoppelt nicht`() {
        assertEquals("hallo", SpeechContinuation.join("", "hallo"))
        assertEquals("hallo", SpeechContinuation.join("hallo", " "))
        assertEquals("ich wollte sagen dass es geht", SpeechContinuation.join("ich wollte sagen", "dass es geht"))
        // Erkenner liefert den alten Text erneut mit → nicht doppelt
        assertEquals("ich wollte sagen dass es geht", SpeechContinuation.join("ich wollte sagen", "Ich wollte sagen dass es geht").lowercase())
        // Satzzeichen des Erkenners stören die Dopplungsprüfung nicht
        assertEquals("Ich wollte sagen dass es geht", SpeechContinuation.join("ich wollte sagen,", "Ich wollte sagen dass es geht"))
    }

    @Test
    fun `Collector kurzer Befehl wird sofort fertig`() {
        val c = SpeechCollector()
        val d = c.offer("Licht an", 0L, long)
        assertEquals(ContinuationDecision.Finish("Licht an"), d)
        assertFalse(c.hasPending)
    }

    @Test
    fun `Collector sammelt Abschnitte bis zur Stille`() {
        val c = SpeechCollector()
        val first = c.offer("Ich wollte dir noch erzählen was heute beim Stream und", 0L, long)
        assertEquals(ContinuationDecision.Continue(long.graceIncompleteMs), first)
        assertTrue(c.hasPending)
        assertEquals("Ich wollte dir noch erzählen was heute beim Stream und das Publikum", c.display("das Publikum"))
        val second = c.offer("das Publikum war super", 3_000L, long)
        assertEquals(ContinuationDecision.Continue(long.graceLongMs), second)
        // Nachhör-Erkenner liefert nichts mehr → Gesamttext
        val done = c.offer("", 6_000L, long)
        assertEquals(
            ContinuationDecision.Finish("Ich wollte dir noch erzählen was heute beim Stream und das Publikum war super"),
            done,
        )
        assertFalse(c.hasPending)
    }

    @Test
    fun `Collector take liefert Gesammeltes und setzt zurueck`() {
        val c = SpeechCollector()
        c.offer("Ich muss dir noch was zu morgen sagen und zwar", 0L, long)
        assertEquals("Ich muss dir noch was zu morgen sagen und zwar", c.take())
        assertFalse(c.hasPending)
        assertEquals("", c.take())
    }

    @Test
    fun `takeWith behaelt das zuletzt Gehoerte bei Erkennerfehler`() {
        val c = SpeechCollector()
        c.offer("Ich muss dir noch was zu morgen sagen und", 0L, long)
        assertEquals("Ich muss dir noch was zu morgen sagen und zwar wegen dem Termin", c.takeWith("zwar wegen dem Termin"))
        assertFalse(c.hasPending)
    }

    @Test
    fun `Fertig oder Barge-in erzwingt Senden`() {
        val c = SpeechCollector()
        c.offer("Ich muss dir noch was zu morgen sagen und", 0L, long)
        val d = c.offer("zwar wegen dem Termin", 1_000L, long, allowContinue = false)
        assertEquals(ContinuationDecision.Finish("Ich muss dir noch was zu morgen sagen und zwar wegen dem Termin"), d)
        assertFalse(c.hasPending)
    }

    @Test
    fun `Obergrenzen beenden das Nachhoeren`() {
        val c = SpeechCollector()
        c.offer("eins zwei drei vier fünf sechs und", 0L, long)
        val overTime = c.offer("noch mehr und", SpeechContinuation.MAX_TOTAL_MS, long)
        assertTrue(overTime is ContinuationDecision.Finish)

        val s = SpeechCollector()
        var last: ContinuationDecision? = null
        for (i in 1..SpeechContinuation.MAX_SEGMENTS) last = s.offer("teil $i und", i * 100L, long)
        assertTrue(last is ContinuationDecision.Finish)
    }

    @Test
    fun `Restzeit deckelt das Nachhoeren`() {
        val c = SpeechCollector()
        assertEquals(SpeechContinuation.MAX_TOTAL_MS, c.remainingMs(5_000L))
        c.offer("Ich muss dir noch was zu morgen sagen und", 10_000L, long)
        assertEquals(SpeechContinuation.MAX_TOTAL_MS - 5_000L, c.remainingMs(15_000L))
        assertEquals(1_000L, c.remainingMs(10_000L + SpeechContinuation.MAX_TOTAL_MS + 5_000L))
    }

    @Test
    fun `Sprachende wird nur bei laengeren Saetzen aufgeschoben`() {
        val c = SpeechCollector()
        assertFalse(c.deferEndOfSpeech("Licht an", long))
        assertFalse(c.deferEndOfSpeech("", long))
        assertTrue(c.deferEndOfSpeech("Ich wollte dir noch erzählen was heute beim Stream passiert ist", long))
        assertTrue(c.deferEndOfSpeech("Erinner mich an die Wäsche und", long))
        // im Nachhören (schon gesammelt) zählt auch ein kurzer Nachsatz
        c.offer("Ich wollte dir noch erzählen was heute beim Stream passiert ist", 0L, long)
        assertTrue(c.deferEndOfSpeech("echt", long))
    }
}

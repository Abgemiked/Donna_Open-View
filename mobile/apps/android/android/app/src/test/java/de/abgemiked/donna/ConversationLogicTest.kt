package com.yourcompany.donna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #122 Paket A: reine Bausteine des Gespraechsmodus (Ende-Woerter, Hoerfenster, Messwerte, Fehler-Ende) und die
 * Quelltext-Vertraege, dass Blase und Activity-Overlay DIESELBEN Bausteine nutzen.
 */
class ConversationLogicTest {

    // ── Ende-Woerter ─────────────────────────────────────────────────────────

    @Test fun `ganze Abschlussaeusserung beendet das Gespraech`() {
        for (t in listOf(
            "tschüss", "Tschüss!", "TSCHÜSS", "tschuess", "tschüß", "tschuss", "Tschüssi", "ciao", "stopp", "Stopp.", "stop",
            "ende", "Ende!", "halt", "Halt.", "das war's", "Das war’s.", "das wars", "Das war alles", "fertig",
            "bis später", "bis spaeter", "Bis gleich!", "stopp danke", "okay, tschüss", "tschüss, danke", "Tschüss Donna",
            "ok danke tschüss donna", "ja, das war's dann",
        )) assertTrue("'$t' muss Ende sein", ConversationEnd.isEndPhrase(t))
    }

    @Test fun `Anfragen mit Ende-Wort mittendrin beenden NICHT`() {
        for (t in listOf(
            "halt mal die Musik an", "stopp den Timer", "ende der Woche habe ich Zeit", "danke, und morgen?",
            "bis später habe ich einen Termin", "stopp die Musik bitte", "wann ist Feierabend", "fertig machen für heute",
            "tschüss sagen auf Englisch", "das war's mit dem Wetter oder nicht",
        )) assertFalse("'$t' darf KEIN Ende sein", ConversationEnd.isEndPhrase(t))
    }

    @Test fun `danke allein und leere Eingabe sind kein Ende`() {
        for (t in listOf("danke", "Danke!", "okay", "okay danke", "donna", "", "   ", "...", "?!")) {
            assertFalse("'$t'", ConversationEnd.isEndPhrase(t))
        }
        assertFalse(ConversationEnd.isEndPhrase(null))
    }

    @Test fun `Ende-Woerter gelten nur im Weiterhoeren`() {
        assertTrue(ConversationEnd.shouldEnd(followUpListen = true, text = "tschüss"))
        // im ersten Turn ist „stopp den Timer“ eine normale Anfrage — und selbst ein einzelnes „stopp“ wird gesendet
        assertFalse(ConversationEnd.shouldEnd(followUpListen = false, text = "stopp"))
        assertFalse(ConversationEnd.shouldEnd(followUpListen = true, text = "stopp den Timer"))
    }

    @Test fun `Falten ist tolerant gegen Umlaute, ss, Gross-Klein und Apostrophe`() {
        assertEquals(ConversationEnd.fold("Tschüß"), ConversationEnd.fold("tschuess"))
        assertEquals(ConversationEnd.fold("TSCHÜSS"), ConversationEnd.fold("tschuss"))
        assertEquals("das wars", ConversationEnd.fold("Das war's!"))
        assertEquals("das wars", ConversationEnd.fold("Das war´s"))
        assertEquals(ConversationEnd.fold("später"), ConversationEnd.fold("spaeter"))
    }

    // ── Hoerfenster ──────────────────────────────────────────────────────────

    @Test fun `Hoerfenster Stufen 6 10 15, Standard 10`() {
        assertEquals(listOf(6, 10, 15), FollowUpWindow.STEPS_S)
        assertEquals(10, FollowUpWindow.DEFAULT_S)
        assertEquals("follow_up_window_s", FollowUpWindow.PREF_FOLLOW_UP_WINDOW_S)
    }

    @Test fun `Hoerfenster normalize - ungueltig ist Standard, sonst naechste Stufe`() {
        assertEquals(10, FollowUpWindow.normalize(null))
        assertEquals(10, FollowUpWindow.normalize(0))
        assertEquals(10, FollowUpWindow.normalize(-5))
        assertEquals(6, FollowUpWindow.normalize(6))
        assertEquals(6, FollowUpWindow.normalize(1))
        assertEquals(10, FollowUpWindow.normalize(10))
        assertEquals(10, FollowUpWindow.normalize(11))
        assertEquals(15, FollowUpWindow.normalize(15))
        assertEquals(15, FollowUpWindow.normalize(60))
    }

    @Test fun `Restzeit des Fensters und stiller Neustart des Erkenners`() {
        assertEquals(10_000L, FollowUpWindow.remainingMs(10_000, startedAt = 1_000, now = 1_000))
        assertEquals(4_000L, FollowUpWindow.remainingMs(10_000, startedAt = 1_000, now = 7_000))
        assertEquals(0L, FollowUpWindow.remainingMs(10_000, startedAt = 1_000, now = 99_000))
        // Uhr-Sprung rueckwaerts darf das Fenster nicht verlaengern
        assertEquals(10_000L, FollowUpWindow.remainingMs(10_000, startedAt = 5_000, now = 1_000))
        assertTrue(FollowUpWindow.shouldRestart(4_000))
        assertFalse(FollowUpWindow.shouldRestart(500))
        assertFalse(FollowUpWindow.shouldRestart(0))
    }

    // ── Messwerte ────────────────────────────────────────────────────────────

    @Test fun `Messwerte zaehlen und liefern genau eine Zusammenfassung`() {
        var now = 1_000_000L
        val s = ConversationStats { now }
        s.onTurn(followUp = false)
        s.onTurn(followUp = true)
        s.onTurn(followUp = true)
        s.onWindowExpired()
        s.onInterruption()
        now += 125_400L
        val sum = s.takeSummary(EndReason.SILENCE)
        assertNotNull(sum)
        assertEquals(3, sum!!.turns); assertEquals(2, sum.followUps); assertEquals(1, sum.expiredWindows)
        assertEquals(1, sum.interruptions); assertEquals(125L, sum.durationS); assertEquals(EndReason.SILENCE, sum.reason)
        assertNull("nur EINMAL", s.takeSummary(EndReason.BUTTON))
    }

    @Test fun `Gespraech ohne gesendete Frage erzeugt keine Zusammenfassung`() {
        assertNull(ConversationStats { 0L }.takeSummary(EndReason.BUTTON))
    }

    @Test fun `Push-Koerper enthaelt nur Zahlen und den Endegrund`() {
        val body = ConversationSummary(4, 3, 1, 0, 87, EndReason.KEYWORD).toPushBody()
        assertEquals(
            "{\"type\":\"conversation_summary\",\"conversation_summary\":{\"turns\":4,\"follow_ups\":3," +
                "\"expired_windows\":1,\"interruptions\":0,\"duration_s\":87,\"end_reason\":\"keyword\"}}",
            body,
        )
        assertEquals(listOf("silence", "keyword", "button", "limit", "error"), EndReason.values().map { it.wire })
        // jede Grundform ist gueltiges JSON ohne freie Texte: nur Ziffern und Aufzaehlungswerte
        for (r in EndReason.values()) {
            val b = ConversationSummary(1, 0, 0, 0, 1, r).toPushBody()
            assertTrue(b.contains("\"end_reason\":\"${r.wire}\""))
            assertFalse(b.contains("text") || b.contains("name") || b.contains("package"))
        }
    }

    // ── Fehler-Ende ──────────────────────────────────────────────────────────

    @Test fun `derselbe Fehler dreimal in Folge, ein anderer setzt zurueck`() {
        val a = OverlayErrors.fromHttp(502)
        val b = OverlayErrors.fromHttp(401)
        val t = RepeatedErrorTracker()
        assertFalse(t.record(a)); assertFalse(t.record(a))
        assertTrue(t.record(a))
        val t2 = RepeatedErrorTracker()
        assertFalse(t2.record(a)); assertFalse(t2.record(a))
        assertFalse(t2.record(b))          // anderer Fehler -> Zaehler zurueck
        assertFalse(t2.record(a)); assertFalse(t2.record(a))
        t2.reset()
        assertFalse(t2.record(a))
    }

    @Test fun `Statuszeile am Ende - Bis gleich, bei Fehler der Klartext`() {
        assertEquals("Bis gleich.", ConversationTexts.endingStatus(EndReason.SILENCE))
        assertEquals("Bis gleich.", ConversationTexts.endingStatus(EndReason.KEYWORD))
        assertEquals("Bis gleich.", ConversationTexts.endingStatus(null))
        assertTrue(ConversationTexts.endingStatus(EndReason.ERROR).contains("beende"))
    }

    // ── Quelltext-Vertraege: Blase und Overlay teilen die Bausteine ─────────

    private fun src(name: String) = java.io.File("src/main/java/com/yourcompany/donna/$name").readText()

    @Test fun `Blase und Activity nutzen dieselben Bausteine, keine zweite Kopie der Regeln`() {
        for (f in listOf("ConversationController.kt", "VoiceInputActivity.kt")) {
            val s = src(f)
            assertTrue("$f: Ende-Woerter ueber ConversationEnd.shouldEnd", s.contains("ConversationEnd.shouldEnd(machine.followUpListen, text, confirmationPending)"))
            assertTrue("$f: Hoerfenster ueber FollowUpWindow", s.contains("FollowUpWindow.readMs(prefs)"))
            assertTrue("$f: Restzeit ueber FollowUpWindow", s.contains("FollowUpWindow.remainingMs("))
            assertTrue("$f: Zusammenfassung ueber den Automaten", s.contains("machine.takeSummary("))
            assertTrue("$f: genau ein Sender", s.contains("ConversationSummarySender.send("))
            assertTrue("$f: Ende-Wort -> EndPhraseHeard", s.contains("OverlayEvent.EndPhraseHeard"))
            assertTrue("$f: Ende wird vom Automaten gesteuert", s.contains("OverlayEffect.ScheduleEnding") && s.contains("OverlayEffect.CloseConversation"))
            assertFalse("$f: keine eigene Kopie der Ende-Wort-Liste", s.contains("tschüss") || s.contains("tschuess"))
            assertFalse("$f: Pref-Schluessel steht nur in FollowUpWindow", s.contains("follow_up_window_s"))
        }
    }

    @Test fun `Pref-Schluessel des Hoerfensters steht nur an einer Stelle`() {
        val all = java.io.File("src/main/java/com/yourcompany/donna").listFiles { f -> f.extension == "kt" }!!.map { it.readText() }
        assertEquals(1, all.sumOf { Regex("\"follow_up_window_s\"").findAll(it).count() })
    }

    @Test fun `Hoerfenster wird bei jedem Start gelesen, nicht per Listener`() {
        for (f in listOf("ConversationController.kt", "VoiceInputActivity.kt")) {
            val s = src(f)
            assertFalse("$f: kein Listener auf die Prefs", s.contains("registerOnSharedPreferenceChangeListener"))
            val start = s.substringAfter("private fun startListening()").substringBefore("SpeechRecognizer.createSpeechRecognizer")
            assertTrue("$f: startListening liest das Fenster", start.contains("FollowUpWindow.readMs(prefs)"))
            assertTrue("$f: erster Hoerversuch bleibt bei 8 s", start.contains("OverlayTimings.NO_SPEECH_TIMEOUT_MS"))
        }
    }

    @Test fun `ENDED schliesst ueber die vorhandenen Schliess-Wege`() {
        val svc = src("LiveBubbleService.kt")
        assertTrue(svc.contains("override fun onConversationEnded()"))
        assertTrue(svc.substringAfter("override fun onConversationEnded()").substringBefore("}").contains("stopSelfAndBubble()"))
        assertTrue(src("VoiceInputActivity.kt").substringAfter("OverlayEffect.CloseConversation ->").take(300).contains("finish()"))
        // Paket B: die Blase holt den Text aus der gemeinsamen Abbildung OrbVisuals (die ConversationTexts.BYE nutzt)
        assertTrue("Statuszeile Bis gleich", svc.contains("OrbVisuals.of(state") && src("OrbVisual.kt").contains("ConversationTexts.BYE"))
    }

    @Test fun `Sender blockiert nie den Aufrufer und verschluckt Fehler`() {
        val s = src("ConversationSummarySender.kt")
        assertTrue(s.contains("Thread {"))
        assertTrue(s.contains("catch (e: Exception)"))
        assertTrue(s.contains("/tracking/push"))
        assertFalse("kein Text/Name im Sender", s.contains("message") || s.contains("utterance"))
    }

    @Test fun `Literal Ich habe nichts gehoert bleibt im Quelltext`() {
        assertTrue(src("ConversationController.kt").contains("Ich habe nichts gehört"))
        assertTrue(src("VoiceInputActivity.kt").contains("Ich habe nichts gehört"))
        assertTrue(src("OverlayStateMachine.kt").contains("Ich habe nichts gehört"))
    }
}

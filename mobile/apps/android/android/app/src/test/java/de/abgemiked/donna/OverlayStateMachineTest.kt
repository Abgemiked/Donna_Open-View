package com.yourcompany.donna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-Unit-Tests für den Zustandsautomaten des Overlays (UX-02/UX-04).
 * Mikrofon, Audio und Animation sind nur auf dem Gerät prüfbar.
 */
class OverlayStateMachineTest {

    private fun machine(conversation: Boolean = true) = OverlayStateMachine(conversationMode = conversation)

    private fun OverlayStateMachine.go(vararg events: OverlayEvent): OverlayTransition {
        var last = OverlayTransition(state)
        for (e in events) last = dispatch(e)
        return last
    }

    // ── Normalablauf ─────────────────────────────────────────────────────────

    @Test
    fun `Mic im Leerlauf startet Zuhoeren`() {
        val m = machine()
        val t = m.dispatch(OverlayEvent.MicRequested)
        assertEquals(OverlayState.LISTENING, t.state)
        assertEquals(listOf(OverlayEffect.StartListening), t.effects)
    }

    @Test
    fun `Sprachende des Erkenners fuehrt zu Denken ohne den Erkenner zu stoppen`() {
        val m = machine()
        m.dispatch(OverlayEvent.MicRequested)
        val t = m.dispatch(OverlayEvent.SpeechEnded(userInitiated = false))
        assertEquals(OverlayState.THINKING, t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun `Fertig-Taste beendet den Erkenner aktiv`() {
        val m = machine()
        m.dispatch(OverlayEvent.MicRequested)
        val t = m.dispatch(OverlayEvent.SpeechEnded(userInitiated = true))
        assertEquals(OverlayState.THINKING, t.state)
        assertEquals(listOf(OverlayEffect.FinishListening), t.effects)
    }

    @Test
    fun `erstes Delta fuehrt von Denken zu Sprechen`() {
        val m = machine()
        m.go(OverlayEvent.MicRequested, OverlayEvent.SpeechEnded(false), OverlayEvent.TextSubmitted())
        assertEquals(OverlayState.THINKING, m.state)
        assertEquals(OverlayState.SPEAKING, m.dispatch(OverlayEvent.ResponseStarted).state)
        // weitere Deltas aendern nichts
        assertEquals(OverlayState.SPEAKING, m.dispatch(OverlayEvent.ResponseStarted).state)
    }

    @Test
    fun `Gespraechsmodus an - nach echtem Sprachende wieder zuhoeren`() {
        val m = machine(conversation = true)
        m.go(OverlayEvent.TextSubmitted(), OverlayEvent.ResponseStarted)
        val t = m.dispatch(OverlayEvent.ResponseFinished(spokeAnything = true, hasPendingCards = false))
        assertEquals(OverlayState.LISTENING, t.state)
        // mit Echo-Schutz-Verzögerung, nicht sofort
        assertEquals(listOf(OverlayEffect.StartListeningAfterEcho), t.effects)
    }

    @Test
    fun `Gespraechsmodus aus - nach der Antwort Leerlauf`() {
        val m = machine(conversation = false)
        m.go(OverlayEvent.TextSubmitted(), OverlayEvent.ResponseStarted)
        val t = m.dispatch(OverlayEvent.ResponseFinished(spokeAnything = true, hasPendingCards = false))
        assertEquals(OverlayState.IDLE, t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun `wartende Aktionskarten verhindern das Auto-Relisten`() {
        val m = machine(conversation = true)
        m.go(OverlayEvent.TextSubmitted(), OverlayEvent.ResponseStarted)
        val t = m.dispatch(OverlayEvent.ResponseFinished(spokeAnything = true, hasPendingCards = true))
        assertEquals(OverlayState.IDLE, t.state)
    }

    @Test
    fun `stille Antwort (Live-Guard) loest kein Auto-Relisten aus`() {
        val m = machine(conversation = true)
        m.go(OverlayEvent.TextSubmitted(), OverlayEvent.ResponseStarted)
        val t = m.dispatch(OverlayEvent.ResponseFinished(spokeAnything = false, hasPendingCards = false))
        assertEquals(OverlayState.IDLE, t.state)
    }

    @Test
    fun `Antwort ohne Delta (nur Karten) endet im Leerlauf`() {
        val m = machine()
        m.dispatch(OverlayEvent.TextSubmitted())
        val t = m.dispatch(OverlayEvent.ResponseFinished(spokeAnything = false, hasPendingCards = true))
        assertEquals(OverlayState.IDLE, t.state)
    }

    // ── 8-Sekunden-Stille ────────────────────────────────────────────────────

    @Test
    fun `8 Sekunden ohne Sprache beim ersten Zuhoeren schliesst das Overlay leise`() {
        val m = machine()
        m.dispatch(OverlayEvent.MicRequested)
        val t = m.dispatch(OverlayEvent.NoSpeechTimeout)
        assertEquals(OverlayState.IDLE, t.state)
        assertTrue(OverlayEffect.CloseOverlayDelayed in t.effects)
        assertTrue(OverlayEffect.CancelListening in t.effects)
    }

    @Test
    fun `Stille nach einer Antwort schliesst nicht - die Antwort bleibt lesbar`() {
        val m = machine()
        m.go(OverlayEvent.TextSubmitted(), OverlayEvent.ResponseStarted)
        assertTrue(m.hasConversation)
        m.dispatch(OverlayEvent.ResponseFinished(true, false)) // → LISTENING (Auto-Relisten)
        val t = m.dispatch(OverlayEvent.NoSpeechTimeout)
        assertEquals(OverlayState.IDLE, t.state)
        assertFalse(OverlayEffect.CloseOverlayDelayed in t.effects)
    }

    @Test
    fun `NoSpeechTimeout ausserhalb von Zuhoeren wird ignoriert`() {
        val m = machine()
        m.go(OverlayEvent.TextSubmitted())
        assertEquals(OverlayState.THINKING, m.dispatch(OverlayEvent.NoSpeechTimeout).state)
    }

    // ── Stopp / Unterbrechen ─────────────────────────────────────────────────

    @Test
    fun `Stopp beim Sprechen bricht Anfrage und Ausgabe ab`() {
        val m = machine()
        m.go(OverlayEvent.TextSubmitted(), OverlayEvent.ResponseStarted)
        val t = m.dispatch(OverlayEvent.StopRequested)
        assertEquals(OverlayState.IDLE, t.state)
        assertTrue(OverlayEffect.CancelRequest in t.effects)
        assertTrue(OverlayEffect.StopSpeaking in t.effects)
    }

    @Test
    fun `Stopp beim Zuhoeren verwirft die Aufnahme`() {
        val m = machine()
        m.dispatch(OverlayEvent.MicRequested)
        val t = m.dispatch(OverlayEvent.StopRequested)
        assertEquals(OverlayState.IDLE, t.state)
        assertEquals(listOf(OverlayEffect.CancelListening), t.effects)
    }

    @Test
    fun `Mic waehrend Sprechen unterbricht und hoert sofort zu`() {
        val m = machine()
        m.go(OverlayEvent.TextSubmitted(), OverlayEvent.ResponseStarted)
        val t = m.dispatch(OverlayEvent.MicRequested)
        assertEquals(OverlayState.LISTENING, t.state)
        assertEquals(
            listOf(OverlayEffect.CancelRequest, OverlayEffect.StopSpeaking, OverlayEffect.StartListening),
            t.effects,
        )
    }

    @Test
    fun `getippter Text waehrend Sprechen unterbricht die alte Antwort`() {
        val m = machine()
        m.go(OverlayEvent.TextSubmitted(), OverlayEvent.ResponseStarted)
        val t = m.dispatch(OverlayEvent.TextSubmitted())
        assertEquals(OverlayState.THINKING, t.state)
        assertTrue(OverlayEffect.StopSpeaking in t.effects)
        assertTrue(OverlayEffect.CancelRequest in t.effects)
    }

    @Test
    fun `Erkennungsergebnis nach Sprachende bricht den Erkenner nicht erneut ab`() {
        val m = machine()
        m.go(OverlayEvent.MicRequested, OverlayEvent.SpeechEnded(false))
        val t = m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true))
        assertFalse(OverlayEffect.CancelListening in t.effects)
    }

    @Test
    fun `getippter Text verwirft einen noch laufenden Erkenner`() {
        val m = machine()
        m.go(OverlayEvent.MicRequested, OverlayEvent.SpeechEnded(false)) // Erkenner liefert noch sein Ergebnis
        val t = m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = false))
        assertEquals(OverlayState.THINKING, t.state)
        assertTrue(OverlayEffect.CancelListening in t.effects)
    }

    @Test
    fun `getippter Text waehrend Zuhoeren verwirft den Erkenner`() {
        val m = machine()
        m.dispatch(OverlayEvent.MicRequested)
        val t = m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = false))
        assertTrue(OverlayEffect.CancelListening in t.effects)
    }

    // ── Fehler ───────────────────────────────────────────────────────────────

    @Test
    fun `Fehler aus jedem Zustand fuehrt zu ERROR und raeumt auf`() {
        for (start in listOf(OverlayState.IDLE, OverlayState.LISTENING, OverlayState.THINKING, OverlayState.SPEAKING)) {
            val m = OverlayStateMachine(initial = start)
            val t = m.dispatch(OverlayEvent.Failed(OverlayErrors.fromHttp(502)))
            assertEquals(start.name, OverlayState.ERROR, t.state)
            assertTrue(OverlayEffect.CancelRequest in t.effects)
            assertTrue(OverlayEffect.StopSpeaking in t.effects)
        }
    }

    @Test
    fun `Nochmal nach Fehler startet das Zuhoeren`() {
        val m = OverlayStateMachine(initial = OverlayState.ERROR)
        val t = m.dispatch(OverlayEvent.MicRequested)
        assertEquals(OverlayState.LISTENING, t.state)
        assertEquals(listOf(OverlayEffect.StartListening), t.effects)
    }

    @Test
    fun `kein Auto-Relisten aus dem Fehlerzustand`() {
        val m = OverlayStateMachine(initial = OverlayState.ERROR)
        assertEquals(OverlayState.ERROR, m.dispatch(OverlayEvent.ResponseFinished(true, false)).state)
    }

    @Test
    fun `veraltete Ereignisse im Leerlauf werden ignoriert`() {
        val m = machine()
        assertEquals(OverlayState.IDLE, m.dispatch(OverlayEvent.ResponseStarted).state)
        assertEquals(OverlayState.IDLE, m.dispatch(OverlayEvent.SpeechEnded(false)).state)
        assertEquals(OverlayState.IDLE, m.dispatch(OverlayEvent.StopRequested).state)
    }

    @Test
    fun `Aborted (onPause) setzt alles zurueck ohne Fehler`() {
        val m = machine()
        m.go(OverlayEvent.TextSubmitted(), OverlayEvent.ResponseStarted)
        val t = m.dispatch(OverlayEvent.Aborted)
        assertEquals(OverlayState.IDLE, t.state)
        assertTrue(OverlayEffect.CancelListening in t.effects)
        assertTrue(OverlayEffect.StopSpeaking in t.effects)
        assertTrue(OverlayEffect.CancelRequest in t.effects)
    }

    // ── Fehlermeldungen ──────────────────────────────────────────────────────

    @Test
    fun `Erkennerfehler werden lesbar und ohne Rohcode gemeldet`() {
        val codes = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 99)
        for (heard in listOf(false, true)) {
            for (c in codes) {
                val e = OverlayErrors.fromRecognizer(c, heard)
                assertTrue("Code $c: Meldung leer", e.message.isNotBlank())
                assertFalse("Code $c: Rohcode in der Meldung", e.message.contains("($c)"))
                assertFalse("Code $c: Grossbuchstaben-Meldung", e.message == e.message.uppercase())
            }
        }
    }

    @Test
    fun `Stille wird nur ohne erkannte Sprache als Stille gewertet`() {
        assertTrue(OverlayErrors.isSilence(OverlayErrors.ERROR_SPEECH_TIMEOUT, heardSpeech = false))
        assertTrue(OverlayErrors.isSilence(OverlayErrors.ERROR_NO_MATCH, heardSpeech = false))
        assertFalse(OverlayErrors.isSilence(OverlayErrors.ERROR_NO_MATCH, heardSpeech = true))
        assertFalse(OverlayErrors.isSilence(OverlayErrors.ERROR_NETWORK, heardSpeech = false))
    }

    @Test
    fun `stiller Neustart nur fuer technische Fehler`() {
        assertTrue(OverlayErrors.isSilentlyRetryable(OverlayErrors.ERROR_SERVER_DISCONNECTED))
        assertTrue(OverlayErrors.isSilentlyRetryable(OverlayErrors.ERROR_RECOGNIZER_BUSY))
        assertFalse(OverlayErrors.isSilentlyRetryable(OverlayErrors.ERROR_NO_MATCH))
        assertFalse(OverlayErrors.isSilentlyRetryable(OverlayErrors.ERROR_SPEECH_TIMEOUT))
    }

    @Test
    fun `HTTP-Fehler und Ausnahmen werden unterschieden`() {
        assertEquals(UserError.Kind.AUTH, OverlayErrors.fromHttp(401).kind)
        assertEquals(UserError.Kind.SERVER, OverlayErrors.fromHttp(503).kind)
        assertTrue(OverlayErrors.fromHttp(502).message.contains("502"))
        assertEquals(UserError.Kind.NETWORK, OverlayErrors.fromException(java.net.SocketTimeoutException()).kind)
        assertEquals(UserError.Kind.NETWORK, OverlayErrors.fromException(java.net.UnknownHostException()).kind)
        assertEquals(UserError.Kind.UNKNOWN, OverlayErrors.fromException(IllegalStateException()).kind)
    }

    // ── Sprechpause ──────────────────────────────────────────────────────────

    @Test
    fun `Sprechpause - Standard ist grosszuegig, Flag mit Fallback`() {
        // ADHS: bis es den Einstellungen-Screen gibt, der laengste Standard (Denkpausen nicht abschneiden)
        assertEquals(SilenceProfile.LONG, SilenceProfile.DEFAULT)
        assertTrue(SilenceProfile.DEFAULT.completeMs >= 1_500L)
        assertEquals(SilenceProfile.LONG, SilenceProfile.fromKey("long"))
        assertEquals(SilenceProfile.SHORT, SilenceProfile.fromKey("short"))
        assertEquals(SilenceProfile.DEFAULT, SilenceProfile.fromKey(null))
        assertEquals(SilenceProfile.DEFAULT, SilenceProfile.fromKey("quatsch"))
        // „möglicherweise fertig“ darf nie länger sein als „fertig“
        for (p in SilenceProfile.values()) assertTrue(p.possiblyCompleteMs <= p.completeMs)
    }

    // ── Echo-Schutz / Turn-Limit (Auto-Relisten) ────────────────────────────

    private fun OverlayStateMachine.answer(spoke: Boolean = true, cards: Boolean = false, typing: Boolean = false): OverlayTransition {
        dispatch(OverlayEvent.ResponseStarted)
        return dispatch(OverlayEvent.ResponseFinished(spoke, cards, typing))
    }

    @Test
    fun `nach drei automatischen Relisten ohne Beruehrung ist Schluss`() {
        val m = machine()
        m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true))
        for (i in 1..MAX_AUTO_LISTENS) {
            assertEquals("Auto-Relisten $i", OverlayState.LISTENING, m.answer().state)
            m.dispatch(OverlayEvent.SpeechEnded(false))
            m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true)) // Echo/Sprache ohne Beruehrung
        }
        assertEquals("kein viertes Relisten", OverlayState.IDLE, m.answer().state)
    }

    @Test
    fun `Beruehrung setzt den Auto-Relisten-Zaehler zurueck`() {
        val m = machine()
        m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true))
        repeat(MAX_AUTO_LISTENS) {
            m.answer()
            m.dispatch(OverlayEvent.SpeechEnded(false))
            m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true))
        }
        assertEquals(MAX_AUTO_LISTENS, m.autoListens)
        m.dispatch(OverlayEvent.StopRequested)           // Nutzer greift ein
        assertEquals(0, m.autoListens)
        m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = false))
        assertEquals(OverlayState.LISTENING, m.answer().state)
    }

    @Test
    fun `getippte Frage setzt den Zaehler zurueck, Spracherkennung nicht`() {
        val m = machine()
        m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true))
        m.answer()
        assertEquals(1, m.autoListens)
        m.dispatch(OverlayEvent.SpeechEnded(false))
        m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true))
        assertEquals(1, m.autoListens)
        m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = false))
        assertEquals(0, m.autoListens)
    }

    @Test
    fun `offene Tastatur oder gefuelltes Eingabefeld unterdrueckt das Auto-Relisten`() {
        val m = machine()
        m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true))
        val t = m.answer(typing = true)
        assertEquals(OverlayState.IDLE, t.state)
        assertTrue(t.effects.isEmpty())
        assertEquals(0, m.autoListens)
    }

    @Test
    fun `Stille beim Tippen kann ueber Stopp aufgefangen werden ohne zu schliessen`() {
        val m = machine()
        m.dispatch(OverlayEvent.MicRequested)
        val t = m.dispatch(OverlayEvent.StopRequested)
        assertEquals(OverlayState.IDLE, t.state)
        assertFalse(OverlayEffect.CloseOverlayDelayed in t.effects)
    }

    @Test
    fun `Stopp in Denken verwirft auch einen noch wartenden Erkenner`() {
        val m = machine()
        m.go(OverlayEvent.MicRequested, OverlayEvent.SpeechEnded(false))
        val t = m.dispatch(OverlayEvent.StopRequested)
        assertTrue(OverlayEffect.CancelListening in t.effects)
    }

    // ── Barge-in ─────────────────────────────────────────────────────────────

    @Test
    fun `Barge-in aus jedem laufenden Zustand - Mic raeumt erst auf, hoert dann zu`() {
        for (start in listOf(OverlayState.THINKING, OverlayState.SPEAKING)) {
            val m = OverlayStateMachine(initial = start)
            val t = m.dispatch(OverlayEvent.MicRequested)
            assertEquals(start.name, OverlayState.LISTENING, t.state)
            // Reihenfolge: alte Anfrage und Ausgabe zuerst beenden, erst dann das Mikrofon oeffnen
            assertEquals(
                listOf(OverlayEffect.CancelRequest, OverlayEffect.StopSpeaking, OverlayEffect.StartListening),
                t.effects,
            )
        }
    }

    @Test
    fun `Stopp beim Sprechen und danach sofort neue getippte Frage - kein Rest der alten Antwort`() {
        val m = machine()
        m.go(OverlayEvent.TextSubmitted(), OverlayEvent.ResponseStarted)
        assertEquals(OverlayState.IDLE, m.dispatch(OverlayEvent.StopRequested).state)
        val t = m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = false))
        assertEquals(OverlayState.THINKING, t.state)
        // ein spaetes ResponseFinished der alten Antwort darf das Auto-Relisten nicht mehr ausloesen,
        // solange die neue Anfrage laeuft und noch kein Delta da ist … (Zustand THINKING → Ende ohne Sprache = Leerlauf)
        val late = m.dispatch(OverlayEvent.ResponseFinished(spokeAnything = false, hasPendingCards = false))
        assertEquals(OverlayState.IDLE, late.state)
    }

    // ── Freisprech-Gespraechsmodus ───────────────────────────────────────────

    @Test
    fun `echte Sprache haelt die Freisprech-Schleife am Laufen (Echo-Zaehler zurueck)`() {
        val m = machine()
        m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true, genuineSpeech = true))
        repeat(MAX_AUTO_LISTENS + 2) { i ->
            assertEquals("Runde $i", OverlayState.LISTENING, m.answer().state)
            m.dispatch(OverlayEvent.SpeechEnded(false))
            m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true, genuineSpeech = true))
            assertEquals(0, m.autoListens)
        }
    }

    @Test
    fun `harte Obergrenze fuer Freisprech-Runden ohne Beruehrung`() {
        val m = machine()
        m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true, genuineSpeech = true))
        repeat(MAX_HANDS_FREE_TURNS) {
            assertEquals(OverlayState.LISTENING, m.answer().state)
            m.dispatch(OverlayEvent.SpeechEnded(false))
            m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true, genuineSpeech = true))
        }
        assertEquals(OverlayState.IDLE, m.answer().state)
        // eine Beruehrung (Mic-Tap) setzt auch diesen Zaehler zurueck
        m.dispatch(OverlayEvent.MicRequested)
        assertEquals(0, m.handsFreeTurns)
    }

    @Test
    fun `Gespraechsmodus aus - kein Freisprechen, nur ein Durchgang`() {
        val m = machine(conversation = false)
        m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true, genuineSpeech = true))
        assertEquals(OverlayState.IDLE, m.answer().state)
    }

    @Test
    fun `Voice-Barge-in beendet Ausgabe und Anfrage und hoert zu`() {
        val m = machine()
        m.go(OverlayEvent.TextSubmitted(), OverlayEvent.ResponseStarted)
        val t = m.dispatch(OverlayEvent.VoiceBargeIn)
        assertEquals(OverlayState.LISTENING, t.state)
        assertEquals(
            listOf(OverlayEffect.CancelRequest, OverlayEffect.StopSpeaking, OverlayEffect.StartListening),
            t.effects,
        )
    }

    @Test
    fun `Voice-Barge-in zaehlt zum Limit und setzt es NICHT zurueck`() {
        val m = machine()
        m.go(OverlayEvent.TextSubmitted(), OverlayEvent.ResponseStarted)
        m.dispatch(OverlayEvent.ResponseFinished(true, false))   // Auto-Relisten → handsFreeTurns = 1
        m.dispatch(OverlayEvent.SpeechEnded(false))
        m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true, genuineSpeech = true))
        m.dispatch(OverlayEvent.ResponseStarted)
        val before = m.handsFreeTurns
        m.dispatch(OverlayEvent.VoiceBargeIn)
        assertEquals("Barge-in zaehlt als Freisprech-Runde", before + 1, m.handsFreeTurns)
        assertEquals(1, m.bargeIns)
    }

    @Test
    fun `hoechstens drei Voice-Barge-ins pro Beruehrung`() {
        val m = machine()
        m.dispatch(OverlayEvent.MicRequested)   // Beruehrung
        repeat(MAX_BARGE_INS_PER_TOUCH) { i ->
            m.dispatch(OverlayEvent.SpeechEnded(false))
            m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true, genuineSpeech = true))
            m.dispatch(OverlayEvent.ResponseStarted)
            assertTrue("Barge-in ${i + 1} erlaubt", m.canVoiceBargeIn)
            assertEquals(OverlayState.LISTENING, m.dispatch(OverlayEvent.VoiceBargeIn).state)
        }
        assertFalse(m.canVoiceBargeIn)
        m.dispatch(OverlayEvent.SpeechEnded(false))
        m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true, genuineSpeech = true))
        m.dispatch(OverlayEvent.ResponseStarted)
        // das vierte wird ignoriert: Donna spricht weiter
        val t = m.dispatch(OverlayEvent.VoiceBargeIn)
        assertEquals(OverlayState.SPEAKING, t.state)
        assertTrue(t.effects.isEmpty())
        // eine Beruehrung gibt die Barge-ins wieder frei
        m.dispatch(OverlayEvent.StopRequested)
        assertEquals(0, m.bargeIns)
        assertTrue(m.canVoiceBargeIn)
    }

    @Test
    fun `Voice-Barge-in nur im Sprechen`() {
        for (start in listOf(OverlayState.IDLE, OverlayState.LISTENING, OverlayState.THINKING, OverlayState.ERROR)) {
            val m = OverlayStateMachine(initial = start)
            val t = m.dispatch(OverlayEvent.VoiceBargeIn)
            assertEquals(start.name, start, t.state)
            assertTrue(t.effects.isEmpty())
        }
    }

    @Test
    fun `Barge-in-Schleife ist begrenzt - Echo kann das Limit nicht umgehen`() {
        val m = machine()
        m.dispatch(OverlayEvent.MicRequested)
        var rounds = 0
        // Echo-Szenario: jede Runde ein Barge-in, jedes Mal „echte“ Sprache, nie eine Beruehrung
        while (rounds < 200) {
            m.dispatch(OverlayEvent.SpeechEnded(false))
            m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true, genuineSpeech = true))
            m.dispatch(OverlayEvent.ResponseStarted)
            if (m.dispatch(OverlayEvent.VoiceBargeIn).state != OverlayState.LISTENING) break
            rounds++
        }
        assertTrue("Schleife nicht begrenzt: $rounds", rounds <= MAX_BARGE_INS_PER_TOUCH)
    }
}

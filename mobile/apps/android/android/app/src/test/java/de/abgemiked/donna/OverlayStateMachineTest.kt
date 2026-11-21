package com.yourcompany.donna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals(OverlayState.FOLLOW_UP, t.state)
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

    // ── #52: Sprach-Bestätigung einer wartenden Ja/Nein-Karte ────────────────

    @Test
    fun `#52 bestaetigbare Karte - es wird trotz offener Karte zugehoert (Sprach-Antwort)`() {
        val m = machine(conversation = true)
        m.go(OverlayEvent.TextSubmitted(), OverlayEvent.ResponseStarted)
        val t = m.dispatch(OverlayEvent.ResponseFinished(spokeAnything = true, hasPendingCards = true, voiceConfirmable = true))
        assertEquals(OverlayState.FOLLOW_UP, t.state)
        assertTrue(t.effects.contains(OverlayEffect.StartListeningAfterEcho))
    }

    @Test
    fun `#52 voiceConfirmable ohne Gespraechsmodus oder bei stiller Antwort bleibt IDLE`() {
        val off = machine(conversation = false)
        off.go(OverlayEvent.TextSubmitted(), OverlayEvent.ResponseStarted)
        assertEquals(OverlayState.IDLE, off.dispatch(OverlayEvent.ResponseFinished(true, true, voiceConfirmable = true)).state)
        val silent = machine(conversation = true)
        silent.go(OverlayEvent.TextSubmitted(), OverlayEvent.ResponseStarted)
        assertEquals(OverlayState.IDLE, silent.dispatch(OverlayEvent.ResponseFinished(false, true, voiceConfirmable = true)).state)
    }

    @Test
    fun `#52 Bestaetigungs-Zuhoeren ist durch die Auto-Relisten-Limits begrenzt`() {
        val m = machine(conversation = true)
        m.go(OverlayEvent.TextSubmitted())
        // MAX_AUTO_LISTENS (3) automatische Relisten in Folge ohne Berührung — danach ist Schluss (kein Endlos-Lauschen)
        repeat(MAX_AUTO_LISTENS) {
            m.dispatch(OverlayEvent.ResponseStarted)
            assertEquals(OverlayState.FOLLOW_UP, m.dispatch(OverlayEvent.ResponseFinished(true, true, voiceConfirmable = true)).state)
            m.dispatch(OverlayEvent.SpeechEnded(userInitiated = false))
            m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true, genuineSpeech = false))
        }
        m.dispatch(OverlayEvent.ResponseStarted)
        assertEquals(OverlayState.ENDING, m.dispatch(OverlayEvent.ResponseFinished(true, true, voiceConfirmable = true)).state)
    }

    @Test
    fun `#52 Default bleibt das alte Verhalten - offene Karte ohne voiceConfirmable fuehrt zu IDLE (Overlay-Activity)`() {
        val m = machine(conversation = true)
        m.go(OverlayEvent.TextSubmitted(), OverlayEvent.ResponseStarted)
        assertEquals(OverlayState.IDLE, m.dispatch(OverlayEvent.ResponseFinished(true, true)).state)
    }

    @Test
    fun `#52 Abbruch nach Sprach-Entscheidung (Aborted) setzt die Zaehler nicht zurueck`() {
        val m = machine(conversation = true)
        m.go(OverlayEvent.TextSubmitted(), OverlayEvent.ResponseStarted)
        m.dispatch(OverlayEvent.ResponseFinished(true, true, voiceConfirmable = true)) // → LISTENING (autoListens=1)
        val before = m.autoListens
        m.dispatch(OverlayEvent.SpeechEnded(userInitiated = false))
        val t = m.dispatch(OverlayEvent.Aborted)
        assertEquals(OverlayState.IDLE, t.state)
        assertEquals(before, m.autoListens)
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
    fun `Stille nach einer Antwort beendet ruhig ueber ENDING - ohne leises Schliessen`() {
        val m = machine()
        m.go(OverlayEvent.TextSubmitted(), OverlayEvent.ResponseStarted)
        assertTrue(m.hasConversation)
        m.dispatch(OverlayEvent.ResponseFinished(true, false)) // → FOLLOW_UP (Weiterhören)
        val t = m.dispatch(OverlayEvent.NoSpeechTimeout)
        assertEquals(OverlayState.ENDING, t.state)
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
        val t = m.dispatch(OverlayEvent.CancelRequested)
        assertEquals(OverlayState.IDLE, t.state)
        assertTrue(OverlayEffect.CancelRequest in t.effects)
        assertTrue(OverlayEffect.StopSpeaking in t.effects)
    }

    @Test
    fun `Stopp beim Zuhoeren verwirft die Aufnahme`() {
        val m = machine()
        m.dispatch(OverlayEvent.MicRequested)
        val t = m.dispatch(OverlayEvent.CancelRequested)
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
        assertEquals(OverlayState.IDLE, m.dispatch(OverlayEvent.CancelRequested).state)
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
    fun `Netz-Server-Fehler loesen den On-Device-Fallback aus, auch ERROR_SERVER`() {
        // Mike-Befund 2026-11-02: ERROR_SERVER MUSS dazugehören (kommt oft bei stabilem Netz), sonst fällt er
        // hart auf „kein Netz, ruf neu auf". NETWORK/TIMEOUT ohnehin. Nicht-Netzfehler lösen KEINEN Fallback aus.
        assertTrue(OverlayErrors.isRecognizerNetworkError(OverlayErrors.ERROR_NETWORK))
        assertTrue(OverlayErrors.isRecognizerNetworkError(OverlayErrors.ERROR_NETWORK_TIMEOUT))
        assertTrue(OverlayErrors.isRecognizerNetworkError(OverlayErrors.ERROR_SERVER))
        assertFalse(OverlayErrors.isRecognizerNetworkError(OverlayErrors.ERROR_NO_MATCH))
        assertFalse(OverlayErrors.isRecognizerNetworkError(OverlayErrors.ERROR_SPEECH_TIMEOUT))
        assertFalse(OverlayErrors.isRecognizerNetworkError(OverlayErrors.ERROR_INSUFFICIENT_PERMISSIONS))
    }

    @Test
    fun `STT-nicht-nutzbar meldet kein Netz NUR bei echtem Offline`() {
        // Online: der Erkenner streikt — NICHT „wenn du wieder online bist" vertrösten (Mikes falsche Offline-Meldung).
        val online = OverlayErrors.sttUnavailable(online = true)
        assertEquals(UserError.Kind.NETWORK, online.kind)
        assertFalse("online darf nicht von fehlendem Netz sprechen", online.message.contains("kein Netz"))
        assertFalse(online.message.contains("wieder online"))
        // Offline: die ehrliche „kein Netz"-Meldung ist korrekt.
        val offline = OverlayErrors.sttUnavailable(online = false)
        assertEquals(UserError.Kind.NETWORK, offline.kind)
        assertTrue(offline.message.contains("kein Netz"))
        // Keine der Meldungen verweist aufs Tippen (Panel hat kein Tippfeld → panelMessageFor soll sie durchlassen).
        assertFalse(online.message.contains("tipp", ignoreCase = true))
        assertFalse(offline.message.contains("tipp", ignoreCase = true))
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
            assertEquals("Auto-Relisten $i", OverlayState.FOLLOW_UP, m.answer().state)
            m.dispatch(OverlayEvent.SpeechEnded(false))
            m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true)) // Echo/Sprache ohne Beruehrung
        }
        assertEquals("kein viertes Relisten", OverlayState.ENDING, m.answer().state)
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
        m.dispatch(OverlayEvent.CancelRequested)           // Nutzer greift ein
        assertEquals(0, m.autoListens)
        m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = false))
        assertEquals(OverlayState.FOLLOW_UP, m.answer().state)
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
        val t = m.dispatch(OverlayEvent.CancelRequested)
        assertEquals(OverlayState.IDLE, t.state)
        assertFalse(OverlayEffect.CloseOverlayDelayed in t.effects)
    }

    @Test
    fun `Stopp in Denken verwirft auch einen noch wartenden Erkenner`() {
        val m = machine()
        m.go(OverlayEvent.MicRequested, OverlayEvent.SpeechEnded(false))
        val t = m.dispatch(OverlayEvent.CancelRequested)
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
        assertEquals(OverlayState.IDLE, m.dispatch(OverlayEvent.CancelRequested).state)
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
            assertEquals("Runde $i", OverlayState.FOLLOW_UP, m.answer().state)
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
            assertEquals(OverlayState.FOLLOW_UP, m.answer().state)
            m.dispatch(OverlayEvent.SpeechEnded(false))
            m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true, genuineSpeech = true))
        }
        assertEquals(OverlayState.ENDING, m.answer().state)
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
        m.dispatch(OverlayEvent.CancelRequested)
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

    @Test
    fun `Barge-in-Fehlalarm aus LISTENING setzt Donna im Sprechen fort`() {
        val m = machine()
        m.go(OverlayEvent.TextSubmitted(), OverlayEvent.ResponseStarted)
        m.dispatch(OverlayEvent.VoiceBargeIn)
        val before = m.bargeIns
        val t = m.dispatch(OverlayEvent.BargeInFalseAlarm)
        assertEquals(OverlayState.SPEAKING, t.state)
        assertEquals(listOf(OverlayEffect.CancelListening), t.effects)
        assertEquals("Fehlalarm zaehlt nicht doppelt", before, m.bargeIns)
        // danach normales Ende → Auto-Relisten
        assertEquals(OverlayState.FOLLOW_UP, m.dispatch(OverlayEvent.ResponseFinished(true, false)).state)
    }

    @Test
    fun `Barge-in-Fehlalarm aus THINKING moeglich, sonst ohne Wirkung`() {
        val m = machine()
        m.go(OverlayEvent.TextSubmitted(), OverlayEvent.ResponseStarted)
        m.dispatch(OverlayEvent.VoiceBargeIn)
        m.dispatch(OverlayEvent.SpeechEnded(false))
        assertEquals(OverlayState.THINKING, m.state)
        assertEquals(OverlayState.SPEAKING, m.dispatch(OverlayEvent.BargeInFalseAlarm).state)
        for (s in listOf(OverlayState.IDLE, OverlayState.ERROR)) {
            val x = OverlayStateMachine(initial = s)
            val t = x.dispatch(OverlayEvent.BargeInFalseAlarm)
            assertEquals(s, t.state)
            assertTrue(t.effects.isEmpty())
        }
        val sp = machine().also { it.go(OverlayEvent.TextSubmitted(), OverlayEvent.ResponseStarted) }
        assertEquals(OverlayState.SPEAKING, sp.dispatch(OverlayEvent.BargeInFalseAlarm).state)
        assertTrue(sp.state == OverlayState.SPEAKING)
    }

    @Test
    fun `Stille mit laufendem Gespraech zeigt keinen Fehlertext, ohne Gespraech den Hinweis`() {
        assertNull(OverlayErrors.silenceStatus(hasConversation = true, text = "Ich habe nichts gehört."))
        assertEquals("Ich habe nichts gehört.", OverlayErrors.silenceStatus(hasConversation = false, text = "Ich habe nichts gehört."))
    }

    // ═══ #122 Paket A: Gespraech erkennbar (FOLLOW_UP / ENDING / ENDED) ═════════════════════════════════

    /** Frage gestellt, Antwort gesprochen -> Automat steht im Weiterhoeren (FOLLOW_UP). */
    private fun OverlayStateMachine.toFollowUp(): OverlayStateMachine {
        dispatch(OverlayEvent.MicRequested)
        dispatch(OverlayEvent.SpeechEnded(false))
        dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true, genuineSpeech = true))
        dispatch(OverlayEvent.ResponseStarted)
        dispatch(OverlayEvent.ResponseFinished(true, false))
        return this
    }

    @Test
    fun `A - nach der gesprochenen Antwort geht es ins Weiterhoeren FOLLOW_UP, mit Echo-Schutz`() {
        val m = machine().toFollowUp()
        assertEquals(OverlayState.FOLLOW_UP, m.state)
        assertTrue(m.state.isListening)
        assertTrue(m.followUpListen)
    }

    @Test
    fun `A - FOLLOW_UP und LISTENING sind beide Zuhoer-Zustaende, alle anderen nicht`() {
        for (s in OverlayState.values()) {
            assertEquals(s.name, s == OverlayState.LISTENING || s == OverlayState.FOLLOW_UP, s.isListening)
        }
    }

    @Test
    fun `A - echte Sprache im Weiterhoeren fuehrt zu THINKING und zaehlt als Folgefrage`() {
        val m = machine().toFollowUp()
        assertEquals(OverlayState.THINKING, m.dispatch(OverlayEvent.SpeechEnded(false)).state)
        assertTrue("Erkenner-Ergebnis wird noch als Weiterhoeren ausgewertet", m.followUpListen)
        m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true, genuineSpeech = true))
        assertFalse(m.followUpListen)
        val sum = m.takeSummary()!!
        assertEquals(2, sum.turns)
        assertEquals(1, sum.followUps)
    }

    @Test
    fun `A - Fenster abgelaufen fuehrt ueber ENDING zu ENDED, Stille-Grund`() {
        val m = machine().toFollowUp()
        val t = m.dispatch(OverlayEvent.NoSpeechTimeout)
        assertEquals(OverlayState.ENDING, t.state)
        assertTrue(OverlayEffect.CancelListening in t.effects)
        assertTrue(t.effects.contains(OverlayEffect.ScheduleEnding(OverlayTimings.ENDING_MS)))
        assertFalse(OverlayEffect.CloseOverlayDelayed in t.effects)
        assertEquals(EndReason.SILENCE, m.endReason)
        val e = m.dispatch(OverlayEvent.EndingElapsed)
        assertEquals(OverlayState.ENDED, e.state)
        assertEquals(listOf(OverlayEffect.CloseConversation), e.effects)
        assertEquals(1, m.takeSummary()!!.expiredWindows)
    }

    @Test
    fun `A - ENDING dauert ca 1,5 s, bei Fehler-Ende laenger`() {
        assertEquals(1_500L, OverlayTimings.ENDING_MS)
        assertEquals(OverlayTimings.ENDING_MS, OverlayTimings.endingMs(EndReason.SILENCE))
        assertTrue(OverlayTimings.endingMs(EndReason.ERROR) > OverlayTimings.ENDING_MS)
    }

    @Test
    fun `A - erster Turn ohne Sprache wie bisher - IDLE und leises Schliessen`() {
        val m = machine()
        m.dispatch(OverlayEvent.MicRequested)
        val t = m.dispatch(OverlayEvent.NoSpeechTimeout)
        assertEquals(OverlayState.IDLE, t.state)
        assertTrue(OverlayEffect.CloseOverlayDelayed in t.effects)
        assertNull("ohne Frage keine Zusammenfassung", m.takeSummary())
    }

    @Test
    fun `A - Karten-Fall wie bisher - IDLE statt Weiterhoeren`() {
        val m = machine()
        m.go(OverlayEvent.TextSubmitted(), OverlayEvent.ResponseStarted)
        assertEquals(OverlayState.IDLE, m.dispatch(OverlayEvent.ResponseFinished(true, true)).state)
    }

    @Test
    fun `A - Limit 3 Weiterhoer-Runden ohne echte Sprache fuehrt ueber ENDING (Grund limit)`() {
        val m = machine()
        m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true))
        repeat(MAX_AUTO_LISTENS) {
            assertEquals(OverlayState.FOLLOW_UP, m.answer().state)
            m.dispatch(OverlayEvent.SpeechEnded(false))
            m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true))
        }
        val t = m.answer()
        assertEquals(OverlayState.ENDING, t.state)
        assertEquals(EndReason.LIMIT, m.endReason)
        assertEquals(OverlayState.ENDED, m.dispatch(OverlayEvent.EndingElapsed).state)
    }

    @Test
    fun `A - Limit 12 Freisprech-Runden fuehrt ueber ENDING (Grund limit)`() {
        val m = machine()
        m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true, genuineSpeech = true))
        repeat(MAX_HANDS_FREE_TURNS) {
            assertEquals(OverlayState.FOLLOW_UP, m.answer().state)
            m.dispatch(OverlayEvent.SpeechEnded(false))
            m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true, genuineSpeech = true))
        }
        assertEquals(OverlayState.ENDING, m.answer().state)
        assertEquals(EndReason.LIMIT, m.endReason)
    }

    @Test
    fun `A - ohne gesprochene Antwort bleibt es bei IDLE, nur das Limit endet`() {
        val m = machine()
        m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true))
        for (i in 1..MAX_AUTO_LISTENS) {
            m.answer(); m.dispatch(OverlayEvent.SpeechEnded(false)); m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true))
        }
        assertEquals(OverlayState.IDLE, m.answer(spoke = false).state)
    }

    @Test
    fun `A - StopRequested aus jedem Zustand fuehrt zu ENDED mit Grund button`() {
        for (start in listOf(
            OverlayState.IDLE, OverlayState.LISTENING, OverlayState.FOLLOW_UP, OverlayState.THINKING,
            OverlayState.SPEAKING, OverlayState.ENDING, OverlayState.ERROR,
        )) {
            val m = OverlayStateMachine(initial = start)
            val t = m.dispatch(OverlayEvent.StopRequested)
            assertEquals(start.name, OverlayState.ENDED, t.state)
            assertTrue(start.name, OverlayEffect.CloseConversation in t.effects)
            assertTrue(start.name, OverlayEffect.CancelListening in t.effects)
            assertTrue(start.name, OverlayEffect.CancelRequest in t.effects)
            assertTrue(start.name, OverlayEffect.StopSpeaking in t.effects)
        }
        val m = machine().toFollowUp()
        m.dispatch(OverlayEvent.StopRequested)
        assertEquals(EndReason.BUTTON, m.endReason)
        assertEquals(EndReason.BUTTON, m.takeSummary()!!.reason)
    }

    @Test
    fun `A - StopRequested waehrend ENDING behaelt den urspruenglichen Grund`() {
        val m = machine().toFollowUp()
        m.dispatch(OverlayEvent.NoSpeechTimeout)
        m.dispatch(OverlayEvent.StopRequested)
        assertEquals(OverlayState.ENDED, m.state)
        assertEquals(EndReason.SILENCE, m.takeSummary()!!.reason)
    }

    @Test
    fun `A - ENDED ist endgueltig - spaete Ereignisse aendern nichts`() {
        val m = machine().toFollowUp()
        m.dispatch(OverlayEvent.StopRequested)
        for (e in listOf(
            OverlayEvent.MicRequested, OverlayEvent.ResponseStarted, OverlayEvent.NoSpeechTimeout,
            OverlayEvent.TextSubmitted(fromRecognizer = true), OverlayEvent.Failed(OverlayErrors.fromHttp(500)),
            OverlayEvent.EndPhraseHeard, OverlayEvent.EndingElapsed, OverlayEvent.Aborted, OverlayEvent.CancelRequested,
        )) {
            val t = m.dispatch(e)
            assertEquals(OverlayState.ENDED, t.state)
            assertTrue(t.effects.isEmpty())
        }
    }

    @Test
    fun `A - CancelRequested ist der alte Stopp - Fenster bleibt offen`() {
        val m = machine()
        m.go(OverlayEvent.TextSubmitted(), OverlayEvent.ResponseStarted)
        val t = m.dispatch(OverlayEvent.CancelRequested)
        assertEquals(OverlayState.IDLE, t.state)
        assertTrue(OverlayEffect.CancelRequest in t.effects && OverlayEffect.StopSpeaking in t.effects)
        val f = machine().toFollowUp()
        val tf = f.dispatch(OverlayEvent.CancelRequested)
        assertEquals(OverlayState.IDLE, tf.state)
        assertEquals(listOf(OverlayEffect.CancelListening), tf.effects)
    }

    @Test
    fun `A - Ende-Wort im Weiterhoeren beendet, ohne zu senden (Grund keyword)`() {
        val m = machine().toFollowUp()
        m.dispatch(OverlayEvent.SpeechEnded(false))              // Erkenner liefert Ergebnis (THINKING)
        val t = m.dispatch(OverlayEvent.EndPhraseHeard)
        assertEquals(OverlayState.ENDING, t.state)
        assertEquals(EndReason.KEYWORD, m.endReason)
        assertTrue(OverlayEffect.CancelRequest in t.effects)
        assertEquals("keine zusaetzliche Frage gezaehlt", 1, m.stats.turns)
        assertEquals(OverlayState.ENDED, m.dispatch(OverlayEvent.EndingElapsed).state)
        assertEquals(EndReason.KEYWORD, m.takeSummary()!!.reason)
    }

    @Test
    fun `A - Ende-Wort wirkt nur im Weiterhoeren, nicht im ersten Turn`() {
        val m = machine()
        m.dispatch(OverlayEvent.MicRequested)
        m.dispatch(OverlayEvent.SpeechEnded(false))
        assertFalse(m.followUpListen)
        assertEquals(OverlayState.THINKING, m.dispatch(OverlayEvent.EndPhraseHeard).state)
        val n = OverlayStateMachine(initial = OverlayState.SPEAKING)
        assertEquals(OverlayState.SPEAKING, n.dispatch(OverlayEvent.EndPhraseHeard).state)
    }

    @Test
    fun `A - nach einer Anfrage in THINKING ist das Weiterhoer-Merkmal weg`() {
        val m = machine().toFollowUp()
        m.dispatch(OverlayEvent.SpeechEnded(false))
        m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true, genuineSpeech = true))
        assertFalse(m.followUpListen)
        assertEquals(OverlayState.THINKING, m.dispatch(OverlayEvent.EndPhraseHeard).state)
    }

    @Test
    fun `A - dreimal derselbe Fehler beendet mit Klartext, ein anderer setzt zurueck`() {
        val a = OverlayErrors.fromHttp(502)
        val b = OverlayErrors.fromHttp(401)
        val m = machine()
        assertEquals(OverlayState.ERROR, m.dispatch(OverlayEvent.Failed(a)).state)
        assertEquals(OverlayState.ERROR, m.dispatch(OverlayEvent.Failed(a)).state)
        assertEquals(OverlayState.ERROR, m.dispatch(OverlayEvent.Failed(b)).state)   // anderer Fehler -> Zaehler zurueck
        assertEquals(OverlayState.ERROR, m.dispatch(OverlayEvent.Failed(a)).state)
        assertEquals(OverlayState.ERROR, m.dispatch(OverlayEvent.Failed(a)).state)
        val t = m.dispatch(OverlayEvent.Failed(a))
        assertEquals(OverlayState.ENDING, t.state)
        assertEquals(EndReason.ERROR, m.endReason)
        assertTrue(t.effects.contains(OverlayEffect.ScheduleEnding(OverlayTimings.ENDING_ERROR_MS)))
    }

    @Test
    fun `A - ein erfolgreicher Turn setzt den Fehlerzaehler zurueck`() {
        val a = OverlayErrors.fromHttp(502)
        val m = machine()
        m.dispatch(OverlayEvent.Failed(a)); m.dispatch(OverlayEvent.Failed(a))
        m.go(OverlayEvent.TextSubmitted(), OverlayEvent.ResponseStarted, OverlayEvent.ResponseFinished(true, false))
        assertEquals(OverlayState.ERROR, m.dispatch(OverlayEvent.Failed(a)).state)
    }

    @Test
    fun `A - Tippen auf Mikrofon waehrend ENDING setzt das Gespraech fort`() {
        val m = machine().toFollowUp()
        m.dispatch(OverlayEvent.NoSpeechTimeout)
        val t = m.dispatch(OverlayEvent.MicRequested)
        assertEquals(OverlayState.LISTENING, t.state)
        assertNull(m.endReason)
        assertEquals("spaetes EndingElapsed ist wirkungslos", OverlayState.LISTENING, m.dispatch(OverlayEvent.EndingElapsed).state)
    }

    @Test
    fun `A - Aborted waehrend ENDING schliesst, sonst IDLE wie bisher`() {
        val m = machine().toFollowUp()
        m.dispatch(OverlayEvent.NoSpeechTimeout)
        assertEquals(OverlayState.ENDED, m.dispatch(OverlayEvent.Aborted).state)
        val n = machine().toFollowUp()
        assertEquals(OverlayState.IDLE, n.dispatch(OverlayEvent.Aborted).state)
    }

    @Test
    fun `A - Messwerte - Unterbrechungen werden gezaehlt, Zusammenfassung genau einmal`() {
        var now = 0L
        val m = OverlayStateMachine(clock = { now })
        m.go(OverlayEvent.TextSubmitted(), OverlayEvent.ResponseStarted)
        m.dispatch(OverlayEvent.MicRequested)                   // Unterbrechen per Tipp
        m.dispatch(OverlayEvent.SpeechEnded(false))
        m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true, genuineSpeech = true))
        m.dispatch(OverlayEvent.ResponseStarted)
        m.dispatch(OverlayEvent.VoiceBargeIn)                   // Unterbrechen per Stimme
        now = 61_000
        val s = m.takeSummary(EndReason.BUTTON)!!
        assertEquals(2, s.turns); assertEquals(2, s.interruptions); assertEquals(61L, s.durationS)
        assertNull(m.takeSummary(EndReason.BUTTON))
    }

    @Test
    fun `A - Zuhoer-Aktionen laufen auch aus FOLLOW_UP - SpeechEnded und Fehler`() {
        val m = machine().toFollowUp()
        val f = machine().toFollowUp()
        assertEquals(OverlayState.THINKING, m.dispatch(OverlayEvent.SpeechEnded(userInitiated = true)).state)
        assertEquals(OverlayState.ERROR, f.dispatch(OverlayEvent.Failed(OverlayErrors.fromHttp(502))).state)
    }
}

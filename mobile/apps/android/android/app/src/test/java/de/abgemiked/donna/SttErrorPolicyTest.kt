package com.yourcompany.donna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #146: Was passiert bei einem Erkennerfehler? Reine Entscheidung, damit nie mehr pauschal „kein Netz“ erscheint. */
class SttErrorPolicyTest {
    private fun decide(
        code: Int,
        state: OverlayState = OverlayState.LISTENING,
        online: Boolean = true,
        answered: Boolean = false,
        triedOffline: Boolean = false,
        restarts: Int = 0,
        heard: Boolean = false,
        requestActive: Boolean = false,
    ) = SttErrorPolicy.decide(code, state, online, answered, triedOffline, restarts, heard, requestActive)

    private fun shown(a: SttErrorAction) = (a as SttErrorAction.Show).error

    @Test
    fun `Code 11, 5 und 8 starten still neu - Verbindung zum Erkennungsdienst ist kein Netzproblem`() {
        for (c in listOf(11, 5, 8)) {
            assertEquals("Code $c", SttErrorAction.RestartSilently, decide(c))
            assertEquals("Code $c", SttErrorAction.RestartSilently, decide(c, restarts = SttErrorPolicy.MAX_SILENT_RESTARTS - 1))
        }
    }

    @Test
    fun `stille Neustarts haben eine Obergrenze - keine Endlosschleife`() {
        var restarts = 0
        var steps = 0
        while (decide(11, restarts = restarts) == SttErrorAction.RestartSilently) { restarts++; steps++; assertTrue(steps < 10) }
        assertEquals(SttErrorPolicy.MAX_SILENT_RESTARTS, restarts)
    }

    @Test
    fun `Code 11 nach verbrauchten Neustarts im ersten Zuhoeren - Erkenner hakt, NICHT kein Netz`() {
        val e = shown(decide(11, restarts = SttErrorPolicy.MAX_SILENT_RESTARTS, online = false))
        assertEquals(SttErrorPolicy.TEXT_HICCUP, e.message)
        assertFalse(e.message.contains("Netz"))
        assertTrue(e.kind != UserError.Kind.NETWORK)
    }

    @Test
    fun `Codes 1, 2 und 4 wechseln zuerst auf die Erkennung auf dem Geraet - auch im Weiterhoeren`() {
        for (c in listOf(1, 2, 4)) {
            assertEquals("Code $c", SttErrorAction.OfflineRetry, decide(c))
            assertEquals("Code $c", SttErrorAction.OfflineRetry, decide(c, state = OverlayState.FOLLOW_UP))
        }
        // aus THINKING nur, solange keine Anfrage laeuft
        assertEquals(SttErrorAction.OfflineRetry, decide(4, state = OverlayState.THINKING))
        assertEquals(SttErrorAction.QuietIdle, decide(4, state = OverlayState.THINKING, requestActive = true, answered = true))
    }

    @Test
    fun `nach dem Geraete-Versuch im ersten Zuhoeren - Netz-Meldung nur bei fehlendem Netz`() {
        for (c in listOf(1, 2, 4)) {
            assertEquals(SttErrorPolicy.TEXT_NO_NET, shown(decide(c, triedOffline = true, online = false)).message)
            assertEquals(SttErrorPolicy.TEXT_HICCUP, shown(decide(c, triedOffline = true, online = true)).message)
        }
    }

    @Test
    fun `im Weiterhoeren oder nach vorliegender Antwort nie eine Meldung - still in Ruhe`() {
        for (c in listOf(1, 2, 3, 4, 5, 8, 11, 99)) { // 9, 12, 13 siehe unten: nie unterdrueckt
            for (online in listOf(true, false)) {
                assertEquals("FOLLOW_UP $c", SttErrorAction.QuietIdle,
                    decide(c, state = OverlayState.FOLLOW_UP, online = online, triedOffline = true, restarts = 5, heard = true))
                assertEquals("beantwortet $c", SttErrorAction.QuietIdle,
                    decide(c, answered = true, online = online, triedOffline = true, restarts = 5, heard = true))
            }
        }
    }

    @Test
    fun `Stille und kein Treffer ohne Sprache bleiben wie bisher Stille`() {
        for (c in listOf(6, 7)) {
            assertEquals(SttErrorAction.Silence, decide(c, heard = false))
            assertEquals(SttErrorAction.Silence, decide(c, state = OverlayState.FOLLOW_UP, heard = false))
        }
        assertEquals(SttErrorAction.Silence, decide(6, answered = true, heard = true))
        // kein Treffer MIT gehoerter Sprache im ersten Zuhoeren: wie bisher die Meldung
        assertEquals(UserError.Kind.NO_MATCH, shown(decide(7, heard = true)).kind)
    }

    @Test
    fun `uebrige Fehler im ersten Zuhoeren behalten ihre Meldung`() {
        assertEquals(UserError.Kind.MIC_PERMISSION, shown(decide(9)).kind)
        assertEquals(UserError.Kind.MIC_UNAVAILABLE, shown(decide(3, restarts = SttErrorPolicy.MAX_SILENT_RESTARTS)).kind)
        // nach einem Geraete-Versuch statt der geteilten „tippe“-Meldung die ehrliche ohne Tipp-Bezug
        assertEquals(UserError.Kind.NO_MATCH, shown(decide(7, triedOffline = true, heard = true)).kind)
    }

    @Test
    fun `neue Meldungstexte sind kurz und ohne Tipp-Bezug`() {
        for (t in listOf(SttErrorPolicy.TEXT_NO_NET, SttErrorPolicy.TEXT_HICCUP)) {
            assertTrue(t, t.length <= 40)
            assertFalse(t.contains("tipp", ignoreCase = true))
        }
        assertEquals("Kein Netz für die Spracherkennung.", SttErrorPolicy.TEXT_NO_NET)
        assertEquals("Die Spracherkennung hakt gerade.", SttErrorPolicy.TEXT_HICCUP)
    }

    @Test
    fun `online heisst Internet-Faehigkeit und validiert ODER wenigstens verbunden`() {
        assertTrue(SttErrorPolicy.isOnline(hasInternet = true, validated = true, connected = true))
        assertTrue(SttErrorPolicy.isOnline(true, validated = false, connected = true)) // VPN, privates DNS, Netzwechsel
        assertFalse(SttErrorPolicy.isOnline(true, validated = false, connected = false))
        assertFalse(SttErrorPolicy.isOnline(hasInternet = false, validated = true, connected = true))
    }

    @Test
    fun `fehlende Berechtigung und nicht verfuegbare Sprache werden NIE unterdrueckt - auch nicht im Weiterhoeren`() {
        for (state in listOf(OverlayState.LISTENING, OverlayState.FOLLOW_UP)) {
            for (answered in listOf(false, true)) {
                for (tried in listOf(false, true)) {
                    assertEquals(UserError.Kind.MIC_PERMISSION, shown(decide(9, state = state, answered = answered, triedOffline = tried)).kind)
                    for (c in listOf(12, 13)) {
                        val e = shown(decide(c, state = state, answered = answered, triedOffline = tried))
                        assertEquals("Code $c", UserError.Kind.RECOGNIZER_UNAVAILABLE, e.kind)
                        assertFalse(e.message.contains("nicht verstehen"))
                    }
                }
            }
        }
    }

    @Test
    fun `TurnGuard - Antwort sperrt, jeder vom Nutzer ausgeloeste Hoerstart und jede neue Anfrage hebt die Sperre auf`() {
        val g = TurnGuard()
        assertFalse(g.answered)
        g.onAnswer("")
        assertFalse(g.answered) // leere Antwort zaehlt nicht
        g.onAnswer("Hallo")
        assertTrue(g.answered)
        g.onUserListenStart() // Funktionstaste / Mikrofon-Taste / Unterbrechen per Stimme
        assertFalse(g.answered)
        g.onAnswer("Hallo")
        g.onRequestStarted()
        assertFalse(g.answered)
    }

    @Test
    fun `Szenario - Zug 1 beantwortet, neuer Hoerstart, echter Fehler im ersten Zuhoeren wird gemeldet`() {
        val g = TurnGuard()
        g.onAnswer("Antwort")
        // im Weiterhoeren desselben Zugs: still
        assertEquals(SttErrorAction.QuietIdle, decide(4, state = OverlayState.FOLLOW_UP, answered = g.answered, triedOffline = true))
        // Nutzer startet neu (Taste): Sperre aus -> echter Netzfehler nach dem Geraete-Versuch wird gemeldet
        g.onUserListenStart()
        assertEquals(SttErrorPolicy.TEXT_NO_NET, shown(decide(4, answered = g.answered, triedOffline = true, online = false)).message)
        assertEquals(UserError.Kind.MIC_UNAVAILABLE, shown(decide(3, answered = g.answered, restarts = SttErrorPolicy.MAX_SILENT_RESTARTS)).kind)
    }

    @Test
    fun `online heisst auch verbunden ohne Validierung - der echte Verbindungszustand zaehlt`() {
        assertFalse(SttErrorPolicy.isOnline(hasInternet = true, validated = false, connected = false))
    }
}

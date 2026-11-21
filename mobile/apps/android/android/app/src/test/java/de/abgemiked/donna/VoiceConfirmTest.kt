package com.yourcompany.donna

import com.yourcompany.donna.VoiceConfirm.Answer
import com.yourcompany.donna.VoiceConfirm.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #52 — Ja/Nein-Sprachbestätigung im Live-Bubble-Panel. Spiegelt `voiceConfirm.test.ts` (Parität zum In-App-Chat)
 * und ergänzt die Entscheidungsfunktion [VoiceConfirm.decide] (Sicherheitsinvarianten).
 */
class VoiceConfirmTest {

    private fun p(t: String) = VoiceConfirm.parse(t)

    @Test fun `eindeutiges Ja`() {
        assertEquals(Answer.YES, p("Ja"))
        assertEquals(Answer.YES, p("ja, mach das"))
        assertEquals(Answer.YES, p("Okay, klar"))
        assertEquals(Answer.YES, p("Bestätige"))
    }

    @Test fun `eindeutiges Nein - Verneinung hat Vorrang`() {
        assertEquals(Answer.NO, p("Nein"))
        assertEquals(Answer.NO, p("nein, mach das nicht"))   // „mach" ist ein schwaches Ja-Wort
        assertEquals(Answer.NO, p("lass das lieber"))
        assertEquals(Answer.NO, p("Abbrechen bitte"))
        assertEquals(Answer.NO, p("doch nicht"))
    }

    @Test fun `Mehrdeutigkeit - starkes Ja UND Verneinung ist ambiguous`() {
        assertEquals(Answer.AMBIGUOUS, p("ja, aber nicht jetzt"))
        assertEquals(Answer.AMBIGUOUS, p("ja nein"))
        assertEquals(Answer.AMBIGUOUS, p("ja, lass das bleiben"))
    }

    @Test fun `Wortgrenzen statt contains - Teilstrings loesen nichts aus`() {
        assertEquals(Answer.NONE, p("das ist eine Machtfrage"))
        assertEquals(Answer.NONE, p("bitte aufklären"))
        assertEquals(Answer.NONE, p("Jahresplanung"))
        assertEquals(Answer.NONE, p("eine Stellungnahme"))
        assertEquals(Answer.NONE, p("Nichtraucher"))
    }

    @Test fun `weder Ja noch Nein ist none`() {
        assertEquals(Answer.NONE, p("Wie wird das Wetter morgen"))
        assertEquals(Answer.NONE, p(""))
        assertEquals(Answer.NONE, p("   ...  "))
    }

    @Test fun `Gross-Kleinschreibung und Satzzeichen sind egal`() {
        assertEquals(Answer.YES, p("JA!!!"))
        assertEquals(Answer.NO, p("Nein."))
    }

    @Test fun `lange Saetze sind neue Befehle und nie eine Bestaetigung`() {
        assertEquals(Answer.NONE, p("Mach das Licht im Wohnzimmer aus"))
        assertEquals(Answer.NONE, p("Okay und was ist mit morgen"))
        assertEquals(Answer.NONE, p("Klar, ruf bitte noch meine Mutter an"))
        assertEquals(Answer.NONE, p("Genau, stell einen Wecker für sieben Uhr"))
        assertEquals(Answer.NONE, p("Ja, schalte bitte auch noch die Lampe im Flur an"))
    }

    @Test fun `schwache Ja-Woerter nur bei hoechstens 3 Woertern`() {
        assertEquals(Answer.YES, p("mach das"))
        assertEquals(Answer.YES, p("ok klar"))
        assertEquals(Answer.YES, p("genau so"))
        assertEquals(Answer.NONE, p("mach das Licht aus"))
        assertEquals(Answer.NONE, p("klar mach das jetzt"))
    }

    @Test fun `starke Ja-Woerter bis 5 Woerter, darueber nichts`() {
        assertEquals(Answer.YES, p("ja bitte mach das jetzt"))
        assertEquals(Answer.NONE, p("ja bitte mach das jetzt sofort"))
    }

    @Test fun `Verneinung in langen Saetzen loescht die Karte nicht`() {
        assertEquals(Answer.NONE, p("Nein, mach stattdessen das Licht im Wohnzimmer aus"))
        assertEquals(Answer.NONE, p("Lass uns später über das Wetter morgen reden"))
    }

    @Test fun `kurze Verneinungen bleiben Nein`() {
        assertEquals(Answer.NO, p("nein, mach das nicht"))
        assertEquals(Answer.NO, p("nicht jetzt"))
        assertEquals(Answer.AMBIGUOUS, p("ja, aber nicht jetzt"))
    }

    // ── decide(): Sicherheitsinvarianten ─────────────────────────────────────────

    @Test fun `OHNE wartende bestaetigbare Karte loest auch ein eindeutiges Ja nichts aus`() {
        assertEquals(Decision.PASS_THROUGH, VoiceConfirm.decide(false, "Ja"))
        assertEquals(Decision.PASS_THROUGH, VoiceConfirm.decide(false, "bestätige"))
        assertEquals(Decision.PASS_THROUGH, VoiceConfirm.decide(false, "nein"))
    }

    @Test fun `MIT wartender Karte - Ja bestaetigt, Nein verwirft, gemischt tut NICHTS`() {
        assertEquals(Decision.CONFIRM, VoiceConfirm.decide(true, "Ja"))
        assertEquals(Decision.DISMISS, VoiceConfirm.decide(true, "Nein"))
        assertEquals(Decision.AMBIGUOUS, VoiceConfirm.decide(true, "ja, aber nicht jetzt"))
        // anderer Satz → normaler Turn (Karte wird mit dem neuen Turn verworfen, nie ausgeführt)
        assertEquals(Decision.PASS_THROUGH, VoiceConfirm.decide(true, "Wie wird das Wetter morgen"))
        assertEquals(Decision.PASS_THROUGH, VoiceConfirm.decide(true, "Mach das Licht im Wohnzimmer aus"))
    }

    @Test fun `nur einfache Ja-Nein-Aktionen sind per Sprache bestaetigbar - keine Auswahl-Fehlerkarten`() {
        listOf("whatsapp", "sms", "call", "create_event", "set_alarm", "set_timer", "navigate",
            "play_music", "note", "open_url").forEach { assertTrue(it, VoiceConfirm.isVoiceConfirmable(it)) }
        // Tesla: der Tap wirkt direkt (kein zweiter Schritt in einer Ziel-App) → nie per Sprache (Review R4)
        assertFalse(VoiceConfirm.isVoiceConfirmable("tesla_navigate"))
        assertFalse(VoiceConfirm.isVoiceConfirmable("navigate_disambiguate"))
        assertFalse(VoiceConfirm.isVoiceConfirmable("navigate_not_found"))
        // Smart-Home läuft im Backend (home_control) — nie als Panel-Karte per Sprache
        assertFalse(VoiceConfirm.isVoiceConfirmable("home_control"))
        assertFalse(VoiceConfirm.isVoiceConfirmable(""))
    }

    // ── #52 Review R1/R6: nur STARKES Ja, nie schwache Wörter / Echo-Ausklang ──────────────────

    @Test fun `schwache Ja-Woerter loesen per Sprache NIE eine Aktion aus (auch passt, klar, ok, genau)`() {
        listOf("ok", "okay", "klar", "passt", "genau", "stimmt", "sicher", "mach das", "ok klar", "genau so")
            .forEach { assertEquals(it, Decision.NEEDS_STRONG_YES, VoiceConfirm.decide(true, it)) }
    }

    @Test fun `starkes Ja bestaetigt (ja, jawohl, bestaetige, gerne, unbedingt)`() {
        listOf("Ja", "jawohl", "Bestätige", "ja bitte", "gerne", "unbedingt", "ja, mach das")
            .forEach { assertEquals(it, Decision.CONFIRM, VoiceConfirm.decide(true, it)) }
    }

    @Test fun `Ja mit Zoegern oder Einwand ist keine saubere Bestaetigung (Review R1)`() {
        listOf("ja aber was kostet das", "ja aber erst später", "ja moment", "ja warte kurz", "ja wann denn")
            .forEach { assertEquals(it, Decision.AMBIGUOUS, VoiceConfirm.decide(true, it)) }
        // Normale Bestätigungen bleiben unberührt
        assertEquals(Decision.CONFIRM, VoiceConfirm.decide(true, "ja bitte mach das jetzt"))
        assertEquals(Decision.CONFIRM, VoiceConfirm.decide(true, "ja gerne"))
    }

    @Test fun `Ja als Ausklang von Donnas eigener Frage ist Echo-Verdacht, nicht Bestaetigung`() {
        val spoken = "Soll ich Max eine WhatsApp senden, ja"
        assertEquals(Decision.ECHO_SUSPECT, VoiceConfirm.decide(true, "Ja", spoken))
        // Mike sagt mehr als der Ausklang → echte Antwort
        assertEquals(Decision.CONFIRM, VoiceConfirm.decide(true, "Ja, bitte", spoken))
        // Frage endet nicht auf ja → ein „Ja" ist eine echte Antwort
        assertEquals(Decision.CONFIRM, VoiceConfirm.decide(true, "Ja", "Soll ich Max anrufen"))
        // ohne gesprochenen Text (leer) kein Echo-Verdacht
        assertEquals(Decision.CONFIRM, VoiceConfirm.decide(true, "Ja", ""))
    }

    @Test fun `echoSuspect betrachtet nur die letzten Woerter`() {
        val long = "ja " + "wort ".repeat(VoiceConfirm.ECHO_TAIL_WORDS) + "ende"
        assertFalse(VoiceConfirm.echoSuspect("ja", long))
        assertTrue(VoiceConfirm.echoSuspect("ende", long))
        assertFalse(VoiceConfirm.echoSuspect("", long))
    }

    @Test fun `Nein bleibt auch bei Echo-Verdacht ein Nein (verwerfen ist die sichere Richtung)`() {
        assertEquals(Decision.DISMISS, VoiceConfirm.decide(true, "Nein", "Soll ich das senden, nein"))
    }

    // ── VoiceReplyWindow: nur direkt nach Donnas Frage ────────────────────────────────────

    @Test fun `Antwort-Fenster wird einmal verbraucht und schliesst`() {
        val w = VoiceReplyWindow()
        assertFalse(w.consume())            // nie geöffnet → liegengebliebene Karte darf nicht reagieren
        w.open()
        assertTrue(w.isOpen)
        assertTrue(w.consume())
        assertFalse(w.isOpen)
        assertFalse(w.consume())            // zweites Ergebnis: kein Fenster mehr
    }

    @Test fun `close schliesst ein offenes Fenster (Beruehrung, Abbruch, Stille, neuer Turn)`() {
        val w = VoiceReplyWindow()
        w.open(); w.close()
        assertFalse(w.consume())
    }

    // ── Verdrahtung (Quelltext-Guards, ergänzend zu den Verhaltenstests oben) ────────────────

    private fun controllerSrc() = java.io.File("src/main/java/com/yourcompany/donna/ConversationController.kt").readText()

    @Test fun `ConversationController wertet die Sprach-Bestaetigung nach dem Echo-Filter und vor dem Backend-Turn aus`() {
        val onResults = controllerSrc().substringAfter("override fun onResults(").substringBefore("override fun onError(")
        val echo = onResults.indexOf("SpokenTextMatcher.isEcho")
        val confirm = onResults.indexOf("voiceReplyWindow.consume() && handleVoiceConfirmation(text)")
        val send = onResults.indexOf("sendToBackend(text")
        assertTrue("Echo-Filter fehlt", echo >= 0)
        assertTrue("Bestätigungs-Aufruf fehlt", confirm >= 0)
        assertTrue("Backend-Turn fehlt", send >= 0)
        assertTrue("Echo-Filter muss vor der Bestätigung laufen", echo < confirm)
        assertTrue("Bestätigung muss vor dem Backend-Turn laufen", confirm < send)
    }

    @Test fun `Bestaetigung nutzt die Karten-Pruefung des Hosts, die Echo-Basis und fuehrt nur ueber den Host aus`() {
        val src = controllerSrc()
        assertTrue(src.contains("VoiceConfirm.decide(host.hasVoiceConfirmableCard(), text, lastSpokenText)"))
        assertTrue(src.contains("host.confirmCardByVoice()"))
        assertFalse("kein direkter Executor im Controller", src.contains("ActionExecutor("))
    }

    @Test fun `Antwort-Fenster oeffnet nur wenn die Maschine wirklich zuhoert und schliesst bei Beruehrung, Abbruch, Stille, Barge-in`() {
        val src = controllerSrc()
        assertTrue(src.contains("if (confirmable && machine.state.isListening) voiceReplyWindow.open()"))
        val fire = src.substringAfter("private fun fire(").substringBefore("private fun fail(")
        listOf("MicRequested", "StopRequested", "Failed", "Aborted", "NoSpeechTimeout", "VoiceBargeIn", "TextSubmitted")
            .forEach { assertTrue("fire() schließt das Fenster nicht bei $it", fire.contains("OverlayEvent.$it")) }
        assertTrue(fire.contains("voiceReplyWindow.close()"))
        // keepSttFlags (interner Offline-Retry derselben Antwort-Runde) lässt das Fenster offen
        assertTrue(fire.contains("is OverlayEvent.MicRequested -> if (!keepSttFlags) voiceReplyWindow.close()"))
    }

    @Test fun `LiveBubbleService fuehrt per Sprache nur bei scharfer Karte aus und entfernt sie vorher`() {
        val src = java.io.File("src/main/java/com/yourcompany/donna/LiveBubbleService.kt").readText()
        val fn = src.substringAfter("override fun confirmCardByVoice()").substringBefore("/** Wie der Nein-Tap")
        val remove = fn.indexOf("removeActionCard(spec)")
        val exec = fn.indexOf("actionExecutor.execute(spec.action)")
        assertTrue("removeActionCard fehlt", remove >= 0)
        assertTrue("execute fehlt", exec >= 0)
        assertTrue("Karte muss VOR der Ausführung entfernt werden", remove < exec)
        assertTrue(fn.contains("if (!cardsArmed) return false"))
        assertTrue(fn.contains("pending.size != 1"))
    }
}

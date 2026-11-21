package com.yourcompany.donna

/**
 * #52 — Ja/Nein-Sprachbestätigung für wartende Aktions-Karten im Live-Bubble-Panel (JVM-testbar).
 *
 * 1:1-Port von `mobile/apps/android/src/utils/voiceConfirm.ts` (`parseConfirmation`) — gleiche Regeln, gleiche
 * Testfälle (VoiceConfirmTest spiegelt voiceConfirm.test.ts).
 *
 * SICHERHEITSREGEL: Eine Aktion wird NUR bei eindeutigem Ja ausgeführt. Verneinung hat Vorrang, Wörter werden an
 * Wortgrenzen verglichen (kein `contains`: „klar" steckt nicht in „aufklären"), Mehrdeutigkeit („ja, aber nicht
 * jetzt") und lange Sätze („Mach das Licht im Wohnzimmer aus" = neuer Befehl) lösen NICHTS aus.
 */
object VoiceConfirm {

    enum class Answer { YES, NO, AMBIGUOUS, NONE }

    /** Längere Sätze sind nie eine Bestätigung. */
    const val MAX_CONFIRM_WORDS = 5

    /** Schwache Ja-Wörter („mach", „klar", „ok" …) zählen nur in ganz kurzen Antworten. */
    const val MAX_WEAK_YES_WORDS = 3

    private val NO_WORDS = setOf(
        "nein", "nee", "nö", "nicht", "kein", "keine", "keinen", "abbrechen", "abbruch", "stopp", "stop",
        "lass", "lieber", "cancel", "niemals", "verwerfen", "vergiss",
    )

    private val YES_WORDS = setOf(
        "ja", "jawohl", "jep", "jup", "yes", "ok", "okay", "klar", "mach", "bestätige", "bestätigen", "genau",
        "gerne", "unbedingt", "passt", "stimmt", "sicher",
    )

    // Eindeutige Zustimmung: zusammen mit einer Verneinung ist die Äußerung mehrdeutig.
    private val STRONG_YES = setOf("ja", "jawohl", "jep", "jup", "yes", "bestätige", "bestätigen", "gerne", "unbedingt")

    /** Zerlegung an allem, was kein Buchstabe/Ziffer ist (Umlaute/ß bleiben), Kleinbuchstaben. */
    private val SPLIT = Regex("[^a-zäöüß0-9]+")

    private fun words(text: String): List<String> =
        text.lowercase().split(SPLIT).filter { it.isNotEmpty() }

    fun parse(text: String): Answer {
        val w = words(text)
        if (w.isEmpty() || w.size > MAX_CONFIRM_WORDS) return Answer.NONE
        val hasNo = w.any { it in NO_WORDS }
        if (hasNo) return if (w.any { it in STRONG_YES }) Answer.AMBIGUOUS else Answer.NO
        val strongYes = w.any { it in STRONG_YES }
        val weakYes = w.size <= MAX_WEAK_YES_WORDS && w.any { it in YES_WORDS }
        return if (strongYes || weakYes) Answer.YES else Answer.NONE
    }

    /**
     * Aktions-Typen mit einfacher Ja/Nein-Karte, die per Sprache bestätigt werden dürfen. Auswahl-/Fehlerkarten
     * (`navigate_disambiguate`, `navigate_not_found`) sind AUSGESCHLOSSEN — ein „Ja" wäre dort nicht eindeutig.
     */
    // tesla_navigate bewusst NICHT dabei: dort wirkt der Tap direkt (kein zweiter Schritt in einer Ziel-App) —
    // per Sprache wäre das eine Fernauslösung ohne Gegenkontrolle. Der Kachel-Tap „An Tesla senden" bleibt.
    private val CONFIRMABLE_TYPES = setOf(
        "create_event", "set_alarm", "set_timer", "navigate",
        "call", "sms", "whatsapp", "play_music", "note", "open_url",
    )

    fun isVoiceConfirmable(actionType: String): Boolean = actionType in CONFIRMABLE_TYPES

    /** Entscheidung für eine erkannte Äußerung, während (nicht) eine bestätigbare Karte wartet. */
    enum class Decision {
        /** Keine Karte oder keine Bestätigungs-Äußerung → ganz normaler neuer Turn. */
        PASS_THROUGH,
        /** Eindeutiges, STARKES Ja bei genau einer bestätigbaren Karte → Aktion ausführen (wie Ja-Tap). */
        CONFIRM,
        /** Eindeutiges Nein → Karte verwerfen (wie Nein-Tap). */
        DISMISS,
        /** Ja UND Nein gemischt → NICHTS ausführen, Karte bleibt tippbar. */
        AMBIGUOUS,
        /**
         * Nur ein schwaches Ja-Wort („ok", „klar", „passt", „genau" …) → NICHTS ausführen. Per Sprache löst nur ein
         * ausdrückliches „Ja"/„Bestätigen" eine Aktion aus: schwache Wörter sind zu leicht Echo/Floskel/Fremdstimme.
         */
        NEEDS_STRONG_YES,
        /** „Ja" kann Donnas eigener Frage-Ausklang sein („…senden, ja?") → NICHTS ausführen, Karte bleibt tippbar. */
        ECHO_SUSPECT,
    }

    /** Wie viele der letzten von Donna gesprochenen Wörter als möglicher Echo-Ausklang gelten. */
    const val ECHO_TAIL_WORDS = 8

    /**
     * Zögern/Einwand neben einem Ja („Ja, aber was kostet das?", „ja, erst später") — dann ist es KEINE saubere
     * Bestätigung: nichts ausführen, Karte bleibt tippbar (Review R1, nur in [decide]; [parse] bleibt TS-Parität).
     */
    private val HESITATION_WORDS = setOf(
        "aber", "moment", "warte", "halt", "erst", "später", "wann", "wie", "was", "warum", "wieso",
    )

    /** Enthält die Äußerung ein STARKES Ja-Wort? */
    fun hasStrongYes(text: String): Boolean = words(text).any { it in STRONG_YES }

    /**
     * Kann die Äußerung nur der Ausklang von Donnas eigener Frage sein? Ja, wenn JEDES gehörte Wort in den letzten
     * [ECHO_TAIL_WORDS] Wörtern ihres gesprochenen Textes vorkommt („…senden, ja?" → gehört „Ja"). „Ja, bitte" ist
     * kein Echo, solange „bitte" nicht am Ende ihrer Frage stand.
     */
    fun echoSuspect(heard: String, spoken: String): Boolean {
        val h = words(heard)
        if (h.isEmpty()) return false
        val tail = words(spoken).takeLast(ECHO_TAIL_WORDS).toSet()
        if (tail.isEmpty()) return false
        return h.all { it in tail }
    }

    /**
     * Reine Entscheidungsfunktion (JVM-testbar). Ohne bestätigbare Karte ist JEDE Äußerung — auch ein „Ja" —
     * ein normaler Turn: niemals eine Aktion ohne wartende Karte auslösen.
     * [lastSpoken] = was Donna zuletzt gesprochen hat (Echo-Verdacht).
     */
    fun decide(hasConfirmableCard: Boolean, text: String, lastSpoken: String = ""): Decision {
        if (!hasConfirmableCard) return Decision.PASS_THROUGH
        return when (parse(text)) {
            Answer.YES -> when {
                !hasStrongYes(text) -> Decision.NEEDS_STRONG_YES
                words(text).any { it in HESITATION_WORDS } -> Decision.AMBIGUOUS
                echoSuspect(text, lastSpoken) -> Decision.ECHO_SUSPECT
                else -> Decision.CONFIRM
            }
            Answer.NO -> Decision.DISMISS
            Answer.AMBIGUOUS -> Decision.AMBIGUOUS
            Answer.NONE -> Decision.PASS_THROUGH
        }
    }
}

/**
 * #52 — Zeitfenster „Donna wartet auf die Antwort auf IHRE Frage". Nur wenn die Antwort-Runde direkt nach einer
 * Frage mit wartender Karte automatisch aufgeht, darf ein gesprochenes „Ja" die Karte bestätigen. Jede Berührung
 * (Mic-Tap, Stopp), jeder Abbruch, jede Stille und jeder neue Turn schließt es; ein Ergebnis verbraucht es einmal.
 * Eine liegengebliebene Karte lässt sich so nie durch ein späteres, beiläufiges „Ja/Ok" auslösen.
 */
class VoiceReplyWindow {
    @Volatile var isOpen: Boolean = false
        private set

    fun open() { isOpen = true }
    fun close() { isOpen = false }

    /** @return true, wenn das Fenster offen war (und schließt es). */
    fun consume(): Boolean {
        val was = isOpen
        isOpen = false
        return was
    }
}

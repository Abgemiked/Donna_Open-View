package com.yourcompany.donna

/**
 * #226: Nachhören nach dem Sprachende — reine Logik, ohne Android-Laufzeit JVM-testbar.
 *
 * Der Android-SpeechRecognizer entscheidet selbst, wann eine Äußerung zu Ende ist; die Sprechpausen-Extras
 * ([SilenceProfile]) sind für ihn nur Hinweise. Mike spricht lange und mit Denkpausen (2–3 s) — der Erkenner
 * liefert dann mitten im Gedanken ein Ergebnis und Donna antwortet zu früh.
 *
 * Darum: Liefert der Erkenner ein Ergebnis, wird es (je nach Text) NICHT sofort gesendet, sondern gemerkt; der
 * Erkenner startet nahtlos neu und hört ein kurzes Nachlauf-Fenster ([graceMs]) weiter. Spricht Mike in diesem
 * Fenster weiter (nicht-leeres Teilergebnis), wird der neue Teil angehängt; erst nach echter Stille geht der
 * gesamte Text raus.
 *
 * Kurze Befehle („Licht an“) bekommen KEIN Fenster (Fenster 0 = sofort senden, kein spürbarer Verzug);
 * längere Sätze ein kurzes, erkennbar unvollständige Sätze („… und“, „… weil“, „… ähm“) ein längeres.
 * Alle Werte stehen hier bzw. im [SilenceProfile] (Profil Kurz/Normal/Lang aus den App-Einstellungen).
 */
object SpeechContinuation {
    /** Bis zu so vielen Wörtern gilt ein Text als kurzer Befehl → kein Nachhören (es sei denn, er ist unvollständig). */
    const val SHORT_MAX_WORDS = 5
    /** Ab so vielen Wörtern gilt ein Text als lang → längeres Fenster. */
    const val LONG_MIN_WORDS = 9
    /** Obergrenze für die gesamte gesammelte Äußerung (alle Abschnitte). Danach wird gesendet. */
    const val MAX_TOTAL_MS = 45_000L
    /** Obergrenze für die Anzahl der Abschnitte (Neustarts) einer Äußerung — keine Endlosschleife. */
    const val MAX_SEGMENTS = 8
    /** Sicherung, falls der Erkenner nach onEndOfSpeech kein Ergebnis mehr liefert (Nachhören aufgeschoben). */
    const val RESULT_GUARD_MS = 4_000L
    /**
     * Das Nachlauf-Fenster zählt erst ab „Erkenner bereit“ (onReadyForSpeech) — sonst fräße die Anlaufzeit des
     * Neustarts das Fenster. Wird er nicht bereit, greift das Fenster spätestens nach dieser Zusatzzeit.
     */
    const val READY_ALLOWANCE_MS = 1_500L

    /**
     * Letzte Wörter, nach denen ein Satz IMMER erkennbar weitergeht (unterordnende/beiordnende Konjunktionen,
     * Füllwörter). Bewusst NICHT: trennbare Verbpartikel und Satzend-Partikel, die fertige Befehle beenden
     * („an“, „aus“, „auf“, „ein“, „zu“, „mit“, „denn“, „dann“ — „Schalt das Licht ein“, „Mach die Rollläden zu“,
     * „Nimm das mit“, „Was machst du denn“, „Bis dann“).
     */
    private val INCOMPLETE_TAIL_STRONG = setOf(
        "und", "oder", "aber", "sondern", "weil", "dass", "daß", "wenn", "ob", "obwohl", "damit", "falls",
        "also", "bzw", "beziehungsweise", "sowie", "sowohl",
        "äh", "ähm", "öhm", "hm", "hmm", "ehm", "em",
    )

    /**
     * Artikel, Possessiva und Präpositionen: am Ende meist unvollständig, ABER als Pronomen auch Ende fertiger
     * kurzer Befehle („Was ist das“, „Erklär mir das“, „Speicher das“). Darum nur ab [WEAK_MIN_WORDS] Wörtern.
     */
    private val INCOMPLETE_TAIL_WEAK = setOf(
        "der", "die", "das", "den", "dem", "des", "eine", "einen", "einem", "einer", "eines",
        "mein", "meine", "meinen", "meinem", "meiner", "dein", "deine", "deinen", "deinem", "deiner",
        "für", "von", "vom", "zum", "zur", "im", "ins", "bei", "beim", "über", "unter", "gegen", "ohne", "als", "wie",
    )
    const val WEAK_MIN_WORDS = 6

    private val WORD_SPLIT = Regex("\\s+")
    private val EDGE_PUNCT = Regex("^[\\p{Punct}„“”»«‚‘’…]+|[\\p{Punct}„“”»«‚‘’…]+$")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    fun words(text: String): List<String> =
        text.trim().split(WORD_SPLIT).map { it.replace(EDGE_PUNCT, "") }.filter { it.isNotEmpty() }

    /** Satz geht erkennbar weiter: endet auf Komma/Gedankenstrich/Auslassung oder auf ein Bindewort/Füllwort. */
    fun isIncomplete(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (t.endsWith(",") || t.endsWith("-") || t.endsWith("–") || t.endsWith("…") || t.endsWith("...")) return true
        val w = words(t)
        val last = w.lastOrNull()?.lowercase() ?: return false
        return last in INCOMPLETE_TAIL_STRONG || (w.size >= WEAK_MIN_WORDS && last in INCOMPLETE_TAIL_WEAK)
    }

    /** Vergleichsform: nur Buchstaben/Ziffern, klein, einfache Leerzeichen (Satzzeichen des Erkenners egal). */
    private fun norm(text: String): String = text.lowercase().replace(NON_WORD, " ").trim()

    /**
     * Nachlauf-Fenster in ms für den bisher gesammelten Text. 0 = sofort senden.
     * [segmentsSoFar] = wie viele Abschnitte schon vorher gesammelt wurden: wer schon einmal weitergesprochen hat,
     * spricht offensichtlich länger → mindestens das „lange“ Fenster.
     */
    fun graceMs(text: String, profile: SilenceProfile, segmentsSoFar: Int = 0): Long {
        val n = words(text).size
        if (n == 0) return 0L
        if (isIncomplete(text)) return profile.graceIncompleteMs
        if (segmentsSoFar > 0) return profile.graceLongMs
        return when {
            n <= SHORT_MAX_WORDS -> 0L
            n >= LONG_MIN_WORDS -> profile.graceLongMs
            else -> profile.graceMediumMs
        }
    }

    /**
     * Hängt einen neuen Abschnitt an. Manche Erkenner liefern nach einem Neustart den alten Text noch einmal mit —
     * beginnt der neue Abschnitt mit dem bisherigen, wird er nicht doppelt angehängt.
     */
    fun join(pending: String, next: String): String {
        val a = pending.trim()
        val b = next.trim()
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val na = norm(a)
        if (na.isNotEmpty() && norm(b).startsWith(na)) return b
        return "$a $b"
    }
}

/** Entscheidung nach einem Erkenner-Ergebnis. */
sealed class ContinuationDecision {
    /** Weiter zuhören: Erkenner neu starten, nach [graceMs] ohne neue Sprache den gesammelten Text senden. */
    data class Continue(val graceMs: Long) : ContinuationDecision()
    /** Jetzt senden: [text] = gesamter gesammelter Text (kann leer sein, wenn nichts erkannt wurde). */
    data class Finish(val text: String) : ContinuationDecision()
}

/**
 * Sammelt die Abschnitte EINER Äußerung über Erkenner-Neustarts hinweg. Zustand je Zuhören; [reset] bei jedem
 * neuen Zuhören (nicht bei den Nachhör-Neustarts) und bei Abbruch.
 */
class SpeechCollector {
    var pending: String = ""
        private set
    var segments: Int = 0
        private set
    private var startedAt: Long = -1L

    /** Es liegt schon gesammelter Text vor (= wir sind im Nachhören). */
    val hasPending: Boolean get() = pending.isNotBlank()

    fun reset() {
        pending = ""
        segments = 0
        startedAt = -1L
    }

    /**
     * Neues Erkenner-Ergebnis [text]. [allowContinue] = false erzwingt Senden (Nutzer hat „Fertig“ getippt,
     * Barge-in-Sitzung, Zustand ist kein Zuhören mehr).
     */
    fun offer(text: String, nowMs: Long, profile: SilenceProfile, allowContinue: Boolean = true): ContinuationDecision {
        if (startedAt < 0L) startedAt = nowMs
        val joined = SpeechContinuation.join(pending, text)
        val before = segments
        if (text.isNotBlank()) segments++
        val overLimit = segments >= SpeechContinuation.MAX_SEGMENTS || nowMs - startedAt >= SpeechContinuation.MAX_TOTAL_MS
        val grace = if (!allowContinue || overLimit || text.isBlank()) 0L
        else SpeechContinuation.graceMs(joined, profile, before)
        return if (grace > 0L) {
            pending = joined
            ContinuationDecision.Continue(grace)
        } else {
            reset()
            ContinuationDecision.Finish(joined)
        }
    }

    /** Nachlauf-Fenster ohne neue Sprache abgelaufen bzw. Erkennerfehler im Nachhören: gesammelten Text abgeben. */
    fun take(): String {
        val t = pending.trim()
        reset()
        return t
    }

    /**
     * Wie [take], aber mit dem letzten Teilergebnis des laufenden Abschnitts (Erkennerfehler NACH neuer Sprache:
     * das zuletzt Gehörte soll nicht verloren gehen).
     */
    fun takeWith(lastPartial: String): String {
        val t = SpeechContinuation.join(pending, lastPartial).trim()
        reset()
        return t
    }

    /**
     * Restzeit bis [SpeechContinuation.MAX_TOTAL_MS] für die ganze Äußerung (Deckel für den Höchstdauer-Timer eines
     * Nachhör-Abschnitts, damit das Mikrofon nicht 45 s + 30 s offen bleibt). Mindestens 1 s.
     */
    fun remainingMs(nowMs: Long): Long =
        if (startedAt < 0L) SpeechContinuation.MAX_TOTAL_MS
        else (SpeechContinuation.MAX_TOTAL_MS - (nowMs - startedAt)).coerceAtLeast(1_000L)

    /** Anzeige während des Nachhörens: bisheriger Text + aktuelles Teilergebnis. */
    fun display(partial: String): String = SpeechContinuation.join(pending, partial)

    /**
     * Soll ein onEndOfSpeech das Sprachende NOCH NICHT melden (weil danach wahrscheinlich nachgehört wird)?
     * Kurze Befehle melden es sofort wie bisher.
     */
    fun deferEndOfSpeech(lastPartial: String, profile: SilenceProfile): Boolean =
        SpeechContinuation.graceMs(display(lastPartial), profile, segments) > 0L
}

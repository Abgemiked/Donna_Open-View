package com.yourcompany.donna

import java.util.Locale

/**
 * Reine Bausteine des Gespraechsmodus (#122, Paket A) — OHNE Android-Typen, JVM-testbar. Blase
 * ([ConversationController]) und Activity-Overlay ([VoiceInputActivity]) nutzen DIESELBEN Regeln.
 */

/** Warum ein Gespraech endete (nur diese fuenf Werte gehen ins Messwert-Ereignis). */
enum class EndReason(val wire: String) {
    SILENCE("silence"),
    KEYWORD("keyword"),
    BUTTON("button"),
    LIMIT("limit"),
    ERROR("error"),
}

/** Statuszeilen am Gespraechsende. */
object ConversationTexts {
    const val BYE = "Bis gleich."
    const val ERROR_END = "Das klappt gerade nicht. Ich beende das Gespräch — ruf mich gleich nochmal auf."

    fun endingStatus(reason: EndReason?): String = if (reason == EndReason.ERROR) ERROR_END else BYE
}

/**
 * Ende-Woerter: Ein Gespraech endet nur, wenn die GANZE Aeusserung (ohne Satzzeichen und ohne Fuellwoerter am Rand)
 * aus einer Abschlussphrase besteht UND Donna im Weiterhoeren ([OverlayState.FOLLOW_UP]) war. „halt mal die Musik an“
 * oder „stopp den Timer“ sind normale Anfragen.
 */
object ConversationEnd {
    /**
     * Eine Abschlussphrase. [unambiguous] = eindeutiger Abschied (beendet auch bei offener Rueckfrage);
     * [politeEdgeOnly] = nur mit hoeflichem Rand erlaubt („halt danke“, nicht „ja halt“).
     */
    private class Phrase(val unambiguous: Boolean, val politeEdgeOnly: Boolean = false)

    private fun clear(vararg raw: String) = raw.associate { fold(it) to Phrase(unambiguous = true) }
    private fun vague(vararg raw: String) = raw.associate { fold(it) to Phrase(unambiguous = false) }

    /** Abschlussphrasen, bereits gefaltet (siehe [fold]). Eindeutige zuerst, dann mehrdeutige. */
    private val PHRASES: Map<String, Phrase> =
        clear(
            "tschüss", "tschüs", "tschüssi", "tschüß", "tschö", "tschau", "ciao",
            "das war's", "das wars", "das war alles", "das war es", "das ist alles",
            "bis später", "bis gleich", "bis bald", "auf wiedersehen", "auf wiederhören", "gute nacht",
        ) + vague(
            "stopp", "stop", "ende", "schluss", "das reicht", "fertig", "ich bin fertig", "nichts weiter", "nein danke",
        ) + mapOf(fold("halt") to Phrase(unambiguous = false, politeEdgeOnly = true))

    /** Fuellwoerter, die am Rand toleriert werden („okay, stopp, danke“). Allein sind sie KEIN Ende. „nein“ gehoert NICHT dazu. */
    private val FILLERS: Set<String> = listOf(
        "okay", "ok", "danke", "dankeschön", "vielen", "dank", "donna", "bitte", "ja", "na", "also", "dann", "so",
        "gut", "super", "hey",
    ).map { fold(it) }.toSet()

    /** Hoefliche Raender, die auch „halt“ vertraegt; Partikel wie „ja/na/also/dann/so“ nicht. */
    private val POLITE_FILLERS: Set<String> = listOf(
        "okay", "ok", "danke", "dankeschön", "vielen", "dank", "donna", "bitte",
    ).map { fold(it) }.toSet()

    /** Kleinschreibung, ss/ß, Umlaute (ü=ue=u …), Apostrophe weg, Satzzeichen zu Leerzeichen. */
    fun fold(raw: String): String {
        var s = raw.lowercase(Locale.ROOT)
        s = s.replace("ß", "ss")
        s = s.replace("ä", "a").replace("ö", "o").replace("ü", "u")
        s = s.replace("ae", "a").replace("oe", "o").replace("ue", "u")
        s = s.replace(Regex("['’‘´`]"), "")
        s = s.replace(Regex("[^a-z0-9]+"), " ")
        return s.trim()
    }

    /**
     * Findet die Abschlussphrase der GANZEN Aeusserung: von vorn und hinten duerfen Fuellwoerter abfallen; ein
     * Kandidat gilt, wenn er genau eine Phrase ergibt (und bei „halt“ nur hoeflicher Rand abfiel).
     */
    private fun match(text: String?): Phrase? {
        if (text.isNullOrBlank()) return null
        val tokens = fold(text).split(' ').filter { it.isNotEmpty() }
        for (front in 0..tokens.size) {
            if (front > 0 && tokens[front - 1] !in FILLERS) break
            for (back in 0..(tokens.size - front)) {
                if (back > 0 && tokens[tokens.size - back] !in FILLERS) break
                val core = tokens.subList(front, tokens.size - back)
                if (core.isEmpty()) continue
                val phrase = PHRASES[core.joinToString(" ")] ?: continue
                val edge = tokens.subList(0, front) + tokens.subList(tokens.size - back, tokens.size)
                if (phrase.politeEdgeOnly && edge.any { it !in POLITE_FILLERS }) continue
                return phrase
            }
        }
        return null
    }

    fun isEndPhrase(text: String?): Boolean = match(text) != null

    /** Offene Rueckfrage: Antwortfenster („Ja/Nein“ erwartet) ODER eine Karte wartet auf Bestaetigung. */
    fun confirmationPending(replyWindowOpen: Boolean, cardsPending: Boolean): Boolean = replyWindowOpen || cardsPending

    /**
     * Die einzige Entscheidungsstelle: Ende-Woerter zaehlen nur beim Weiterhoeren nach einer Antwort. Bei einer
     * offenen Rueckfrage [confirmationPending] beenden NUR eindeutige Abschiede; mehrdeutige Woerter („stopp“,
     * „halt“, „nein danke“ …) gehen an die Bestaetigungs-Logik.
     */
    fun shouldEnd(followUpListen: Boolean, text: String?, confirmationPending: Boolean = false): Boolean {
        if (!followUpListen) return false
        val p = match(text) ?: return false
        return !confirmationPending || p.unambiguous
    }
}

/**
 * Hoerfenster nach der Antwort (Weiterhoeren): 6 / 10 / 15 s, Standard 10 s. Liegt als Int in [TtsEngines.PREFS]
 * und wird beim START jedes Weiterhoerens frisch gelesen (kein Listener). Der Schluessel steht NUR hier.
 */
object FollowUpWindow {
    const val PREF_FOLLOW_UP_WINDOW_S = "follow_up_window_s"
    val STEPS_S: List<Int> = listOf(6, 10, 15)
    const val DEFAULT_S = 10

    /** Unter dieser Restzeit lohnt kein stiller Neustart des Erkenners mehr. */
    const val MIN_RESTART_MS = 1_500L

    /** Ungueltig (null, <= 0) -> Standard; sonst naechste Stufe. */
    fun normalize(raw: Int?): Int {
        if (raw == null || raw <= 0) return DEFAULT_S
        return STEPS_S.minByOrNull { kotlin.math.abs(it - raw) } ?: DEFAULT_S
    }

    fun read(prefs: android.content.SharedPreferences): Int =
        try {
            normalize(if (prefs.contains(PREF_FOLLOW_UP_WINDOW_S)) prefs.getInt(PREF_FOLLOW_UP_WINDOW_S, DEFAULT_S) else null)
        } catch (_: Exception) {
            DEFAULT_S
        }

    fun readMs(prefs: android.content.SharedPreferences): Long = read(prefs) * 1_000L

    /** Restzeit des Fensters; der Spracherkenner meldet Stille oft frueher als das Fenster lang ist. */
    fun remainingMs(windowMs: Long, startedAt: Long, now: Long): Long =
        (windowMs - (now - startedAt)).coerceIn(0L, windowMs)

    fun shouldRestart(remainingMs: Long): Boolean = remainingMs >= MIN_RESTART_MS
}

/**
 * „Unterbrechen per Stimme“ (Voice-Barge-in): Standard AN (Opt-out). Liegt als Boolean in [TtsEngines.PREFS]; die Blase
 * liest den Wert bei jedem Sprechen frisch. Der Schluessel steht NUR hier (App-Schalter, Langdruck und Overlay nutzen ihn).
 */
object VoiceBargeInSetting {
    const val PREF_KEY = "voice_barge_in"
    const val DEFAULT = true

    /** Gibt es schon einen ausdruecklich gespeicherten Wert? (Die einmalige Uebernahme alter App-Werte schreibt nur ohne.) */
    fun isSet(prefs: android.content.SharedPreferences): Boolean =
        try { prefs.contains(PREF_KEY) } catch (_: Exception) { false }

    fun read(prefs: android.content.SharedPreferences): Boolean =
        try { prefs.getBoolean(PREF_KEY, DEFAULT) } catch (_: Exception) { DEFAULT }

    fun write(prefs: android.content.SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(PREF_KEY, enabled).apply()
    }
}

/** Zusammenfassung EINES Gespraechs: nur Zahlen und der Endegrund — nie Text, App- oder Kontaktnamen. */
data class ConversationSummary(
    val turns: Int,
    val followUps: Int,
    val expiredWindows: Int,
    val interruptions: Int,
    val durationS: Long,
    val reason: EndReason,
) {
    /** Koerper fuer `POST /tracking/push` (type="conversation_summary"). Nur Zahlen und ein Aufzaehlungswert. */
    fun toPushBody(): String =
        "{\"type\":\"conversation_summary\",\"conversation_summary\":{" +
            "\"turns\":$turns,\"follow_ups\":$followUps,\"expired_windows\":$expiredWindows," +
            "\"interruptions\":$interruptions,\"duration_s\":$durationS,\"end_reason\":\"${reason.wire}\"}}"
}

/** Zaehler je Gespraech. Der Takt kommt von aussen (testbar). */
class ConversationStats(private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val startedAt = clock()
    var turns = 0; private set
    var followUps = 0; private set
    var expiredWindows = 0; private set
    var interruptions = 0; private set
    private var consumed = false

    fun onTurn(followUp: Boolean) { turns++; if (followUp) followUps++ }
    fun onWindowExpired() { expiredWindows++ }
    fun onInterruption() { interruptions++ }

    /**
     * Die Zusammenfassung GENAU EINMAL; danach null. Ein Gespraech ohne gesendete Frage ergibt keine.
     */
    fun takeSummary(reason: EndReason): ConversationSummary? {
        if (consumed || turns == 0) return null
        consumed = true
        return ConversationSummary(
            turns, followUps, expiredWindows, interruptions,
            ((clock() - startedAt) / 1_000L).coerceAtLeast(0L), reason,
        )
    }
}

/** Derselbe Fehler dreimal in Folge beendet das Gespraech; ein anderer Fehler (oder ein Erfolg) setzt zurueck. */
class RepeatedErrorTracker(private val limit: Int = 3) {
    private var last: UserError? = null
    private var count = 0

    /** @return true, wenn dieser Fehler das [limit]-te Mal in Folge auftrat. */
    fun record(error: UserError): Boolean {
        if (error == last) count++ else { last = error; count = 1 }
        return count >= limit
    }

    fun reset() { last = null; count = 0 }
}

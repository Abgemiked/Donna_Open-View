package com.yourcompany.donna

import kotlin.math.max

/**
 * Energie-basierte Spracherkennung für das Voice-Barge-in (Donna per STIMME unterbrechen) — reine Logik,
 * JVM-testbar. Gefüttert wird sie mit dem Pegel (dBFS) von 20-ms-Frames des Mikrofons, WÄHREND Donna spricht.
 *
 * Echo-Schutz in der Logik (zusätzlich zum Hardware-AEC in [BargeInDetector]):
 *  - Lernphase: der Pegel von Donnas eigener Stimme (Echo-Rest) wird gelernt, nie ausgelöst. Sie beginnt erst am
 *    ersten HÖRBAREN Frame (≥ [audibleDb]) und dauert [warmupMs] — ein stiller TTS-Vorlauf (Leerlauf am Anfang der
 *    Audiodatei) verfälscht sie nicht. Kommt bis [hardCapMs] gar nichts Hörbares, endet sie „still“ und es gilt
 *    die konservative Untergrenze [silentFloorDb].
 *  - Schwelle = max(Echo-Spitze + [marginDb], Untergrenze): nur deutlich LAUTERES als das Echo zählt. Die
 *    Echo-Spitze folgt Donnas Lautstärke (fällt nur langsam, [peakDecayDbPerSec]).
 *  - Dauer: erst nach [sustainMs] anhaltend lauter Frames (kurzes Klicken/Husten löst nichts aus).
 *
 * Voice-Batch (Barge-in standardmäßig AN → muss hart gegen Selbst-Auslösung und Lärm sein):
 *  - Sprachband: der Pegel kommt aus [SpeechFrameAnalyzer] (Hochpass ~250 Hz) — Brummen/Dröhnen/Wind zählen kaum.
 *  - Stimm-Ähnlichkeit: nur Frames mit sprachtypischer Nulldurchgangsrate ([voiceLike]) bauen die Dauer auf; Rauschen
 *    (zu hoch) und Rumpeln/Brummen (zu niedrig) nicht — "nur echte menschliche Sprache unterbricht".
 *  - Umgebungsgeräusch: ein Minimum-Statistik-Boden ([noiseFloorDb], steigt langsam) hebt die Schwelle in lauter
 *    Umgebung an (Boden + [ambientMarginDb]) — Straße, Küche, Fernseher lösen nichts aus.
 */
class BargeInVad(
    private val frameMs: Int = 20,
    private val warmupMs: Int = 300,
    private val hardCapMs: Int = 1_400,
    private val audibleDb: Double = -65.0,
    private val marginDb: Double = 11.0,
    private val absMinDb: Double = -36.0,
    private val silentFloorDb: Double = -30.0,
    private val sustainMs: Int = 380,
    private val peakDecayDbPerSec: Double = 0.5,
    private val ambientMarginDb: Double = 12.0,
    private val noiseFloorRiseDbPerSec: Double = 2.0,
) {
    private var elapsedMs = 0
    private var echoPeakDb = -90.0
    private var noiseFloorDb = 0.0
    private var voicedMs = 0
    private var firstAudibleMs = -1
    private var learningDone = false
    private var silentLearning = false

    var triggered: Boolean = false
        private set

    /** Lernphase abgeschlossen (erst danach kann ausgelöst werden) — für Tests/Diagnose. */
    val isLearningDone: Boolean get() = learningDone

    /** Aktuelle Auslöse-Schwelle (dBFS) — für Tests/Diagnose. */
    val thresholdDb: Double
        get() = max(
            max(echoPeakDb + marginDb, noiseFloorDb + ambientMarginDb),
            if (silentLearning) silentFloorDb else absMinDb,
        )

    /**
     * @param voiceLike sprachtypische Frame-Eigenschaften (siehe [SpeechFrameAnalyzer.isVoiceLike]); false = laut,
     *   aber Rauschen/Brummen → zählt nicht zur Dauer.
     * @return true genau EINMAL: im Moment des Auslösens.
     */
    fun onFrame(db: Double, voiceLike: Boolean = true): Boolean {
        if (triggered) return false
        elapsedMs += frameMs
        // Minimum-Statistik: folgt dem leisesten Pegel nach unten, steigt nur langsam (Umgebungsgeräusch-Boden)
        noiseFloorDb = Math.min(noiseFloorDb + noiseFloorRiseDbPerSec * frameMs / 1000.0, max(db, -80.0))
        if (!learningDone) {
            if (db >= audibleDb) {
                if (firstAudibleMs < 0) firstAudibleMs = elapsedMs
                echoPeakDb = max(echoPeakDb, db)
            }
            if (firstAudibleMs >= 0 && elapsedMs - firstAudibleMs >= warmupMs) {
                learningDone = true
            } else if (elapsedMs >= hardCapMs) {
                learningDone = true
                silentLearning = firstAudibleMs < 0
            }
            return false
        }
        val loud = db > thresholdDb
        if (loud && voiceLike) {
            voicedMs += frameMs
            if (voicedMs >= sustainMs) {
                triggered = true
                return true
            }
        } else {
            // Kurze Lücken in der Sprache verzeihen (halber Abzug); laute Nicht-Sprache zählt als Lücke
            voicedMs = max(0, voicedMs - frameMs / 2)
            if (!loud) {
                // Spitze langsam nachführen (nur bei leisen Frames — ein Knall hebt die Echo-Spitze nicht an)
                val decay = peakDecayDbPerSec * frameMs / 1000.0
                echoPeakDb = max(echoPeakDb - decay, db)
            }
        }
        return false
    }

    companion object {
        /** RMS eines 16-bit-PCM-Frames in dBFS (−90 bei Stille). */
        fun rmsDb(samples: ShortArray, count: Int): Double {
            if (count <= 0) return -90.0
            var sum = 0.0
            for (i in 0 until count) {
                val s = samples[i].toDouble() / 32768.0
                sum += s * s
            }
            val rms = Math.sqrt(sum / count)
            return if (rms <= 1e-9) -90.0 else 20.0 * Math.log10(rms)
        }
    }
}

/**
 * Frame-Analyse für die Stimm-Erkennung: Hochpass erster Ordnung (~[CUTOFF_HZ]) entfernt Brummen/Dröhnen/Gleichanteil,
 * danach Pegel (dBFS) und Nulldurchgangsrate (ZCR) des gefilterten Signals. Hält den Filterzustand über Frames
 * (ein Analyzer pro Aufnahme-Lauf, nicht thread-sicher). Reine Logik, JVM-testbar.
 *
 * ZCR (Nulldurchgänge pro Sample): Sinus der Frequenz f bei Abtastrate fs → 2f/fs. Stimmhafte Sprache (Formanten
 * ~300-900 Hz) liegt bei 16 kHz um 0,04-0,12; Brummen/Rumpeln darunter, Zischen/Rauschen weit darüber (>0,35).
 */
class SpeechFrameAnalyzer(private val sampleRate: Int = 16_000, cutoffHz: Double = CUTOFF_HZ) {
    class Result(val db: Double, val zcr: Double) {
        /** Sprachtypisch: ZCR im Band [VOICE_ZCR_MIN, VOICE_ZCR_MAX]. */
        val voiceLike: Boolean get() = zcr in VOICE_ZCR_MIN..VOICE_ZCR_MAX
    }

    private val alpha: Double = run {
        val rc = 1.0 / (2.0 * Math.PI * cutoffHz)
        val dt = 1.0 / sampleRate
        rc / (rc + dt)
    }
    private var prevIn = 0.0
    private var prevOut = 0.0

    fun analyze(samples: ShortArray, count: Int): Result {
        if (count <= 0) return Result(-90.0, 0.0)
        var sum = 0.0
        var crossings = 0
        var prevSign = 0
        for (i in 0 until count) {
            val x = samples[i].toDouble() / 32768.0
            val y = alpha * (prevOut + x - prevIn)
            prevIn = x
            prevOut = y
            sum += y * y
            // Nulldurchgänge nur zählen, wenn das Signal über einem winzigen Rauschteppich liegt (kein Zittern um 0)
            val sign = if (y > ZCR_DEADBAND) 1 else if (y < -ZCR_DEADBAND) -1 else 0
            if (sign != 0) {
                if (prevSign != 0 && sign != prevSign) crossings++
                prevSign = sign
            }
        }
        val rms = Math.sqrt(sum / count)
        val db = if (rms <= 1e-9) -90.0 else 20.0 * Math.log10(rms)
        return Result(db, if (count > 1) crossings.toDouble() / (count - 1) else 0.0)
    }

    companion object {
        const val CUTOFF_HZ = 250.0
        const val VOICE_ZCR_MIN = 0.02
        const val VOICE_ZCR_MAX = 0.30
        private const val ZCR_DEADBAND = 1e-4
    }
}

/**
 * Erkennt, ob eine erkannte Äußerung nur Donnas eigene Stimme war (Echo): Anteil der gehörten Wörter, die im
 * zuletzt gesprochenen Text vorkommen. Verhindert Selbstgespräche und Endlosschleifen.
 *
 * Wird NUR beim automatischen Relisten und beim Voice-Barge-in angewendet — bei manuellem Mic-Tap spricht Mike
 * garantiert selbst (Aufrufer leert den Vergleichstext).
 */
object SpokenTextMatcher {

    private val TOKEN = Regex("[\\p{L}\\p{N}]+")

    /** Antwort-Partikel: kommen sie in der Äußerung vor, in Donnas Text aber nicht, ist es eine echte Antwort. */
    private val ANSWER_WORDS = setOf(
        "ja", "nein", "nee", "jep", "ok", "okay", "klar", "genau", "doch", "gerne", "bitte", "danke",
        "stopp", "stop", "abbrechen", "lass", "nicht",
    )

    private fun tokens(text: String): List<String> =
        TOKEN.findAll(text.lowercase()).map { it.value }.filter { it.length >= 2 }.toList()

    /**
     * @param bargeIn Äußerung stammt aus einem Voice-Barge-in (Mikrofon war WÄHREND Donnas Ausgabe offen):
     *   niedrigere Schwelle (~0,5) und auch ein einzelnes Wort aus Donnas Text zählt als Echo.
     * Beim automatischen Relisten (bargeIn=false) gilt: bis 6 Wörter müssen ALLE aus Donnas Text stammen, darüber
     * 85 % — „Ja, das Licht im Wohnzimmer“ (auf „… Licht im Wohnzimmer ausschalten?“) bleibt eine echte Antwort.
     */
    fun isEcho(heard: String, spoken: String, bargeIn: Boolean = false): Boolean {
        val h = tokens(heard)
        val s = tokens(spoken).toSet()
        if (h.isEmpty() || s.isEmpty()) return false
        // „Ja“, „Nein“, „Stopp“ … die Donna so nicht gesagt hat → echte Antwort von Mike
        if (h.any { it in ANSWER_WORDS && it !in s }) return false
        val hits = h.count { it in s }
        if (h.size == 1) return bargeIn && hits == 1
        val ratio = hits.toDouble() / h.size
        return when {
            bargeIn -> ratio >= 0.5
            h.size <= 6 -> ratio >= 1.0
            else -> ratio >= 0.85
        }
    }
}

/**
 * Zählt Selbst-Fehlauslöser des Voice-Barge-in (Echo/Stille/Fehler). FRÜHER wurde Barge-in nach nur zwei
 * Fehlern DAUERHAFT (prozessweit) abgeschaltet — dadurch „griff Reinreden real nie" mehr, wenn früh in einer
 * Sitzung zwei Flukes auftraten (Mikes Beschwerde). JETZT (A3):
 *  - höhere Toleranz als früher ([maxStrikes], Default 3 statt der alten 2),
 *  - nach Erreichen der Schwelle nur TEMPORÄRE Pause ([cooldownMs]) statt Dauer-Aus — danach automatisch
 *    wieder scharf ([isPaused] setzt sich nach Ablauf selbst zurück),
 *  - alte Strikes klingen ab ([strikeDecayMs]): vereinzelte Fehler über die Zeit summieren sich nicht.
 *    Default 60 s ≈ reale Turn-Dauer (TTS + Lauschen), damit nur ein ECHT echo-anfälliges Setup (mehrere
 *    Fehlauslöser dicht hintereinander, auch über Berührungen hinweg) die Pause auslöst,
 *  - echte Sprache ([reset]) setzt alles zurück.
 * Zeit kommt über [clock] rein — in Prod monotone Uhr (SystemClock.elapsedRealtime, injiziert in
 * [BargeInDetector]), Default System.currentTimeMillis() hält diese Datei JVM-rein/testbar.
 */
class EchoStrikes(
    private val maxStrikes: Int = 3,
    private val cooldownMs: Long = 90_000L,
    private val strikeDecayMs: Long = 60_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile var strikes: Int = 0
        private set
    @Volatile private var lastStrikeAt: Long = 0L
    @Volatile private var pausedUntil: Long = 0L

    /** @return true, wenn das Voice-Barge-in jetzt TEMPORÄR pausiert werden soll (Cooldown [cooldownMs]). */
    fun record(): Boolean {
        val now = clock()
        // Abgelaufene Pause selbst aufräumen (Konsistenz mit isPaused(), falls record() zuerst läuft).
        if (pausedUntil != 0L && now >= pausedUntil) {
            pausedUntil = 0L
            strikes = 0
            lastStrikeAt = 0L
        }
        // Liegt der letzte Fehler lange zurück, zählt neu ab 1 (vereinzelte Flukes kumulieren nicht).
        // Guard über strikes>0 (nicht über lastStrikeAt==0): eine Uhr darf legitim 0 liefern.
        if (strikes > 0 && now - lastStrikeAt > strikeDecayMs) strikes = 0
        lastStrikeAt = now
        strikes++
        if (strikes >= maxStrikes) {
            pausedUntil = now + cooldownMs
            return true
        }
        return false
    }

    /** True, solange der Cooldown nach [maxStrikes] läuft. Nach Ablauf: automatisch frisch scharf (kein Dauer-Aus). */
    fun isPaused(): Boolean {
        if (pausedUntil == 0L) return false
        if (clock() >= pausedUntil) {
            pausedUntil = 0L
            strikes = 0
            lastStrikeAt = 0L
            return false
        }
        return true
    }

    fun reset() {
        strikes = 0
        lastStrikeAt = 0L
        pausedUntil = 0L
    }
}

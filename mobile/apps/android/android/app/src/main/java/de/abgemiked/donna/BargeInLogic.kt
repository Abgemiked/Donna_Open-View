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
 *    Echo-Spitze wird am ENDE der Lernphase als [echoPercentile]-Perzentil der Lern-Frames bestimmt, NACHDEM die
 *    lautesten Onset-Frames (erste [onsetSkipMs] ms) übersprungen wurden (Gerätetest v3) — so wird Evas typisches
 *    Residual gelernt, nicht der laute TTS-Einschwing-Transient (der im Bubble-/SpeechDriver-Pfad die Schwelle zu
 *    hoch riss, so dass Barge-in dort nur beim Schreien feuerte). Danach bleibt sie KONSTANT (eingefroren) — sie
 *    folgt Mikes lauter werdendem Dauerinput nicht nach oben (sonst liefe die Schwelle davon) und nicht nach unten
 *    zum Tal eines schwankenden Echos (sonst Selbst-Trigger) (Gerätetest v2).
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
    private val ambientMarginDb: Double = 12.0,
    private val noiseFloorRiseDbPerSec: Double = 2.0,
    /**
     * Gerätetest v2: ein LAUTER Frame zählt auch dann zur Dauer, wenn er selbst voice=false ist, aber in den
     * letzten [voiceRecencyMs] ms ein LAUTER voiceLike-Frame war — Mikes lauteste Barge-in-Frames sind oft
     * voice=false (AEC-Transienten). Nur aus LAUTEN Stimm-Frames gesetzt (nicht aus leisem Echo/Lernphase), damit
     * kein Dauer-Lärm nach einem Echo-Frame durchrutscht. 0 = exakt das alte Verhalten (nur voiceLike zählt).
     */
    private val voiceRecencyMs: Int = 0,
    /**
     * Gerätetest v2 (Leaky-Sustain): Bei einem NICHT-lauten Frame baut die Dauer nur um frameMs/[sustainDecayDivisor]
     * ab (statt hart). So überbrücken Mikes echo-gecancelte Wortpausen (-60-dB-Frames) die Lücke und voicedMs
     * erreicht sustainMs trotz choppy delivery. Detector: 4 (sanft); Default 2 (altes Verhalten).
     */
    private val sustainDecayDivisor: Int = 2,
    /**
     * Gerätetest v3 (Bubble-Pfad-Fix 2026-11-05): Die Echo-Spitze wird am Ende der Lernphase NICHT mehr als
     * lautester Frame gelernt (ein einzelner TTS-Onset-Transient riss sie zu hoch — im Bubble-/SpeechDriver-Pfad
     * ist dieser Einschwing-Peak lauter als im Overlay-Pfad, daher feuerte Barge-in dort nur beim Schreien),
     * sondern als [echoPercentile]-Perzentil der Lern-Frames, NACHDEM die lautesten Onset-Frames (die ersten
     * [onsetSkipMs] ms ab dem ersten hörbaren Frame) übersprungen wurden. So lernt sie Evas typisches Residual
     * (die ruhigen Frames) statt die Anfangsspitze → die eingefrorene Schwelle landet tief genug, dass Mikes
     * Normalstimme (-15..-20 dB) feuert, während Evas Residual (-28..-31 dB) drunter bleibt. Bei KONSTANTEM Echo
     * (alle Lern-Frames gleich laut) ist das Perzentil exakt dieser Wert — die bisherigen Freeze-/Selbst-Trigger-
     * Tests bleiben unverändert grün.
     */
    private val onsetSkipMs: Int = 120,
    private val echoPercentile: Double = 0.70,
    /** Mindest-Rest-Frames nach dem Onset-Skip, sonst Fallback aufs Perzentil ALLER hörbaren Lern-Frames. */
    private val minRetainedLearnFrames: Int = 3,
) {
    private var elapsedMs = 0
    private var echoPeakDb = -90.0
    private var noiseFloorDb = 0.0
    private var voicedMs = 0
    private var firstAudibleMs = -1
    private var learningDone = false
    private var silentLearning = false
    private var lastLoudVoiceMs = -1_000_000  // Gerätetest v2: letzter LAUTE voiceLike-Frame (für voiceRecency)
    // Gerätetest v3: Pegel aller hörbaren Lern-Frames + ihr Offset (ms) ab dem ersten hörbaren Frame (für den
    // Onset-Skip). Das Fenster ist warmupMs ab dem ersten hörbaren Frame (bis ~16 Frames bei 20ms/300ms) —
    // vernachlässigbarer Speicher, der nach der Lernphase nicht mehr wächst (die Listen werden dann nicht mehr beschrieben).
    private val learnDbs = ArrayList<Double>(24)
    private val learnOffsetsMs = ArrayList<Int>(24)

    var triggered: Boolean = false
        private set

    /** Diagnose (Detektor-Log): hörbare Lern-Frames gesamt / davon nach dem Onset-Skip fürs Perzentil verwendet. */
    var totalLearnFrames = 0
        private set
    var retainedLearnFrames = 0
        private set

    /** Gelernte Echo-Spitze (dBFS) — nach der Lernphase eingefroren; für Diagnose (Detektor-Log: gesund ≲ -23). */
    val learnedEchoDb: Double get() = echoPeakDb

    /** Lernphase abgeschlossen (erst danach kann ausgelöst werden) — für Tests/Diagnose. */
    val isLearningDone: Boolean get() = learningDone

    /** Aktuell aufgebaute anhaltende Sprach-Dauer (ms) — für Diagnose (Detektor-Log). */
    val currentVoicedMs: Int get() = voicedMs

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
        // Minimum-Statistik: folgt dem leisesten Pegel nach unten, steigt nur langsam (Umgebungsgeräusch-Boden,
        // 2 dB/s). Der schnelle Schwellen-Anstieg am Gerät kam NICHT von hier (langsam), sondern vom echoPeak —
        // der ist jetzt nach der Lernphase eingefroren. Dieser langsame Boden bleibt nötig, damit lauter Dauerlärm
        // (-20 dB Straße/Fernseher) die Schwelle anhebt und Sprechen auf Lärm-Niveau nicht auslöst.
        noiseFloorDb = Math.min(noiseFloorDb + noiseFloorRiseDbPerSec * frameMs / 1000.0, max(db, -80.0))
        if (!learningDone) {
            if (db >= audibleDb) {
                if (firstAudibleMs < 0) firstAudibleMs = elapsedMs
                // v3: Pegel + Offset sammeln statt sofort max() — die Spitze wird erst am Lernphasen-Ende als
                // Perzentil der NICHT-Onset-Frames bestimmt (ein lauter Einschwing-Transient verfälscht sie nicht mehr).
                learnDbs.add(db)
                learnOffsetsMs.add(elapsedMs - firstAudibleMs)
            }
            if (firstAudibleMs >= 0 && elapsedMs - firstAudibleMs >= warmupMs) {
                learningDone = true
                echoPeakDb = finalizeLearnedEchoPeak()
            } else if (elapsedMs >= hardCapMs) {
                learningDone = true
                silentLearning = firstAudibleMs < 0
                if (!silentLearning) echoPeakDb = finalizeLearnedEchoPeak()
            }
            return false
        }
        val loud = db > thresholdDb
        // Gerätetest v2 (voiceRecency): der Frame zählt zur Dauer, wenn er laut UND stimm-ähnlich ist ODER wenn er
        // laut ist und in den letzten [voiceRecencyMs] ein LAUTER voiceLike-Frame war. Mikes lauteste Barge-in-Frames
        // sind oft voice=false (AEC-Transienten mitten in lauter Rede). Nur LAUTE Stimm-Frames setzen die Frische —
        // leises Echo/Lernphase (voiceLike, aber nicht laut) tut es NICHT, sonst rutschte Dauer-Lärm nach einem
        // Echo-Frame durch. (Selbst-Trigger-Schutz: Evas Residual bleibt unter der gelernten Echo-Spitze → nie laut.)
        if (loud && voiceLike) lastLoudVoiceMs = elapsedMs
        val voiceOrRecent = voiceLike || (elapsedMs - lastLoudVoiceMs <= voiceRecencyMs)
        if (loud && voiceOrRecent) {
            voicedMs += frameMs
            if (voicedMs >= sustainMs) {
                triggered = true
                return true
            }
        } else if (loud) {
            // Laut, aber weder stimm-ähnlich noch kürzlich laute Stimme (reiner Lärm-Burst) → Fortschritt HALTEN,
            // nicht abbauen, aber auch nicht aufbauen. Reine Nicht-Sprache bleibt sicher: ohne je einen lauten
            // voiceLike-Frame bleibt voicedMs = 0 → kann nie auslösen. echoPeak NICHT anheben.
        } else {
            // Nicht laut: Leaky-Sustain — Dauer nur sanft abbauen (frameMs/[sustainDecayDivisor]), damit Mikes
            // echo-gecancelte Wortpausen (-60-dB-Frames) sie nicht wegreißen, bevor sustainMs erreicht ist.
            // coerceAtLeast(1): bei frameMs < divisor würde die Ganzzahldivision 0 → gar kein Abbau; mind. 1 ms.
            voicedMs = max(0, voicedMs - (frameMs / sustainDecayDivisor).coerceAtLeast(1))
            // Gerätetest v2 (HAUPTHEBEL gegen das Weglaufen): Die Echo-Spitze wird in der Lernphase gelernt und
            // bleibt danach KONSTANT — sie folgt Mikes Reinreden weder nach oben (sonst lief thr davon: am Gerät
            // -16 → -7) noch nach unten zum Tal eines schwankenden Echos (sonst überschritten die Echo-Peaks später
            // die gesunkene Schwelle → Selbst-Trigger). Die gelernte Eva-Spitze ist eine stabile Obergrenze.
        }
        return false
    }

    /**
     * Gerätetest v3: die gelernte Echo-Spitze am Ende der Lernphase. Überspringt die lautesten Onset-Frames
     * (erste [onsetSkipMs] ms ab dem ersten hörbaren Frame — der TTS-Einschwing-Transient) und nimmt dann das
     * [echoPercentile]-Perzentil der restlichen Frames. Bleiben nach dem Skip zu wenige ([minRetainedLearnFrames])
     * übrig (sehr kurzes Echo), fällt sie aufs Perzentil ALLER hörbaren Lern-Frames zurück (dann ist
     * [retainedLearnFrames] == [totalLearnFrames]). Das Perzentil liegt bewusst UNTER dem alten max() (es ignoriert
     * die lautesten Onset-Frames) — die harte Untergrenze gegen ein Weglaufen nach unten ist [absMinDb] in
     * [thresholdDb]. Setzt die Diagnose-Zähler.
     */
    private fun finalizeLearnedEchoPeak(): Double {
        totalLearnFrames = learnDbs.size
        if (learnDbs.isEmpty()) return echoPeakDb  // (bei firstAudibleMs>=0 nicht erreichbar; defensiv)
        val retained = ArrayList<Double>(learnDbs.size)
        for (i in learnDbs.indices) if (learnOffsetsMs[i] >= onsetSkipMs) retained.add(learnDbs[i])
        val basis = if (retained.size >= minRetainedLearnFrames) retained else learnDbs
        retainedLearnFrames = basis.size
        return percentile(basis, echoPercentile)
    }

    companion object {
        /**
         * [p]-Perzentil (0..1) einer Werteliste mit linearer Interpolation (nearest-rank mit Interpolation,
         * wie NumPy-Default). Leer → -90 dB. JVM-rein/testbar.
         */
        fun percentile(values: List<Double>, p: Double): Double {
            if (values.isEmpty()) return -90.0
            if (values.size == 1) return values[0]
            val sorted = values.sorted()
            val clamped = p.coerceIn(0.0, 1.0)
            val rank = clamped * (sorted.size - 1)
            val lo = Math.floor(rank).toInt()
            val hi = Math.ceil(rank).toInt()
            if (lo == hi) return sorted[lo]
            val frac = rank - lo
            return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
        }

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

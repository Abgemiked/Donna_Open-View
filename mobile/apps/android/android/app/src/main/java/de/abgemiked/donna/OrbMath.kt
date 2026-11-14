package com.yourcompany.donna

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Reine Animationsmathematik für den Donna-Orb (ohne Android-Laufzeit testbar).
 * Die View [DonnaOrbView] zeichnet nur noch, was hier berechnet wird.
 */
object OrbMath {

    /** Periodendauern der Zustände. */
    const val THINKING_PERIOD_MS = 1_600L   // atmen/rotieren
    const val SPEAKING_PERIOD_MS = 1_000L   // ~1 Hz pulsieren
    const val LISTENING_IDLE_PERIOD_MS = 2_400L
    const val ERROR_SHAKE_MS = 480L

    /**
     * onRmsChanged-Wert des SpeechRecognizers (grob −2 … +10 dB) → 0…1.
     * Leises Rauschen (< 0 dB) bleibt nahe 0, normale Sprache liegt bei ~0,5 – 0,9.
     */
    fun levelFromRms(rmsdB: Float): Float {
        if (rmsdB.isNaN()) return 0f
        return ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
    }

    /** Schnell ansteigen (Attack), langsam abklingen (Release) — wirkt flüssig statt zappelig. */
    fun smooth(previous: Float, target: Float, attack: Float = 0.55f, release: Float = 0.12f): Float {
        val k = if (target > previous) attack else release
        return previous + (target - previous) * k
    }

    /** 0…1…0 über eine Periode (Cosinus-Atmung), phase = 0…1. */
    fun breathe(phase: Float): Float = (0.5 - 0.5 * cos(2.0 * PI * phase)).toFloat()

    /** Phase 0…1 aus Zeit und Periode. */
    fun phase(timeMs: Long, periodMs: Long): Float {
        if (periodMs <= 0) return 0f
        val m = ((timeMs % periodMs) + periodMs) % periodMs
        return m.toFloat() / periodMs.toFloat()
    }

    /** Horizontales Schütteln beim Fehler: abklingende Schwingung, elapsed 0…[ERROR_SHAKE_MS]. */
    fun shakeOffset(elapsedMs: Long, amplitudePx: Float): Float {
        if (elapsedMs < 0 || elapsedMs >= ERROR_SHAKE_MS) return 0f
        val t = elapsedMs.toFloat() / ERROR_SHAKE_MS.toFloat()
        return (amplitudePx * sin(2.0 * PI * 3.0 * t) * (1f - t)).toFloat()
    }

    /** Kernradius-Faktor (1 = Ruhegröße) je Zustand. */
    fun coreScale(state: OverlayState, level: Float, phaseThinking: Float, phaseSpeaking: Float, phaseIdle: Float): Float =
        when (state) {
            OverlayState.LISTENING -> 1f + 0.20f * level + 0.03f * breathe(phaseIdle)
            OverlayState.THINKING -> 1f + 0.06f * breathe(phaseThinking)
            OverlayState.SPEAKING -> 1f + 0.10f * breathe(phaseSpeaking)
            OverlayState.ERROR, OverlayState.IDLE -> 1f
        }

    /** Stärke des Leuchtens (0…1). */
    fun glowStrength(state: OverlayState, level: Float, phaseSpeaking: Float): Float = when (state) {
        OverlayState.LISTENING -> 0.45f + 0.55f * level
        OverlayState.THINKING -> 0.55f
        OverlayState.SPEAKING -> 0.50f + 0.35f * breathe(phaseSpeaking)
        OverlayState.ERROR -> 0.50f
        OverlayState.IDLE -> 0.25f
    }

    /** Läuft im Zustand eine Dauer-Animation? (IDLE ist statisch, ERROR schüttelt nur einmal.) */
    fun isContinuous(state: OverlayState): Boolean =
        state == OverlayState.LISTENING || state == OverlayState.THINKING || state == OverlayState.SPEAKING
}

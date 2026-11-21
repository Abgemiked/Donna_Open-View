package com.yourcompany.donna

/**
 * Reine Darstellungsentscheidungen des Gespraechsmodus (#122, Paket B) — OHNE Android-Typen, JVM-testbar.
 * Blase ([LiveBubbleService]) und Activity-Overlay ([VoiceInputActivity]) fragen DIESELBE Abbildung
 * [OrbVisuals.of]; [DonnaOrbView] und [RainbowEdgeView] setzen sie nur noch um (zeichnen, animieren).
 */

/** Farbrolle des Orbs; die echten Farben kommen aus dem Theme (colors.xml). */
enum class OrbTone { ACCENT, ACCENT2, MUTED, ERROR }

/** Bewegungsart des Orbs. [NONE] = statisch (auch bei „Animationen entfernen“). */
enum class OrbMotion { NONE, LEVEL, BREATHE, SPEAK_PULSE, SHAKE }

data class OrbVisual(
    val tone: OrbTone,
    val motion: OrbMotion,
    /** Zustandstext (Statuszeile der Blase). */
    val label: String,
    /** Anteil des Hoerfensters, der noch uebrig ist (1 -> 0); null = kein Ring. */
    val ringFraction: Float?,
    /** Leuchtender Regenbogen-Rand sichtbar (genau dann, wenn das Mikrofon aufnimmt). */
    val edgeGlow: Boolean,
    /** Rand rotiert (nur mit Animationen). */
    val edgeRotating: Boolean,
    /** Die schwebende Blase pulsiert (nur beim Aufnehmen und mit Animationen). */
    val bubblePulse: Boolean,
)

object OrbVisuals {
    // #134 P7: kurze Zustandswoerter ohne Anleitung (kein "tippen zum ..."); die Bedienung zeigen die Tasten selbst.
    const val LABEL_LISTENING = "Ich höre zu"
    const val LABEL_FOLLOW_UP = "Ich höre weiter zu"
    const val LABEL_THINKING = "Donna denkt nach"
    const val LABEL_SPEAKING = "Donna spricht"
    const val LABEL_IDLE = "Bereit"
    const val LABEL_ERROR = "Das hat nicht geklappt"

    /** Ring-Anteil allein (je Frame nutzbar, ohne die ganze Abbildung neu zu bilden); null ausserhalb des Weiterhoerens. */
    fun ringFraction(state: OverlayState, animationsEnabled: Boolean, followUpFraction: Float): Float? {
        if (state != OverlayState.FOLLOW_UP) return null
        return if (animationsEnabled && !followUpFraction.isNaN()) followUpFraction.coerceIn(0f, 1f)
        else if (animationsEnabled) 0f else 1f
    }

    /**
     * @param animationsEnabled false bei „Animationen entfernen“ -> keine Bewegung, kein Rotieren, kein Puls,
     *   der Ring bleibt voll (kein Zeitgeber noetig); Zustandsfarbe und statischer Rand bleiben.
     * @param followUpFraction Restanteil des Hoerfensters (siehe [FollowUpRing]); nur im Weiterhoeren wirksam.
     */
    fun of(state: OverlayState, animationsEnabled: Boolean, followUpFraction: Float = 1f): OrbVisual {
        val listening = state.isListening
        val tone = when (state) {
            OverlayState.IDLE, OverlayState.ENDING, OverlayState.ENDED -> OrbTone.MUTED
            OverlayState.LISTENING, OverlayState.FOLLOW_UP, OverlayState.SPEAKING -> OrbTone.ACCENT
            OverlayState.THINKING -> OrbTone.ACCENT2
            OverlayState.ERROR -> OrbTone.ERROR
        }
        val motion = if (!animationsEnabled) OrbMotion.NONE else when (state) {
            OverlayState.LISTENING, OverlayState.FOLLOW_UP -> OrbMotion.LEVEL
            OverlayState.THINKING -> OrbMotion.BREATHE
            OverlayState.SPEAKING -> OrbMotion.SPEAK_PULSE
            OverlayState.ERROR -> OrbMotion.SHAKE
            OverlayState.IDLE, OverlayState.ENDING, OverlayState.ENDED -> OrbMotion.NONE
        }
        val label = when (state) {
            OverlayState.LISTENING -> LABEL_LISTENING
            OverlayState.FOLLOW_UP -> LABEL_FOLLOW_UP
            OverlayState.THINKING -> LABEL_THINKING
            OverlayState.SPEAKING -> LABEL_SPEAKING
            OverlayState.IDLE -> LABEL_IDLE
            OverlayState.ENDING, OverlayState.ENDED -> ConversationTexts.BYE
            OverlayState.ERROR -> LABEL_ERROR
        }
        val ring = ringFraction(state, animationsEnabled, followUpFraction)
        return OrbVisual(
            tone = tone,
            motion = motion,
            label = label,
            ringFraction = ring,
            edgeGlow = listening,
            edgeRotating = listening && animationsEnabled,
            bubblePulse = listening && animationsEnabled,
        )
    }
}

/** Ring im Weiterhoeren: gespeist aus [FollowUpWindow.remainingMs] — derselben Rechnung wie die Steuerung. */
object FollowUpRing {
    /** 1 = Fenster voll, 0 = abgelaufen. Vor dem Fensterbeginn ([startedAt] <= 0, Echo-Schutz) oder ohne Fenster: 1. */
    fun fraction(windowMs: Long, startedAt: Long, now: Long): Float {
        if (windowMs <= 0L || startedAt <= 0L) return 1f
        return FollowUpWindow.remainingMs(windowMs, startedAt, now).toFloat() / windowMs.toFloat()
    }
}

/** Leuchtender Rand: benannte Farben (Regenbogen, Start = Ende fuer einen nahtlosen Sweep) und Umlaufzeit. */
object RainbowEdge {
    const val PERIOD_MS = 6_000L
    private const val RED = 0xFFFF4D6D.toInt()
    private const val ORANGE = 0xFFFF9F43.toInt()
    private const val YELLOW = 0xFFFFD93D.toInt()
    private const val GREEN = 0xFF4ADE80.toInt()
    private const val CYAN = 0xFF38BDF8.toInt()
    private const val VIOLET = 0xFFA78BFA.toInt()
    val COLORS: IntArray = intArrayOf(RED, ORANGE, YELLOW, GREEN, CYAN, VIOLET, RED)

    fun rotationDeg(timeMs: Long): Float = OrbMath.phase(timeMs, PERIOD_MS) * 360f
}

/** Puls der schwebenden Blase (Skalierung 1 … [MAX_SCALE]), phase = 0…1. */
object BubblePulse {
    const val PERIOD_MS = 1_200L
    const val MAX_SCALE = 1.14f
    fun scale(phase: Float): Float = 1f + (MAX_SCALE - 1f) * OrbMath.breathe(phase)
}

enum class HapticCue { LISTEN_START, CONVERSATION_END }

/**
 * Haptik-Entscheidung: ein kurzes Signal beim Start des Zuhoerens nach einem Aufruf (aus Leerlauf/Fehler/erstem
 * Start) und eines beim Gespraechsende — nie bei jedem Zustandswechsel, nie beim Weiterhoeren, nie beim Unterbrechen.
 */
class HapticCues {
    private var prev: OverlayState? = null
    private var endCued = false

    fun onState(state: OverlayState): HapticCue? {
        val p = prev
        prev = state
        if (state == p) return null
        return when {
            state == OverlayState.LISTENING &&
                (p == null || p == OverlayState.IDLE || p == OverlayState.ERROR) -> { endCued = false; HapticCue.LISTEN_START }
            (state == OverlayState.ENDING || state == OverlayState.ENDED) && !endCued -> { endCued = true; HapticCue.CONVERSATION_END }
            else -> null
        }
    }
}

enum class NotificationAction(val label: String) { MUTE("Stumm"), END("Beenden") }

data class NotificationSpec(val text: String, val actions: List<NotificationAction>)

/** Beschreibung der Vordergrund-Benachrichtigung der Blase (der Dienst baut daraus die echte). */
object BubbleNotification {
    const val TEXT_LISTENING = "Donna hört zu"
    const val TEXT_ACTIVE = "Donna-Gespräch aktiv"

    fun spec(state: OverlayState): NotificationSpec =
        if (state.isListening) NotificationSpec(TEXT_LISTENING, listOf(NotificationAction.MUTE, NotificationAction.END))
        else NotificationSpec(TEXT_ACTIVE, listOf(NotificationAction.END))
}

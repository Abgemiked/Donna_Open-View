package com.yourcompany.donna.overlay

import android.text.Html
import com.yourcompany.donna.MarkdownHtml
import com.yourcompany.donna.SpeechText

/**
 * Darstellung des Streaming-Antworttextes eines Turns (B1 Phase-C Overlay-Refactor).
 *
 * 1:1 aus `VoiceInputActivity.renderResponse`/`scheduleRender` extrahiert — VERHALTENSNEUTRAL.
 * Die reine Entscheidungslogik (Drossel-Timing [throttle], Autoscroll-Bedingung
 * [shouldAutoscroll]) ist ohne Android-Laufzeit testbar; das eigentliche View-Rendering
 * (Html.fromHtml → TextView, Sichtbarkeit, scrollTo) läuft über [Host] zurück in die Activity.
 *
 * Die Turn-Lebenszyklus-Buchführung (lastRenderAt, renderScheduled, renderRunnable) bleibt
 * bewusst in der Activity — sie ist mit Pipeline-/Stream-Zuständen verwoben; hier wird nur die
 * zusammenhängende Dreh-Logik gekapselt.
 */
class TurnRenderer(private val host: Host) {

    interface Host {
        /** Setzt den gerenderten HTML-Text (vormals `responseText.text = …`). */
        fun setResponseHtml(html: CharSequence)
        /** Macht die Antwortfläche sichtbar (vormals `responseScroll.visibility = VISIBLE`). */
        fun showResponse()
        /** Scrollt den Körper ans Ende (vormals `bodyScroll.post { bodyScroll.scrollTo(0, bodyContent.height) }`). */
        fun autoscroll()
    }

    /**
     * Rendert den (bereinigten) Rohtext als Markdown/HTML. Leerer Anzeigetext ändert nichts
     * (identisch zum früheren frühen `return`). Autoscroll nur unter [shouldAutoscroll].
     */
    fun render(rawText: String, streamEnded: Boolean, userScrolled: Boolean) {
        val shown = SpeechText.displayText(rawText).trim()
        if (shown.isEmpty()) return
        host.setResponseHtml(Html.fromHtml(MarkdownHtml.convert(shown), Html.FROM_HTML_MODE_COMPACT))
        host.showResponse()
        if (shouldAutoscroll(streamEnded, userScrolled)) host.autoscroll()
    }

    /** Ergebnis der Drossel-Entscheidung. */
    sealed class Throttle {
        /** Sofort rendern (Drosselzeit abgelaufen). */
        object RenderNow : Throttle()
        /** Einmalig mit der Restzeit planen. */
        data class Schedule(val delayMs: Long) : Throttle()
        /** Nichts tun (innerhalb der Drosselzeit und bereits geplant). */
        object Skip : Throttle()
    }

    companion object {
        /** Beim Streamen mitscrollen, solange der Nutzer nicht selbst gescrollt hat. */
        fun shouldAutoscroll(streamEnded: Boolean, userScrolled: Boolean): Boolean =
            !streamEnded && !userScrolled

        /**
         * Text gedrosselt (~[com.yourcompany.donna.OverlayTimings.TEXT_THROTTLE_MS]) anzeigen:
         * sofort, wenn die letzte Darstellung lange her ist, sonst gebündelt (einmalig geplant).
         */
        fun throttle(now: Long, lastRenderAt: Long, throttleMs: Long, alreadyScheduled: Boolean): Throttle {
            val wait = throttleMs - (now - lastRenderAt)
            return when {
                wait <= 0 -> Throttle.RenderNow
                !alreadyScheduled -> Throttle.Schedule(wait)
                else -> Throttle.Skip
            }
        }
    }
}

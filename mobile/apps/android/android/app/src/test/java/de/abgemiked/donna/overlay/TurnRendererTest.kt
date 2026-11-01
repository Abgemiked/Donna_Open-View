package com.yourcompany.donna.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Charakterisierungs-Tests für die reine Dreh-/Drossel-Logik der Streaming-Textdarstellung
 * aus [TurnRenderer] (B1 Phase-C Overlay-Refactor).
 *
 * Die Golden Values stammen aus dem früheren inline `scheduleRender`/`renderResponse` in
 * VoiceInputActivity: Autoscroll nur beim Streamen solange der Nutzer nicht selbst gescrollt
 * hat; Text-Drossel = TEXT_THROTTLE_MS (50 ms) seit letztem Render — sonst EINMALIG planen.
 * Das eigentliche View-Rendering (Html.fromHtml, scrollTo) ist Android-gebunden und nur auf dem
 * Gerät prüfbar; hier wird die Entscheidungslogik ohne Android-Laufzeit festgenagelt.
 */
class TurnRendererTest {

    // ── Autoscroll-Bedingung ──────────────────────────────────────────────────
    @Test
    fun `Autoscroll nur beim Streamen und ohne eigenes Scrollen`() {
        assertTrue(TurnRenderer.shouldAutoscroll(streamEnded = false, userScrolled = false))
        assertFalse("Nutzer hat gescrollt", TurnRenderer.shouldAutoscroll(false, userScrolled = true))
        assertFalse("Stream beendet", TurnRenderer.shouldAutoscroll(streamEnded = true, userScrolled = false))
        assertFalse(TurnRenderer.shouldAutoscroll(streamEnded = true, userScrolled = true))
    }

    // ── Drossel-Entscheidung ──────────────────────────────────────────────────
    @Test
    fun `sofort rendern wenn die Drosselzeit abgelaufen ist`() {
        // now - lastRenderAt = 60 >= 50 -> wait <= 0 -> RenderNow
        assertEquals(
            TurnRenderer.Throttle.RenderNow,
            TurnRenderer.throttle(now = 1060, lastRenderAt = 1000, throttleMs = 50, alreadyScheduled = false),
        )
    }

    @Test
    fun `erstes Mal innerhalb der Drosselzeit wird mit Restzeit geplant`() {
        // now - lastRenderAt = 20 -> wait = 30 -> Schedule(30)
        assertEquals(
            TurnRenderer.Throttle.Schedule(30),
            TurnRenderer.throttle(now = 1020, lastRenderAt = 1000, throttleMs = 50, alreadyScheduled = false),
        )
    }

    @Test
    fun `bereits geplant innerhalb der Drosselzeit wird uebersprungen`() {
        assertEquals(
            TurnRenderer.Throttle.Skip,
            TurnRenderer.throttle(now = 1020, lastRenderAt = 1000, throttleMs = 50, alreadyScheduled = true),
        )
    }

    @Test
    fun `abgelaufene Drosselzeit rendert sofort auch wenn schon geplant`() {
        assertEquals(
            TurnRenderer.Throttle.RenderNow,
            TurnRenderer.throttle(now = 2000, lastRenderAt = 1000, throttleMs = 50, alreadyScheduled = true),
        )
    }

    @Test
    fun `erster Render bei lastRenderAt 0 rendert sofort`() {
        // lastRenderAt = 0L (Initialwert) -> wait <= 0 -> RenderNow
        assertEquals(
            TurnRenderer.Throttle.RenderNow,
            TurnRenderer.throttle(now = 123456, lastRenderAt = 0, throttleMs = 50, alreadyScheduled = false),
        )
    }
}

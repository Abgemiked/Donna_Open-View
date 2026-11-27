package com.yourcompany.donna

/**
 * #125 (Pause): Richtlinie fuer den AUTOMATISCHEN Versand von Bildschirmtext an den Server
 * (DonnaAccessibilityService.onAccessibilityEvent -> /tracking/screen).
 *
 * Standard AUS, bis der lokale Filter auf dem Handy steht. Betrifft NICHT das bestaetigte Antippen
 * (B2) und NICHT das Lesen auf Anforderung (AccessibilityBridge.extractScreenText).
 * Rein Kotlin, ohne Android-Laufzeit testbar.
 */
object ScreenPushPolicy {
    const val AUTO_PUSH_ENABLED: Boolean = false

    fun shouldAutoPush(): Boolean = AUTO_PUSH_ENABLED
}

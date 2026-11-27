package com.yourcompany.donna

/**
 * #180: Wartezeiten für den NtfyService-Neustart (rein, JVM-getestet).
 *  - Start gescheitert (Hintergrundstart nicht erlaubt / Zeitlimit erschöpft): 60 s, kein Dauerfeuer.
 *  - Nach FGS-Timeout: 10 s, damit der Stopp sicher durch ist, bevor neu gestartet wird.
 */
object NtfyRestartPolicy {
    const val RETRY_AFTER_START_FAILURE_MS = 60_000L
    const val RESTART_AFTER_TIMEOUT_MS = 10_000L
}

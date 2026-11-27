package com.yourcompany.donna.device

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * #147 Paket D — klare Regel, WANN der Offline-Weg greift (reine Logik, testbar):
 *  1. Das Gerät hat gar kein Netz mit Internet -> sofort offline (keine Wartezeit).
 *  2. Sonst: der Server ist NICHT ERREICHBAR, wenn der Verbindungsaufbau scheitert (Host unbekannt, Verbindung
 *     abgelehnt, keine Route, Zeitgrenze [CONNECT_TIMEOUT_MS] beim Verbinden) oder ein Vorschaltdienst meldet
 *     502. Dann – und nur dann – gilt die Regeltabelle auf dem Gerät.
 *  3. Nie, sobald der Server geantwortet hat (auch langsam/mit Fehler): Lesezeitüberschreitungen, Abbrüche mitten im
 *     Strom, Anmelde- und Serverfehler (401/403/500 …) und Zertifikatsfehler bleiben normale Fehler. Hat der Strom schon
 *     ein Ereignis geliefert, wird nichts mehr ersetzt.
 */
object OfflineGate {
    /** Kurze Zeitgrenze für den Verbindungsaufbau (vorher 20 s); 12 s lässt schwachem Mobilfunk noch Luft. */
    const val CONNECT_TIMEOUT_MS = 12_000

    /** Nur 502 (Vorschaltdienst erreicht den Server nicht). 503/504 kann der Server selbst senden, evtl. nach Annahme. */
    private val FALLBACK_HTTP = setOf(502)

    fun httpMeansDown(code: Int): Boolean = code in FALLBACK_HTTP

    /** [reached]: der Verbindungsaufbau zum Server war schon erfolgreich. */
    fun unreachable(e: Throwable, reached: Boolean): Boolean = when (e) {
        is UnknownHostException, is ConnectException, is NoRouteToHostException, is PortUnreachableException -> !reached
        is SocketTimeoutException -> !reached // nur die Zeitgrenze beim Verbinden, nie ein Lese-Timeout
        else -> false
    }

    fun shouldFallback(sawEvent: Boolean, error: Throwable?, httpCode: Int?, reached: Boolean): Boolean {
        if (sawEvent) return false
        if (httpCode != null) return httpMeansDown(httpCode)
        return error != null && unreachable(error, reached)
    }
}

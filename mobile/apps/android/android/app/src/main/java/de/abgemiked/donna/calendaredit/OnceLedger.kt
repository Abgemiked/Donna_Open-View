package com.yourcompany.donna.calendaredit

import com.yourcompany.donna.device.ResultKind

/**
 * #147 Paket H — Einmal-Garantie je Karte/Aktion: geordnete Liste der schon ausgeführten Kennungen (höchstens 50, die
 * ältesten fallen zuerst heraus). Rein, ohne Android; das Modul speichert den Text in den Einstellungen.
 */
object OnceLedger {
    const val MAX = 50

    /** Ein erneuter Tipp auf eine schon ausgeführte Karte ist KEIN Fehler: die Aktion ist erledigt (ok melden). */
    val REPORT_FOR_ALREADY_DONE: ResultKind = ResultKind.OK

    fun ids(stored: String?): List<String> = (stored ?: "").split(',').filter { it.isNotEmpty() }

    fun contains(stored: String?, id: String): Boolean = id in ids(stored)

    fun add(stored: String?, id: String): String = (ids(stored).filter { it != id } + id).takeLast(MAX).joinToString(",")
}

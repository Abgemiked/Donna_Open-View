package com.yourcompany.donna.device

import org.json.JSONObject

/**
 * #183 Schritt 0 (Refs #147): Rückkanal App -> Server — reine Logik. Der Server vergibt je Geräte-Aktion eine
 * Kennung (`action_id`, optional `group_id`), sofern die Anfrage `device_action_results` meldet; die App meldet danach
 * NUR einen Ergebniscode und eine Ursache aus fester Liste (keine Inhalte, kein Freitext). Vertrag:
 * `backend/tests/contracts/device_action_result_contract.json` (Server- und App-Test lesen dieselbe Datei).
 */
object DeviceActionReport {
    const val REQUEST_FLAG = "device_action_results"
    const val ACTION_ID_PATTERN = "^da_[0-9a-f]{16}$"
    const val GROUP_ID_PATTERN = "^g_[0-9a-f]{16}$"
    val ID_FIELDS: Set<String> = setOf("action_id", "group_id")

    val RESULT_CODES: List<String> = listOf("ok", "braucht_berechtigung", "nicht_unterstuetzt", "fehler")
    val REASONS: List<String> = listOf(
        "schalter_aus", "gesperrt", "mehrdeutig", "abgelehnt", "berechtigung", "nicht_verfuegbar",
        "fehlgeschlagen", "sonstige",
    )

    private val ACTION_ID = Regex(ACTION_ID_PATTERN)
    private val GROUP_ID = Regex(GROUP_ID_PATTERN)

    data class Wire(val result: String, val reason: String?)

    /** Jede Ergebnisart des Geräts hat genau einen Code (Tabelle im Vertrag). */
    fun wire(kind: ResultKind): Wire = when (kind) {
        ResultKind.OK -> Wire("ok", null)
        ResultKind.NEEDS_PERMISSION -> Wire("braucht_berechtigung", "berechtigung")
        ResultKind.UNSUPPORTED -> Wire("nicht_unterstuetzt", "nicht_verfuegbar")
        ResultKind.FAILED -> Wire("fehler", "fehlgeschlagen")
        ResultKind.REJECTED -> Wire("fehler", "abgelehnt")
        ResultKind.DISABLED -> Wire("nicht_unterstuetzt", "schalter_aus")
        ResultKind.AMBIGUOUS -> Wire("fehler", "mehrdeutig")
        ResultKind.BLOCKED -> Wire("nicht_unterstuetzt", "gesperrt")
    }

    /** Kennung nur, wenn sie exakt dem Muster entspricht (sonst wird weder gemeldet noch ein Pfad gebaut). */
    fun actionId(json: JSONObject): String? =
        (json.opt("action_id") as? String)?.takeIf { ACTION_ID.matches(it) }

    fun groupId(json: JSONObject): String? =
        (json.opt("group_id") as? String)?.takeIf { GROUP_ID.matches(it) }

    fun path(actionId: String): String = "/device-actions/$actionId/result"

    fun body(w: Wire): String =
        JSONObject().put("result", w.result).apply { if (w.reason != null) put("reason", w.reason) }.toString()

    /** Netzfehler, Serverfehler und Drosselung einmal wiederholen; 2xx und andere 4xx sind endgültig. */
    fun shouldRetry(httpCode: Int?): Boolean = httpCode == null || httpCode == 429 || httpCode >= 500
}

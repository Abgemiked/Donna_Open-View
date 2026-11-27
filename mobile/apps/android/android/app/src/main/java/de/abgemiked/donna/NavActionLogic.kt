package com.yourcompany.donna

/**
 * Reine Logik der Navigations-Actions im Seitentasten-Overlay (ohne Android-Laufzeit testbar).
 *
 * Backend-Vertrag: tesla_navigate / navigate (destination, [resolved_address]),
 * navigate_disambiguate / navigate_not_found (nav_type, query, [options]).
 * `nav_type` fehlt bei älteren Backends → Handy-Navigation.
 */
object NavActionLogic {

    const val TESLA_NAVIGATE = "tesla_navigate"

    /** Tesla nur, wenn die Action selbst oder ihr nav_type ausdrücklich tesla_navigate ist. */
    fun isTesla(type: String?, navType: String?): Boolean =
        type == TESLA_NAVIGATE || navType == TESLA_NAVIGATE

    /**
     * Sendeziel: volle Adresse (nav_address) > kurze aufgelöste Adresse > gesprochenes Ziel > Suchanfrage.
     */
    fun targetOf(navAddress: String?, resolvedAddress: String?, destination: String?, query: String?): String =
        listOf(navAddress, resolvedAddress, destination, query)
            .map { it?.trim().orEmpty() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()

    /**
     * Navigationsziel einer (tesla_)navigate-Action aus dem Backend-JSON — Priorität identisch zum Haupt-Chat
     * (`getNavTarget` in utils/navActions.ts): nav_address > resolved_address > destination > query.
     * JSON-null/Nicht-Strings zählen als fehlend (nie ein wörtliches „null“).
     */
    fun targetOfAction(action: org.json.JSONObject): String = targetOf(
        JsonSafe.text(action, "nav_address"),
        JsonSafe.text(action, "resolved_address"),
        JsonSafe.text(action, "destination"),
        JsonSafe.text(action, "query"),
    )

    /** Gewählter Treffer: volle Adresse (address), sonst das (gekürzte) Label älterer Backends. */
    fun optionTarget(address: String?, label: String?): String =
        listOf(address, label).map { it?.trim().orEmpty() }.firstOrNull { it.isNotEmpty() }.orEmpty()

    /** Freundliche Meldung für navigate_not_found. */
    fun notFoundMessage(query: String?, tesla: Boolean): String {
        val q = query?.trim().orEmpty()
        val where = if (tesla) " für den Tesla" else ""
        return if (q.isEmpty()) {
            "Ich habe das Ziel nicht gefunden$where. Nenn mir bitte eine genauere Adresse."
        } else {
            "Ich habe „$q“ nicht gefunden$where. Nenn mir bitte eine genauere Adresse oder den Ort dazu."
        }
    }
}

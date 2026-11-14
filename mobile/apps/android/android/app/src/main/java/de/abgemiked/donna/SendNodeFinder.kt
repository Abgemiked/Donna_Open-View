package com.yourcompany.donna

/**
 * SendNodeFinder — REINE, Android-unabhängige Suche nach dem „Senden"/„Anrufen"-Node
 * (B2 Accessibility Auto-Send #3 WhatsApp + #1 Anruf, Stufe 3, SICHERHEITSSENSIBEL).
 *
 * Entkopplung: `android.view.accessibility.AccessibilityNodeInfo` ist im android.jar nur ein
 * nicht ausführbarer Stub. Darum arbeitet die gesamte Such-Logik auf der reinen Datenklasse
 * [UiNode] und ist voll JVM-unit-testbar (siehe SendNodeFinderTest). Im Betrieb baut
 * [DonnaAccessibilityService] aus dem echten rootInActiveWindow einen [UiNode]-Baum, dessen `ref`
 * auf den echten Node zeigt; der Treffer wird via performAction(ACTION_CLICK) geklickt.
 *
 * HEURISTIK (nach Stufe-3-Review, 2. Runde — KONSERVATIV: lieber Fallback als Fehlklick):
 *  - NUR zwei Stufen: P1 = exakter viewId-Suffix ("/send"), P2 = EXAKTE contentDescription-Gleichheit.
 *    Ein TEXT-Match (früher P3) ist ENTFERNT — ein klickbarer Node mit Text „Senden" könnte fremder
 *    Inhalt sein (Chat-Nachricht) und ein falsches „gesendet" melden. Konsequenz: etwas niedrigere
 *    Auto-Tap-Quote, dafür kein Textmatch-Risiko (bewusst, fail-safe).
 *  - contentDescription: EXAKTE Gleichheit gegen eine enge Label-Liste (kein Teilwort/Phrase):
 *    „Anrufen Max Mustermann"/„Standort senden"/„Senden abbrechen" treffen NICHT.
 *  - MEHRDEUTIGKEIT: >1 Kandidat in einer Stufe → kein eindeutiger Treffer → Fallback (null).
 *  - Treffer muss SELBST usable sein: isClickable + sichtbar + aktiv + Fläche ≤ [MAX_AREA_FRACTION];
 *    unbekannte Fläche (null) → fail-closed (NICHT usable). Kein Climb mehr — der Node selbst klickt.
 *  - [find] erhält zudem ein `truncated`-Flag: war der gelesene Baum abgeschnitten (Tiefen-/Knotenlimit
 *    oder null-Kind), kann der Mehrdeutigkeits-Check unvollständig sein → IMMER Fallback (fail-safe).
 *
 * SICHERHEIT (Paket): nur „whatsapp"/„call" haben einen [AutoSendSpec]; `allowedPackages` ist fest
 * verdrahtet (nicht aus JS erweiterbar); `com.android.server.telecom` (In-Call-UI) ist NICHT erlaubt.
 */

/** Reine Abbildung eines UI-Knotens. `ref` ist im Test null, im Betrieb der echte Node (opak). */
data class UiNode(
    val viewId: String? = null,
    val contentDescription: String? = null,
    val text: String? = null,
    val isClickable: Boolean = false,
    val isEnabled: Boolean = true,
    val isVisibleToUser: Boolean = true,
    /** Node-Fläche / Fensterfläche (0..1). null = unbekannt → fail-closed (nicht usable). */
    val areaFraction: Double? = null,
    val children: List<UiNode> = emptyList(),
    val ref: Any? = null,
)

/** Fest verdrahtete Such-Spezifikation je Aktions-Kind (keine JS-Hints — nur Defaults). */
data class AutoSendSpec(
    val kind: String,
    val allowedPackages: Set<String>,
    /** Exakte viewId-Suffixe MIT führendem „/" (Grenze), z. B. "/send". */
    val viewIdSuffixes: List<String>,
    /** Erlaubte EXAKTE Labels (contentDescription/Text), z. B. "Senden", "Anrufen". */
    val descriptions: List<String>,
) {
    fun toHints(): SendNodeFinder.Hints = SendNodeFinder.Hints(viewIdSuffixes, descriptions)
}

object SendNodeFinder {

    /** Such-Vorgaben aus [AutoSendSpec]. */
    data class Hints(val viewIdSuffixes: List<String>, val descriptions: List<String>)

    /** Fläche eines Treffers darf höchstens so groß relativ zum Fenster sein (Fehlklick-Schutz). */
    const val MAX_AREA_FRACTION = 0.4

    private val SPECS: Map<String, AutoSendSpec> = mapOf(
        "whatsapp" to AutoSendSpec(
            kind = "whatsapp",
            allowedPackages = setOf("com.whatsapp", "com.whatsapp.w4b"),
            viewIdSuffixes = listOf("/send"),
            descriptions = listOf("senden", "send"),
        ),
        "call" to AutoSendSpec(
            kind = "call",
            // Gängige Dialer; NICHT com.android.server.telecom (In-Call-UI) und keine FAB-viewId
            // (öffnet nur das Tastenfeld, startet keinen Anruf) → nur exakte contentDescription.
            allowedPackages = setOf(
                "com.google.android.dialer",
                "com.samsung.android.dialer",
                "com.android.dialer",
            ),
            viewIdSuffixes = emptyList(),
            descriptions = listOf("anrufen", "wählen"),
        ),
    )

    fun specFor(kind: String): AutoSendSpec? = SPECS[kind]

    /** Reine Paket-Prüfung: im Spec erlaubt UND nicht geblockt. */
    fun isAllowedPackage(spec: AutoSendSpec, pkg: String?, blocked: Set<String>): Boolean =
        pkg != null && pkg in spec.allowedPackages && pkg !in blocked

    // ── Prädikate ─────────────────────────────────────────────────────────────
    fun matchesViewId(viewId: String?, hints: Hints): Boolean =
        viewId != null && hints.viewIdSuffixes.any { it.startsWith("/") && viewId.endsWith(it) }

    /** EXAKTE Gleichheit gegen die erlaubten Labels (getrimmt, case-insensitiv) — kein Teilwort. */
    fun matchesExactLabel(label: String?, hints: Hints): Boolean {
        val l = label?.trim() ?: return false
        return hints.descriptions.any { l.equals(it, ignoreCase = true) }
    }

    /** Treffer muss SELBST klickbar + sichtbar + aktiv + klein genug sein. Fläche null = fail-closed. */
    private fun usable(n: UiNode): Boolean =
        n.isClickable && n.isEnabled && n.isVisibleToUser &&
            n.areaFraction != null && n.areaFraction <= MAX_AREA_FRACTION

    /**
     * Sammelt alle usable Kandidaten je Stufe und wählt KONSERVATIV:
     *  P1 = exakter viewId-Suffix, P2 = exakte contentDescription (KEIN Textmatch).
     *  Pro Stufe gilt: genau 1 Kandidat → dieser; 0 → nächste Stufe; >1 → mehrdeutig → Fallback (null).
     *  Eine mehrdeutige höhere Stufe steigt NICHT auf eine eindeutige niedrigere ab (fail-safe).
     *  [truncated]: war der Baum abgeschnitten (Limit/null-Kind), ist der Mehrdeutigkeits-Check evtl.
     *  unvollständig → IMMER Fallback (ein zweiter Send-Kandidat könnte im fehlenden Teil liegen).
     */
    fun find(root: UiNode, hints: Hints, truncated: Boolean = false): UiNode? {
        if (truncated) return null

        val p1 = mutableListOf<UiNode>()
        val p2 = mutableListOf<UiNode>()

        fun dfs(n: UiNode) {
            if (usable(n)) {
                when {
                    matchesViewId(n.viewId, hints) -> p1.add(n)
                    matchesExactLabel(n.contentDescription, hints) -> p2.add(n)
                }
            }
            for (c in n.children) dfs(c)
        }
        dfs(root)

        for (level in listOf(p1, p2)) {
            when (level.size) {
                1 -> return level[0]
                0 -> {} // nächste Stufe
                else -> return null // mehrdeutig → Fallback, nicht raten
            }
        }
        return null
    }
}

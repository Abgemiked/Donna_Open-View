package com.yourcompany.donna.overlay

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * #147 Paket A — Rückmeldung einer Geräte-Aktion im Overlay: kurzer Text vom Gerät und, wenn es etwas zurückzunehmen
 * gibt, die Taste „Rückgängig“. Die Taste ist sofort bedienbar (kein Fehltipp-Schutz wie bei Bestätigungskarten:
 * hier wird nichts ausgelöst, sondern zurückgenommen). Gemeinsam für das Blasen-Panel und den Altweg
 * (VoiceInputActivity); Farben (Auflöser) und dp kommen von außen. Optik: gemeinsamer Kartenstil ([CardKit], #151).
 */
class DeviceResultCardBuilder(
    private val ctx: Context,
    private val dp: (Int) -> Int,
    private val color: (Int) -> Int,
) {
    private val kit = CardKit(ctx, dp, color)

    /** [onUndo] nimmt die Aktion zurück und liefert die Meldung des Geräts; danach verschwindet die Taste. */
    fun build(message: String, undoable: Boolean, undoLabel: String = "Rückgängig", onUndo: () -> String): View {
        val card = kit.card()
        val text = kit.text(message, CardStyle.Type.TITLE).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        card.addView(text)
        if (undoable) {
            val undo = kit.button(undoLabel, primary = true).apply {
                contentDescription = if (undoLabel == "Rückgängig") "Rückgängig: Aktion zurücknehmen" else undoLabel
                layoutParams = kit.stackParams(bottomDp = 0).also { it.topMargin = dp(CardStyle.TEXT_TO_BUTTONS_DP) }
            }
            undo.setOnClickListener {
                undo.isEnabled = false // einmalig
                text.text = onUndo()
                (undo.parent as? ViewGroup)?.removeView(undo)
            }
            card.addView(undo)
        }
        return card
    }

    companion object {
        /** Timer/Wecker: die Samsung Uhr löscht nicht automatisch, die Taste öffnet nur die Uhr-App (#210). */
        const val CLOCK_LABEL = "Uhr-App öffnen"
    }
}

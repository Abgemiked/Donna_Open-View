package com.yourcompany.donna.overlay

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.yourcompany.donna.JsonSafe
import com.yourcompany.donna.NavActionLogic
import com.yourcompany.donna.R
import com.yourcompany.donna.UrlPolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reiner Text-Baustein für die Kontakt-Bestätigungskarte (B2 BLOCKER 4, JVM-testbar).
 *
 * Spiegelt `utils/actionSummary.contactActionFields` im Schriftchat: zeigt Empfänger + NUMMER
 * (immer, wenn vorhanden) + (bei Nachricht) den vollständigen Text. Fehlt der Name, stammt die
 * Nummer nur aus Donnas Vorschlag (Payload) → wird als solche markiert. Mehrzeiliges Label.
 */
object ActionCardText {
    fun contact(header: String, name: String, number: String, message: String): String {
        val lines = mutableListOf(header)
        if (name.isNotBlank()) lines += "An: $name"
        // BLOCKER 1 (2. Runde): Der Overlay-Pfad löst Nummern NIE aus dem Adressbuch auf
        // (ActionExecutor nutzt immer action.number) → JEDE Overlay-Nummer ist payload-stämmig und
        // wird markiert, damit eine halluzinierte Nummer nicht blind bestätigt wird.
        if (number.isNotBlank()) lines += "Nummer (aus Anfrage, nicht aus Kontakten): $number"
        if (message.isNotBlank()) lines += "Nachricht: $message"
        return lines.joinToString("\n")
    }
}

/**
 * Baut die Aktions-Karten (Ja/Nein, Tesla, Geocoding-Rückfragen) des Seitentasten-Overlays.
 *
 * B1 Phase-C Overlay-Refactor: 1:1 aus `VoiceInputActivity.buildActionCard` +
 * `buildNavDisambiguateCard` + `buildNavNotFoundCard` (+ Helfer newNavCard/navCardTitle/
 * buildCardButton) extrahiert — VERHALTENSNEUTRAL. Die View-Primitive (Context, dp, Farben,
 * TalkBack-Rolle, ARM_TAG) und die Aktions-Callbacks (Aktion ausführen, Tesla, Handy-Navigation)
 * kommen über [Host] aus der Activity. Keine Logikänderung; Optik: gemeinsamer Kartenstil ([CardStyle]/[CardKit], #151).
 */
class ActionCardBuilder(private val host: Host) {
    private val kit by lazy { CardKit(host.ctx, host::dp, host::color) }


    interface Host {
        val ctx: Context
        fun dp(n: Int): Int
        fun setRole(v: View, className: String, checked: (() -> Boolean)? = null)
        /** Scharf-Schalt-Tag der Bestätigen-Tasten (Fehltipp-Schutz, gemeinsame Konstante mit der Activity). */
        val armTag: String
        /** Farbressource im Darstellungs-Kontext des Hosts (Hell/Dunkel nach ThemeChoice). */
        fun color(id: Int): Int
        /** Bestätigte Aktion ausführen (→ ActionExecutor). */
        fun executeAction(action: JSONObject)
        /** Ziel an die Tesla-App senden. */
        fun sendToTesla(target: String)
        /** Handy-Navigation zu einem gewählten Treffer. */
        fun startMapsNavigation(dest: String, lat: Double?, lon: Double?)
        /** Sendeziel einer (tesla_)navigate-Action. */
        fun teslaTargetOf(action: JSONObject): String
    }

    /** JSON-null-sicher (wie die frühere private Extension in VoiceInputActivity). */
    private fun JSONObject.safeString(key: String, default: String = ""): String = JsonSafe.str(this, key, default)

    // ── Aktions-Karte ────────────────────────────────────────────────────────
    fun buildActionCard(action: JSONObject): View? {
        val type = action.safeString("type")
        // Geocoding-Rueckfrage / -Fehler des Backends: eigene Karten (kein Ja/Nein)
        when (type) {
            "navigate_disambiguate" -> return buildNavDisambiguateCard(action)
            "navigate_not_found"    -> return buildNavNotFoundCard(action)
        }
        // tesla_navigate ohne Ziel: nichts zu senden -> keine Karte
        if (type == "tesla_navigate" && host.teslaTargetOf(action).isEmpty()) return null
        val label = when (type) {
            "create_event" -> "Termin eintragen: ${action.safeString("title")}"
            "set_alarm"    -> "Wecker stellen: ${action.safeString("time")} ${action.safeString("label")}".trim()
            "set_timer"    -> "Timer starten: ${action.optInt("minutes", 0)} Min ${action.safeString("label")}".trim()
            "navigate"     -> "Navigation zu: ${action.safeString("destination")}"
            "tesla_navigate" -> "Ziel an Tesla senden: ${host.teslaTargetOf(action)}"
            // BLOCKER 4 (informierte Bestätigung): Empfänger + NUMMER + (bei Nachricht) TEXT zeigen,
            // Payload-Nummer markieren. Auto-Send macht diese Karte zur einzigen Kontrolle vor dem Senden.
            "call"         -> ActionCardText.contact("Anrufen", action.safeString("name"), action.safeString("number"), "")
            "sms"          -> ActionCardText.contact("SMS", action.safeString("name"), action.safeString("number"), action.safeString("message"))
            "whatsapp"     -> ActionCardText.contact("WhatsApp", action.safeString("name"), action.safeString("number"), action.safeString("message"))
            "play_music"   -> "Musik abspielen: ${action.safeString("query")}"
            "note"         -> "Notiz: ${action.safeString("title")}"
            "open_url"     -> {
                val c = UrlPolicy.check(action.safeString("url")) ?: return null   // fremde Schemata: kein Angebot
                "Link öffnen: ${action.safeString("title").ifBlank { c.displayTarget() }}\nZiel: ${c.displayTarget()}"
            }
            else           -> return null
        }

        val card = kit.card()

        // Titel (bricht um, nie abgeschnitten); Kontakt-Aktionen tragen das Icon des Satzes (Farbe per Tint, Drawable ist weiss)
        card.addView(kit.text(label, CardStyle.Type.TITLE).apply {
            val iconRes = when (type) {
                "call" -> R.drawable.ic_donna_phone
                "sms", "whatsapp" -> R.drawable.ic_donna_message
                else -> 0
            }
            if (iconRes != 0) {
                androidx.core.content.ContextCompat.getDrawable(host.ctx, iconRes)?.mutate()?.let { d ->
                    d.setTint(kit.colorOf(CardStyle.Role.TEXT))
                    d.setBounds(0, 0, host.dp(20), host.dp(20))
                    setCompoundDrawablesRelative(d, null, null, null)
                    compoundDrawablePadding = host.dp(CardStyle.BUTTON_GAP_DP)
                }
            }
        })

        // Tasten: links "Ja" (die eine Hauptaktion, gefuellt), rechts "Nein" (Umriss)
        val btnRow = kit.buttonRow()

        val yes = kit.button(if (type == "tesla_navigate") "An Tesla senden" else "Ja", primary = true).apply {
            contentDescription = "Ja: " + label
            layoutParams = kit.rowParams(last = false)
            // Fehltipp-Schutz: erst nach Streaming-Ende scharf (armActionButtons)
            tag = host.armTag
            isEnabled = false
            alpha = 0.5f
            setOnClickListener {
                host.executeAction(action)
                (card.parent as? ViewGroup)?.removeView(card)
            }
        }
        btnRow.addView(yes)
        host.setRole(yes, "android.widget.Button")

        val no = kit.button("Nein", primary = false).apply {
            contentDescription = "Nein, nicht ausführen"
            layoutParams = kit.rowParams(last = true)
            setOnClickListener {
                (card.parent as? ViewGroup)?.removeView(card)
            }
        }
        btnRow.addView(no)
        host.setRole(no, "android.widget.Button")

        card.addView(btnRow)
        return card
    }

    // ── Geocoding-Rueckfragen (Karten-Helfer) ────────────────────────────────

    private fun buildCardButton(label: String, primary: Boolean, onClick: () -> Unit): TextView =
        kit.button(label, primary).apply {
            layoutParams = kit.stackParams()
            // Auswahl (z. B. Ziel an Tesla senden) erst nach Streaming-Ende scharf
            tag = host.armTag
            isEnabled = false
            alpha = 0.5f
            setOnClickListener { onClick() }
        }

    private fun newNavCard(): LinearLayout = kit.card()

    private fun navCardTitle(text: String): TextView =
        kit.text(text, CardStyle.Type.TITLE, bottomDp = CardStyle.TEXT_TO_BUTTONS_DP)

    /** navigate_disambiguate: mehrere Geocoding-Treffer — ein Tipp waehlt das Ziel (Tesla oder Handy). */
    private fun buildNavDisambiguateCard(action: JSONObject): View {
        val tesla = NavActionLogic.isTesla(action.safeString("type"), action.safeString("nav_type"))
        val query = action.safeString("query")
        val card = newNavCard()
        card.addView(navCardTitle(
            if (tesla) "Mehrere Treffer für „$query“ — Ziel für den Tesla wählen:"
            else "Mehrere Treffer für „$query“ — Ziel wählen:"
        ))
        val options = action.optJSONArray("options") ?: JSONArray()
        for (i in 0 until options.length()) {
            val o = options.optJSONObject(i) ?: continue
            val label = o.safeString("label").trim()
            if (label.isEmpty()) continue
            // NaN/Infinity (fehlend oder kaputt) -> null, sonst entstuende "google.navigation:q=NaN,NaN"
            val lat = o.optDouble("lat", Double.NaN).takeIf { it.isFinite() }
            val lon = o.optDouble("lon", Double.NaN).takeIf { it.isFinite() }
            // Gesendet wird die VOLLE Adresse; das kurze Label ist nur Anzeige
            val target = NavActionLogic.optionTarget(o.safeString("address"), label)
            card.addView(buildCardButton(label, primary = false) {
                if (tesla) host.sendToTesla(target) else host.startMapsNavigation(target, lat, lon)
                (card.parent as? ViewGroup)?.removeView(card)
            })
        }
        card.addView(buildCardButton("Abbrechen", primary = false) {
            (card.parent as? ViewGroup)?.removeView(card)
        })
        return card
    }

    /** navigate_not_found: kein Treffer — freundliche Meldung statt stillem Verschwinden. */
    private fun buildNavNotFoundCard(action: JSONObject): View {
        val tesla = NavActionLogic.isTesla(action.safeString("type"), action.safeString("nav_type"))
        val card = newNavCard()
        card.addView(navCardTitle(NavActionLogic.notFoundMessage(action.safeString("query"), tesla)))
        card.addView(buildCardButton("OK", primary = true) {
            (card.parent as? ViewGroup)?.removeView(card)
        })
        return card
    }
}

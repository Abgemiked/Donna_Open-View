package com.yourcompany.donna.overlay

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.yourcompany.donna.JsonSafe
import com.yourcompany.donna.NavActionLogic
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
 * kommen über [Host] aus der Activity. Keine Logikänderung, keine neue Optik.
 */
class ActionCardBuilder(private val host: Host) {

    interface Host {
        val ctx: Context
        fun dp(n: Int): Int
        fun setRole(v: View, className: String, checked: (() -> Boolean)? = null)
        /** Scharf-Schalt-Tag der Bestätigen-Tasten (Fehltipp-Schutz, gemeinsame Konstante mit der Activity). */
        val armTag: String
        val colorSurface: Int
        val colorBorder: Int
        val colorText: Int
        val colorOnAccent: Int
        val colorAccent: Int
        val colorMuted: Int
        val colorCard: Int
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
            "create_event" -> "📅 Termin eintragen: ${action.safeString("title")}"
            "set_alarm"    -> "⏰ Wecker stellen: ${action.safeString("time")} ${action.safeString("label")}".trim()
            "set_timer"    -> "⏱ Timer starten: ${action.optInt("minutes", 0)} Min ${action.safeString("label")}".trim()
            "navigate"     -> "🗺 Navigation zu: ${action.safeString("destination")}"
            "tesla_navigate" -> "🚗 Ziel an Tesla senden: ${host.teslaTargetOf(action)}"
            // BLOCKER 4 (informierte Bestätigung): Empfänger + NUMMER + (bei Nachricht) TEXT zeigen,
            // Payload-Nummer markieren. Auto-Send macht diese Karte zur einzigen Kontrolle vor dem Senden.
            "call"         -> ActionCardText.contact("📞 Anrufen", action.safeString("name"), action.safeString("number"), "")
            "sms"          -> ActionCardText.contact("💬 SMS", action.safeString("name"), action.safeString("number"), action.safeString("message"))
            "whatsapp"     -> ActionCardText.contact("💬 WhatsApp", action.safeString("name"), action.safeString("number"), action.safeString("message"))
            "play_music"   -> "🎵 Musik abspielen: ${action.safeString("query")}"
            "note"         -> "📝 Notiz: ${action.safeString("title")}"
            "open_url"     -> {
                val c = UrlPolicy.check(action.safeString("url")) ?: return null   // fremde Schemata: kein Angebot
                "🌐 Link öffnen: ${action.safeString("title").ifBlank { c.displayTarget() }}\nZiel: ${c.displayTarget()}"
            }
            else           -> return null
        }

        val card = LinearLayout(host.ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(host.colorSurface)
                cornerRadius = host.dp(10).toFloat()
                setStroke(1, host.colorBorder)
            }
            setPadding(host.dp(14), host.dp(12), host.dp(14), host.dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = host.dp(8) }
        }

        // Titel
        card.addView(TextView(host.ctx).apply {
            text = label
            setTextColor(host.colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = host.dp(10) }
        })

        // Buttons-Reihe
        val btnRow = LinearLayout(host.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // "Ja"-Button
        btnRow.addView(TextView(host.ctx).apply {
            text = if (type == "tesla_navigate") "An Tesla senden" else "Ja"
            setTextColor(host.colorOnAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(host.colorAccent)
                cornerRadius = host.dp(8).toFloat()
            }
            setPadding(host.dp(16), host.dp(8), host.dp(16), host.dp(8))
            minHeight = host.dp(48)
            minWidth = host.dp(64)
            contentDescription = "Ja: " + label
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = host.dp(10) }
            isClickable = true
            isFocusable = true
            // Fehltipp-Schutz: erst nach Streaming-Ende scharf (armActionButtons)
            tag = host.armTag
            isEnabled = false
            alpha = 0.5f
            setOnClickListener {
                host.executeAction(action)
                (card.parent as? ViewGroup)?.removeView(card)
            }
        })
        host.setRole(btnRow.getChildAt(btnRow.childCount - 1), "android.widget.Button")

        // "Nein"-Button
        btnRow.addView(TextView(host.ctx).apply {
            text = "Nein"
            setTextColor(host.colorMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(host.colorSurface)
                cornerRadius = host.dp(8).toFloat()
                setStroke(1, host.colorBorder)
            }
            setPadding(host.dp(16), host.dp(8), host.dp(16), host.dp(8))
            minHeight = host.dp(48)
            minWidth = host.dp(64)
            contentDescription = "Nein, nicht ausführen"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                (card.parent as? ViewGroup)?.removeView(card)
            }
        })
        host.setRole(btnRow.getChildAt(btnRow.childCount - 1), "android.widget.Button")

        card.addView(btnRow)
        return card
    }

    // ── Geocoding-Rueckfragen (Karten-Helfer) ────────────────────────────────

    private fun buildCardButton(label: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(host.ctx).apply {
            text = label
            setTextColor(if (primary) host.colorOnAccent else host.colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = host.dp(48)
            background = GradientDrawable().apply {
                setColor(if (primary) host.colorAccent else host.colorCard)
                cornerRadius = host.dp(8).toFloat()
                if (!primary) setStroke(1, host.colorBorder)
            }
            setPadding(host.dp(14), host.dp(10), host.dp(14), host.dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = host.dp(6) }
            isClickable = true
            isFocusable = true
            // Auswahl (z. B. Ziel an Tesla senden) erst nach Streaming-Ende scharf
            tag = host.armTag
            isEnabled = false
            alpha = 0.5f
            setOnClickListener { onClick() }
        }

    private fun newNavCard(): LinearLayout = LinearLayout(host.ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(host.colorSurface)
            cornerRadius = host.dp(10).toFloat()
            setStroke(1, host.colorBorder)
        }
        setPadding(host.dp(14), host.dp(12), host.dp(14), host.dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = host.dp(8) }
    }

    private fun navCardTitle(text: String): TextView = TextView(host.ctx).apply {
        this.text = text
        setTextColor(host.colorText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = host.dp(10) }
    }

    /** navigate_disambiguate: mehrere Geocoding-Treffer — ein Tipp waehlt das Ziel (Tesla oder Handy). */
    private fun buildNavDisambiguateCard(action: JSONObject): View {
        val tesla = NavActionLogic.isTesla(action.safeString("type"), action.safeString("nav_type"))
        val query = action.safeString("query")
        val card = newNavCard()
        card.addView(navCardTitle(
            if (tesla) "🚗 Mehrere Treffer für „$query“ — Ziel für den Tesla wählen:"
            else "🗺 Mehrere Treffer für „$query“ — Ziel wählen:"
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

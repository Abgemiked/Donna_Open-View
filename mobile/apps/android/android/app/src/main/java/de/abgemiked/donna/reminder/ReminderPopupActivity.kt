package com.yourcompany.donna.reminder

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.yourcompany.donna.DonnaFonts
import com.yourcompany.donna.R
import com.yourcompany.donna.ThemeChoice
import com.yourcompany.donna.TtsEngines
import com.yourcompany.donna.overlay.CardKit
import com.yourcompany.donna.overlay.CardStyle

/**
 * #153: Popup einer Erinnerung ueber dem Sperrbildschirm und anderen Apps (Vollbild-Absicht der Benachrichtigung).
 * Dunkle bzw. helle Karte unten mittig wie das Overlay-Panel, Text, "Erledigt" und "Spaeter" (Stufen 10 Min / 30 Min / 1 Std).
 * Entsperrt das Geraet nicht. Zurueck oder Tippen daneben schliesst nur das Popup; die Benachrichtigung bleibt stehen.
 */
class ReminderPopupActivity : AppCompatActivity() {

    private var snooze = ReminderLogic.SNOOZE_DEFAULT
    private var busy = false
    private var statusView: TextView? = null

    companion object {
        // Zustand je Erinnerung ueber Neuaufbau (Drehung) und onNewIntent derselben Kennung hinweg
        @Volatile private var stateId: String? = null
        @Volatile private var stateSnooze = ReminderLogic.SNOOZE_DEFAULT
        @Volatile private var stateBusy = false
        @Volatile private var live: ReminderPopupActivity? = null
    }

    override fun onStart() { super.onStart(); live = this }
    override fun onDestroy() { if (live === this) live = null; super.onDestroy() }

    private fun dp(n: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), resources.displayMetrics).toInt()

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = when (ThemeChoice.nightOverride(ThemeChoice.read(getSharedPreferences(TtsEngines.PREFS, MODE_PRIVATE)))) {
            true -> AppCompatDelegate.MODE_NIGHT_YES
            false -> AppCompatDelegate.MODE_NIGHT_NO
            null -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        super.onCreate(savedInstanceState)
        // Ab Android 15 zeichnet jede App randlos: Abstaende zu Navigations-/Gestenleiste kommen aus den Insets
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent)
    }

    private fun render(intent: Intent) {
        val id = intent.getStringExtra(ReminderNotifier.EXTRA_ID)
        val text = intent.getStringExtra(ReminderNotifier.EXTRA_TEXT).orEmpty()
        val notifId = intent.getIntExtra(ReminderNotifier.EXTRA_NOTIF_ID, ReminderLogic.notificationId(id))
        if (id != stateId) { // andere Erinnerung: frischer Zustand; dieselbe: Stufe und Sendestatus bleiben
            stateId = id; stateSnooze = ReminderLogic.SNOOZE_DEFAULT; stateBusy = false
        }
        snooze = stateSnooze
        busy = stateBusy

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(0x47, 0, 0, 0))
            setOnClickListener { finish() }
        }
        val card = kit.card().apply {
            // Auf dem Scrim (nicht im Panel) steht die Karte auf der Kartenfarbe der Palette
            background = GradientDrawable().apply {
                setColor(color(R.color.donna_card))
                cornerRadius = resources.getDimension(R.dimen.donna_radius_card)
                setStroke(CardStyle.DIVIDER_PX, color(R.color.donna_border))
            }
            isClickable = true // Tippen auf die Karte schliesst nicht
        }
        val title = TextView(this).apply {
            this.text = "Erinnerung"
            setTextColor(color(R.color.donna_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = DonnaFonts.typeface(context, DonnaFonts.Face.SANS_SEMIBOLD)
        }
        val body = TextView(this).apply {
            this.text = text
            setTextColor(color(R.color.donna_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = DonnaFonts.typeface(context, DonnaFonts.Face.DISPLAY_BOLD)
            setPadding(0, dp(8), 0, dp(16))
        }
        // Langer Text scrollt; die Tasten bleiben immer im Bild (Hoehe hoechstens 40 % des Bildschirms)
        val maxTextHeight = (resources.displayMetrics.heightPixels * 0.4f).toInt()
        val scroll = object : android.widget.ScrollView(this) {
            override fun onMeasure(w: Int, h: Int) =
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxTextHeight, View.MeasureSpec.AT_MOST))
        }.apply { addView(body) }
        val status = TextView(this).apply {
            setTextColor(color(R.color.donna_danger))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = DonnaFonts.typeface(context, DonnaFonts.Face.SANS_REGULAR)
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        statusView = status
        card.addView(title)
        card.addView(scroll)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        val done = pill(if (id != null) "Erledigt" else "OK", primary = true)
        val later = pill("Später", primary = false)

        fun send(kind: ReplyKind) {
            if (id == null) { finish(); return }
            if (busy) return
            busy = true; stateBusy = true
            status.visibility = View.GONE
            // Fehler meldet nur das Popup (eine Meldung), daher kein Toast
            ReminderReplies.submit(this, Reply(kind, id, if (kind == ReplyKind.SNOOZE) snooze else null), notifId, toastOnFailure = false) { outcome ->
                stateBusy = false
                // auch nach Drehung: die gerade sichtbare Instanz meldet das Ergebnis
                live?.runOnUiThread { live?.onReplyResult(outcome) }
            }
        }

        if (id != null) {
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(8)) }
            val chipViews = ReminderLogic.SNOOZE_STEPS.map { step -> step to chip(ReminderLogic.snoozeLabel(step)) }
            fun paintChips() = chipViews.forEach { (step, v) -> paintChip(v, step == snooze) }
            chipViews.forEach { (step, v) ->
                v.contentDescription = "Später in ${if (step % 60 == 0) "${step / 60} Stunde" else "$step Minuten"}"
                v.setOnClickListener { snooze = step; stateSnooze = step; paintChips() }
                chips.addView(v)
            }
            paintChips()
            card.addView(chips)
            later.setOnClickListener { send(ReplyKind.SNOOZE) }
            buttons.addView(later)
        }
        done.setOnClickListener { send(ReplyKind.DONE) }
        buttons.addView(done, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        card.addView(buttons)
        card.addView(status)

        val cardParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { setMargins(dp(12), dp(24), dp(12), dp(24)) }
        root.addView(card, cardParams)
        // Karte steht ueber Navigations-/Gestenleiste und ueber der Tastatur; die Tasten bleiben voll sichtbar und >= 48 dp
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            cardParams.bottomMargin = ReminderLogic.popupBottomMargin(bars.bottom, ime.bottom, dp(24))
            cardParams.topMargin = dp(24) + bars.top
            card.layoutParams = cardParams
            insets
        }
        setContentView(root)
    }

    private fun onReplyResult(outcome: Outcome) {
        busy = false
        if (outcome == Outcome.SENT || outcome == Outcome.GONE) {
            finish()
        } else {
            statusView?.apply {
                text = "Konnte nicht an Donna senden. Die Erinnerung bleibt stehen, ich versuche es später erneut."
                visibility = View.VISIBLE
            }
        }
    }

    private fun asRole(v: View, className: String, checked: Boolean? = null) {
        androidx.core.view.ViewCompat.setAccessibilityDelegate(v, object : androidx.core.view.AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = className
                if (checked != null) { info.isCheckable = true; info.isChecked = checked }
            }
        })
    }

    /** Gemeinsamer Kartenstil (#151): Hauptaktion gefüllt in Mint, zweite Aktion als Umriss, 48 dp, Text bricht um. */
    private val kit by lazy { CardKit(this, { dp(it) }, { color(it) }) }

    private fun pill(label: String, primary: Boolean): TextView = kit.button(label, primary).apply {
        contentDescription = label
        asRole(this, android.widget.Button::class.java.name)
    }

    private fun chip(label: String): TextView = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = DonnaFonts.typeface(context, DonnaFonts.Face.SANS_SEMIBOLD)
        gravity = Gravity.CENTER
        minHeight = dp(48)
        minWidth = dp(64)
        setPadding(dp(12), 0, dp(12), 0)
        isClickable = true
        isFocusable = true
    }

    private fun paintChip(v: TextView, selected: Boolean) {
        v.isSelected = selected
        asRole(v, android.widget.RadioButton::class.java.name, selected)
        androidx.core.view.ViewCompat.setStateDescription(v, if (selected) "ausgewählt" else "nicht ausgewählt")
        v.setTextColor(color(if (selected) R.color.donna_on_accent_container else R.color.donna_muted))
        v.background = android.graphics.drawable.InsetDrawable(
            GradientDrawable().apply {
                cornerRadius = resources.getDimension(R.dimen.donna_radius_pill)
                setColor(if (selected) color(R.color.donna_accent_container) else Color.TRANSPARENT)
            },
            0, dp(7), 0, dp(7),
        )
    }
}

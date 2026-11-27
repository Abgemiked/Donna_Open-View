package com.yourcompany.donna.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.yourcompany.donna.DonnaFonts
import com.yourcompany.donna.R

/**
 * Baut die Bausteine aller Overlay-Karten nach [CardStyle] (#151): Kartenflaeche, Texte, Tasten (Haupt gefuellt in Mint,
 * Neben als Umriss), Haarlinie, Symbol. Einzige Stelle, die Rollen auf Farb- und Mass-Ressourcen abbildet.
 *
 * [color] loest eine Farbressource im Darstellungs-Kontext des Aufrufers auf (Hell/Dunkel nach ThemeChoice);
 * [dp] rechnet dp in Pixel.
 */
class CardKit(
    private val ctx: Context,
    private val dp: (Int) -> Int,
    private val color: (Int) -> Int,
) {
    fun colorOf(role: CardStyle.Role): Int = color(
        when (role) {
            CardStyle.Role.FILL -> R.color.donna_overlay_chip
            CardStyle.Role.BORDER -> R.color.donna_border
            CardStyle.Role.TEXT -> R.color.donna_text
            CardStyle.Role.MUTED -> R.color.donna_muted
            CardStyle.Role.ICON -> R.color.donna_accent
            CardStyle.Role.PRIMARY_FILL -> R.color.donna_action
            CardStyle.Role.PRIMARY_TEXT -> R.color.donna_on_action
            CardStyle.Role.SECONDARY_BORDER -> R.color.donna_control_border
            CardStyle.Role.SECONDARY_TEXT -> R.color.donna_text
        }
    )

    private fun radiusPx(r: CardStyle.Radius): Float = ctx.resources.getDimension(
        when (r) {
            CardStyle.Radius.SM -> R.dimen.donna_radius_sm
            CardStyle.Radius.MD -> R.dimen.donna_radius_md
            CardStyle.Radius.LG -> R.dimen.donna_radius_lg
            CardStyle.Radius.PILL -> R.dimen.donna_radius_pill
            CardStyle.Radius.CARD -> R.dimen.donna_radius_card
        }
    )

    private fun face(name: String): DonnaFonts.Face =
        DonnaFonts.Face.values().firstOrNull { it.rnName == name } ?: DonnaFonts.Face.SANS_REGULAR

    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    /** Kartenflaeche: Rundung/Rand/Abstaende des Stils; [horizontal] fuer Zeilenkarten (Symbol + Text). */
    fun card(horizontal: Boolean = false): LinearLayout = LinearLayout(ctx).apply {
        orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (horizontal) gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            setColor(colorOf(CardStyle.Role.FILL))
            cornerRadius = radiusPx(CardStyle.CARD_RADIUS)
            setStroke(CardStyle.DIVIDER_PX, colorOf(CardStyle.Role.BORDER))
        }
        setPadding(dp(CardStyle.PAD_H_DP), dp(CardStyle.PAD_TOP_DP), dp(CardStyle.PAD_H_DP), dp(CardStyle.PAD_BOTTOM_DP))
        layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .also { it.bottomMargin = dp(CardStyle.CARD_GAP_DP) }
    }

    /** Text im Stil [type]; bricht um (keine Zeilenbegrenzung), Abstand nach unten [bottomDp]. */
    fun text(value: CharSequence, type: CardStyle.Type, role: CardStyle.Role = CardStyle.Role.TEXT, bottomDp: Int = 0): TextView =
        TextView(ctx).apply {
            text = value
            setTextColor(colorOf(role))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, type.sp.toFloat())
            typeface = DonnaFonts.typeface(ctx, face(type.face))
            layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = dp(bottomDp) }
        }

    /** Tippbare Taste: [primary] = die eine gefuellte Hauptaktion, sonst Umriss. Mindestens 48 dp hoch, Text bricht um. */
    fun button(label: String, primary: Boolean): TextView {
        val role = CardStyle.buttonRole(primary)
        return TextView(ctx).apply {
            text = label
            setTextColor(colorOf(role.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, CardStyle.Type.BUTTON.sp.toFloat())
            typeface = DonnaFonts.typeface(ctx, face(CardStyle.Type.BUTTON.face))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = radiusPx(CardStyle.BUTTON_RADIUS)
                role.fill?.let { setColor(colorOf(it)) }
                role.border?.let { setStroke(CardStyle.DIVIDER_PX, colorOf(it)) }
            }
            setPadding(dp(CardStyle.BUTTON_PAD_H_DP), dp(CardStyle.BUTTON_PAD_V_DP), dp(CardStyle.BUTTON_PAD_H_DP), dp(CardStyle.BUTTON_PAD_V_DP))
            minHeight = dp(CardStyle.buttonMinHeightDp())
            minWidth = dp(CardStyle.BUTTON_MIN_WIDTH_DP)
            isClickable = true
            isFocusable = true
        }
    }

    /** Layout einer Taste in einer waagerechten Reihe: gleiche Breite, Abstand [CardStyle.BUTTON_GAP_DP] zur naechsten. */
    fun rowParams(last: Boolean): LinearLayout.LayoutParams =
        lp(0, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.weight = CardStyle.BUTTON_ROW_WEIGHT
            if (!last) it.marginEnd = dp(CardStyle.BUTTON_GAP_DP)
        }

    /** Layout einer Taste unter anderen (volle Breite). */
    fun stackParams(bottomDp: Int = CardStyle.BUTTON_GAP_DP): LinearLayout.LayoutParams =
        lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(bottomDp) }

    /** Waagerechte Tastenreihe, [topDp] Abstand zum Text darueber. */
    fun buttonRow(topDp: Int = CardStyle.TEXT_TO_BUTTONS_DP): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(topDp) }
    }

    /** Haarlinie zwischen zwei Bereichen. */
    fun divider(vertical: Int = CardStyle.BUTTON_GAP_DP): View = View(ctx).apply {
        setBackgroundColor(colorOf(CardStyle.Role.BORDER))
        layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, CardStyle.DIVIDER_PX).also {
            it.topMargin = dp(vertical); it.bottomMargin = dp(vertical)
        }
    }

    /** Symbol aus dem gemeinsamen Satz (Vektor, weiss) in einer Rolle eingefaerbt. */
    fun icon(resId: Int, sizeDp: Int, role: CardStyle.Role = CardStyle.Role.ICON): ImageView = ImageView(ctx).apply {
        setImageResource(resId)
        imageTintList = ColorStateList.valueOf(colorOf(role))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }
}

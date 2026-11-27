package com.yourcompany.donna

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

/**
 * Schriften der Donna-App (#134 P1), Kotlin-Gegenstueck zu `FONT_FAMILY` in src/theme/tokens.ts.
 * Statische Schnitte unter res/font (Dateinamen = [Face.rnName] = Familienname der RN-Seite); fonts.test.ts
 * vergleicht Namen, Dateien und diese Tabelle. In P1 nur bereitgestellt; eingesetzt wird sie ab P3/P7.
 */
object DonnaFonts {
    enum class Face(val resId: Int, val rnName: String) {
        DISPLAY_BOLD(R.font.bricolage_grotesque_bold, "bricolage_grotesque_bold"),
        DISPLAY_EXTRABOLD(R.font.bricolage_grotesque_extrabold, "bricolage_grotesque_extrabold"),
        SANS_REGULAR(R.font.nunito_sans_regular, "nunito_sans_regular"),
        SANS_SEMIBOLD(R.font.nunito_sans_semibold, "nunito_sans_semibold"),
        SANS_BOLD(R.font.nunito_sans_bold, "nunito_sans_bold"),
    }

    /** Laedt den Schnitt; faellt bei jedem Fehler auf die Systemschrift zurueck (nie null, nie ein Absturz). */
    fun typeface(context: Context, face: Face): Typeface =
        try {
            ResourcesCompat.getFont(context, face.resId) ?: Typeface.DEFAULT
        } catch (_: Exception) {
            Typeface.DEFAULT
        }
}

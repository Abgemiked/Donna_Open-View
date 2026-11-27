package com.yourcompany.donna

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat

/** #156: grosses Benachrichtigungs-Symbol (Orb) aus dem Vektor; einmal gezeichnet und gemerkt. */
object NotificationIcons {
    private const val SIZE_PX = 192

    @Volatile private var cached: Bitmap? = null

    /** Null, falls das Zeichnen scheitert — die Benachrichtigung funktioniert dann ohne grosses Symbol. */
    fun largeOrb(context: Context): Bitmap? {
        cached?.let { return it }
        return try {
            val d = ContextCompat.getDrawable(context, R.drawable.ic_orb_large) ?: return null
            val bmp = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, SIZE_PX, SIZE_PX)
            d.draw(Canvas(bmp))
            cached = bmp
            bmp
        } catch (_: Exception) {
            null
        }
    }
}

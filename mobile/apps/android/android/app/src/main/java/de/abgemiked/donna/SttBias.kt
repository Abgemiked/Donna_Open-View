package com.yourcompany.donna

import android.content.Intent
import android.os.Build
import android.speech.RecognizerIntent

/**
 * STT-Biasing: lenkt den Android-Spracherkenner auf die echten Raum-, Geräte- und Routinen-Namen
 * („Streamingzimmer“ wird sonst oft als „Esszimmer“/„Spielzimmer“/„Flur“ verhört → falscher Raum).
 *
 * Eine gemeinsame Quelle für BEIDE Erkenner-Pfade: [VoiceInputActivity] (Overlay) und [OfflineSTTModule]
 * (Haupt-Chat). Biasing lenkt nur, ersetzt nichts — ein echtes „Esszimmer“ bleibt möglich. Ignoriert ein Gerät
 * EXTRA_BIASING_STRINGS (vor API 33 / manche Engines), schadet das nicht.
 */
object SttBias {

    val ROOMS: List<String> = listOf(
        "Streamingzimmer", "Schlafzimmer", "Wohnzimmer", "Kinderzimmer", "Küche", "Flur", "Bad", "Balkon",
    )

    val DEVICES_AND_ROUTINES: List<String> = listOf(
        "Softboxen", "Regallicht", "Wandlampe", "Lüfter Steckdose", "Shishakohle", "Shishakohleanzünder",
        "Shisha Restzeit", "Kohle", "Yannik Projektor", "Ambilight", "Licht Streamen", "Regallicht Halloween",
    )

    /** Alle Begriffe (eindeutig, Reihenfolge stabil). */
    val TERMS: List<String> = (ROOMS + DEVICES_AND_ROUTINES).distinct()

    /** EXTRA_BIASING_STRINGS gibt es ab API 33. */
    fun isSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= 33

    /** Setzt die Biasing-Begriffe auf den Erkenner-Intent (nur API 33+). */
    fun applyTo(intent: Intent) {
        if (isSupported()) {
            intent.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(TERMS))
        }
    }
}

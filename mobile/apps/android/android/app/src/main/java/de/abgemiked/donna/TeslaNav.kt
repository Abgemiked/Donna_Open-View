package com.yourcompany.donna

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/**
 * TeslaNav — schickt ein Navigationsziel LAUTLOS ans Auto.
 *
 * Mechanismus (per ADB verifiziert): expliziter ACTION_SEND-Intent (text/plain, EXTRA_TEXT = Adresse) an
 * `com.teslamotors.tesla / com.tesla.share.ShareActivity`. Die Tesla-App zeigt kurz "an Tesla gesendet".
 *
 * Wird vom React-Native-Modul (TeslaNavModule, Chat) UND vom Seitentasten-Overlay (VoiceInputActivity)
 * genutzt. Die reine Text-/Intent-Logik ist ohne Android-Laufzeit testbar (TeslaNavTest).
 */
object TeslaNav {

    const val TESLA_PACKAGE = "com.teslamotors.tesla"
    const val TESLA_SHARE_ACTIVITY = "com.tesla.share.ShareActivity"

    /** Obergrenze für das Ziel — Adressen sind kurz; verhindert absurd lange Extras. */
    const val MAX_TEXT_LENGTH = 500

    enum class Result { SENT, NOT_INSTALLED, EMPTY, FAILED }

    /**
     * Bereinigt das Ziel: Steuerzeichen/Zeilenumbrüche → Leerzeichen, Mehrfach-Leerzeichen zusammenfassen,
     * trimmen, kürzen. Leer → null (nichts zu senden).
     */
    fun normalizeText(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw
            // Unsichtbare Format-Zeichen (Zero-Width, Bidi-Override wie U+202E) komplett entfernen ...
            .replace(Regex("\\p{Cf}+"), "")
            // ... Steuerzeichen/Zeilenumbrueche werden zu Leerzeichen
            .replace(Regex("[\\p{Cntrl}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_TEXT_LENGTH)
            .trim()
        return cleaned.ifEmpty { null }
    }

    /** Der explizite Share-Intent an die Tesla-App (kein Chooser, kein Implicit-Intent). */
    fun buildIntent(text: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            setClassName(TESLA_PACKAGE, TESLA_SHARE_ACTIVITY)
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Sendet [rawText] an die Tesla-App; wirft nie, sondern liefert ein [Result]. */
    fun send(context: Context, rawText: String?): Result {
        val text = normalizeText(rawText) ?: return Result.EMPTY
        return try {
            context.startActivity(buildIntent(text))
            Result.SENT
        } catch (e: ActivityNotFoundException) {
            // Tesla-App nicht installiert (expliziter Intent findet keine Activity)
            Result.NOT_INSTALLED
        } catch (e: Exception) {
            // z. B. SecurityException, wenn die ShareActivity in einer künftigen Tesla-Version nicht mehr exportiert ist
            Result.FAILED
        }
    }
}

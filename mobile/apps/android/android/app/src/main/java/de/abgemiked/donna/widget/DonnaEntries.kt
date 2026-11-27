package com.yourcompany.donna.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.yourcompany.donna.LiveBubbleLauncherActivity
import com.yourcompany.donna.VoiceInputActivity
import com.yourcompany.donna.idea.IdeaCaptureActivity

/**
 * Welle 1 Widgets (#232/#233/#234): die EINE Quelle fuer die Einstiege von Homescreen-Widgets, Schnelleinstellungs-
 * Kachel und App-Shortcuts. Alle Einstiege oeffnen eine Activity — der Bubble-Dienst wird NIE direkt gestartet
 * (Mikrofon-Vordergrunddienst ab Android 14 nur aus sichtbarer App; das macht [LiveBubbleLauncherActivity]).
 * Die statischen Shortcuts (res/xml/shortcuts.xml) muessen dieselben Extras tragen.
 */
object DonnaEntries {
    /** Extra fuer [VoiceInputActivity]: Startmodus. Gleicher Name in res/xml/shortcuts.xml. */
    const val EXTRA_START_MODE = "start_mode"
    const val START_MODE_TEXT = "text"
    /** #243: Ideen-Erfassung startet direkt das Diktat (Widget-Knopf „Sprechen“). */
    const val START_MODE_VOICE = "voice"

    /** Extra fuer [IdeaCaptureActivity]: woher die Erfassung kam (Server-Vertrag "kanal"). */
    const val EXTRA_KANAL = "kanal"

    // Eigene requestCodes je Einstieg, damit sich die PendingIntents nicht gegenseitig ueberschreiben.
    const val RC_SEARCH_FIELD = 2321
    const val RC_SEARCH_MIC = 2322
    const val RC_IDEA_WIDGET = 2331
    const val RC_IDEA_VOICE = 2332
    const val RC_TILE = 2341

    /** Textmodus: Tastatur offen, Mikrofon aus (reine Logik, testbar). */
    fun isTextStart(value: String?): Boolean = value == START_MODE_TEXT

    /** Sprachstart der Ideen-Erfassung (reine Logik, testbar). */
    fun isVoiceStart(value: String?): Boolean = value == START_MODE_VOICE

    /** Donna-Overlay im Textmodus (Suchleisten-Feld, Shortcut „Frage tippen“). */
    fun textInput(ctx: Context): Intent =
        Intent(ctx, VoiceInputActivity::class.java)
            .putExtra(EXTRA_START_MODE, START_MODE_TEXT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Overlay/Live-Bubble wie die Funktionstaste (Mikro-Symbol, Kachel). */
    fun overlay(ctx: Context): Intent =
        Intent(ctx, LiveBubbleLauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Ideen-Erfassung (Ideen-Widget, Shortcut „Idee erfassen“). */
    fun ideaCapture(ctx: Context, kanal: String, voice: Boolean = false): Intent =
        Intent(ctx, IdeaCaptureActivity::class.java)
            .putExtra(EXTRA_KANAL, kanal)
            .apply { if (voice) putExtra(EXTRA_START_MODE, START_MODE_VOICE) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** #246: Startmodus der Tagesbriefing-Kachel (Donna fragt sofort nach dem Briefing). */
    const val START_MODE_BRIEFING = "briefing"

    fun isBriefingStart(value: String?): Boolean = value == START_MODE_BRIEFING

    /** Donna-Overlay mit der Briefing-Frage (Tagesbriefing-Kachel). */
    fun briefing(ctx: Context): Intent =
        Intent(ctx, VoiceInputActivity::class.java)
            .putExtra(EXTRA_START_MODE, START_MODE_BRIEFING)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun pending(ctx: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            ctx, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

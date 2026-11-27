package com.yourcompany.donna.overlay

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.yourcompany.donna.JsonSafe
import com.yourcompany.donna.PhoneNumbers
import com.yourcompany.donna.UrlPolicy
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Reine URI-Bausteine für die Geräte-Intents (ohne Android-Laufzeit testbar).
 *
 * Die Prozent-Kodierung wird als Funktion injiziert: im Betrieb `android.net.Uri::encode`,
 * im Test ein Sentinel, der die kodierten Stellen sichtbar macht (siehe ActionExecutorTest).
 * So bleibt die Template-Logik (Schema, Query-Parameter, Reihenfolge, Nummern-Normalisierung)
 * festnagelbar, obwohl `Uri.encode` im android.jar nur ein nicht ausführbarer Stub ist.
 */
object ActionIntents {
    /** navigate: google.navigation:q=<Ziel>&mode=d (Google Maps). */
    fun navigateQuery(destination: String, encode: (String) -> String): String =
        "google.navigation:q=${encode(destination)}&mode=d"

    /** call: tel:<Nummer> (ACTION_DIAL). */
    fun telUri(number: String, encode: (String) -> String): String =
        "tel:${encode(number)}"

    /** sms: smsto:<Nummer> (ACTION_SENDTO, Body als Extra "sms_body"). */
    fun smsUri(number: String, encode: (String) -> String): String =
        "smsto:${encode(number)}"

    /** whatsapp: api.whatsapp.com/send?phone=<nur Ziffern>&text=<Text>. */
    fun whatsAppUrl(rawNumber: String, message: String, encode: (String) -> String): String =
        "https://api.whatsapp.com/send?phone=${encode(PhoneNumbers.forWhatsApp(rawNumber))}&text=${encode(message)}"

    /** play_music (Spotify): spotify:search:<Query>. */
    fun spotifySearch(query: String, encode: (String) -> String): String =
        "spotify:search:${encode(query)}"

    /** play_music (YouTube Music): music.youtube.com/search?q=<Query>. */
    fun youtubeMusicSearch(query: String, encode: (String) -> String): String =
        "https://music.youtube.com/search?q=${encode(query)}"
}

/**
 * Baut und startet die Geräte-Intents einer bestätigten [com.yourcompany.donna] DonnaAction.
 *
 * B1 Phase-C Overlay-Refactor: 1:1 aus `VoiceInputActivity.executeAction` extrahiert —
 * VERHALTENSNEUTRAL. Android-/Activity-gebundene Schritte (startActivity, PackageManager,
 * Tesla-Versand, URL-Richtlinie, Fehler-Toast) laufen über [Host] zurück in die Activity;
 * der reine URI-Bau nutzt [ActionIntents] mit `Uri::encode`. Keine Logikänderung.
 */
class ActionExecutor(private val host: Host) {

    interface Host {
        fun startActivity(intent: Intent)
        fun getLaunchIntentForPackage(packageName: String): Intent?
        fun sendToTesla(target: String)
        fun teslaTargetOf(action: JSONObject): String
        fun openCheckedUrl(checked: UrlPolicy.Checked?)
        /** Freundliche Fehlermeldung statt Absturz (vormals `toast(startFailureMessage(e))`). */
        fun onActionFailed(e: Exception)
    }

    /** Prozent-Encoder im Betrieb: identisch zum alten inline `android.net.Uri.encode(...)`. */
    private val enc: (String) -> String = { Uri.encode(it) }

    /** JSON-null-sicher (wie die frühere private Extension in VoiceInputActivity). */
    private fun JSONObject.safeString(key: String, default: String = ""): String = JsonSafe.str(this, key, default)

    fun execute(action: JSONObject) {
        try {
            when (action.safeString("type")) {
                "create_event" -> {
                    val intent = Intent(Intent.ACTION_INSERT).apply {
                        data = CalendarContract.Events.CONTENT_URI
                        putExtra(CalendarContract.Events.TITLE, action.safeString("title"))
                        val startStr = action.safeString("start")
                        val endStr = action.safeString("end")
                        if (startStr.isNotBlank()) {
                            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault())
                            runCatching { sdf.parse(startStr)?.time?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) } }
                            runCatching { sdf.parse(endStr)?.time?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) } }
                        }
                        val loc = action.safeString("location")
                        if (loc.isNotBlank()) putExtra(CalendarContract.Events.EVENT_LOCATION, loc)
                    }
                    host.startActivity(intent)
                }
                "set_alarm" -> {
                    val parts = action.safeString("time").split(":")
                    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        if (parts.size == 2) {
                            putExtra(AlarmClock.EXTRA_HOUR, parts[0].toIntOrNull() ?: 7)
                            putExtra(AlarmClock.EXTRA_MINUTES, parts[1].toIntOrNull() ?: 0)
                        }
                        val lbl = action.safeString("label")
                        if (lbl.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, lbl)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    }
                    host.startActivity(intent)
                }
                "set_timer" -> {
                    val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(AlarmClock.EXTRA_LENGTH, (action.optInt("minutes", 5)) * 60)
                        val lbl = action.safeString("label")
                        if (lbl.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, lbl)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    }
                    host.startActivity(intent)
                }
                "navigate" -> {
                    val dest = action.safeString("destination")
                    val uri = Uri.parse(ActionIntents.navigateQuery(dest, enc))
                    host.startActivity(Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") })
                }
                "tesla_navigate" -> host.sendToTesla(host.teslaTargetOf(action))
                "call" -> {
                    val number = action.safeString("number")
                    host.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(ActionIntents.telUri(number, enc))))
                }
                "sms" -> {
                    val number = action.safeString("number")
                    val msg = action.safeString("message")
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(ActionIntents.smsUri(number, enc))).apply {
                        putExtra("sms_body", msg)
                    }
                    host.startActivity(intent)
                }
                "whatsapp" -> {
                    val msg = action.safeString("message")
                    val uri = Uri.parse(ActionIntents.whatsAppUrl(action.safeString("number"), msg, enc))
                    host.startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                "play_music" -> {
                    // DONNA-226: YouTube Music-Intent via Deep-Link statt ACTION_SEARCH
                    // ACTION_SEARCH mit setPackage() öffnet manchmal reguläres YouTube.
                    // youtube-music://search?q= ist die korrekte Intent-URI.
                    val query = action.safeString("query")
                    val service = action.safeString("service", "youtube_music")
                    val intent = if (service == "spotify") {
                        Intent(Intent.ACTION_VIEW, Uri.parse(ActionIntents.spotifySearch(query, enc)))
                    } else {
                        // YouTube Music: Deep-Link-URI (funktioniert zuverlässiger als ACTION_SEARCH)
                        val ytMusicUri = Uri.parse(ActionIntents.youtubeMusicSearch(query, enc))
                        Intent(Intent.ACTION_VIEW, ytMusicUri).apply {
                            setPackage("com.google.android.apps.youtube.music")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
                    // Fallback: YouTube Music nicht installiert → reguläre Intent-Auflösung
                    runCatching {
                        host.startActivity(intent)
                    }.onFailure {
                        // Fallback ohne Package-Constraint
                        val fallback = Intent(Intent.ACTION_SEARCH).apply {
                            putExtra(SearchManager.QUERY, query)
                        }
                        runCatching { host.startActivity(fallback) }
                    }
                }
                "note" -> {
                    val title = action.safeString("title")
                    val content = action.safeString("content")
                    val samsungIntent = host.getLaunchIntentForPackage("com.samsung.android.app.notes")
                    if (samsungIntent != null) {
                        host.startActivity(Intent(Intent.ACTION_SEND).apply {
                            setPackage("com.samsung.android.app.notes")
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, title)
                            putExtra(Intent.EXTRA_TEXT, content)
                        })
                    } else {
                        host.startActivity(Intent(Intent.ACTION_INSERT).apply {
                            data = Uri.parse("content://com.google.android.keep")
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, title)
                            putExtra(Intent.EXTRA_TEXT, content)
                        })
                    }
                }
                "open_url" -> host.openCheckedUrl(UrlPolicy.check(action.safeString("url")))
            }
        } catch (e: Exception) {
            host.onActionFailed(e)
        }
    }
}

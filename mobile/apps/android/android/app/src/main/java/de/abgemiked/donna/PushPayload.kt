package com.yourcompany.donna

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Reine Logik für Push-Nachrichten aus dem ntfy-Stream (JVM-testbar).
 *
 * Voice-Batch / Notification-Fix: Der Nachrichtentext (z. B. das Morgen-Briefing) steckt
 *  1. aufklappbar in der Benachrichtigung (BigTextStyle) und
 *  2. im contentIntent als Extra `donna_proactive_message` — beim Antippen seedet [MainActivity] daraus den Chat
 *     (SharedPreferences des [ProactiveMessageModule]), statt sich auf einen evtl. schon überschriebenen oder
 *     verbrauchten Eintrag zu verlassen. Das Extra ist die Wahrheit über die ANGETIPPTE Nachricht.
 */
object PushPayload {
    /** Obergrenze für Text im Intent-Extra (Binder-Limit ~500 KB je Transaktion — weit darunter bleiben). */
    const val MAX_BODY_CHARS = 20_000

    /**
     * B4 (#4-nativ): ntfy-Tag, der eine Push als „persistent" markiert. Der Backend-Endpunkt POST /notify
     * reicht `tags` unverändert als ntfy-`Tags`-Header durch (z. B. `"tags": ["donna_persistent"]`); der ntfy-
     * JSON-Stream liefert sie als `"tags": [...]` zurück. Kein Backend-Umbau nötig.
     */
    const val TAG_PERSISTENT = "donna_persistent"

    /** Channel für normale Pushes (unverändert, auto-cancelbar). */
    const val CHANNEL_PUSH_ID = "donna_push"

    /** Channel für persistente Erinnerungen (B4) — getrennt, damit Mike ihn separat steuern kann. */
    const val CHANNEL_REMINDER_ID = "donna_reminders"

    class Parsed(val title: String, val body: String, val sessionId: String?, val persistent: Boolean = false)

    /** @return null, wenn die Nachricht keinen Text hat (wird ignoriert). */
    fun parse(json: JSONObject): Parsed? {
        val title = json.optString("title", "Donna").ifBlank { "Donna" }
        val body = json.optString("message", "").ifBlank { json.optString("body", "") }
        if (body.isBlank()) return null
        val sessionId = json.optString("session_id", "").ifBlank { null }
        return Parsed(title, body.take(MAX_BODY_CHARS), sessionId, isPersistent(json))
    }

    /**
     * True, wenn die ntfy-Nachricht das Tag [TAG_PERSISTENT] trägt. Robust: fehlendes/kein Array/Nicht-String-
     * Einträge -> false (dann gilt das normale Push-Verhalten). Vergleich case-insensitiv, getrimmt.
     */
    fun isPersistent(json: JSONObject): Boolean {
        val tags = json.optJSONArray("tags") ?: return false
        for (i in 0 until tags.length()) {
            val t = tags.optString(i, "").trim()
            if (t.equals(TAG_PERSISTENT, ignoreCase = true)) return true
        }
        return false
    }

    /**
     * Entscheidung über Channel/Verhalten einer Notification (reine Funktion, JVM-testbar).
     *  - normal:     Channel `donna_push`,      autoCancel=true,  ongoing=false  (wie bisher)
     *  - persistent: Channel `donna_reminders`, autoCancel=false, ongoing=true   (bleibt bis Mike sie wegwischt)
     */
    class NotificationSpec(val channelId: String, val ongoing: Boolean, val autoCancel: Boolean, val reminderCategory: Boolean)

    fun notificationSpec(persistent: Boolean): NotificationSpec =
        if (persistent) NotificationSpec(CHANNEL_REMINDER_ID, ongoing = true, autoCancel = false, reminderCategory = true)
        else NotificationSpec(CHANNEL_PUSH_ID, ongoing = false, autoCancel = true, reminderCategory = false)

    /**
     * Native Notification im Vordergrund unterdrücken? Normale Pushes: ja (der WebSocket-Hook zeigt sie in der App).
     * Persistente: NIE unterdrücken — die In-App-Anzeige ist flüchtig, die Erinnerung muss in der Leiste bleiben.
     */
    fun suppressInForeground(persistent: Boolean, appInForeground: Boolean): Boolean =
        appInForeground && !persistent

    /**
     * Proaktiv-Seed in SharedPreferences schreiben? Nur im Hintergrund (DONNA-247: im Vordergrund würde ein
     * veralteter Eintrag später als neuer Chat erscheinen). Der Tap-Intent trägt Titel/Body ohnehin selbst.
     */
    fun shouldSaveProactiveSeed(appInForeground: Boolean): Boolean = !appInForeground

    private val nonceCounter = AtomicLong(0)

    /**
     * Eindeutiger Marker je angetippter Benachrichtigung — die JS-Schicht verwirft damit Doppel-Seeds
     * (Doppel-Tap, onCreate+onNewIntent, AppState-Resume-Retries) und ein ALTER Seed taucht beim nächsten
     * App-Start nicht versehentlich erneut auf (jeder Tap hat einen neuen Nonce).
     */
    fun newNonce(): String = "${System.currentTimeMillis()}-${nonceCounter.incrementAndGet()}"

    /**
     * JSON, wie es [ProactiveMessageModule.getAndClear] an die JS-Schicht liefert.
     * Notification-Tap-Kontext: Titel + voller Body + Nonce, damit der Chat beim Tippen sofort zeigt, worum es ging
     * (nicht nur der leere/alte Chat). Titel optional (Default "Donna" wird NICHT mitgeschickt — kein Pseudo-Titel).
     */
    fun proactiveJson(body: String, sessionId: String?, title: String? = null, nonce: String? = null): String =
        JSONObject().apply {
            put("message", body)
            if (!title.isNullOrBlank() && title != "Donna") put("title", title)
            if (!sessionId.isNullOrBlank()) put("session_id", sessionId)
            if (!nonce.isNullOrBlank()) put("nonce", nonce)
        }.toString()
}

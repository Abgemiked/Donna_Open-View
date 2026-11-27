package com.yourcompany.donna.reminder

import org.json.JSONArray
import org.json.JSONObject

/** Wie eine Erinnerung gezeigt wird (siehe [ReminderLogic.presentation]). */
enum class Presentation { FULLSCREEN_POPUP, HEADS_UP, NOTIFICATION_ONLY }

enum class ReplyKind(val key: String) {
    DONE("done"), SNOOZE("snooze");

    companion object {
        fun fromKey(key: String?): ReplyKind? = values().firstOrNull { it.key == key }
    }
}

/** Antwort der Nutzerin auf eine Erinnerung. [minutes] nur bei [ReplyKind.SNOOZE]. */
data class Reply(val kind: ReplyKind, val id: String, val minutes: Int?, val attempts: Int = 0)

/** Eine Erinnerung aus dem ntfy-Strom. [id] ist null, wenn der Server keine (sichere) Kennung mitgeschickt hat. */
class Reminder(val id: String?, val text: String)

class ReminderRequest(val path: String, val body: String?)

/** SENT/GONE: erledigt. RETRY: Netz/Serverfehler, spaeter erneut. PERMANENT: Server lehnt dauerhaft ab (400/401/403/422). */
enum class Outcome { SENT, GONE, RETRY, PERMANENT }

/** Ergebnis von [ReminderLogic.queueFail]: neue Warteschlange und ob der Eintrag verworfen wurde. */
class FailResult(val raw: String, val dropped: Boolean)

/**
 * #153 App-Teil — REINE Logik (JVM-testbar, keine Android-Aufrufe): Erkennen einer Erinnerung an den ntfy-Tags,
 * Entscheidung Popup/Heads-up, Kanalwahl, Spaeter-Stufen, Anfragen an den Server und Wiederholungs-Warteschlange.
 * Das Android-Gegenstueck steht in [ReminderNotifier], [ReminderReplies] und [ReminderPopupActivity].
 */
object ReminderLogic {
    /** ntfy-Tag des Servers (backend/app/routes/reminders.py); `rid=<id>` traegt die Kennung. */
    const val TAG_REMINDER = "donna_reminder"
    const val RID_PREFIX = "rid="
    private val SAFE_ID = Regex("^[0-9A-Za-z_-]{1,64}$")

    const val CHANNEL_ID = "donna_erinnerungen"
    const val CHANNEL_DND_ID = "donna_erinnerungen_dnd"

    /** Schalter "Darf Nicht stoeren durchbrechen": Standard AUS. Pref in [com.yourcompany.donna.TtsEngines.PREFS]. */
    const val DND_BYPASS_DEFAULT = false
    const val PREF_DND_BYPASS = "reminder_dnd_bypass"

    val SNOOZE_STEPS: List<Int> = listOf(10, 30, 60)
    const val SNOOZE_DEFAULT = 10

    const val QUEUE_MAX = 50

    /** Nach so vielen dauerhaften Fehlern wird eine gemerkte Antwort verworfen (kein Endlos-Stau). */
    const val MAX_ATTEMPTS = 5

    /** Merkliste der zuletzt erledigten Kennungen (gegen doppelte Zustellung). */
    const val ANSWERED_MAX = 50

    /** Erinnerung aus einer ntfy-Nachricht; null, wenn das Tag fehlt oder kein Text da ist. */
    fun fromJson(json: JSONObject): Reminder? {
        val tags = json.optJSONArray("tags") ?: return null
        var isReminder = false
        var id: String? = null
        for (i in 0 until tags.length()) {
            val t = tags.optString(i, "").trim()
            if (t.equals(TAG_REMINDER, ignoreCase = true)) isReminder = true
            if (t.startsWith(RID_PREFIX, ignoreCase = true)) {
                val v = t.substring(RID_PREFIX.length).trim()
                if (SAFE_ID.matches(v)) id = v
            }
        }
        if (!isReminder) return null
        val text = json.optString("message", "").ifBlank { json.optString("body", "") }.trim()
        if (text.isEmpty()) return null
        return Reminder(id, text.take(2_000))
    }

    /** Stabile Kennung der Benachrichtigung je Erinnerung (30000..39999), damit eine Wiederholung sie ersetzt. */
    fun notificationId(id: String?): Int = 30_000 + Math.floorMod((id ?: "").hashCode(), 10_000)

    /** Erinnerung ohne gueltige Kennung: eigene Kennung je Zaehler (40000..40999), ueberschreibt keine andere. */
    fun notificationIdAnonymous(counter: Int): Int = 40_000 + Math.floorMod(counter, 1_000)

    /**
     * Vollbild-Popup nur bei gesperrtem Geraet UND vorhandener Vollbild-Berechtigung (Android 14+: Sonderberechtigung).
     * Entsperrt zeigt Android ohnehin nur Heads-up (die Tasten sind dort verfuegbar). Hat die Nutzerin den Kanal
     * herabgestuft ([headsUpAllowed] false), bleibt es bei der stillen Benachrichtigung.
     */
    fun presentation(deviceLocked: Boolean, canUseFullScreen: Boolean, headsUpAllowed: Boolean): Presentation = when {
        !headsUpAllowed -> Presentation.NOTIFICATION_ONLY
        deviceLocked && canUseFullScreen -> Presentation.FULLSCREEN_POPUP
        else -> Presentation.HEADS_UP
    }

    /** Zweiter Kanal (Durchbrechen von Nicht stoeren) NUR bei eingeschaltetem Schalter. */
    fun channelId(dndBypass: Boolean): String = if (dndBypass) CHANNEL_DND_ID else CHANNEL_ID

    fun channelBypassesDnd(channelId: String): Boolean = channelId == CHANNEL_DND_ID

    /** Unbekannte oder fehlende Stufe -> Standardstufe. */
    /** Unterer Abstand der Popup-Karte: Grundabstand plus Systemleiste bzw. Tastatur (die hoehere), nie negativ. */
    fun popupBottomMargin(systemBarBottom: Int, imeBottom: Int, base: Int): Int =
        base + maxOf(systemBarBottom, imeBottom, 0)

    fun snoozeMinutes(requested: Int?): Int = if (requested != null && requested in SNOOZE_STEPS) requested else SNOOZE_DEFAULT

    fun snoozeLabel(minutes: Int): String = if (minutes % 60 == 0) "${minutes / 60} Std" else "$minutes Min"

    /** Anfrage an den Server (Pfad relativ zur Basis-URL); null bei unsicherer Kennung. */
    fun request(reply: Reply): ReminderRequest? {
        if (!SAFE_ID.matches(reply.id)) return null
        return when (reply.kind) {
            ReplyKind.DONE -> ReminderRequest("/reminders/${reply.id}/done", null)
            ReplyKind.SNOOZE -> ReminderRequest(
                "/reminders/${reply.id}/snooze",
                JSONObject().put("minutes", snoozeMinutes(reply.minutes)).toString(),
            )
        }
    }

    /** 2xx = angekommen; 404/409 = gibt es nicht mehr bzw. schon beantwortet (nichts mehr zu tun); sonst erneut versuchen. */
    fun outcome(httpCode: Int?): Outcome = when (httpCode) {
        null -> Outcome.RETRY
        in 200..299 -> Outcome.SENT
        404, 409 -> Outcome.GONE
        400, 401, 403, 422 -> Outcome.PERMANENT
        else -> Outcome.RETRY
    }

    /** Nur Erfolg oder "gibt es nicht mehr" raeumt die Benachrichtigung ab; sonst bleibt sie stehen. */
    fun clearsNotification(o: Outcome): Boolean = o == Outcome.SENT || o == Outcome.GONE

    // ── Wiederholungs-Warteschlange (JSON-Text in einer Pref) ───────────────
    fun queueParse(raw: String?): List<Reply> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val kind = ReplyKind.fromKey(o.optString("kind", "")) ?: return@mapNotNull null
                val id = o.optString("id", "")
                if (!SAFE_ID.matches(id)) return@mapNotNull null
                val minutes = if (kind == ReplyKind.SNOOZE) snoozeMinutes(if (o.has("minutes")) o.optInt("minutes") else null) else null
                Reply(kind, id, minutes, o.optInt("attempts", 0).coerceAtLeast(0))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun queueWrite(items: List<Reply>): String {
        val arr = JSONArray()
        items.forEach {
            val o = JSONObject().put("kind", it.kind.key).put("id", it.id)
            if (it.minutes != null) o.put("minutes", it.minutes)
            if (it.attempts > 0) o.put("attempts", it.attempts)
            arr.put(o)
        }
        return arr.toString()
    }

    /** Haengt die Antwort an; je Erinnerung gilt nur die neueste, die Liste ist begrenzt (aelteste fallen weg). */
    fun queueAdd(raw: String?, reply: Reply): String =
        queueWrite((queueParse(raw).filter { it.id != reply.id } + reply).takeLast(QUEUE_MAX))

    fun queueRemove(raw: String?, id: String): String = queueWrite(queueParse(raw).filter { it.id != id })

    private fun same(a: Reply, b: Reply) = a.id == b.id && a.kind == b.kind && a.minutes == b.minutes

    /**
     * Entfernt den Eintrag nur, wenn er noch DERSELBE ist wie der gesendete (Art, Kennung, Minuten) — eine neuere
     * Antwort zur selben Erinnerung bleibt erhalten.
     */
    fun queueRemoveIfSame(raw: String?, sent: Reply): String = queueWrite(queueParse(raw).filter { !same(it, sent) })

    /** Dauerhafter Fehler: Versuch zaehlen, Eintrag nach hinten; ab [MAX_ATTEMPTS] verwerfen. */
    fun queueFail(raw: String?, failed: Reply): FailResult {
        val items = queueParse(raw)
        val current = items.firstOrNull { same(it, failed) } ?: return FailResult(queueWrite(items), false)
        val next = current.copy(attempts = current.attempts + 1)
        val rest = items.filter { !same(it, failed) }
        return if (next.attempts >= MAX_ATTEMPTS) FailResult(queueWrite(rest), true) else FailResult(queueWrite(rest + next), false)
    }

    // ── Merkliste erledigter Erinnerungen ───────────────────────────────────
    fun answeredParse(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it, "") }.filter { SAFE_ID.matches(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun answeredAdd(raw: String?, id: String): String {
        if (!SAFE_ID.matches(id)) return JSONArray(answeredParse(raw)).toString()
        return JSONArray((answeredParse(raw).filter { it != id } + id).takeLast(ANSWERED_MAX)).toString()
    }

    /** Eine bereits erledigte Erinnerung (doppelte Zustellung) wird nicht erneut angezeigt; ohne Kennung immer. */
    fun shouldShow(r: Reminder, answeredRaw: String?): Boolean = r.id == null || r.id !in answeredParse(answeredRaw)
}

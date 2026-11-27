package com.yourcompany.donna.reminder

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.yourcompany.donna.BuildConfig
import com.yourcompany.donna.TokenStore
import com.yourcompany.donna.TtsEngines
import java.net.HttpURLConnection
import java.net.URL

/**
 * #153: Sendet "Erledigt" / "Spaeter" an den Server (`POST /reminders/{id}/done|snooze`, Bearer wie die uebrigen
 * App-Routen). Gemeinsamer Weg fuer die Benachrichtigungs-Tasten ([ReminderActionReceiver]) und das Popup
 * ([ReminderPopupActivity]). Scheitert der Versand, wird die Antwort gemerkt und beim naechsten Start des
 * Push-Dienstes erneut versucht; die Benachrichtigung bleibt dann stehen und die Nutzerin erfaehrt es ehrlich.
 */
object ReminderReplies {
    private const val TAG = "ReminderReplies"
    private const val PREF_QUEUE = "reminder_reply_queue"
    private const val PREF_ANSWERED = "reminder_answered"
    private val flushing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val lock = Any()

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(TtsEngines.PREFS, Context.MODE_PRIVATE)

    /** HTTP-Antwortcode, null bei Fehler (kein Token, kein Netz, unsichere Kennung). Blockiert: nie auf dem Hauptthread. */
    private fun post(ctx: Context, reply: Reply): Int? {
        val req = ReminderLogic.request(reply) ?: return null
        val token = try { TokenStore.getToken(ctx.applicationContext) } catch (_: Exception) { null } ?: return null
        return try {
            val conn = URL("${BuildConfig.DONNA_API_URL}${req.path}").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", "application/json")
                // Netzzeit unter der Broadcast-Grenze (~10 s) des Receivers halten
                conn.connectTimeout = 4_000
                conn.readTimeout = 4_000
                conn.doOutput = true
                conn.outputStream.use { it.write((req.body ?: "{}").toByteArray(Charsets.UTF_8)) }
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Antwort nicht gesendet: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Rohtext der Merkliste erledigter Kennungen (gegen doppelte Zustellung). */
    fun answeredRaw(ctx: Context): String? = synchronized(lock) { prefs(ctx).getString(PREF_ANSWERED, null) }

    private fun markAnswered(ctx: Context, id: String) {
        synchronized(lock) {
            val p = prefs(ctx)
            p.edit().putString(PREF_ANSWERED, ReminderLogic.answeredAdd(p.getString(PREF_ANSWERED, null), id)).apply()
        }
    }

    private fun enqueue(ctx: Context, reply: Reply) {
        synchronized(lock) {
            val p = prefs(ctx)
            p.edit().putString(PREF_QUEUE, ReminderLogic.queueAdd(p.getString(PREF_QUEUE, null), reply)).apply()
        }
    }

    /**
     * Wertet das Ergebnis aus und raeumt die Warteschlange auf. Liefert das wirksame Ergebnis: ein dauerhaft
     * abgelehnter Eintrag wird nach [ReminderLogic.MAX_ATTEMPTS] Versuchen verworfen und die Benachrichtigung
     * abgeraeumt (GONE), damit nichts unentfernbar festhaengt.
     */
    private fun apply(ctx: Context, reply: Reply, outcome: Outcome): Outcome {
        var effective = outcome
        synchronized(lock) {
            val p = prefs(ctx)
            val raw = p.getString(PREF_QUEUE, null)
            when (outcome) {
                Outcome.SENT, Outcome.GONE ->
                    p.edit().putString(PREF_QUEUE, ReminderLogic.queueRemoveIfSame(raw, reply)).apply()
                Outcome.PERMANENT -> {
                    val r = ReminderLogic.queueFail(raw, reply)
                    p.edit().putString(PREF_QUEUE, r.raw).apply()
                    if (r.dropped) effective = Outcome.GONE
                }
                Outcome.RETRY -> Unit // bleibt gemerkt, naechster Versuch bei Start oder neuer Erinnerung
            }
        }
        if (ReminderLogic.clearsNotification(effective)) ReminderNotifier.cancel(ctx, ReminderLogic.notificationId(reply.id))
        return effective
    }

    /**
     * Sendet im Hintergrund. Die Antwort wird ZUERST gemerkt (ein Prozessende waehrend des Sendens verliert sie nicht)
     * und nach Erfolg wieder entfernt. Bei SENT/GONE verschwindet die Benachrichtigung, sonst bleibt sie stehen.
     * [toastOnFailure]: das Popup zeigt den Fehler selbst und braucht keinen zweiten. [onResult] kommt immer.
     */
    fun submit(ctx: Context, reply: Reply, notifId: Int, toastOnFailure: Boolean = true, onResult: ((Outcome) -> Unit)? = null) {
        val app = ctx.applicationContext
        Thread {
            var outcome = Outcome.RETRY
            try {
                enqueue(app, reply)
                if (reply.kind == ReplyKind.DONE) markAnswered(app, reply.id)
                outcome = apply(app, reply, ReminderLogic.outcome(post(app, reply)))
                if (ReminderLogic.clearsNotification(outcome)) {
                    ReminderNotifier.cancel(app, notifId)
                } else if (toastOnFailure) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(
                            app,
                            "Konnte deine Antwort nicht an Donna senden. Die Erinnerung bleibt stehen, ich versuche es später erneut.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Antwort fehlgeschlagen: ${e.javaClass.simpleName}")
            } finally {
                try { onResult?.invoke(outcome) } catch (_: Exception) {}
            }
        }.apply { isDaemon = true; name = "reminder-reply" }.start()
    }

    /**
     * Offene Antworten erneut senden (beim Start des Dienstes und wenn eine neue Erinnerung ankommt, das Netz also da
     * ist). Ein Lauf zur Zeit. Netzfehler beenden den Lauf; dauerhaft abgelehnte Eintraege ruecken nach hinten und
     * halten die uebrigen nicht auf.
     */
    fun flushPending(ctx: Context) {
        if (!flushing.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        Thread {
            try {
                val items = synchronized(lock) { ReminderLogic.queueParse(prefs(app).getString(PREF_QUEUE, null)) }
                for (reply in items) {
                    val outcome = ReminderLogic.outcome(post(app, reply))
                    if (outcome == Outcome.RETRY) break
                    apply(app, reply, outcome)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Nachsenden fehlgeschlagen: ${e.javaClass.simpleName}")
            } finally {
                flushing.set(false)
            }
        }.apply { isDaemon = true; name = "reminder-flush" }.start()
    }
}

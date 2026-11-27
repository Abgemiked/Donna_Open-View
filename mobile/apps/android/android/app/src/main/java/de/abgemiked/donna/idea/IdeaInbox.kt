package com.yourcompany.donna.idea

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
import java.util.concurrent.atomic.AtomicBoolean

/** Ergebnis eines Speichern-Tipps, wie es die Erfassung anzeigt. */
sealed class SubmitResult {
    /** Issue angelegt (oder schon vorhanden). [number] kann fehlen, wenn die Antwort keine Nummer trug. */
    data class Saved(val number: Int?) : SubmitResult()
    /** Nicht gesendet (kein Netz, Server nicht bereit, nicht gekoppelt) — gemerkt, wird nachgesendet. */
    object Queued : SubmitResult()
    /** Server lehnt die Eingabe ab; die Idee bleibt im Feld. */
    object Rejected : SubmitResult()
    /** Warteschlange voll — nichts gemerkt, die Idee bleibt im Feld. */
    object QueueFull : SubmitResult()
}

/**
 * #233: Sendet eine bestaetigte Idee an `POST /inbox/capture` (Bearer aus [TokenStore]). Die Idee wird ZUERST
 * gemerkt (ein Prozessende verliert sie nicht) und erst nach Erfolg entfernt; die Nonce bleibt dabei gleich, also
 * legt ein Nachsenden nie ein zweites Issue an. Nachsenden beim Oeffnen der Erfassung und mit dem Push-Dienst
 * (wie ReminderReplies). Im Log stehen nie Text oder Token.
 */
object IdeaInbox {
    private const val TAG = "IdeaInbox"
    private const val PREF_QUEUE = "idea_capture_queue"
    private val lock = Any()
    private val flushing = AtomicBoolean(false)
    /** Nonces, die gerade per Speichern-Tipp gesendet werden — das Nachsenden laesst sie aus (kein 409-Gleichlauf). */
    private val submitting: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(TtsEngines.PREFS, Context.MODE_PRIVATE)

    /** (HTTP-Code, Antworttext) oder null (kein Token, kein Netz, ungueltiger Eintrag). Blockiert: nie Hauptthread. */
    private fun post(ctx: Context, item: PendingIdea): Pair<Int, String?>? {
        val body = IdeaCaptureLogic.body(item) ?: return null
        val token = try { TokenStore.getToken(ctx.applicationContext) } catch (_: Exception) { null } ?: return null
        return try {
            val conn = URL("${BuildConfig.DONNA_API_URL}${IdeaCaptureLogic.PATH}").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.connectTimeout = 6_000
                conn.readTimeout = 10_000
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val text = try {
                    (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(4_000) }
                } catch (_: Exception) { null }
                code to text
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Idee nicht gesendet: ${e.javaClass.simpleName}")
            null
        }
    }

    fun pendingCount(ctx: Context): Int =
        synchronized(lock) { IdeaCaptureLogic.queueParse(prefs(ctx).getString(PREF_QUEUE, null)).size }

    private fun enqueue(ctx: Context, item: PendingIdea): Boolean = synchronized(lock) {
        val p = prefs(ctx)
        val next = IdeaCaptureLogic.queueAdd(p.getString(PREF_QUEUE, null), item) ?: return@synchronized false
        p.edit().putString(PREF_QUEUE, next).commit()
    }

    /** Raeumt nach dem Ergebnis auf; true, wenn ein dauerhaft abgelehnter Eintrag verworfen wurde. */
    private fun apply(ctx: Context, item: PendingIdea, outcome: IdeaOutcome, dropNow: Boolean): Boolean {
        synchronized(lock) {
            val p = prefs(ctx)
            val raw = p.getString(PREF_QUEUE, null)
            return when (outcome) {
                IdeaOutcome.SENT -> {
                    p.edit().putString(PREF_QUEUE, IdeaCaptureLogic.queueRemove(raw, item.nonce)).apply(); false
                }
                IdeaOutcome.PERMANENT -> if (dropNow) {
                    // Vordergrund: die Idee steht noch im Feld, Mike kann sie anpassen — nicht doppelt merken.
                    p.edit().putString(PREF_QUEUE, IdeaCaptureLogic.queueRemove(raw, item.nonce)).apply(); true
                } else {
                    val (next, dropped) = IdeaCaptureLogic.queueFail(raw, item.nonce)
                    p.edit().putString(PREF_QUEUE, next).apply(); dropped
                }
                IdeaOutcome.RETRY -> false
            }
        }
    }

    /** Speichern-Tipp: merken, senden, Ergebnis auf dem Hauptthread melden. */
    fun submit(ctx: Context, item: PendingIdea, onResult: (SubmitResult) -> Unit) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        submitting.add(item.nonce)
        Thread {
            var result: SubmitResult = SubmitResult.Queued
            try {
                if (!enqueue(app, item)) {
                    result = SubmitResult.QueueFull
                } else {
                    val resp = post(app, item)
                    val outcome = IdeaCaptureLogic.outcome(resp?.first)
                    if (resp != null && outcome != IdeaOutcome.SENT) Log.w(TAG, "Idee nicht angelegt: HTTP ${resp.first}")
                    apply(app, item, outcome, dropNow = true)
                    result = when (outcome) {
                        IdeaOutcome.SENT -> SubmitResult.Saved(IdeaCaptureLogic.issueNumber(resp?.second))
                        IdeaOutcome.PERMANENT -> SubmitResult.Rejected
                        IdeaOutcome.RETRY -> SubmitResult.Queued
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Speichern fehlgeschlagen: ${e.javaClass.simpleName}")
            } finally {
                submitting.remove(item.nonce)
                main.post { try { onResult(result) } catch (_: Exception) {} }
            }
        }.apply { isDaemon = true; name = "idea-submit" }.start()
    }

    /**
     * Gemerkte Ideen nachsenden. Ein Lauf zur Zeit; ein voruebergehender Fehler beendet den Lauf (Reihenfolge bleibt).
     * Nach Erfolg kurzer Hinweis, damit Mike weiss, dass die Idee jetzt in der Inbox ist.
     */
    fun flushPending(ctx: Context) {
        if (!flushing.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        Thread {
            var saved = 0
            try {
                val items = synchronized(lock) {
                    val raw = prefs(app).getString(PREF_QUEUE, null)
                    IdeaCaptureLogic.queueParse(raw).also {
                        if (it.isEmpty() && !raw.isNullOrBlank() && raw != "[]") Log.w(TAG, "Warteschlange unlesbar (Länge ${raw.length})")
                    }
                }
                for (item in items) {
                    if (item.nonce in submitting) continue
                    val code = post(app, item)?.first
                    val outcome = IdeaCaptureLogic.outcome(code)
                    if (outcome == IdeaOutcome.RETRY) break
                    if (apply(app, item, outcome, dropNow = false)) Log.w(TAG, "Gemerkte Idee verworfen (abgelehnt, HTTP $code)")
                    if (outcome == IdeaOutcome.SENT) saved++
                }
            } catch (e: Exception) {
                Log.w(TAG, "Nachsenden fehlgeschlagen: ${e.javaClass.simpleName}")
            } finally {
                flushing.set(false)
            }
            if (saved > 0) {
                Handler(Looper.getMainLooper()).post {
                    val msg = if (saved == 1) "Gemerkte Idee ist jetzt in deiner Inbox." else "$saved gemerkte Ideen sind jetzt in deiner Inbox."
                    Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }.apply { isDaemon = true; name = "idea-flush" }.start()
    }
}

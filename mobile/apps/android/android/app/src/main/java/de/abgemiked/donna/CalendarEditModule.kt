package com.yourcompany.donna

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.uimanager.ViewManager
import com.yourcompany.donna.calendaredit.AndroidCalendarGateway
import com.yourcompany.donna.calendaredit.ApplyOutcome
import com.yourcompany.donna.calendaredit.CalendarEditMessages
import com.yourcompany.donna.calendaredit.CalendarEditService
import com.yourcompany.donna.calendaredit.EditRequest
import com.yourcompany.donna.calendaredit.EditRequestParser
import com.yourcompany.donna.calendaredit.OnceLedger
import com.yourcompany.donna.calendaredit.PlanResult
import com.yourcompany.donna.calendaredit.RefusalReason
import com.yourcompany.donna.calendaredit.Scope
import com.yourcompany.donna.calendaredit.Summary
import com.yourcompany.donna.device.DeviceActionKind
import com.yourcompany.donna.device.DeviceActionReport
import com.yourcompany.donna.device.DeviceActionReporter
import com.yourcompany.donna.device.ResultKind
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

/**
 * #147 Paket H — Brücke für die Terminkarte (Stufe 2). Suchen, Prüfen und Schreiben passieren hier auf dem Gerät
 * (Laufzeitrecht Kalender). Der Schalter "calendar_edit" (Standard aus) wird bei JEDEM Aufruf neu geprüft. Nach dem
 * Tipp wird alles frisch gelesen und mit der Karte verglichen; das Ergebnis geht über den Rückkanal an den Server.
 */
class CalendarEditModule(private val reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
    override fun getName() = "CalendarEdit"

    private companion object {
        val APPLY_LOCK = Any()
        const val DONE_KEY = "calendar_edit_done_ids"
    }

    private fun prefs() = reactContext.getSharedPreferences(TtsEngines.PREFS, Context.MODE_PRIVATE)
    private fun enabled() = DeviceActionKind.read(prefs(), DeviceActionKind.CALENDAR_EDIT)
    private fun zone(): ZoneId = ZoneId.systemDefault()
    private fun service() = CalendarEditService(AndroidCalendarGateway(reactContext), zone()) { System.currentTimeMillis() }

    private fun alreadyDone(id: String): Boolean = OnceLedger.contains(prefs().getString(DONE_KEY, ""), id)

    /** Die Einmal-Marke wird SOFORT und synchron geschrieben (commit), damit sie ein Prozessende direkt nach dem Schreiben überlebt. */
    private fun markDone(id: String) {
        prefs().edit().putString(DONE_KEY, OnceLedger.add(prefs().getString(DONE_KEY, ""), id)).commit()
    }

    /** Schlüssel der Einmal-Ausführung: Aktionskennung, sonst Karten-Kennung (nur [a-z0-9-], sonst keine Ausführung). */
    private fun onceKey(json: JSONObject): String? =
        DeviceActionReport.actionId(json) ?: (json.opt("card_id") as? String)?.takeIf { Regex("^[a-z0-9-]{6,40}$").matches(it) }

    private fun status(s: String, message: String? = null) =
        JSONObject().put("status", s).apply { if (message != null) put("message", message) }

    @ReactMethod
    fun getPermissionState(promise: Promise) {
        promise.resolve(if (AndroidCalendarGateway(reactContext).hasPermission()) "granted" else "missing")
    }

    @ReactMethod
    fun openPermissionSettings(promise: Promise) {
        try {
            reactContext.startActivity(
                Intent("android.settings.APPLICATION_DETAILS_SETTINGS", Uri.fromParts("package", reactContext.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            promise.resolve(true)
        } catch (_: Exception) {
            promise.resolve(false)
        }
    }

    /** Sucht und prüft (nur lesen). status: ready | choose | none | refused | needs_permission | rejected | disabled | failed. */
    @ReactMethod
    fun plan(requestJson: String, promise: Promise) {
        Thread {
            promise.resolve(try { doPlan(requestJson, null).toString() } catch (_: Exception) { status("failed").toString() })
        }.start()
    }

    @ReactMethod
    fun planSelected(requestJson: String, eventId: Double, beginMs: Double, promise: Promise) {
        Thread {
            promise.resolve(
                try { doPlan(requestJson, eventId.toLong() to beginMs.toLong()).toString() } catch (_: Exception) { status("failed").toString() },
            )
        }.start()
    }

    private fun parse(requestJson: String): EditRequest? =
        try { EditRequestParser.parse(JSONObject(requestJson)) } catch (_: Exception) { null }

    private fun doPlan(requestJson: String, sel: Pair<Long, Long>?): JSONObject {
        if (!enabled()) {
            return status("disabled", "Das darf ich noch nicht selbst tun. Du kannst es in den Einstellungen unter „Donna darf selbst …“ einschalten.")
        }
        val req = parse(requestJson) ?: return status("rejected", "Das kann ich so nicht ausführen.")
        val svc = service()
        val z = zone()
        return when (val r = if (sel == null) svc.plan(req) else svc.planSelected(req, sel.first, sel.second)) {
            PlanResult.NeedsPermission -> status("needs_permission", CalendarEditMessages.needsPermission())
            is PlanResult.NoMatch -> status("none", CalendarEditMessages.noMatch(r.syncedCalendars))
            is PlanResult.Refused -> status("refused", CalendarEditMessages.refusal(r.reason))
            is PlanResult.Choose -> status("choose").put(
                "candidates",
                JSONArray().also { arr ->
                    r.candidates.forEach {
                        arr.put(
                            JSONObject().put("eventId", it.eventId).put("beginMs", it.beginMs).put("title", it.title)
                                .put("own", it.own).put("recurring", it.recurring)
                                .put("when", CalendarEditMessages.when_(it.beginMs, it.beginMs, it.allDay, z).substringBefore("–")),
                        )
                    }
                },
            )
            is PlanResult.Ready -> {
                val p = r.plan
                val b = p.before
                status("ready")
                    .put("op", p.op.key).put("eventId", p.eventId).put("recurring", p.recurring)
                    .put("seriesAllowed", p.seriesAllowed).put("irreversible", p.irreversible)
                    .put(
                        "before",
                        JSONObject().put("title", b.title).put("beginMs", b.beginMs).put("endMs", b.endMs)
                            .put("allDay", b.allDay).put("when", CalendarEditMessages.when_(b.beginMs, b.endMs, b.allDay, z)),
                    )
                    .put(
                        "after",
                        JSONObject().put("title", p.newTitle ?: b.title).put(
                            "when",
                            if (p.newBeginMs != null && p.newEndMs != null) {
                                CalendarEditMessages.when_(p.newBeginMs, p.newEndMs, b.allDay && !p.toTimed, z)
                            } else {
                                CalendarEditMessages.when_(b.beginMs, b.endMs, b.allDay, z)
                            },
                        ),
                    )
                    .apply { if (p.irreversible) put("warning", CalendarEditMessages.deleteWarning()) }
            }
        }
    }

    /**
     * Nach dem Tipp. [scope] "instance" | "series"; [seriesConfirmed] nur nach der zweiten Bestätigung. Meldet das Ergebnis
     * über den Rückkanal (nur wenn der Server eine Kennung vergeben hat).
     */
    @ReactMethod
    fun apply(
        requestJson: String, eventId: Double, beginMs: Double, expectedTitle: String, expectedEndMs: Double,
        scope: String, seriesConfirmed: Boolean, promise: Promise,
    ) {
        Thread {
            promise.resolve(
                try {
                    // höchstens ein Schreibvorgang gleichzeitig (Doppeltipp)
                    synchronized(APPLY_LOCK) { doApply(requestJson, eventId.toLong(), beginMs.toLong(), expectedTitle, expectedEndMs.toLong(), scope, seriesConfirmed) }.toString()
                } catch (_: Exception) {
                    status("failed", CalendarEditMessages.refusal(RefusalReason.FAILED)).toString()
                },
            )
        }.start()
    }

    private fun doApply(
        requestJson: String, eventId: Long, beginMs: Long, title: String, endMs: Long, scope: String, seriesConfirmed: Boolean,
    ): JSONObject {
        val actionId = try { DeviceActionReport.actionId(JSONObject(requestJson)) } catch (_: Exception) { null }
        fun report(k: ResultKind) { if (actionId != null) DeviceActionReporter.report(reactContext, actionId, k) }
        if (!enabled()) {
            report(ResultKind.DISABLED)
            return status("disabled", "Das darf ich noch nicht selbst tun.")
        }
        val req = parse(requestJson)
        if (req == null) {
            report(ResultKind.REJECTED)
            return status("rejected", "Das kann ich so nicht ausführen.")
        }
        // Eine Karte (Kennung) wird höchstens EINMAL ausgeführt - auch nach Neustart/Neuladen des Verlaufs
        val once = try { onceKey(JSONObject(requestJson)) } catch (_: Exception) { null }
        if (once == null) {   // ohne Kennung nie ausführen (Einmal-Garantie)
            report(ResultKind.REJECTED)
            return status("rejected", "Das kann ich so nicht ausführen.")
        }
        if (alreadyDone(once)) {
            report(OnceLedger.REPORT_FOR_ALREADY_DONE)   // erledigt = ok (auch wenn die Erstmeldung verloren ging)
            return status("ok", "Das habe ich schon erledigt — ich ändere es nicht noch einmal.")
        }
        val sc = if (scope == "series") Scope.SERIES else Scope.INSTANCE
        val res = service().applyFromRequest(req, eventId, beginMs, Summary(title, beginMs, endMs, false, false), sc, seriesConfirmed)
        if (res.outcome == ApplyOutcome.OK) markDone(once)
        return when (res.outcome) {
            ApplyOutcome.OK -> { report(ResultKind.OK); status("ok", CalendarEditMessages.done(req.op, sc == Scope.SERIES)) }
            ApplyOutcome.NEEDS_PERMISSION -> { report(ResultKind.NEEDS_PERMISSION); status("needs_permission", CalendarEditMessages.needsPermission()) }
            ApplyOutcome.REFUSED -> { report(ResultKind.REJECTED); status("refused", CalendarEditMessages.refusal(res.refusal ?: RefusalReason.FAILED)) }
            ApplyOutcome.FAILED -> { report(ResultKind.FAILED); status("failed", CalendarEditMessages.refusal(RefusalReason.FAILED)) }
        }
    }
}

class CalendarEditPackage : ReactPackage {
    override fun createNativeModules(ctx: ReactApplicationContext) = listOf(CalendarEditModule(ctx))
    override fun createViewManagers(ctx: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}

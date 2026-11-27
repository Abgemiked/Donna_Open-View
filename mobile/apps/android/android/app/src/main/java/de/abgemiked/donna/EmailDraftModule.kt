package com.yourcompany.donna

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.uimanager.ViewManager
import com.yourcompany.donna.calendaredit.OnceLedger
import com.yourcompany.donna.device.DeviceActionKind
import com.yourcompany.donna.device.DeviceActionReport
import com.yourcompany.donna.device.DeviceActionReporter
import com.yourcompany.donna.emaildraft.DoneStore
import com.yourcompany.donna.emaildraft.EmailDraft
import com.yourcompany.donna.emaildraft.EmailDraftIntent
import com.yourcompany.donna.emaildraft.EmailDraftMessages
import com.yourcompany.donna.emaildraft.EmailDraftService
import com.yourcompany.donna.emaildraft.LaunchResult
import com.yourcompany.donna.device.ResultKind
import org.json.JSONObject

/**
 * #147 Paket I — Brücke für die E-Mail-Entwurf-Karte (Stufe 2). Öffnet NUR einen Entwurf in der Mail-App
 * (ACTION_SENDTO, Daten-Uri nacktes "mailto:"); gesendet wird nie. Keine Berechtigung nötig. Der Schalter
 * "email_draft" (Standard aus) wird bei jedem Aufruf neu geprüft. Das Ergebnis geht über den Rückkanal an den Server.
 * Empfänger, Betreff und Text werden nie geloggt.
 */
class EmailDraftModule(private val reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
    override fun getName() = "EmailDraft"

    private companion object {
        val LOCK = Any()
        const val DONE_KEY = "email_draft_done_ids"
    }

    private fun prefs() = reactContext.getSharedPreferences(TtsEngines.PREFS, Context.MODE_PRIVATE)

    /** Einmal-Marke sofort und synchron (commit), damit sie ein Prozessende direkt nach dem Öffnen überlebt. */
    private val store = object : DoneStore {
        override fun contains(id: String) = OnceLedger.contains(prefs().getString(DONE_KEY, ""), id)
        override fun mark(id: String) {
            prefs().edit().putString(DONE_KEY, OnceLedger.add(prefs().getString(DONE_KEY, ""), id)).commit()
        }
    }

    private fun launch(d: EmailDraft): LaunchResult {
        val spec = EmailDraftIntent.spec(d)
        val intent = Intent(spec.action, Uri.parse(spec.dataUri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        spec.email?.let { intent.putExtra(Intent.EXTRA_EMAIL, it.toTypedArray()) }
        spec.subject?.let { intent.putExtra(Intent.EXTRA_SUBJECT, it) }
        spec.text?.let { intent.putExtra(Intent.EXTRA_TEXT, it) }
        return try {
            reactContext.startActivity(intent)
            LaunchResult.STARTED
        } catch (_: ActivityNotFoundException) {
            LaunchResult.NO_APP
        } catch (_: Exception) {
            LaunchResult.ERROR
        }
    }

    /** Nach dem Tipp. Antwort: {status: ok | unsupported | rejected | disabled | failed, message}. */
    @ReactMethod
    fun open(requestJson: String, promise: Promise) {
        Thread {
            promise.resolve(
                try {
                    synchronized(LOCK) { doOpen(requestJson) }.toString()
                } catch (_: Exception) {
                    JSONObject().put("status", "failed").put("message", EmailDraftMessages.failed()).toString()
                },
            )
        }.start()
    }

    private fun doOpen(requestJson: String): JSONObject {
        val json = try { JSONObject(requestJson) } catch (_: Exception) {
            return JSONObject().put("status", "rejected").put("message", EmailDraftMessages.rejected())
        }
        val enabled = DeviceActionKind.read(prefs(), DeviceActionKind.EMAIL_DRAFT)
        val out = EmailDraftService.execute(enabled, json, store, ::launch)
        DeviceActionReport.actionId(json)?.let { DeviceActionReporter.report(reactContext, it, out.kind) }
        return JSONObject().put("status", out.status).put("message", out.message)
    }
}

class EmailDraftPackage : ReactPackage {
    override fun createNativeModules(ctx: ReactApplicationContext) = listOf(EmailDraftModule(ctx))
    override fun createViewManagers(ctx: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}

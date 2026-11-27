package com.yourcompany.donna.device

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.yourcompany.donna.DonnaAccessibilityService
import org.json.JSONObject

/**
 * #147 Paket A — führt geprüfte Geräte-Aktionen aus (Android-Aufrufe). Die Entscheidungen (Positivliste, Grenzen,
 * Freigabe je Aktionsart, Budget, App-Zuordnung, Rückgängig-Ablage) stehen in DeviceActions.kt; hier wird nur
 * umgesetzt. EIN Weg für Overlay und Chat: beide rufen [runGated]. Rückmeldungen sind ehrlich: ein gestarteter
 * Intent wird nicht als Erfolg der Zielapp ausgegeben, Fehler und fehlende Rechte werden benannt. Logs ohne
 * Nutzerinhalte (nur Aktionsname und Ergebnisart).
 */
class DeviceActionRunner(
    private val context: Context,
    /** Bestehende Sperrliste (Banking, Passwort-Manager, Einstellungen …) — wiederverwendet, nicht dupliziert. */
    private val blocked: Set<String> = DonnaAccessibilityService.BLOCKED_PACKAGES,
) {
    /** Prüft (Positivliste, Aktionsart eingeschaltet, Budget) und führt dann aus. */
    fun runGated(json: JSONObject, enabled: Set<DeviceActionKind>, budget: TurnBudget): DeviceActionResult {
        val actionId = DeviceActionReport.actionId(json)
        // #147 Paket F: Screenshot meldet erst nach der Verzögerung (siehe screenshot()); sonst sofort.
        val later: ((ResultKind) -> Unit)? = actionId?.let { id -> { k -> DeviceActionReporter.report(context, id, k) } }
        val result = when (val g = DeviceActionGate.check(json, enabled, budget)) {
            is GateResult.Refused -> DeviceActionResult(g.kind, g.message).also { log("gate", it.kind) }
            is GateResult.Run -> run(g.action, later)
        }
        // #183: Ergebnis an den Server melden (nur wenn er eine Kennung vergeben hat = neue Anfrage-Fassung).
        if (actionId != null && !result.reportsLater) DeviceActionReporter.report(context, actionId, result.kind)
        return result
    }

    /**
     * Führt aus und hält die Aktion in der Rückgängig-Ablage fest. Das Ergebnis trägt die Kennung ([undoId]); die
     * Taste "Rückgängig" wirkt nur auf genau diese Aktion.
     */
    fun run(action: DeviceAction, onFinal: ((ResultKind) -> Unit)? = null): DeviceActionResult {
        val result = execute(action, onFinal)
        if (!result.ok) return result
        val id = STORE.record(result.undo, conflictKeyOf(action))
        return if (result.undo != null) result.copy(undoId = id) else result
    }

    /** Aktionen am selben Zustand (Lautstärke je Kanal, Taschenlampe) machen ältere Rückgängig-Tasten ungültig. */
    private fun conflictKeyOf(a: DeviceAction): String? = when (a) {
        is DeviceAction.Flashlight -> "flashlight"
        is DeviceAction.Volume -> "volume:${a.channel.key}"
        is DeviceAction.SetBrightness -> "brightness"
        is DeviceAction.Dnd -> "dnd"
        else -> null
    }

    private fun execute(action: DeviceAction, onFinal: ((ResultKind) -> Unit)? = null): DeviceActionResult {
        val result = try {
            when (action) {
                is DeviceAction.Flashlight -> flashlight(action)
                is DeviceAction.Volume -> volume(action)
                is DeviceAction.OpenApp -> openApp(action)
                is DeviceAction.OpenSettings -> openSettings(action)
                is DeviceAction.SetTimer -> timer(action)
                is DeviceAction.SetAlarm -> alarm(action)
                is DeviceAction.SetBrightness -> brightness(action)
                is DeviceAction.Dnd -> dnd(action)
                DeviceAction.LockScreen -> lockScreen(action)
                DeviceAction.Screenshot -> screenshot(action, onFinal)
                is DeviceAction.OpenCamera -> openCamera(action)
            }
        } catch (e: SecurityException) {
            // Recht zwischen Prüfung und Ausführung entzogen: kindspezifische Meldung statt des generischen Texts
            denied(action.kind, ResultKind.NEEDS_PERMISSION)
        } catch (e: ActivityNotFoundException) {
            fail(ResultKind.UNSUPPORTED)
        } catch (e: Exception) {
            fail(ResultKind.FAILED)
        }
        log(action.name, result.kind)
        return result
    }

    /**
     * Nimmt die Aktion mit der Kennung [id] zurück (und nur diese). Freigabe wird erneut geprüft, höchstens 2
     * Minuten, einmalig, nicht mehr nach einer späteren Änderung desselben Zustands. Lautstärke und Taschenlampe
     * stellen den alten Wert her; Timer/Wecker werden NIE automatisch verworfen (das könnte einen anderen treffen),
     * stattdessen öffnet sich die Uhr-App. Verbraucht wird die Möglichkeit erst, wenn das Zurücknehmen angestoßen
     * werden konnte.
     */
    fun undo(id: Long, enabled: Set<DeviceActionKind>): DeviceActionResult =
        when (val t = STORE.take(id, enabled)) {
            is UndoTake.Ready -> performUndo(t.spec).also { if (it.ok) STORE.commit(id) }
            else -> DeviceActionResult(
                if (t == UndoTake.Disabled) ResultKind.DISABLED else ResultKind.FAILED,
                DeviceActionMessages.undoRefused(t),
            )
        }

    private fun performUndo(spec: UndoSpec): DeviceActionResult {
        val result = try {
            when (spec) {
                is UndoSpec.VolumeTo, is UndoSpec.FlashlightTo -> {
                    val json = DeviceActionUndo.toJson(spec) ?: return fail(ResultKind.UNSUPPORTED)
                    val action = (DeviceActionParser.parse(json) as? ParseResult.Ok)?.action ?: return fail(ResultKind.REJECTED)
                    val r = execute(action)
                    if (r.ok) DeviceActionResult(ResultKind.OK, DeviceActionMessages.undone(spec)) else r
                }
                is UndoSpec.BrightnessTo -> restoreBrightness(spec)
                is UndoSpec.DndTo -> restoreDnd(spec)
                UndoSpec.ClockTimers -> showClock(spec, AlarmClock.ACTION_SHOW_TIMERS)
                UndoSpec.ClockAlarms -> showClock(spec, AlarmClock.ACTION_SHOW_ALARMS)
            }
        } catch (e: SecurityException) {
            denied(spec.kind, ResultKind.NEEDS_PERMISSION)
        } catch (e: ActivityNotFoundException) {
            fail(ResultKind.UNSUPPORTED)
        } catch (e: Exception) {
            fail(ResultKind.FAILED)
        }
        log("undo", result.kind)
        return result
    }

    private fun showClock(spec: UndoSpec, action: String): DeviceActionResult {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return DeviceActionResult(ResultKind.OK, DeviceActionMessages.undone(spec))
    }

    private fun fail(kind: ResultKind) = DeviceActionResult(kind, DeviceActionMessages.refused(kind))

    private fun log(act: String, kind: ResultKind) {
        Log.i(TAG, "action=$act result=${kind.key}")
    }

    /** Taschenlampen-Zustand früh beobachten (Haupt-Looper), damit der Vorzustand beim ersten Aufruf bekannt ist. */
    fun warmUp() {
        try {
            (context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager)?.let { ensureTorchCallback(it) }
        } catch (_: Exception) {
        }
    }

    // ── Taschenlampe ───────────────────────────────────────────────────────────
    private fun flashlight(a: DeviceAction.Flashlight): DeviceActionResult {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return fail(ResultKind.UNSUPPORTED)
        ensureTorchCallback(cm)
        val id = cm.cameraIdList.firstOrNull {
            val c = cm.getCameraCharacteristics(it)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return fail(ResultKind.UNSUPPORTED)
        val previous = torchState
        return try {
            cm.setTorchMode(id, a.on)
            torchState = a.on
            DeviceActionResult(ResultKind.OK, DeviceActionMessages.ok(a), DeviceActionUndo.forFlashlight(previous))
        } catch (e: CameraAccessException) {
            fail(ResultKind.FAILED)
        }
    }

    // ── Lautstärke ─────────────────────────────────────────────────────────────
    private fun streamOf(c: VolumeChannel): Int = when (c) {
        VolumeChannel.MEDIA -> AudioManager.STREAM_MUSIC
        VolumeChannel.RING -> AudioManager.STREAM_RING
        VolumeChannel.ALARM -> AudioManager.STREAM_ALARM
        VolumeChannel.CALL -> AudioManager.STREAM_VOICE_CALL
    }

    private fun volume(a: DeviceAction.Volume): DeviceActionResult {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return fail(ResultKind.UNSUPPORTED)
        val stream = streamOf(a.channel)
        val max = am.getStreamMaxVolume(stream)
        val previous = am.getStreamVolume(stream)
        val flags = AudioManager.FLAG_SHOW_UI // der Nutzer sieht die Änderung am Regler
        when (a.mode) {
            VolumeMode.SET -> am.setStreamVolume(stream, VolumeMath.indexFor(a.percent ?: 0, max), flags)
            VolumeMode.UP -> am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, flags)
            VolumeMode.DOWN -> am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, flags)
            VolumeMode.MUTE -> am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, flags)
            VolumeMode.UNMUTE -> am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, flags)
        }
        if (a.channel == VolumeChannel.ALARM) {
            // Der Wecker-Ton wird nie unter 10 Prozent gedreht, auch nicht über mehrere Antworten mit "leiser".
            val now = am.getStreamVolume(stream)
            val fixed = VolumeMath.clampAlarm(now, max)
            if (fixed != now) am.setStreamVolume(stream, fixed, 0)
        }
        return DeviceActionResult(ResultKind.OK, DeviceActionMessages.ok(a), DeviceActionUndo.forVolume(a.channel, previous, max))
    }

    // ── Paket F: Systemaktionen ────────────────────────────────────────────────
    private fun denied(kind: DeviceActionKind, result: ResultKind): DeviceActionResult =
        if (result == ResultKind.NEEDS_PERMISSION) DeviceActionResult(result, DeviceActionMessages.needsPermission(kind)) else fail(result)

    private fun brightness(a: DeviceAction.SetBrightness): DeviceActionResult {
        SystemPermissions.precheck(a.kind, permissionFacts(context))?.let { return denied(a.kind, it) }
        val cr = context.contentResolver
        val previousRaw = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1)
        val previousAuto = Settings.System.getInt(
            cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        // Bei Automatik ist der gespeicherte Wert nicht die Anzeige: heller/dunkler nicht raten
        if (BrightnessMath.needsExplicitValue(a.mode, previousAuto)) {
            return DeviceActionResult(ResultKind.AMBIGUOUS, DeviceActionMessages.brightnessAuto())
        }
        // Ist der Ausgangswert unlesbar, rechnet "heller/dunkler" nicht ins Blaue: ehrlich scheitern
        if (previousRaw < 0 && a.mode != BrightnessMode.SET) return fail(ResultKind.FAILED)
        val target = BrightnessMath.targetPercent(a.mode, a.percent, BrightnessMath.toPercent(previousRaw.coerceAtLeast(0)))
        val ok = Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) &&
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, BrightnessMath.toRaw(target))
        if (!ok) return fail(ResultKind.FAILED)
        return DeviceActionResult(ResultKind.OK, DeviceActionMessages.ok(a), DeviceActionUndo.forBrightness(previousRaw, previousAuto))
    }

    private fun restoreBrightness(spec: UndoSpec.BrightnessTo): DeviceActionResult {
        SystemPermissions.precheck(spec.kind, permissionFacts(context))?.let { return denied(spec.kind, it) }
        val cr = context.contentResolver
        // erst der Wert, dann der Modus (Automatik darf den Wert danach wieder übernehmen)
        val ok = Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, spec.raw) &&
            Settings.System.putInt(
                cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (spec.automatic) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
        return if (ok) DeviceActionResult(ResultKind.OK, DeviceActionMessages.undone(spec)) else fail(ResultKind.FAILED)
    }

    private fun notificationManager(): NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    /**
     * Setzt den Filter und liest ihn zurück (Android 15 kann nur eine App-Regel anlegen): stimmt er nach der Wartezeit
     * nicht, ist es FAILED. ACHTUNG: Thread.sleep darf nicht auf dem Main-Thread laufen — die Overlay-Wege führen
     * deshalb auf einem Worker aus; nur die Rückgängig-Taste (Klick) läuft auf Main und wartet dann höchstens
     * [DndLogic.READBACK_TRIES_MAIN] x 100 ms.
     */
    private fun applyFilter(nm: NotificationManager, filter: Int): Boolean {
        nm.setInterruptionFilter(filter)
        val tries = if (Looper.myLooper() == Looper.getMainLooper()) DndLogic.READBACK_TRIES_MAIN else DndLogic.READBACK_TRIES
        for (i in 0 until tries) {
            if (DndLogic.applied(filter, nm.currentInterruptionFilter)) return true
            try {
                Thread.sleep(DndLogic.READBACK_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return DndLogic.applied(filter, nm.currentInterruptionFilter)
    }

    private fun dnd(a: DeviceAction.Dnd): DeviceActionResult {
        SystemPermissions.precheck(a.kind, permissionFacts(context))?.let { return denied(a.kind, it) }
        val nm = notificationManager() ?: return fail(ResultKind.UNSUPPORTED)
        val previous = nm.currentInterruptionFilter
        if (!applyFilter(nm, DndLogic.targetFilter(a.on))) {
            return DeviceActionResult(ResultKind.FAILED, DeviceActionMessages.dndNotApplied())
        }
        return DeviceActionResult(ResultKind.OK, DeviceActionMessages.ok(a), DeviceActionUndo.forDnd(previous))
    }

    private fun restoreDnd(spec: UndoSpec.DndTo): DeviceActionResult {
        SystemPermissions.precheck(spec.kind, permissionFacts(context))?.let { return denied(spec.kind, it) }
        val nm = notificationManager() ?: return fail(ResultKind.UNSUPPORTED)
        return if (applyFilter(nm, spec.filter)) DeviceActionResult(ResultKind.OK, DeviceActionMessages.undone(spec))
        else DeviceActionResult(ResultKind.FAILED, DeviceActionMessages.dndNotApplied())
    }

    private fun lockScreen(a: DeviceAction): DeviceActionResult {
        SystemPermissions.precheck(a.kind, permissionFacts(context))?.let { return denied(a.kind, it) }
        val svc = DonnaAccessibilityService.instance ?: return denied(a.kind, ResultKind.NEEDS_PERMISSION)
        return if (svc.performSystemAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) {
            DeviceActionResult(ResultKind.OK, DeviceActionMessages.ok(a))
        } else {
            fail(ResultKind.FAILED)
        }
    }

    /**
     * Screenshot (Review Stufe 3, Datenschutz): NUR die Systemaktion GLOBAL_ACTION_TAKE_SCREENSHOT; das System legt das
     * Bild in die Galerie. Donna nimmt nie Pixel auf und lädt/loggt nie ein Bild; seit #219 liest nur die Anzeige der Vorschau das fertige Bild LOKAL (MediaStore-URI/Thumbnail).
     * Ablauf: (1) Voraussetzungen, (2) Vordergrund-Paket SYNCHRON prüfen — gesperrt/sensibel/unbekannt = BLOCKED, nichts
     * geschieht; (3) nach [ScreenshotGuard.DELAY_MS] (Handler, der Aufrufer wird nicht blockiert) das Paket NOCHMAL prüfen
     * und erst dann auslösen. Der Rückkanal meldet erst NACH diesem zweiten Schritt (OK nur, wenn das System die Aktion
     * angenommen hat; sonst BLOCKED/FAILED). Mike hört sofort "ich löse gleich aus"; scheitert es danach, sagt ihm ein
     * Toast ehrlich, dass nichts aufgenommen wurde (auch ohne [onFinal], z. B. beim Aufruf über run() ohne Rückkanal).
     * Restrisiko: wechselt die App NACH der zweiten Prüfung und vor dem Systemschuss (Millisekunden), ist das nicht
     * auszuschließen; darum ist die Verzögerung kurz ([ScreenshotGuard.DELAY_MS], nicht erhöhen).
     */
    private fun screenshot(a: DeviceAction, onFinal: ((ResultKind) -> Unit)?): DeviceActionResult {
        SystemPermissions.precheck(a.kind, permissionFacts(context))?.let { return denied(a.kind, it) }
        val svc = DonnaAccessibilityService.instance ?: return denied(a.kind, ResultKind.NEEDS_PERMISSION)
        when (screenshotDecision(svc)) {
            ScreenshotDecision.Allowed -> Unit
            ScreenshotDecision.Blocked -> return DeviceActionResult(ResultKind.BLOCKED, DeviceActionMessages.screenshotBlocked())
            ScreenshotDecision.Unknown -> return DeviceActionResult(ResultKind.BLOCKED, DeviceActionMessages.screenshotUnknown())
        }
        val posted = Handler(Looper.getMainLooper()).postDelayed({
            val triggeredAt = System.currentTimeMillis()
            val kind = triggerScreenshot()
            log("screenshot_final", kind)
            if (kind == ResultKind.OK) announceScreenshot(triggeredAt)
            ScreenshotFlow.lateNotice(kind)?.let { Toast.makeText(context.applicationContext, it, Toast.LENGTH_LONG).show() }
            onFinal?.invoke(kind)
        }, ScreenshotGuard.DELAY_MS)
        if (!posted) return fail(ResultKind.FAILED)
        return DeviceActionResult(ResultKind.OK, DeviceActionMessages.ok(a), reportsLater = onFinal != null)
    }

    /**
     * #219: meldet die URI des neuen Screenshots an den Aufrufer (Chat: RN-Event, Overlay: Vorschau-Karte). Nur die
     * URI aus MediaStore (Metadaten), kein Bild gelesen/hochgeladen/geloggt. Ohne Rückkanal oder Leserecht passiert nichts.
     */
    @Volatile var onScreenshotSaved: ((String) -> Unit)? = null

    private fun announceScreenshot(triggeredAtMs: Long) {
        val cb = onScreenshotSaved ?: return
        Thread {
            ScreenshotPreview.awaitUri(context.applicationContext, triggeredAtMs)?.let { cb(it) }
        }.apply { isDaemon = true; name = "screenshot-preview" }.start()
    }

    /** Zweite Prüfung unmittelbar vor dem Auslösen (der Vordergrund kann sich in der Verzögerung geändert haben). */
    private fun triggerScreenshot(): ResultKind {
        val svc = DonnaAccessibilityService.instance ?: return ResultKind.NEEDS_PERMISSION
        return ScreenshotFlow.finish(screenshotDecision(svc)) {
            svc.performSystemAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
        }
    }

    /** ALLE sichtbaren Anwendungsfenster zählen (auch Banking hinter Donnas Overlay); Label über den PackageManager. */
    private fun screenshotDecision(svc: DonnaAccessibilityService): ScreenshotDecision =
        ScreenshotGuard.decide(svc.visibleAppPackages(), blocked, context.packageName, ::labelOf)

    private fun labelOf(pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        ""
    }

    private fun openCamera(a: DeviceAction.OpenCamera): DeviceActionResult {
        val action = if (a.capture == CameraCapture.VIDEO) MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Ohne Auswahldialog ("Vorgang abschliessen mit"): direkt die System-Kamera, nie Drittanbieter (z. B. Snapchat)
        val pm = context.packageManager
        val cands = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map {
            val f = it.activityInfo.applicationInfo.flags
            CameraCandidate(it.activityInfo.packageName, (f and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0)
        }
        val def = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        val pkg = CameraPicker.choose(cands, def) ?: return DeviceActionResult(ResultKind.UNSUPPORTED, DeviceActionMessages.noSystemCamera())
        context.startActivity(intent.setPackage(pkg)) // ActivityNotFound -> UNSUPPORTED (execute)
        return DeviceActionResult(ResultKind.OK, DeviceActionMessages.ok(a))
    }

    // ── App öffnen ─────────────────────────────────────────────────────────────
    private fun launchableApps(): List<AppEntry> {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0).map {
            AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName)
        }
    }

    private fun openApp(a: DeviceAction.OpenApp): DeviceActionResult {
        return when (val m = AppResolver.resolve(a.app, launchableApps(), blocked)) {
            is AppMatch.NotFound -> DeviceActionResult(ResultKind.UNSUPPORTED, DeviceActionMessages.notFoundApp())
            is AppMatch.Blocked -> DeviceActionResult(ResultKind.BLOCKED, DeviceActionMessages.blockedApp(m.label))
            is AppMatch.Ambiguous -> DeviceActionResult(ResultKind.AMBIGUOUS, DeviceActionMessages.ambiguous(m.labels))
            is AppMatch.Found -> {
                val launch = context.packageManager.getLaunchIntentForPackage(m.entry.packageName)
                    ?: return DeviceActionResult(ResultKind.UNSUPPORTED, DeviceActionMessages.notFoundApp())
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                DeviceActionResult(ResultKind.OK, DeviceActionMessages.ok(DeviceAction.OpenApp(m.entry.label)))
            }
        }
    }

    // ── Einstellungsseiten ─────────────────────────────────────────────────────
    private fun openSettings(a: DeviceAction.OpenSettings): DeviceActionResult {
        val page = a.page
        val action = if (page.panel && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            page.fallbackAction ?: page.intentAction
        } else {
            page.intentAction
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (page.ownPackage) {
            when (page) {
                SettingsPage.NOTIFICATIONS -> intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                SettingsPage.APP_INFO -> intent.data = Uri.fromParts("package", context.packageName, null)
                else -> Unit
            }
        }
        context.startActivity(intent)
        return DeviceActionResult(ResultKind.OK, DeviceActionMessages.ok(a))
    }

    // ── Timer / Wecker ohne Uhr-App-Fenster (SKIP_UI) ──────────────────────────
    private fun timer(a: DeviceAction.SetTimer): DeviceActionResult {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, a.minutes * 60)
            if (a.label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, a.label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        // #247: Ablaufzeit fürs Timer-Widget merken (Abbruch in der Uhr-App erkennt Donna nicht, siehe TimerWidgetLogic)
        com.yourcompany.donna.widget.TimerWidgetProvider.onTimerSet(context, a.minutes, a.label)
        return DeviceActionResult(ResultKind.OK, DeviceActionMessages.ok(a), DeviceActionUndo.forAction(a))
    }

    private fun alarm(a: DeviceAction.SetAlarm): DeviceActionResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, a.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, a.minute)
            if (a.label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, a.label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return DeviceActionResult(ResultKind.OK, DeviceActionMessages.ok(a), DeviceActionUndo.forAction(a))
    }

    companion object {
        private const val TAG = "DeviceActions"

        /** Tatsachen für die Berechtigungsentscheidung (Einstellungen-Status und Prüfung vor der Ausführung). */
        fun permissionFacts(ctx: Context): PermissionFacts = PermissionFacts(
            sdk = Build.VERSION.SDK_INT,
            canWriteSettings = try { Settings.System.canWrite(ctx) } catch (_: Exception) { false },
            policyAccess = try {
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.isNotificationPolicyAccessGranted == true
            } catch (_: Exception) { false },
            accessibilityActive = DonnaAccessibilityService.instance != null,
            calendarGranted = listOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR)
                .all { ctx.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED },
        )

        /** EINE Ablage für alle Wege (Chat, Blasen-Overlay, Altweg): die letzten 3 Aktionen, je mit Kennung. */
        private val STORE = UndoStore()

        /** Unbekannt (null), bis der Callback oder unser eigenes Schalten den Zustand kennt. */
        @Volatile private var torchState: Boolean? = null
        @Volatile private var torchCallbackRegistered: Boolean = false

        /**
         * Der Callback läuft über den Handler des Haupt-Loopers: der Chat-Weg ruft aus einem Thread ohne Looper,
         * dort würde `registerTorchCallback(cb, null)` eine Ausnahme werfen. Das Flag wird erst NACH der
         * erfolgreichen Registrierung gesetzt, ein Fehlschlag kann beim nächsten Aufruf neu versucht werden.
         */
        @Synchronized
        private fun ensureTorchCallback(cm: CameraManager) {
            if (torchCallbackRegistered) return
            try {
                cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torchState = enabled }
                }, Handler(Looper.getMainLooper()))
                torchCallbackRegistered = true
            } catch (_: Exception) {
                // nicht registriert: Zustand bleibt unbekannt, kein Rückgängig für die Taschenlampe
            }
        }
    }
}

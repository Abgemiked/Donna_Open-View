package com.yourcompany.donna

import android.app.Activity
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * DonnaSetupModule — Native Brücke des Ersteinrichtungs-Assistenten (OnboardingScreen.tsx).
 *
 * API (JS: NativeModules.DonnaSetupModule, Wrapper: src/native/DonnaSetup.ts):
 *   getSetupStatus(): Promise<{[key]: {granted, available, blocked}}>
 *     Prüft alle 17 Schritte (siehe SetupSteps). `available=false` → auf diesem Gerät /
 *     API-Level nicht anwendbar (granted ist dann true, außer Health Connect: false).
 *     `blocked=true` → Runtime-Permission dauerhaft abgelehnt; "Einrichten" öffnet dann
 *     die App-Einstellungen statt eines wirkungslosen Dialogs.
 *   requestStep(key): Promise<{key, granted, available, blocked}>
 *     Öffnet Permission-Dialog bzw. passenden Settings-Intent und resolved NACH der
 *     Rückkehr in die App mit dem frisch geprüften Status des Schritts.
 *   openBixbyGuide(): öffnet die vorhandene BixbyOnboardingActivity (Hinweis, kein Schritt).
 *
 * Wiederverwendet: HealthConnectPermissionActivity / HealthConnectManager (Schritt health),
 * BixbyOnboardingActivity, ActivityRecognitionService, GeofenceManager, TrackingService.
 *
 * Rückkehr-Erkennung: Runtime-Permissions über PermissionAwareActivity-Callback, alle
 * Settings-/Rollen-/Health-Intents über startActivityForResult (onActivityResult) und
 * zusätzlich onHostResume nach einem Pause (manche Settings-Seiten liefern das Result
 * sofort). JS prüft zusätzlich bei AppState→active neu.
 */
class DonnaSetupModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), ActivityEventListener, LifecycleEventListener {

    private data class StepState(
        val granted: Boolean,
        val available: Boolean = true,
        val blocked: Boolean = false,
    )

    // Nicht abgefangene Fehler einer Coroutine dürfen weder die App beenden noch einen Promise hängen lassen
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "Unbehandelter Fehler in DonnaSetupModule", e) }
    )
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Welche dienst-relevanten Schritte beim letzten Status erteilt waren (idempotenter Dienst-Start). */
    @Volatile private var servicesSignature = ""

    // Es läuft immer nur EIN Schritt (System-Dialoge sind modal). Zugriff über @Synchronized.
    private var pendingKey: String? = null
    private var pendingPromise: Promise? = null
    private var pendingRuntime = false
    private var pausedSinceLaunch = false

    init {
        reactContext.addActivityEventListener(this)
        reactContext.addLifecycleEventListener(this)
    }

    override fun getName() = "DonnaSetupModule"

    override fun onCatalystInstanceDestroy() {
        reactContext.removeActivityEventListener(this)
        reactContext.removeLifecycleEventListener(this)
        scope.cancel()
        super.onCatalystInstanceDestroy()
    }

    // ── JS-API ───────────────────────────────────────────────────────────────

    @ReactMethod
    fun getSetupStatus(promise: Promise) {
        scope.launch {
            try {
                val result = Arguments.createMap()
                val grantedServiceSteps = mutableListOf<String>()
                for (key in SetupSteps.ALL_KEYS) {
                    // Pro Schritt isoliert: ein kaputter Check darf nicht alle 17 Schritte lahmlegen
                    val state = safeStepState(key)
                    result.putMap(key, stateMap(key, state))
                    if (state.granted && key in SERVICE_STEPS) grantedServiceSteps.add(key)
                }
                // Dienste auch starten, wenn der Nutzer das Recht außerhalb des Wizards erteilt hat
                // (z. B. direkt in den Systemeinstellungen) — nur bei Änderung, nicht bei jedem Refresh.
                val signature = grantedServiceSteps.joinToString(",")
                if (signature != servicesSignature) {
                    servicesSignature = signature
                    if (signature.isNotEmpty()) startPermittedServices(reactContext)
                }
                promise.resolve(result)
            } catch (e: Exception) {
                Log.e(TAG, "getSetupStatus fehlgeschlagen", e)
                promise.reject("E_STATUS", e.message ?: "Status konnte nicht geprüft werden", e)
            }
        }
    }

    @ReactMethod
    fun requestStep(key: String, promise: Promise) {
        if (key !in SetupSteps.ALL_KEYS) {
            promise.reject("E_UNKNOWN_STEP", "Unbekannter Schritt: $key")
            return
        }
        val activity = reactContext.currentActivity
        if (activity == null) {
            promise.reject("E_NO_ACTIVITY", "Donna ist gerade nicht im Vordergrund")
            return
        }
        scope.launch {
            try {
                val state = safeStepState(key)
                if (state.granted) {
                    promise.resolve(stateMap(key, state))
                    return@launch
                }
                if (!state.available) {
                    promise.reject("E_UNAVAILABLE", "Auf diesem Gerät nicht verfügbar")
                    return@launch
                }
                val permissions = SetupSteps.runtimePermissions(key, Build.VERSION.SDK_INT)
                if (permissions != null) {
                    requestRuntime(activity, key, permissions, promise)
                } else {
                    launchSpecial(activity, key, promise)
                }
            } catch (e: Exception) {
                Log.e(TAG, "requestStep($key) fehlgeschlagen", e)
                promise.reject("E_REQUEST", e.message ?: "Schritt konnte nicht gestartet werden", e)
            }
        }
    }

    @ReactMethod
    fun openBixbyGuide(promise: Promise) {
        val activity = reactContext.currentActivity
        if (activity == null) {
            promise.reject("E_NO_ACTIVITY", "Donna ist gerade nicht im Vordergrund")
            return
        }
        UiThreadUtil.runOnUiThread {
            try {
                activity.startActivity(Intent(activity, BixbyOnboardingActivity::class.java))
                promise.resolve(null)
            } catch (e: Exception) {
                promise.reject("E_BIXBY", e.message ?: "Anleitung konnte nicht geöffnet werden", e)
            }
        }
    }

    // Pflicht-Methoden für NativeEventEmitter-Kompatibilität (JS könnte später Events abonnieren)
    @ReactMethod
    fun addListener(@Suppress("UNUSED_PARAMETER") eventName: String) {}

    @ReactMethod
    fun removeListeners(@Suppress("UNUSED_PARAMETER") count: Int) {}

    // ── Status-Prüfung ───────────────────────────────────────────────────────

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(reactContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun prefs() = reactContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun wasRequested(key: String) = prefs().getBoolean("requested_$key", false)

    private fun markRequested(key: String) {
        prefs().edit().putBoolean("requested_$key", true).apply()
    }

    /**
     * Lockout-Schutz: Ein hängender Check (v. a. Health Connect) darf weder die gesamte Statusabfrage
     * noch einen Request blockieren. Timeout oder Fehler → "offen, verfügbar" + Log.
     */
    private suspend fun safeStepState(key: String): StepState {
        return try {
            withTimeoutOrNull(STEP_TIMEOUT_MS) { stepState(key) }
                ?: StepState(granted = false, available = true).also {
                    Log.w(TAG, "Status von $key: Timeout nach ${STEP_TIMEOUT_MS}ms")
                }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Status von $key nicht prüfbar: ${e.message}", e)
            StepState(granted = false, available = true)
        }
    }

    private suspend fun stepState(key: String): StepState {
        val sdk = Build.VERSION.SDK_INT

        val allPermissions = SetupSteps.runtimePermissions(key, sdk)
        if (key == SetupSteps.NOTIFICATIONS) {
            // Zusätzlich zur Permission zählt der System-Schalter "Benachrichtigungen zulassen"
            val enabled = NotificationManagerCompat.from(reactContext).areNotificationsEnabled()
            val permissionOk = allPermissions.isNullOrEmpty() || allPermissions.all { has(it) }
            return when {
                permissionOk && enabled -> StepState(granted = true, available = !allPermissions.isNullOrEmpty())
                // Permission erteilt, aber in den Systemeinstellungen abgeschaltet → dorthin führen
                permissionOk -> StepState(granted = false, blocked = true)
                else -> StepState(granted = false, blocked = isBlocked(key, allPermissions.orEmpty()))
            }
        }
        if (allPermissions != null) {
            if (allPermissions.isEmpty()) return StepState(granted = true, available = false)
            val needed = SetupSteps.requiredForGranted(key, sdk) ?: allPermissions
            val granted = needed.all { has(it) }
            return StepState(granted = granted, blocked = !granted && isBlocked(key, allPermissions))
        }

        return when (key) {
            SetupSteps.OVERLAY -> StepState(Settings.canDrawOverlays(reactContext))
            SetupSteps.ASSISTANT -> StepState(granted = isAssistantHeld())
            SetupSteps.BATTERY -> {
                val pm = reactContext.getSystemService(PowerManager::class.java)
                StepState(pm?.isIgnoringBatteryOptimizations(reactContext.packageName) == true)
            }
            SetupSteps.EXACT_ALARM ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val am = reactContext.getSystemService(AlarmManager::class.java)
                    StepState(am?.canScheduleExactAlarms() == true)
                } else {
                    StepState(granted = true, available = false)
                }
            SetupSteps.ACCESSIBILITY -> StepState(isAccessibilityEnabled())
            SetupSteps.NOTIF_LISTENER -> StepState(
                NotificationManagerCompat.getEnabledListenerPackages(reactContext)
                    .contains(reactContext.packageName)
            )
            SetupSteps.USAGE_STATS -> StepState(hasUsageStatsAccess())
            SetupSteps.HEALTH -> {
                val available = HealthConnectManager.isAvailable(reactContext)
                StepState(
                    granted = available && HealthConnectManager.hasAllPermissions(reactContext),
                    available = available,
                )
            }
            else -> StepState(granted = false, available = false)
        }
    }

    /** Dauerhaft abgelehnt: schon einmal gefragt, aber Android zeigt keinen Dialog mehr. */
    private fun isBlocked(key: String, permissions: List<String>): Boolean {
        val activity = reactContext.currentActivity ?: return false
        val denied = permissions.filter { !has(it) }
        if (denied.isEmpty()) return false
        val rationale = denied.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        return SetupSteps.shouldOpenAppSettings(wasRequested(key), rationale)
    }

    private fun isAssistantHeld(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val held = runCatching {
                reactContext.getSystemService(RoleManager::class.java)
                    ?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
            }.getOrDefault(false)
            if (held) return true
        }
        // Fallback (z. B. Samsung-Pfad ohne Rollen-Sync): Systemeinstellung des Standard-Assistenten
        return listOf("assistant", "voice_interaction_service").any { setting ->
            runCatching { Settings.Secure.getString(reactContext.contentResolver, setting) }
                .getOrNull()
                ?.substringBefore('/') == reactContext.packageName
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = runCatching {
            Settings.Secure.getString(
                reactContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull()
        return SetupSteps.isComponentEnabled(
            enabled,
            reactContext.packageName,
            DonnaAccessibilityService::class.java.name,
        )
    }

    private fun hasUsageStatsAccess(): Boolean {
        val appOps = reactContext.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                reactContext.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                reactContext.packageName,
            )
        }
        val permissionGranted = reactContext.checkCallingOrSelfPermission(
            android.Manifest.permission.PACKAGE_USAGE_STATS
        ) == PackageManager.PERMISSION_GRANTED
        return SetupSteps.usageStatsGranted(mode, permissionGranted)
    }

    private fun stateMap(key: String, state: StepState): WritableMap =
        Arguments.createMap().apply {
            putString("key", key)
            putBoolean("granted", state.granted)
            putBoolean("available", state.available)
            putBoolean("blocked", state.blocked)
        }

    // ── Anfragen ─────────────────────────────────────────────────────────────

    /** Runtime-Permission(s) per System-Dialog; bei dauerhafter Ablehnung App-Einstellungen. */
    private fun requestRuntime(
        activity: Activity,
        key: String,
        permissions: List<String>,
        promise: Promise,
    ) {
        if (key == SetupSteps.LOCATION_BG && !has(android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            promise.reject("E_PRECONDITION", "Zuerst „Standort“ erlauben")
            return
        }
        val missing = permissions.filter { !has(it) }
        if (key == SetupSteps.NOTIFICATIONS && missing.isEmpty()) {
            // Permission da, aber Benachrichtigungen systemweit für Donna aus → Benachrichtigungs-Einstellungen
            launchForResult(
                activity, key, promise,
                listOf(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, reactContext.packageName),
                    appDetailsIntent(),
                ),
            )
            return
        }
        val rationale = missing.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        if (SetupSteps.shouldOpenAppSettings(wasRequested(key), rationale)) {
            launchForResult(activity, key, promise, listOf(appDetailsIntent()))
            return
        }
        // Standort: immer FINE + COARSE gemeinsam (sonst ignoriert Android 12+ den Upgrade "ungefähr" → "genau")
        val toRequest = SetupSteps.permissionsToRequest(key, Build.VERSION.SDK_INT, missing)
        val permissionActivity = activity as? PermissionAwareActivity
        if (permissionActivity == null) {
            promise.reject("E_NO_PERMISSION_ACTIVITY", "Berechtigungs-Dialog nicht verfügbar")
            return
        }
        markRequested(key)
        setPending(key, promise, runtime = true)
        UiThreadUtil.runOnUiThread {
            try {
                permissionActivity.requestPermissions(
                    toRequest.toTypedArray(),
                    REQ_PERMISSION,
                    PermissionListener { requestCode, _, _ ->
                        if (requestCode == REQ_PERMISSION) finishPending()
                        true
                    },
                )
            } catch (e: Exception) {
                abortPending(promise, "E_REQUEST", e)
            }
        }
    }

    /** Spezialzugriffe: passender Settings-/Rollen-/Health-Intent via startActivityForResult. */
    private fun launchSpecial(activity: Activity, key: String, promise: Promise) {
        val pkgUri = Uri.parse("package:${reactContext.packageName}")
        val intents: List<Intent> = when (key) {
            SetupSteps.OVERLAY -> listOf(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri),
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            )
            SetupSteps.ASSISTANT -> assistantIntents(activity, key)
            SetupSteps.BATTERY -> listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
            SetupSteps.EXACT_ALARM -> listOf(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri),
                appDetailsIntent(),
            )
            SetupSteps.ACCESSIBILITY -> listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            SetupSteps.NOTIF_LISTENER -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        ComponentName(reactContext, DonnaNotificationListener::class.java).flattenToString(),
                    )
                } else null,
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            )
            SetupSteps.USAGE_STATS -> listOf(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkgUri),
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            )
            SetupSteps.HEALTH -> listOf(Intent(activity, HealthConnectPermissionActivity::class.java))
            else -> emptyList()
        }
        launchForResult(activity, key, promise, intents)
    }

    /**
     * Digitaler Assistent: erster Versuch = Rollen-Dialog (ROLE_ASSISTANT). Hat der Nutzer ihn
     * schon einmal gesehen und Donna ist trotzdem nicht Assistent (abgelehnt / Samsung ohne
     * Rollen-Sync), führt jeder weitere Klick direkt in "Standard-Apps → Digitaler Assistent".
     */
    private fun assistantIntents(activity: Activity, key: String): List<Intent> {
        val fallback = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || wasRequested(key)) return listOf(fallback)
        val roleManager = activity.getSystemService(RoleManager::class.java)
        val roleAvailable = runCatching {
            roleManager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true
        }.getOrDefault(false)
        if (!roleAvailable) return listOf(fallback)
        return listOf(roleManager!!.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), fallback)
    }

    private fun appDetailsIntent() = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${reactContext.packageName}"),
    )

    /** Startet den ersten startbaren Intent; resolved bei Rückkehr (onActivityResult / onHostResume). */
    private fun launchForResult(activity: Activity, key: String, promise: Promise, intents: List<Intent>) {
        UiThreadUtil.runOnUiThread {
            // Activity kann zwischen Klick und UI-Thread gewechselt/beendet worden sein → frisch lesen
            val host = reactContext.currentActivity ?: activity
            if (host.isFinishing) {
                promise.reject("E_NO_ACTIVITY", "Donna ist gerade nicht im Vordergrund")
                return@runOnUiThread
            }
            setPending(key, promise, runtime = false)
            val started = intents.any { intent ->
                try {
                    host.startActivityForResult(intent, REQ_SETTINGS)
                    true
                } catch (e: Exception) {
                    // ActivityNotFound, Security, BadParcelable … → nächster Fallback-Intent
                    Log.w(TAG, "Intent ${intent.action} nicht startbar: ${e.message}")
                    false
                }
            }
            if (started) {
                markRequested(key)
            } else {
                abortPending(promise, "E_NO_INTENT", ActivityNotFoundException("Kein passender Einstellungs-Dialog gefunden"))
            }
        }
    }

    // ── Pending-Verwaltung ───────────────────────────────────────────────────

    @Synchronized
    private fun setPending(key: String, promise: Promise, runtime: Boolean) {
        val oldKey = pendingKey
        val oldPromise = pendingPromise
        pendingKey = key
        pendingPromise = promise
        pendingRuntime = runtime
        pausedSinceLaunch = false
        // Vorherigen, noch offenen Request nicht hängen lassen
        if (oldKey != null && oldPromise != null) resolveWithFreshState(oldKey, oldPromise)
    }

    @Synchronized
    private fun takePending(): Pair<String, Promise>? {
        val key = pendingKey
        val promise = pendingPromise
        pendingKey = null
        pendingPromise = null
        pendingRuntime = false
        pausedSinceLaunch = false
        return if (key != null && promise != null) key to promise else null
    }

    private fun finishPending() {
        val (key, promise) = takePending() ?: return
        resolveWithFreshState(key, promise)
    }

    @Synchronized
    private fun clearPendingIf(promise: Promise) {
        if (pendingPromise === promise) {
            pendingKey = null
            pendingPromise = null
            pendingRuntime = false
            pausedSinceLaunch = false
        }
    }

    private fun abortPending(promise: Promise, code: String, error: Exception) {
        clearPendingIf(promise)
        promise.reject(code, error.message ?: "Fehler", error)
    }

    private fun resolveWithFreshState(key: String, promise: Promise) {
        scope.launch {
            try {
                val state = safeStepState(key)
                if (state.granted) onStepGranted(key)
                promise.resolve(stateMap(key, state))
            } catch (e: Exception) {
                promise.reject("E_STATUS", e.message ?: "Status konnte nicht geprüft werden", e)
            }
        }
    }

    /** Nach einem Grant die abhängigen Dienste sofort starten (kein App-Neustart nötig). */
    private fun onStepGranted(key: String) {
        when (key) {
            SetupSteps.LOCATION, SetupSteps.LOCATION_BG, SetupSteps.ACTIVITY, SetupSteps.HEALTH ->
                startPermittedServices(reactContext)
        }
    }

    // ── Activity-/Lifecycle-Events ───────────────────────────────────────────

    override fun onActivityResult(activity: Activity?, requestCode: Int, resultCode: Int, data: Intent?) {
        // Nur nach echter Pause auflösen: Settings-Seiten in einem neuen Task liefern ihr Result
        // sofort (CANCELED), noch bevor der Nutzer etwas getan hat. Sonst übernimmt onHostResume.
        val paused = synchronized(this) { pausedSinceLaunch }
        if (requestCode == REQ_SETTINGS && paused) finishPending()
    }

    override fun onNewIntent(intent: Intent?) {}

    override fun onHostResume() {
        val (promise, runtime) = synchronized(this) {
            if (pendingKey != null && pausedSinceLaunch) pendingPromise to pendingRuntime else null to false
        }
        if (promise == null) return
        if (!runtime) {
            finishPending()
        } else {
            // Watchdog: RN teilt EINEN PermissionListener mit anderen Modulen (Chat/Voice/Kontakte) —
            // kommt unser Callback nie an, bliebe der Schritt sonst ewig "busy".
            // Normalfall: onRequestPermissionsResult kam VOR onResume, pending ist dann schon leer.
            mainHandler.postDelayed({
                val stillPending = synchronized(this) { pendingPromise === promise }
                if (stillPending) finishPending()
            }, RUNTIME_WATCHDOG_MS)
        }
    }

    override fun onHostPause() {
        synchronized(this) { if (pendingKey != null) pausedSinceLaunch = true }
    }

    override fun onHostDestroy() {}

    companion object {
        private const val TAG = "DonnaSetupModule"
        private const val PREFS = "donna_setup"
        private const val REQ_PERMISSION = 7421
        private const val REQ_SETTINGS = 7422
        private const val RUNTIME_WATCHDOG_MS = 800L
        private const val STEP_TIMEOUT_MS = 3000L

        /** Schritte, deren Grant einen Hintergrunddienst startet (siehe startPermittedServices). */
        private val SERVICE_STEPS = setOf(
            SetupSteps.LOCATION, SetupSteps.LOCATION_BG, SetupSteps.ACTIVITY, SetupSteps.HEALTH,
        )

        /**
         * Startet alle permission-abhängigen Hintergrunddienste, deren Berechtigung bereits erteilt ist.
         * Ersetzt die früheren Auto-Prompts in MainActivity.onCreate(): Dienste starten nur noch,
         * wenn der Wizard (oder der Nutzer vorher) die Berechtigung erteilt hat — nie mit Dialog.
         */
        fun startPermittedServices(context: Context) {
            val hasLocation = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            // Jeder Dienst einzeln abgesichert: ein Fehler darf die übrigen nicht verhindern
            // (und nie MainActivity.onCreate zum Absturz bringen). Alle Aufrufe sind idempotent.
            if (hasLocation) {
                runCatching { TrackingService.start(context) }
                    .onFailure { Log.w(TAG, "TrackingService-Start fehlgeschlagen: ${it.message}") }
            }
            runCatching { ActivityRecognitionService.start(context) }
                .onFailure { Log.w(TAG, "ActivityRecognition-Start fehlgeschlagen: ${it.message}") }
            runCatching { GeofenceManager.start(context) }
                .onFailure { Log.w(TAG, "Geofencing-Start fehlgeschlagen: ${it.message}") }
            runCatching { HealthConnectManager.syncIfNeeded(context) }
                .onFailure { Log.w(TAG, "Health-Sync-Start fehlgeschlagen: ${it.message}") }
        }
    }
}

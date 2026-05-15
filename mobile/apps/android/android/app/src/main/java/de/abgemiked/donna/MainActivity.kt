package com.yourcompany.donna

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import android.app.AlertDialog
import android.app.KeyguardManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONObject

class MainActivity : ReactActivity() {

    private var isUnlocked = false

    override fun getMainComponentName(): String = "DonnaAndroid"

    override fun createReactActivityDelegate(): ReactActivityDelegate =
        DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

    // ── Intent Handling (BUG-1 Fix: Notification-Tap) ────────────────────────

    /**
     * Aufgerufen wenn die App bereits läuft und ein neuer Intent eingeht
     * (z.B. Notification-Tap während App im Vordergrund/Background).
     * Ohne Override würde der alte Intent bestehen bleiben und JS bekommt
     * den Notification-Extra nicht mit.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // DONNA-135: App war bereits offen → proaktive Nachricht via Event an React Native senden
        emitProactiveChatEventIfNeeded(intent)
    }

    // ── Biometric Lock ───────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Battery-Optimierung immer zuerst — auch vor Pairing, sonst wird App beim
        // TOTP-Request eingefroren (Samsung FreecessHandler) und Pairing schlägt fehl
        requestBatteryOptimizationExemption()
        // DONNA-103: Pairing-Check — vor allem anderen
        if (!TokenStore.hasToken(this)) {
            PairingActivity.start(this)
            finish()
            return
        }
        // Biometrics nur wenn Gerät beim App-Start gesperrt war (Overlay vom Sperrbildschirm).
        // Ist das Gerät bereits entsperrt, wird kein Biometrics-Dialog gezeigt.
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!isUnlocked && km.isKeyguardLocked) showBiometricLock()
        // DONNA-13: NtfyService sofort starten — unabhängig von Location-Permissions
        NtfyService.start(this)
        // DONNA-13: Akku-Optimierungs-Ausnahme — hält NtfyService auch bei Samsung Doze am Leben
        requestBatteryOptimizationExemption()
        requestTrackingPermissions()  // startet auch WakeWordService nach RECORD_AUDIO-Grant
        promptAccessibilityServiceIfNeeded()
        // DONNA-120: Health Connect — täglicher Sync beim App-Start
        HealthConnectManager.syncIfNeeded(this)
        // DONNA-121: Geofencing starten (benötigt ACCESS_FINE_LOCATION + ACCESS_BACKGROUND_LOCATION)
        GeofenceManager.start(this)
        // DONNA-122: Notification-Listener-Check — einmaliger Toast falls nicht aktiviert
        checkNotificationListenerAccess()
        // DONNA-123: MediaSessionObserver initialisieren (initialer Refresh)
        MediaSessionObserver.refresh(this)
        // DONNA-124: Samsung Health Bridge — versucht Samsung Health SDK zu laden, Fallback auf Health Connect
        android.os.Handler(mainLooper).postDelayed({
            Thread { SamsungHealthBridge.syncIfAvailable(this) }.start()
        }, 2_000)
        // DONNA-126: Bixby Onboarding — einmalig beim ersten Start anzeigen
        BixbyOnboardingActivity.showIfNeeded(this)
        // DONNA-135: App wurde kalt gestartet durch Notification-Tap → Intent prüfen.
        // scheduleProactiveChatEmit() übernimmt Retry-Loop intern (bis 3s / 200ms-Schritte).
        // DONNA-198 v4: SharedPreferences wird NICHT mehr automatisch geleert.
        // JS-Layer ist Source-of-Truth: consumeProactiveFromPrefs() in App.tsx liest
        // atomar via getAndClear() — verhindert Race zwischen MainActivity-clear und JS-read.
        // Beim allerersten Start nach Install ist SharedPreferences eh leer (kein Notification-Tap).
        emitProactiveChatEventIfNeeded(intent)
    }

    // ── Proaktiver Chat via Notification-Tap (DONNA-135) ─────────────────────────

    /**
     * Prüft ob der Intent Extras für einen proaktiven Chat enthält und startet
     * eine Retry-Schleife bis der RN-Context bereit ist (max 3s / 15 Versuche).
     * Bei Kaltstart braucht React Native bis zu 2s — einmaliges 800ms-Delay
     * reicht nicht zuverlässig.
     */
    private fun emitProactiveChatEventIfNeeded(intent: Intent?) {
        val openNewChat = intent?.getBooleanExtra("open_new_chat", false) ?: false
        val proactiveMsg = intent?.getStringExtra("donna_proactive_message")
        if (!openNewChat || proactiveMsg.isNullOrBlank()) return
        // DONNA-147: Extras nach Consume entfernen — verhindert Doppel-Emit bei
        // Activity-Recreation (Orientation-Change, Theme-Change). setIntent() in
        // onNewIntent() persistiert den Intent — ohne removeExtra würde onCreate()
        // nach Recreation die gleiche Nachricht ein zweites Mal senden.
        val sessionId = intent.getStringExtra("session_id")
        intent.removeExtra("donna_proactive_message")
        intent.removeExtra("open_new_chat")
        intent.removeExtra("session_id")
        // DONNA-198: session_id als JSON mitgeben damit JS-Schicht korrekte Session nutzt
        val payload = JSONObject().apply {
            put("message", proactiveMsg)
            if (!sessionId.isNullOrBlank()) put("session_id", sessionId)
        }.toString()
        scheduleProactiveChatEmit(payload, attempts = 0)
    }

    private fun scheduleProactiveChatEmit(payload: String, attempts: Int) {
        if (isDestroyed || isFinishing) return  // Guard: Activity bereits weg
        val reactContext = reactInstanceManager?.currentReactContext
        if (reactContext != null) {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                ?.emit("donna_open_proactive_chat", payload)
            android.util.Log.i("MainActivity", "DONNA-198: donna_open_proactive_chat emitted nach ${attempts * 200}ms: ${payload.take(80)}")
            // DONNA-198 v4: SharedPreferences NICHT mehr automatisch leeren.
            // JS-Layer (App.tsx consumeProactiveFromPrefs) liest atomar via getAndClear()
            // sowohl beim Mount als auch bei AppState→active. Single Source of Truth.
        } else if (attempts < 14) {
            // RN-Context noch nicht bereit — 200ms warten und nochmal
            android.os.Handler(mainLooper).postDelayed({
                scheduleProactiveChatEmit(payload, attempts + 1)
            }, 200)
        } else {
            android.util.Log.w("MainActivity", "DONNA-198: RN-Context nach 3s nicht bereit — Event verworfen (SharedPreferences-Fallback aktiv)")
        }
    }

    private fun showBiometricLock() {
        val allowedAuth =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(allowedAuth) !=
            BiometricManager.BIOMETRIC_SUCCESS) return

        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(r); isUnlocked = true
                }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    super.onAuthenticationError(code, msg); finish()
                }
            })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Donna")
                .setSubtitle("Fingerabdruck oder Gesicht")
                .setConfirmationRequired(false)
                .setAllowedAuthenticators(allowedAuth)
                .build()
        )
    }

    // ── Tracking Permissions + Service Start ─────────────────────────────────

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val hasLocation = grants[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                          grants[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val hasAudio = grants[android.Manifest.permission.RECORD_AUDIO] == true

        // DONNA-73: WakeWordService starten sobald RECORD_AUDIO erteilt
        // DONNA-151: Wake-Word deaktiviert
        // if (hasAudio) {
        //     WakeWordService.start(this)
        // }

        if (hasLocation) {
            // Background-Location: auf Android 10+ separat anfragen
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                backgroundLocationLauncher.launch(
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                )
            } else {
                startTrackingService()
            }
        }
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Service starten — auch wenn Background denied (dann nur Foreground-GPS)
        startTrackingService()
    }

    // DONNA-119: Activity Recognition Permission Launcher
    private val activityRecognitionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            ActivityRecognitionService.start(this)
        } else {
            android.util.Log.w("MainActivity", "ACTIVITY_RECOGNITION Permission verweigert — Activity Recognition nicht gestartet")
        }
    }

    private fun requestTrackingPermissions() {
        // Schritt 1: Fine + Coarse Location + RECORD_AUDIO (für Wake-Word)
        locationPermLauncher.launch(
            arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.RECORD_AUDIO,  // DONNA-73: Wake-Word
            )
        )

        // DONNA-119: Activity Recognition Permission (Android 10+ Runtime Permission)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            if (ActivityRecognitionService.hasPermission(this)) {
                ActivityRecognitionService.start(this)
            } else {
                activityRecognitionLauncher.launch(android.Manifest.permission.ACTIVITY_RECOGNITION)
            }
        } else {
            // Unter Android 10: direkt starten, keine Runtime-Permission nötig
            ActivityRecognitionService.start(this)
        }
        // PACKAGE_USAGE_STATS: Special Permission — User muss manuell in Einstellungen aktivieren
        // Wir prüfen ob bereits granted, sonst → Settings öffnen
        if (!hasUsageStatsPermission()) {
            openUsageAccessSettings()
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        // AppOpsManager ist die korrekte Methode — queryUsageStats() gibt bei kurzen
        // Zeitfenstern auch mit erteilter Permission eine leere Liste zurück (kein Bug, nur kein Traffic).
        val appOps = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName,
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun openUsageAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
    }

    private fun startTrackingService() {
        TrackingService.start(this)
    }

    // ── Battery Optimization Exemption (DONNA-13) ─────────────────────────────

    /**
     * Fordert Ausnahme von Android-Akku-Optimierung an.
     * Ohne Ausnahme tötet Samsung One UI (Doze/App-Sleep) den NtfyService
     * nach wenigen Minuten im Hintergrund — START_STICKY allein reicht nicht.
     *
     * Play-Store-Policy: ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS muss mit
     * einer Erklärung für den User versehen werden (AlertDialog davor).
     * Wird nur aufgerufen wenn noch nicht gewährt (isIgnoringBatteryOptimizations).
     */
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return  // bereits gewährt

        // Rationale-Dialog (Play-Store-Policy) — erst erklären, dann System-Dialog öffnen
        AlertDialog.Builder(this)
            .setTitle("Hintergrund-Benachrichtigungen")
            .setMessage(
                "Damit Donna auch bei geschlossener App Push-Nachrichten empfangen kann, " +
                "muss die Akku-Optimierung für Donna deaktiviert werden.\n\n" +
                "Im nächsten Schritt erscheint ein Android-Systemdialog — " +
                "bitte „Zulassen“ wählen."
            )
            .setPositiveButton("Weiter") { _, _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } catch (_: Exception) {
                    // Einige Hersteller blockieren diesen Intent — ignorieren
                }
            }
            .setNegativeButton("Später") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // ── Notification Listener Check (DONNA-122) ───────────────────────────────

    /**
     * Prüft ob Donna im Benachrichtigungszugriff aktiviert ist.
     * Falls nicht → zeigt einmaligen Toast mit Hinweis.
     * User muss manuell in Einstellungen → Benachrichtigungszugriff aktivieren.
     */
    private fun checkNotificationListenerAccess() {
        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(this)
        if (!enabledListeners.contains(packageName)) {
            Toast.makeText(
                this,
                "Donna: Benachrichtigungszugriff aktivieren für Notifications-Feature " +
                "(Einstellungen → Benachrichtigungszugriff → Donna)",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ── Accessibility Service Prompt ──────────────────────────────────────────

    private fun promptAccessibilityServiceIfNeeded() {
        if (!isDonnaAccessibilityEnabled()) {
            // Direkt in Accessibility-Settings öffnen — User muss einmalig aktivieren
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } catch (_: Exception) {}
        }
    }

    private fun isDonnaAccessibilityEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabled.any { info ->
            info.resolveInfo.serviceInfo.packageName == packageName &&
            info.resolveInfo.serviceInfo.name.contains("DonnaAccessibilityService")
        }
    }

    // ── Samsung Side-Key Handler ─────────────────────────────────────────────

    private var lastStemPressTime = 0L
    private val doublePressThresholdMs = 500L

    // ── S Pen Air Action Handler (DONNA-118) ─────────────────────────────────

    private var lastSpenPressTime = 0L
    private val spenDoublePressThresholdMs = 400L

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // S Pen Air Action: KEYCODE_MEDIA_RECORD (126) — vor SideKey prüfen
        if (SpenActionHandler.isSpenKeyCode(keyCode)) {
            handleSpenPress()
            return true
        }
        if (isSamsungSideKey(keyCode)) {
            handleSideKeyPress()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleSpenPress() {
        // Graceful Degradation: auf Geräten ohne S Pen keinen Fehler werfen
        if (!SpenActionHandler.isSpenPresent(this)) return

        val now = System.currentTimeMillis()
        if (now - lastSpenPressTime < spenDoublePressThresholdMs) {
            lastSpenPressTime = 0L
            SpenActionHandler.onDoublePress(this)
        } else {
            lastSpenPressTime = now
            SpenActionHandler.onSinglePress(this)
        }
    }

    private fun isSamsungSideKey(keyCode: Int): Boolean =
        keyCode == SideButtonModule.KEYCODE_STEM_PRIMARY ||
        keyCode == SideButtonModule.KEYCODE_STEM_1

    private fun handleSideKeyPress() {
        val now = System.currentTimeMillis()
        val module = getSideButtonModule() ?: return

        if (now - lastStemPressTime < doublePressThresholdMs) {
            lastStemPressTime = 0L
            module.emitSideButtonDoublePress()
        } else {
            lastStemPressTime = now
            module.emitSideButtonPress()
        }
    }

    private fun getSideButtonModule(): SideButtonModule? =
        reactInstanceManager
            ?.currentReactContext
            ?.getNativeModule(SideButtonModule::class.java)
}

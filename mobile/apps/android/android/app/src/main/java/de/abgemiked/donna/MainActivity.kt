package com.yourcompany.donna

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.app.KeyguardManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
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
        // DONNA-103: Pairing-Check — vor allem anderen.
        // Die Akku-Ausnahme vor dem TOTP-Request holt PairingActivity selbst ein
        // (Samsung FreecessHandler friert sonst das Netzwerk ein).
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
        // Ersteinrichtungs-Assistent (OnboardingScreen / DonnaSetupModule) übernimmt ALLE
        // Berechtigungs-Dialoge (Akku-Ausnahme, Standort, Mikrofon, Eingabehilfe,
        // Benachrichtigungszugriff, Nutzungszugriff, Bixby-Hinweis). Hier starten nur noch
        // Dienste, deren Berechtigung bereits erteilt ist — nie mehr ein Dialog beim App-Start.
        // Umfasst: TrackingService, ActivityRecognition (DONNA-119), Geofencing (DONNA-121),
        // Health-Connect-Tagessync (DONNA-120).
        DonnaSetupModule.startPermittedServices(this)
        // DONNA-123: MediaSessionObserver initialisieren (initialer Refresh)
        MediaSessionObserver.refresh(this)
        // DONNA-124: Samsung Health Bridge — versucht Samsung Health SDK zu laden, Fallback auf Health Connect
        android.os.Handler(mainLooper).postDelayed({
            Thread { SamsungHealthBridge.syncIfAvailable(this) }.start()
        }, 2_000)
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
        val proactiveTitle = intent.getStringExtra("donna_proactive_title")
        // Nonce aus dem Tap-Intent übernehmen (Doppel-Seed-Schutz in der JS-Schicht); fehlt er, einen neuen setzen.
        val nonce = intent.getStringExtra("donna_proactive_nonce") ?: PushPayload.newNonce()
        intent.removeExtra("donna_proactive_message")
        intent.removeExtra("donna_proactive_title")
        intent.removeExtra("donna_proactive_nonce")
        intent.removeExtra("open_new_chat")
        intent.removeExtra("session_id")
        // DONNA-198: session_id als JSON mitgeben damit JS-Schicht korrekte Session nutzt.
        // Notification-Tap-Kontext: Titel + voller Body + Nonce → der Chat zeigt den Inhalt der angetippten Push.
        val payload = PushPayload.proactiveJson(proactiveMsg, sessionId, proactiveTitle, nonce)
        // Notification-Fix: Der Text der ANGETIPPTEN Benachrichtigung (contentIntent-Extra) ist die Wahrheit —
        // in die SharedPreferences schreiben, aus denen die JS-Schicht (consumeProactiveFromPrefs / ChatScreen) den
        // Chat seedet. So öffnet der Tap nie einen leeren Chat, auch wenn der NtfyService-Eintrag schon verbraucht
        // oder durch eine neuere Nachricht überschrieben wurde. Überschreiben (kein Anhängen): kein Doppel-Seed.
        ProactiveMessageModule.save(applicationContext, payload)
        scheduleProactiveChatEmit(payload, attempts = 0)
    }

    private fun scheduleProactiveChatEmit(payload: String, attempts: Int) {
        if (isDestroyed || isFinishing) return  // Guard: Activity bereits weg
        val reactContext = reactInstanceManager?.currentReactContext
        if (reactContext != null) {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                ?.emit("donna_open_proactive_chat", payload)
            // Nachrichtentext (Briefing) nie in Release-Logs
            android.util.Log.i("MainActivity", "DONNA-198: donna_open_proactive_chat emitted nach ${attempts * 200}ms" +
                if (BuildConfig.DEBUG) ": ${payload.take(80)}" else "")
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

package com.yourcompany.donna

import android.Manifest
import android.app.AppOpsManager
import android.os.Build

/**
 * SetupSteps — reine Logik des Ersteinrichtungs-Assistenten (Onboarding-Wizard).
 *
 * Bewusst ohne Android-Framework-Aufrufe, damit die Zuordnung Schritt → Permission,
 * die API-Level-Abhängigkeit und das "Dauerhaft abgelehnt"-Verhalten per JVM-Unit-Test
 * prüfbar sind (siehe SetupStepsTest). Die Android-Seite (Status-Prüfung, Intents)
 * steckt in DonnaSetupModule.
 *
 * Schlüssel (key) sind identisch mit src/native/DonnaSetup.ts (SETUP_STEPS).
 */
object SetupSteps {

    // ── Pflicht ──────────────────────────────────────────────────────────────
    const val MIC = "mic"
    const val NOTIFICATIONS = "notifications"
    const val OVERLAY = "overlay"
    const val ASSISTANT = "assistant"
    const val BATTERY = "battery"

    // ── Für Funktionen (empfohlen) ───────────────────────────────────────────
    // exact_alarm ist bewusst NICHT Pflicht: NtfyService startet auch ohne neu (nur weniger präzise).
    const val EXACT_ALARM = "exact_alarm"
    const val LOCATION = "location"
    const val LOCATION_BG = "location_bg"
    const val CONTACTS = "contacts"
    const val PHONE = "phone"
    const val CALENDAR = "calendar"
    const val CAMERA = "camera"
    const val ACTIVITY = "activity"

    // ── Erweitert (optional) ─────────────────────────────────────────────────
    const val ACCESSIBILITY = "accessibility"
    const val NOTIF_LISTENER = "notif_listener"
    const val USAGE_STATS = "usage_stats"
    const val HEALTH = "health"

    val REQUIRED_KEYS: List<String> = listOf(MIC, NOTIFICATIONS, OVERLAY, ASSISTANT, BATTERY)

    val ALL_KEYS: List<String> = REQUIRED_KEYS + listOf(
        EXACT_ALARM, LOCATION, LOCATION_BG, CONTACTS, PHONE, CALENDAR, CAMERA, ACTIVITY,
        ACCESSIBILITY, NOTIF_LISTENER, USAGE_STATS, HEALTH,
    )

    /**
     * Runtime-Permissions eines Schritts für das gegebene API-Level.
     *
     * @return `null`, wenn der Schritt keine Runtime-Permission ist (Spezialzugriff);
     *         leere Liste, wenn die Permission auf diesem API-Level nicht existiert
     *         (Schritt gilt dann als "nicht nötig"); sonst die anzufragenden Permissions.
     */
    fun runtimePermissions(key: String, sdk: Int): List<String>? = when (key) {
        MIC -> listOf(Manifest.permission.RECORD_AUDIO)
        NOTIFICATIONS ->
            if (sdk >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        LOCATION -> listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        LOCATION_BG ->
            if (sdk >= Build.VERSION_CODES.Q) listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else emptyList()
        CONTACTS -> listOf(Manifest.permission.READ_CONTACTS)
        PHONE -> listOf(Manifest.permission.CALL_PHONE)
        CALENDAR -> listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        CAMERA -> listOf(Manifest.permission.CAMERA)
        ACTIVITY ->
            if (sdk >= Build.VERSION_CODES.Q) listOf(Manifest.permission.ACTIVITY_RECOGNITION) else emptyList()
        else -> null
    }

    /**
     * Permissions, die für "granted" erteilt sein müssen.
     *
     * Standort: Android 12+ erlaubt dem Nutzer, nur "ungefähr" zu erteilen. Donna braucht
     * für Geofencing/Tracking die genaue Position → nur ACCESS_FINE_LOCATION entscheidet.
     * Alle anderen Schritte: sämtliche Permissions des Schritts.
     */
    fun requiredForGranted(key: String, sdk: Int): List<String>? {
        val all = runtimePermissions(key, sdk) ?: return null
        return if (key == LOCATION) listOf(Manifest.permission.ACCESS_FINE_LOCATION) else all
    }

    /**
     * Welche Permissions beim System-Dialog angefragt werden.
     *
     * Standort: IMMER FINE + COARSE zusammen. Android 12+ ignoriert eine reine FINE-Anfrage,
     * wenn der Nutzer vorher nur "ungefähr" erlaubt hat (COARSE schon erteilt) — ein Upgrade auf
     * "genau" ist nur mit beiden Permissions im selben Request möglich.
     * Alle anderen Schritte: nur die noch fehlenden Permissions.
     */
    fun permissionsToRequest(key: String, sdk: Int, missing: List<String>): List<String> {
        if (key == LOCATION) return runtimePermissions(LOCATION, sdk) ?: missing
        return missing
    }

    /**
     * Nutzungszugriff (AppOps): MODE_ALLOWED → erteilt. MODE_DEFAULT heißt "Entscheidung der
     * Permission" → dann zählt, ob PACKAGE_USAGE_STATS erteilt ist (manche Geräte melden so).
     */
    fun usageStatsGranted(appOpsMode: Int, permissionGranted: Boolean): Boolean = when (appOpsMode) {
        AppOpsManager.MODE_ALLOWED -> true
        AppOpsManager.MODE_DEFAULT -> permissionGranted
        else -> false
    }

    /**
     * Ob der "Einrichten"-Button statt eines (wirkungslosen) System-Dialogs die
     * App-Einstellungen öffnen muss: Die Permission wurde schon einmal angefragt und
     * Android zeigt keine Begründung mehr an → "Nicht erneut fragen" ist aktiv bzw.
     * die zweite Ablehnung hat sie dauerhaft gesperrt.
     */
    fun shouldOpenAppSettings(requestedBefore: Boolean, showRationale: Boolean): Boolean =
        requestedBefore && !showRationale

    /**
     * Prüft, ob eine Komponente in einem Settings.Secure-Eintrag wie
     * `enabled_accessibility_services` ("pkg/.Klasse:pkg2/pkg2.Klasse2") steht.
     * Kurzform (".Klasse") und Langform (voller Klassenname) werden gleich behandelt.
     */
    fun isComponentEnabled(enabledList: String?, pkg: String, cls: String): Boolean {
        if (enabledList.isNullOrBlank()) return false
        val fullName = if (cls.startsWith(".")) pkg + cls else cls
        return enabledList.split(':').any { entry ->
            val parts = entry.trim().split('/', limit = 2)
            if (parts.size != 2 || parts[0] != pkg) return@any false
            val entryCls = parts[1].let { if (it.startsWith(".")) pkg + it else it }
            entryCls == fullName
        }
    }

    /** Alle Pflicht-Schritte erledigt (nicht verfügbare Schritte zählen als erledigt). */
    fun requiredComplete(granted: Map<String, Boolean>): Boolean =
        REQUIRED_KEYS.all { granted[it] == true }
}

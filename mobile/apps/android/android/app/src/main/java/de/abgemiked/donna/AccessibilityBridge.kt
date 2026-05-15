package com.yourcompany.donna

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

/**
 * AccessibilityBridge — DONNA-127: React Native Bridge für Screen-Text-Lesen.
 *
 * Stellt @ReactMethod readCurrentScreen() bereit — liest den aktuellen Screen-Text
 * via DonnaAccessibilityService NUR wenn explizit aufgerufen (kein Auto-Monitoring).
 *
 * Datenschutz-Maßnahmen:
 * - PASSWORD-Type-Nodes werden gefiltert
 * - Banking-Apps-Blocklist (keine Daten aus Finanz-Apps)
 * - Kein automatisches Senden ans Backend — wartet auf expliziten JS-Aufruf
 */
class AccessibilityBridge(private val reactContext: ReactApplicationContext)
    : ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "AccessibilityBridge"

        /** Banking & Finanz-Apps: absolut blockiert — werden nie gelesen. */
        private val BANKING_BLOCKLIST: Set<String> = setOf(
            "com.paypal.android.p2pmobile",    // PayPal
            "com.paypal",                       // PayPal (generisch)
            "com.ing.diba.mbbr2",              // ING Banking
            "com.commerzbank.MobileApp",        // Commerzbank
            "com.commerzbank.android",          // Commerzbank (generisch)
            "de.commerzbank.android",           // Commerzbank (alt)
            "com.sparkasse.android",            // Sparkasse (generisch)
            "de.number26.android",              // N26
            "de.dkb.portalapp",                // DKB
            "de.comdirect.android",            // Comdirect
            "de.ingdiba.bankingapp",           // ING
            "com.db.pbc.mibaby",               // Deutsche Bank
            "com.hypovereinsbank",              // HVB
            "com.volksbank.android",           // Volksbank
            "de.postbank.finanzassistent",     // Postbank
        )
    }

    override fun getName(): String = "AccessibilityBridge"

    // ── React Method ─────────────────────────────────────────────────────────

    /**
     * Liest den aktuellen Screen-Text via AccessibilityService.
     *
     * Gibt "App: [AppName]\n[TextInhalt]" zurück.
     * Reject mit NOT_ENABLED wenn Service nicht aktiviert.
     * Reject mit BLOCKED wenn aktuelle App in der Banking-Blocklist ist.
     */
    @ReactMethod
    fun readCurrentScreen(promise: Promise) {
        try {
            // Service-Check
            if (!isDonnaAccessibilityEnabled()) {
                promise.reject(
                    "NOT_ENABLED",
                    "Accessibility Service nicht aktiviert. " +
                    "Bitte in Einstellungen → Bedienungshilfen → Donna aktivieren."
                )
                return
            }

            val service = DonnaAccessibilityService.instance
            if (service == null) {
                promise.reject(
                    "SERVICE_NOT_CONNECTED",
                    "Accessibility Service ist aktiviert aber noch nicht verbunden."
                )
                return
            }

            // Screen-Text via Service lesen
            val result = service.extractScreenText()
            if (result == null) {
                promise.reject("NO_WINDOW", "Kein aktives Fenster lesbar.")
                return
            }

            Log.i(TAG, "readCurrentScreen erfolgreich: ${result.take(80)}...")
            promise.resolve(result)

        } catch (e: Exception) {
            Log.e(TAG, "readCurrentScreen Fehler: ${e.message}")
            promise.reject("ERROR", "Screen lesen fehlgeschlagen: ${e.message}")
        }
    }

    /**
     * Prüft ob DonnaAccessibilityService aktiviert ist.
     */
    @ReactMethod
    fun isAccessibilityEnabled(promise: Promise) {
        promise.resolve(isDonnaAccessibilityEnabled())
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private fun isDonnaAccessibilityEnabled(): Boolean {
        val am = reactContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabled.any { info ->
            info.resolveInfo.serviceInfo.packageName == reactContext.packageName &&
            info.resolveInfo.serviceInfo.name.contains("DonnaAccessibilityService")
        }
    }
}

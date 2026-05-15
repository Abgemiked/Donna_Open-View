package com.yourcompany.donna

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * DonnaAccessibilityService — liest Titel und sichtbaren Text aus ausgewählten Apps.
 *
 * Überwachte Apps:
 *  - Google News, YouTube, WhatsApp, TikTok, Instagram, Facebook, LinkedIn,
 *    Snapchat, Twitter/X, Reddit, Spiegel, FAZ, t-online, Tagesschau
 *
 * Was NICHT gelesen wird:
 *  - Banking-Apps, Passwort-Manager, System-UI
 *  - Inline-Passwort-Felder (isPassword=true werden gefiltert)
 *  - Apps außerhalb der Whitelist
 *
 * Rate-Limit: pro App max. 1 Event alle 45 Sekunden.
 * Max Text-Länge pro Event: 2000 Zeichen.
 *
 * DONNA-127: On-Demand Screen-Text via AccessibilityBridge.extractScreenText().
 * Kein automatisches Senden — wartet auf expliziten Aufruf aus React Native.
 */
class DonnaAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Unix-Timestamps (ms) wann zuletzt pro Paket gesendet wurde. */
    private val lastSentMs = mutableMapOf<String, Long>()
    private val rateLimitMs = 45_000L  // 45 Sekunden

    companion object {
        // ── DONNA-127: Singleton-Referenz für AccessibilityBridge ───────────
        @Volatile
        var instance: DonnaAccessibilityService? = null
            private set

        // ── Whitelist: überwachte Apps (Auto-Push) ───────────────────────────
        val WATCHED_PACKAGES: Set<String> = setOf(
            // News & Video
            "com.google.android.apps.magazines",        // Google News
            "com.google.android.youtube",               // YouTube
            "com.google.android.apps.youtube.music",    // YouTube Music
            "com.ndr.nachrichten",                      // NDR
            "de.spiegel.android.app.ipad",              // Spiegel Online
            "de.faz.net.app",                           // FAZ
            "de.t_online.app",                          // t-online
            "de.tagesschau",                            // Tagesschau
            "de.bild.android.app",                      // BILD
            "com.bbc.mobile.news.ww",                   // BBC News
            "com.cnn.mobile.android.phone",             // CNN
            "com.theguardian.android",                  // Guardian
            // Social Media
            "com.whatsapp",                             // WhatsApp
            "com.zhiliaoapp.musically",                 // TikTok
            "com.instagram.android",                    // Instagram
            "com.facebook.katana",                      // Facebook
            "com.linkedin.android",                     // LinkedIn
            "com.snapchat.android",                     // Snapchat
            "com.twitter.android",                      // Twitter/X
            "com.reddit.frontpage",                     // Reddit
            "com.discord",                              // Discord
            "com.pinterest",                            // Pinterest
            // Productivity / Info
            "com.google.android.apps.searchlite",       // Google App (Discover)
            "com.medium.reader",                        // Medium
            "com.pocket",                               // Pocket
        )

        // ── Blacklist: Auto-Push niemals, On-Demand niemals ─────────────────
        val BLOCKED_PACKAGES: Set<String> = setOf(
            "com.android.settings",
            "com.samsung.android.settings",
            // Banking (auch für On-Demand DONNA-127)
            "com.paypal.android.p2pmobile",
            "com.paypal",
            "com.ing.diba.mbbr2",
            "com.commerzbank.MobileApp",
            "com.commerzbank.android",
            "de.commerzbank.android",
            "com.sparkasse.android",
            "de.number26.android",
            "de.dkb.portalapp",
            "de.comdirect.android",
            "de.ingdiba.bankingapp",
            "com.db.pbc.mibaby",                       // Deutsche Bank
            "com.hypovereinsbank",
            "com.volksbank.android",
            "de.postbank.finanzassistent",
            // System & Passwort-Manager
            "com.google.android.gms",                  // Play Services
            "de.idnow.android",                        // ID-Verifikation
            "com.lastpass.lpandroid",                  // LastPass
            "com.dashlane",                            // Dashlane
            "com.onepassword.android",                 // 1Password
            "com.bitwarden.mobile",                    // Bitwarden
        )

        // ── UI-Rauschen filtern ──────────────────────────────────────────────
        private val NOISE_TEXTS: Set<String> = setOf(
            "search", "suchen", "suche", "home", "startseite",
            "back", "zurück", "menu", "mehr", "share", "teilen",
            "like", "gefällt mir", "follow", "folgen", "unfollow",
            "comment", "kommentar", "reply", "antworten",
            "notification", "benachrichtigung", "settings", "einstellungen",
        )
    }

    // ── AccessibilityService Lifecycle ────────────────────────────────────

    override fun onServiceConnected() {
        instance = this  // DONNA-127: Referenz setzen
        // DONNA-193: Keine dynamische serviceInfo-Überschreibung — XML-Config ist alleinige Autorität.
        // Doppelzuweisung (XML + dynamisch) verursacht "funktioniert nicht" in Android-Einstellungen.
        // Konfiguration liegt in: res/xml/accessibility_service_config.xml
        android.util.Log.i("DonnaAccessibility", "Service connected — config via xml/accessibility_service_config.xml")
    }

    override fun onInterrupt() {
        android.util.Log.w("DonnaAccessibility", "Service interrupted")
    }

    override fun onDestroy() {
        instance = null  // DONNA-127: Referenz freigeben
        super.onDestroy()
        scope.cancel()
    }

    // ── Event Handler (Auto-Push für Whitelist-Apps) ──────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return

        // Blacklist-Check (gilt immer)
        if (pkg in BLOCKED_PACKAGES) return
        // Whitelist-Check (nur Whitelist-Apps triggern Auto-Push)
        if (pkg !in WATCHED_PACKAGES) return

        // Rate-Limit: max 1 Event pro App alle 45 Sek
        val now = System.currentTimeMillis()
        if ((now - (lastSentMs[pkg] ?: 0L)) < rateLimitMs) return

        val root = rootInActiveWindow ?: return
        val texts = extractTexts(root)
        root.recycle()

        if (texts.isEmpty()) return

        val appLabel = resolveAppLabel(pkg)
        val content = texts.take(15).joinToString(" · ")

        lastSentMs[pkg] = now

        scope.launch {
            sendScreenEvent(pkg, appLabel, content, event.eventType)
        }
    }

    // ── DONNA-127: On-Demand Screen-Text ─────────────────────────────────

    /**
     * Liest strukturierten Text vom aktuellen Screen.
     * NUR on-demand — kein automatisches Senden.
     *
     * Datenschutz:
     * - PASSWORD-Nodes werden gefiltert
     * - BLOCKED_PACKAGES (Banking etc.) werden abgelehnt → null
     * - Kein Auto-Backend-Push — Rückgabe an JS-Layer
     *
     * Rückgabe: "App: [AppName]\n[TextInhalt]" oder null wenn geblockt/leer.
     */
    fun extractScreenText(): String? {
        val root = rootInActiveWindow ?: return null

        val pkg = root.packageName?.toString()
        if (pkg != null && pkg in BLOCKED_PACKAGES) {
            root.recycle()
            android.util.Log.d("DonnaAccessibility", "extractScreenText: $pkg ist geblockt")
            return null
        }

        val texts = extractTexts(root)
        root.recycle()

        if (texts.isEmpty()) return null

        val appLabel = if (pkg != null) resolveAppLabel(pkg) else "Unbekannte App"
        val content = texts.take(20).joinToString("\n")

        return "App: $appLabel\n$content"
    }

    // ── Text Extraction ───────────────────────────────────────────────────

    private fun extractTexts(node: AccessibilityNodeInfo): List<String> {
        val results = mutableListOf<String>()
        extractRecursive(node, results, depth = 0)
        return results
            .filter { it.length > 8 }
            .filter { it.lowercase() !in NOISE_TEXTS }
            .distinct()
    }

    private fun extractRecursive(
        node: AccessibilityNodeInfo,
        out: MutableList<String>,
        depth: Int,
    ) {
        if (depth > 10) return  // Max Tiefe — verhindert Stack-Overflow in komplexen Layouts
        if (out.size >= 30) return  // Max 30 Textstücke pro Event

        // Passwort-Felder NIEMALS lesen (gilt für Auto-Push UND On-Demand)
        if (node.isPassword) return

        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank() && text.length > 3) {
            out.add(text)
        }

        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrBlank() && desc.length > 3 && desc != text) {
            out.add(desc)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractRecursive(child, out, depth + 1)
            child.recycle()
        }
    }

    // ── App Label ─────────────────────────────────────────────────────────

    private fun resolveAppLabel(packageName: String): String {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast(".")
        }
    }

    // ── HTTP Push (Auto-Push für Whitelist-Apps) ──────────────────────────

    private fun sendScreenEvent(
        packageName: String,
        appLabel: String,
        content: String,
        eventType: Int,
    ) {
        val body = JSONObject().apply {
            put("type", "screen")
            put("screen", JSONObject().apply {
                put("package", packageName)
                put("app", appLabel)
                put("content", content.take(2000))
                put("event_type", eventType)
            })
        }

        try {
            val url = URL("${BuildConfig.DONNA_API_URL}/tracking/screen")
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${TokenStore.getToken(applicationContext) ?: ""}")
                connectTimeout = 8_000
                readTimeout = 8_000
                doOutput = true
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            if (code !in 200..299) {
                android.util.Log.w("DonnaAccessibility", "push HTTP $code for $packageName")
            } else {
                android.util.Log.d("DonnaAccessibility", "pushed screen event for $appLabel")
            }
        } catch (e: Exception) {
            android.util.Log.w("DonnaAccessibility", "push error: ${e.message}")
        }
    }
}

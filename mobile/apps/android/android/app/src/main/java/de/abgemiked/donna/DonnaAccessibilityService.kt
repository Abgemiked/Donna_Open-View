package com.yourcompany.donna

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
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

    // ── B2 Auto-Send: Vordergrund-Fenster-Tracking (Window-State-Event) ───────
    // Statt eines fixen Delays wird auf das TYPE_WINDOW_STATE_CHANGED des Ziel-Pakets gewartet:
    // der Senden-Node (WhatsApp) erscheint erst nach dem Voll-Start der App.
    @Volatile private var lastWindowStatePkg: String? = null
    @Volatile private var lastWindowStateAt: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

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

        // B2 Auto-Send: NUR den Namen des gerade in den Vordergrund getretenen Pakets merken
        // (kein Inhalt wird gelesen). Dient als Timing-Signal für findAndClickSendNode.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastWindowStatePkg = pkg
            lastWindowStateAt = SystemClock.uptimeMillis()
        }

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

    // ── B2: Accessibility Auto-Send (#3 WhatsApp / #1 Anruf), Stufe 3 ──────────
    //
    // SICHERHEIT (Kern-Invariante): requestAutoTap wird AUSSCHLIESSLICH nach Mikes expliziter
    // Bestätigung aufgerufen — aus ChatScreen.confirmPendingAction (Bestätigen-Button) bzw. aus
    // VoiceInputActivity nach der verbalen Zustimmung (ActionCardBuilder „Ja"-Button). Der Service
    // tippt NIE von sich aus. Getippt wird nur im erwarteten Ziel-Paket ([AutoSendSpec.allowedPackages]);
    // andere Apps werden nie angetippt. Kein Node gefunden → KEIN Fehlklick, sondern Toast-Hinweis.

    private val autoTapPollMs = 150L
    private val autoTapMaxDepth = 40
    private val autoTapMaxNodes = 2_000   // Knotenlimit: schützt vor pathologisch großen Bäumen

    /**
     * Startet den Auto-Tap (asynchron). [onDone] wird GARANTIERT auf dem Main-Thread genau einmal
     * mit `true` (geklickt) oder `false` (kein Node → Fallback) aufgerufen — auch bei Exception/
     * CancellationException/Service-Zerstörung (try/catch/finally), damit die Bridge-Promise nie
     * offen bleibt (sonst würde sendingRef im JS alles sperren). Bei `false` zeigt der Service
     * zusätzlich einen Toast „bitte selbst tippen" (harter, sichtbarer Fallback).
     */
    fun requestAutoTap(
        spec: AutoSendSpec,
        hints: SendNodeFinder.Hints,
        timeoutMs: Long,
        onDone: (Boolean) -> Unit,
    ) {
        val requestedAt = SystemClock.uptimeMillis()
        scope.launch {
            var clicked = false
            try {
                clicked = findAndClickSendNode(spec, hints, timeoutMs, requestedAt)
            } catch (e: Throwable) {
                android.util.Log.w("DonnaAccessibility", "auto-tap error (${spec.kind}): ${e.message}")
                // clicked bleibt false → Fallback. CancellationException läuft ebenfalls hier durch
                // und wird im finally sauber als Fallback gemeldet.
            } finally {
                val result = clicked
                mainHandler.post {
                    if (!result) {
                        val hint = if (spec.kind == "call")
                            "Bitte in der Telefon-App auf Anrufen tippen."
                        else
                            "Bitte in WhatsApp auf Senden tippen."
                        runCatching { Toast.makeText(applicationContext, hint, Toast.LENGTH_LONG).show() }
                    }
                    onDone(result)
                }
            }
        }
    }

    /**
     * Wartet (bis [timeoutMs]) auf ein FRISCHES Vordergrund-Fenster des Ziel-Pakets und tippt dann
     * den Send-/Call-Node. „Warten" heißt: pollt, bis ein TYPE_WINDOW_STATE_CHANGED für ein erlaubtes
     * Paket NACH dem Request kam — KEIN fixer Delay, und NICHT bloß „root ist schon das Ziel" (sonst
     * Risiko, in einem falschen/Entwurf-Chat zu tippen, wenn WhatsApp beim Bestätigen schon vorn war).
     * Node-Suche via [SendNodeFinder]. Rückgabe: true = geklickt, sonst false (Fallback).
     */
    private suspend fun findAndClickSendNode(
        spec: AutoSendSpec,
        hints: SendNodeFinder.Hints,
        timeoutMs: Long,
        requestedAt: Long,
    ): Boolean {
        val deadline = requestedAt + timeoutMs.coerceIn(1_000L, 15_000L)
        while (SystemClock.uptimeMillis() < deadline) {
            if (targetWindowReady(spec, requestedAt)) {
                val root = rootInActiveWindow
                val rpkg = root?.packageName?.toString()
                if (root != null && SendNodeFinder.isAllowedPackage(spec, rpkg, BLOCKED_PACKAGES)) {
                    // Baum ohne Recycling aufbauen (ab API 33 ist recycle() ohnehin no-op) — die
                    // UiNode.ref-Handles müssen für den Klick gültig bleiben.
                    val rootPx = rootArea(root)
                    val truncated = booleanArrayOf(false)
                    val tree = buildUiNode(root, 0, intArrayOf(0), rootPx, truncated)
                    // R2-A: War der Baum abgeschnitten, könnte ein zweiter Send-Kandidat fehlen →
                    // find() gibt dann IMMER Fallback (kein Klick auf einen evtl. mehrdeutigen Node).
                    val match = SendNodeFinder.find(tree, hints, truncated[0])
                    if (match != null && clickNode(match, rootPx)) {
                        android.util.Log.i("DonnaAccessibility", "auto-tap ok (${spec.kind})")
                        return true
                    }
                }
            }
            delay(autoTapPollMs)
        }
        android.util.Log.w("DonnaAccessibility", "auto-tap: kein Node (${spec.kind}) → Fallback")
        return false
    }

    /**
     * Ziel-Fenster bereit? Verlangt ein FRISCHES Window-State-Event des Ziel-Pakets NACH dem Request.
     * (Bewusst KEIN „root zeigt schon das Ziel"-Fallback — Schutz gegen das Antippen eines bereits
     * offenen, womöglich falschen WhatsApp-Chats.)
     */
    private fun targetWindowReady(spec: AutoSendSpec, requestedAt: Long): Boolean {
        val fg = lastWindowStatePkg
        return fg != null && fg in spec.allowedPackages && lastWindowStateAt >= requestedAt
    }

    /** Fensterfläche (px²) für den Flächenanteil-Schutz; 0 → unbekannt (Anteil wird dann null). */
    private fun rootArea(root: AccessibilityNodeInfo): Int {
        val r = android.graphics.Rect()
        root.getBoundsInScreen(r)
        val area = r.width().toLong() * r.height().toLong()
        return if (area in 1..Int.MAX_VALUE.toLong()) area.toInt() else 0
    }

    /**
     * Reiner [UiNode]-Baum aus dem echten Node; `ref` zeigt zum Klicken auf den echten Node.
     * [count] zählt Knoten über den ganzen Baum (Limit [autoTapMaxNodes]); [rootAreaPx] dient dem
     * Flächenanteil (Node-Bounds / Fenster) als Fehlklick-Schutz in [SendNodeFinder].
     * R2-A: [truncated]`[0]` wird true, sobald der Baum NICHT vollständig gelesen wurde — Tiefenlimit,
     * Knotenlimit ODER ein `getChild()==null`. Der Aufrufer behandelt das als „nicht eindeutig" → Fallback.
     */
    private fun buildUiNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        count: IntArray,
        rootAreaPx: Int,
        truncated: BooleanArray,
    ): UiNode {
        count[0]++
        val atLimit = depth >= autoTapMaxDepth || count[0] >= autoTapMaxNodes
        if (atLimit && node.childCount > 0) truncated[0] = true
        val children = if (atLimit) emptyList() else buildList {
            for (i in 0 until node.childCount) {
                if (count[0] >= autoTapMaxNodes) { truncated[0] = true; break }
                val child = node.getChild(i)
                if (child == null) { truncated[0] = true; continue }  // null-Kind → Baum unvollständig
                add(buildUiNode(child, depth + 1, count, rootAreaPx, truncated))
            }
        }
        val frac: Double? = if (rootAreaPx > 0) {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            (r.width().toLong() * r.height().toLong()).toDouble() / rootAreaPx.toDouble()
        } else null
        return UiNode(
            viewId = node.viewIdResourceName,
            contentDescription = node.contentDescription?.toString(),
            text = node.text?.toString(),
            isClickable = node.isClickable,
            isEnabled = node.isEnabled,
            isVisibleToUser = node.isVisibleToUser,
            areaFraction = frac,
            children = children,
            ref = node,
        )
    }

    /** Spiegelt [SendNodeFinder.usable] am ECHTEN Node (für den Re-Check unmittelbar vor dem Klick). */
    private fun nodeUsable(node: AccessibilityNodeInfo, rootAreaPx: Int): Boolean {
        if (!node.isClickable || !node.isVisibleToUser || !node.isEnabled) return false
        if (rootAreaPx <= 0) return false   // Fläche unbekannt → fail-closed
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        val frac = (r.width().toLong() * r.height().toLong()).toDouble() / rootAreaPx.toDouble()
        return frac <= SendNodeFinder.MAX_AREA_FRACTION
    }

    /**
     * Klickt GENAU den vom [SendNodeFinder] gelieferten Treffer-Node — KEIN Climb zu Vorfahren
     * (ein geklickter Container wäre ein potenzieller Fehlklick). R2-B: unmittelbar vor dem Klick
     * `refresh()` + erneute [nodeUsable]-Prüfung (klickbar + sichtbar + aktiv + Fläche ≤ Limit) am
     * FRISCHEN Zustand — schlägt refresh fehl oder ist der Node nicht mehr usable → KEIN Klick (Fallback).
     */
    private fun clickNode(match: UiNode, rootAreaPx: Int): Boolean {
        val node = match.ref as? AccessibilityNodeInfo ?: return false
        val refreshed = runCatching { node.refresh() }.getOrDefault(false)
        if (!refreshed || !nodeUsable(node, rootAreaPx)) {
            android.util.Log.w("DonnaAccessibility", "auto-tap: Treffer nicht mehr usable (refresh=$refreshed) → kein Klick")
            return false
        }
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
    }
}

package com.yourcompany.donna.device

import android.content.SharedPreferences
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * #147 Paket A — Geräte-Aktionen: REINE Logik (Positivliste, Schema, Grenzen, Stufen, Freigabe je Aktionsart,
 * Zuordnungstabellen, Rückgängig-Daten). Keine Android-Aufrufe (außer dem reinen Typ SharedPreferences);
 * die Ausführung steht getrennt in [DeviceActionRunner]. Grundsatz: fail-closed — was nicht ausdrücklich auf der
 * Positivliste steht oder eine Grenze verletzt, wird abgelehnt. Nichts hier sendet Nachrichten, ruft an, bewegt
 * Geld, schaltet Smart Home oder startet gesperrte Apps.
 */

/** Aktionsart = ein Schalter in den Einstellungen („Donna darf selbst …“). Standard je Art: aus. */
enum class DeviceActionKind(val key: String) {
    FLASHLIGHT("flashlight"),
    VOLUME("volume"),
    OPEN_APP("open_app"),
    OPEN_SETTINGS("open_settings"),
    TIMER_ALARM("timer_alarm"),
    // Paket F (#147): Systemaktionen — je Art ein eigener Schalter, Standard aus
    BRIGHTNESS("brightness"),
    DND("dnd"),
    LOCK_SCREEN("lock_screen"),
    SCREENSHOT("screenshot"),
    OPEN_CAMERA("open_camera"),
    // Paket H (#147): Termine ändern/löschen auf dem Gerät (Laufzeitrecht Kalender), Standard aus
    CALENDAR_EDIT("calendar_edit"),
    // Paket I (#147): E-Mail-Entwurf öffnen (Karte im Chat, nur öffnen, nie senden), Standard aus
    EMAIL_DRAFT("email_draft");

    /** Schlüssel der nativen Pref (Datei [com.yourcompany.donna.TtsEngines.PREFS]); EINE Quelle der Wahrheit. */
    val prefKey: String get() = "device_action_$key"

    companion object {
        const val DEFAULT_ENABLED = false

        /** Ereignistyp im SSE-Strom und in den Aktions-Objekten. */
        const val TYPE = "device_action"

        fun fromKey(key: String?): DeviceActionKind? = values().firstOrNull { it.key == key }

        /** Rohliste (z. B. aus dem Request) -> bekannte Arten; Unbekanntes und Doppeltes fällt weg. */
        fun parseEnabled(raw: Collection<String?>?): Set<DeviceActionKind> =
            raw?.mapNotNull { fromKey(it) }?.toSet() ?: emptySet()

        fun enabledSet(prefs: SharedPreferences): Set<DeviceActionKind> =
            values().filter { read(prefs, it) }.toSet()

        /** Schlüssel der eingeschalteten Arten (geht mit jeder Anfrage ans Backend). */
        fun enabledKeys(prefs: SharedPreferences): List<String> = enabledSet(prefs).map { it.key }

        /** Arten, die nur der Chat-Bildschirm bedienen kann (Karte mit Tipp): Overlay/Sprachweg bietet sie nie an. */
        val CHAT_ONLY: Set<DeviceActionKind> = setOf(CALENDAR_EDIT, EMAIL_DRAFT)

        /** Wie [enabledKeys], ohne die Arten, die eine Karte im Chat brauchen (für Overlay und Sprachweg). */
        fun enabledKeysNoCards(prefs: SharedPreferences): List<String> = keysWithoutChatOnly(enabledSet(prefs))

        fun keysWithoutChatOnly(kinds: Collection<DeviceActionKind>): List<String> =
            kinds.filter { it !in CHAT_ONLY }.map { it.key }

        fun read(prefs: SharedPreferences, kind: DeviceActionKind): Boolean =
            try { prefs.getBoolean(kind.prefKey, DEFAULT_ENABLED) } catch (_: Exception) { DEFAULT_ENABLED }

        fun write(prefs: SharedPreferences, kind: DeviceActionKind, enabled: Boolean) {
            prefs.edit().putBoolean(kind.prefKey, enabled).apply()
        }
    }
}

enum class VolumeChannel(val key: String, val label: String) {
    MEDIA("media", "Medien"),
    RING("ring", "Klingelton"),
    ALARM("alarm", "Wecker"),
    CALL("call", "Anruf");

    companion object {
        fun fromKey(key: String?): VolumeChannel? = values().firstOrNull { it.key == key }
    }
}

enum class VolumeMode(val key: String) {
    SET("set"), UP("up"), DOWN("down"), MUTE("mute"), UNMUTE("unmute");

    companion object {
        fun fromKey(key: String?): VolumeMode? = values().firstOrNull { it.key == key }
    }
}

/**
 * Einstellungsseiten über ÖFFENTLICHE Einstellungs-Absichten. Funk (WLAN, Bluetooth, Daten, Flugmodus) wird nie
 * geschaltet, nur die Seite bzw. das Schnellzugriff-Panel geöffnet. [ownPackage]: Seite zu Donnas eigener App.
 */
enum class SettingsPage(
    val key: String,
    val label: String,
    val intentAction: String,
    val panel: Boolean = false,
    val ownPackage: Boolean = false,
    /** Panels gibt es erst ab API 29; sonst diese Seite. */
    val fallbackAction: String? = null,
) {
    WIFI("wifi", "WLAN", "android.settings.WIFI_SETTINGS"),
    BLUETOOTH("bluetooth", "Bluetooth", "android.settings.BLUETOOTH_SETTINGS"),
    MOBILE_DATA("mobile_data", "mobile Daten", "android.settings.WIRELESS_SETTINGS"),
    DISPLAY("display", "Display", "android.settings.DISPLAY_SETTINGS"),
    SOUND("sound", "Ton", "android.settings.SOUND_SETTINGS"),
    BATTERY("battery", "Akku", "android.settings.BATTERY_SAVER_SETTINGS"),
    LOCATION("location", "Standort", "android.settings.LOCATION_SOURCE_SETTINGS"),
    NOTIFICATIONS("notifications", "Benachrichtigungen", "android.settings.APP_NOTIFICATION_SETTINGS", ownPackage = true),
    APP_INFO("app_info", "App-Info", "android.settings.APPLICATION_DETAILS_SETTINGS", ownPackage = true),
    ACCESSIBILITY("accessibility", "Bedienungshilfen", "android.settings.ACCESSIBILITY_SETTINGS"),
    AIRPLANE("airplane", "Flugmodus", "android.settings.AIRPLANE_MODE_SETTINGS"),
    INTERNET_PANEL(
        "internet_panel", "Internet", "android.settings.panel.action.INTERNET_CONNECTIVITY",
        panel = true, fallbackAction = "android.settings.WIFI_SETTINGS",
    ),
    VOLUME_PANEL(
        "volume_panel", "Lautstärke", "android.settings.panel.action.VOLUME",
        panel = true, fallbackAction = "android.settings.SOUND_SETTINGS",
    );

    companion object {
        fun fromKey(key: String?): SettingsPage? = values().firstOrNull { it.key == key }
    }
}

/** Zuordnung deutscher Einstellungsbegriffe -> Seite. Nur genaue Treffer (nach Normalisierung), nie raten. */
object SettingsPageResolver {
    private val FILLER = setOf("einstellungen", "einstellung", "die", "das", "der", "meine", "mein", "den")

    private val SYNONYMS: Map<String, SettingsPage> = buildMap {
        for (p in SettingsPage.values()) put(norm(p.key.replace('_', ' ')), p)
        val table = mapOf(
            SettingsPage.WIFI to listOf("wlan", "wi fi", "w lan", "wifi"),
            SettingsPage.BLUETOOTH to listOf("bluetooth"),
            SettingsPage.MOBILE_DATA to listOf("mobile daten", "mobilfunk", "datenverbindung", "mobiles internet", "daten"),
            SettingsPage.DISPLAY to listOf("display", "bildschirm", "helligkeit"),
            SettingsPage.SOUND to listOf("ton", "toene", "sound", "klingelton", "klingeltoene"),
            SettingsPage.BATTERY to listOf("akku", "batterie", "energiesparmodus"),
            SettingsPage.LOCATION to listOf("standort", "ortung", "gps"),
            SettingsPage.NOTIFICATIONS to listOf("benachrichtigungen", "benachrichtigung"),
            SettingsPage.APP_INFO to listOf("app info", "appinfo", "app informationen"),
            SettingsPage.ACCESSIBILITY to listOf("bedienungshilfen", "barrierefreiheit"),
            SettingsPage.AIRPLANE to listOf("flugmodus", "flugzeugmodus"),
            SettingsPage.INTERNET_PANEL to listOf("internet"),
            SettingsPage.VOLUME_PANEL to listOf("lautstaerke"),
        )
        for ((page, terms) in table) for (t in terms) put(norm(t), page)
    }

    /** Kleinschreibung, Umlaute gefaltet, Satzzeichen zu Leerzeichen, Füllwörter weg. */
    private fun norm(s: String): String =
        TextFold.fold(s).split(' ').filter { it.isNotEmpty() && it !in FILLER }.joinToString(" ")

    fun resolve(text: String?): SettingsPage? {
        val n = norm(text ?: return null)
        return if (n.isEmpty()) null else SYNONYMS[n]
    }
}

internal object TextFold {
    /** Kleinschreibung, ä/ö/ü/ß gefaltet, alles außer Buchstaben/Ziffern -> Leerzeichen (zusammengezogen). */
    fun fold(s: String): String {
        val l = s.lowercase(Locale.ROOT)
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        return l.map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
            .trim().replace(Regex(" +"), " ")
    }
}

/** Eine startbare App des Geräts. */
data class AppEntry(val label: String, val packageName: String)

sealed class AppMatch {
    data class Found(val entry: AppEntry) : AppMatch()
    data class Ambiguous(val labels: List<String>) : AppMatch()
    data class Blocked(val label: String) : AppMatch()
    object NotFound : AppMatch()
}

/** Zuordnung App-Name -> Paket mit Sperren. Die Sperrliste kommt von außen (bestehende BLOCKED_PACKAGES). */
object AppResolver {
    private const val MIN_PARTIAL = 3

    /**
     * Teilwörter (im zusammengezogenen Label bzw. Paketnamen), die auf Banking, Zahlung, Broker, Krypto, Passwort-
     * Manager oder 2FA hindeuten (zusätzlich zur Sperrliste). Absichtlich großzügig: im Zweifel ablehnen.
     */
    private val SENSITIVE_PARTS = listOf(
        "bank", "sparkasse", "volksbank", "raiffeisen", "consors", "comdirect", "n26", "paypal", "wallet", "bezahl",
        "konto", "revolut", "klarna", "traderepublic", "scalable", "bitpanda", "coinbase", "binance", "krypto",
        "authy", "authenticat", "authentif", "2fa", "aegis", "bitwarden", "1password", "onepassword", "protonpass",
        "lastpass", "dashlane", "keepass", "passwor", "passkey", "vault", "pushtan", "phototan", "chiptan",
        "smarttan", "tan2go", "broker", "etoro", "trading", "kraken", "metamask", "securego", "freeotp",
        "enpass", "nordpass", "andotp", "bunq", "duosecurity",
    )

    /** Nur als GANZES Wort (Label-Wort bzw. Paket-Segment): „Payback“ und „Passbild“ bleiben frei. */
    private val SENSITIVE_WORDS = setOf("pay", "otp", "tan", "pass", "pw", "auth", "duo")

    fun isSensitive(app: AppEntry): Boolean {
        val label = TextFold.fold(app.label)
        val pkg = app.packageName.lowercase(Locale.ROOT)
        val compactLabel = label.replace(" ", "")
        val compactPkg = pkg.replace(".", "")
        if (SENSITIVE_PARTS.any { compactLabel.contains(it) || compactPkg.contains(it) }) return true
        val words = label.split(' ') + pkg.split('.')
        return words.any { it in SENSITIVE_WORDS }
    }

    private fun compact(s: String): String = TextFold.fold(s).replace(" ", "")

    fun resolve(query: String, apps: List<AppEntry>, blocked: Set<String>): AppMatch {
        val q = compact(query)
        if (q.isEmpty()) return AppMatch.NotFound
        val labelled = apps.map { it to compact(it.label) }
        val exact = labelled.filter { it.second == q }
        val tier = when {
            exact.isNotEmpty() -> exact
            q.length < MIN_PARTIAL -> emptyList()
            else -> labelled.filter { it.second.startsWith(q) }.ifEmpty { labelled.filter { it.second.contains(q) } }
        }
        val hits = tier.map { it.first }.distinctBy { it.packageName }
        if (hits.isEmpty()) return AppMatch.NotFound
        val (forbidden, allowed) = hits.partition { it.packageName in blocked || isSensitive(it) }
        if (forbidden.isEmpty()) {
            return if (hits.size == 1) AppMatch.Found(hits[0]) else AppMatch.Ambiguous(hits.map { it.label }.distinct().take(3))
        }
        // Fail-closed: nichts Gesperrtes wird gestartet. Gibt es erlaubte Kandidaten, wird NUR zu denen
        // zurückgefragt (kein stilles Starten, kein irreführendes "öffne ich nicht").
        return if (allowed.isEmpty()) AppMatch.Blocked(forbidden[0].label)
        else AppMatch.Ambiguous(allowed.map { it.label }.distinct().take(3))
    }
}

/** Stufe 0 = läuft sofort; Stufe 1 = läuft sofort, kurze Rückmeldung mit Rückgängig-Daten. */
sealed class DeviceAction {
    abstract val name: String
    abstract val kind: DeviceActionKind
    open val level: Int get() = 0

    /** Schlüssel für „dieselbe Aktion nicht zweimal in einer Antwort“. */
    open val dedupeKey: String get() = name

    data class Flashlight(val on: Boolean) : DeviceAction() {
        override val name get() = "flashlight"
        override val kind get() = DeviceActionKind.FLASHLIGHT
    }

    data class Volume(val channel: VolumeChannel, val mode: VolumeMode, val percent: Int?) : DeviceAction() {
        override val name get() = "volume"
        override val kind get() = DeviceActionKind.VOLUME
        override val dedupeKey get() = "volume:${channel.key}"
    }

    data class OpenApp(val app: String) : DeviceAction() {
        override val name get() = "open_app"
        override val kind get() = DeviceActionKind.OPEN_APP
        override val dedupeKey get() = "open_app:${TextFold.fold(app)}"
    }

    data class OpenSettings(val page: SettingsPage) : DeviceAction() {
        override val name get() = "open_settings"
        override val kind get() = DeviceActionKind.OPEN_SETTINGS
        override val dedupeKey get() = "open_settings:${page.key}"
    }

    data class SetTimer(val minutes: Int, val label: String) : DeviceAction() {
        override val name get() = "set_timer"
        override val kind get() = DeviceActionKind.TIMER_ALARM
        override val level get() = 1
        override val dedupeKey get() = "set_timer:$minutes:${TextFold.fold(label)}"
    }

    data class SetAlarm(val hour: Int, val minute: Int, val label: String) : DeviceAction() {
        override val name get() = "set_alarm"
        override val kind get() = DeviceActionKind.TIMER_ALARM
        override val level get() = 1
        override val dedupeKey get() = "set_alarm:$hour:$minute:${TextFold.fold(label)}"
    }

    // ── Paket F: Systemaktionen ──
    /** [percent] nur bei [BrightnessMode.SET] (5..100), sonst null. Eine Helligkeitsänderung je Antwort. */
    data class SetBrightness(val mode: BrightnessMode, val percent: Int?) : DeviceAction() {
        override val name get() = "set_brightness"
        override val kind get() = DeviceActionKind.BRIGHTNESS
        override val level get() = 1
        override val dedupeKey get() = "set_brightness"
    }

    data class Dnd(val on: Boolean) : DeviceAction() {
        override val name get() = "dnd"
        override val kind get() = DeviceActionKind.DND
        override val level get() = 1
        override val dedupeKey get() = "dnd"
    }

    object LockScreen : DeviceAction() {
        override val name get() = "lock_screen"
        override val kind get() = DeviceActionKind.LOCK_SCREEN
        override val level get() = 1
        override fun toString() = "LockScreen"
    }

    object Screenshot : DeviceAction() {
        override val name get() = "screenshot"
        override val kind get() = DeviceActionKind.SCREENSHOT
        override val level get() = 1
        override fun toString() = "Screenshot"
    }

    data class OpenCamera(val capture: CameraCapture) : DeviceAction() {
        override val name get() = "open_camera"
        override val kind get() = DeviceActionKind.OPEN_CAMERA
        override val dedupeKey get() = "open_camera"
    }
}

enum class BrightnessMode(val key: String) {
    SET("set"), UP("up"), DOWN("down");

    companion object {
        fun fromKey(key: String?): BrightnessMode? = values().firstOrNull { it.key == key }
    }
}

enum class CameraCapture(val key: String) {
    PHOTO("photo"), VIDEO("video");

    companion object {
        fun fromKey(key: String?): CameraCapture? = values().firstOrNull { it.key == key }
    }
}

sealed class ParseResult {
    data class Ok(val action: DeviceAction) : ParseResult()
    data class Rejected(val reason: String) : ParseResult()
}

/** Schema- und Grenzprüfung. Unbekannte Aktion, unbekannter Parameter, falscher Typ, Wert außerhalb -> abgelehnt. */
object DeviceActionParser {
    val ALLOWED_ACTIONS: Set<String> =
        setOf(
            "flashlight", "volume", "open_app", "open_settings", "set_timer", "set_alarm",
            "set_brightness", "dnd", "lock_screen", "screenshot", "open_camera",
        )

    private val PARAMS: Map<String, Set<String>> = mapOf(
        "flashlight" to setOf("state"),
        "volume" to setOf("channel", "mode", "percent"),
        "open_app" to setOf("app"),
        "open_settings" to setOf("page"),
        "set_timer" to setOf("minutes", "label"),
        "set_alarm" to setOf("time", "label"),
        "set_brightness" to setOf("mode", "percent"),
        "dnd" to setOf("state"),
        "lock_screen" to emptySet(),
        "screenshot" to emptySet(),
        "open_camera" to setOf("capture"),
    )

    private const val MAX_APP_LEN = 60
    private const val MAX_LABEL_LEN = 100
    const val MIN_ALARM_VOLUME_PERCENT = 10
    private val TIME_RE = Regex("^(\\d{1,2}):(\\d{2})$")

    private fun rej(reason: String) = ParseResult.Rejected(reason)

    private fun intOf(v: Any?): Int? = when (v) {
        is Int -> v
        is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else null
        // 30.0 wie 30 (das Modell schreibt manchmal Dezimalzahlen); 30.5, NaN und Unendlich nicht
        is Double -> if (!v.isNaN() && !v.isInfinite() && v == Math.rint(v) && v in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) v.toInt() else null
        else -> null
    }

    private fun cleanLabel(v: Any?): String? =
        (v as? String)?.filterNot { it.isISOControl() }?.trim()?.take(MAX_LABEL_LEN)

    fun parse(json: JSONObject): ParseResult {
        if (json.opt("type") != DeviceActionKind.TYPE) return rej("type")
        val name = json.opt("action") as? String ?: return rej("action")
        val allowed = PARAMS[name] ?: return rej("unknown_action")
        val keys = json.keys().asSequence().toSet()
        if (!(keys - setOf("type", "action") - DeviceActionReport.ID_FIELDS).all { it in allowed }) return rej("unknown_param")
        // #183: Kennungen des Servers (Rückkanal) nur in gültiger Form; sie ändern nichts an der Aktion.
        if (json.has("action_id") && DeviceActionReport.actionId(json) == null) return rej("action_id")
        if (json.has("group_id") && DeviceActionReport.groupId(json) == null) return rej("group_id")

        return when (name) {
            "flashlight" -> when (json.opt("state")) {
                "on" -> ParseResult.Ok(DeviceAction.Flashlight(true))
                "off" -> ParseResult.Ok(DeviceAction.Flashlight(false))
                else -> rej("state")
            }
            "volume" -> parseVolume(json)
            "set_brightness" -> parseBrightness(json)
            "dnd" -> when (json.opt("state")) {
                "on" -> ParseResult.Ok(DeviceAction.Dnd(true))
                "off" -> ParseResult.Ok(DeviceAction.Dnd(false))
                else -> rej("state")
            }
            "lock_screen" -> ParseResult.Ok(DeviceAction.LockScreen)
            "screenshot" -> ParseResult.Ok(DeviceAction.Screenshot)
            "open_camera" -> {
                val capture = CameraCapture.fromKey(json.opt("capture") as? String) ?: return rej("capture")
                ParseResult.Ok(DeviceAction.OpenCamera(capture))
            }
            "open_app" -> {
                val app = (json.opt("app") as? String)?.trim() ?: return rej("app")
                if (app.isEmpty() || app.length > MAX_APP_LEN) rej("app") else ParseResult.Ok(DeviceAction.OpenApp(app))
            }
            "open_settings" -> {
                val page = SettingsPageResolver.resolve(json.opt("page") as? String) ?: return rej("page")
                ParseResult.Ok(DeviceAction.OpenSettings(page))
            }
            "set_timer" -> {
                val minutes = intOf(json.opt("minutes")) ?: return rej("minutes")
                if (minutes !in 1..1440) return rej("minutes")
                val label = if (json.has("label")) cleanLabel(json.opt("label")) ?: return rej("label") else ""
                ParseResult.Ok(DeviceAction.SetTimer(minutes, label))
            }
            else -> { // set_alarm
                val m = TIME_RE.matchEntire(json.opt("time") as? String ?: return rej("time")) ?: return rej("time")
                val h = m.groupValues[1].toInt()
                val min = m.groupValues[2].toInt()
                if (h !in 0..23 || min !in 0..59) return rej("time")
                val label = if (json.has("label")) cleanLabel(json.opt("label")) ?: return rej("label") else ""
                ParseResult.Ok(DeviceAction.SetAlarm(h, min, label))
            }
        }
    }

    private fun parseBrightness(json: JSONObject): ParseResult {
        val mode = BrightnessMode.fromKey(json.opt("mode") as? String) ?: return rej("mode")
        var percent: Int? = null
        if (mode == BrightnessMode.SET) {
            percent = intOf(json.opt("percent")) ?: return rej("percent")
            if (percent !in BrightnessMath.MIN_PERCENT..BrightnessMath.MAX_PERCENT) return rej("percent")
        } else if (json.has("percent")) {
            return rej("percent")
        }
        return ParseResult.Ok(DeviceAction.SetBrightness(mode, percent))
    }

    private fun parseVolume(json: JSONObject): ParseResult {
        val channel = VolumeChannel.fromKey(json.opt("channel") as? String) ?: return rej("channel")
        val mode = VolumeMode.fromKey(json.opt("mode") as? String) ?: return rej("mode")
        val hasPercent = json.has("percent")
        var percent: Int? = null
        if (mode == VolumeMode.SET) {
            percent = intOf(json.opt("percent")) ?: return rej("percent")
            if (percent !in 0..100) return rej("percent")
        } else if (hasPercent) {
            return rej("percent")
        }
        // Der Wecker-Ton wird nie stumm oder fast stumm gestellt (sonst klingelt der Wecker nicht).
        if (channel == VolumeChannel.ALARM) {
            if (mode == VolumeMode.MUTE) return rej("alarm_mute")
            if (mode == VolumeMode.SET && percent!! < MIN_ALARM_VOLUME_PERCENT) return rej("alarm_mute")
        }
        return ParseResult.Ok(DeviceAction.Volume(channel, mode, percent))
    }
}

object VolumeMath {
    fun indexFor(percent: Int, max: Int): Int {
        if (max <= 0) return 0
        val p = percent.coerceIn(0, 100)
        return (p * max / 100.0).roundToInt().coerceIn(0, max)
    }

    /** Kleinster Index des Wecker-Kanals: mindestens 10 Prozent, mindestens 1 (sonst klingelt der Wecker nicht). */
    fun alarmFloor(max: Int): Int =
        if (max <= 0) 0 else kotlin.math.ceil(max * DeviceActionParser.MIN_ALARM_VOLUME_PERCENT / 100.0).toInt().coerceIn(1, max)

    fun clampAlarm(index: Int, max: Int): Int = index.coerceAtLeast(alarmFloor(max)).coerceAtMost(max.coerceAtLeast(0))

    fun percentOf(index: Int, max: Int): Int {
        if (max <= 0) return 0
        return (index.coerceIn(0, max) * 100.0 / max).roundToInt()
    }
}

/** Helligkeit: Prozent (5..100) <-> Systemwert 0..255. Nie ganz dunkel (Prozent 0 gibt es nicht). */
object BrightnessMath {
    const val MIN_PERCENT = 5   // Mike 23.11.: Untergrenze 5 Prozent
    const val MAX_PERCENT = 100
    const val STEP_PERCENT = 15
    const val RAW_MAX = 255

    fun toRaw(percent: Int): Int =
        (percent.coerceIn(MIN_PERCENT, MAX_PERCENT) * RAW_MAX / 100.0).roundToInt().coerceIn(1, RAW_MAX)

    fun toPercent(raw: Int): Int =
        (raw.coerceIn(0, RAW_MAX) * 100.0 / RAW_MAX).roundToInt().coerceIn(MIN_PERCENT, MAX_PERCENT)

    /** Zielwert in Prozent: set = absolut, up/down = +/- [STEP_PERCENT], immer auf 5..100 geklemmt. */
    /** Bei Automatik ist der gespeicherte Wert nicht die Anzeige: heller/dunkler nicht raten, Prozentwert erfragen. */
    fun needsExplicitValue(mode: BrightnessMode, automatic: Boolean): Boolean = automatic && mode != BrightnessMode.SET

    fun targetPercent(mode: BrightnessMode, percent: Int?, currentPercent: Int): Int = when (mode) {
        BrightnessMode.SET -> (percent ?: currentPercent).coerceIn(MIN_PERCENT, MAX_PERCENT)
        BrightnessMode.UP -> (currentPercent + STEP_PERCENT).coerceIn(MIN_PERCENT, MAX_PERCENT)
        BrightnessMode.DOWN -> (currentPercent - STEP_PERCENT).coerceIn(MIN_PERCENT, MAX_PERCENT)
    }
}

/** Nicht stören: Filterwerte wie NotificationManager.INTERRUPTION_FILTER_* (hier als Zahlen, damit die Logik rein bleibt). */
object DndLogic {
    const val FILTER_UNKNOWN = 0
    const val FILTER_ALL = 1
    const val FILTER_PRIORITY = 2
    const val FILTER_NONE = 3
    const val FILTER_ALARMS = 4

    /** Rücklesen: bis ~800 ms (8 x 100 ms); auf dem Main-Thread nur kurz (2 x 100 ms), dort darf nicht lange geschlafen werden. */
    const val READBACK_TRIES = 8
    const val READBACK_TRIES_MAIN = 2
    const val READBACK_INTERVAL_MS = 100L

    fun targetFilter(on: Boolean): Int = if (on) FILTER_PRIORITY else FILTER_ALL

    /** Nur bekannte Filter lassen sich als Vorzustand merken. */
    fun isRestorable(filter: Int): Boolean = filter in FILTER_ALL..FILTER_ALARMS

    /** Android 15 kann statt des globalen Filters nur eine App-Regel setzen: gilt nur, wenn der Zustand wirklich so ist. */
    fun applied(requested: Int, actual: Int): Boolean = requested == actual
}

/** Was die einzelne Aktionsart an Sonderrecht braucht (Einstellungen: Zeile mit Status und Knopf). */
enum class PermissionNeed(val settingsAction: String, val needsPackageUri: Boolean = false) {
    WRITE_SETTINGS("android.settings.action.MANAGE_WRITE_SETTINGS", needsPackageUri = true),
    NOTIFICATION_POLICY("android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS"),
    ACCESSIBILITY("android.settings.ACCESSIBILITY_SETTINGS"),
    /** Laufzeitrecht Kalender (lesen + schreiben): geführt über die App-Info-Seite. */
    CALENDAR("android.settings.APPLICATION_DETAILS_SETTINGS", needsPackageUri = true),
}

/** Messbare Tatsachen des Geräts (vom Runner gelesen); die Entscheidungen darauf sind rein und getestet. */
data class PermissionFacts(
    val sdk: Int,
    val canWriteSettings: Boolean,
    val policyAccess: Boolean,
    val accessibilityActive: Boolean,
    val calendarGranted: Boolean = false,
)

object SystemPermissions {
    const val GLOBAL_ACTION_MIN_SDK = 28

    fun needOf(kind: DeviceActionKind): PermissionNeed? = when (kind) {
        DeviceActionKind.BRIGHTNESS -> PermissionNeed.WRITE_SETTINGS
        DeviceActionKind.DND -> PermissionNeed.NOTIFICATION_POLICY
        DeviceActionKind.LOCK_SCREEN, DeviceActionKind.SCREENSHOT -> PermissionNeed.ACCESSIBILITY
        DeviceActionKind.CALENDAR_EDIT -> PermissionNeed.CALENDAR
        else -> null
    }

    /** null = Voraussetzung erfüllt; sonst Ergebnisart (nie stilles Erteilen, nie eine Settings-Seite von selbst). */
    fun precheck(kind: DeviceActionKind, f: PermissionFacts): ResultKind? = when (needOf(kind)) {
        null -> null
        PermissionNeed.WRITE_SETTINGS -> if (f.canWriteSettings) null else ResultKind.NEEDS_PERMISSION
        PermissionNeed.NOTIFICATION_POLICY -> if (f.policyAccess) null else ResultKind.NEEDS_PERMISSION
        PermissionNeed.CALENDAR -> if (f.calendarGranted) null else ResultKind.NEEDS_PERMISSION
        PermissionNeed.ACCESSIBILITY ->
            if (f.sdk < GLOBAL_ACTION_MIN_SDK) ResultKind.UNSUPPORTED
            else if (!f.accessibilityActive) ResultKind.NEEDS_PERMISSION
            else null
    }

    /** "granted" | "missing" | "n/a" für den geführten Hinweis in den Einstellungen. */
    fun stateOf(kind: DeviceActionKind, f: PermissionFacts): String = when {
        needOf(kind) == null -> "n/a"
        precheck(kind, f) == null -> "granted"
        precheck(kind, f) == ResultKind.UNSUPPORTED -> "n/a"
        else -> "missing"
    }
}

/**
 * Screenshot-Datenschutz: ob im aktuellen Vordergrund ausgelöst werden darf. Rein, Paketname-basiert, fail-closed:
 * gesperrt, sensibel (Banking/Passwort/2FA/Broker/Einstellungen) oder unbekannt -> nie. Es wird nie ein Bild gelesen.
 */
sealed class ScreenshotDecision {
    object Allowed : ScreenshotDecision()
    object Blocked : ScreenshotDecision()
    object Unknown : ScreenshotDecision()
}

object ScreenshotGuard {
    /**
     * Zusätzlich zur Sperrliste des Dienstes: Benachrichtigungsleiste/Sperrbildschirm (private Inhalte), Pay-/Wallet-,
     * Passwort-/2FA-, Banking- und Tresor-Apps, die per Namensmuster nicht sicher erkannt würden.
     */
    private val EXTRA_BLOCKED = setOf(
        "com.android.systemui", "com.samsung.android.lool", "com.android.settings.intelligence",
        "com.starfinanz.smob.android.sfinanzstatus", "com.samsung.android.spay", "com.samsung.android.samsungpass",
        "com.samsung.knox.securefolder", "com.google.android.apps.walletnfcrel", "com.google.android.apps.authenticator2",
    )

    /** Verzögerung, damit Donnas Overlay weg ist, bevor das System das Bild macht. */
    const val DELAY_MS = 800L

    /**
     * [visible] = Pakete ALLER sichtbaren Anwendungsfenster (null-Eintrag = Fenster, dessen Paket nicht lesbar ist).
     * Gesperrt/sensibel in IRGENDEINEM fremden Fenster -> Blocked (auch hinter Donnas Overlay/Blase); leer oder ein
     * unlesbares Fenster -> Unknown; nur ausschließlich Donnas eigene Fenster oder harmlose fremde -> Allowed.
     * [labelOf]: Anzeigename des Pakets (leer = unbekannt), damit die Namensmuster auch Label treffen.
     */
    fun decide(
        visible: List<String?>?,
        blocked: Set<String>,
        ownPackage: String,
        labelOf: (String) -> String = { "" },
    ): ScreenshotDecision {
        if (visible.isNullOrEmpty()) return ScreenshotDecision.Unknown
        var unknown = false
        for (raw in visible) {
            val pkg = raw?.trim()
            if (pkg.isNullOrEmpty()) { unknown = true; continue }
            if (pkg == ownPackage) continue
            if (pkg in blocked || pkg in EXTRA_BLOCKED) return ScreenshotDecision.Blocked
            val label = try { labelOf(pkg) } catch (_: Exception) { "" }
            if (AppResolver.isSensitive(AppEntry(label, pkg))) return ScreenshotDecision.Blocked
        }
        return if (unknown) ScreenshotDecision.Unknown else ScreenshotDecision.Allowed
    }
}

/** Ausgang des verzögerten Screenshots als reine Funktion (zweite Prüfung, dann die Systemaktion). */
object ScreenshotFlow {
    fun finish(decision: ScreenshotDecision, perform: () -> Boolean): ResultKind = when (decision) {
        ScreenshotDecision.Allowed -> if (perform()) ResultKind.OK else ResultKind.FAILED
        ScreenshotDecision.Blocked, ScreenshotDecision.Unknown -> ResultKind.BLOCKED
    }

    /** Hinweis an Mike, wenn nach der sofortigen Meldung doch nichts aufgenommen wurde; null nur bei OK. */
    fun lateNotice(kind: ResultKind): String? = when (kind) {
        ResultKind.OK -> null
        ResultKind.BLOCKED -> "Screenshot nicht aufgenommen: ein geschützter Bildschirm war offen."
        ResultKind.NEEDS_PERMISSION -> "Screenshot nicht aufgenommen: die Bedienungshilfe von Donna ist aus."
        else -> "Screenshot nicht aufgenommen: das System hat es nicht erlaubt."
    }
}

/** Mehrere Aktionen in EINEM Aufruf: Sperren und Screenshot zuletzt (stabil), damit nichts nach dem Sperren hängt. */
object DeviceActionOrder {
    private val LAST = setOf("lock_screen", "screenshot")

    /** Ausführungsreihenfolge als Indizes der Eingabe; die Ergebnisse gehören trotzdem an die Eingabeposition. */
    fun executionOrder(actionNames: List<String?>): List<Int> {
        val idx = actionNames.indices
        return idx.filter { actionNames[it] !in LAST } + idx.filter { actionNames[it] in LAST }
    }
}

enum class ResultKind(val key: String) {
    OK("ok"),
    NEEDS_PERMISSION("needs_permission"),
    UNSUPPORTED("unsupported"),
    FAILED("failed"),
    REJECTED("rejected"),
    DISABLED("disabled"),
    AMBIGUOUS("ambiguous"),
    BLOCKED("blocked"),
}

data class DeviceActionResult(
    val kind: ResultKind,
    val message: String,
    val undo: UndoSpec? = null,
    /** Kennung dieser Aktion in der Rückgängig-Ablage; die Taste trägt sie und wirkt NUR auf diese Aktion. */
    val undoId: Long? = null,
    /** Der Rückkanal meldet erst später (Screenshot nach der Verzögerung): der Runner meldet dann nicht sofort. */
    val reportsLater: Boolean = false,
) {
    val ok: Boolean get() = kind == ResultKind.OK

    /** Die Rückgängig-Daten bleiben auf dem Gerät (Ablage); nach außen nur, OB und mit welcher Kennung. */
    fun toJson(): JSONObject {
        val j = JSONObject().put("kind", kind.key).put("message", message).put("undoable", ok && undo != null)
        if (ok && undo != null && undoId != null) j.put("undoId", undoId)
        // Samsung Uhr loescht laufende Timer/Wecker nicht per ACTION_DISMISS_TIMER/_ALARM (am S25 geprueft, #210):
        // die Taste kann nur die Uhr-App oeffnen und muss das auch so beschriften.
        if (ok && undo != null && undo.opensClockApp) j.put("undoOpensClock", true)
        return j
    }
}

/** Kurze, ehrliche Rückmeldungen (sie kommen vom Gerät, nicht vom Modell). Keine Nutzerinhalte außer dem App-Namen. */
object DeviceActionMessages {
    fun ok(a: DeviceAction): String = when (a) {
        is DeviceAction.Flashlight -> if (a.on) "Taschenlampe an." else "Taschenlampe aus."
        is DeviceAction.Volume -> when (a.mode) {
            VolumeMode.SET -> "${a.channel.label} auf ${a.percent} Prozent."
            VolumeMode.UP -> "${a.channel.label} lauter."
            VolumeMode.DOWN -> "${a.channel.label} leiser."
            VolumeMode.MUTE -> "${a.channel.label} stumm."
            VolumeMode.UNMUTE -> "${a.channel.label} wieder an."
        }
        // Intent-Starts ohne Bestätigung der Ziel-App: keine Erfolgsbehauptung
        is DeviceAction.OpenApp -> "Ich versuche, ${a.app} zu öffnen."
        is DeviceAction.OpenSettings -> "Ich versuche, die Einstellungen für ${a.page.label} zu öffnen."
        is DeviceAction.SetTimer -> "Timer für ${a.minutes} ${if (a.minutes == 1) "Minute" else "Minuten"} an die Uhr-App übergeben."
        is DeviceAction.SetAlarm -> "Wecker für %02d:%02d Uhr an die Uhr-App übergeben.".format(Locale.GERMANY, a.hour, a.minute)
        is DeviceAction.SetBrightness -> when (a.mode) {
            BrightnessMode.SET -> "Helligkeit auf ${a.percent} Prozent angestoßen."
            BrightnessMode.UP -> "Helligkeit heller angestoßen."
            BrightnessMode.DOWN -> "Helligkeit dunkler angestoßen."
        }
        is DeviceAction.Dnd ->
            if (a.on) "Nicht stören an. Erinnerungen von Donna können still bleiben." else "Nicht stören aus."
        DeviceAction.LockScreen -> "Bildschirmsperre angestoßen."
        DeviceAction.Screenshot -> "Ich löse gleich einen Screenshot aus (er landet in der Galerie, wenn das System es erlaubt)."
        is DeviceAction.OpenCamera ->
            (if (a.capture == CameraCapture.VIDEO) "Videokamera geöffnet" else "Kamera geöffnet") + " — auslösen musst du selbst."
    }

    /** Fehlende Sonderberechtigung: ehrlich, mit dem Weg dorthin; kein Fehlschlag-Lärm. */
    fun needsPermission(kind: DeviceActionKind): String = when (SystemPermissions.needOf(kind)) {
        PermissionNeed.WRITE_SETTINGS ->
            "Dafür fehlt die Berechtigung „Systemeinstellungen ändern“. Du kannst sie in Donnas Einstellungen unter „Donna darf selbst …“ bei Helligkeit erteilen."
        PermissionNeed.NOTIFICATION_POLICY ->
            "Dafür fehlt der Zugriff auf „Nicht stören“. Du kannst ihn in Donnas Einstellungen unter „Donna darf selbst …“ bei Nicht stören erteilen."
        PermissionNeed.CALENDAR ->
            "Dafür braucht Donna die Berechtigung „Kalender“. Du kannst sie in Donnas Einstellungen unter „Donna darf selbst …“ bei Termine ändern erteilen."
        PermissionNeed.ACCESSIBILITY ->
            "Dafür muss die Bedienungshilfe von Donna eingeschaltet sein (Einstellungen → Bedienungshilfen → Donna)."
        null -> refused(ResultKind.NEEDS_PERMISSION)
    }

    fun brightnessAuto(): String = "Die Helligkeit steht auf Automatik — nenne mir einen Prozentwert."

    fun dndNotApplied(): String = "Nicht stören wurde vom System nicht übernommen."

    fun screenshotBlocked(): String = "Auf diesem Bildschirm mache ich keinen Screenshot."

    fun screenshotUnknown(): String = "Ich erkenne nicht sicher, was gerade offen ist — darum mache ich keinen Screenshot."

    fun refused(kind: ResultKind): String = when (kind) {
        ResultKind.DISABLED -> "Das darf ich noch nicht selbst tun. Du kannst es in den Einstellungen unter „Donna darf selbst …“ einschalten."
        ResultKind.REJECTED -> "Das kann ich so nicht ausführen."
        ResultKind.NEEDS_PERMISSION -> "Dafür fehlt Donna gerade die Berechtigung."
        ResultKind.UNSUPPORTED -> "Das unterstützt dieses Gerät nicht."
        ResultKind.FAILED -> "Das hat nicht geklappt."
        ResultKind.AMBIGUOUS -> "Das ist nicht eindeutig."
        ResultKind.BLOCKED -> "Das öffne ich nicht selbst."
        ResultKind.OK -> ""
    }

    fun blockedApp(label: String): String = "„$label“ öffne ich nicht selbst."

    fun notFoundApp(): String = "Diese App finde ich auf dem Handy nicht."

    fun noSystemCamera(): String = "Die Kamera-App des Handys finde ich nicht — ich öffne keine fremde Kamera-App."

    fun ambiguous(labels: List<String>): String =
        if (labels.size == 1) "Meinst du ${labels[0]}?" else "Welche meinst du: ${labels.joinToString(" oder ")}?"

    /** Nennt, WAS zurückgenommen wurde. Timer/Wecker: nur die Uhr-App wird geöffnet (nie automatisches Verwerfen). */
    fun undone(spec: UndoSpec): String = when (spec) {
        is UndoSpec.VolumeTo -> "${spec.channel.label} wieder auf ${spec.percent} Prozent."
        is UndoSpec.FlashlightTo -> "Taschenlampe wieder ${if (spec.on) "an" else "aus"}."
        UndoSpec.ClockTimers -> "Ich habe die Uhr-App geöffnet — dort kannst du den Timer löschen."
        UndoSpec.ClockAlarms -> "Ich habe die Uhr-App geöffnet — dort kannst du den Wecker löschen."
        is UndoSpec.BrightnessTo -> "Helligkeit wieder wie vorher."
        is UndoSpec.DndTo -> when (spec.filter) {
            DndLogic.FILTER_ALL -> "Nicht stören wieder aus."
            DndLogic.FILTER_PRIORITY -> "Nicht stören wieder an."
            else -> "Nicht stören wieder wie vorher."
        }
    }

    fun undoRefused(take: UndoTake): String = when (take) {
        UndoTake.Superseded -> "Das geht nicht mehr — danach wurde etwas anderes geändert."
        UndoTake.Expired -> undoExpired()
        UndoTake.AlreadyDone -> "Das ist schon rückgängig gemacht."
        UndoTake.None -> nothingToUndo()
        UndoTake.Disabled -> refused(ResultKind.DISABLED)
        is UndoTake.Ready -> ""
    }

    fun undoExpired(): String = "Das lässt sich nicht mehr rückgängig machen."

    fun nothingToUndo(): String = "Das lässt sich nicht mehr rückgängig machen."
}

/** Zählt je Antwort: höchstens [MAX_PER_TURN] Aktionen, keine Wiederholung derselben Aktion. */
class TurnBudget {
    var used: Int = 0
        private set
    private val seen = HashSet<String>()

    fun admit(action: DeviceAction): Boolean {
        if (used >= MAX_PER_TURN) return false
        if (!seen.add(action.dedupeKey)) return false
        used++
        return true
    }

    companion object {
        const val MAX_PER_TURN = 3
    }
}

sealed class GateResult {
    data class Run(val action: DeviceAction) : GateResult()
    data class Refused(val kind: ResultKind, val message: String) : GateResult()
}

/** Eintrittsprüfung auf dem Gerät: Positivliste/Schema, Aktionsart eingeschaltet, Budget. */
object DeviceActionGate {
    fun check(json: JSONObject, enabled: Set<DeviceActionKind>, budget: TurnBudget): GateResult {
        val action = when (val p = DeviceActionParser.parse(json)) {
            is ParseResult.Ok -> p.action
            is ParseResult.Rejected -> return refused(ResultKind.REJECTED)
        }
        if (action.kind !in enabled) return refused(ResultKind.DISABLED)
        if (!budget.admit(action)) return refused(ResultKind.REJECTED)
        return GateResult.Run(action)
    }

    private fun refused(kind: ResultKind) = GateResult.Refused(kind, DeviceActionMessages.refused(kind))
}

/** Rückgängig-Daten (Stufe 1 bzw. merkbare Zustände). */
sealed class UndoSpec {
    /** true: "Rückgängig" öffnet nur die Uhr-App (kein automatisches Löschen möglich). */
    val opensClockApp: Boolean get() = this === ClockTimers || this === ClockAlarms

    abstract val kind: DeviceActionKind

    /** Aktionen mit gleichem Schlüssel überschreiben einander (zweite Lautstärke-Änderung am selben Kanal ...). */
    open val conflictKey: String? get() = null

    data class VolumeTo(val channel: VolumeChannel, val percent: Int) : UndoSpec() {
        override val kind get() = DeviceActionKind.VOLUME
        override val conflictKey get() = "volume:${channel.key}"
    }

    data class FlashlightTo(val on: Boolean) : UndoSpec() {
        override val kind get() = DeviceActionKind.FLASHLIGHT
        override val conflictKey get() = "flashlight"
    }

    /** Helligkeit: alter Systemwert (0..255) UND alter Modus (Automatik an/aus) werden zusammen zurückgesetzt. */
    data class BrightnessTo(val raw: Int, val automatic: Boolean) : UndoSpec() {
        override val kind get() = DeviceActionKind.BRIGHTNESS
        override val conflictKey get() = "brightness"
    }

    /** Nicht stören: vorheriger Interruption-Filter (1..4). */
    data class DndTo(val filter: Int) : UndoSpec() {
        override val kind get() = DeviceActionKind.DND
        override val conflictKey get() = "dnd"
    }

    /** Timer/Wecker: automatisches Verwerfen könnte einen ANDEREN Timer/Wecker treffen -> Uhr-App öffnen. */
    object ClockTimers : UndoSpec() {
        override val kind get() = DeviceActionKind.TIMER_ALARM
        val screen get() = ClockScreen.TIMERS
    }

    object ClockAlarms : UndoSpec() {
        override val kind get() = DeviceActionKind.TIMER_ALARM
        val screen get() = ClockScreen.ALARMS
    }
}

enum class ClockScreen { TIMERS, ALARMS }

sealed class UndoTake {
    data class Ready(val spec: UndoSpec) : UndoTake()
    object None : UndoTake()
    object Expired : UndoTake()
    /** Eine spätere Aktion hat denselben Zustand geändert: die ältere Taste würde deren Ergebnis zerstören. */
    object Superseded : UndoTake()
    object AlreadyDone : UndoTake()
    object Disabled : UndoTake()
}

/**
 * Rückgängig-Ablage: jede ausgeführte Aktion bekommt eine Kennung; die Taste trägt sie und wirkt nur auf ihre eigene
 * Aktion. Die letzten [MAX_ENTRIES] Aktionen, je [TTL_MS] lang (monotone Uhr), einmalig ([commit]), nur solange keine
 * spätere Aktion denselben Zustand geändert hat und die Aktionsart noch eingeschaltet ist. [take] verbraucht nichts:
 * erst [commit] nach erfolgreich angestoßenem Zurücknehmen (sonst bliebe bei einem Fehlschlag nichts mehr übrig).
 */
class UndoStore(private val clock: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private class Entry(val id: Long, val spec: UndoSpec?, val key: String, val at: Long) {
        var done = false
    }

    private val entries = ArrayDeque<Entry>()
    private var nextId = 1L

    /** Hält eine erfolgreiche Aktion fest (spec = null: nichts zurückzunehmen, aber Konfliktschlüssel zählt). */
    @Synchronized fun record(spec: UndoSpec?, key: String? = null): Long {
        val id = nextId++
        entries.addLast(Entry(id, spec, key ?: spec?.conflictKey ?: "#$id", clock()))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        return id
    }

    @Synchronized fun take(id: Long, enabled: Set<DeviceActionKind>): UndoTake {
        val e = entries.firstOrNull { it.id == id } ?: return UndoTake.None
        if (e.done) return UndoTake.AlreadyDone
        if (clock() - e.at > TTL_MS) return UndoTake.Expired
        val spec = e.spec ?: return UndoTake.None
        // #186: eine spätere Aktion, die selbst schon zurückgenommen wurde, hat den Zustand wiederhergestellt -> überholt nicht
        if (entries.any { it.id > e.id && it.key == e.key && !it.done }) return UndoTake.Superseded
        if (spec.kind !in enabled) return UndoTake.Disabled
        return UndoTake.Ready(spec)
    }

    @Synchronized fun commit(id: Long) {
        entries.firstOrNull { it.id == id }?.done = true
    }

    companion object {
        const val MAX_ENTRIES = 3
        const val TTL_MS = 120_000L
    }
}

object DeviceActionUndo {
    fun forVolume(channel: VolumeChannel, previousIndex: Int, max: Int): UndoSpec =
        UndoSpec.VolumeTo(channel, VolumeMath.percentOf(previousIndex, max))

    /** Unbekannter Vorzustand (null) -> kein Rückgängig anbieten. */
    fun forFlashlight(previousOn: Boolean?): UndoSpec? = previousOn?.let { UndoSpec.FlashlightTo(it) }

    /** Unlesbarer Vorzustand (raw < 0) -> kein Rückgängig anbieten. */
    fun forBrightness(previousRaw: Int, previousAutomatic: Boolean): UndoSpec? =
        if (previousRaw < 0) null else UndoSpec.BrightnessTo(previousRaw.coerceAtMost(BrightnessMath.RAW_MAX), previousAutomatic)

    fun forDnd(previousFilter: Int): UndoSpec? =
        if (DndLogic.isRestorable(previousFilter)) UndoSpec.DndTo(previousFilter) else null

    fun forAction(a: DeviceAction): UndoSpec? = when (a) {
        is DeviceAction.SetTimer -> UndoSpec.ClockTimers
        is DeviceAction.SetAlarm -> UndoSpec.ClockAlarms
        else -> null
    }

    /**
     * Aktion, die den alten Zustand wiederherstellt (läuft durch dieselbe Prüfung); null = nur per Absicht.
     * Helligkeit und Nicht stören haben bewusst KEINE Aktions-Form (Automatik-Modus bzw. beliebiger Filter lassen sich
     * im Schema nicht ausdrücken): der Runner stellt sie direkt aus der [UndoSpec] her.
     */
    fun toJson(u: UndoSpec): JSONObject? = when (u) {
        is UndoSpec.VolumeTo -> JSONObject().put("type", DeviceActionKind.TYPE).put("action", "volume")
            .put("channel", u.channel.key).put("mode", "set").put("percent", u.percent)
        is UndoSpec.FlashlightTo -> JSONObject().put("type", DeviceActionKind.TYPE).put("action", "flashlight")
            .put("state", if (u.on) "on" else "off")
        else -> null
    }
}

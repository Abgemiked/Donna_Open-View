package com.yourcompany.donna.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #147 Paket A: reine Logik der Geräte-Aktionen (Positivliste, Schema, Grenzen, Stufen, Einschaltung je Aktionsart,
 * Zuordnungstabellen, Rückgängig-Daten). Keine Android-Aufrufe; alles fail-closed: Unbekanntes wird abgelehnt.
 */
class DeviceActionsTest {

    private fun act(vararg pairs: Pair<String, Any?>): JSONObject =
        JSONObject().apply {
            put("type", "device_action")
            pairs.forEach { (k, v) -> put(k, v) }
        }

    private fun ok(json: JSONObject): DeviceAction {
        val r = DeviceActionParser.parse(json)
        assertTrue("erwartet Ok, war $r", r is ParseResult.Ok)
        return (r as ParseResult.Ok).action
    }

    private fun rejected(json: JSONObject) =
        assertTrue("erwartet Rejected fuer $json", DeviceActionParser.parse(json) is ParseResult.Rejected)

    // ── Positivliste ────────────────────────────────────────────────────────────
    @Test
    fun `Positivliste enthaelt genau die elf Aktionen`() {
        assertEquals(
            setOf(
                "flashlight", "volume", "open_app", "open_settings", "set_timer", "set_alarm",
                "set_brightness", "dnd", "lock_screen", "screenshot", "open_camera",
            ),
            DeviceActionParser.ALLOWED_ACTIONS,
        )
    }

    @Test
    fun `unbekannte Aktion wird abgelehnt - auch gefaehrliche Namen`() {
        for (name in listOf("send_sms", "call", "pay", "control_home", "lock", "wifi_off", "take_screenshot", "brightness", "", "FLASHLIGHT ")) {
            rejected(act("action" to name))
        }
    }

    @Test
    fun `falscher oder fehlender Typ wird abgelehnt`() {
        rejected(JSONObject().put("type", "set_timer").put("action", "flashlight").put("state", "on"))
        rejected(JSONObject().put("action", "flashlight").put("state", "on"))
    }

    @Test
    fun `unbekannter Parameter wird abgelehnt`() {
        rejected(act("action" to "flashlight", "state" to "on", "extra" to "x"))
        rejected(act("action" to "open_app", "app" to "Kamera", "package" to "com.evil"))
        rejected(act("action" to "set_timer", "minutes" to 5, "number" to "0123"))
    }

    // ── Aktionsarten und Stufen ─────────────────────────────────────────────────
    @Test
    fun `Aktionsart und Stufe je Aktion`() {
        val cases = listOf(
            act("action" to "flashlight", "state" to "on") to Pair(DeviceActionKind.FLASHLIGHT, 0),
            act("action" to "volume", "channel" to "media", "mode" to "up") to Pair(DeviceActionKind.VOLUME, 0),
            act("action" to "open_app", "app" to "Kamera") to Pair(DeviceActionKind.OPEN_APP, 0),
            act("action" to "open_settings", "page" to "wifi") to Pair(DeviceActionKind.OPEN_SETTINGS, 0),
            act("action" to "set_timer", "minutes" to 5) to Pair(DeviceActionKind.TIMER_ALARM, 1),
            act("action" to "set_alarm", "time" to "07:30") to Pair(DeviceActionKind.TIMER_ALARM, 1),
        )
        for ((json, expected) in cases) {
            val a = ok(json)
            assertEquals(expected.first, a.kind)
            assertEquals(expected.second, a.level)
        }
    }

    @Test
    fun `Einschalt-Schluessel sind stabil und eindeutig`() {
        assertEquals(
            listOf(
                "flashlight", "volume", "open_app", "open_settings", "timer_alarm",
                "brightness", "dnd", "lock_screen", "screenshot", "open_camera", "calendar_edit", "email_draft",
            ),
            DeviceActionKind.values().map { it.key },
        )
        assertEquals(DeviceActionKind.TIMER_ALARM, DeviceActionKind.fromKey("timer_alarm"))
        assertNull(DeviceActionKind.fromKey("lock"))
        assertNull(DeviceActionKind.fromKey(null))
    }

    @Test
    fun `Overlay und Sprachweg bieten Terminaenderung nie an (Karte nur im Chat)`() {
        val all = DeviceActionKind.values().toList()
        val keys = DeviceActionKind.keysWithoutChatOnly(all)
        assertFalse("calendar_edit" in keys)
        assertFalse("email_draft" in keys)   // Paket I: Mailkarte nur im Chat
        assertEquals(all.size - 2, keys.size)
        assertEquals(setOf(DeviceActionKind.CALENDAR_EDIT, DeviceActionKind.EMAIL_DRAFT), DeviceActionKind.CHAT_ONLY)
    }

    @Test
    fun `Standard ist fuer jede Aktionsart aus`() {
        assertFalse(DeviceActionKind.values().any { DeviceActionKind.DEFAULT_ENABLED })
        assertEquals("device_action_flashlight", DeviceActionKind.FLASHLIGHT.prefKey)
    }

    @Test
    fun `eingeschaltete Arten aus Rohliste - Unbekanntes und Doppeltes faellt weg`() {
        assertEquals(
            setOf(DeviceActionKind.FLASHLIGHT, DeviceActionKind.OPEN_APP),
            DeviceActionKind.parseEnabled(listOf("flashlight", "open_app", "lock", "flashlight", "")),
        )
        assertEquals(emptySet<DeviceActionKind>(), DeviceActionKind.parseEnabled(null))
    }

    // ── Taschenlampe ────────────────────────────────────────────────────────────
    @Test
    fun `Taschenlampe an und aus`() {
        assertEquals(DeviceAction.Flashlight(true), ok(act("action" to "flashlight", "state" to "on")))
        assertEquals(DeviceAction.Flashlight(false), ok(act("action" to "flashlight", "state" to "off")))
        rejected(act("action" to "flashlight", "state" to "blink"))
        rejected(act("action" to "flashlight"))
        rejected(act("action" to "flashlight", "state" to true))
    }

    // ── Lautstaerke ─────────────────────────────────────────────────────────────
    @Test
    fun `Lautstaerke setzen mit Prozent 0 bis 100`() {
        assertEquals(
            DeviceAction.Volume(VolumeChannel.MEDIA, VolumeMode.SET, 30),
            ok(act("action" to "volume", "channel" to "media", "mode" to "set", "percent" to 30)),
        )
        assertEquals(0, (ok(act("action" to "volume", "channel" to "ring", "mode" to "set", "percent" to 0)) as DeviceAction.Volume).percent)
        assertEquals(100, (ok(act("action" to "volume", "channel" to "call", "mode" to "set", "percent" to 100)) as DeviceAction.Volume).percent)
    }

    @Test
    fun `Lautstaerke ausserhalb 0 bis 100 oder ohne ganze Zahl wird abgelehnt`() {
        rejected(act("action" to "volume", "channel" to "media", "mode" to "set", "percent" to 101))
        rejected(act("action" to "volume", "channel" to "media", "mode" to "set", "percent" to -1))
        rejected(act("action" to "volume", "channel" to "media", "mode" to "set", "percent" to 30.5))
        rejected(act("action" to "volume", "channel" to "media", "mode" to "set", "percent" to "30"))
        rejected(act("action" to "volume", "channel" to "media", "mode" to "set"))
    }

    @Test
    fun `Lautstaerke Kanalnamen und Modi sind eine feste Liste`() {
        assertEquals(listOf("media", "ring", "alarm", "call"), VolumeChannel.values().map { it.key })
        assertEquals(listOf("set", "up", "down", "mute", "unmute"), VolumeMode.values().map { it.key })
        rejected(act("action" to "volume", "channel" to "notification", "mode" to "up"))
        rejected(act("action" to "volume", "channel" to "media", "mode" to "max"))
        rejected(act("action" to "volume", "mode" to "up"))
    }

    @Test
    fun `Lautstaerke relativ und stumm tragen keinen Prozentwert`() {
        assertEquals(DeviceAction.Volume(VolumeChannel.MEDIA, VolumeMode.UP, null), ok(act("action" to "volume", "channel" to "media", "mode" to "up")))
        assertEquals(DeviceAction.Volume(VolumeChannel.RING, VolumeMode.MUTE, null), ok(act("action" to "volume", "channel" to "ring", "mode" to "mute")))
        rejected(act("action" to "volume", "channel" to "media", "mode" to "up", "percent" to 10))
    }

    @Test
    fun `der Wecker-Ton wird nie stumm oder fast stumm gestellt`() {
        rejected(act("action" to "volume", "channel" to "alarm", "mode" to "mute"))
        rejected(act("action" to "volume", "channel" to "alarm", "mode" to "set", "percent" to 0))
        rejected(act("action" to "volume", "channel" to "alarm", "mode" to "set", "percent" to 9))
        ok(act("action" to "volume", "channel" to "alarm", "mode" to "set", "percent" to 10))
        ok(act("action" to "volume", "channel" to "alarm", "mode" to "up"))
    }

    @Test
    fun `Lautstaerke Prozent zu Index und zurueck`() {
        assertEquals(0, VolumeMath.indexFor(0, 15))
        assertEquals(15, VolumeMath.indexFor(100, 15))
        assertEquals(5, VolumeMath.indexFor(30, 15)) // 4,5 rundet auf 5
        assertEquals(1, VolumeMath.indexFor(1, 15).coerceAtLeast(1))
        assertEquals(0, VolumeMath.indexFor(50, 0))
        assertEquals(15, VolumeMath.indexFor(500, 15)) // nie ueber das Maximum
        assertEquals(0, VolumeMath.indexFor(-5, 15))
        assertEquals(0, VolumeMath.percentOf(0, 15))
        assertEquals(100, VolumeMath.percentOf(15, 15))
        assertEquals(33, VolumeMath.percentOf(5, 15))
        assertEquals(0, VolumeMath.percentOf(5, 0))
    }

    // ── Timer ───────────────────────────────────────────────────────────────────
    @Test
    fun `Timer Dauer 1 bis 1440 Minuten`() {
        assertEquals(DeviceAction.SetTimer(5, ""), ok(act("action" to "set_timer", "minutes" to 5)))
        assertEquals(1440, (ok(act("action" to "set_timer", "minutes" to 1440)) as DeviceAction.SetTimer).minutes)
        rejected(act("action" to "set_timer", "minutes" to 0))
        rejected(act("action" to "set_timer", "minutes" to 1441))
        rejected(act("action" to "set_timer", "minutes" to -3))
        rejected(act("action" to "set_timer", "minutes" to "5"))
        rejected(act("action" to "set_timer", "minutes" to 2.5))
        rejected(act("action" to "set_timer"))
    }

    // ── Wecker ──────────────────────────────────────────────────────────────────
    @Test
    fun `Weckzeit HH MM mit gueltigen Grenzen`() {
        assertEquals(DeviceAction.SetAlarm(7, 30, ""), ok(act("action" to "set_alarm", "time" to "07:30")))
        assertEquals(DeviceAction.SetAlarm(0, 0, ""), ok(act("action" to "set_alarm", "time" to "00:00")))
        assertEquals(DeviceAction.SetAlarm(23, 59, ""), ok(act("action" to "set_alarm", "time" to "23:59")))
        for (bad in listOf("24:00", "7:60", "7", "07:3", "abc", "", "07:30:00", "-1:00")) {
            rejected(act("action" to "set_alarm", "time" to bad))
        }
        rejected(act("action" to "set_alarm"))
    }

    @Test
    fun `Bezeichnung wird gekuerzt und von Steuerzeichen befreit`() {
        val long = "x".repeat(300)
        val t = ok(act("action" to "set_timer", "minutes" to 5, "label" to long)) as DeviceAction.SetTimer
        assertEquals(100, t.label.length)
        val a = ok(act("action" to "set_alarm", "time" to "06:00", "label" to "Auf\nstehen\u0000!")) as DeviceAction.SetAlarm
        assertEquals("Aufstehen!", a.label)
        rejected(act("action" to "set_timer", "minutes" to 5, "label" to 5))
    }

    // ── Apps oeffnen / Einstellungsseiten ───────────────────────────────────────
    @Test
    fun `App-Name ist Pflicht und begrenzt`() {
        assertEquals(DeviceAction.OpenApp("Kamera"), ok(act("action" to "open_app", "app" to "  Kamera ")))
        rejected(act("action" to "open_app", "app" to ""))
        rejected(act("action" to "open_app", "app" to "   "))
        rejected(act("action" to "open_app", "app" to "a".repeat(61)))
        rejected(act("action" to "open_app", "app" to 5))
        rejected(act("action" to "open_app"))
    }

    @Test
    fun `Einstellungsseite - Schluessel und deutsche Begriffe`() {
        assertEquals(SettingsPage.WIFI, ok(act("action" to "open_settings", "page" to "wifi")).let { (it as DeviceAction.OpenSettings).page })
        for ((term, page) in listOf(
            "WLAN" to SettingsPage.WIFI, "wlan" to SettingsPage.WIFI, "Wi-Fi" to SettingsPage.WIFI,
            "WLAN-Einstellungen" to SettingsPage.WIFI, "die WLAN Einstellungen" to SettingsPage.WIFI,
            "Bluetooth" to SettingsPage.BLUETOOTH,
            "mobile Daten" to SettingsPage.MOBILE_DATA, "Mobilfunk" to SettingsPage.MOBILE_DATA,
            "Display" to SettingsPage.DISPLAY, "Bildschirm" to SettingsPage.DISPLAY,
            "Ton" to SettingsPage.SOUND, "Töne" to SettingsPage.SOUND, "Toene" to SettingsPage.SOUND,
            "Akku" to SettingsPage.BATTERY, "Batterie" to SettingsPage.BATTERY,
            "Standort" to SettingsPage.LOCATION,
            "Benachrichtigungen" to SettingsPage.NOTIFICATIONS,
            "App-Info" to SettingsPage.APP_INFO,
            "Bedienungshilfen" to SettingsPage.ACCESSIBILITY,
            "Flugmodus" to SettingsPage.AIRPLANE,
            "Internet" to SettingsPage.INTERNET_PANEL,
            "Lautstärke" to SettingsPage.VOLUME_PANEL,
        )) {
            assertEquals("Begriff $term", page, SettingsPageResolver.resolve(term))
        }
    }

    @Test
    fun `Einstellungsseite - Unbekanntes und Gefaehrliches wird nicht geraten`() {
        for (bad in listOf("", "  ", "Sicherheit", "Passwörter", "Entwickleroptionen", "Zahlungen", "wifi; rm", "xyz", "Konto")) {
            assertNull("Begriff $bad", SettingsPageResolver.resolve(bad))
            rejected(act("action" to "open_settings", "page" to bad))
        }
        rejected(act("action" to "open_settings"))
    }

    @Test
    fun `Einstellungsseiten tragen oeffentliche Absichten ohne eigene Pakete Dritter`() {
        assertEquals("android.settings.WIFI_SETTINGS", SettingsPage.WIFI.intentAction)
        assertEquals("android.settings.BLUETOOTH_SETTINGS", SettingsPage.BLUETOOTH.intentAction)
        assertTrue(SettingsPage.INTERNET_PANEL.panel)
        assertTrue(SettingsPage.VOLUME_PANEL.panel)
        assertEquals("android.settings.panel.action.INTERNET_CONNECTIVITY", SettingsPage.INTERNET_PANEL.intentAction)
        assertTrue(SettingsPage.values().all { it.intentAction.startsWith("android.settings.") })
        // Schluessel eindeutig
        assertEquals(SettingsPage.values().size, SettingsPage.values().map { it.key }.toSet().size)
    }

    // ── Apps: Name -> Paket ─────────────────────────────────────────────────────
    private val apps = listOf(
        AppEntry("Kamera", "com.samsung.android.camera"),
        AppEntry("Spotify", "com.spotify.music"),
        AppEntry("YouTube", "com.google.android.youtube"),
        AppEntry("YouTube Music", "com.google.android.apps.youtube.music"),
        AppEntry("Telefon", "com.samsung.android.dialer"),
        AppEntry("Google Maps", "com.google.android.apps.maps"),
        AppEntry("Google Fotos", "com.google.android.apps.photos"),
        AppEntry("Google Kalender", "com.google.android.calendar"),
        AppEntry("Samsung Kalender", "com.samsung.android.calendar"),
        AppEntry("Sparkasse", "com.sparkasse.android"),
        AppEntry("Meine Bank", "de.example.meinebank"),
        AppEntry("Bitwarden", "com.bitwarden.mobile"),
        AppEntry("Einstellungen", "com.android.settings"),
    )
    private val blocked = setOf("com.android.settings", "com.sparkasse.android", "com.bitwarden.mobile")

    @Test
    fun `App eindeutiger Treffer ignoriert Gross-Klein und Umlaute`() {
        assertEquals(AppMatch.Found(AppEntry("Kamera", "com.samsung.android.camera")), AppResolver.resolve("kamera", apps, blocked))
        assertEquals(AppMatch.Found(AppEntry("Spotify", "com.spotify.music")), AppResolver.resolve("SPOTIFY", apps, blocked))
        assertEquals(AppMatch.Found(AppEntry("Google Maps", "com.google.android.apps.maps")), AppResolver.resolve("Maps", apps, blocked))
        assertEquals(AppMatch.Found(AppEntry("Google Maps", "com.google.android.apps.maps")), AppResolver.resolve("google maps", apps, blocked))
    }

    @Test
    fun `App genauer Name schlaegt Teiltreffer`() {
        // "YouTube" ist auch Anfang von "YouTube Music", der genaue Name gewinnt
        assertEquals(AppMatch.Found(AppEntry("YouTube", "com.google.android.youtube")), AppResolver.resolve("youtube", apps, blocked))
        assertEquals(AppMatch.Found(AppEntry("YouTube Music", "com.google.android.apps.youtube.music")), AppResolver.resolve("YouTube Music", apps, blocked))
        assertEquals(AppMatch.Found(AppEntry("YouTube", "com.google.android.youtube")), AppResolver.resolve("you tube", apps, blocked))
    }

    @Test
    fun `App mehrdeutig - nachfragen statt raten`() {
        val m = AppResolver.resolve("Kalender", apps, blocked)
        assertTrue(m is AppMatch.Ambiguous)
        assertEquals(setOf("Google Kalender", "Samsung Kalender"), (m as AppMatch.Ambiguous).labels.toSet())
    }

    @Test
    fun `App ohne Treffer oder zu kurze Anfrage`() {
        assertEquals(AppMatch.NotFound, AppResolver.resolve("Fantasie-App", apps, blocked))
        assertEquals(AppMatch.NotFound, AppResolver.resolve("", apps, blocked))
        assertEquals(AppMatch.NotFound, AppResolver.resolve("ka", apps, blocked)) // Teiltreffer erst ab 3 Zeichen
    }

    @Test
    fun `App von der Sperrliste wird nie gestartet`() {
        assertEquals(AppMatch.Blocked("Sparkasse"), AppResolver.resolve("Sparkasse", apps, blocked))
        assertEquals(AppMatch.Blocked("Bitwarden"), AppResolver.resolve("bitwarden", apps, blocked))
        assertEquals(AppMatch.Blocked("Einstellungen"), AppResolver.resolve("Einstellungen", apps, blocked))
    }

    @Test
    fun `App Banking Zahlung Passwort 2FA auch ohne Eintrag auf der Sperrliste gesperrt`() {
        val extra = listOf(
            AppEntry("Meine Bank", "de.example.meinebank"),
            AppEntry("PayPal", "com.paypal.android.p2pmobile"),
            AppEntry("Google Wallet", "com.google.android.apps.walletnfcrel"),
            AppEntry("Authenticator", "com.example.auth"),
            AppEntry("Passwort Tresor", "org.example.vault"),
            AppEntry("Mobiles Bezahlen", "de.example.pay"),
        )
        for (name in listOf("Meine Bank", "PayPal", "Google Wallet", "Authenticator", "Passwort Tresor", "Mobiles Bezahlen")) {
            val m = AppResolver.resolve(name, extra, emptySet())
            assertTrue("$name -> $m", m is AppMatch.Blocked)
        }
        assertFalse(AppResolver.isSensitive(AppEntry("Spotify", "com.spotify.music")))
        assertFalse(AppResolver.isSensitive(AppEntry("Kamera", "com.samsung.android.camera")))
    }

    @Test
    fun `Mehrdeutigkeit mit gesperrter App meldet Sperre statt eine andere zu starten`() {
        val list = listOf(AppEntry("Kalender Bank", "de.example.kalenderbank"), AppEntry("Kalender", "de.example.kalender"))
        // genauer Name "Kalender" ist erlaubt und eindeutig
        assertEquals(AppMatch.Found(AppEntry("Kalender", "de.example.kalender")), AppResolver.resolve("Kalender", list, emptySet()))
    }

    // ── Freigabe je Aktionsart, Budget, Gate ────────────────────────────────────
    private val all = DeviceActionKind.values().toSet()

    @Test
    fun `Gate - ausgeschaltete Art wird nicht ausgefuehrt`() {
        val g = DeviceActionGate.check(act("action" to "flashlight", "state" to "on"), emptySet(), TurnBudget())
        assertTrue(g is GateResult.Refused)
        assertEquals(ResultKind.DISABLED, (g as GateResult.Refused).kind)
        val g2 = DeviceActionGate.check(act("action" to "flashlight", "state" to "on"), setOf(DeviceActionKind.VOLUME), TurnBudget())
        assertEquals(ResultKind.DISABLED, (g2 as GateResult.Refused).kind)
    }

    @Test
    fun `Gate - eingeschaltete Art laeuft, Timer und Wecker teilen sich einen Schalter`() {
        val on = setOf(DeviceActionKind.TIMER_ALARM)
        assertTrue(DeviceActionGate.check(act("action" to "set_timer", "minutes" to 3), on, TurnBudget()) is GateResult.Run)
        assertTrue(DeviceActionGate.check(act("action" to "set_alarm", "time" to "07:00"), on, TurnBudget()) is GateResult.Run)
        assertEquals(
            ResultKind.DISABLED,
            (DeviceActionGate.check(act("action" to "volume", "channel" to "media", "mode" to "up"), on, TurnBudget()) as GateResult.Refused).kind,
        )
    }

    @Test
    fun `Gate - Unbekanntes wird abgelehnt auch wenn alles eingeschaltet ist`() {
        val g = DeviceActionGate.check(act("action" to "send_sms"), all, TurnBudget())
        assertEquals(ResultKind.REJECTED, (g as GateResult.Refused).kind)
    }

    @Test
    fun `Budget - hoechstens drei Aktionen je Antwort`() {
        val b = TurnBudget()
        val first = listOf(
            act("action" to "flashlight", "state" to "on"),
            act("action" to "volume", "channel" to "media", "mode" to "up"),
            act("action" to "open_settings", "page" to "wifi"),
        )
        first.forEach { assertTrue(DeviceActionGate.check(it, all, b) is GateResult.Run) }
        val fourth = DeviceActionGate.check(act("action" to "set_timer", "minutes" to 5), all, b)
        assertEquals(ResultKind.REJECTED, (fourth as GateResult.Refused).kind)
        assertEquals(3, TurnBudget.MAX_PER_TURN)
    }

    @Test
    fun `Budget - dieselbe Aktion nicht zweimal in einer Antwort`() {
        val b = TurnBudget()
        assertTrue(DeviceActionGate.check(act("action" to "flashlight", "state" to "on"), all, b) is GateResult.Run)
        val again = DeviceActionGate.check(act("action" to "flashlight", "state" to "off"), all, b)
        assertEquals(ResultKind.REJECTED, (again as GateResult.Refused).kind)
        // Lautstaerke je Kanal darf getrennt vorkommen, derselbe Kanal nicht
        assertTrue(DeviceActionGate.check(act("action" to "volume", "channel" to "media", "mode" to "up"), all, b) is GateResult.Run)
        assertTrue(DeviceActionGate.check(act("action" to "volume", "channel" to "ring", "mode" to "down"), all, b) is GateResult.Run)
        val sameChannel = DeviceActionGate.check(act("action" to "volume", "channel" to "media", "mode" to "down"), all, TurnBudget().also {
            DeviceActionGate.check(act("action" to "volume", "channel" to "media", "mode" to "up"), all, it)
        })
        assertEquals(ResultKind.REJECTED, (sameChannel as GateResult.Refused).kind)
    }

    @Test
    fun `Budget - abgelehnte Aktion verbraucht kein Kontingent`() {
        val b = TurnBudget()
        repeat(5) { DeviceActionGate.check(act("action" to "send_sms"), all, b) }
        assertEquals(0, b.used)
        DeviceActionGate.check(act("action" to "flashlight", "state" to "on"), emptySet(), b) // ausgeschaltet
        assertEquals(0, b.used)
        DeviceActionGate.check(act("action" to "flashlight", "state" to "on"), all, b)
        assertEquals(1, b.used)
    }

    // ── Ergebnisarten und Texte ─────────────────────────────────────────────────
    @Test
    fun `Ergebnisarten haben stabile Schluessel`() {
        assertEquals(
            listOf("ok", "needs_permission", "unsupported", "failed", "rejected", "disabled", "ambiguous", "blocked"),
            ResultKind.values().map { it.key },
        )
    }

    @Test
    fun `Meldungen sind ehrlich und kurz`() {
        assertEquals("Taschenlampe an.", DeviceActionMessages.ok(DeviceAction.Flashlight(true)))
        assertEquals("Taschenlampe aus.", DeviceActionMessages.ok(DeviceAction.Flashlight(false)))
        // Intent-Start ohne Bestätigung der Ziel-App: keine Erfolgsbehauptung
        assertEquals("Ich versuche, Kamera zu öffnen.", DeviceActionMessages.ok(DeviceAction.OpenApp("Kamera")))
        assertEquals("Timer für 5 Minuten an die Uhr-App übergeben.", DeviceActionMessages.ok(DeviceAction.SetTimer(5, "")))
        assertEquals("Timer für 1 Minute an die Uhr-App übergeben.", DeviceActionMessages.ok(DeviceAction.SetTimer(1, "")))
        assertEquals("Wecker für 07:05 Uhr an die Uhr-App übergeben.", DeviceActionMessages.ok(DeviceAction.SetAlarm(7, 5, "")))
        assertEquals("Medien auf 30 Prozent.", DeviceActionMessages.ok(DeviceAction.Volume(VolumeChannel.MEDIA, VolumeMode.SET, 30)))
        assertEquals("Ich versuche, die Einstellungen für WLAN zu öffnen.", DeviceActionMessages.ok(DeviceAction.OpenSettings(SettingsPage.WIFI)))
        assertTrue(DeviceActionMessages.refused(ResultKind.DISABLED).contains("Einstellungen"))
        assertTrue(DeviceActionMessages.blockedApp("Sparkasse").contains("Sparkasse"))
        assertTrue(DeviceActionMessages.ambiguous(listOf("Google Kalender", "Samsung Kalender")).contains("Google Kalender"))
        // keine Behauptung, die App wuerde etwas ausloesen, das sie nicht getan hat
        assertFalse(DeviceActionMessages.refused(ResultKind.FAILED).lowercase().contains("erledigt"))
    }

    // ── Rueckgaengig-Daten ──────────────────────────────────────────────────────
    @Test
    fun `Rueckgaengig Lautstaerke merkt den alten Wert`() {
        val u = DeviceActionUndo.forVolume(VolumeChannel.MEDIA, previousIndex = 6, max = 15)
        assertEquals(UndoSpec.VolumeTo(VolumeChannel.MEDIA, 40), u)
    }

    @Test
    fun `Rueckgaengig Taschenlampe ist der Gegenzustand`() {
        assertEquals(UndoSpec.FlashlightTo(false), DeviceActionUndo.forFlashlight(previousOn = false))
        assertEquals(UndoSpec.FlashlightTo(true), DeviceActionUndo.forFlashlight(previousOn = true))
    }

    @Test
    fun `Rueckgaengig Timer und Wecker sind Stufe 1 mit Gegenabsicht`() {
        assertEquals(UndoSpec.ClockTimers, DeviceActionUndo.forAction(DeviceAction.SetTimer(5, "")))
        assertEquals(UndoSpec.ClockAlarms, DeviceActionUndo.forAction(DeviceAction.SetAlarm(7, 30, "")))
        assertNull(DeviceActionUndo.forAction(DeviceAction.OpenApp("Kamera")))
        assertNull(DeviceActionUndo.forAction(DeviceAction.OpenSettings(SettingsPage.WIFI)))
    }

    @Test
    fun `Rueckgaengig-Spec wird als Aktion zurueckgespielt und besteht die eigene Pruefung`() {
        val j = DeviceActionUndo.toJson(UndoSpec.VolumeTo(VolumeChannel.MEDIA, 40))!!
        assertEquals(DeviceAction.Volume(VolumeChannel.MEDIA, VolumeMode.SET, 40), ok(j))
        val f = DeviceActionUndo.toJson(UndoSpec.FlashlightTo(false))!!
        assertEquals(DeviceAction.Flashlight(false), ok(f))
    }
}

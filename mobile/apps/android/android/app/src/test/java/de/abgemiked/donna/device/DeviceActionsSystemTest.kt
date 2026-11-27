package com.yourcompany.donna.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #147 Paket F: Systemaktionen (Helligkeit, Nicht stören, Sperren, Screenshot, Kamera) — reine Logik. Keine
 * Android-Aufrufe; alles fail-closed: Unbekanntes, Parameter außerhalb der Grenzen und Unklarheiten werden abgelehnt.
 */
class DeviceActionsSystemTest {

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

    private val all = DeviceActionKind.values().toSet()

    // ── Parser ──────────────────────────────────────────────────────────────────
    @Test
    fun `Helligkeit - gueltige Formen`() {
        assertEquals(DeviceAction.SetBrightness(BrightnessMode.SET, 50), ok(act("action" to "set_brightness", "mode" to "set", "percent" to 50)))
        assertEquals(DeviceAction.SetBrightness(BrightnessMode.SET, 5), ok(act("action" to "set_brightness", "mode" to "set", "percent" to 5)))
        assertEquals(DeviceAction.SetBrightness(BrightnessMode.SET, 100), ok(act("action" to "set_brightness", "mode" to "set", "percent" to 100)))
        assertEquals(DeviceAction.SetBrightness(BrightnessMode.SET, 40), ok(act("action" to "set_brightness", "mode" to "set", "percent" to 40.0)))
        assertEquals(DeviceAction.SetBrightness(BrightnessMode.UP, null), ok(act("action" to "set_brightness", "mode" to "up")))
        assertEquals(DeviceAction.SetBrightness(BrightnessMode.DOWN, null), ok(act("action" to "set_brightness", "mode" to "down")))
    }

    @Test
    fun `Helligkeit - Grenzen und Fehlformen werden abgelehnt`() {
        rejected(act("action" to "set_brightness", "mode" to "set", "percent" to 0))
        rejected(act("action" to "set_brightness", "mode" to "set", "percent" to 101))
        rejected(act("action" to "set_brightness", "mode" to "set", "percent" to -5))
        rejected(act("action" to "set_brightness", "mode" to "set", "percent" to 50.5))
        rejected(act("action" to "set_brightness", "mode" to "set", "percent" to "50"))
        rejected(act("action" to "set_brightness", "mode" to "set")) // percent Pflicht bei set
        rejected(act("action" to "set_brightness", "mode" to "up", "percent" to 20)) // percent nur bei set
        rejected(act("action" to "set_brightness", "mode" to "down", "percent" to 20))
        rejected(act("action" to "set_brightness")) // mode Pflicht
        rejected(act("action" to "set_brightness", "mode" to "max"))
        rejected(act("action" to "set_brightness", "mode" to "mute"))
        rejected(act("action" to "set_brightness", "mode" to "up", "auto" to true)) // unbekannter Parameter
    }

    @Test
    fun `Nicht stoeren - gueltig und ungueltig`() {
        assertEquals(DeviceAction.Dnd(true), ok(act("action" to "dnd", "state" to "on")))
        assertEquals(DeviceAction.Dnd(false), ok(act("action" to "dnd", "state" to "off")))
        rejected(act("action" to "dnd"))
        rejected(act("action" to "dnd", "state" to "total_silence"))
        rejected(act("action" to "dnd", "state" to true))
        rejected(act("action" to "dnd", "state" to "on", "filter" to 3))
    }

    @Test
    fun `Sperren und Screenshot - ohne Parameter`() {
        assertEquals(DeviceAction.LockScreen, ok(act("action" to "lock_screen")))
        assertEquals(DeviceAction.Screenshot, ok(act("action" to "screenshot")))
        rejected(act("action" to "lock_screen", "delay" to 5))
        rejected(act("action" to "screenshot", "save_to" to "x"))
        rejected(act("action" to "screenshot", "upload" to true))
    }

    @Test
    fun `Kamera - capture ist Pflicht und streng`() {
        assertEquals(DeviceAction.OpenCamera(CameraCapture.PHOTO), ok(act("action" to "open_camera", "capture" to "photo")))
        assertEquals(DeviceAction.OpenCamera(CameraCapture.VIDEO), ok(act("action" to "open_camera", "capture" to "video")))
        rejected(act("action" to "open_camera"))
        rejected(act("action" to "open_camera", "capture" to "selfie"))
        rejected(act("action" to "open_camera", "capture" to "Photo"))
        rejected(act("action" to "open_camera", "capture" to "photo", "shutter" to true))
    }

    @Test
    fun `Kennungen des Servers sind fuer die neuen Arten erlaubt, ungueltige nicht`() {
        ok(act("action" to "screenshot", "action_id" to "da_0123456789abcdef", "group_id" to "g_0123456789abcdef"))
        rejected(act("action" to "screenshot", "action_id" to "boom"))
    }

    @Test
    fun `Stufen und Arten stimmen mit dem Vertrag ueberein`() {
        val table = mapOf(
            act("action" to "set_brightness", "mode" to "up") to Pair(DeviceActionKind.BRIGHTNESS, 1),
            act("action" to "dnd", "state" to "on") to Pair(DeviceActionKind.DND, 1),
            act("action" to "lock_screen") to Pair(DeviceActionKind.LOCK_SCREEN, 1),
            act("action" to "screenshot") to Pair(DeviceActionKind.SCREENSHOT, 1),
            act("action" to "open_camera", "capture" to "photo") to Pair(DeviceActionKind.OPEN_CAMERA, 0),
        )
        for ((json, expected) in table) {
            val a = ok(json)
            assertEquals(expected.first, a.kind)
            assertEquals(expected.second, a.level)
        }
    }

    @Test
    fun `Schluessel und Pref der neuen Arten, Standard aus`() {
        assertEquals("device_action_brightness", DeviceActionKind.BRIGHTNESS.prefKey)
        assertEquals("device_action_dnd", DeviceActionKind.DND.prefKey)
        assertEquals("device_action_lock_screen", DeviceActionKind.LOCK_SCREEN.prefKey)
        assertEquals("device_action_screenshot", DeviceActionKind.SCREENSHOT.prefKey)
        assertEquals("device_action_open_camera", DeviceActionKind.OPEN_CAMERA.prefKey)
        assertFalse(DeviceActionKind.DEFAULT_ENABLED)
        assertEquals(DeviceActionKind.OPEN_CAMERA, DeviceActionKind.fromKey("open_camera"))
    }

    // ── Gate, Schalter, Budget ──────────────────────────────────────────────────
    @Test
    fun `Gate - Schalter aus blockiert jede neue Art, Schalter an laesst nur diese Art zu`() {
        val cases = listOf(
            act("action" to "set_brightness", "mode" to "up") to DeviceActionKind.BRIGHTNESS,
            act("action" to "dnd", "state" to "on") to DeviceActionKind.DND,
            act("action" to "lock_screen") to DeviceActionKind.LOCK_SCREEN,
            act("action" to "screenshot") to DeviceActionKind.SCREENSHOT,
            act("action" to "open_camera", "capture" to "photo") to DeviceActionKind.OPEN_CAMERA,
        )
        for ((json, kind) in cases) {
            val off = DeviceActionGate.check(json, emptySet(), TurnBudget())
            assertEquals(ResultKind.DISABLED, (off as GateResult.Refused).kind)
            val other = DeviceActionGate.check(json, all - kind, TurnBudget())
            assertEquals(ResultKind.DISABLED, (other as GateResult.Refused).kind)
            assertTrue(DeviceActionGate.check(json, setOf(kind), TurnBudget()) is GateResult.Run)
        }
    }

    @Test
    fun `Budget - dieselbe Systemaktion nicht zweimal je Antwort, hoechstens drei`() {
        val b = TurnBudget()
        assertTrue(DeviceActionGate.check(act("action" to "set_brightness", "mode" to "up"), all, b) is GateResult.Run)
        // Helligkeit zweimal (auch mit anderem Wert): zweite abgelehnt
        assertEquals(
            ResultKind.REJECTED,
            (DeviceActionGate.check(act("action" to "set_brightness", "mode" to "set", "percent" to 10), all, b) as GateResult.Refused).kind,
        )
        assertTrue(DeviceActionGate.check(act("action" to "dnd", "state" to "on"), all, b) is GateResult.Run)
        assertTrue(DeviceActionGate.check(act("action" to "open_camera", "capture" to "photo"), all, b) is GateResult.Run)
        // vierte Aktion: Obergrenze
        assertEquals(
            ResultKind.REJECTED,
            (DeviceActionGate.check(act("action" to "screenshot"), all, b) as GateResult.Refused).kind,
        )
    }

    // ── Meldungen ───────────────────────────────────────────────────────────────
    @Test
    fun `Meldungen sind ehrlich - angestossen oder geoeffnet, nie erledigt`() {
        val msgs = listOf(
            DeviceAction.SetBrightness(BrightnessMode.SET, 30), DeviceAction.SetBrightness(BrightnessMode.UP, null),
            DeviceAction.SetBrightness(BrightnessMode.DOWN, null), DeviceAction.LockScreen, DeviceAction.Screenshot,
            DeviceAction.OpenCamera(CameraCapture.PHOTO), DeviceAction.OpenCamera(CameraCapture.VIDEO),
        ).map { DeviceActionMessages.ok(it) }
        for (m in msgs) {
            assertFalse(m, m.contains("erledigt", ignoreCase = true))
            assertFalse(m, m.contains("hat nicht geklappt"))
        }
        assertEquals("Helligkeit auf 30 Prozent angestoßen.", msgs[0])
        assertTrue(DeviceActionMessages.ok(DeviceAction.LockScreen).contains("angestoßen"))
        assertTrue(DeviceActionMessages.ok(DeviceAction.Screenshot).contains("Galerie"))
        assertEquals("Kamera geöffnet — auslösen musst du selbst.", DeviceActionMessages.ok(DeviceAction.OpenCamera(CameraCapture.PHOTO)))
        assertTrue(DeviceActionMessages.ok(DeviceAction.OpenCamera(CameraCapture.VIDEO)).contains("auslösen musst du selbst"))
        assertTrue(DeviceActionMessages.noSystemCamera().contains("keine fremde"))
        assertEquals("Nicht stören an. Erinnerungen von Donna können still bleiben.", DeviceActionMessages.ok(DeviceAction.Dnd(true)))
        assertEquals("Nicht stören aus.", DeviceActionMessages.ok(DeviceAction.Dnd(false)))
        assertEquals(
            "Ich löse gleich einen Screenshot aus (er landet in der Galerie, wenn das System es erlaubt).",
            DeviceActionMessages.ok(DeviceAction.Screenshot),
        )
    }

    @Test
    fun `Meldungen bei fehlender Berechtigung nennen den Weg`() {
        assertTrue(DeviceActionMessages.needsPermission(DeviceActionKind.BRIGHTNESS).contains("Systemeinstellungen ändern"))
        assertTrue(DeviceActionMessages.needsPermission(DeviceActionKind.BRIGHTNESS).contains("Donna darf selbst"))
        assertTrue(DeviceActionMessages.needsPermission(DeviceActionKind.DND).contains("Nicht stören"))
        assertTrue(DeviceActionMessages.needsPermission(DeviceActionKind.LOCK_SCREEN).contains("Bedienungshilfe von Donna"))
        assertTrue(DeviceActionMessages.needsPermission(DeviceActionKind.SCREENSHOT).contains("Bedienungshilfe von Donna"))
        assertEquals(DeviceActionMessages.refused(ResultKind.NEEDS_PERMISSION), DeviceActionMessages.needsPermission(DeviceActionKind.OPEN_CAMERA))
        assertTrue(DeviceActionMessages.dndNotApplied().contains("vom System nicht übernommen"))
    }

    // ── BrightnessMath ──────────────────────────────────────────────────────────
    @Test
    fun `Helligkeit - Umrechnung Prozent und 0 bis 255`() {
        assertEquals(255, BrightnessMath.toRaw(100))
        assertEquals(13, BrightnessMath.toRaw(1)) // auf 5 Prozent geklemmt (Mike 23.11.), nie 0 (Display aus)
        assertEquals(128, BrightnessMath.toRaw(50))
        assertEquals(13, BrightnessMath.toRaw(0)) // auf 5 Prozent geklemmt, nie dunkler
        assertEquals(255, BrightnessMath.toRaw(500))
        assertEquals(100, BrightnessMath.toPercent(255))
        assertEquals(5, BrightnessMath.toPercent(0))
        assertEquals(50, BrightnessMath.toPercent(128))
        assertEquals(100, BrightnessMath.toPercent(9999))
    }

    @Test
    fun `Helligkeit - plus minus 15 Punkte, geklemmt auf 5 bis 100`() {
        assertEquals(65, BrightnessMath.targetPercent(BrightnessMode.UP, null, 50))
        assertEquals(35, BrightnessMath.targetPercent(BrightnessMode.DOWN, null, 50))
        assertEquals(100, BrightnessMath.targetPercent(BrightnessMode.UP, null, 95))
        assertEquals(5, BrightnessMath.targetPercent(BrightnessMode.DOWN, null, 10))
        assertEquals(5, BrightnessMath.targetPercent(BrightnessMode.DOWN, null, 1))
        assertEquals(70, BrightnessMath.targetPercent(BrightnessMode.SET, 70, 20))
        assertEquals(100, BrightnessMath.targetPercent(BrightnessMode.SET, 250, 20))
    }

    // ── Undo ────────────────────────────────────────────────────────────────────
    @Test
    fun `Undo-Daten - Helligkeit merkt Wert und Automatik, Nicht stoeren den Filter`() {
        assertEquals(UndoSpec.BrightnessTo(120, true), DeviceActionUndo.forBrightness(120, true))
        assertEquals(UndoSpec.BrightnessTo(255, false), DeviceActionUndo.forBrightness(400, false))
        assertNull(DeviceActionUndo.forBrightness(-1, false)) // unlesbar -> kein Rueckgaengig
        assertEquals(UndoSpec.DndTo(DndLogic.FILTER_ALL), DeviceActionUndo.forDnd(DndLogic.FILTER_ALL))
        assertEquals(UndoSpec.DndTo(DndLogic.FILTER_ALARMS), DeviceActionUndo.forDnd(DndLogic.FILTER_ALARMS))
        assertNull(DeviceActionUndo.forDnd(DndLogic.FILTER_UNKNOWN))
        assertNull(DeviceActionUndo.forDnd(99))
        assertEquals("brightness", UndoSpec.BrightnessTo(1, false).conflictKey)
        assertEquals("dnd", UndoSpec.DndTo(1).conflictKey)
        // der Runner stellt beide direkt aus der Spec her: keine Aktions-Form im Schema
        assertNull(DeviceActionUndo.toJson(UndoSpec.BrightnessTo(1, true)))
        assertNull(DeviceActionUndo.toJson(UndoSpec.DndTo(2)))
    }

    @Test
    fun `Undo-Meldungen nennen, was zurueckgenommen wurde`() {
        assertEquals("Helligkeit wieder wie vorher.", DeviceActionMessages.undone(UndoSpec.BrightnessTo(10, true)))
        assertEquals("Nicht stören wieder aus.", DeviceActionMessages.undone(UndoSpec.DndTo(DndLogic.FILTER_ALL)))
        assertEquals("Nicht stören wieder an.", DeviceActionMessages.undone(UndoSpec.DndTo(DndLogic.FILTER_PRIORITY)))
        assertEquals("Nicht stören wieder wie vorher.", DeviceActionMessages.undone(UndoSpec.DndTo(DndLogic.FILTER_ALARMS)))
    }

    @Test
    fun `Einzel-Undo - Konflikt, zweite Helligkeitsaenderung ueberholt die erste`() {
        val store = UndoStore()
        val a = store.record(UndoSpec.BrightnessTo(100, false))
        val b = store.record(UndoSpec.BrightnessTo(150, false))
        assertEquals(UndoTake.Superseded, store.take(a, all))
        assertTrue(store.take(b, all) is UndoTake.Ready)
        // Helligkeit und Nicht stoeren stoeren einander nicht
        val d = store.record(UndoSpec.DndTo(DndLogic.FILTER_ALL))
        assertTrue(store.take(b, all) is UndoTake.Ready)
        assertTrue(store.take(d, all) is UndoTake.Ready)
    }

    @Test
    fun `Gruppen-Undo - Helligkeit und Nicht stoeren in umgekehrter Reihenfolge, Schalter aus verhindert`() {
        val store = UndoStore()
        val bri1 = store.record(UndoSpec.BrightnessTo(100, true))
        val dnd = store.record(UndoSpec.DndTo(DndLogic.FILTER_ALL))
        val bri2 = store.record(UndoSpec.BrightnessTo(200, false)) // zweite Aenderung derselben Art
        assertEquals(UndoTake.Superseded, store.take(bri1, all))
        for (id in listOf(bri2, dnd, bri1)) {
            assertTrue("Schritt $id", store.take(id, all) is UndoTake.Ready)
            store.commit(id)
        }
        assertEquals(UndoTake.AlreadyDone, store.take(bri2, all))
        val id = store.record(UndoSpec.DndTo(DndLogic.FILTER_ALL))
        assertEquals(UndoTake.Disabled, store.take(id, all - DeviceActionKind.DND))
    }

    @Test
    fun `lock, Screenshot und Kamera haben nichts zurueckzunehmen - ehrliche Meldung statt Erfolg`() {
        assertNull(DeviceActionUndo.forAction(DeviceAction.LockScreen))
        assertNull(DeviceActionUndo.forAction(DeviceAction.Screenshot))
        assertNull(DeviceActionUndo.forAction(DeviceAction.OpenCamera(CameraCapture.PHOTO)))
        val store = UndoStore()
        val id = store.record(null) // erfolgreich, aber ohne Spec
        val t = store.take(id, all)
        assertEquals(UndoTake.None, t)
        assertEquals("Das lässt sich nicht mehr rückgängig machen.", DeviceActionMessages.undoRefused(t))
    }

    @Test
    fun `Ergebnis ohne Undo meldet undoable false, mit Undo true`() {
        val without = DeviceActionResult(ResultKind.OK, "x", null, null)
        assertFalse(without.toJson().getBoolean("undoable"))
        val with = DeviceActionResult(ResultKind.OK, "x", UndoSpec.DndTo(1), 7L)
        assertTrue(with.toJson().getBoolean("undoable"))
        assertEquals(7L, with.toJson().getLong("undoId"))
    }

    // ── Nicht stoeren: Filter ───────────────────────────────────────────────────
    @Test
    fun `Nicht stoeren - Zielfilter und Rueckleseprobe`() {
        assertEquals(DndLogic.FILTER_PRIORITY, DndLogic.targetFilter(true))
        assertEquals(DndLogic.FILTER_ALL, DndLogic.targetFilter(false))
        assertTrue(DndLogic.applied(DndLogic.FILTER_PRIORITY, DndLogic.FILTER_PRIORITY))
        // Android 15: nur eine App-Regel gesetzt, globaler Filter unveraendert -> NICHT ok
        assertFalse(DndLogic.applied(DndLogic.FILTER_PRIORITY, DndLogic.FILTER_ALL))
        assertFalse(DndLogic.applied(DndLogic.FILTER_ALL, DndLogic.FILTER_NONE))
    }

    // ── Berechtigungs-Entscheidungen ────────────────────────────────────────────
    private fun facts(
        sdk: Int = 35, write: Boolean = false, policy: Boolean = false, a11y: Boolean = false,
    ) = PermissionFacts(sdk, write, policy, a11y)

    @Test
    fun `Berechtigung - Helligkeit braucht Systemeinstellungen ändern`() {
        assertEquals(ResultKind.NEEDS_PERMISSION, SystemPermissions.precheck(DeviceActionKind.BRIGHTNESS, facts()))
        assertNull(SystemPermissions.precheck(DeviceActionKind.BRIGHTNESS, facts(write = true)))
        assertEquals(PermissionNeed.WRITE_SETTINGS, SystemPermissions.needOf(DeviceActionKind.BRIGHTNESS))
    }

    @Test
    fun `Berechtigung - Nicht stoeren braucht Policy-Zugriff`() {
        assertEquals(ResultKind.NEEDS_PERMISSION, SystemPermissions.precheck(DeviceActionKind.DND, facts(write = true)))
        assertNull(SystemPermissions.precheck(DeviceActionKind.DND, facts(policy = true)))
    }

    @Test
    fun `Berechtigung - Sperren und Screenshot - Dienst aus, Dienst an, API zu alt`() {
        for (k in listOf(DeviceActionKind.LOCK_SCREEN, DeviceActionKind.SCREENSHOT)) {
            assertEquals(ResultKind.NEEDS_PERMISSION, SystemPermissions.precheck(k, facts()))
            assertNull(SystemPermissions.precheck(k, facts(a11y = true)))
            assertEquals(ResultKind.UNSUPPORTED, SystemPermissions.precheck(k, facts(sdk = 27, a11y = true)))
            assertEquals(ResultKind.UNSUPPORTED, SystemPermissions.precheck(k, facts(sdk = 27)))
        }
        assertTrue(DeviceActionMessages.needsPermission(DeviceActionKind.LOCK_SCREEN).contains("Bedienungshilfe von Donna"))
    }

    @Test
    fun `Berechtigung - Kamera und alte Arten brauchen kein Sonderrecht`() {
        for (k in listOf(
            DeviceActionKind.OPEN_CAMERA, DeviceActionKind.FLASHLIGHT, DeviceActionKind.VOLUME,
            DeviceActionKind.OPEN_APP, DeviceActionKind.OPEN_SETTINGS, DeviceActionKind.TIMER_ALARM,
        )) {
            assertNull(SystemPermissions.needOf(k))
            assertNull(SystemPermissions.precheck(k, facts()))
            assertEquals("n/a", SystemPermissions.stateOf(k, facts()))
        }
    }

    @Test
    fun `Berechtigung - Status fuer die Einstellungen`() {
        assertEquals("missing", SystemPermissions.stateOf(DeviceActionKind.BRIGHTNESS, facts()))
        assertEquals("granted", SystemPermissions.stateOf(DeviceActionKind.BRIGHTNESS, facts(write = true)))
        assertEquals("missing", SystemPermissions.stateOf(DeviceActionKind.DND, facts()))
        assertEquals("granted", SystemPermissions.stateOf(DeviceActionKind.DND, facts(policy = true)))
        assertEquals("missing", SystemPermissions.stateOf(DeviceActionKind.SCREENSHOT, facts()))
        assertEquals("granted", SystemPermissions.stateOf(DeviceActionKind.LOCK_SCREEN, facts(a11y = true)))
        assertEquals("n/a", SystemPermissions.stateOf(DeviceActionKind.LOCK_SCREEN, facts(sdk = 26)))
    }

    @Test
    fun `Systemseiten fuer den geführten Hinweis`() {
        assertEquals("android.settings.action.MANAGE_WRITE_SETTINGS", PermissionNeed.WRITE_SETTINGS.settingsAction)
        assertTrue(PermissionNeed.WRITE_SETTINGS.needsPackageUri)
        assertEquals("android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS", PermissionNeed.NOTIFICATION_POLICY.settingsAction)
        assertEquals("android.settings.ACCESSIBILITY_SETTINGS", PermissionNeed.ACCESSIBILITY.settingsAction)
        assertFalse(PermissionNeed.ACCESSIBILITY.needsPackageUri)
    }

    // ── Screenshot: Vordergrund-Entscheidung ────────────────────────────────────
    private val own = "com.yourcompany.donna"
    private val blocked = setOf("com.android.settings", "com.paypal.android.p2pmobile", "com.bitwarden.mobile")

    private fun d(vararg pkgs: String?, blockedSet: Set<String> = blocked, label: (String) -> String = { "" }) =
        ScreenshotGuard.decide(pkgs.toList(), blockedSet, own, label)

    @Test
    fun `Screenshot - harmlose Apps und nur eigene Fenster sind erlaubt`() {
        assertEquals(ScreenshotDecision.Allowed, d("com.google.android.youtube"))
        assertEquals(ScreenshotDecision.Allowed, d("com.android.chrome", own))
        assertEquals(ScreenshotDecision.Allowed, d(own)) // nur Donna selbst sichtbar
        assertEquals(ScreenshotDecision.Allowed, d(own, own))
    }

    @Test
    fun `Screenshot - Banking hinter Donnas Overlay oder Blase wird gesperrt`() {
        // oberstes Fenster zuerst: Donna liegt über der Banking-App
        assertEquals(ScreenshotDecision.Blocked, d(own, "com.paypal.android.p2pmobile"))
        assertEquals(ScreenshotDecision.Blocked, d(own, "de.sparkasse.banking"))
        // ... und auch, wenn die Banking-App nur eines von mehreren Fenstern ist (Split-Screen)
        assertEquals(ScreenshotDecision.Blocked, d("com.android.chrome", "com.bitwarden.mobile", own))
        // Gesperrt schlägt Unbekannt
        assertEquals(ScreenshotDecision.Blocked, d(null, "com.paypal.android.p2pmobile"))
    }

    @Test
    fun `Screenshot - Sperrliste, Einstellungen und Passwort-Manager werden nie ausgeloest`() {
        for (p in blocked) assertEquals(p, ScreenshotDecision.Blocked, d(p))
        // auch ohne Eintrag in der Sperrliste: Paketnamen-Muster (Banking, Wallet, 2FA, Broker)
        for (p in listOf(
            "de.sparkasse.banking", "com.google.android.apps.walletnfcrel", "com.google.android.apps.authenticator2",
            "de.traderepublic.app", "com.x.passwordmanager", "com.coinbase.android", "com.bunq.android",
        )) {
            assertEquals(p, ScreenshotDecision.Blocked, d(p, blockedSet = emptySet()))
        }
    }

    @Test
    fun `Screenshot - feste Zusatzliste sperrt auch ohne Namensmuster`() {
        for (p in listOf(
            "com.starfinanz.smob.android.sfinanzstatus", "com.samsung.android.spay", "com.samsung.android.samsungpass",
            "com.samsung.knox.securefolder", "com.google.android.apps.walletnfcrel", "com.google.android.apps.authenticator2",
            "com.android.settings.intelligence", "com.android.systemui", "com.samsung.android.lool",
        )) {
            assertEquals(p, ScreenshotDecision.Blocked, d(own, p, blockedSet = emptySet()))
        }
    }

    @Test
    fun `Screenshot - Label ueber den Resolver (Fake) erkennt sensible Apps mit harmlosem Paketnamen`() {
        val fake = mapOf("de.xyz.app1" to "Meine Bank", "de.xyz.app2" to "Spiele")
        assertEquals(ScreenshotDecision.Blocked, d("de.xyz.app1", label = { fake[it] ?: "" }))
        assertEquals(ScreenshotDecision.Allowed, d("de.xyz.app2", label = { fake[it] ?: "" }))
        // ohne Label bleibt der harmlose Paketname erlaubt (nur das Label verrät Banking)
        assertEquals(ScreenshotDecision.Allowed, d("de.xyz.app1"))
        // ein werfender Resolver ist kein Freibrief und kein Absturz
        assertEquals(ScreenshotDecision.Allowed, d("de.xyz.app2", label = { throw IllegalStateException() }))
    }

    @Test
    fun `Screenshot - leere oder unlesbare Fensterliste wird nie ausgeloest`() {
        assertEquals(ScreenshotDecision.Unknown, ScreenshotGuard.decide(null, blocked, own))
        assertEquals(ScreenshotDecision.Unknown, ScreenshotGuard.decide(emptyList(), blocked, own))
        assertEquals(ScreenshotDecision.Unknown, d(null as String?))
        assertEquals(ScreenshotDecision.Unknown, d(""))
        assertEquals(ScreenshotDecision.Unknown, d("   "))
        // ein unlesbares Fenster macht auch harmlose Nachbarn unsicher
        assertEquals(ScreenshotDecision.Unknown, d("com.android.chrome", null))
        assertEquals(ScreenshotDecision.Unknown, d(own, null))
    }

    @Test
    fun `Screenshot - Ablauf - zweite Pruefung und Systemaktion`() {
        var performed = 0
        val yes = { performed++; true }
        assertEquals(ResultKind.OK, ScreenshotFlow.finish(ScreenshotDecision.Allowed, yes))
        assertEquals(1, performed)
        assertEquals(ResultKind.FAILED, ScreenshotFlow.finish(ScreenshotDecision.Allowed) { false })
        // gesperrt oder unbekannt: die Systemaktion wird NIE aufgerufen
        assertEquals(ResultKind.BLOCKED, ScreenshotFlow.finish(ScreenshotDecision.Blocked, yes))
        assertEquals(ResultKind.BLOCKED, ScreenshotFlow.finish(ScreenshotDecision.Unknown, yes))
        assertEquals(1, performed)
    }

    @Test
    fun `Screenshot - jede spaete Absage bekommt einen Hinweis an Mike, nur OK nicht`() {
        assertNull(ScreenshotFlow.lateNotice(ResultKind.OK))
        for (k in ResultKind.values().filter { it != ResultKind.OK }) {
            val n = ScreenshotFlow.lateNotice(k)
            assertTrue("$k braucht einen Hinweis", n != null && n.startsWith("Screenshot nicht aufgenommen: "))
        }
        assertTrue(ScreenshotFlow.lateNotice(ResultKind.BLOCKED)!!.contains("geschützter Bildschirm"))
    }

    @Test
    fun `Screenshot - Verzoegerung und ehrliche Meldungen`() {
        assertEquals(800L, ScreenshotGuard.DELAY_MS)
        assertTrue(DeviceActionMessages.screenshotBlocked().contains("keinen Screenshot"))
        assertTrue(DeviceActionMessages.screenshotUnknown().contains("keinen Screenshot"))
        // gesperrt = BLOCKED -> auf dem Draht "nicht_unterstuetzt/gesperrt" (keine neuen Ergebniscodes)
        assertEquals(DeviceActionReport.Wire("nicht_unterstuetzt", "gesperrt"), DeviceActionReport.wire(ResultKind.BLOCKED))
    }

    // ── Helligkeit bei Automatik, Nicht stoeren, Reihenfolge ────────────────────
    @Test
    fun `Helligkeit bei Automatik - heller und dunkler werden erfragt statt geraten`() {
        assertTrue(BrightnessMath.needsExplicitValue(BrightnessMode.UP, true))
        assertTrue(BrightnessMath.needsExplicitValue(BrightnessMode.DOWN, true))
        assertFalse(BrightnessMath.needsExplicitValue(BrightnessMode.SET, true))
        assertFalse(BrightnessMath.needsExplicitValue(BrightnessMode.UP, false))
        assertFalse(BrightnessMath.needsExplicitValue(BrightnessMode.DOWN, false))
        assertEquals("Die Helligkeit steht auf Automatik — nenne mir einen Prozentwert.", DeviceActionMessages.brightnessAuto())
        // AMBIGUOUS geht auf dem Draht als "fehler/mehrdeutig" (kein neuer Code)
        assertEquals(DeviceActionReport.Wire("fehler", "mehrdeutig"), DeviceActionReport.wire(ResultKind.AMBIGUOUS))
    }

    @Test
    fun `Nicht stoeren - Rueckleseschleife bis etwa 800 ms, auf Main kurz`() {
        assertEquals(800L, DndLogic.READBACK_TRIES * DndLogic.READBACK_INTERVAL_MS)
        assertTrue(DndLogic.READBACK_TRIES_MAIN * DndLogic.READBACK_INTERVAL_MS <= 200L)
    }

    @Test
    fun `Mehrere Aktionen - Sperren und Screenshot zuletzt, stabil, Positionen bleiben`() {
        assertEquals(listOf(1, 3, 0, 2), DeviceActionOrder.executionOrder(listOf("lock_screen", "dnd", "screenshot", "set_brightness")))
        assertEquals(listOf(0, 1, 2), DeviceActionOrder.executionOrder(listOf("a", "b", "c")))
        assertEquals(listOf(1, 0), DeviceActionOrder.executionOrder(listOf("lock_screen", null)))
        assertEquals(listOf(0, 1), DeviceActionOrder.executionOrder(listOf("screenshot", "lock_screen"))) // stabil
        assertEquals(emptyList<Int>(), DeviceActionOrder.executionOrder(emptyList()))
    }

    @Test
    fun `kindspezifische Meldung bei entzogenem Recht (Race) - nie der generische Text fuer Systemarten`() {
        for (k in listOf(DeviceActionKind.BRIGHTNESS, DeviceActionKind.DND, DeviceActionKind.LOCK_SCREEN, DeviceActionKind.SCREENSHOT)) {
            assertTrue(DeviceActionMessages.needsPermission(k) != DeviceActionMessages.refused(ResultKind.NEEDS_PERMISSION))
        }
    }
}

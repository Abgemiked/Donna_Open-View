package com.yourcompany.donna.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** #147 Paket D — Entscheidung ohne Server: Schalter, Routinen, ehrliche Meldungen, Zugangsregel. Reine Logik. */
class OfflineCommandsTest {

    private val all = DeviceActionKind.values().toSet() - DeviceActionKind.CHAT_ONLY
    private val none = emptySet<DeviceActionKind>()

    private fun run(text: String, enabled: Set<DeviceActionKind> = all, snap: OfflineRoutineSnapshot? = null) =
        OfflineCommands.interpret(text, enabled, snap, nowMs = 1_000L)

    private fun dev(vararg p: Pair<String, Any>) = JSONObject().put("type", "device_action").also { j -> p.forEach { (k, v) -> j.put(k, v) } }

    private fun snapshot(vararg r: OfflineRoutine) = OfflineRoutineSnapshot(1L, r.toList())

    // ── Einzelbefehl ──
    @Test fun taschenlampe_laeuft_als_aktion_und_sagt_angestossen() {
        val o = run("Mach die Taschenlampe an") as OfflineCommands.Outcome.Run
        assertEquals(1, o.actions.size)
        assertEquals("flashlight", o.actions[0].getString("action"))
        assertEquals("on", o.actions[0].getString("state"))
        assertEquals(OfflineCommands.STARTED, o.speech)
        assertFalse(o.speech.contains("erledigt")) // vor dem Ergebnis wird nichts behauptet
    }

    @Test fun schalter_aus_bleibt_aus_auch_offline() {
        val o = run("Mach die Taschenlampe an", all - DeviceActionKind.FLASHLIGHT)
        assertTrue(o is OfflineCommands.Outcome.Say)
        assertTrue((o as OfflineCommands.Outcome.Say).speech.contains("Einstellungen"))
    }

    @Test fun ohne_jeden_schalter_geschieht_nichts() {
        for (t in listOf("Taschenlampe an", "Leiser", "Timer 5 Minuten", "Öffne YouTube", "Sperr den Bildschirm", "Mach den Bildschirm heller")) {
            assertTrue("„$t“", run(t, none) is OfflineCommands.Outcome.Say)
        }
    }

    @Test fun alles_was_den_server_braucht_wird_ehrlich_benannt() {
        for (t in listOf("Mach das Licht aus", "Schreib Anna dass ich später komme", "Ruf Anna an", "Wie wird das Wetter?",
            "Schalte Nicht stören ein", "Mach einen Screenshot")) {
            val o = run(t) as OfflineCommands.Outcome.Say
            assertEquals("„$t“", OfflineCommands.NEEDS_SERVER, o.speech)
        }
    }

    @Test fun erinnerung_wird_nie_offline_angelegt_aber_timer_angeboten() {
        val o = run("Erinnere mich in 20 Minuten an die Wäsche") as OfflineCommands.Outcome.Say
        assertEquals(OfflineCommands.REMINDER, o.speech)
    }

    @Test fun mehrere_befehle_und_unklare_zeit_loesen_nichts_aus() {
        assertEquals(OfflineCommands.MULTI, (run("Taschenlampe an, Timer auf 10 Minuten") as OfflineCommands.Outcome.Say).speech)
        assertEquals(OfflineCommands.CLARIFY_TIME, (run("Stell den Wecker auf 7 Uhr") as OfflineCommands.Outcome.Say).speech)
        assertEquals(OfflineCommands.BRIGHTNESS_RANGE, (run("Stell die Helligkeit auf 3 Prozent") as OfflineCommands.Outcome.Say).speech)
    }

    @Test fun werte_stammen_nur_aus_den_eigenen_worten() {
        val o = run("Stell einen Timer auf 10 Minuten") as OfflineCommands.Outcome.Run
        assertEquals(10, o.actions[0].getInt("minutes"))
        // Fremder Text / Rede eines anderen / Bedingung ist kein Befehl
        for (t in listOf("Er sagt: stell einen Timer auf 10 Minuten", "Wenn ich schlafe, sperr das Handy", "Ist der Timer fertig?")) {
            assertTrue("„$t“", run(t) is OfflineCommands.Outcome.Say)
        }
    }

    @Test fun jede_ausgeloeste_aktion_besteht_das_schema_des_geraets() {
        for (t in listOf("Mach die Taschenlampe an", "Leiser", "Timer 5 Minuten", "Wecker um 22:30", "Öffne YouTube", "Öffne WLAN",
            "Sperr den Bildschirm", "Mach den Bildschirm heller", "Helligkeit auf 50 Prozent")) {
            val o = run(t) as OfflineCommands.Outcome.Run
            for (a in o.actions) assertTrue("„$t“", DeviceActionParser.parse(a) is ParseResult.Ok)
        }
    }

    // ── Routinen aus dem lokalen Stand ──
    private val stream = OfflineRoutine(
        "Streammodus",
        listOf(
            OfflineRoutineStep(dev("action" to "set_brightness", "mode" to "set", "percent" to 80), "Helligkeit auf 80 Prozent"),
            OfflineRoutineStep(null, "Schalten: softbox an"),
            OfflineRoutineStep(dev("action" to "flashlight", "state" to "on"), "Taschenlampe an"),
        ),
    )

    @Test fun routine_laeuft_aus_dem_lokalen_stand_und_benennt_ausgelassenes() {
        val o = run("Streammodus", snap = snapshot(stream)) as OfflineCommands.Outcome.Run
        assertEquals(listOf("set_brightness", "flashlight"), o.actions.map { it.getString("action") })
        assertTrue(o.speech.contains("2 angestoßen"))
        assertTrue(o.speech.contains("braucht Verbindung"))
    }

    @Test fun routine_schalter_aus_laesst_schritt_aus() {
        val o = run("Starte den Streammodus", all - DeviceActionKind.BRIGHTNESS, snapshot(stream)) as OfflineCommands.Outcome.Run
        assertEquals(listOf("flashlight"), o.actions.map { it.getString("action") })
        assertTrue(o.speech.contains("ausgeschaltet"))
    }

    @Test fun routine_ohne_startbaren_schritt_sagt_nichts_gestartet() {
        val o = run("Streammodus", none, snapshot(stream)) as OfflineCommands.Outcome.Say
        assertTrue(o.speech.contains("nichts angestoßen"))
    }

    @Test fun routine_prueft_manipulierte_schritte_erneut() {
        val bad = OfflineRoutine(
            "Schlafenszeit",
            listOf(
                OfflineRoutineStep(dev("action" to "screenshot"), "Screenshot"),
                OfflineRoutineStep(dev("action" to "lock_screen"), "Bildschirm sperren"), // nicht letzter Schritt
                OfflineRoutineStep(dev("action" to "set_timer", "minutes" to 99999), "Timer"),
                OfflineRoutineStep(dev("action" to "hack"), "x"),
                OfflineRoutineStep(dev("action" to "flashlight", "state" to "off"), "Taschenlampe aus"),
            ),
        )
        val o = run("Schlafenszeit", snap = snapshot(bad)) as OfflineCommands.Outcome.Run
        assertEquals(listOf("flashlight"), o.actions.map { it.getString("action") })
    }

    @Test fun sperren_ist_nur_als_letzter_routinenschritt_erlaubt() {
        val r = OfflineRoutine(
            "Feierabend",
            listOf(
                OfflineRoutineStep(dev("action" to "flashlight", "state" to "off"), "Taschenlampe aus"),
                OfflineRoutineStep(dev("action" to "lock_screen"), "Bildschirm sperren"),
            ),
        )
        val o = run("Feierabend", snap = snapshot(r)) as OfflineCommands.Outcome.Run
        assertEquals(listOf("flashlight", "lock_screen"), o.actions.map { it.getString("action") })
    }

    @Test fun routine_hoechstens_drei_schritte_offline() {
        val many = OfflineRoutine(
            "Grossmodus",
            listOf(
                OfflineRoutineStep(dev("action" to "flashlight", "state" to "on"), "a"),
                OfflineRoutineStep(dev("action" to "set_timer", "minutes" to 5), "b"),
                OfflineRoutineStep(dev("action" to "set_brightness", "mode" to "up"), "c"),
                OfflineRoutineStep(dev("action" to "open_app", "app" to "youtube"), "d"),
            ),
        )
        val o = run("Grossmodus", snap = snapshot(many)) as OfflineCommands.Outcome.Run
        assertEquals(3, o.actions.size)
        assertTrue(o.speech.contains("Ausgelassen"))
    }

    @Test fun routine_wird_nicht_im_gespraech_oder_bei_verwaltung_ausgeloest() {
        val s = snapshot(stream)
        for (t in listOf("Wie funktioniert der Streammodus?", "Lösche die Routine Streammodus", "Streammodus aus", "Ich mag den Streammodus gar nicht")) {
            val o = run(t, snap = s)
            assertTrue("„$t“", o is OfflineCommands.Outcome.Say)
        }
    }

    @Test fun veralteter_routinenstand_laeuft_nicht() {
        val old = OfflineRoutineSnapshot(1L, listOf(stream))
        val week = 8L * 24 * 60 * 60 * 1000
        val o = OfflineCommands.interpret("Streammodus", all, old, nowMs = week) as OfflineCommands.Outcome.Say
        assertEquals(OfflineCommands.STALE, o.speech)
        val unknown = OfflineCommands.interpret("Streammodus", all, OfflineRoutineSnapshot(0L, listOf(stream)), nowMs = 5L) as OfflineCommands.Outcome.Say
        assertEquals(OfflineCommands.STALE, unknown.speech)
        val day = 3L * 24 * 60 * 60 * 1000
        val ok = OfflineCommands.interpret("Streammodus", all, old, nowMs = day) as OfflineCommands.Outcome.Run
        assertTrue(ok.speech.contains("vor 2 Tagen"))
    }

    @Test fun routinen_erweitern_die_offline_liste_nicht() {
        val r = OfflineRoutine(
            "Ruhemodus",
            listOf(
                OfflineRoutineStep(dev("action" to "dnd", "state" to "on"), "Nicht stören an"),
                OfflineRoutineStep(dev("action" to "open_camera", "capture" to "photo"), "Kamera"),
                OfflineRoutineStep(dev("action" to "flashlight", "state" to "off"), "Taschenlampe aus"),
            ),
        )
        val o = run("Ruhemodus", snap = snapshot(r)) as OfflineCommands.Outcome.Run
        assertEquals(listOf("flashlight"), o.actions.map { it.getString("action") })
    }

    @Test fun kennungen_des_servers_werden_nicht_uebernommen() {
        val body = """{"routines":[{"name":"Streammodus","steps":[
            {"kind":"device","action":"flashlight","state":"on","action_id":"da_0123456789abcdef","group_id":"gr_0123456789abcdef","label":"x"}]}]}"""
        val st = OfflineRoutineSnapshot.parseServer(body, 5L)!!.routines.single().steps.single().device!!
        assertFalse(st.has("action_id"))
        assertFalse(st.has("group_id"))
    }

    // ── Stand der Routinen ──
    @Test fun serverform_wird_zu_geraeteschritten_und_home_wird_unausfuehrbar() {
        val body = """{"routines":[{"name":"Streammodus","steps":[
            {"kind":"device","action":"flashlight","state":"on","label":"Taschenlampe an"},
            {"kind":"home","label":"Schalten: softbox an"}]}]}"""
        val s = OfflineRoutineSnapshot.parseServer(body, 5L)!!
        val r = s.routines.single()
        assertEquals("device_action", r.steps[0].device!!.getString("type"))
        assertEquals("flashlight", r.steps[0].device!!.getString("action"))
        assertNull(r.steps[1].device)
        val back = OfflineRoutineSnapshot.parseStored(s.toJson())!!
        assertEquals(5L, back.syncedAtMs)
        assertEquals("flashlight", back.routines.single().steps[0].device!!.getString("action"))
    }

    @Test fun kaputter_stand_ergibt_keinen_snapshot() {
        assertNull(OfflineRoutineSnapshot.parseServer("kein json", 1L))
        assertNull(OfflineRoutineSnapshot.parseStored("{}"))
        assertNotNull(OfflineRoutineSnapshot.parseServer("""{"routines":[]}""", 1L))
    }

    // ── Wann der Offline-Weg greift ──
    @Test fun nur_verbindungsaufbau_und_vorschaltfehler_loesen_offline_aus() {
        assertTrue(OfflineGate.shouldFallback(false, UnknownHostException(), null, false))
        assertTrue(OfflineGate.shouldFallback(false, ConnectException(), null, false))
        assertTrue(OfflineGate.shouldFallback(false, SocketTimeoutException("connect timed out"), null, false))
        assertTrue(OfflineGate.shouldFallback(false, null, 502, true))
    }

    @Test fun nie_wenn_der_server_geantwortet_hat() {
        assertFalse(OfflineGate.shouldFallback(false, SocketTimeoutException("read timed out"), null, true))
        assertFalse(OfflineGate.shouldFallback(false, null, 401, true))
        assertFalse(OfflineGate.shouldFallback(false, null, 500, true))
        assertFalse(OfflineGate.shouldFallback(false, null, 503, true)) // kann der Server selbst senden
        assertFalse(OfflineGate.shouldFallback(false, null, 504, true))
        assertFalse(OfflineGate.shouldFallback(false, SSLHandshakeException("x"), null, false))
        // schon ein Ereignis des Servers gesehen: nichts mehr ersetzen
        assertFalse(OfflineGate.shouldFallback(true, ConnectException(), null, false))
        assertFalse(OfflineGate.shouldFallback(true, null, 503, true))
    }

    @Test fun zeitgrenze_beim_verbinden_ist_kurz() {
        assertTrue(OfflineGate.CONNECT_TIMEOUT_MS in 8_000..15_000)
    }
}

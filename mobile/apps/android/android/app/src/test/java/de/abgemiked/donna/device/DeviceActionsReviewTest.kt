package com.yourcompany.donna.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #147 Korrekturrunde: Sperrerkennung, Rückfrage bei Mehrdeutigkeit, Wecker-Untergrenze, Rückgängig, Duplikate. */
class DeviceActionsReviewTest {

    private fun act(vararg pairs: Pair<String, Any?>): JSONObject =
        JSONObject().apply { put("type", "device_action"); pairs.forEach { (k, v) -> put(k, v) } }

    private val all = DeviceActionKind.values().toSet()

    // ── Sperrerkennung für open_app ─────────────────────────────────────────────
    @Test
    fun `Sperrerkennung faengt Banking Broker Krypto Passwort und 2FA auch ohne Eintrag auf der Sperrliste`() {
        val sensitive = listOf(
            AppEntry("Postbank Banking", "de.postbank.finanzassistent2"),
            AppEntry("Commerzbank", "de.commerzbank.neu"),
            AppEntry("Consorsbank", "de.consorsbank.app"),
            AppEntry("Targobank", "de.targobank.app"),
            AppEntry("Revolut", "com.revolut.revolut"),
            AppEntry("Trade Republic", "de.traderepublic.app"),
            AppEntry("Klarna", "com.myklarnamobile"),
            AppEntry("Bitpanda", "com.bitpanda.app"),
            AppEntry("Coinbase", "com.coinbase.android"),
            AppEntry("Authy", "com.authy.authy"),
            AppEntry("2FAS", "com.twofasapp"),
            AppEntry("Aegis", "com.beemdevelopment.aegis"),
            AppEntry("Bitwarden", "com.x8bit.bitwarden"),
            AppEntry("1Password", "com.onepassword.app"),
            AppEntry("Proton Pass", "proton.android.pass"),
            AppEntry("S-pushTAN", "de.sparkasse.pushtan"),
            AppEntry("photoTAN", "de.example.phototan"),
            AppEntry("Samsung Pay", "com.samsung.android.spay"),
            AppEntry("Google Pay", "com.google.android.apps.nbu.paisa.user"),
            AppEntry("Mein Passwort", "de.example.pw"),
        )
        for (a in sensitive) assertTrue("${a.label} muss gesperrt sein", AppResolver.isSensitive(a))
    }

    @Test
    fun `pay bank pass nur als ganzes Wort bzw Banking-Begriff - Payback und Harmloses bleiben frei`() {
        val ok = listOf(
            AppEntry("Payback", "de.payback.client.android"),
            AppEntry("Spotify", "com.spotify.music"),
            AppEntry("Kamera", "com.samsung.android.camera"),
            AppEntry("Passbild", "de.example.passbild"),
            AppEntry("Bahn Navigator", "de.hafas.android.db"),
            AppEntry("Kontakte", "com.samsung.android.contacts"),
            AppEntry("Notizen", "com.samsung.android.app.notes"),
        )
        for (a in ok) assertFalse("${a.label} darf nicht gesperrt sein", AppResolver.isSensitive(a))
    }

    @Test
    fun `Mehrdeutig mit gesperrtem Kandidaten fragt nach den erlaubten Kandidaten`() {
        val apps = listOf(
            AppEntry("Samsung Pay", "com.samsung.android.spay"),
            AppEntry("Samsung Kalender", "com.samsung.android.calendar"),
            AppEntry("Samsung Notizen", "com.samsung.android.app.notes"),
        )
        val m = AppResolver.resolve("Samsung", apps, emptySet())
        assertTrue(m.toString(), m is AppMatch.Ambiguous)
        assertEquals(setOf("Samsung Kalender", "Samsung Notizen"), (m as AppMatch.Ambiguous).labels.toSet())
    }

    @Test
    fun `Genau ein erlaubter Kandidat neben einem gesperrten wird erfragt statt still gestartet`() {
        val apps = listOf(AppEntry("Samsung Pay", "com.samsung.android.spay"), AppEntry("Samsung Kalender", "com.samsung.android.calendar"))
        assertEquals(AppMatch.Ambiguous(listOf("Samsung Kalender")), AppResolver.resolve("Samsung", apps, emptySet()))
    }

    @Test
    fun `Eindeutig gesperrter Treffer bleibt gesperrt`() {
        val apps = listOf(AppEntry("Postbank Banking", "de.postbank.finanzassistent2"), AppEntry("Kamera", "x.cam"))
        assertEquals(AppMatch.Blocked("Postbank Banking"), AppResolver.resolve("Postbank", apps, emptySet()))
        val onlyBlocked = listOf(AppEntry("Bank A", "de.a.bank"), AppEntry("Bank B", "de.b.bank"))
        assertTrue(AppResolver.resolve("Bank", onlyBlocked, emptySet()) is AppMatch.Blocked)
    }

    @Test
    fun `Rueckfrage-Text fuer einen und mehrere Kandidaten`() {
        assertEquals("Meinst du Samsung Kalender?", DeviceActionMessages.ambiguous(listOf("Samsung Kalender")))
        assertEquals("Welche meinst du: A oder B?", DeviceActionMessages.ambiguous(listOf("A", "B")))
    }

    // ── Wecker-Untergrenze im Gerät ─────────────────────────────────────────────
    @Test
    fun `Wecker-Kanal wird auch bei leiser nie unter 10 Prozent gedreht`() {
        assertEquals(1, VolumeMath.alarmFloor(7))
        assertEquals(2, VolumeMath.alarmFloor(15))
        assertEquals(1, VolumeMath.alarmFloor(1))
        assertEquals(0, VolumeMath.alarmFloor(0))
        assertEquals(2, VolumeMath.clampAlarm(0, 15))
        assertEquals(2, VolumeMath.clampAlarm(1, 15))
        assertEquals(5, VolumeMath.clampAlarm(5, 15))
    }

    // ── 30.0 wie 30 ─────────────────────────────────────────────────────────────
    @Test
    fun `ganzzahlige Dezimalzahl wird wie die ganze Zahl akzeptiert`() {
        assertEquals(DeviceAction.SetTimer(30, ""), (DeviceActionParser.parse(act("action" to "set_timer", "minutes" to 30.0)) as ParseResult.Ok).action)
        assertTrue(DeviceActionParser.parse(act("action" to "set_timer", "minutes" to 30.5)) is ParseResult.Rejected)
        val v = DeviceActionParser.parse(act("action" to "volume", "channel" to "media", "mode" to "set", "percent" to 40.0)) as ParseResult.Ok
        assertEquals(40, (v.action as DeviceAction.Volume).percent)
    }

    // ── Duplikat = gleiche Aktion mit gleichen Parametern ───────────────────────
    @Test
    fun `zwei verschiedene Timer und Apps in einer Antwort erlaubt, gleiche nicht`() {
        val b = TurnBudget()
        fun run(j: JSONObject) = DeviceActionGate.check(j, all, b)
        assertTrue(run(act("action" to "set_timer", "minutes" to 10)) is GateResult.Run)
        assertTrue(run(act("action" to "set_timer", "minutes" to 20)) is GateResult.Run)
        assertTrue(run(act("action" to "set_timer", "minutes" to 20)) is GateResult.Refused)
        val b2 = TurnBudget()
        assertTrue(DeviceActionGate.check(act("action" to "open_app", "app" to "Maps"), all, b2) is GateResult.Run)
        assertTrue(DeviceActionGate.check(act("action" to "open_app", "app" to "YouTube"), all, b2) is GateResult.Run)
        assertTrue(DeviceActionGate.check(act("action" to "open_app", "app" to "maps"), all, b2) is GateResult.Refused)
    }

    // ── Rückgängig ──────────────────────────────────────────────────────────────
    @Test
    fun `Taschenlampe bei unbekanntem Vorzustand bietet kein Rueckgaengig an`() {
        assertNull(DeviceActionUndo.forFlashlight(null))
        assertEquals(UndoSpec.FlashlightTo(true), DeviceActionUndo.forFlashlight(true))
    }

    @Test
    fun `Rueckgaengig gehoert zu einer Aktionsart`() {
        assertEquals(DeviceActionKind.VOLUME, UndoSpec.VolumeTo(VolumeChannel.MEDIA, 10).kind)
        assertEquals(DeviceActionKind.FLASHLIGHT, UndoSpec.FlashlightTo(true).kind)
        assertEquals(DeviceActionKind.TIMER_ALARM, UndoSpec.ClockTimers.kind)
        assertEquals(DeviceActionKind.TIMER_ALARM, UndoSpec.ClockAlarms.kind)
    }

    // ── Rückgängig gehört zu SEINER Aktion (UndoStore) ─────────────────────────
    private fun store(now: () -> Long = { 1_000L }) = UndoStore(now)

    @Test
    fun `jede Aktion bekommt eine eigene Kennung und die Taste wirkt nur auf ihre Aktion`() {
        val st = store()
        val torch = st.record(UndoSpec.FlashlightTo(false))
        val vol = st.record(UndoSpec.VolumeTo(VolumeChannel.MEDIA, 40))
        assertTrue(vol > torch)
        // die Taste der ERSTEN Karte nimmt nur die Taschenlampe zurueck, nicht die Lautstaerke
        assertEquals(UndoTake.Ready(UndoSpec.FlashlightTo(false)), st.take(torch, all))
        assertEquals(UndoTake.Ready(UndoSpec.VolumeTo(VolumeChannel.MEDIA, 40)), st.take(vol, all))
    }

    @Test
    fun `zwei Aenderungen desselben Kanals - die erste ist nicht mehr zuruecknehmbar`() {
        val st = store()
        val first = st.record(UndoSpec.VolumeTo(VolumeChannel.MEDIA, 40))
        val second = st.record(UndoSpec.VolumeTo(VolumeChannel.MEDIA, 60))
        assertEquals(UndoTake.Superseded, st.take(first, all))
        assertEquals(UndoTake.Ready(UndoSpec.VolumeTo(VolumeChannel.MEDIA, 60)), st.take(second, all))
        // anderer Kanal beruehrt nichts
        val ring = st.record(UndoSpec.VolumeTo(VolumeChannel.RING, 20))
        assertEquals(UndoTake.Ready(UndoSpec.VolumeTo(VolumeChannel.MEDIA, 60)), st.take(second, all))
        assertTrue(st.take(ring, all) is UndoTake.Ready)
    }

    @Test
    fun `eine spaetere Aenderung ohne Rueckgaengig macht den Kanal ebenfalls unzuruecknehmbar`() {
        val st = store()
        val first = st.record(UndoSpec.VolumeTo(VolumeChannel.MEDIA, 40), "volume:media")
        st.record(null, "volume:media") // spaetere Aktion am selben Kanal ohne eigene Rueckgaengig-Daten
        assertEquals(UndoTake.Superseded, st.take(first, all))
    }

    @Test
    fun `Slot wird erst verbraucht wenn das Zuruecknehmen angestossen werden konnte`() {
        val st = store()
        val id = st.record(UndoSpec.FlashlightTo(true))
        assertTrue(st.take(id, all) is UndoTake.Ready)
        assertTrue(st.take(id, all) is UndoTake.Ready) // take verbraucht nicht (z. B. Uhr-App liess sich nicht oeffnen)
        st.commit(id)
        assertEquals(UndoTake.AlreadyDone, st.take(id, all))
    }

    @Test
    fun `hoechstens 3 Eintraege, 2 Minuten, monotone Uhr, Freigabe erneut geprueft, unbekannte Kennung`() {
        var now = 1_000L
        val st = UndoStore { now }
        val ids = (1..4).map { st.record(UndoSpec.VolumeTo(VolumeChannel.values()[it % 4], 10 * it)) }
        assertEquals(UndoTake.None, st.take(ids[0], all)) // aelteste verdraengt (N = 3)
        assertTrue(st.take(ids[3], all) is UndoTake.Ready)
        assertEquals(UndoTake.None, st.take(9_999L, all))
        now += UndoStore.TTL_MS + 1
        assertEquals(UndoTake.Expired, st.take(ids[3], all))
        assertEquals(120_000L, UndoStore.TTL_MS)
        assertEquals(3, UndoStore.MAX_ENTRIES)
        val st2 = store()
        val id = st2.record(UndoSpec.VolumeTo(VolumeChannel.MEDIA, 40))
        assertEquals(UndoTake.Disabled, st2.take(id, setOf(DeviceActionKind.FLASHLIGHT)))
    }

    @Test
    fun `Eintrag ohne Rueckgaengig-Daten ist nicht zuruecknehmbar`() {
        val st = store()
        val id = st.record(null, "open_app")
        assertEquals(UndoTake.None, st.take(id, all))
    }

    @Test
    fun `Timer und Wecker werden nie automatisch verworfen sondern ueber die Uhr-App`() {
        // Es gibt keine Suche nach dem eigenen Timer/Wecker: automatisches Verwerfen koennte einen anderen treffen
        assertEquals(ClockScreen.TIMERS, UndoSpec.ClockTimers.screen)
        assertEquals(ClockScreen.ALARMS, UndoSpec.ClockAlarms.screen)
        assertTrue(DeviceActionMessages.undone(UndoSpec.ClockTimers).contains("Uhr-App"))
        assertTrue(DeviceActionMessages.undone(UndoSpec.ClockTimers).contains("Timer"))
        assertTrue(DeviceActionMessages.undone(UndoSpec.ClockAlarms).contains("Wecker"))
        // keine Konflikte: Uhr-App oeffnen aendert nichts an anderen Eintraegen
        val st = store()
        val a = st.record(UndoSpec.ClockTimers)
        st.record(UndoSpec.ClockTimers)
        assertTrue(st.take(a, all) is UndoTake.Ready)
    }

    @Test
    fun `Meldungen nennen WAS zurueckgenommen wurde und warum es nicht mehr geht`() {
        assertEquals("Medien wieder auf 40 Prozent.", DeviceActionMessages.undone(UndoSpec.VolumeTo(VolumeChannel.MEDIA, 40)))
        assertEquals("Taschenlampe wieder aus.", DeviceActionMessages.undone(UndoSpec.FlashlightTo(false)))
        assertEquals("Taschenlampe wieder an.", DeviceActionMessages.undone(UndoSpec.FlashlightTo(true)))
        assertTrue(DeviceActionMessages.undoRefused(UndoTake.Superseded).contains("nicht mehr"))
        assertTrue(DeviceActionMessages.undoRefused(UndoTake.Expired).contains("nicht mehr"))
        assertTrue(DeviceActionMessages.undoRefused(UndoTake.AlreadyDone).isNotBlank())
        assertTrue(DeviceActionMessages.undoRefused(UndoTake.None).isNotBlank())
        assertTrue(DeviceActionMessages.undoRefused(UndoTake.Disabled).contains("Einstellungen"))
    }

    @Test
    fun `Sperrerkennung ergaenzt Broker Krypto und 2FA ohne Segment-Treffer`() {
        for (a in listOf(
            AppEntry("Ente Auth", "io.ente.auth"), AppEntry("FreeOTP", "org.fedorahosted.freeotp"),
            AppEntry("eToro", "com.etoro.openbook"), AppEntry("Trading 212", "com.trading212.app"),
            AppEntry("Smartbroker", "de.smartbroker.app"), AppEntry("Kraken", "com.kraken.invest.app"),
            AppEntry("MetaMask", "io.metamask"), AppEntry("SecureGo plus", "de.starfinanz.securego"),
        )) assertTrue("${a.label} muss gesperrt sein", AppResolver.isSensitive(a))
        assertFalse(AppResolver.isSensitive(AppEntry("Author", "de.example.author")))
    }

    @Test
    fun `Timer und Wecker melden, dass Rueckgaengig nur die Uhr-App oeffnet (Samsung loescht nicht, #210)`() {
        val t = DeviceActionResult(ResultKind.OK, "x", UndoSpec.ClockTimers, undoId = 3L).toJson()
        assertEquals(true, t.getBoolean("undoOpensClock"))
        assertEquals(true, DeviceActionResult(ResultKind.OK, "x", UndoSpec.ClockAlarms, undoId = 4L).toJson().getBoolean("undoOpensClock"))
        assertFalse(DeviceActionResult(ResultKind.OK, "x", UndoSpec.FlashlightTo(false), undoId = 7L).toJson().has("undoOpensClock"))
    }

    @Test
    fun `Ergebnis meldet die Rueckgaengig-Moeglichkeit ohne die Spec nach aussen zu geben`() {
        val j = DeviceActionResult(ResultKind.OK, "x", UndoSpec.FlashlightTo(false), undoId = 7L).toJson()
        assertEquals(true, j.getBoolean("undoable"))
        assertEquals(7L, j.getLong("undoId"))
        assertFalse(j.has("undo"))
        assertEquals(false, DeviceActionResult(ResultKind.OK, "x").toJson().getBoolean("undoable"))
        assertEquals(false, DeviceActionResult(ResultKind.FAILED, "x", UndoSpec.FlashlightTo(false)).toJson().getBoolean("undoable"))
    }

    @Test
    fun `Sperrerkennung Schlussrunde Enpass NordPass andOTP Duo Bunq ohne Fehltreffer`() {
        for (a in listOf(
            AppEntry("Enpass", "io.enpass.app"), AppEntry("NordPass", "com.nordsec.nordpass"),
            AppEntry("andOTP", "org.shadowice.flocke.andotp"), AppEntry("Duo Mobile", "com.duosecurity.duomobile"),
            AppEntry("bunq", "com.bunq.android"),
        )) assertTrue("${a.label} muss gesperrt sein", AppResolver.isSensitive(a))
        for (a in listOf(AppEntry("Duolingo", "com.duolingo"), AppEntry("Spotify", "com.spotify.music"), AppEntry("Kamera", "x.camera"))) {
            assertFalse("${a.label} darf nicht gesperrt sein", AppResolver.isSensitive(a))
        }
    }
}

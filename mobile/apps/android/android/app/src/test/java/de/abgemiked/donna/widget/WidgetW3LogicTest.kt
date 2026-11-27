package com.yourcompany.donna.widget

import com.yourcompany.donna.device.DeviceActionKind
import com.yourcompany.donna.device.DndLogic
import com.yourcompany.donna.device.OfflineCommands
import com.yourcompany.donna.device.OfflineRoutine
import com.yourcompany.donna.device.OfflineRoutineSnapshot
import com.yourcompany.donna.device.OfflineRoutineStep
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Welle 3 (#244–#247): reine Logik der W3-Widgets. Nichts hier schaltet etwas (keine Android-Aufrufe). */
class WidgetW3LogicTest {

    // ── #244 Szenen/Routinen ────────────────────────────────────

    @Test fun scenesParsedAndUnsafeIdsDropped() {
        val body = """{"scenes":[
            {"sceneId":"a1b2-c3","sceneName":"Filmabend"},
            {"sceneId":"../../devices","sceneName":"Böse"},
            {"sceneId":"x","sceneName":"  "},
            {"sceneId":"a1b2-c3","sceneName":"Doppelt"}]}"""
        val s = HomeActionsLogic.parseScenes(body)
        assertEquals(listOf(HomeButton("scene", "a1b2-c3", "Filmabend")), s)
    }

    @Test fun routinesNeedNumericIds() {
        val body = """{"routines":[{"id":3,"name":"Streammodus"},{"id":"4","name":"Text-ID"},{"id":0,"name":"Null"},{"id":7,"name":"Schlaf[enszeit]"}]}"""
        val r = HomeActionsLogic.parseRoutines(body)
        assertEquals(listOf("3", "7"), r.map { it.id })
        assertEquals("Schlafenszeit", r[1].name) // Klammern gesäubert
    }

    @Test fun brokenBodiesGiveEmptyLists() {
        assertTrue(HomeActionsLogic.parseScenes(null).isEmpty())
        assertTrue(HomeActionsLogic.parseScenes("kein json").isEmpty())
        assertTrue(HomeActionsLogic.parseRoutines("""{"routines":"x"}""").isEmpty())
    }

    @Test fun selectionRoundTripsAndIsCapped() {
        val many = (1..9).map { HomeButton("routine", "$it", "Routine $it") }
        val back = HomeActionsLogic.fromJson(HomeActionsLogic.toJson(many))
        assertEquals(HomeActionsLogic.MAX_BUTTONS, back.size)
        assertEquals(many.take(6), back)
        // manipulierte Ablage: ungültige Art/Kennung fällt weg
        val bad = """[{"t":"scene","i":"ok-1","n":"A"},{"t":"shell","i":"1","n":"B"},{"t":"routine","i":"99999999999","n":"C"}]"""
        assertEquals(listOf(HomeButton("scene", "ok-1", "A")), HomeActionsLogic.fromJson(bad))
    }

    @Test fun questionNamesTypeAndName() {
        assertEquals("Szene „Filmabend“ starten?", HomeActionsLogic.question(HomeButton("scene", "a", "Filmabend")))
        assertEquals("Routine „Streammodus“ starten?", HomeActionsLogic.question(HomeButton("routine", "1", "Streammodus")))
    }

    @Test fun runResultKeepsOnlyDeviceActions() {
        val body = """{"ok":true,"text":"Routine X: 1 am Handy angestoßen.","device":[
            {"type":"device_action","action":"flashlight","state":"off"},
            {"type":"home_control","domain":"light"}]}"""
        val r = HomeActionsLogic.parseRunResult(body)!!
        assertEquals("Routine X: 1 am Handy angestoßen.", r.text)
        assertEquals(listOf("flashlight"), r.device.map { it.getString("action") })
        assertNull(HomeActionsLogic.parseRunResult("""{"ok":false}"""))
        assertNull(HomeActionsLogic.parseRunResult("nope"))
    }

    @Test fun errorKinds() {
        assertEquals(HomeActionsLogic.ErrorKind.UNREACHABLE, HomeActionsLogic.errorKind(-1))
        assertEquals(HomeActionsLogic.ErrorKind.UNKNOWN, HomeActionsLogic.errorKind(-2)) // nie Offline-Lauf
        assertEquals(HomeActionsLogic.ErrorKind.NO_TOKEN, HomeActionsLogic.errorKind(401))
        assertEquals(HomeActionsLogic.ErrorKind.LIVE_LOCKED, HomeActionsLogic.errorKind(423))
        assertEquals(HomeActionsLogic.ErrorKind.RATE_LIMITED, HomeActionsLogic.errorKind(429))
        assertEquals(HomeActionsLogic.ErrorKind.NOT_CONFIGURED, HomeActionsLogic.errorKind(503))
        assertEquals(HomeActionsLogic.ErrorKind.GONE, HomeActionsLogic.errorKind(404))
        assertEquals(HomeActionsLogic.ErrorKind.FAILED, HomeActionsLogic.errorKind(500))
    }

    @Test fun layoutColumnsAndRows() {
        assertEquals(2, HomeActionsLogic.columnsFor(150))
        assertEquals(3, HomeActionsLogic.columnsFor(250))
        assertEquals(3, HomeActionsLogic.columnsFor(0))
        assertEquals(1, HomeActionsLogic.rowsFor(60))
        assertEquals(2, HomeActionsLogic.rowsFor(140))
        assertEquals(1, HomeActionsLogic.rowsFor(0))
    }

    // Offline-Weg des Widgets: nur Geräte-Schritte aus dem lokalen Stand, Smart Home nie
    private fun dev(vararg p: Pair<String, Any>) = JSONObject().put("type", "device_action").also { j -> p.forEach { (k, v) -> j.put(k, v) } }
    private val stream = OfflineRoutine("Streammodus", listOf(
        OfflineRoutineStep(dev("action" to "flashlight", "state" to "on"), "Taschenlampe an"),
        OfflineRoutineStep(null, "Schalten: softbox an"),
    ))
    private val all = DeviceActionKind.values().toSet() - DeviceActionKind.CHAT_ONLY

    @Test fun offlineRunUsesStoredDeviceStepsOnly() {
        val o = OfflineCommands.runStoredRoutine("Streammodus", all, OfflineRoutineSnapshot(1L, listOf(stream)), nowMs = 2L)
            as OfflineCommands.Outcome.Run
        assertEquals(listOf("flashlight"), o.actions.map { it.getString("action") })
        assertTrue(o.speech.contains("braucht Verbindung"))
    }

    @Test fun offlineRunUnknownOrStale() {
        assertNull(OfflineCommands.runStoredRoutine("Gibtsnicht", all, OfflineRoutineSnapshot(1L, listOf(stream)), nowMs = 2L))
        assertNull(OfflineCommands.runStoredRoutine("Streammodus", all, null))
        val old = OfflineCommands.runStoredRoutine("Streammodus", all, OfflineRoutineSnapshot(1L, listOf(stream)),
            nowMs = 1L + OfflineCommands.MAX_SNAPSHOT_AGE_MS + 1) as OfflineCommands.Outcome.Say
        assertEquals(OfflineCommands.STALE, old.speech)
    }

    @Test fun offlineRunRespectsKindSwitch() {
        val o = OfflineCommands.runStoredRoutine("Streammodus", emptySet(), OfflineRoutineSnapshot(1L, listOf(stream)), nowMs = 2L)
            as OfflineCommands.Outcome.Say
        assertTrue(o.speech.contains("nichts angestoßen"))
    }

    // ── #245 Nicht stören ───────────────────────────────────────

    @Test fun focusState() {
        assertEquals(FocusState.NO_PERMISSION, FocusWidgetLogic.state(false, DndLogic.FILTER_PRIORITY))
        assertEquals(FocusState.OFF, FocusWidgetLogic.state(true, DndLogic.FILTER_ALL))
        assertEquals(FocusState.OFF, FocusWidgetLogic.state(true, DndLogic.FILTER_UNKNOWN))
        assertEquals(FocusState.ON, FocusWidgetLogic.state(true, DndLogic.FILTER_PRIORITY))
        assertEquals(FocusState.ON, FocusWidgetLogic.state(true, DndLogic.FILTER_NONE))
        assertEquals(FocusState.ON, FocusWidgetLogic.state(true, DndLogic.FILTER_ALARMS))
    }

    @Test fun compactThresholds() {
        assertTrue(FocusWidgetLogic.compact(60))
        assertFalse(FocusWidgetLogic.compact(0))   // unbekannt -> volle Darstellung
        assertFalse(FocusWidgetLogic.compact(130))
        assertTrue(BriefingWidgetLogic.compact(150))
        assertFalse(BriefingWidgetLogic.compact(0))
        assertFalse(BriefingWidgetLogic.compact(250))
    }

    // ── #246 Tagesbriefing ──────────────────────────────────────

    @Test fun briefingShowsNeutralNumbersButNeverMood() {
        val body = """{"text":"Gestern warst du überwiegend müde.","streak":5,"total_30d":12,"today_count":3,
            "recent_mood":"tired","mood_confidence":0.8,"peak_time":"evening","peak_weekdays":["Fr"],
            "proactivity_level":3,"proactivity_trend":"x"}"""
        val v = BriefingWidgetLogic.parse(body)!!
        assertEquals("5 Tage in Folge", v.headline)
        assertEquals("Heute 3 Gespräche · 12 von 30 Tagen · aktiv meist abends", v.detail)
        val shown = v.headline + v.detail + BriefingWidgetLogic.toCache(v)
        assertFalse(shown.contains("müde"))
        assertFalse(shown.contains("tired"))
    }

    @Test fun briefingEdgeCases() {
        assertNull(BriefingWidgetLogic.parse("""{"text":"ohne Zahlen"}"""))
        assertNull(BriefingWidgetLogic.parse("kaputt"))
        val v = BriefingWidgetLogic.parse("""{"streak":0,"today_count":1,"total_30d":0,"peak_time":null}""")!!
        assertEquals("Schön, dass du da bist", v.headline)
        assertEquals("Heute 1 Gespräch", v.detail)
        assertEquals(v, BriefingWidgetLogic.fromCache(BriefingWidgetLogic.toCache(v)))
        assertNull(BriefingWidgetLogic.fromCache("{}"))
    }

    @Test fun briefingThrottle() {
        val min = 60_000L
        assertTrue(BriefingWidgetLogic.due(100 * min, 0L, 0L))
        assertFalse(BriefingWidgetLogic.due(100 * min, 80 * min, 0L))   // Erfolg vor 20 min
        assertFalse(BriefingWidgetLogic.due(100 * min, 0L, 97 * min))   // Versuch vor 3 min
        assertTrue(BriefingWidgetLogic.due(100 * min, 60 * min, 90 * min))
    }

    // ── #247 Timer ──────────────────────────────────────────────

    @Test fun timerAddPrunesExpiredAndSorts() {
        val now = 1_000_000L
        var list = TimerWidgetLogic.add(emptyList(), now, 10, "Shisha-Kohle")
        list = TimerWidgetLogic.add(list, now, 3, "")
        assertEquals(listOf(now + 3 * 60_000L, now + 10 * 60_000L), list.map { it.endsAtMs })
        // nach 5 min ist der 3-min-Timer weg
        val later = TimerWidgetLogic.active(list, now + 5 * 60_000L)
        assertEquals(listOf("Shisha-Kohle"), later.map { it.label })
        assertEquals(now + 10 * 60_000L, TimerWidgetLogic.next(list, now + 5 * 60_000L))
        assertNull(TimerWidgetLogic.next(list, now + 11 * 60_000L))
    }

    @Test fun timerRejectsOutOfRangeAndCaps() {
        val now = 0L
        assertTrue(TimerWidgetLogic.add(emptyList(), now, 0, "x").isEmpty())
        assertTrue(TimerWidgetLogic.add(emptyList(), now, TimerWidgetLogic.MAX_MINUTES + 1, "x").isEmpty())
        var list = emptyList<WidgetTimer>()
        for (m in 1..8) list = TimerWidgetLogic.add(list, now, m, "T$m")
        assertEquals(TimerWidgetLogic.MAX_TIMERS, list.size)
        assertEquals("T1", list.first().label) // die bald ablaufenden bleiben
    }

    @Test fun timerJsonRoundTripAndLabelClean() {
        val list = TimerWidgetLogic.add(emptyList(), 0L, 5, "Nudeln\n<b>al dente</b>")
        val back = TimerWidgetLogic.fromJson(TimerWidgetLogic.toJson(list))
        assertEquals(list, back)
        assertEquals("Nudeln bal dente/b", back.single().label)
        assertTrue(TimerWidgetLogic.fromJson("""[{"e":0,"l":"x"},"y"]""").isEmpty())
    }

    @Test fun timerRows() {
        assertEquals(1, TimerWidgetLogic.rowsFor(0))
        assertEquals(1, TimerWidgetLogic.rowsFor(60))
        assertEquals(2, TimerWidgetLogic.rowsFor(120))
        assertEquals(3, TimerWidgetLogic.rowsFor(200))
    }
}

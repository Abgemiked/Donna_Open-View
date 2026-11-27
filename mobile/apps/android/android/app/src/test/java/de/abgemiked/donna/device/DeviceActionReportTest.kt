package com.yourcompany.donna.device

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #183 Schritt 0 (Refs #147): Rückkanal App -> Server. Reine Logik: Zuordnung ResultKind -> Ergebniscode, Kennungen,
 * Anfrage-Körper, Wiederholungsregel. Der Vertrag liegt EINMAL in `backend/tests/contracts/device_action_result_contract.json`;
 * dieser Test liest dieselbe Datei wie der Server-Test — weicht eine Seite ab, schlägt er an.
 */
class DeviceActionReportTest {

    private val contract: JSONObject by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var found: File? = null
        while (dir != null && found == null) {
            val f = File(dir, "backend/tests/contracts/device_action_result_contract.json")
            if (f.isFile) found = f
            dir = dir.parentFile
        }
        JSONObject(requireNotNull(found) { "Vertragsdatei nicht gefunden" }.readText(Charsets.UTF_8))
    }

    private fun strings(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }

    @Test fun vertrag_codes_und_ursachen_stimmen() {
        assertEquals(strings(contract.getJSONArray("result_codes")), DeviceActionReport.RESULT_CODES)
        assertEquals(strings(contract.getJSONArray("reasons")), DeviceActionReport.REASONS)
        assertEquals(contract.getString("action_id_pattern"), DeviceActionReport.ACTION_ID_PATTERN)
        assertEquals(contract.getString("group_id_pattern"), DeviceActionReport.GROUP_ID_PATTERN)
        assertEquals(contract.getString("request_flag"), DeviceActionReport.REQUEST_FLAG)
        assertEquals(contract.getString("path_template").replace("{action_id}", "da_0123456789abcdef"),
            DeviceActionReport.path("da_0123456789abcdef"))
    }

    @Test fun vertrag_zuordnung_je_ergebnisart() {
        val rows = contract.getJSONArray("kind_mapping")
        val tableKinds = (0 until rows.length()).map { rows.getJSONObject(it).getString("app_kind") }.toSet()
        // jede ResultKind des Geräts ist in der Tabelle — nichts bleibt ohne Code
        assertEquals(ResultKind.values().map { it.key }.toSet(), tableKinds)
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val kind = ResultKind.values().first { it.key == row.getString("app_kind") }
            val w = DeviceActionReport.wire(kind)
            assertEquals("Code für ${kind.key}", row.getString("result"), w.result)
            assertEquals("Ursache für ${kind.key}", if (row.isNull("reason")) null else row.getString("reason"), w.reason)
        }
    }

    @Test fun ergebnis_ok_hat_keine_ursache() {
        val w = DeviceActionReport.wire(ResultKind.OK)
        assertEquals("ok", w.result)
        assertNull(w.reason)
    }

    @Test fun body_nur_code_und_ursache_keine_inhalte() {
        val j = JSONObject(DeviceActionReport.body(DeviceActionReport.wire(ResultKind.NEEDS_PERMISSION)))
        assertEquals(setOf("result", "reason"), j.keys().asSequence().toSet())
        assertEquals("braucht_berechtigung", j.getString("result"))
        val ok = JSONObject(DeviceActionReport.body(DeviceActionReport.wire(ResultKind.OK)))
        assertEquals(setOf("result"), ok.keys().asSequence().toSet())
    }

    @Test fun kennung_nur_gueltig() {
        assertEquals("da_0123456789abcdef", DeviceActionReport.actionId(JSONObject().put("action_id", "da_0123456789abcdef")))
        assertNull(DeviceActionReport.actionId(JSONObject()))
        assertNull(DeviceActionReport.actionId(JSONObject().put("action_id", "da_XYZ")))
        assertNull(DeviceActionReport.actionId(JSONObject().put("action_id", "../x")))
        assertNull(DeviceActionReport.actionId(JSONObject().put("action_id", 5)))
        assertNull(DeviceActionReport.actionId(JSONObject().put("action_id", "da_0123456789abcdef\n")))
    }

    @Test fun pfad_aus_kennung() {
        assertEquals("/device-actions/da_0123456789abcdef/result", DeviceActionReport.path("da_0123456789abcdef"))
    }

    @Test fun wiederholung_gegen_vertrag() {
        val http = contract.getJSONObject("http")
        val retry = contract.getJSONArray("retry_codes_app")
        val retryCodes = (0 until retry.length()).map { retry.getInt(it) }.toSet()
        val all = (0 until http.names().length()).map { http.getInt(http.names().getString(it)) }.toSet()
        for (c in all) assertEquals("Code $c", c in retryCodes, DeviceActionReport.shouldRetry(c))
        assertTrue(DeviceActionReport.shouldRetry(null))
    }

    @Test fun wiederholung_nur_bei_netz_und_serverfehlern() {
        assertTrue(DeviceActionReport.shouldRetry(null))
        assertTrue(DeviceActionReport.shouldRetry(500))
        assertTrue(DeviceActionReport.shouldRetry(503))
        assertTrue(DeviceActionReport.shouldRetry(429))
        assertFalse(DeviceActionReport.shouldRetry(200))
        assertFalse(DeviceActionReport.shouldRetry(404))
        assertFalse(DeviceActionReport.shouldRetry(409))
        assertFalse(DeviceActionReport.shouldRetry(422))
        assertFalse(DeviceActionReport.shouldRetry(401))
    }

    // ── Parser: Kennungen sind erlaubt, alles andere Unbekannte bleibt abgelehnt ───────────────────
    private fun flashlight(vararg extra: Pair<String, Any?>) = JSONObject().apply {
        put("type", "device_action"); put("action", "flashlight"); put("state", "on")
        extra.forEach { (k, v) -> put(k, v) }
    }

    @Test fun parser_akzeptiert_gueltige_kennung_und_gruppe() {
        val r = DeviceActionParser.parse(flashlight("action_id" to "da_0123456789abcdef", "group_id" to "g_0123456789abcdef"))
        assertTrue("war $r", r is ParseResult.Ok)
    }

    @Test fun parser_lehnt_ungueltige_kennung_ab() {
        assertTrue(DeviceActionParser.parse(flashlight("action_id" to "boese")) is ParseResult.Rejected)
        assertTrue(DeviceActionParser.parse(flashlight("group_id" to "da_0123456789abcdef")) is ParseResult.Rejected)
        assertTrue(DeviceActionParser.parse(flashlight("action_id" to 7)) is ParseResult.Rejected)
    }

    @Test fun parser_lehnt_weiter_unbekannte_felder_ab() {
        assertTrue(DeviceActionParser.parse(flashlight("extra" to "x")) is ParseResult.Rejected)
        assertTrue(DeviceActionParser.parse(flashlight("action_id" to "da_0123456789abcdef", "extra" to "x")) is ParseResult.Rejected)
    }

    @Test fun parser_ohne_kennung_wie_bisher() {
        val r = DeviceActionParser.parse(flashlight())
        assertNotNull(r)
        assertTrue(r is ParseResult.Ok)
    }
}

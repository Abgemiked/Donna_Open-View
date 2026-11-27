package com.yourcompany.donna.device

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #147 Paket D — Vertragstest Gerät <-> Server. Liest DIESELBE Tabelle wie `backend/tests/test_offline_contract.py`
 * (`backend/tests/contracts/offline_commands.json`): Satz -> erwartete Aktion. Weicht das Gerät ab, schlägt dieser Test
 * an; weicht der Server ab, schlägt der Python-Test an. Keine echten Schaltungen: nur reine Auswertung.
 */
class OfflineContractTest {

    private val table: JSONObject by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var found: File? = null
        while (dir != null && found == null) {
            val f = File(dir, "backend/tests/contracts/offline_commands.json")
            if (f.isFile) found = f
            dir = dir.parentFile
        }
        JSONObject(requireNotNull(found) { "Vertragstabelle nicht gefunden" }.readText(Charsets.UTF_8))
    }

    private fun rows(): List<JSONObject> {
        val a: JSONArray = table.getJSONArray("commands")
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    private fun appKey(s: String) = OfflineIntent.fold(s)

    @Test fun tabelle_ist_gross_genug() {
        assertTrue(rows().size >= 60)
    }

    @Test fun jeder_satz_ergibt_die_erwartete_einordnung() {
        for (row in rows()) {
            val say = row.getString("say")
            val c = OfflineCommands.classify(say)
            assertEquals("Einordnung für „$say“", row.getString("outcome"), c.outcome)
            if (row.getString("outcome") != "action") {
                assertNull("keine Aktion für „$say“", c.act)
                continue
            }
            val exp = row.getJSONObject("expect")
            val act = requireNotNull(c.act) { "Aktion für „$say“" }
            assertEquals("Aktion für „$say“", exp.getString("action"), act.name)
            val expParams = exp.keys().asSequence().filter { it != "action" }.toList()
            assertEquals("Parameter für „$say“", expParams.toSet(), act.params.keys)
            for (k in expParams) {
                val ev = exp.get(k)
                val av = act.params.getValue(k)
                if (k == "app") assertEquals("App für „$say“", appKey(ev as String), appKey(av as String))
                else assertEquals("Wert $k für „$say“", ev.toString(), av.toString())
            }
        }
    }

    @Test fun jede_erkannte_aktion_besteht_die_pruefung_des_geraets() {
        for (row in rows().filter { it.getString("outcome") == "action" }) {
            val c = OfflineCommands.classify(row.getString("say"))
            val r = DeviceActionParser.parse(OfflineCommands.toJson(c.act!!))
            assertTrue("Schema für „${row.getString("say")}“: $r", r is ParseResult.Ok)
        }
    }

    @Test fun routinen_ausloeser_wie_beim_server() {
        val names = (0 until table.getJSONArray("routine_names").length())
            .map { table.getJSONArray("routine_names").getString(it) }
            .associateBy { OfflineIntent.nameKey(it) }
        val a = table.getJSONArray("routine_triggers")
        for (i in 0 until a.length()) {
            val r = a.getJSONObject(i)
            val expect = if (r.isNull("expect")) null else r.getString("expect")
            assertEquals("Auslöser „${r.getString("say")}“", expect, OfflineIntent.matchTrigger(r.getString("say"), names))
        }
    }
}

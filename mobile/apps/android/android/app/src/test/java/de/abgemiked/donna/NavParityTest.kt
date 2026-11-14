package com.yourcompany.donna

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Paritaet Overlay ↔ Haupt-Chat: dieselben Fixtures (src/test/resources/nav_parity.json) pruefen auch
 * src/__tests__/navParity.test.ts gegen getNavTarget(). Gleiche Action → gleiches Navigationsziel.
 */
class NavParityTest {

    private fun cases(): List<Triple<String, JSONObject, String>> {
        val text = javaClass.classLoader!!.getResourceAsStream("nav_parity.json")!!.bufferedReader(Charsets.UTF_8).readText()
        val arr = JSONObject(text).getJSONArray("cases")
        return (0 until arr.length()).map {
            val c = arr.getJSONObject(it)
            Triple(c.getString("name"), c.getJSONObject("action"), c.getString("expected"))
        }
    }

    @Test
    fun `Overlay waehlt fuer jede Fixture-Action das erwartete Navigationsziel`() {
        val all = cases()
        assertTrue("Fixtures fehlen", all.size >= 8)
        for ((name, action, expected) in all) {
            assertEquals(name, expected, NavActionLogic.targetOfAction(action))
        }
    }

    @Test
    fun `resolved_address wird gesendet, wenn vorhanden - sonst das rohe Ziel`() {
        val with = JSONObject("""{"type":"tesla_navigate","destination":"zum Bahnhof","resolved_address":"Hauptbahnhof Nürnberg"}""")
        val without = JSONObject("""{"type":"tesla_navigate","destination":"zum Bahnhof"}""")
        assertEquals("Hauptbahnhof Nürnberg", NavActionLogic.targetOfAction(with))
        assertEquals("zum Bahnhof", NavActionLogic.targetOfAction(without))
    }

    @Test
    fun `nie das Wort null`() {
        val a = JSONObject("""{"type":"tesla_navigate","destination":null,"resolved_address":null,"nav_address":null,"query":null}""")
        assertEquals("", NavActionLogic.targetOfAction(a))
    }
}

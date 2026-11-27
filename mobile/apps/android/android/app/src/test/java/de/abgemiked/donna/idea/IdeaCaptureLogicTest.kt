package com.yourcompany.donna.idea

import com.yourcompany.donna.widget.DonnaEntries
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
 * #233: reine Logik der Ideen-Schnellerfassung. Der Vertrag liegt EINMAL in
 * `backend/tests/contracts/inbox_capture_contract.json`; Server-Test und dieser Test lesen dieselbe Datei.
 */
class IdeaCaptureLogicTest {

    private val contract: JSONObject by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var found: File? = null
        while (dir != null && found == null) {
            val f = File(dir, "backend/tests/contracts/inbox_capture_contract.json")
            if (f.isFile) found = f
            dir = dir.parentFile
        }
        JSONObject(requireNotNull(found) { "Vertragsdatei nicht gefunden" }.readText(Charsets.UTF_8))
    }

    private fun strings(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }

    private fun idea(nonce: String = "abcdef0123456789", text: String = "Idee", projekt: String = "dj") =
        PendingIdea(nonce, text, projekt, IdeaCaptureLogic.KANAL_WIDGET)

    @Test fun vertrag_stimmt() {
        assertEquals(strings(contract.getJSONArray("projekte")), IdeaCaptureLogic.PROJEKTE)
        assertEquals(contract.getString("default_projekt"), IdeaCaptureLogic.DEFAULT_PROJEKT)
        assertEquals(contract.getInt("text_max"), IdeaCaptureLogic.TEXT_MAX)
        assertEquals(contract.getString("nonce_pattern"), IdeaCaptureLogic.NONCE_PATTERN)
        assertEquals(strings(contract.getJSONArray("kanaele")), IdeaCaptureLogic.KANAELE)
        assertEquals(contract.getString("path"), IdeaCaptureLogic.PATH)
    }

    @Test fun standard_projekt_steht_vorn() {
        assertEquals(IdeaCaptureLogic.DEFAULT_PROJEKT, IdeaCaptureLogic.PROJEKTE.first())
        assertEquals(IdeaCaptureLogic.PROJEKTE.size, IdeaCaptureLogic.PROJEKTE.toSet().size)
    }

    @Test fun gemerkte_wahl_nur_aus_liste() {
        assertEquals("dj", IdeaCaptureLogic.initialProjekt("dj"))
        assertEquals("uebergreifend", IdeaCaptureLogic.initialProjekt("gibts-nicht"))
        assertEquals("uebergreifend", IdeaCaptureLogic.initialProjekt(null))
    }

    @Test fun kanal_nur_bekannte() {
        assertEquals("shortcut", IdeaCaptureLogic.kanalOrDefault("shortcut"))
        assertEquals("widget", IdeaCaptureLogic.kanalOrDefault("mail"))
        assertEquals("widget", IdeaCaptureLogic.kanalOrDefault(null))
    }

    @Test fun text_bereinigen_und_kappen() {
        assertNull(IdeaCaptureLogic.cleanText("   \n "))
        assertNull(IdeaCaptureLogic.cleanText(null))
        assertEquals("Hallo", IdeaCaptureLogic.cleanText("  Hallo \n"))
        assertEquals(IdeaCaptureLogic.TEXT_MAX, IdeaCaptureLogic.cleanText("x".repeat(5000))!!.length)
    }

    @Test fun kuerzen_zerschneidet_kein_emoji() {
        val emoji = String(Character.toChars(0x1F4A1)) // Gluehbirne (Surrogatpaar)
        val s = "x".repeat(IdeaCaptureLogic.TEXT_MAX - 1) + emoji
        val cut = IdeaCaptureLogic.takeSafe(s, IdeaCaptureLogic.TEXT_MAX)
        assertEquals(IdeaCaptureLogic.TEXT_MAX - 1, cut.length)
        assertFalse(Character.isHighSurrogate(cut.last()))
        assertEquals("ab", IdeaCaptureLogic.takeSafe("ab", 5))
        assertEquals(IdeaCaptureLogic.TEXT_MAX - 1, IdeaCaptureLogic.cleanText(s)!!.length)
    }

    @Test fun diktat_anhaengen() {
        assertEquals("Hallo Welt", IdeaCaptureLogic.appendDictation("Hallo ", "Welt"))
        assertEquals("Welt", IdeaCaptureLogic.appendDictation("", " Welt "))
        assertEquals("Hallo", IdeaCaptureLogic.appendDictation("Hallo", null))
        assertEquals(IdeaCaptureLogic.TEXT_MAX, IdeaCaptureLogic.appendDictation("x".repeat(1999), "abc").length)
    }

    @Test fun nonce_passt_zum_muster() {
        repeat(20) { assertTrue(IdeaCaptureLogic.isValidNonce(IdeaCaptureLogic.newNonce())) }
        assertFalse(IdeaCaptureLogic.isValidNonce("kurz"))
        assertFalse(IdeaCaptureLogic.isValidNonce("../../0123456789abcd"))
        assertTrue(IdeaCaptureLogic.newNonce() != IdeaCaptureLogic.newNonce())
    }

    @Test fun koerper_genau_die_vertragsfelder() {
        val b = JSONObject(requireNotNull(IdeaCaptureLogic.body(idea(text = "  Neue Idee  "))))
        assertEquals(setOf("text", "projekt", "nonce", "kanal"), b.keys().asSequence().toSet())
        assertEquals("Neue Idee", b.getString("text"))
        assertEquals("dj", b.getString("projekt"))
    }

    @Test fun koerper_ungueltig_wird_nie_gesendet() {
        assertNull(IdeaCaptureLogic.body(idea(projekt = "evil")))
        assertNull(IdeaCaptureLogic.body(idea(nonce = "x")))
        assertNull(IdeaCaptureLogic.body(idea(text = "  ")))
        assertNull(IdeaCaptureLogic.body(idea().copy(kanal = "mail")))
    }

    @Test fun ergebnis_zuordnung() {
        assertEquals(IdeaOutcome.SENT, IdeaCaptureLogic.outcome(200))
        assertEquals(IdeaOutcome.PERMANENT, IdeaCaptureLogic.outcome(422))
        assertEquals(IdeaOutcome.PERMANENT, IdeaCaptureLogic.outcome(400))
        for (c in listOf(null, 401, 403, 409, 429, 500, 502, 503)) {
            assertEquals("Code $c", IdeaOutcome.RETRY, IdeaCaptureLogic.outcome(c))
        }
        // Statuscodes aus dem Vertrag passen zur Zuordnung
        val sc = contract.getJSONObject("status_codes")
        assertEquals(IdeaOutcome.SENT, IdeaCaptureLogic.outcome(sc.getInt("angelegt")))
        assertEquals(IdeaOutcome.PERMANENT, IdeaCaptureLogic.outcome(sc.getInt("ungueltig")))
        for (k in listOf("nicht_autorisiert", "laeuft_schon", "zu_viele", "github_fehler", "nicht_eingerichtet")) {
            assertEquals(k, IdeaOutcome.RETRY, IdeaCaptureLogic.outcome(sc.getInt(k)))
        }
    }

    @Test fun issue_nummer_aus_antwort() {
        assertEquals(42, IdeaCaptureLogic.issueNumber("""{"number":42,"url":"u","duplicate":false}"""))
        assertNull(IdeaCaptureLogic.issueNumber("kaputt"))
        assertNull(IdeaCaptureLogic.issueNumber(null))
    }

    @Test fun warteschlange_hinzufuegen_ersetzen_entfernen() {
        var raw: String? = null
        raw = IdeaCaptureLogic.queueAdd(raw, idea(nonce = "aaaaaaaaaaaaaaaa"))
        raw = IdeaCaptureLogic.queueAdd(raw, idea(nonce = "bbbbbbbbbbbbbbbb"))
        raw = IdeaCaptureLogic.queueAdd(raw, idea(nonce = "aaaaaaaaaaaaaaaa", text = "neu"))
        val items = IdeaCaptureLogic.queueParse(raw)
        assertEquals(listOf("bbbbbbbbbbbbbbbb", "aaaaaaaaaaaaaaaa"), items.map { it.nonce })
        assertEquals("neu", items.last().text)
        raw = IdeaCaptureLogic.queueRemove(raw, "bbbbbbbbbbbbbbbb")
        assertEquals(listOf("aaaaaaaaaaaaaaaa"), IdeaCaptureLogic.queueParse(raw).map { it.nonce })
        assertTrue(IdeaCaptureLogic.queueContains(raw, "aaaaaaaaaaaaaaaa"))
    }

    @Test fun warteschlange_voll_verdraengt_nichts() {
        var raw: String? = null
        for (i in 0 until IdeaCaptureLogic.QUEUE_MAX) {
            raw = IdeaCaptureLogic.queueAdd(raw, idea(nonce = "n%015d".format(i)))
        }
        assertNull(IdeaCaptureLogic.queueAdd(raw, idea(nonce = "zzzzzzzzzzzzzzzz")))
        assertEquals(IdeaCaptureLogic.QUEUE_MAX, IdeaCaptureLogic.queueParse(raw).size)
    }

    @Test fun dauerhafter_fehler_verwirft_nach_max_versuchen() {
        var raw: String? = IdeaCaptureLogic.queueAdd(null, idea(nonce = "aaaaaaaaaaaaaaaa"))
        for (i in 1 until IdeaCaptureLogic.MAX_ATTEMPTS) {
            val (next, dropped) = IdeaCaptureLogic.queueFail(raw, "aaaaaaaaaaaaaaaa")
            assertFalse(dropped)
            raw = next
            assertEquals(i, IdeaCaptureLogic.queueParse(raw).single().attempts)
        }
        val (last, dropped) = IdeaCaptureLogic.queueFail(raw, "aaaaaaaaaaaaaaaa")
        assertTrue(dropped)
        assertTrue(IdeaCaptureLogic.queueParse(last).isEmpty())
    }

    @Test fun kaputte_warteschlange_ist_leer_und_filtert_ungueltige() {
        assertTrue(IdeaCaptureLogic.queueParse("{kaputt").isEmpty())
        assertTrue(IdeaCaptureLogic.queueParse(null).isEmpty())
        val raw = """[{"nonce":"x","text":"a","projekt":"dj","kanal":"widget"},
            {"nonce":"aaaaaaaaaaaaaaaa","text":"ok","projekt":"dj","kanal":"widget"}]"""
        assertEquals(listOf("aaaaaaaaaaaaaaaa"), IdeaCaptureLogic.queueParse(raw).map { it.nonce })
    }

    @Test fun anzeige_namen() {
        assertEquals("Donna", IdeaCaptureLogic.label("donna-assistentin"))
        assertEquals("dj", IdeaCaptureLogic.label("dj"))
        assertNotNull(IdeaCaptureLogic.label("uebergreifend"))
    }

    @Test fun textmodus_erkennung() {
        assertTrue(DonnaEntries.isTextStart("text"))
        assertFalse(DonnaEntries.isTextStart(null))
        assertFalse(DonnaEntries.isTextStart("voice"))
        assertFalse(DonnaEntries.isTextStart("TEXT"))
    }

    @Test fun shortcut_extras_passen_zu_den_einstiegen() {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var xml: File? = null
        while (dir != null && xml == null) {
            val f = File(dir, "src/main/res/xml/shortcuts.xml")
            val g = File(dir, "app/src/main/res/xml/shortcuts.xml")
            xml = if (f.isFile) f else if (g.isFile) g else null
            dir = dir.parentFile
        }
        val s = requireNotNull(xml) { "shortcuts.xml nicht gefunden" }.readText(Charsets.UTF_8)
        assertTrue(s.contains("""android:name="${DonnaEntries.EXTRA_START_MODE}" android:value="${DonnaEntries.START_MODE_TEXT}""""))
        assertTrue(s.contains("""android:name="${DonnaEntries.EXTRA_KANAL}" android:value="${IdeaCaptureLogic.KANAL_SHORTCUT}""""))
    }
}

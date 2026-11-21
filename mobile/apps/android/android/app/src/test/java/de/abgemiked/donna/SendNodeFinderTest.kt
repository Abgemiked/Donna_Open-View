package com.yourcompany.donna

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-Unit-Tests für die REINE Send-Node-Such-Logik (B2 Accessibility Auto-Send #3/#1).
 *
 * Nach Stufe-3-Review (2. Runde) ist die Heuristik KONSERVATIV: lieber Fallback „bitte tippen" als
 * ein falscher Button/Kontakt. Deshalb:
 *  - viewId: exaktes Suffix MIT Grenze ("/send").
 *  - contentDescription/Text: EXAKTE Gleichheit gegen eine enge Label-Liste (kein Teilwort/Phrase):
 *    „Anrufen Max Mustermann"/„Standort senden"/„Senden abbrechen" treffen NICHT.
 *  - MEHRDEUTIGKEIT: >1 Kandidat in einer Stufe → kein eindeutiger Treffer → Fallback.
 *  - Treffer muss SELBST usable sein: isClickable + sichtbar + aktiv + Fläche ≤ 40 %; unbekannte
 *    Fläche (null) → fail-closed (nicht usable).
 */
class SendNodeFinderTest {

    private val whatsapp = SendNodeFinder.specFor("whatsapp")!!.toHints()
    private val call = SendNodeFinder.specFor("call")!!.toHints()

    /** Test-Factory: standardmäßig klickbar, sichtbar, aktiv, kleine Fläche (usable). */
    private fun n(
        viewId: String? = null,
        desc: String? = null,
        text: String? = null,
        clickable: Boolean = true,
        enabled: Boolean = true,
        visible: Boolean = true,
        area: Double? = 0.02,
        children: List<UiNode> = emptyList(),
    ) = UiNode(viewId, desc, text, clickable, enabled, visible, area, children)

    // ── (1) viewId: exakte Grenze "/send" ─────────────────────────────────────
    @Test
    fun `findet WhatsApp Send-Node ueber viewId mit Grenze`() {
        val send = n(viewId = "com.whatsapp:id/send", desc = "Senden")
        assertSame(send, SendNodeFinder.find(n(children = listOf(n(viewId = "com.whatsapp:id/entry", text = "x"), send)), whatsapp))
    }

    @Test
    fun `viewId send_status resend xsend matchen NICHT (Suffix-Grenze)`() {
        assertFalse(SendNodeFinder.matchesViewId("com.whatsapp:id/send_status", whatsapp))
        assertFalse(SendNodeFinder.matchesViewId("com.whatsapp:id/resend", whatsapp))
        assertFalse(SendNodeFinder.matchesViewId("com.whatsapp:id/xsend", whatsapp))
        assertTrue(SendNodeFinder.matchesViewId("com.whatsapp:id/send", whatsapp))
        assertNull(SendNodeFinder.find(n(children = listOf(n(viewId = "com.whatsapp:id/send_status"))), whatsapp))
    }

    // ── (2) contentDescription: EXAKTE Gleichheit, keine Phrase ───────────────
    @Test
    fun `exakte Description Senden wird gefunden`() {
        val send = n(desc = "Senden")
        assertSame(send, SendNodeFinder.find(n(children = listOf(send)), whatsapp))
        val sendEn = n(desc = "Send")
        assertSame(sendEn, SendNodeFinder.find(n(children = listOf(sendEn)), whatsapp))
    }

    @Test
    fun `Phrasen mit Senden als Teil matchen NICHT (WhatsApp)`() {
        for (d in listOf("Standort senden", "Senden abbrechen", "Nachricht nicht senden", "Nachricht senden", "versendet")) {
            assertNull("„$d\" darf kein Treffer sein", SendNodeFinder.find(n(children = listOf(n(desc = d))), whatsapp))
        }
    }

    @Test
    fun `Dialer-Phrasen mit Anrufen als Teil matchen NICHT (kein falscher Kontakt)`() {
        for (d in listOf("Anrufen Max Mustermann", "Video anrufen", "Max Mustermann anrufen")) {
            assertNull("„$d\" darf kein Call-Treffer sein", SendNodeFinder.find(n(children = listOf(n(desc = d))), call))
        }
    }

    @Test
    fun `exaktes Anrufen wird gefunden (call)`() {
        val callBtn = n(desc = "Anrufen")
        assertSame(callBtn, SendNodeFinder.find(n(children = listOf(callBtn)), call))
    }

    @Test
    fun `Negativ-Labels der Telefonie matchen NIE (call)`() {
        for (d in listOf("Anruf beenden", "End call", "Video call", "Videoanruf", "Wahlwiederholung", "Ablehnen", "Auflegen", "Call", "Dial")) {
            assertNull("„$d\" darf kein Call-Treffer sein", SendNodeFinder.find(n(children = listOf(n(desc = d))), call))
        }
    }

    // ── (R2-C) Text-Match ENTFERNT: klickbarer Button-Text führt NICHT mehr zum Treffer ──
    @Test
    fun `exakter Button-Text Senden fuehrt jetzt zum Fallback (P3 entfernt)`() {
        // Früher P3-Treffer; jetzt bewusst KEIN Match (Textmatch-Risiko, z. B. Chat-Blase „Senden").
        assertNull(SendNodeFinder.find(n(children = listOf(n(text = "Senden"))), whatsapp))
        assertNull(SendNodeFinder.find(n(children = listOf(n(text = "ich send dir das gleich"), n(text = "Senden"))), whatsapp))
    }

    @Test
    fun `Text wird ignoriert, Description bleibt der Treffer`() {
        val textNode = n(text = "Senden")        // zählt nicht mehr
        val descNode = n(desc = "Senden")         // P2-Treffer
        assertSame(descNode, SendNodeFinder.find(n(children = listOf(textNode, descNode)), whatsapp))
    }

    // ── Prioritäten ───────────────────────────────────────────────────────────
    @Test
    fun `viewId P1 schlaegt Description P2`() {
        val p2 = n(desc = "Senden")
        val p1 = n(viewId = "com.whatsapp:id/send")
        assertSame(p1, SendNodeFinder.find(n(children = listOf(p2, p1)), whatsapp))
    }

    // ── (R2-A) Abgeschnittener Baum → immer Fallback ──────────────────────────
    @Test
    fun `truncated erzwingt Fallback trotz eindeutigem Treffer`() {
        val tree = n(children = listOf(n(viewId = "com.whatsapp:id/send")))
        assertSame(tree.children[0], SendNodeFinder.find(tree, whatsapp, truncated = false))
        assertNull(SendNodeFinder.find(tree, whatsapp, truncated = true))
    }

    // ── Mehrdeutigkeit → Fallback (null) ──────────────────────────────────────
    @Test
    fun `zwei exakte Description-Treffer sind mehrdeutig und fuehren zum Fallback`() {
        val a = n(desc = "Senden")
        val b = n(desc = "Senden")
        assertNull(SendNodeFinder.find(n(children = listOf(a, b)), whatsapp))
    }

    @Test
    fun `zwei viewId-Treffer sind mehrdeutig (Fallback)`() {
        assertNull(SendNodeFinder.find(n(children = listOf(n(viewId = "com.whatsapp:id/send"), n(viewId = "com.whatsapp:id/send"))), whatsapp))
    }

    @Test
    fun `mehrdeutige hoehere Stufe steigt NICHT auf eindeutige niedrigere ab`() {
        // zwei viewId-Treffer (mehrdeutig P1) + ein eindeutiger Description-Treffer → trotzdem Fallback.
        val root = n(children = listOf(
            n(viewId = "com.whatsapp:id/send"),
            n(viewId = "com.whatsapp:id/send"),
            n(desc = "Senden"),
        ))
        assertNull(SendNodeFinder.find(root, whatsapp))
    }

    // ── usable: klickbar, sichtbar, aktiv, Fläche, fail-closed ────────────────
    @Test
    fun `nicht klickbare oder unsichtbare oder deaktivierte Treffer zaehlen nicht`() {
        assertNull(SendNodeFinder.find(n(children = listOf(n(viewId = "com.whatsapp:id/send", clickable = false))), whatsapp))
        assertNull(SendNodeFinder.find(n(children = listOf(n(viewId = "com.whatsapp:id/send", visible = false))), whatsapp))
        assertNull(SendNodeFinder.find(n(children = listOf(n(viewId = "com.whatsapp:id/send", enabled = false))), whatsapp))
    }

    @Test
    fun `zu grosser Node wird verworfen`() {
        assertNull(SendNodeFinder.find(n(children = listOf(n(viewId = "com.whatsapp:id/send", area = 0.9))), whatsapp))
    }

    @Test
    fun `unbekannte Flaeche (null) ist fail-closed (nicht usable)`() {
        assertNull(SendNodeFinder.find(n(children = listOf(n(viewId = "com.whatsapp:id/send", area = null))), whatsapp))
    }

    // ── FALLBACK ──────────────────────────────────────────────────────────────
    @Test
    fun `gibt null zurueck wenn kein Send-Node existiert`() {
        val root = n(children = listOf(
            n(viewId = "com.whatsapp:id/entry", text = "Nachricht eingeben"),
            n(desc = "Zurück"),
            n(desc = "Anhängen"),
        ))
        assertNull(SendNodeFinder.find(root, whatsapp))
    }

    // ── Spec-Registry / Paket-Sicherheit ──────────────────────────────────────
    @Test
    fun `specFor liefert nur fuer whatsapp und call einen Spec`() {
        assertTrue(SendNodeFinder.specFor("whatsapp") != null)
        assertTrue(SendNodeFinder.specFor("call") != null)
        assertNull(SendNodeFinder.specFor("sms"))
        assertNull(SendNodeFinder.specFor("com.whatsapp"))
        assertNull(SendNodeFinder.specFor(""))
    }

    @Test
    fun `call-Spec enthaelt KEINE WhatsApp-Pakete und kein telecom`() {
        val spec = SendNodeFinder.specFor("call")!!
        assertFalse("com.whatsapp" in spec.allowedPackages)
        assertFalse("com.android.server.telecom" in spec.allowedPackages)
        assertTrue("com.samsung.android.dialer" in spec.allowedPackages)
    }

    @Test
    fun `isAllowedPackage prueft Allowlist UND Blocklist`() {
        val wa = SendNodeFinder.specFor("whatsapp")!!
        assertTrue(SendNodeFinder.isAllowedPackage(wa, "com.whatsapp", emptySet()))
        assertFalse(SendNodeFinder.isAllowedPackage(wa, "com.paypal", emptySet()))
        assertFalse(SendNodeFinder.isAllowedPackage(wa, null, emptySet()))
        assertFalse(SendNodeFinder.isAllowedPackage(wa, "com.whatsapp", setOf("com.whatsapp")))
    }

    @Test
    fun `Flaechenlimit-Konstante ist gesetzt`() {
        assertTrue(SendNodeFinder.MAX_AREA_FRACTION in 0.1..0.6)
    }
}

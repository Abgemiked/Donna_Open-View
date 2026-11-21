package com.yourcompany.donna.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2 BLOCKER 4 — informierte Bestätigung im Overlay (native Karte).
 * Die Karte muss Nummer UND Nachrichtentext zeigen und eine Payload-Nummer markieren.
 */
class ActionCardTextTest {

    @Test
    fun `whatsapp mit Name markiert die Nummer TROTZDEM als aus Anfrage`() {
        // BLOCKER 1 (2. Runde): der Overlay-Pfad löst Nummern NIE aus dem Adressbuch auf
        // (ActionExecutor nimmt immer action.number) → JEDE Overlay-Nummer ist payload-stämmig.
        val label = ActionCardText.contact("💬 WhatsApp", "Ämi", "+4917012345", "Bin um 18 Uhr da")
        assertTrue(label.contains("An: Ämi"))
        assertTrue("Nummer muss als aus-Anfrage markiert sein", label.contains("Nummer (aus Anfrage, nicht aus Kontakten): +4917012345"))
        assertTrue(label.contains("Nachricht: Bin um 18 Uhr da"))
        assertFalse("keine unmarkierte Nummer-Zeile", label.contains("\nNummer: "))
    }

    @Test
    fun `ohne Name wird die Nummer ebenfalls als aus Anfrage markiert`() {
        val label = ActionCardText.contact("💬 WhatsApp", "", "017698765", "Hi")
        assertTrue(label.contains("Nummer (aus Anfrage, nicht aus Kontakten): 017698765"))
        assertTrue(label.contains("Nachricht: Hi"))
    }

    @Test
    fun `call markiert die Nummer, zeigt aber keine Nachrichtenzeile`() {
        val label = ActionCardText.contact("📞 Anrufen", "Chef", "+4930123", "")
        assertTrue(label.contains("Nummer (aus Anfrage, nicht aus Kontakten): +4930123"))
        assertFalse(label.contains("Nachricht:"))
    }
}

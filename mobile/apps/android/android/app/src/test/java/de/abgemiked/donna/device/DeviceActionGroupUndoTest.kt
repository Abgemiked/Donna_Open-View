package com.yourcompany.donna.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #186 G1: "Alles rückgängig" nimmt die Schritte einer Gruppe in UMGEKEHRTER Reihenfolge über die Einzel-Ablage zurück.
 * Die Reihenfolge entscheidet: spätere Änderung desselben Zustands macht eine ältere Taste ungültig (Superseded).
 */
class DeviceActionGroupUndoTest {
    private val all = DeviceActionKind.values().toSet()

    @Test
    fun `umgekehrte Reihenfolge geht, vorwaerts scheitert am spaeteren Schritt desselben Zustands`() {
        val store = UndoStore()
        // Gruppe: Taschenlampe an (vorher aus), Lautstärke, Taschenlampe wieder aus (vorher an)
        val a = store.record(UndoSpec.FlashlightTo(false), "flashlight")
        val b = store.record(UndoSpec.VolumeTo(VolumeChannel.MEDIA, 30), "volume:media")
        val c = store.record(UndoSpec.FlashlightTo(true), "flashlight")

        // vorwärts: der erste Schritt ist durch den dritten überholt
        assertEquals(UndoTake.Superseded, store.take(a, all))

        // rückwärts: jeder Schritt ist beim Drankommen zurücknehmbar
        for (id in listOf(c, b, a)) {
            val t = store.take(id, all)
            assertTrue("Schritt $id muss zurücknehmbar sein", t is UndoTake.Ready)
            store.commit(id)
        }
        // einmalig: ein zweites Mal geht nicht
        assertEquals(UndoTake.AlreadyDone, store.take(c, all))
    }

    @Test
    fun `Timer in der Gruppe oeffnet nur die Uhr-App und die Meldung sagt das ehrlich`() {
        val store = UndoStore()
        val id = store.record(UndoSpec.ClockTimers)
        val t = store.take(id, all) as UndoTake.Ready
        assertEquals(UndoSpec.ClockTimers, t.spec)
        val msg = DeviceActionMessages.undone(t.spec)
        assertTrue(msg.contains("Uhr-App"))
        assertTrue(msg.contains("löschen"))
    }

    @Test
    fun `aeltere Schritte einer langen Gruppe fallen aus der Ablage - ehrliche Meldung statt Erfolg`() {
        val store = UndoStore()
        val ids = (1..UndoStore.MAX_ENTRIES + 2).map { store.record(UndoSpec.VolumeTo(VolumeChannel.RING, it), "volume:ring:$it") }
        val old = store.take(ids.first(), all)
        assertEquals(UndoTake.None, old)
        assertEquals("Das lässt sich nicht mehr rückgängig machen.", DeviceActionMessages.undoRefused(old))
        assertTrue(store.take(ids.last(), all) is UndoTake.Ready)
    }

    @Test
    fun `abgeschalteter Schalter verhindert das Zuruecknehmen auch in der Gruppe`() {
        val store = UndoStore()
        val id = store.record(UndoSpec.FlashlightTo(false), "flashlight")
        assertEquals(UndoTake.Disabled, store.take(id, all - DeviceActionKind.FLASHLIGHT))
    }

    @Test
    fun `Fenster von zwei Minuten gilt je Schritt`() {
        var now = 0L
        val store = UndoStore { now }
        val id = store.record(UndoSpec.FlashlightTo(false), "flashlight")
        now = UndoStore.TTL_MS + 1
        assertEquals(UndoTake.Expired, store.take(id, all))
    }
}

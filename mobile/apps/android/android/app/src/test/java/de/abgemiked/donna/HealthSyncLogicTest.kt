package com.yourcompany.donna

import com.yourcompany.donna.HealthSyncLogic.Sleep
import com.yourcompany.donna.HealthSyncLogic.Snapshot
import com.yourcompany.donna.HealthSyncLogic.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Reine Logik des Health-Connect-Syncs (#182) — ohne Health-Connect-Klassen JVM-testbar. */
class HealthSyncLogicTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val now = Instant.parse("2026-11-23T08:00:00Z") // 09:00 Berlin

    private fun sleep(start: String, end: String, awakeSec: Long = 0, origin: String? = null) =
        Sleep(Instant.parse(start), Instant.parse(end), awakeSec, origin)

    // ── Hauptschlaf ──────────────────────────────────────────────────────────

    @Test fun pickMainSleep_keineSitzung_null() {
        assertNull(HealthSyncLogic.pickMainSleep(emptyList(), now))
    }

    @Test fun pickMainSleep_nimmtLaengsteSitzungImFenster_nichtNickerchen() {
        val nacht = sleep("2026-11-23T00:30:00Z", "2026-11-23T06:30:00Z") // 6 h
        val nap = sleep("2026-11-22T13:00:00Z", "2026-11-22T13:40:00Z")
        assertEquals(nacht, HealthSyncLogic.pickMainSleep(listOf(nap, nacht), now))
    }

    @Test fun pickMainSleep_neueKuerzereNachtLoestLaengereGestrigeAb() {
        val gestern = sleep("2026-11-22T00:00:00Z", "2026-11-22T08:00:00Z") // 8 h, Ende vor 24 h
        val heute = sleep("2026-11-23T01:00:00Z", "2026-11-23T06:00:00Z") // 5 h
        assertEquals(heute, HealthSyncLogic.pickMainSleep(listOf(gestern, heute), now))
    }

    @Test fun pickMainSleep_nurNickerchen_nimmtLaengstes() {
        val a = sleep("2026-11-23T05:00:00Z", "2026-11-23T05:30:00Z")
        val b = sleep("2026-11-22T13:00:00Z", "2026-11-22T14:00:00Z")
        assertEquals(b, HealthSyncLogic.pickMainSleep(listOf(a, b), now))
    }

    @Test fun sleepKey_aktualisierteSitzung_anderesEnde_neuerSchluessel() {
        val a = sleep("2026-11-22T23:30:00Z", "2026-11-23T06:30:00Z")
        val b = sleep("2026-11-22T23:30:00Z", "2026-11-23T07:00:00Z")
        assertFalse(HealthSyncLogic.sleepKey(a, zone) == HealthSyncLogic.sleepKey(b, zone))
    }

    @Test fun payload_sommerzeit_offsetPlus2_undJahreswechsel() {
        val s = sleep("2026-07-01T21:30:00Z", "2026-07-02T05:30:00Z")
        val p = HealthSyncLogic.buildPayload(Snapshot(s, 1, 1, null), zone, "2026-01-01")
        assertEquals("2025-12-31", p.getString("steps_yesterday_date"))
        val q = HealthSyncLogic.buildPayload(Snapshot(s, 1, 1, null), zone, "2026-07-02")
        assertEquals("2026-07-01T23:30:00+02:00", q.getString("sleep_start"))
    }

    @Test fun pickMainSleep_ignoriertSitzungenAusserhalbDesFensters() {
        val alt = sleep("2026-11-20T22:00:00Z", "2026-11-21T06:00:00Z")
        assertNull(HealthSyncLogic.pickMainSleep(listOf(alt), now))
    }

    @Test fun pickMainSleep_ignoriertNochLaufendeSitzung() {
        val laeuft = sleep("2026-11-23T05:00:00Z", "2026-11-23T09:00:00Z") // Ende in der Zukunft
        assertNull(HealthSyncLogic.pickMainSleep(listOf(laeuft), now))
    }

    // ── Stunden ──────────────────────────────────────────────────────────────

    @Test fun sleepHours_ziehtWachphasenAb_undRundetAufZweiStellen() {
        val s = sleep("2026-11-23T00:00:00Z", "2026-11-23T07:00:00Z", awakeSec = 1800)
        assertEquals(6.5, HealthSyncLogic.sleepHours(s)!!, 0.001)
    }

    @Test fun sleepHours_nullBeiNullOderNegativerDauer() {
        assertNull(HealthSyncLogic.sleepHours(null))
        assertNull(HealthSyncLogic.sleepHours(sleep("2026-11-23T00:00:00Z", "2026-11-23T01:00:00Z", awakeSec = 7200)))
    }

    // ── Schlüssel / Push-Entscheidung ────────────────────────────────────────

    @Test fun sleepKey_stabil_ausStartUndEnde() {
        val s = sleep("2026-11-22T23:30:00Z", "2026-11-23T06:30:00Z")
        assertEquals(
            Instant.parse("2026-11-22T23:30:00Z").epochSecond.toString() + "|" +
                Instant.parse("2026-11-23T06:30:00Z").epochSecond,
            HealthSyncLogic.sleepKey(s, zone),
        )
    }

    @Test fun shouldPush_ersterKontaktDesTages_true() {
        assertTrue(HealthSyncLogic.shouldPush(SyncState(null, null), null, "2026-11-23"))
    }

    @Test fun shouldPush_heuteSchonGesendetOhneNeuenSchlaf_false() {
        assertFalse(HealthSyncLogic.shouldPush(SyncState("2026-11-23", "k1"), "k1", "2026-11-23"))
        assertFalse(HealthSyncLogic.shouldPush(SyncState("2026-11-23", "k1"), null, "2026-11-23"))
    }

    @Test fun shouldPush_neueSchlafsitzungNachTagesPush_true_einmalProSitzung() {
        assertTrue(HealthSyncLogic.shouldPush(SyncState("2026-11-23", null), "k2", "2026-11-23"))
        assertFalse(HealthSyncLogic.shouldPush(SyncState("2026-11-23", "k2"), "k2", "2026-11-23"))
    }

    @Test fun shouldPush_nachtwechsel_neuerTag_true() {
        assertTrue(HealthSyncLogic.shouldPush(SyncState("2026-11-22", "k1"), "k1", "2026-11-23"))
    }

    // ── Payload ──────────────────────────────────────────────────────────────

    @Test fun payload_alleWerteFehlen_sindNullNichtNull0() {
        val p = HealthSyncLogic.buildPayload(Snapshot(null, null, null, null), zone, "2026-11-23")
        for (k in listOf("sleep_hours", "sleep_start", "sleep_end", "steps_today", "resting_hr", "steps_yesterday")) {
            assertTrue("$k muss vorhanden sein", p.has(k))
            assertTrue("$k muss null sein", p.isNull(k))
        }
        assertEquals("2026-11-23", p.getString("steps_date"))
        assertEquals("2026-11-22", p.getString("steps_yesterday_date"))
        assertEquals("day:2026-11-23", p.getString("sync_key"))
    }

    @Test fun payload_mitSchlaf_hatIsoMitZeitzoneUndStunden() {
        val s = sleep("2026-11-22T23:30:00Z", "2026-11-23T06:30:00Z")
        val p = HealthSyncLogic.buildPayload(Snapshot(s, 1200, 8000, 52), zone, "2026-11-23")
        assertEquals("2026-11-23T00:30:00+01:00", p.getString("sleep_start"))
        assertEquals("2026-11-23T07:30:00+01:00", p.getString("sleep_end"))
        assertEquals(7.0, p.getDouble("sleep_hours"), 0.001)
        assertEquals(1200, p.getInt("steps_today"))
        assertEquals(8000, p.getInt("steps_yesterday"))
        assertEquals(52, p.getInt("resting_hr"))
        assertEquals(HealthSyncLogic.sleepKey(s, zone), p.getString("sync_key"))
    }

    @Test fun payload_ruhepulsNullOderNullwert_wirdNull() {
        val p = HealthSyncLogic.buildPayload(Snapshot(null, 5, null, 0), zone, "2026-11-23")
        assertTrue(p.isNull("resting_hr"))
    }

    // ── Ehrliche Diagnose ────────────────────────────────────────────────────

    @Test fun diagnosis_keineSchlafdatenIn7Tagen_nenntSamsungHealthSync() {
        val msg = HealthSyncLogic.diagnosis(emptyList(), now, zone)
        assertNotNull(msg)
        assertTrue(msg!!.contains("Samsung Health"))
        assertTrue(msg.contains("Health Connect"))
    }

    @Test fun diagnosis_frischeSchlafdaten_keineMeldung() {
        val s = sleep("2026-11-22T23:00:00Z", "2026-11-23T06:00:00Z")
        assertNull(HealthSyncLogic.diagnosis(listOf(s), now, zone))
    }

    @Test fun diagnosis_veralteteSchlafdaten_nenntLetztesDatum() {
        val s = sleep("2026-11-17T23:00:00Z", "2026-11-18T06:00:00Z")
        val msg = HealthSyncLogic.diagnosis(listOf(s), now, zone)
        assertNotNull(msg)
        assertTrue(msg!!.contains("18.11."))
    }
}

package com.yourcompany.donna.calendaredit

import com.yourcompany.donna.device.DeviceActionReport
import com.yourcompany.donna.device.ResultKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnceLedgerTest {
    @Test fun `schon erledigter Tipp meldet ok - nie fehler`() {
        assertEquals(ResultKind.OK, OnceLedger.REPORT_FOR_ALREADY_DONE)
        assertEquals("ok", DeviceActionReport.wire(OnceLedger.REPORT_FOR_ALREADY_DONE).result)
    }

    @Test fun `Kennung wird gemerkt, leer und unbekannt sind nicht erledigt`() {
        assertFalse(OnceLedger.contains(null, "da_1"))
        assertFalse(OnceLedger.contains("", "da_1"))
        val s = OnceLedger.add(null, "da_1")
        assertTrue(OnceLedger.contains(s, "da_1"))
        assertFalse(OnceLedger.contains(s, "da_2"))
    }

    @Test fun `hoechstens 50, die aeltesten fallen zuerst heraus, keine Doppelten`() {
        var s: String? = null
        for (i in 1..60) s = OnceLedger.add(s, "id-$i")
        assertEquals(OnceLedger.MAX, OnceLedger.ids(s).size)
        assertFalse(OnceLedger.contains(s, "id-10"))
        assertTrue(OnceLedger.contains(s, "id-11"))
        assertTrue(OnceLedger.contains(s, "id-60"))
        val again = OnceLedger.add(s, "id-60")
        assertEquals(OnceLedger.ids(s), OnceLedger.ids(again))
    }
}

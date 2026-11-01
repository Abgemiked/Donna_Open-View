package com.yourcompany.donna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Batch 3: sauberes Stream-Ende und Live-Guard-Korrektur im SSE-Parser. */
class SseBatch3Test {

    @Test
    fun `type done ist das saubere Stream-Ende (wie DONE)`() {
        assertTrue(SseLineParser.parse("""data: {"type":"done"}""") is SseEvent.Done)
        assertTrue(SseLineParser.parse("""data:{"type":"done"}""") is SseEvent.Done)
        assertTrue(SseLineParser.parse("data: [DONE]") is SseEvent.Done)
    }

    @Test
    fun `live_guard_corrected ersetzt den Text`() {
        val e = SseLineParser.parse(
            """data: {"type":"live_guard_corrected","content":"Du bist live - ich verschweige das."}""",
        ) as SseEvent.Replace
        assertEquals("Du bist live - ich verschweige das.", e.text)
    }

    @Test
    fun `live_guard_corrected mit null-Inhalt ergibt leeren Ersatz statt Text null`() {
        val e = SseLineParser.parse("""data: {"type":"live_guard_corrected","content":null}""") as SseEvent.Replace
        assertEquals("", e.text)
    }

    @Test
    fun `live_guard Banner-Ereignis ohne Text wird ignoriert`() {
        assertNull(SseLineParser.parse("""data: {"type":"live_guard","active":true}"""))
    }
}

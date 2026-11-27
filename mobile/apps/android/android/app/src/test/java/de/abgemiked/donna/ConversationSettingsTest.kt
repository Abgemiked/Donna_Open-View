package com.yourcompany.donna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #122 Paket C: Einstellungen Sprechpause und Unterbrechen per Stimme — je EINE Quelle (native Pref), der Schluessel
 * steht nur hier, die Standardwerte bleiben wie vor Paket C.
 */
class ConversationSettingsTest {

    @Test
    fun `Sprechpause - Schluessel, Stufen und Standard unveraendert`() {
        assertEquals("silence_profile", SilenceProfile.PREF_KEY)
        assertEquals(listOf("short", "normal", "long"), SilenceProfile.values().map { it.key })
        assertEquals(SilenceProfile.LONG, SilenceProfile.DEFAULT)
        assertEquals(1200L, SilenceProfile.SHORT.completeMs)
        assertEquals(1800L, SilenceProfile.NORMAL.completeMs)
        assertEquals(2200L, SilenceProfile.LONG.completeMs)
    }

    @Test
    fun `Sprechpause - ungueltiger oder fehlender Wert ergibt den Standard, gueltiger bleibt`() {
        assertEquals("long", SilenceProfile.normalizeKey(null))
        assertEquals("long", SilenceProfile.normalizeKey(""))
        assertEquals("long", SilenceProfile.normalizeKey("extrem"))
        assertEquals("short", SilenceProfile.normalizeKey("short"))
        assertEquals("normal", SilenceProfile.normalizeKey("normal"))
    }

    @Test
    fun `Unterbrechen per Stimme - Schluessel und Standard AN unveraendert`() {
        assertEquals("voice_barge_in", VoiceBargeInSetting.PREF_KEY)
        assertTrue(VoiceBargeInSetting.DEFAULT)
    }
}

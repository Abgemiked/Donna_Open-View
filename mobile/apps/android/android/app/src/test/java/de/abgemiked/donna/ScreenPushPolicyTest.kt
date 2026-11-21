package com.yourcompany.donna

import org.junit.Assert.assertFalse
import org.junit.Test

/** #125 (Pause): der automatische Versand von Bildschirmtext ist standardmaessig aus. */
class ScreenPushPolicyTest {

    @Test
    fun `automatischer Versand ist standardmaessig aus`() {
        assertFalse(ScreenPushPolicy.AUTO_PUSH_ENABLED)
    }

    @Test
    fun `shouldAutoPush ist bei Standard immer false`() {
        assertFalse(ScreenPushPolicy.shouldAutoPush())
    }
}

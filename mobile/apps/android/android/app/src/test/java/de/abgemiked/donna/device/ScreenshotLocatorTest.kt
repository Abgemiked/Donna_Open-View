package com.yourcompany.donna.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #219: Auswahl des gerade aufgenommenen Screenshots und Schutz der URI (rein, ohne Android). */
class ScreenshotLocatorTest {
    private val t0 = 1_700_000_000_000L // Auslösezeit in ms
    private val sec = t0 / 1000

    private fun row(id: Long, added: Long, path: String? = "DCIM/Screenshots/", name: String? = "Screenshot_$id.jpg", pending: Boolean = false) =
        MediaRow(id, added, path, name, pending)

    @Test fun picksNewestScreenshotSinceTrigger() {
        val rows = listOf(row(1, sec + 1), row(2, sec + 3), row(3, sec + 2))
        assertEquals(2L, ScreenshotLocator.pick(rows, t0)?.id)
    }

    @Test fun ignoresOlderThanTriggerSecond() {
        assertNull(ScreenshotLocator.pick(listOf(row(1, sec - 5)), t0))
        assertNull(ScreenshotLocator.pick(listOf(row(1, sec - 1)), t0))
        assertEquals(1L, ScreenshotLocator.pick(listOf(row(1, sec)), t0)?.id)
        // Auslösung mitten in der Sekunde: gleiche (abgerundete) Sekunde zählt
        assertEquals(1L, ScreenshotLocator.pick(listOf(row(1, sec)), t0 + 999)?.id)
    }

    @Test fun sameSecondTakesHigherId() {
        assertEquals(9L, ScreenshotLocator.pick(listOf(row(9, sec + 1), row(4, sec + 1)), t0)?.id)
    }

    @Test fun ignoresPendingRows() {
        assertNull(ScreenshotLocator.pick(listOf(row(1, sec + 1, pending = true)), t0))
    }

    @Test fun ignoresNonScreenshotPhotos() {
        assertNull(ScreenshotLocator.pick(listOf(row(1, sec + 1, path = "DCIM/Camera/", name = "20241125_1.jpg")), t0))
    }

    @Test fun recognisesByNameWhenPathMissingAndByPathWhenNameMissing() {
        assertTrue(ScreenshotLocator.looksLikeScreenshot(row(1, sec, path = null, name = "Screenshot_20241125.png")))
        assertTrue(ScreenshotLocator.looksLikeScreenshot(row(1, sec, path = "Pictures/Screenshots/", name = null)))
        assertFalse(ScreenshotLocator.looksLikeScreenshot(row(1, sec, path = null, name = null)))
    }

    @Test fun emptyListGivesNull() = assertNull(ScreenshotLocator.pick(emptyList(), t0))

    @Test fun onlyMediaStoreImageUrisAreOpenable() {
        assertTrue(ScreenshotLocator.isMediaImageUri("content://media/external/images/media/1234"))
        assertTrue(ScreenshotLocator.isMediaImageUri("content://media/external_primary/images/media/9"))
        assertFalse(ScreenshotLocator.isMediaImageUri(null))
        assertFalse(ScreenshotLocator.isMediaImageUri("content://media/external/video/media/1"))
        assertFalse(ScreenshotLocator.isMediaImageUri("file:///sdcard/x.png"))
        assertFalse(ScreenshotLocator.isMediaImageUri("https://evil.example/a.png"))
        assertFalse(ScreenshotLocator.isMediaImageUri("content://media/external/images/media/12/../34"))
        assertFalse(ScreenshotLocator.isMediaImageUri("content://media/external/images/media/"))
        assertFalse(ScreenshotLocator.isMediaImageUri("content://media/external/images/media/12a"))
        assertFalse(ScreenshotLocator.isMediaImageUri("content://media/external/images/media/1234567890123456789"))
        assertFalse(ScreenshotLocator.isMediaImageUri("content://media/external/images/media/12?x=1"))
        assertFalse(ScreenshotLocator.isMediaImageUri("content://com.other.provider/images/media/1"))
    }
}

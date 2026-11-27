package com.yourcompany.donna.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraPickerTest {
    private val samsung = CameraCandidate("com.sec.android.app.camera", true)
    private val snap = CameraCandidate("com.snapchat.android", false)

    @Test fun `Samsung-Kamera statt Snapchat, ohne Auswahl`() {
        assertEquals("com.sec.android.app.camera", CameraPicker.choose(listOf(snap, samsung), null))
        assertEquals("com.sec.android.app.camera", CameraPicker.choose(listOf(samsung, snap), "android"))
    }

    @Test fun `Als Standard gesetzte Drittanbieter-App wird nie genommen`() {
        assertEquals("com.sec.android.app.camera", CameraPicker.choose(listOf(snap, samsung), "com.snapchat.android"))
    }

    @Test fun `System-Standard gewinnt`() {
        val other = CameraCandidate("com.google.android.GoogleCamera", true)
        assertEquals("com.google.android.GoogleCamera", CameraPicker.choose(listOf(samsung, other), "com.google.android.GoogleCamera"))
    }

    @Test fun `Keine System-Kamera - nichts waehlen`() {
        assertNull(CameraPicker.choose(listOf(snap), "com.snapchat.android"))
        assertNull(CameraPicker.choose(emptyList(), null))
    }

    @Test fun `Unbekannte System-Kameras - stabile Reihenfolge`() {
        val b = CameraCandidate("com.zzz.cam", true)
        val a = CameraCandidate("com.aaa.cam", true)
        assertEquals("com.aaa.cam", CameraPicker.choose(listOf(b, a), null))
    }
}

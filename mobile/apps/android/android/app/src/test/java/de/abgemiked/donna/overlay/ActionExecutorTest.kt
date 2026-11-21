package com.yourcompany.donna.overlay

import com.yourcompany.donna.PhoneNumbers
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Charakterisierungs-Tests (Golden Values) für die reine URI-Bau-Logik der Geräte-Intents
 * aus [ActionExecutor] (B1 Phase-C Overlay-Refactor).
 *
 * WARUM so getestet: `android.content.Intent`, `android.net.Uri.parse`/`Uri.encode` sind im
 * android.jar nur Stubs (nicht JVM-ausführbar, kein Robolectric im Projekt — siehe TeslaNavTest).
 * Deshalb kapselt [ActionIntents] die reine String-/Template-Logik und nimmt den Prozent-Encoder
 * als Parameter. Im Betrieb ist das `android.net.Uri::encode`; hier ein SENTINEL-Encoder `<…>`,
 * der sichtbar macht, WELCHE Teilstücke kodiert werden — so werden Schema, Pfad, Query-Parameter,
 * Reihenfolge UND die Telefonnummer-Normalisierung festgenagelt, ohne Android-Laufzeit.
 *
 * Die Golden Values stammen aus dem früheren inline `executeAction` in VoiceInputActivity
 * (navigate/call/sms/whatsapp/play_music). Verhaltensneutralität: `execute()` baut die echten
 * Intents aus exakt diesen Funktionen mit `Uri::encode` — identisch zum alten Code.
 */
class ActionExecutorTest {

    /** Sentinel: zeigt die kodierten Stellen, damit die Golden Values die Encode-Aufrufstellen beweisen. */
    private val enc: (String) -> String = { "<$it>" }

    // ── navigate (Handy, Google Maps) ─────────────────────────────────────────
    @Test
    fun `navigate baut google_navigation URI mit kodiertem Ziel und mode=d`() {
        assertEquals(
            "google.navigation:q=<Berlin Hbf>&mode=d",
            ActionIntents.navigateQuery("Berlin Hbf", enc),
        )
    }

    // ── call (ACTION_DIAL, tel:) ──────────────────────────────────────────────
    @Test
    fun `call baut tel URI mit kodierter Nummer`() {
        assertEquals("tel:<+49 170 1234>", ActionIntents.telUri("+49 170 1234", enc))
    }

    // ── sms (ACTION_SENDTO, smsto:) ───────────────────────────────────────────
    @Test
    fun `sms baut smsto URI mit kodierter Nummer`() {
        assertEquals("smsto:<0170 1234>", ActionIntents.smsUri("0170 1234", enc))
    }

    // ── whatsapp (api.whatsapp.com, phone nur Ziffern + text) ─────────────────
    @Test
    fun `whatsapp baut send URL mit nur-Ziffern-Nummer und kodiertem Text`() {
        // forWhatsApp filtert auf Ziffern: "+49 170 / 12-34" -> "491701234"
        assertEquals(
            "https://api.whatsapp.com/send?phone=<491701234>&text=<Hallo Welt>",
            ActionIntents.whatsAppUrl("+49 170 / 12-34", "Hallo Welt", enc),
        )
    }

    @Test
    fun `whatsapp Nummer-Normalisierung entspricht PhoneNumbers forWhatsApp`() {
        assertEquals("491701234", PhoneNumbers.forWhatsApp("+49 170 / 12-34"))
    }

    // ── play_music (Spotify / YouTube Music) ──────────────────────────────────
    @Test
    fun `play_music spotify baut search URI mit kodierter Query`() {
        assertEquals("spotify:search:<the beatles>", ActionIntents.spotifySearch("the beatles", enc))
    }

    @Test
    fun `play_music youtube music baut search URL mit kodierter Query`() {
        assertEquals(
            "https://music.youtube.com/search?q=<the beatles>",
            ActionIntents.youtubeMusicSearch("the beatles", enc),
        )
    }

    // ── Identitäts-Encoder: reine Template-Form (ohne Kodierungs-Rauschen) ─────
    @Test
    fun `Templates sind ohne Encoder-Rauschen stabil`() {
        val id: (String) -> String = { it }
        assertEquals("google.navigation:q=Marienplatz&mode=d", ActionIntents.navigateQuery("Marienplatz", id))
        assertEquals("tel:0891234", ActionIntents.telUri("0891234", id))
        assertEquals("smsto:0891234", ActionIntents.smsUri("0891234", id))
        assertEquals(
            "https://api.whatsapp.com/send?phone=491234&text=Hi",
            ActionIntents.whatsAppUrl("+49 1234", "Hi", id),
        )
    }
}

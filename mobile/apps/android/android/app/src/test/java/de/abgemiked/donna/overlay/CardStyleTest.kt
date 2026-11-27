package com.yourcompany.donna.overlay

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #151: Stil-Beschreibung der Karten (Rundung, Abstaende, Schrift, Farbrollen je Farbwelt, Tastenrollen) als Daten. */
class CardStyleTest {
    private fun res(rel: String): File {
        val a = File("src/main/res/$rel")
        return if (a.exists()) a else File("app/src/main/res/$rel")
    }

    private fun colors(dir: String): Map<String, String> {
        val out = HashMap<String, String>()
        val files = res(dir).listFiles { f -> f.name.endsWith(".xml") } ?: error("keine Ressourcen in $dir")
        val rx = Regex("""<color name="([a-z0-9_]+)">(#[0-9a-fA-F]{6,8})</color>""")
        for (f in files) for (m in rx.findAll(f.readText())) out[m.groupValues[1]] = m.groupValues[2]
        return out
    }

    private fun dimens(): Map<String, Int> =
        Regex("""<dimen name="([a-z_]+)">(\d+)(?:dp|sp)</dimen>""").findAll(res("values/dimens.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }

    private fun lum(hex: String): Double {
        val h = hex.removePrefix("#").takeLast(6)
        fun ch(i: Int): Double {
            val v = h.substring(i, i + 2).toInt(16) / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(0) + 0.7152 * ch(2) + 0.0722 * ch(4)
    }

    private fun contrast(a: String, b: String): Double {
        val l1 = lum(a)
        val l2 = lum(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    @Test
    fun `Radien und Abstaende stimmen mit dimens xml ueberein`() {
        val d = dimens()
        for (r in CardStyle.Radius.values()) assertEquals(r.name, d[r.dimenName], r.dp)
        assertEquals(d["donna_radius_card"], CardStyle.CARD_RADIUS.dp)
        assertEquals(d["donna_radius_pill"], CardStyle.BUTTON_RADIUS.dp)
        assertEquals(d["donna_space_gutter"], CardStyle.PAD_H_DP)
        assertEquals(d["donna_space_gutter"], CardStyle.PAD_TOP_DP)
        assertEquals(d["donna_space_lg"], CardStyle.PAD_BOTTOM_DP)
        assertEquals(d["donna_space_sm"], CardStyle.CARD_GAP_DP)
        assertEquals(d["donna_space_sm"], CardStyle.BUTTON_GAP_DP)
        assertEquals(d["donna_space_lg"], CardStyle.TEXT_TO_BUTTONS_DP)
        assertEquals(d["donna_touch_min"], CardStyle.TOUCH_MIN_DP)
    }

    @Test
    fun `Tippflaeche mindestens 48 dp und Taste in der Reihe mit Gewicht`() {
        assertTrue(CardStyle.buttonMinHeightDp() >= 48)
        assertTrue(CardStyle.BUTTON_MIN_WIDTH_DP >= 48)
        assertTrue(CardStyle.BUTTON_ROW_WEIGHT > 0f)
    }

    @Test
    fun `Schriftgroessen und Schnitte aus den Tokens`() {
        val d = dimens()
        val allowedSp = setOf(d["donna_text_caption"], d["donna_text_label"], d["donna_text_body"], d["donna_text_title"], 56)
        val faces = setOf("bricolage_grotesque_bold", "bricolage_grotesque_extrabold", "nunito_sans_regular", "nunito_sans_semibold", "nunito_sans_bold")
        for (t in CardStyle.Type.values()) {
            assertTrue(t.name, t.sp in allowedSp)
            assertTrue(t.name, t.face in faces)
            assertTrue(t.name, t.sp >= 13)
        }
        assertTrue(CardStyle.Type.TITLE.sp >= CardStyle.Type.CAPTION.sp)
    }

    @Test
    fun `jede Farbrolle gibt es in beiden Farbwelten`() {
        val light = colors("values")
        val dark = colors("values-night")
        for (r in CardStyle.Role.values()) {
            assertNotNull("hell ${r.resName}", light[r.resName])
            assertNotNull("dunkel ${r.resName}", dark[r.resName])
        }
    }

    @Test
    fun `Schrift auf Grund ist in beiden Farbwelten lesbar`() {
        for ((world, c) in listOf("hell" to colors("values"), "dunkel" to colors("values-night"))) {
            for ((fg, bg) in CardStyle.READABLE_PAIRS) {
                val ratio = contrast(c[fg.resName]!!, c[bg.resName]!!)
                assertTrue("$world ${fg.name} auf ${bg.name}: $ratio", ratio >= CardStyle.MIN_CONTRAST)
            }
            val edge = contrast(c[CardStyle.Role.SECONDARY_BORDER.resName]!!, c[CardStyle.Role.FILL.resName]!!)
            assertTrue("$world Umriss: $edge", edge >= CardStyle.MIN_CONTROL_CONTRAST)
        }
    }

    @Test
    fun `Hauptaktion ist die Mint-Flaeche der Gestaltung und gefuellt, die zweite Aktion nur Umriss`() {
        val p = CardStyle.buttonRole(true)
        val s = CardStyle.buttonRole(false)
        assertEquals("donna_action", p.fill!!.resName)
        assertEquals("donna_on_action", p.text.resName)
        assertNull(p.border)
        assertNull(s.fill)
        assertNotNull(s.border)
        assertEquals(colors("values")["donna_action"], colors("values-night")["donna_action"])
    }

    @Test
    fun `Kartenflaeche hebt sich vom Panel-Grund ab`() {
        for (c in listOf(colors("values"), colors("values-night"))) {
            assertTrue(c[CardStyle.Role.FILL.resName] != c["donna_overlay_panel"])
        }
    }
}

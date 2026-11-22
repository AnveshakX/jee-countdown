package com.jeecountdown

import org.junit.Assert.assertEquals
import org.junit.Test

class CfgCodecTest {
    private val custom = Cfg(
        theme = 3, bgMode = 2, solid = 0xFF1F3B5A.toInt(), dim = 60, blur = 45, accent = 0xFFFF9EC4.toInt(),
        urgency = false, font = 3, ring = false, card = false, pill = false, timeLeft = false, seconds = false,
        unit = 2, label = "DAYS LEFT", goal = "IIT BOMBAY CSE", effect = 2, onboarded = true, photoVersion = 1_234L
    )

    @Test fun emptyStorageGivesSensibleDefaults() {
        assertEquals(Cfg(), CfgCodec.decode(emptyMap()))
    }

    @Test fun everySettingPersistsAcrossRestart() {
        val store = MemoryStore()
        store.write(CfgCodec.encode(custom))
        val restored = CfgCodec.decode(MemoryStore(store.all()).all()) // "restart": decode from persisted map
        assertEquals(custom, restored)
        // spot-check the user-visible ones called out in the P0 brief
        assertEquals(3, restored.theme)
        assertEquals(0xFFFF9EC4.toInt(), restored.accent)
        assertEquals(3, restored.font)
        assertEquals("DAYS LEFT", restored.label)
        assertEquals("IIT BOMBAY CSE", restored.goal)
        assertEquals(1_234L, restored.photoVersion)
    }

    @Test fun outOfRangeValuesAreClamped() {
        val bad = mapOf<String, Any?>(
            "theme" to 99, "bgMode" to -4, "dim" to 500, "blur" to -1, "font" to 42,
            "unit" to 7, "effect" to 9, "photoVersion" to -5L
        )
        val c = CfgCodec.decode(bad)
        assertEquals(Themes.all.size - 1, c.theme)
        assertEquals(0, c.bgMode)
        assertEquals(100, c.dim)
        assertEquals(0, c.blur)
        assertEquals(Fonts.families.size - 1, c.font)
        assertEquals(2, c.unit)
        assertEquals(2, c.effect)
        assertEquals(0L, c.photoVersion)
    }

    @Test fun wrongTypesFallBackToDefaults() {
        val bad = mapOf<String, Any?>("theme" to "forest", "ring" to "yes", "label" to 12, "dim" to true)
        assertEquals(Cfg(), CfgCodec.decode(bad))
    }

    @Test fun colourAlphaIsForcedOpaqueAndZeroAccentMeansAuto() {
        val c = CfgCodec.sanitize(Cfg(solid = 0x0014211A, accent = 0x00A9C98A))
        assertEquals(0xFF14211A.toInt(), c.solid)
        assertEquals(0xFFA9C98A.toInt(), c.accent)
        assertEquals(0, CfgCodec.sanitize(Cfg(accent = 0)).accent)
    }

    @Test fun textIsTrimmedStrippedAndCapped() {
        val c = CfgCodec.sanitize(Cfg(label = "  A\u0000B\n" + "x".repeat(100), goal = "\t"))
        assertEquals(CfgCodec.MAX_TEXT, c.label.length)
        assertEquals("AB" + "x".repeat(CfgCodec.MAX_TEXT - 2), c.label)
        assertEquals("", c.goal)
    }

    @Test fun unknownKeysAreIgnoredSoDowngradesAndFutureKeysAreSafe() {
        val m = HashMap(CfgCodec.encode(custom))
        m["someFutureSetting"] = 123
        assertEquals(custom, CfgCodec.decode(m))
    }

    @Test fun sanitizeIsIdempotentAndMatchesDecodeEncode() {
        val messy = custom.copy(dim = 999, label = "  hi  ")
        assertEquals(CfgCodec.sanitize(messy), CfgCodec.sanitize(CfgCodec.sanitize(messy)))
        assertEquals(CfgCodec.sanitize(messy), CfgCodec.decode(CfgCodec.encode(messy)))
    }

    @Test fun themeAndFontCatalogsAreIntact() {
        assertEquals(7, Themes.all.size)
        assertEquals(listOf("Forest", "Midnight", "Sunset", "Ocean", "Rose", "Paper", "AMOLED"), Themes.all.map { it.name })
        assertEquals(5, Fonts.names.size)
        assertEquals(Fonts.names.size, Fonts.families.size)
        Themes.all.forEach { assertEquals(3, it.bg.size) }
    }
}

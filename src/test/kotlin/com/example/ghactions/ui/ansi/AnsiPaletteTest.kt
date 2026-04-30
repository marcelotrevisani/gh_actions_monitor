package com.example.ghactions.ui.ansi

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AnsiPaletteTest {

    @Test
    fun `every color maps to a distinct AWT colour`() {
        val mapped = AnsiColor.values().map { AnsiPalette.awtColor(it) }
        assertEquals(mapped.size, mapped.toSet().size, "duplicate colours in palette")
    }

    @Test
    fun `red is reddish and green is greenish`() {
        val red = AnsiPalette.awtColor(AnsiColor.RED)
        val green = AnsiPalette.awtColor(AnsiColor.GREEN)
        // R > G,B for red; G > R,B for green. Keeps the palette honest.
        assertEquals(true, red.red > red.green && red.red > red.blue, "RED dominant channel")
        assertEquals(true, green.green > green.red && green.green > green.blue, "GREEN dominant channel")
    }

    @Test
    fun `bright variants differ from their non-bright siblings`() {
        // Pick three to spot-check; the duplicate-set test above covers the rest.
        assertNotEquals(AnsiPalette.awtColor(AnsiColor.RED), AnsiPalette.awtColor(AnsiColor.BRIGHT_RED))
        assertNotEquals(AnsiPalette.awtColor(AnsiColor.GREEN), AnsiPalette.awtColor(AnsiColor.BRIGHT_GREEN))
        assertNotEquals(AnsiPalette.awtColor(AnsiColor.WHITE), AnsiPalette.awtColor(AnsiColor.BRIGHT_WHITE))
    }
}

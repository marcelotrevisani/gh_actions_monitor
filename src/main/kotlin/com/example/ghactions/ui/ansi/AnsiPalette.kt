package com.example.ghactions.ui.ansi

import java.awt.Color

/**
 * Maps [AnsiColor] to [java.awt.Color]. The palette is hand-picked to remain legible on
 * both the IntelliJ Light and Darcula backgrounds — i.e. avoid pure black/white and the
 * fully saturated primaries that disappear into bright themes.
 */
object AnsiPalette {
    fun awtColor(color: AnsiColor): Color = when (color) {
        AnsiColor.BLACK         -> Color(0x3B, 0x42, 0x52)
        AnsiColor.RED           -> Color(0xBF, 0x61, 0x6A)
        AnsiColor.GREEN         -> Color(0xA3, 0xBE, 0x8C)
        AnsiColor.YELLOW        -> Color(0xEB, 0xCB, 0x8B)
        AnsiColor.BLUE          -> Color(0x81, 0xA1, 0xC1)
        AnsiColor.MAGENTA       -> Color(0xB4, 0x8E, 0xAD)
        AnsiColor.CYAN          -> Color(0x88, 0xC0, 0xD0)
        AnsiColor.WHITE         -> Color(0xE5, 0xE9, 0xF0)
        AnsiColor.BRIGHT_BLACK  -> Color(0x4C, 0x56, 0x6A)
        AnsiColor.BRIGHT_RED    -> Color(0xD0, 0x87, 0x70)
        AnsiColor.BRIGHT_GREEN  -> Color(0xB5, 0xCE, 0xA8)
        AnsiColor.BRIGHT_YELLOW -> Color(0xF0, 0xD8, 0xA8)
        AnsiColor.BRIGHT_BLUE   -> Color(0x8F, 0xBC, 0xBB)
        AnsiColor.BRIGHT_MAGENTA -> Color(0xC5, 0x95, 0xC5)
        AnsiColor.BRIGHT_CYAN   -> Color(0x8F, 0xD8, 0xE0)
        AnsiColor.BRIGHT_WHITE  -> Color(0xEC, 0xEF, 0xF4)
    }
}

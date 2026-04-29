package com.example.ghactions.ui.ansi

/**
 * Standard 16-color ANSI palette. Codes 30-37 map to the first 8; 90-97 to the bright 8.
 * The actual RGB values used by the renderer live in [AnsiPalette] so the model stays
 * decoupled from Swing.
 */
enum class AnsiColor {
    BLACK, RED, GREEN, YELLOW, BLUE, MAGENTA, CYAN, WHITE,
    BRIGHT_BLACK, BRIGHT_RED, BRIGHT_GREEN, BRIGHT_YELLOW,
    BRIGHT_BLUE, BRIGHT_MAGENTA, BRIGHT_CYAN, BRIGHT_WHITE
}

/**
 * Display attributes applied to a [AnsiSpan]. `null` foreground means "use the text pane's
 * default" (i.e. inherit the IDE's editor foreground colour) — important for both themes
 * since we never want to hard-code black or white as the "default".
 */
data class AnsiStyle(
    val foreground: AnsiColor? = null,
    val bold: Boolean = false
) {
    companion object {
        val DEFAULT = AnsiStyle()
    }
}

/** A run of characters that share an [AnsiStyle]. Output of [AnsiParser.parse]. */
data class AnsiSpan(
    val text: String,
    val style: AnsiStyle
)

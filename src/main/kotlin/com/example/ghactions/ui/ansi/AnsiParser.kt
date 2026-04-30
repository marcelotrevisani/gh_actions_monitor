package com.example.ghactions.ui.ansi

/**
 * Parses CSI SGR escape sequences (`ESC [ codes m`) into a list of styled [AnsiSpan]s.
 *
 * **Recognised codes**
 * - `0`             — reset to default
 * - `1`             — bold
 * - `30..37`        — standard foreground (BLACK..WHITE)
 * - `90..97`        — bright foreground (BRIGHT_BLACK..BRIGHT_WHITE)
 *
 * **Behaviour for everything else**
 * - 256-color (`38;5;n`), truecolor (`38;2;r;g;b`), background colors (`40..47`,
 *   `100..107`), italic, underline, etc. — these *codes* are silently consumed; the
 *   surrounding escape characters are stripped. Unrecognised CSI sequences (e.g. cursor
 *   moves) are dropped entirely.
 * - Lone `ESC` characters not part of a CSI sequence are dropped.
 */
object AnsiParser {

    private const val ESC = ''

    fun parse(text: String): List<AnsiSpan> {
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<AnsiSpan>()
        val buf = StringBuilder()
        var style = AnsiStyle.DEFAULT
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == ESC && i + 1 < text.length && text[i + 1] == '[') {
                // Flush the buffer into a span before changing style.
                if (buf.isNotEmpty()) {
                    out += AnsiSpan(buf.toString(), style)
                    buf.setLength(0)
                }
                // Find the terminator. CSI sequences end at the first byte in 0x40..0x7E.
                val termIdx = findCsiTerminator(text, i + 2)
                if (termIdx < 0) {
                    // Malformed — drop the rest of the escape but keep emitting text.
                    i = text.length
                    continue
                }
                val params = text.substring(i + 2, termIdx)
                val terminator = text[termIdx]
                if (terminator == 'm') {
                    style = applySgr(style, params)
                }
                // Non-`m` terminators (e.g. cursor moves) are silently dropped.
                i = termIdx + 1
            } else {
                buf.append(c)
                i++
            }
        }
        if (buf.isNotEmpty()) out += AnsiSpan(buf.toString(), style)
        return out
    }

    private fun findCsiTerminator(text: String, fromIndex: Int): Int {
        for (j in fromIndex until text.length) {
            val b = text[j].code
            if (b in 0x40..0x7E) return j
        }
        return -1
    }

    private fun applySgr(current: AnsiStyle, params: String): AnsiStyle {
        if (params.isEmpty()) return AnsiStyle.DEFAULT
        val codes = params.split(';').mapNotNull { it.toIntOrNull() }
        if (codes.isEmpty()) return current
        var style = current
        var i = 0
        while (i < codes.size) {
            val code = codes[i]
            when {
                code == 0 -> style = AnsiStyle.DEFAULT
                code == 1 -> style = style.copy(bold = true)
                code in 30..37 -> style = style.copy(foreground = standardColor(code - 30))
                code in 90..97 -> style = style.copy(foreground = brightColor(code - 90))
                code == 38 -> {
                    // Extended color: 38;5;n (256) or 38;2;r;g;b (truecolor). Skip the
                    // payload bytes so we don't re-parse them as separate codes.
                    i += skipExtendedPalette(codes, i + 1)
                }
                code == 48 -> {
                    // Same shape as 38, for background. Skip payload, no rendering.
                    i += skipExtendedPalette(codes, i + 1)
                }
                else -> { /* swallow unknown codes silently */ }
            }
            i++
        }
        return style
    }

    private fun skipExtendedPalette(codes: List<Int>, fromIndex: Int): Int {
        // Returns how many *additional* indices to advance past the introducer (38 or 48).
        val mode = codes.getOrNull(fromIndex) ?: return 0
        return when (mode) {
            5 -> 2          // 5; n
            2 -> 4          // 2; r; g; b
            else -> 0
        }
    }

    private fun standardColor(idx: Int): AnsiColor = AnsiColor.values()[idx]
    private fun brightColor(idx: Int): AnsiColor = AnsiColor.values()[idx + 8]
}

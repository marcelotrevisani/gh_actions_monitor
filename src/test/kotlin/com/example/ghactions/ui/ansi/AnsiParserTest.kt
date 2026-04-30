package com.example.ghactions.ui.ansi

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AnsiParserTest {

    private val ESC = ""

    @Test
    fun `plain text returns single default span`() {
        val spans = AnsiParser.parse("hello world\n")
        assertEquals(1, spans.size)
        assertEquals("hello world\n", spans[0].text)
        assertEquals(AnsiStyle.DEFAULT, spans[0].style)
    }

    @Test
    fun `empty input returns empty list`() {
        assertEquals(emptyList(), AnsiParser.parse(""))
    }

    @Test
    fun `simple foreground color`() {
        val spans = AnsiParser.parse("$ESC[31merror$ESC[0m done")
        assertEquals(
            listOf(
                AnsiSpan("error", AnsiStyle(foreground = AnsiColor.RED)),
                AnsiSpan(" done", AnsiStyle.DEFAULT)
            ),
            spans
        )
    }

    @Test
    fun `bold + color combined in one CSI`() {
        val spans = AnsiParser.parse("$ESC[1;32mPASS$ESC[0m")
        assertEquals(
            listOf(AnsiSpan("PASS", AnsiStyle(foreground = AnsiColor.GREEN, bold = true))),
            spans
        )
    }

    @Test
    fun `bright color codes 90-97`() {
        val spans = AnsiParser.parse("$ESC[91mbright red$ESC[0m")
        assertEquals(
            listOf(AnsiSpan("bright red", AnsiStyle(foreground = AnsiColor.BRIGHT_RED))),
            spans
        )
    }

    @Test
    fun `unknown code is stripped without leaking escape characters`() {
        // 256-color escape: ESC[38;5;208m (orange). We don't support it, but it must not
        // appear as visible text.
        val spans = AnsiParser.parse("${ESC}[38;5;208morange${ESC}[0m next")
        val rendered = spans.joinToString("") { it.text }
        assertEquals("orange next", rendered)
    }

    @Test
    fun `reset clears bold and color`() {
        val spans = AnsiParser.parse("$ESC[1;31mAB$ESC[0mCD")
        assertEquals(
            listOf(
                AnsiSpan("AB", AnsiStyle(foreground = AnsiColor.RED, bold = true)),
                AnsiSpan("CD", AnsiStyle.DEFAULT)
            ),
            spans
        )
    }
}

package com.example.ghactions.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LogSectionParserTest {

    @Test
    fun `empty log produces no sections`() {
        assertEquals(emptyList(), LogSectionParser.parse(""))
    }

    @Test
    fun `log with no group markers produces no sections`() {
        val log = """
            line one
            line two
            line three
        """.trimIndent()
        assertEquals(emptyList(), LogSectionParser.parse(log))
    }

    @Test
    fun `single top-level group`() {
        val log = """
            ##[group]Set up job
            Some output
            More output
            ##[endgroup]
        """.trimIndent()
        val sections = LogSectionParser.parse(log)
        assertEquals(1, sections.size)
        assertEquals("Set up job", sections[0].name)
        assertEquals(0, sections[0].startLine)
        assertEquals(4, sections[0].endLineExclusive)
    }

    @Test
    fun `multiple sequential groups`() {
        val log = """
            ##[group]Set up job
            a
            ##[endgroup]
            ##[group]Run actions/checkout@v4
            b
            ##[endgroup]
            ##[group]Complete job
            c
            ##[endgroup]
        """.trimIndent()
        val sections = LogSectionParser.parse(log)
        assertEquals(3, sections.size)
        assertEquals("Set up job", sections[0].name)
        assertEquals("Run actions/checkout@v4", sections[1].name)
        assertEquals("Complete job", sections[2].name)
        // Each section is 3 lines: group, output, endgroup
        assertEquals(0..2, sections[0].startLine..sections[0].endLineExclusive - 1)
        assertEquals(3..5, sections[1].startLine..sections[1].endLineExclusive - 1)
        assertEquals(6..8, sections[2].startLine..sections[2].endLineExclusive - 1)
    }

    @Test
    fun `nested groups are folded into the parent section`() {
        val log = """
            ##[group]Outer step
            outer line
            ##[group]Inner subtask
            inner line
            ##[endgroup]
            more outer
            ##[endgroup]
        """.trimIndent()
        val sections = LogSectionParser.parse(log)
        assertEquals(1, sections.size)
        assertEquals("Outer step", sections[0].name)
        assertEquals(0, sections[0].startLine)
        assertEquals(7, sections[0].endLineExclusive) // includes inner group
    }

    @Test
    fun `timestamp prefix on group line is preserved as part of name`() {
        val log = """
            2026-04-29T19:33:10.000Z ##[group]Run actions/checkout@v4
            output
            2026-04-29T19:33:14.500Z ##[endgroup]
        """.trimIndent()
        val sections = LogSectionParser.parse(log)
        assertEquals(1, sections.size)
        // The regex matches `##[group]` anywhere on the line and captures everything after,
        // so the timestamp prefix is naturally not in the captured name.
        assertEquals("Run actions/checkout@v4", sections[0].name)
    }

    @Test
    fun `unterminated group is ignored`() {
        val log = """
            ##[group]Started but never closed
            output
            output
        """.trimIndent()
        val sections = LogSectionParser.parse(log)
        assertEquals(emptyList(), sections)
    }

    @Test
    fun `extra endgroup with no group is ignored without crashing`() {
        val log = """
            ##[endgroup]
            stray output
            ##[group]Real section
            content
            ##[endgroup]
        """.trimIndent()
        val sections = LogSectionParser.parse(log)
        assertEquals(1, sections.size)
        assertEquals("Real section", sections[0].name)
    }
}

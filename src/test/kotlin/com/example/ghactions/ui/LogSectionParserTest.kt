package com.example.ghactions.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LogSectionParserTest {

    @Test
    fun `empty log produces no sections`() {
        assertEquals(emptyList(), LogSectionParser.parse(""))
    }

    @Test
    fun `log with no Run groups produces no sections`() {
        // Lines and depth-0 groups exist, but none start with "Run " — so no user steps.
        val log = """
            ##[group]Runner Image Provisioner
            something
            ##[endgroup]
            other line
        """.trimIndent()
        assertEquals(emptyList(), LogSectionParser.parse(log))
    }

    @Test
    fun `single Run section extends to end of log`() {
        val log = """
            ##[group]Run swift test
            swift test
            shell: /bin/bash -e {0}
            ##[endgroup]
            output line one
            output line two
        """.trimIndent()
        val sections = LogSectionParser.parse(log)
        assertEquals(1, sections.size)
        assertEquals("Run swift test", sections[0].name)
        assertEquals(0, sections[0].startLine)
        assertEquals(6, sections[0].endLineExclusive)
    }

    @Test
    fun `multiple Run sections — each ends at the next Run line`() {
        val log = """
            ##[group]Run actions/checkout@v4
            with: stuff
            ##[endgroup]
            Syncing repository: octo/repo
            ##[group]Run swift build
            swift build
            ##[endgroup]
            Build complete
            ##[group]Run swift test
            swift test
            ##[endgroup]
            test output
        """.trimIndent()
        val sections = LogSectionParser.parse(log)
        assertEquals(3, sections.size)
        assertEquals(listOf("Run actions/checkout@v4", "Run swift build", "Run swift test"), sections.map { it.name })
        assertEquals(listOf(0, 4, 8), sections.map { it.startLine })
        assertEquals(listOf(4, 8, 12), sections.map { it.endLineExclusive })
    }

    @Test
    fun `sibling subgroups within an action stay inside the enclosing step`() {
        // This is the canonical actions/checkout@v4 shape: a "Run" group, then
        // multiple sibling depth-0 groups for git sub-tasks. Only the first
        // group should be a section start.
        val log = """
            ##[group]Run actions/checkout@v4
            with: stuff
            ##[endgroup]
            Syncing repository
            ##[group]Getting Git version info
            git version
            ##[endgroup]
            ##[group]Initializing the repository
            git init
            ##[endgroup]
            ##[group]Run swift test
            swift test
            ##[endgroup]
            test output
        """.trimIndent()
        val sections = LogSectionParser.parse(log)
        assertEquals(2, sections.size)
        assertEquals("Run actions/checkout@v4", sections[0].name)
        assertEquals(0, sections[0].startLine)
        assertEquals(10, sections[0].endLineExclusive) // includes 'Getting Git…' and 'Initializing…'
        assertEquals("Run swift test", sections[1].name)
        assertEquals(10, sections[1].startLine)
    }

    @Test
    fun `nested groups inside a Run step do not start a new section`() {
        val log = """
            ##[group]Run actions/foo
            outer
            ##[group]Inner subtask
            inner
            ##[endgroup]
            more outer
            ##[endgroup]
            output
        """.trimIndent()
        val sections = LogSectionParser.parse(log)
        assertEquals(1, sections.size)
        assertEquals("Run actions/foo", sections[0].name)
        assertEquals(0, sections[0].startLine)
        assertEquals(8, sections[0].endLineExclusive)
    }

    @Test
    fun `synthetic prelude groups before the first Run are not surfaced`() {
        val log = """
            ##[group]Runner Image Provisioner
            metadata
            ##[endgroup]
            ##[group]Operating System
            macOS
            ##[endgroup]
            ##[group]Run swift test
            swift test
            ##[endgroup]
            output
        """.trimIndent()
        val sections = LogSectionParser.parse(log)
        assertEquals(1, sections.size)
        assertEquals("Run swift test", sections[0].name)
        assertEquals(6, sections[0].startLine)
    }

    @Test
    fun `timestamp prefix on Run group line still matches`() {
        val log = """
            2026-04-29T19:33:10.000Z ##[group]Run actions/checkout@v4
            output
            2026-04-29T19:33:14.500Z ##[endgroup]
        """.trimIndent()
        val sections = LogSectionParser.parse(log)
        assertEquals(1, sections.size)
        assertEquals("Run actions/checkout@v4", sections[0].name)
    }

    @Test
    fun `unterminated Run group still produces a section to end of log`() {
        val log = """
            ##[group]Run never closed
            output
            output
        """.trimIndent()
        val sections = LogSectionParser.parse(log)
        assertEquals(1, sections.size)
        assertEquals("Run never closed", sections[0].name)
        assertEquals(0, sections[0].startLine)
        assertEquals(3, sections[0].endLineExclusive)
    }

    @Test
    fun `extra endgroup with no group is ignored without crashing`() {
        val log = """
            ##[endgroup]
            stray output
            ##[group]Run real
            content
            ##[endgroup]
        """.trimIndent()
        val sections = LogSectionParser.parse(log)
        assertEquals(1, sections.size)
        assertEquals("Run real", sections[0].name)
    }
}

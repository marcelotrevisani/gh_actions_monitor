package com.example.ghactions.ui.ansi

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkflowCommandParserTest {

    @Test
    fun `plain text returns null`() {
        assertNull(WorkflowCommandParser.parseLine("hello world"))
        assertNull(WorkflowCommandParser.parseLine(""))
        assertNull(WorkflowCommandParser.parseLine("::not-a-real-command::oops"))
    }

    @Test
    fun `colon-colon error without location`() {
        val c = WorkflowCommandParser.parseLine("::error::it broke")
        assertEquals(WorkflowCommand(CommandLevel.ERROR, "it broke"), c)
    }

    @Test
    fun `colon-colon error with file and line`() {
        val c = WorkflowCommandParser.parseLine("::error file=src/main.kt,line=42,col=8::Unresolved reference")
        assertEquals(
            WorkflowCommand(CommandLevel.ERROR, "Unresolved reference", file = "src/main.kt", line = 42),
            c
        )
    }

    @Test
    fun `colon-colon warning without location`() {
        val c = WorkflowCommandParser.parseLine("::warning::deprecated API")
        assertEquals(WorkflowCommand(CommandLevel.WARNING, "deprecated API"), c)
    }

    @Test
    fun `colon-colon notice with extra parameters preserves message`() {
        val c = WorkflowCommandParser.parseLine("::notice file=README.md,line=1,endLine=3,title=Heads up::please read")
        assertEquals(
            WorkflowCommand(CommandLevel.NOTICE, "please read", file = "README.md", line = 1),
            c
        )
    }

    @Test
    fun `hashhash format error without location`() {
        val c = WorkflowCommandParser.parseLine("##[error]boom")
        assertEquals(WorkflowCommand(CommandLevel.ERROR, "boom"), c)
    }

    @Test
    fun `leading whitespace is tolerated`() {
        val c = WorkflowCommandParser.parseLine("    ::warning::indented")
        assertEquals(WorkflowCommand(CommandLevel.WARNING, "indented"), c)
    }
}

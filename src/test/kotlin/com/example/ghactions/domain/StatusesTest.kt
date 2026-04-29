package com.example.ghactions.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatusesTest {
    @Test
    fun `RunStatus parses known wire values`() {
        assertEquals(RunStatus.QUEUED, RunStatus.fromWire("queued"))
        assertEquals(RunStatus.IN_PROGRESS, RunStatus.fromWire("in_progress"))
        assertEquals(RunStatus.COMPLETED, RunStatus.fromWire("completed"))
        assertEquals(RunStatus.WAITING, RunStatus.fromWire("waiting"))
        assertEquals(RunStatus.PENDING, RunStatus.fromWire("pending"))
    }

    @Test
    fun `RunStatus returns UNKNOWN for unrecognized wire value`() {
        assertEquals(RunStatus.UNKNOWN, RunStatus.fromWire("not_a_real_status"))
    }

    @Test
    fun `RunConclusion parses known wire values and handles null`() {
        assertEquals(RunConclusion.SUCCESS, RunConclusion.fromWire("success"))
        assertEquals(RunConclusion.FAILURE, RunConclusion.fromWire("failure"))
        assertEquals(RunConclusion.CANCELLED, RunConclusion.fromWire("cancelled"))
        assertEquals(RunConclusion.SKIPPED, RunConclusion.fromWire("skipped"))
        assertEquals(RunConclusion.TIMED_OUT, RunConclusion.fromWire("timed_out"))
        assertEquals(RunConclusion.NEUTRAL, RunConclusion.fromWire("neutral"))
        assertEquals(RunConclusion.ACTION_REQUIRED, RunConclusion.fromWire("action_required"))
        assertNull(RunConclusion.fromWire(null))
        assertEquals(RunConclusion.UNKNOWN, RunConclusion.fromWire("startle"))
    }

    @Test
    fun `wireValue round-trip works`() {
        for (s in RunStatus.entries.filter { it != RunStatus.UNKNOWN }) {
            assertEquals(s, RunStatus.fromWire(s.wireValue))
        }
        for (c in RunConclusion.entries.filter { it != RunConclusion.UNKNOWN }) {
            assertEquals(c, RunConclusion.fromWire(c.wireValue))
        }
    }
}

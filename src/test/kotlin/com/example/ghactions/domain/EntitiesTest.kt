package com.example.ghactions.domain

import java.time.Instant
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class EntitiesTest {
    @Test
    fun `RunId and JobId are distinct types around the same Long`() {
        val r = RunId(123L)
        val j = JobId(123L)
        assertEquals(123L, r.value)
        assertEquals(123L, j.value)
        // Compile-time: RunId and JobId cannot be assigned to each other (covered by typecheck, not runtime).
        // At runtime they're just Long, so this just smoke-checks construction.
        assertNotEquals<Any>(r, j)
    }

    @Test
    fun `Run holds expected fields`() {
        val run = Run(
            id = RunId(1L),
            workflowName = "CI",
            status = RunStatus.IN_PROGRESS,
            conclusion = null,
            headBranch = "main",
            headSha = "abc1234",
            event = "push",
            actorLogin = "octocat",
            createdAt = Instant.parse("2026-04-29T10:00:00Z"),
            updatedAt = Instant.parse("2026-04-29T10:01:00Z"),
            htmlUrl = "https://github.com/octocat/repo/actions/runs/1",
            runNumber = 42,
            displayTitle = "Update README"
        )
        assertEquals(RunId(1L), run.id)
        assertEquals("CI", run.workflowName)
        assertEquals(RunStatus.IN_PROGRESS, run.status)
        assertEquals(null, run.conclusion)
    }

    @Test
    fun `Job holds expected fields including step list`() {
        val step = Step(number = 1, name = "Checkout", status = RunStatus.COMPLETED, conclusion = RunConclusion.SUCCESS)
        val job = Job(
            id = JobId(7L),
            runId = RunId(1L),
            name = "build",
            status = RunStatus.COMPLETED,
            conclusion = RunConclusion.FAILURE,
            startedAt = Instant.parse("2026-04-29T10:00:05Z"),
            completedAt = Instant.parse("2026-04-29T10:01:00Z"),
            htmlUrl = "https://github.com/octocat/repo/actions/runs/1/job/7",
            steps = listOf(step)
        )
        assertEquals(JobId(7L), job.id)
        assertEquals(RunId(1L), job.runId)
        assertEquals(1, job.steps.size)
        assertEquals("Checkout", job.steps[0].name)
    }
}

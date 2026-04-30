package com.example.ghactions.statusbar

import com.example.ghactions.domain.Run
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunId
import com.example.ghactions.domain.RunStatus
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusAggregatorTest {

    private fun run(
        id: Long,
        status: RunStatus,
        conclusion: RunConclusion? = null,
        workflowName: String = "CI"
    ): Run = Run(
        id = RunId(id), workflowName = workflowName,
        status = status, conclusion = conclusion,
        headBranch = "main", headSha = "sha-$id", event = "push",
        actorLogin = "octocat",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        htmlUrl = "https://example.com/run/$id", runNumber = id.toInt(),
        displayTitle = "title $id"
    )

    @Test
    fun `empty list yields IDLE`() {
        val s = StatusAggregator.summarize(emptyList())
        assertEquals(StatusState.IDLE, s.state)
        assertTrue(s.tooltip.contains("No runs", ignoreCase = true))
    }

    @Test
    fun `all success yields SUCCESS`() {
        val s = StatusAggregator.summarize(listOf(
            run(1, RunStatus.COMPLETED, RunConclusion.SUCCESS),
            run(2, RunStatus.COMPLETED, RunConclusion.SUCCESS, workflowName = "Lint")
        ))
        assertEquals(StatusState.SUCCESS, s.state)
    }

    @Test
    fun `any failure yields FAILURE even if others succeeded`() {
        val s = StatusAggregator.summarize(listOf(
            run(1, RunStatus.COMPLETED, RunConclusion.SUCCESS),
            run(2, RunStatus.COMPLETED, RunConclusion.FAILURE, workflowName = "Lint")
        ))
        assertEquals(StatusState.FAILURE, s.state)
    }

    @Test
    fun `any in-progress run yields RUNNING regardless of others`() {
        val s = StatusAggregator.summarize(listOf(
            run(1, RunStatus.COMPLETED, RunConclusion.SUCCESS),
            run(2, RunStatus.IN_PROGRESS, workflowName = "Lint")
        ))
        assertEquals(StatusState.RUNNING, s.state)
        assertTrue(s.tooltip.contains("1 in progress", ignoreCase = true))
    }

    @Test
    fun `queued counts as running for the badge`() {
        val s = StatusAggregator.summarize(listOf(
            run(1, RunStatus.QUEUED)
        ))
        assertEquals(StatusState.RUNNING, s.state)
    }
}

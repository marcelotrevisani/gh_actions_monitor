package com.example.ghactions.notifications

import com.example.ghactions.domain.Run
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunId
import com.example.ghactions.domain.RunStatus
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationDeciderTest {

    private fun run(
        id: Long,
        status: RunStatus,
        conclusion: RunConclusion? = null,
        workflowName: String = "CI"
    ) = Run(
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
    fun `OFF emits nothing even when a run completes with failure`() {
        val previous = mapOf(RunId(1) to RunStatus.IN_PROGRESS)
        val current = listOf(run(1, RunStatus.COMPLETED, RunConclusion.FAILURE))
        val events = NotificationDecider.decide(previous, current, NotificationLevel.OFF)
        assertEquals(emptyList(), events)
    }

    @Test
    fun `FAILURES_ONLY emits on failure but not success`() {
        val previous = mapOf(
            RunId(1) to RunStatus.IN_PROGRESS,
            RunId(2) to RunStatus.IN_PROGRESS
        )
        val current = listOf(
            run(1, RunStatus.COMPLETED, RunConclusion.FAILURE),
            run(2, RunStatus.COMPLETED, RunConclusion.SUCCESS)
        )
        val events = NotificationDecider.decide(previous, current, NotificationLevel.FAILURES_ONLY)
        assertEquals(1, events.size)
        assertEquals(RunId(1), events[0].run.id)
        assertEquals(NotificationKind.FAILURE, events[0].kind)
    }

    @Test
    fun `ALL emits on every terminal transition`() {
        val previous = mapOf(
            RunId(1) to RunStatus.IN_PROGRESS,
            RunId(2) to RunStatus.IN_PROGRESS
        )
        val current = listOf(
            run(1, RunStatus.COMPLETED, RunConclusion.FAILURE),
            run(2, RunStatus.COMPLETED, RunConclusion.SUCCESS)
        )
        val events = NotificationDecider.decide(previous, current, NotificationLevel.ALL)
        assertEquals(2, events.size)
        assertTrue(events.any { it.run.id == RunId(1) && it.kind == NotificationKind.FAILURE })
        assertTrue(events.any { it.run.id == RunId(2) && it.kind == NotificationKind.SUCCESS })
    }

    @Test
    fun `runs that were already terminal in the previous snapshot do not fire`() {
        val previous = mapOf(RunId(1) to RunStatus.COMPLETED)
        val current = listOf(run(1, RunStatus.COMPLETED, RunConclusion.SUCCESS))
        val events = NotificationDecider.decide(previous, current, NotificationLevel.ALL)
        assertEquals(emptyList(), events)
    }

    @Test
    fun `runs unseen in the previous snapshot do not fire`() {
        // First time we see a run — even if it's already completed, don't notify (we don't
        // know if the user already saw it elsewhere; only fire on transitions we observed).
        val previous = emptyMap<RunId, RunStatus>()
        val current = listOf(run(1, RunStatus.COMPLETED, RunConclusion.FAILURE))
        val events = NotificationDecider.decide(previous, current, NotificationLevel.ALL)
        assertEquals(emptyList(), events)
    }

    @Test
    fun `cancelled and timed out runs are treated as failures for FAILURES_ONLY`() {
        val previous = mapOf(
            RunId(1) to RunStatus.IN_PROGRESS,
            RunId(2) to RunStatus.IN_PROGRESS
        )
        val current = listOf(
            run(1, RunStatus.COMPLETED, RunConclusion.CANCELLED),
            run(2, RunStatus.COMPLETED, RunConclusion.TIMED_OUT)
        )
        val events = NotificationDecider.decide(previous, current, NotificationLevel.FAILURES_ONLY)
        assertEquals(2, events.size)
        assertTrue(events.all { it.kind == NotificationKind.FAILURE })
    }
}

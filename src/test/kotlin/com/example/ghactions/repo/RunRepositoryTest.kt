package com.example.ghactions.repo

import com.example.ghactions.api.GitHubApiException
import com.example.ghactions.api.GitHubClient
import com.example.ghactions.domain.JobId
import com.example.ghactions.domain.Run
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunId
import com.example.ghactions.domain.RunStatus
import com.example.ghactions.events.BoundRepo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class RunRepositoryTest {

    private val repo = BoundRepo(host = "https://api.github.com", owner = "octo", repo = "Hello-World")

    private fun aRun(id: Long, status: RunStatus = RunStatus.COMPLETED): Run = Run(
        id = RunId(id),
        workflowName = "CI",
        status = status,
        conclusion = if (status == RunStatus.COMPLETED) RunConclusion.SUCCESS else null,
        headBranch = "main",
        headSha = "deadbee" + id,
        event = "push",
        actorLogin = "octocat",
        createdAt = Instant.parse("2026-04-29T10:00:00Z"),
        updatedAt = Instant.parse("2026-04-29T10:01:00Z"),
        htmlUrl = "https://example.com/$id",
        runNumber = id.toInt(),
        displayTitle = "title $id"
    )

    @Test
    fun `initial runs state is Idle`() {
        val client = mockk<GitHubClient>(relaxed = true)
        val repository = RunRepository(boundRepo = { repo }, clientFactory = { client })
        assertIs<RunListState.Idle>(repository.runsState.value)
    }

    @Test
    fun `refreshRuns transitions to Loaded on success`() = runTest(StandardTestDispatcher()) {
        val client = mockk<GitHubClient>(relaxed = true)
        coEvery { client.listRunsForRepo(repo, any()) } returns listOf(aRun(1), aRun(2))
        val repository = RunRepository(boundRepo = { repo }, clientFactory = { client })

        repository.refreshRuns()
        advanceUntilIdle()

        val final = repository.runsState.value
        assertIs<RunListState.Loaded>(final)
        assertEquals(2, final.runs.size)
    }

    @Test
    fun `refreshRuns surfaces api errors as Error state`() = runTest(StandardTestDispatcher()) {
        val client = mockk<GitHubClient>(relaxed = true)
        coEvery { client.listRunsForRepo(repo, any()) } throws GitHubApiException(401, "Unauthorized")
        val repository = RunRepository(boundRepo = { repo }, clientFactory = { client })

        repository.refreshRuns()
        advanceUntilIdle()

        val state = repository.runsState.value
        assertIs<RunListState.Error>(state)
        assertEquals(401, state.httpStatus)
    }

    @Test
    fun `refreshJobs populates jobsState for given runId`() = runTest(StandardTestDispatcher()) {
        val client = mockk<GitHubClient>(relaxed = true)
        coEvery { client.listJobs(repo, RunId(7L)) } returns emptyList()
        val repository = RunRepository(boundRepo = { repo }, clientFactory = { client })

        repository.refreshJobs(RunId(7L))
        advanceUntilIdle()

        assertIs<JobsState.Loaded>(repository.jobsState(RunId(7L)).value)
        coVerify { client.listJobs(repo, RunId(7L)) }
    }

    @Test
    fun `refreshLogs populates logsState for given jobId`() = runTest(StandardTestDispatcher()) {
        val client = mockk<GitHubClient>(relaxed = true)
        coEvery { client.getJobLogs(repo, JobId(99L)) } returns "log text"
        val repository = RunRepository(boundRepo = { repo }, clientFactory = { client })

        repository.refreshLogs(JobId(99L))
        advanceUntilIdle()

        val state = repository.logsState(JobId(99L)).value
        assertIs<LogState.Loaded>(state)
        assertEquals("log text", state.text)
    }

    @Test
    fun `refreshRuns is a no-op when boundRepo is null`() = runTest(StandardTestDispatcher()) {
        val client = mockk<GitHubClient>(relaxed = true)
        val repository = RunRepository(boundRepo = { null }, clientFactory = { client })

        repository.refreshRuns()
        advanceUntilIdle()

        assertIs<RunListState.Idle>(repository.runsState.value)
        coVerify(exactly = 0) { client.listRunsForRepo(any(), any()) }
    }
}

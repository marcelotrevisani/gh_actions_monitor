package com.example.ghactions.repo

import com.example.ghactions.api.GitHubClient
import com.example.ghactions.domain.CheckRunId
import com.example.ghactions.domain.CheckRunOutput
import com.example.ghactions.domain.Job
import com.example.ghactions.domain.JobId
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunId
import com.example.ghactions.domain.RunStatus
import com.example.ghactions.events.BoundRepo
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunRepositoryCheckRunsTest {

    private val repo = BoundRepo(host = "https://api.github.com", owner = "o", repo = "r")
    private val runId = RunId(99)

    private fun job(id: Long, name: String, checkRunId: Long?): Job = Job(
        id = JobId(id),
        runId = runId,
        name = name,
        status = RunStatus.COMPLETED,
        conclusion = RunConclusion.SUCCESS,
        startedAt = Instant.EPOCH,
        completedAt = Instant.EPOCH,
        htmlUrl = "https://example.com/job/$id",
        steps = emptyList(),
        checkRunId = checkRunId?.let { CheckRunId(it) }
    )

    private fun newRepo(client: GitHubClient): RunRepository = RunRepository(
        boundRepo = { repo },
        clientFactory = { client },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    )

    @Test
    fun `refreshSummary prefers raw step summary over check-run output`() = runTest {
        val client = mockk<GitHubClient>()
        coEvery { client.listJobs(repo, runId) } returns listOf(
            job(1, "build", checkRunId = 11),
            job(2, "test", checkRunId = 22)
        )
        coEvery { client.getStepSummaryRaw("https://example.com/job/1") } returns "# build markdown"
        coEvery { client.getStepSummaryRaw("https://example.com/job/2") } returns null  // no step summary
        coEvery { client.getCheckRun(repo, CheckRunId(11)) } returns
            CheckRunOutput(title = "Build", summary = "build OK", text = null, annotationsCount = 0)
        coEvery { client.getCheckRun(repo, CheckRunId(22)) } returns
            CheckRunOutput(title = "Test", summary = "1 failure", text = null, annotationsCount = 1)
        val repository = newRepo(client)

        repository.refreshSummary(runId).join()
        val state = repository.summaryState(runId).first()
        assertTrue(state is SummaryState.Loaded, "expected Loaded, got $state")
        val sections = (state as SummaryState.Loaded).sections
        assertEquals(2, sections.size)
        assertEquals("build", sections[0].jobName)
        // Job 1 has both rawSummary and check-run output; both are kept on the section.
        assertEquals("# build markdown", sections[0].rawSummary)
        assertEquals("build OK", sections[0].output?.summary)
        // Job 2 has no rawSummary; only check-run output is present.
        assertEquals(null, sections[1].rawSummary)
        assertEquals("1 failure", sections[1].output?.summary)
    }

    @Test
    fun `refreshSummary still emits a section for jobs without a check_run_url`() = runTest {
        val client = mockk<GitHubClient>()
        coEvery { client.listJobs(repo, runId) } returns listOf(
            job(1, "build", checkRunId = null),
            job(2, "test", checkRunId = 22)
        )
        coEvery { client.getStepSummaryRaw("https://example.com/job/1") } returns "build raw"
        coEvery { client.getStepSummaryRaw("https://example.com/job/2") } returns null
        coEvery { client.getCheckRun(repo, CheckRunId(22)) } returns
            CheckRunOutput(title = "Test", summary = "ok", text = null, annotationsCount = 0)
        val repository = newRepo(client)

        repository.refreshSummary(runId).join()
        val state = repository.summaryState(runId).first()
        assertTrue(state is SummaryState.Loaded)
        val sections = (state as SummaryState.Loaded).sections
        assertEquals(2, sections.size)
        assertEquals("build", sections[0].jobName)
        assertEquals("build raw", sections[0].rawSummary)
        assertEquals(null, sections[0].output)  // no check-run for this job
        assertEquals("test", sections[1].jobName)
        assertEquals("ok", sections[1].output?.summary)
    }

    @Test
    fun `refreshAnnotations aggregates annotations across jobs`() = runTest {
        val client = mockk<GitHubClient>()
        coEvery { client.listJobs(repo, runId) } returns listOf(
            job(1, "build", checkRunId = 11),
            job(2, "test", checkRunId = 22)
        )
        coEvery { client.listAnnotations(repo, CheckRunId(11)) } returns listOf(
            com.example.ghactions.domain.Annotation(
                path = "src/main.kt", startLine = 1, endLine = 1,
                level = com.example.ghactions.domain.AnnotationLevel.FAILURE,
                title = "fail", message = "boom"
            )
        )
        coEvery { client.listAnnotations(repo, CheckRunId(22)) } returns listOf(
            com.example.ghactions.domain.Annotation(
                path = "src/test.kt", startLine = 5, endLine = 5,
                level = com.example.ghactions.domain.AnnotationLevel.WARNING,
                title = null, message = "deprecated"
            )
        )
        val repository = newRepo(client)

        repository.refreshAnnotations(runId).join()
        val state = repository.annotationsState(runId).first()
        assertTrue(state is AnnotationsState.Loaded)
        val items = (state as AnnotationsState.Loaded).items
        assertEquals(2, items.size)
        assertEquals("build", items[0].jobName)
        assertEquals("src/main.kt", items[0].annotation.path)
        assertEquals("test", items[1].jobName)
    }

    @Test
    fun `refreshAnnotations skips jobs without a check_run_url`() = runTest {
        val client = mockk<GitHubClient>()
        coEvery { client.listJobs(repo, runId) } returns listOf(
            job(1, "build", checkRunId = null)
        )
        val repository = newRepo(client)

        repository.refreshAnnotations(runId).join()
        val state = repository.annotationsState(runId).first()
        assertTrue(state is AnnotationsState.Loaded)
        assertEquals(emptyList(), (state as AnnotationsState.Loaded).items)
    }
}

package com.example.ghactions.repo

import com.example.ghactions.api.GitHubApiException
import com.example.ghactions.api.GitHubClient
import com.example.ghactions.domain.JobId
import com.example.ghactions.domain.RunId
import com.example.ghactions.events.BoundRepo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RunRepositoryArchiveTest {

    private val repo = BoundRepo(host = "https://api.github.com", owner = "octo", repo = "Hello-World")
    private fun unconfinedScope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun zipWith(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((p, b) in entries) {
                zos.putNextEntry(ZipEntry(p)); zos.write(b.toByteArray()); zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `refreshStepLog fetches the archive, extracts the step text, transitions Loaded`() = runTest {
        val client = mockk<GitHubClient>(relaxed = true)
        coEvery { client.getRunLogsArchive(repo, RunId(1L)) } returns zipWith(
            mapOf("build/3_Run swift test.txt" to "tests output")
        )
        val repository = RunRepository(boundRepo = { repo }, clientFactory = { client }, scope = unconfinedScope())

        repository.refreshStepLog(RunId(1L), JobId(7L), jobName = "build", stepNumber = 3, stepName = "Run swift test")

        val state = repository.logsState(JobId(7L), stepNumber = 3).value
        assertIs<LogState.Loaded>(state)
        assertEquals("tests output", state.text)
    }

    @Test
    fun `refreshStepLog reuses the cached archive on a second call to the same run`() = runTest {
        val client = mockk<GitHubClient>(relaxed = true)
        coEvery { client.getRunLogsArchive(repo, RunId(1L)) } returns zipWith(
            mapOf(
                "build/1_Set up job.txt" to "setup output",
                "build/3_Run swift test.txt" to "tests output"
            )
        )
        val repository = RunRepository(boundRepo = { repo }, clientFactory = { client }, scope = unconfinedScope())

        repository.refreshStepLog(RunId(1L), JobId(7L), "build", 1, "Set up job")
        repository.refreshStepLog(RunId(1L), JobId(7L), "build", 3, "Run swift test")

        coVerify(exactly = 1) { client.getRunLogsArchive(repo, RunId(1L)) }
    }

    @Test
    fun `refreshStepLog falls back to getJobLogs when archive returns 404`() = runTest {
        val client = mockk<GitHubClient>(relaxed = true)
        coEvery { client.getRunLogsArchive(repo, RunId(1L)) } throws GitHubApiException(404, "Not Found")
        coEvery { client.getJobLogs(repo, JobId(7L)) } returns "fallback in-progress text"
        val repository = RunRepository(boundRepo = { repo }, clientFactory = { client }, scope = unconfinedScope())

        repository.refreshStepLog(RunId(1L), JobId(7L), "build", 3, "Run swift test")

        val state = repository.logsState(JobId(7L), stepNumber = 3).value
        assertIs<LogState.Loaded>(state)
        assertEquals("fallback in-progress text", state.text)
        coVerify { client.getJobLogs(repo, JobId(7L)) }
    }

    @Test
    fun `refreshStepLog surfaces non-404 errors as Error state`() = runTest {
        val client = mockk<GitHubClient>(relaxed = true)
        coEvery { client.getRunLogsArchive(repo, RunId(1L)) } throws GitHubApiException(500, "boom")
        val repository = RunRepository(boundRepo = { repo }, clientFactory = { client }, scope = unconfinedScope())

        repository.refreshStepLog(RunId(1L), JobId(7L), "build", 3, "Run swift test")

        val state = repository.logsState(JobId(7L), stepNumber = 3).value
        assertIs<LogState.Error>(state)
        assertEquals(500, state.httpStatus)
    }

    @Test
    fun `refreshStepLog with a step not in the archive surfaces a clear error`() = runTest {
        val client = mockk<GitHubClient>(relaxed = true)
        coEvery { client.getRunLogsArchive(repo, RunId(1L)) } returns zipWith(
            mapOf("build/1_Set up job.txt" to "x")  // step 99 not in archive
        )
        val repository = RunRepository(boundRepo = { repo }, clientFactory = { client }, scope = unconfinedScope())

        repository.refreshStepLog(RunId(1L), JobId(7L), "build", 99, "Run nothing")

        val state = repository.logsState(JobId(7L), stepNumber = 99).value
        assertIs<LogState.Error>(state)
    }
}

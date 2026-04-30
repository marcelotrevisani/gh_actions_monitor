package com.example.ghactions.repo

import com.example.ghactions.api.GitHubApiException
import com.example.ghactions.api.GitHubClient
import com.example.ghactions.domain.RunId
import com.example.ghactions.events.BoundRepo
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunRepositoryRunActionsTest {

    private val repo = BoundRepo(host = "https://api.github.com", owner = "o", repo = "r")
    private val runId = RunId(99)

    private fun newRepo(client: GitHubClient): RunRepository = RunRepository(
        boundRepo = { repo },
        clientFactory = { client },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    )

    @Test
    fun `cancelRun returns Success and triggers a runs refresh`() = runTest {
        val client = mockk<GitHubClient>(relaxed = true)
        coEvery { client.cancelRun(repo, runId) } just Runs
        val repository = newRepo(client)

        val result = repository.cancelRun(runId)

        assertTrue(result is ActionResult.Success)
        // refreshRuns hits listRunsForRepo via clientFactory — the relaxed mock returns
        // an empty list, but we just want to confirm we asked.
        coVerify { client.listRunsForRepo(repo, any(), any()) }
    }

    @Test
    fun `rerunRun maps GitHubApiException to ActionResult Error`() = runTest {
        val client = mockk<GitHubClient>(relaxed = true)
        coEvery { client.rerunRun(repo, runId) } throws GitHubApiException(403, "Forbidden")
        val repository = newRepo(client)

        val result = repository.rerunRun(runId)

        assertTrue(result is ActionResult.Error)
        assertEquals(403, (result as ActionResult.Error).httpStatus)
    }
}

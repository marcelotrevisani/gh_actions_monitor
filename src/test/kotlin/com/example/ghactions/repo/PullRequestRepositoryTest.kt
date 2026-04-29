package com.example.ghactions.repo

import com.example.ghactions.api.GitHubApiException
import com.example.ghactions.api.GitHubClient
import com.example.ghactions.domain.PullRequest
import com.example.ghactions.domain.PullRequestId
import com.example.ghactions.domain.PullRequestState
import com.example.ghactions.domain.Run
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunId
import com.example.ghactions.domain.RunStatus
import com.example.ghactions.events.BoundRepo
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PullRequestRepositoryTest {

    private val repo = BoundRepo(host = "https://api.github.com", owner = "octo", repo = "Hello-World")
    private fun unconfinedScope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun aPr(id: Long, branch: String, state: PullRequestState = PullRequestState.OPEN): PullRequest =
        PullRequest(
            id = PullRequestId(id), number = id.toInt(), state = state, title = "PR $id",
            authorLogin = "octocat", headRef = branch, headSha = "sha-$id",
            htmlUrl = "https://example.com/$id", isDraft = false,
            updatedAt = Instant.parse("2026-04-29T10:00:00Z")
        )

    private fun aRun(id: Long, branch: String, updatedSeconds: Long): Run = Run(
        id = RunId(id), workflowName = "CI",
        status = RunStatus.COMPLETED, conclusion = RunConclusion.SUCCESS,
        headBranch = branch, headSha = "sha-$id", event = "pull_request",
        actorLogin = "octocat",
        createdAt = Instant.parse("2026-04-29T10:00:00Z"),
        updatedAt = Instant.ofEpochSecond(updatedSeconds),
        htmlUrl = "https://example.com/run/$id", runNumber = id.toInt(),
        displayTitle = "title $id"
    )

    @Test
    fun `initial state is Idle`() {
        val client = mockk<GitHubClient>(relaxed = true)
        val repository = PullRequestRepository(boundRepo = { repo }, clientFactory = { client }, scope = unconfinedScope())
        assertIs<PullRequestListState.Idle>(repository.pullRequestsState.value)
    }

    @Test
    fun `refreshPullRequests joins each PR with the most recent matching run by branch`() = runTest {
        val client = mockk<GitHubClient>(relaxed = true)
        val pr1 = aPr(1, branch = "feat/login")
        val pr2 = aPr(2, branch = "feat/cache")
        coEvery { client.listPullRequests(repo, PullRequestState.OPEN, any()) } returns listOf(pr1, pr2)
        coEvery { client.listRunsForRepo(repo, any()) } returns listOf(
            aRun(101, "feat/login", updatedSeconds = 100),
            aRun(102, "feat/login", updatedSeconds = 200), // newer — should win
            aRun(103, "feat/cache", updatedSeconds = 150),
            aRun(104, "main", updatedSeconds = 999)        // unrelated — must be ignored
        )
        val repository = PullRequestRepository(boundRepo = { repo }, clientFactory = { client }, scope = unconfinedScope())

        repository.refreshPullRequests(PullRequestState.OPEN)

        val state = repository.pullRequestsState.value
        assertIs<PullRequestListState.Loaded>(state)
        assertEquals(2, state.entries.size)
        assertEquals(RunId(102), state.entries[0].latestRun?.id)
        assertEquals(RunId(103), state.entries[1].latestRun?.id)
    }

    @Test
    fun `refreshPullRequests sets latestRun to null when no run matches the PR's branch`() = runTest {
        val client = mockk<GitHubClient>(relaxed = true)
        coEvery { client.listPullRequests(repo, PullRequestState.OPEN, any()) } returns listOf(aPr(1, "feat/lonely"))
        coEvery { client.listRunsForRepo(repo, any()) } returns listOf(aRun(101, "main", 100))
        val repository = PullRequestRepository(boundRepo = { repo }, clientFactory = { client }, scope = unconfinedScope())

        repository.refreshPullRequests(PullRequestState.OPEN)

        val state = repository.pullRequestsState.value
        assertIs<PullRequestListState.Loaded>(state)
        assertEquals(1, state.entries.size)
        assertNull(state.entries[0].latestRun)
    }

    @Test
    fun `refreshPullRequests surfaces API errors as Error state`() = runTest {
        val client = mockk<GitHubClient>(relaxed = true)
        coEvery { client.listPullRequests(repo, any(), any()) } throws GitHubApiException(403, "Forbidden")
        val repository = PullRequestRepository(boundRepo = { repo }, clientFactory = { client }, scope = unconfinedScope())

        repository.refreshPullRequests(PullRequestState.OPEN)

        val state = repository.pullRequestsState.value
        assertIs<PullRequestListState.Error>(state)
        assertEquals(403, state.httpStatus)
    }

    @Test
    fun `refreshPullRequests is a no-op when boundRepo is null`() = runTest {
        val client = mockk<GitHubClient>(relaxed = true)
        val repository = PullRequestRepository(boundRepo = { null }, clientFactory = { client }, scope = unconfinedScope())

        repository.refreshPullRequests(PullRequestState.OPEN)

        assertIs<PullRequestListState.Idle>(repository.pullRequestsState.value)
    }
}

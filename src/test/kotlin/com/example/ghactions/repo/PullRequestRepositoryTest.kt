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

    private fun aRun(
        id: Long,
        branch: String,
        updatedSeconds: Long,
        workflowName: String = "CI"
    ): Run = Run(
        id = RunId(id), workflowName = workflowName,
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
    fun `refreshPullRequests fetches runs per PR branch and groups by workflow`() = runTest {
        val client = mockk<GitHubClient>(relaxed = true)
        val pr1 = aPr(1, branch = "feat/login")
        val pr2 = aPr(2, branch = "feat/cache")
        coEvery { client.listPullRequests(repo, PullRequestState.OPEN, any()) } returns listOf(pr1, pr2)
        // Each PR's branch gets its own scoped fetch.
        coEvery { client.listRunsForRepo(repo, any(), branch = "feat/login") } returns listOf(
            aRun(101, "feat/login", updatedSeconds = 100, workflowName = "CI"),
            aRun(102, "feat/login", updatedSeconds = 200, workflowName = "CI"),  // newer CI — should win
            aRun(105, "feat/login", updatedSeconds = 180, workflowName = "Lint")
        )
        coEvery { client.listRunsForRepo(repo, any(), branch = "feat/cache") } returns listOf(
            aRun(103, "feat/cache", updatedSeconds = 150, workflowName = "CI")
        )
        val repository = PullRequestRepository(boundRepo = { repo }, clientFactory = { client }, scope = unconfinedScope())

        repository.refreshPullRequests(PullRequestState.OPEN)

        val state = repository.pullRequestsState.value
        assertIs<PullRequestListState.Loaded>(state)
        assertEquals(2, state.entries.size)

        // pr1: two workflows (CI 102, Lint 105), sorted newest-first
        assertEquals(listOf(RunId(102), RunId(105)), state.entries[0].latestRuns.map { it.id })

        // pr2: one workflow
        assertEquals(listOf(RunId(103)), state.entries[1].latestRuns.map { it.id })
    }

    @Test
    fun `refreshPullRequests yields empty latestRuns when no run matches the PR's branch`() = runTest {
        val client = mockk<GitHubClient>(relaxed = true)
        coEvery { client.listPullRequests(repo, PullRequestState.OPEN, any()) } returns listOf(aPr(1, "feat/lonely"))
        coEvery { client.listRunsForRepo(repo, any(), branch = "feat/lonely") } returns emptyList()
        val repository = PullRequestRepository(boundRepo = { repo }, clientFactory = { client }, scope = unconfinedScope())

        repository.refreshPullRequests(PullRequestState.OPEN)

        val state = repository.pullRequestsState.value
        assertIs<PullRequestListState.Loaded>(state)
        assertEquals(1, state.entries.size)
        assertEquals(emptyList(), state.entries[0].latestRuns)
        assertNull(state.entries[0].latestRun)
    }

    @Test
    fun `refreshPullRequests treats per-branch run fetch failures as empty for that PR`() = runTest {
        val client = mockk<GitHubClient>(relaxed = true)
        val pr1 = aPr(1, branch = "feat/works")
        val pr2 = aPr(2, branch = "feat/broken")
        coEvery { client.listPullRequests(repo, PullRequestState.OPEN, any()) } returns listOf(pr1, pr2)
        coEvery { client.listRunsForRepo(repo, any(), branch = "feat/works") } returns listOf(
            aRun(101, "feat/works", 100, "CI")
        )
        coEvery { client.listRunsForRepo(repo, any(), branch = "feat/broken") } throws
            GitHubApiException(500, "boom")
        val repository = PullRequestRepository(boundRepo = { repo }, clientFactory = { client }, scope = unconfinedScope())

        repository.refreshPullRequests(PullRequestState.OPEN)

        val state = repository.pullRequestsState.value
        assertIs<PullRequestListState.Loaded>(state)
        assertEquals(2, state.entries.size)
        assertEquals(listOf(RunId(101)), state.entries[0].latestRuns.map { it.id })
        assertEquals(emptyList(), state.entries[1].latestRuns)
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

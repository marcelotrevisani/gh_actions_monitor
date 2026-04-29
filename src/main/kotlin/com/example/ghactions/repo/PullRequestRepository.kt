package com.example.ghactions.repo

import com.example.ghactions.api.GitHubApiException
import com.example.ghactions.api.GitHubClient
import com.example.ghactions.domain.PullRequest
import com.example.ghactions.domain.PullRequestState
import com.example.ghactions.domain.Run
import com.example.ghactions.events.BoundRepo
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** State of the PR list (with each PR's matched latest run). */
sealed class PullRequestListState {
    data object Idle : PullRequestListState()
    data object Loading : PullRequestListState()
    data class Loaded(val entries: List<PullRequestWithRun>) : PullRequestListState()
    data class Error(val httpStatus: Int?, val message: String) : PullRequestListState()
}

/** A PR plus the most recent workflow run whose `head_branch` matches [pr.headRef]. */
data class PullRequestWithRun(val pr: PullRequest, val latestRun: Run?)

/**
 * Project-scoped state cache for pull requests. Single source of truth for the PR tree.
 *
 * Production code obtains an instance via `project.getService(PullRequestRepository::class.java)`.
 * Tests construct it directly with explicit dependencies.
 */
@Service(Service.Level.PROJECT)
class PullRequestRepository(
    private val boundRepo: () -> BoundRepo?,
    private val clientFactory: suspend () -> GitHubClient?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : Disposable {

    private val log = Logger.getInstance(PullRequestRepository::class.java)

    /** Project-service constructor — used by IntelliJ's DI container. */
    @Suppress("unused")
    constructor(project: Project) : this(
        boundRepo = { project.getService(com.example.ghactions.repo.RepoBinding::class.java).current },
        clientFactory = { com.example.ghactions.repo.ProductionClientFactory.create(project) }
    )

    private val _pullRequestsState = MutableStateFlow<PullRequestListState>(PullRequestListState.Idle)
    val pullRequestsState: StateFlow<PullRequestListState> = _pullRequestsState.asStateFlow()

    /**
     * Fetch PRs filtered by [state] plus the most recent runs for the bound repo, then
     * for each PR pick the most recent run whose `head_branch` matches the PR's head ref.
     */
    fun refreshPullRequests(state: PullRequestState, perPage: Int = 30): CJob = scope.launch {
        val repo = boundRepo() ?: return@launch
        val client = clientFactory() ?: run {
            _pullRequestsState.value =
                PullRequestListState.Error(null, "No credentials available for ${repo.host}")
            return@launch
        }
        _pullRequestsState.value = PullRequestListState.Loading
        _pullRequestsState.value = try {
            val prs = client.listPullRequests(repo, state, perPage)
            // Fetch enough runs that a PR with a recent commit will probably have at least
            // one match; 100 is plenty for typical CI volumes.
            val runs = client.listRunsForRepo(repo, perPage = 100)
            val byBranch = runs.groupBy { it.headBranch }
            val entries = prs.map { pr ->
                val latest = byBranch[pr.headRef].orEmpty()
                    .maxByOrNull { it.updatedAt }
                PullRequestWithRun(pr, latest)
            }
            PullRequestListState.Loaded(entries)
        } catch (e: GitHubApiException) {
            log.warn("listPullRequests failed: status=${e.status}", e)
            PullRequestListState.Error(e.status, e.message ?: "API error")
        } catch (e: Throwable) {
            log.warn("listPullRequests threw unexpectedly", e)
            PullRequestListState.Error(null, e.message ?: e::class.java.simpleName)
        }
    }

    fun invalidateAll() {
        _pullRequestsState.value = PullRequestListState.Idle
    }

    override fun dispose() {
        scope.cancel()
    }
}

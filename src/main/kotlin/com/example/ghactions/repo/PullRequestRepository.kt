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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
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

/**
 * A PR plus the most recent workflow run *per workflow* whose `head_branch` matches
 * [pr.headRef]. A single PR push can trigger multiple workflows (CI, security scan,
 * deploy, …); we keep the latest run for each so the UI can list them all.
 *
 * [latestRuns] is sorted newest-updated first; empty when no runs match the PR's branch.
 */
data class PullRequestWithRun(val pr: PullRequest, val latestRuns: List<Run>) {
    /** Convenience for callers that only want the most-recent run across all workflows. */
    val latestRun: Run? get() = latestRuns.firstOrNull()
}

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
     * Fetch PRs filtered by [state], then for each PR fan out a per-branch run query so
     * the matched runs reflect *that PR's branch* rather than the first page of the
     * repo-wide run feed. The previous implementation pulled `?per_page=100` of runs once
     * and grouped client-side, which silently dropped PR rows whose runs had been paged
     * out by activity on `main` or other branches.
     *
     * The per-PR queries run in parallel via [coroutineScope] + [async]; failures on a
     * single branch fall through to an empty run list for that PR rather than failing
     * the whole refresh.
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
            val entries = coroutineScope {
                prs.map { pr ->
                    async {
                        val runs = try {
                            client.listRunsForRepo(repo, perPage = 30, branch = pr.headRef)
                        } catch (e: GitHubApiException) {
                            log.warn("listRunsForRepo(branch=${pr.headRef}) failed: status=${e.status}", e)
                            emptyList()
                        } catch (e: Throwable) {
                            log.warn("listRunsForRepo(branch=${pr.headRef}) threw unexpectedly", e)
                            emptyList()
                        }
                        val latestPerWorkflow = runs
                            .groupBy { it.workflowName }
                            .mapNotNull { (_, group) -> group.maxByOrNull { it.updatedAt } }
                            .sortedByDescending { it.updatedAt }
                        PullRequestWithRun(pr, latestPerWorkflow)
                    }
                }.awaitAll()
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

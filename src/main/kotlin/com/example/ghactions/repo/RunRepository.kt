package com.example.ghactions.repo

import com.example.ghactions.api.GitHubApiException
import com.example.ghactions.api.GitHubClient
import com.example.ghactions.domain.Job
import com.example.ghactions.domain.JobId
import com.example.ghactions.domain.Run
import com.example.ghactions.domain.RunId
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
import java.util.concurrent.ConcurrentHashMap

/** State of the runs list for the bound repo. */
sealed class RunListState {
    data object Idle : RunListState()
    data object Loading : RunListState()
    data class Loaded(val runs: List<Run>) : RunListState()
    data class Error(val httpStatus: Int?, val message: String) : RunListState()
}

/** State of the jobs list for one specific run. */
sealed class JobsState {
    data object Idle : JobsState()
    data object Loading : JobsState()
    data class Loaded(val jobs: List<Job>) : JobsState()
    data class Error(val httpStatus: Int?, val message: String) : JobsState()
}

/** State of the log text for one specific job. */
sealed class LogState {
    data object Idle : LogState()
    data object Loading : LogState()
    data class Loaded(val text: String) : LogState()
    data class Error(val httpStatus: Int?, val message: String) : LogState()
}

/**
 * Project-scoped state cache. Single source of truth for run, job, and log data.
 * Never throws — errors land in the appropriate StateFlow's [Error] variant.
 *
 * Production code obtains an instance via `project.getService(RunRepository::class.java)`.
 * Tests construct it directly with explicit dependencies (this avoids
 * BasePlatformTestCase's plugin.xml-loading quirk).
 */
@Service(Service.Level.PROJECT)
class RunRepository(
    private val boundRepo: () -> BoundRepo?,
    private val clientFactory: () -> GitHubClient?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
) : Disposable {

    private val log = Logger.getInstance(RunRepository::class.java)

    /** Project-service constructor used by IntelliJ's DI container. */
    @Suppress("unused")
    constructor(project: Project) : this(
        boundRepo = { project.getService(com.example.ghactions.repo.RepoBinding::class.java).current },
        clientFactory = { ProductionClientFactory.create(project) }
    )

    private val _runsState = MutableStateFlow<RunListState>(RunListState.Idle)
    val runsState: StateFlow<RunListState> = _runsState.asStateFlow()

    private val _jobsByRun = ConcurrentHashMap<RunId, MutableStateFlow<JobsState>>()
    fun jobsState(runId: RunId): StateFlow<JobsState> =
        _jobsByRun.computeIfAbsent(runId) { MutableStateFlow(JobsState.Idle) }.asStateFlow()

    private val _logsByJob = ConcurrentHashMap<JobId, MutableStateFlow<LogState>>()
    fun logsState(jobId: JobId): StateFlow<LogState> =
        _logsByJob.computeIfAbsent(jobId) { MutableStateFlow(LogState.Idle) }.asStateFlow()

    fun refreshRuns(perPage: Int = 30): CJob = scope.launch {
        val repo = boundRepo() ?: return@launch
        val client = clientFactory() ?: run {
            _runsState.value = RunListState.Error(null, "No credentials available for ${repo.host}")
            return@launch
        }
        _runsState.value = RunListState.Loading
        _runsState.value = try {
            RunListState.Loaded(client.listRunsForRepo(repo, perPage))
        } catch (e: GitHubApiException) {
            log.warn("listRunsForRepo failed: status=${e.status}", e)
            RunListState.Error(e.status, e.message ?: "API error")
        } catch (e: Throwable) {
            log.warn("listRunsForRepo threw unexpectedly", e)
            RunListState.Error(null, e.message ?: e::class.java.simpleName)
        }
    }

    fun refreshJobs(runId: RunId): CJob = scope.launch {
        val repo = boundRepo() ?: return@launch
        val client = clientFactory() ?: run {
            jobsFlow(runId).value = JobsState.Error(null, "No credentials available for ${repo.host}")
            return@launch
        }
        jobsFlow(runId).value = JobsState.Loading
        jobsFlow(runId).value = try {
            JobsState.Loaded(client.listJobs(repo, runId))
        } catch (e: GitHubApiException) {
            log.warn("listJobs failed: status=${e.status}", e)
            JobsState.Error(e.status, e.message ?: "API error")
        } catch (e: Throwable) {
            log.warn("listJobs threw unexpectedly", e)
            JobsState.Error(null, e.message ?: e::class.java.simpleName)
        }
    }

    fun refreshLogs(jobId: JobId): CJob = scope.launch {
        val repo = boundRepo() ?: return@launch
        val client = clientFactory() ?: run {
            logsFlow(jobId).value = LogState.Error(null, "No credentials available for ${repo.host}")
            return@launch
        }
        logsFlow(jobId).value = LogState.Loading
        logsFlow(jobId).value = try {
            LogState.Loaded(client.getJobLogs(repo, jobId))
        } catch (e: GitHubApiException) {
            log.warn("getJobLogs failed: status=${e.status}", e)
            LogState.Error(e.status, e.message ?: "API error")
        } catch (e: Throwable) {
            log.warn("getJobLogs threw unexpectedly", e)
            LogState.Error(null, e.message ?: e::class.java.simpleName)
        }
    }

    fun invalidateAll() {
        _runsState.value = RunListState.Idle
        _jobsByRun.values.forEach { it.value = JobsState.Idle }
        _logsByJob.values.forEach { it.value = LogState.Idle }
    }

    override fun dispose() {
        scope.cancel()
    }

    private fun jobsFlow(runId: RunId): MutableStateFlow<JobsState> =
        _jobsByRun.computeIfAbsent(runId) { MutableStateFlow(JobsState.Idle) }

    private fun logsFlow(jobId: JobId): MutableStateFlow<LogState> =
        _logsByJob.computeIfAbsent(jobId) { MutableStateFlow(LogState.Idle) }
}

/** Holder for the production GitHubClient factory — kept out of the [RunRepository] body for clarity. */
internal object ProductionClientFactory {
    fun create(project: Project): GitHubClient? {
        val binding = project.getService(com.example.ghactions.repo.RepoBinding::class.java).current ?: return null
        val settings = com.example.ghactions.auth.PluginSettings.getInstance().state

        val resolver = com.example.ghactions.auth.GitHubAccountResolver(
            ideSource = com.example.ghactions.auth.BundledGithubAccountSource(),
            patLookup = object : com.example.ghactions.auth.PatLookup {
                override fun getToken(host: String) = com.example.ghactions.auth.PatStorage().getToken(host)
            },
            preferredAccountId = settings.preferredAccountId
        )
        val auth = resolver.resolve(binding.host) ?: return null

        // For Plan 2: only PAT auth produces a usable client.
        // IDE-account auth would require an async findCredentials() call — deferred to Plan 3.
        val token = when (auth) {
            is com.example.ghactions.auth.AuthSource.Pat -> auth.token
            is com.example.ghactions.auth.AuthSource.IdeAccount -> {
                Logger.getInstance(ProductionClientFactory::class.java)
                    .warn("IDE-account credentials not yet wired in Plan 2; user must use a PAT for now.")
                return null
            }
        }
        val patAsAuth = com.example.ghactions.auth.AuthSource.Pat(host = binding.host, token = token)
        val http = com.example.ghactions.api.GitHubHttp.create(binding.host, patAsAuth)
        return GitHubClient(http)
    }
}

package com.example.ghactions.repo

import com.example.ghactions.api.GitHubApiException
import com.example.ghactions.api.GitHubClient
import com.example.ghactions.api.logs.RunLogsArchive
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
    private val clientFactory: suspend () -> GitHubClient?,
    // Production uses Dispatchers.IO so HTTP setup (Ktor CIO engine boot, etc.) does NOT run on
    // the EDT. Tests inject a scope built on `StandardTestDispatcher` and drive it via
    // `advanceUntilIdle`, which is why the third constructor parameter exists.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

    /** Per-(jobId, stepNumber) state flow. Plan 3 wires step clicks here. */
    private val _stepLogs = ConcurrentHashMap<Pair<JobId, Int>, MutableStateFlow<LogState>>()
    fun logsState(jobId: JobId, stepNumber: Int): StateFlow<LogState> =
        _stepLogs.computeIfAbsent(jobId to stepNumber) { MutableStateFlow(LogState.Idle) }.asStateFlow()

    /** Cached run-logs zips keyed by run id. Cleared by [invalidateAll]. */
    private val _archivesByRun = ConcurrentHashMap<RunId, RunLogsArchive>()

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

    /**
     * Fetch (or reuse-cached) the run's log archive, extract the specific step's text, and
     * publish to the `(jobId, stepNumber)` state flow. Falls back to [GitHubClient.getJobLogs]
     * when the archive endpoint returns 404 (in-progress runs don't have an archive yet).
     */
    fun refreshStepLog(
        runId: RunId,
        jobId: JobId,
        jobName: String,
        stepNumber: Int,
        stepName: String
    ): CJob = scope.launch {
        val repo = boundRepo() ?: return@launch
        val client = clientFactory() ?: run {
            stepFlow(jobId, stepNumber).value =
                LogState.Error(null, "No credentials available for ${repo.host}")
            return@launch
        }
        stepFlow(jobId, stepNumber).value = LogState.Loading

        // Try cache first; on miss, fetch the archive from GitHub.
        val cached = _archivesByRun[runId]
        val archive = if (cached != null) {
            cached
        } else {
            try {
                val freshArchive = RunLogsArchive(client.getRunLogsArchive(repo, runId))
                _archivesByRun[runId] = freshArchive
                freshArchive
            } catch (e: GitHubApiException) {
                if (e.status == 404) {
                    // In-progress runs don't have an archive yet — fall back to per-job text.
                    stepFlow(jobId, stepNumber).value = try {
                        LogState.Loaded(client.getJobLogs(repo, jobId))
                    } catch (e2: GitHubApiException) {
                        log.warn("getJobLogs fallback failed: status=${e2.status}", e2)
                        LogState.Error(e2.status, e2.message ?: "API error")
                    } catch (e2: Throwable) {
                        log.warn("getJobLogs fallback threw unexpectedly", e2)
                        LogState.Error(null, e2.message ?: e2::class.java.simpleName)
                    }
                    return@launch
                }
                log.warn("getRunLogsArchive failed: status=${e.status}", e)
                stepFlow(jobId, stepNumber).value = LogState.Error(e.status, e.message ?: "API error")
                return@launch
            } catch (e: Throwable) {
                log.warn("getRunLogsArchive threw unexpectedly", e)
                stepFlow(jobId, stepNumber).value =
                    LogState.Error(null, e.message ?: e::class.java.simpleName)
                return@launch
            }
        }

        val text = archive.stepLog(jobName, stepNumber, stepName)
        stepFlow(jobId, stepNumber).value = if (text != null) {
            LogState.Loaded(text)
        } else {
            val stepFiles = archive.listStepFiles(jobName).joinToString(", ").ifEmpty { "<no step files>" }
            val jobs = archive.listJobs().joinToString(", ").ifEmpty { "<empty archive>" }
            LogState.Error(
                null,
                "Step $stepNumber (\"$stepName\") not found for job '$jobName'. " +
                    "Jobs in archive: $jobs. Step files for this job: $stepFiles"
            )
        }
    }

    fun invalidateAll() {
        _runsState.value = RunListState.Idle
        _jobsByRun.values.forEach { it.value = JobsState.Idle }
        _logsByJob.values.forEach { it.value = LogState.Idle }
        _stepLogs.values.forEach { it.value = LogState.Idle }
        _archivesByRun.clear()
    }

    override fun dispose() {
        scope.cancel()
    }

    private fun jobsFlow(runId: RunId): MutableStateFlow<JobsState> =
        _jobsByRun.computeIfAbsent(runId) { MutableStateFlow(JobsState.Idle) }

    private fun logsFlow(jobId: JobId): MutableStateFlow<LogState> =
        _logsByJob.computeIfAbsent(jobId) { MutableStateFlow(LogState.Idle) }

    private fun stepFlow(jobId: JobId, stepNumber: Int): MutableStateFlow<LogState> =
        _stepLogs.computeIfAbsent(jobId to stepNumber) { MutableStateFlow(LogState.Idle) }
}


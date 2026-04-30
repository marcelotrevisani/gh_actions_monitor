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

/** State of the artifacts list for one specific run. */
sealed class ArtifactsState {
    data object Idle : ArtifactsState()
    data object Loading : ArtifactsState()
    data class Loaded(val artifacts: List<com.example.ghactions.domain.Artifact>) : ArtifactsState()
    data class Error(val httpStatus: Int?, val message: String) : ArtifactsState()
}

/** Outcome of [RunRepository.downloadArtifactToFile]. */
sealed class DownloadResult {
    data class Success(val file: java.io.File) : DownloadResult()
    data class Error(val httpStatus: Int?, val message: String) : DownloadResult()
}

/** Outcome of a write-side workflow run action (cancel / rerun). */
sealed class ActionResult {
    data object Success : ActionResult()
    data class Error(val httpStatus: Int?, val message: String) : ActionResult()
}

/** State of the aggregated check-run summaries for a run. */
sealed class SummaryState {
    data object Idle : SummaryState()
    data object Loading : SummaryState()
    data class Loaded(val sections: List<Section>) : SummaryState()
    data class Error(val httpStatus: Int?, val message: String) : SummaryState()

    /**
     * One section per job; ordered as the jobs were returned.
     *
     * - [rawSummary] is the markdown the job wrote to `$GITHUB_STEP_SUMMARY`, fetched from
     *   the undocumented `summary_raw` web endpoint. When present, it's authoritative —
     *   it's what the GitHub UI shows on the run's Summary page.
     * - [output] is the check-run output (title / summary / text), present when the job
     *   has an associated check-run. Used as a fallback when `rawSummary` is null/blank.
     */
    data class Section(
        val jobName: String,
        val output: com.example.ghactions.domain.CheckRunOutput?,
        val rawSummary: String? = null
    )
}

/** State of the aggregated check-run annotations for a run. */
sealed class AnnotationsState {
    data object Idle : AnnotationsState()
    data object Loading : AnnotationsState()
    data class Loaded(val items: List<Item>) : AnnotationsState()
    data class Error(val httpStatus: Int?, val message: String) : AnnotationsState()

    data class Item(val jobName: String, val annotation: com.example.ghactions.domain.Annotation)
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

    private val _artifactsByRun = ConcurrentHashMap<com.example.ghactions.domain.RunId, MutableStateFlow<ArtifactsState>>()
    fun artifactsState(runId: com.example.ghactions.domain.RunId): StateFlow<ArtifactsState> =
        _artifactsByRun.computeIfAbsent(runId) { MutableStateFlow(ArtifactsState.Idle) }.asStateFlow()

    private fun artifactsFlow(runId: com.example.ghactions.domain.RunId): MutableStateFlow<ArtifactsState> =
        _artifactsByRun.computeIfAbsent(runId) { MutableStateFlow(ArtifactsState.Idle) }

    private val _summariesByRun = ConcurrentHashMap<com.example.ghactions.domain.RunId, MutableStateFlow<SummaryState>>()
    fun summaryState(runId: com.example.ghactions.domain.RunId): StateFlow<SummaryState> =
        _summariesByRun.computeIfAbsent(runId) { MutableStateFlow(SummaryState.Idle) }.asStateFlow()

    private fun summaryFlow(runId: com.example.ghactions.domain.RunId): MutableStateFlow<SummaryState> =
        _summariesByRun.computeIfAbsent(runId) { MutableStateFlow(SummaryState.Idle) }

    private val _annotationsByRun = ConcurrentHashMap<com.example.ghactions.domain.RunId, MutableStateFlow<AnnotationsState>>()
    fun annotationsState(runId: com.example.ghactions.domain.RunId): StateFlow<AnnotationsState> =
        _annotationsByRun.computeIfAbsent(runId) { MutableStateFlow(AnnotationsState.Idle) }.asStateFlow()

    private fun annotationsFlow(runId: com.example.ghactions.domain.RunId): MutableStateFlow<AnnotationsState> =
        _annotationsByRun.computeIfAbsent(runId) { MutableStateFlow(AnnotationsState.Idle) }

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

    fun refreshArtifacts(runId: com.example.ghactions.domain.RunId): CJob = scope.launch {
        val repo = boundRepo() ?: return@launch
        val client = clientFactory() ?: run {
            artifactsFlow(runId).value =
                ArtifactsState.Error(null, "No credentials available for ${repo.host}")
            return@launch
        }
        artifactsFlow(runId).value = ArtifactsState.Loading
        artifactsFlow(runId).value = try {
            ArtifactsState.Loaded(client.listArtifacts(repo, runId))
        } catch (e: com.example.ghactions.api.GitHubApiException) {
            log.warn("listArtifacts failed: status=${e.status}", e)
            ArtifactsState.Error(e.status, e.message ?: "API error")
        } catch (e: Throwable) {
            log.warn("listArtifacts threw unexpectedly", e)
            ArtifactsState.Error(null, e.message ?: e::class.java.simpleName)
        }
    }

    fun refreshSummary(runId: com.example.ghactions.domain.RunId): CJob = scope.launch {
        val repo = boundRepo() ?: return@launch
        val client = clientFactory() ?: run {
            summaryFlow(runId).value =
                SummaryState.Error(null, "No credentials available for ${repo.host}")
            return@launch
        }
        summaryFlow(runId).value = SummaryState.Loading
        summaryFlow(runId).value = try {
            val jobs = client.listJobs(repo, runId)
            log.info("refreshSummary(run=${runId.value}): ${jobs.size} job(s)")
            val sections = jobs.map { job ->
                // Best-effort: the undocumented summary_raw web endpoint usually 404s
                // with a PAT (different auth path than api.github.com). When it does
                // succeed, we get the canonical `$GITHUB_STEP_SUMMARY` markdown. When
                // it doesn't, SummaryPanel falls back to the check-run output and the
                // 'Open Summary on GitHub' link. See [GitHubClient.getStepSummaryRaw].
                val raw = client.getStepSummaryRaw(repo, runId, job.id)
                // Always fetch the check-run output too if available — gives us the
                // headline title even when the raw summary is empty.
                val output = job.checkRunId?.let { crid ->
                    runCatching { client.getCheckRun(repo, crid) }
                        .onFailure { log.warn("getCheckRun(${crid.value}) failed", it) }
                        .getOrNull()
                }
                log.info(
                    "refreshSummary: job '${job.name}' raw.len=${raw?.length ?: 0} " +
                        "checkRun.title.len=${output?.title?.length ?: 0} " +
                        "checkRun.summary.len=${output?.summary?.length ?: 0} " +
                        "checkRun.text.len=${output?.text?.length ?: 0}"
                )
                SummaryState.Section(jobName = job.name, output = output, rawSummary = raw)
            }
            SummaryState.Loaded(sections)
        } catch (e: com.example.ghactions.api.GitHubApiException) {
            log.warn("refreshSummary failed: status=${e.status}", e)
            SummaryState.Error(e.status, e.message ?: "API error")
        } catch (e: Throwable) {
            log.warn("refreshSummary threw unexpectedly", e)
            SummaryState.Error(null, e.message ?: e::class.java.simpleName)
        }
    }

    fun refreshAnnotations(runId: com.example.ghactions.domain.RunId): CJob = scope.launch {
        val repo = boundRepo() ?: return@launch
        val client = clientFactory() ?: run {
            annotationsFlow(runId).value =
                AnnotationsState.Error(null, "No credentials available for ${repo.host}")
            return@launch
        }
        annotationsFlow(runId).value = AnnotationsState.Loading
        annotationsFlow(runId).value = try {
            val jobs = client.listJobs(repo, runId)
            val items = jobs.flatMap { job ->
                val crid = job.checkRunId ?: return@flatMap emptyList()
                client.listAnnotations(repo, crid).map { ann -> AnnotationsState.Item(job.name, ann) }
            }
            AnnotationsState.Loaded(items)
        } catch (e: com.example.ghactions.api.GitHubApiException) {
            log.warn("refreshAnnotations failed: status=${e.status}", e)
            AnnotationsState.Error(e.status, e.message ?: "API error")
        } catch (e: Throwable) {
            log.warn("refreshAnnotations threw unexpectedly", e)
            AnnotationsState.Error(null, e.message ?: e::class.java.simpleName)
        }
    }

    suspend fun downloadArtifactToFile(
        artifactId: com.example.ghactions.domain.ArtifactId,
        target: java.io.File
    ): DownloadResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val repo = boundRepo() ?: return@withContext DownloadResult.Error(null, "No bound repo")
        val client = clientFactory() ?: return@withContext DownloadResult.Error(null, "No credentials available for ${repo.host}")
        try {
            val bytes = client.downloadArtifact(repo, artifactId)
            target.writeBytes(bytes)
            DownloadResult.Success(target)
        } catch (e: com.example.ghactions.api.GitHubApiException) {
            log.warn("downloadArtifact failed: status=${e.status}", e)
            DownloadResult.Error(e.status, e.message ?: "API error")
        } catch (e: Throwable) {
            log.warn("downloadArtifact threw unexpectedly", e)
            DownloadResult.Error(null, e.message ?: e::class.java.simpleName)
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

    suspend fun cancelRun(runId: com.example.ghactions.domain.RunId): ActionResult =
        runAction(runId, "cancelRun") { client, repo -> client.cancelRun(repo, runId) }

    suspend fun rerunRun(runId: com.example.ghactions.domain.RunId): ActionResult =
        runAction(runId, "rerunRun") { client, repo -> client.rerunRun(repo, runId) }

    suspend fun rerunFailedJobs(runId: com.example.ghactions.domain.RunId): ActionResult =
        runAction(runId, "rerunFailedJobs") { client, repo -> client.rerunFailedJobs(repo, runId) }

    suspend fun dispatchWorkflow(workflowId: com.example.ghactions.domain.WorkflowId, ref: String): ActionResult =
        runAction(com.example.ghactions.domain.RunId(0), "dispatchWorkflow") { client, repo ->
            client.dispatchWorkflow(repo, workflowId, ref)
        }

    suspend fun listWorkflows(): List<com.example.ghactions.domain.Workflow> {
        val repo = boundRepo() ?: return emptyList()
        val client = clientFactory() ?: return emptyList()
        return try {
            client.listWorkflows(repo)
        } catch (e: Throwable) {
            log.warn("listWorkflows failed", e)
            emptyList()
        }
    }

    private suspend fun runAction(
        runId: com.example.ghactions.domain.RunId,
        label: String,
        block: suspend (com.example.ghactions.api.GitHubClient, com.example.ghactions.events.BoundRepo) -> Unit
    ): ActionResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val repo = boundRepo() ?: return@withContext ActionResult.Error(null, "No bound repo")
        val client = clientFactory() ?: return@withContext ActionResult.Error(
            null, "No credentials available for ${repo.host}"
        )
        try {
            block(client, repo)
            // Trigger an out-of-band refresh so the UI catches up faster than the next
            // polling tick. Fire-and-forget — the action's result is independent of refresh.
            refreshRuns()
            ActionResult.Success
        } catch (e: com.example.ghactions.api.GitHubApiException) {
            log.warn("$label failed: status=${e.status}", e)
            ActionResult.Error(e.status, e.message ?: "API error")
        } catch (e: Throwable) {
            log.warn("$label threw unexpectedly", e)
            ActionResult.Error(null, e.message ?: e::class.java.simpleName)
        }
    }

    fun invalidateAll() {
        _runsState.value = RunListState.Idle
        _jobsByRun.values.forEach { it.value = JobsState.Idle }
        _logsByJob.values.forEach { it.value = LogState.Idle }
        _stepLogs.values.forEach { it.value = LogState.Idle }
        _artifactsByRun.values.forEach { it.value = ArtifactsState.Idle }
        _summariesByRun.values.forEach { it.value = SummaryState.Idle }
        _annotationsByRun.values.forEach { it.value = AnnotationsState.Idle }
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


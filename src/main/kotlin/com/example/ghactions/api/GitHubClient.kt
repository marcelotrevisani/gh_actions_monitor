package com.example.ghactions.api

import com.example.ghactions.api.dto.ListRunsResponse
import com.example.ghactions.domain.Run
import com.example.ghactions.events.BoundRepo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-level GitHub REST surface. Methods are `suspend` and run on [Dispatchers.IO].
 *
 * Construct one per active credential; the underlying [HttpClient] is owned by this object
 * and closed via [close] (or by leaving it to GC for short-lived test instances).
 */
class GitHubClient(private val http: HttpClient) : AutoCloseable {

    /**
     * `GET /repos/{owner}/{repo}/actions/runs?per_page=...&branch=...`. Returns the most recent
     * runs first. When [branch] is non-null the response is filtered server-side to runs whose
     * `head_branch` matches, which is essential for PR-row population on high-volume repos
     * where a global page-1 fetch can miss older PR branches entirely.
     */
    suspend fun listRunsForRepo(
        repo: BoundRepo,
        perPage: Int = 30,
        branch: String? = null
    ): List<Run> = withContext(Dispatchers.IO) {
        val response = http.get("/repos/${repo.owner}/${repo.repo}/actions/runs") {
            parameter("per_page", perPage)
            if (branch != null) parameter("branch", branch)
        }
        if (!response.status.isSuccess()) fail(response, "runs")
        response.body<ListRunsResponse>().workflowRuns.map { it.toDomain() }
    }

    /** `GET /repos/{owner}/{repo}/actions/runs/{run_id}/jobs`. */
    suspend fun listJobs(repo: BoundRepo, runId: com.example.ghactions.domain.RunId): List<com.example.ghactions.domain.Job> =
        withContext(Dispatchers.IO) {
            val response = http.get("/repos/${repo.owner}/${repo.repo}/actions/runs/${runId.value}/jobs")
            if (!response.status.isSuccess()) fail(response, "jobs")
            response.body<com.example.ghactions.api.dto.ListJobsResponse>().jobs.map { it.toDomain() }
        }

    /**
     * `GET /repos/{owner}/{repo}/actions/jobs/{job_id}/logs`. Returns the full plain-text
     * log. GitHub typically responds with `302 Found` redirecting to a signed download URL;
     * Ktor follows redirects by default, so the body we return is the final 200 body.
     */
    suspend fun getJobLogs(repo: BoundRepo, jobId: com.example.ghactions.domain.JobId): String =
        withContext(Dispatchers.IO) {
            val response = http.get("/repos/${repo.owner}/${repo.repo}/actions/jobs/${jobId.value}/logs")
            if (!response.status.isSuccess()) fail(response, "logs")
            response.bodyAsText()
        }

    /**
     * `GET /repos/{owner}/{repo}/actions/runs/{run_id}/logs`. Downloads the entire run's
     * log archive as a zip. GitHub redirects to a signed download URL — Ktor follows the
     * redirect transparently, so the body we return is the raw zip bytes from the final
     * response.
     *
     * Throws [GitHubApiException] with status 404 for in-progress runs (the archive is
     * only available once the run completes). Callers should fall back to [getJobLogs]
     * for that case.
     */
    suspend fun getRunLogsArchive(repo: BoundRepo, runId: com.example.ghactions.domain.RunId): ByteArray =
        withContext(Dispatchers.IO) {
            val response = http.get("/repos/${repo.owner}/${repo.repo}/actions/runs/${runId.value}/logs")
            if (!response.status.isSuccess()) fail(response, "run logs archive")
            response.body<ByteArray>()
        }

    /**
     * `GET /repos/{owner}/{repo}/pulls?state=…&per_page=…`. Paginated; Plan 4 fetches the
     * first page only.
     *
     * `state` accepts the API wire values via [com.example.ghactions.domain.PullRequestState];
     * [PullRequestState.ALL] passes through as `state=all` to GitHub.
     */
    suspend fun listPullRequests(
        repo: BoundRepo,
        state: com.example.ghactions.domain.PullRequestState,
        perPage: Int = 30
    ): List<com.example.ghactions.domain.PullRequest> = withContext(Dispatchers.IO) {
        val response = http.get("/repos/${repo.owner}/${repo.repo}/pulls") {
            parameter("state", state.wireValue.ifEmpty { "all" })
            parameter("per_page", perPage)
        }
        if (!response.status.isSuccess()) fail(response, "pulls")
        response.body<List<com.example.ghactions.api.dto.PullRequestDto>>().map { it.toDomain() }
    }

    /**
     * `GET /repos/{owner}/{repo}/actions/runs/{run_id}/artifacts`. Returns the run's
     * artifacts. Expired artifacts are still listed (with `expired = true`) but their
     * download endpoint returns 410.
     */
    suspend fun listArtifacts(
        repo: BoundRepo,
        runId: com.example.ghactions.domain.RunId
    ): List<com.example.ghactions.domain.Artifact> = withContext(Dispatchers.IO) {
        val response = http.get("/repos/${repo.owner}/${repo.repo}/actions/runs/${runId.value}/artifacts")
        if (!response.status.isSuccess()) fail(response, "artifacts")
        response.body<com.example.ghactions.api.dto.ListArtifactsResponse>().artifacts.map { it.toDomain() }
    }

    /**
     * `GET /repos/{owner}/{repo}/actions/artifacts/{artifact_id}/zip`. Returns the zip
     * bytes (Ktor follows the GitHub redirect to the signed URL transparently).
     *
     * Throws [GitHubApiException] with status 410 when the artifact has expired.
     */
    suspend fun downloadArtifact(
        repo: BoundRepo,
        artifactId: com.example.ghactions.domain.ArtifactId
    ): ByteArray = withContext(Dispatchers.IO) {
        val response = http.get("/repos/${repo.owner}/${repo.repo}/actions/artifacts/${artifactId.value}/zip")
        if (!response.status.isSuccess()) fail(response, "artifact zip")
        response.body<ByteArray>()
    }

    suspend fun getCheckRun(
        repo: BoundRepo,
        checkRunId: com.example.ghactions.domain.CheckRunId
    ): com.example.ghactions.domain.CheckRunOutput = withContext(Dispatchers.IO) {
        val response = http.get("/repos/${repo.owner}/${repo.repo}/check-runs/${checkRunId.value}")
        if (!response.status.isSuccess()) fail(response, "check-run")
        response.body<com.example.ghactions.api.dto.CheckRunDto>().toDomain()
    }

    suspend fun listAnnotations(
        repo: BoundRepo,
        checkRunId: com.example.ghactions.domain.CheckRunId
    ): List<com.example.ghactions.domain.Annotation> = withContext(Dispatchers.IO) {
        val response = http.get("/repos/${repo.owner}/${repo.repo}/check-runs/${checkRunId.value}/annotations")
        if (!response.status.isSuccess()) fail(response, "annotations")
        response.body<List<com.example.ghactions.api.dto.AnnotationDto>>().map { it.toDomain() }
    }

    /**
     * Best-effort fetch of `$GITHUB_STEP_SUMMARY` content from the undocumented web
     * endpoint `https://github.com/{o}/{r}/actions/runs/{run_id}/jobs/{job_id}/summary_raw`.
     *
     * **This usually returns 404 with a PAT.** The endpoint is on the github.com *web*
     * host, not `api.github.com`. The web host gates many URLs on session cookies, not
     * PATs — a 404 with a `text/html` body is GitHub's standard "not authorised" response
     * (same page the browser shows for any private URL when not logged in). The reason
     * `ipdxco/job-summary-url-action` can use this URL is it runs inside GitHub Actions
     * with `GITHUB_TOKEN`, an OAuth-shaped token the web host accepts.
     *
     * We try anyway (it's free) — if your IDE happens to be authenticated with a token
     * the web host honours, the markdown comes through and `SummaryPanel` renders it.
     * Otherwise the panel shows the *Open Summary on GitHub* link as the workaround.
     *
     * Returns null on any failure (including 404). Doesn't throw.
     */
    suspend fun getStepSummaryRaw(
        repo: BoundRepo,
        runId: com.example.ghactions.domain.RunId,
        jobId: com.example.ghactions.domain.JobId
    ): String? = withContext(Dispatchers.IO) {
        val webHost = repo.webBaseUrl()
        val url = "$webHost/${repo.owner}/${repo.repo}/actions/runs/${runId.value}/jobs/${jobId.value}/summary_raw"
        // The web host doesn't speak `application/vnd.github+json`; reset Accept so the
        // server returns raw markdown instead of a JSON error.
        val response = try {
            http.get(url) {
                headers.remove(io.ktor.http.HttpHeaders.Accept)
                headers.append(io.ktor.http.HttpHeaders.Accept, "text/plain, text/markdown, */*")
            }
        } catch (t: Throwable) {
            return@withContext null
        }
        // A 404 with text/html body is GitHub's "not authorised" page; treat as null.
        val ct = response.headers[io.ktor.http.HttpHeaders.ContentType].orEmpty()
        if (!response.status.isSuccess() || ct.startsWith("text/html")) return@withContext null
        response.bodyAsText().takeIf { it.isNotBlank() }
    }

    /**
     * Derive the GitHub web host from the API host stored on [BoundRepo].
     * - `https://api.github.com` → `https://github.com`
     * - `https://ghe.example.com/api/v3` → `https://ghe.example.com`
     */
    private fun BoundRepo.webBaseUrl(): String {
        val h = this.host
        return when {
            h == "https://api.github.com" -> "https://github.com"
            h.endsWith("/api/v3") -> h.removeSuffix("/api/v3")
            else -> h
        }
    }

    /**
     * `POST /repos/{owner}/{repo}/actions/runs/{run_id}/cancel`. GitHub returns 202 / 204
     * on success; 409 if the run is already terminal (cannot cancel).
     */
    suspend fun cancelRun(
        repo: BoundRepo,
        runId: com.example.ghactions.domain.RunId
    ) = withContext(Dispatchers.IO) {
        val response = http.post("/repos/${repo.owner}/${repo.repo}/actions/runs/${runId.value}/cancel")
        if (!response.status.isSuccess()) fail(response, "cancel run")
    }

    /**
     * `POST /repos/{owner}/{repo}/actions/runs/{run_id}/rerun`. Re-runs every job in the run.
     * GitHub returns 201 on success; 403 if the run is too old or already running.
     */
    suspend fun rerunRun(
        repo: BoundRepo,
        runId: com.example.ghactions.domain.RunId
    ) = withContext(Dispatchers.IO) {
        val response = http.post("/repos/${repo.owner}/${repo.repo}/actions/runs/${runId.value}/rerun")
        if (!response.status.isSuccess()) fail(response, "rerun run")
    }

    /**
     * `POST /repos/{owner}/{repo}/actions/runs/{run_id}/rerun-failed-jobs`. Re-runs only the
     * jobs whose conclusion was non-success. Same status-code pattern as [rerunRun].
     */
    suspend fun rerunFailedJobs(
        repo: BoundRepo,
        runId: com.example.ghactions.domain.RunId
    ) = withContext(Dispatchers.IO) {
        val response = http.post("/repos/${repo.owner}/${repo.repo}/actions/runs/${runId.value}/rerun-failed-jobs")
        if (!response.status.isSuccess()) fail(response, "rerun failed jobs")
    }

    suspend fun listWorkflows(repo: BoundRepo): List<com.example.ghactions.domain.Workflow> = withContext(Dispatchers.IO) {
        val response = http.get("/repos/${repo.owner}/${repo.repo}/actions/workflows")
        if (!response.status.isSuccess()) fail(response, "workflows")
        response.body<com.example.ghactions.api.dto.ListWorkflowsResponse>().workflows.map { it.toDomain() }
    }

    suspend fun dispatchWorkflow(
        repo: BoundRepo,
        workflowId: com.example.ghactions.domain.WorkflowId,
        ref: String
    ) = withContext(Dispatchers.IO) {
        val response = http.post("/repos/${repo.owner}/${repo.repo}/actions/workflows/${workflowId.value}/dispatches") {
            contentType(ContentType.Application.Json)
            setBody(com.example.ghactions.api.dto.DispatchRequest(ref = ref))
        }
        if (!response.status.isSuccess()) fail(response, "dispatch workflow")
    }

    private suspend fun fail(response: HttpResponse, label: String): Nothing {
        val body = response.bodyAsText()
        val info = RateLimitInfo.fromHeaders(response.headers)
        val status = response.status.value
        val isRateLimited = status == 429 || (status == 403 && info.remaining == 0)
        val message = "GET $label failed: ${body.take(200)}"
        if (isRateLimited) throw RateLimitedException(status = status, message = message, info = info)
        throw GitHubApiException(status = status, message = message)
    }

    override fun close() {
        http.close()
    }
}

/** Thrown for any non-2xx response from GitHub. The message is truncated for log safety. */
open class GitHubApiException(
    val status: Int,
    message: String
) : RuntimeException(message)

/**
 * Specialised [GitHubApiException] for the rate-limit cases — `429`, or `403` with
 * `X-RateLimit-Remaining: 0`. Callers (esp. `PollingCoordinator`) catch this to back
 * off until the reset window passes.
 */
class RateLimitedException(
    status: Int,
    message: String,
    val info: RateLimitInfo
) : GitHubApiException(status = status, message = message)

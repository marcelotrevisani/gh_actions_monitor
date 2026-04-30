package com.example.ghactions.api

import com.example.ghactions.api.dto.ListRunsResponse
import com.example.ghactions.domain.Run
import com.example.ghactions.events.BoundRepo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
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
     * `GET /repos/{owner}/{repo}/actions/runs?per_page=...`. Returns the most recent runs first.
     * Caller is responsible for paging — Plan 2 only fetches the first page.
     */
    suspend fun listRunsForRepo(repo: BoundRepo, perPage: Int = 30): List<Run> = withContext(Dispatchers.IO) {
        val response = http.get("/repos/${repo.owner}/${repo.repo}/actions/runs") {
            parameter("per_page", perPage)
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

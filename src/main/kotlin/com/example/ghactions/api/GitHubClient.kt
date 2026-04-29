package com.example.ghactions.api

import com.example.ghactions.api.dto.ListRunsResponse
import com.example.ghactions.domain.Run
import com.example.ghactions.events.BoundRepo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
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
        if (!response.status.isSuccess()) {
            throw GitHubApiException(
                status = response.status.value,
                message = "GET runs failed: ${response.bodyAsText().take(200)}"
            )
        }
        response.body<ListRunsResponse>().workflowRuns.map { it.toDomain() }
    }

    /** `GET /repos/{owner}/{repo}/actions/runs/{run_id}/jobs`. */
    suspend fun listJobs(repo: BoundRepo, runId: com.example.ghactions.domain.RunId): List<com.example.ghactions.domain.Job> =
        withContext(Dispatchers.IO) {
            val response = http.get("/repos/${repo.owner}/${repo.repo}/actions/runs/${runId.value}/jobs")
            if (!response.status.isSuccess()) {
                throw GitHubApiException(
                    status = response.status.value,
                    message = "GET jobs failed: ${response.bodyAsText().take(200)}"
                )
            }
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
            if (!response.status.isSuccess()) {
                throw GitHubApiException(
                    status = response.status.value,
                    message = "GET logs failed: ${response.bodyAsText().take(200)}"
                )
            }
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
            if (!response.status.isSuccess()) {
                throw GitHubApiException(
                    status = response.status.value,
                    message = "GET run logs archive failed: ${response.bodyAsText().take(200)}"
                )
            }
            response.body<ByteArray>()
        }

    override fun close() {
        http.close()
    }
}

/** Thrown for any non-2xx response from GitHub. The message is truncated for log safety. */
class GitHubApiException(
    val status: Int,
    message: String
) : RuntimeException(message)

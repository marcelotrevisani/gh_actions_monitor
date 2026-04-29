package com.example.ghactions.api

import com.example.ghactions.auth.AuthSource
import com.example.ghactions.domain.JobId
import com.example.ghactions.events.BoundRepo
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitHubClientGetLogsTest {

    private val auth = AuthSource.Pat("https://api.github.com", "ghp_x")
    private val repo = BoundRepo(host = "https://api.github.com", owner = "octo", repo = "Hello-World")

    private fun fixture(name: String): String =
        this::class.java.getResource("/github-fixtures/$name")!!.readText()

    @Test
    fun `getJobLogs issues GET to logs endpoint`() = runTest {
        val captured = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val engine = MockEngine { req ->
            captured += req
            respond(fixture("job-logs-200.txt"), HttpStatusCode.OK, headersOf("Content-Type", "text/plain"))
        }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        client.getJobLogs(repo, JobId(399444496L))

        assertEquals("GET", captured[0].method.value)
        assertEquals(
            "https://api.github.com/repos/octo/Hello-World/actions/jobs/399444496/logs",
            captured[0].url.toString()
        )
    }

    @Test
    fun `getJobLogs returns the response body as plain text`() = runTest {
        val engine = MockEngine { _ ->
            respond(fixture("job-logs-200.txt"), HttpStatusCode.OK, headersOf("Content-Type", "text/plain"))
        }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        val logs = client.getJobLogs(repo, JobId(399444496L))

        assertTrue(logs.contains("##[group]Run actions/checkout@v4"))
        assertTrue(logs.contains("Done."))
    }

    @Test
    fun `getJobLogs throws on non-2xx`() = runTest {
        val engine = MockEngine { _ -> respond("server error", HttpStatusCode.InternalServerError) }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        try {
            client.getJobLogs(repo, JobId(1L))
            error("expected exception")
        } catch (e: GitHubApiException) {
            assertEquals(500, e.status)
        }
    }
}

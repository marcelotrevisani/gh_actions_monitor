package com.example.ghactions.api

import com.example.ghactions.auth.AuthSource
import com.example.ghactions.domain.RunConclusion
import com.example.ghactions.domain.RunId
import com.example.ghactions.domain.RunStatus
import com.example.ghactions.events.BoundRepo
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitHubClientListJobsTest {

    private val auth = AuthSource.Pat("https://api.github.com", "ghp_x")
    private val repo = BoundRepo(host = "https://api.github.com", owner = "octo", repo = "Hello-World")

    private fun fixture(name: String): String =
        this::class.java.getResource("/github-fixtures/$name")!!.readText()

    @Test
    fun `listJobs issues GET to runs jobs endpoint`() = runTest {
        val captured = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val engine = MockEngine { req ->
            captured += req
            respond(fixture("jobs-list-200.json"), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        client.listJobs(repo, RunId(30433642L))

        assertEquals("GET", captured[0].method.value)
        assertEquals(
            "https://api.github.com/repos/octo/Hello-World/actions/runs/30433642/jobs",
            captured[0].url.toString()
        )
    }

    @Test
    fun `listJobs returns mapped jobs and steps`() = runTest {
        val engine = MockEngine { _ ->
            respond(fixture("jobs-list-200.json"), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        val jobs = client.listJobs(repo, RunId(30433642L))

        assertEquals(2, jobs.size)
        assertEquals("build", jobs[0].name)
        assertEquals(RunConclusion.SUCCESS, jobs[0].conclusion)
        assertEquals(2, jobs[0].steps.size)
        assertEquals("test", jobs[1].name)
        assertEquals(RunStatus.IN_PROGRESS, jobs[1].status)
        assertNull(jobs[1].conclusion)
        assertNull(jobs[1].completedAt)
    }

    @Test
    fun `listJobs throws on non-2xx`() = runTest {
        val engine = MockEngine { _ -> respond("Not found", HttpStatusCode.NotFound) }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        try {
            client.listJobs(repo, RunId(99L))
            error("expected exception")
        } catch (e: GitHubApiException) {
            assertEquals(404, e.status)
        }
    }
}

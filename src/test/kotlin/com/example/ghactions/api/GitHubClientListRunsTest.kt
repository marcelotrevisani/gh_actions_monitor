package com.example.ghactions.api

import com.example.ghactions.auth.AuthSource
import com.example.ghactions.domain.RunConclusion
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

class GitHubClientListRunsTest {

    private val auth = AuthSource.Pat("https://api.github.com", "ghp_x")
    private val repo = BoundRepo(host = "https://api.github.com", owner = "octo", repo = "Hello-World")

    private fun fixture(name: String): String =
        this::class.java.getResource("/github-fixtures/$name")!!.readText()

    @Test
    fun `listRunsForRepo issues GET to actions runs endpoint`() = runTest {
        val captured = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val engine = MockEngine { req ->
            captured += req
            respond(fixture("runs-list-200.json"), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        client.listRunsForRepo(repo, perPage = 30)

        assertEquals("GET", captured[0].method.value)
        assertEquals(
            "https://api.github.com/repos/octo/Hello-World/actions/runs?per_page=30",
            captured[0].url.toString()
        )
    }

    @Test
    fun `listRunsForRepo returns mapped domain runs`() = runTest {
        val engine = MockEngine { _ ->
            respond(fixture("runs-list-200.json"), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        val runs = client.listRunsForRepo(repo, perPage = 30)

        assertEquals(2, runs.size)
        assertEquals("Build", runs[0].workflowName)
        assertEquals(RunStatus.IN_PROGRESS, runs[0].status)
        assertNull(runs[0].conclusion)
        assertEquals("Lint", runs[1].workflowName)
        assertEquals(RunStatus.COMPLETED, runs[1].status)
        assertEquals(RunConclusion.SUCCESS, runs[1].conclusion)
        assertEquals("octocat", runs[0].actorLogin)
    }

    @Test
    fun `listRunsForRepo throws on non-2xx`() = runTest {
        val engine = MockEngine { _ -> respond("Unauthorized", HttpStatusCode.Unauthorized) }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        try {
            client.listRunsForRepo(repo, perPage = 30)
            error("expected exception")
        } catch (e: GitHubApiException) {
            assertEquals(401, e.status)
        }
    }
}

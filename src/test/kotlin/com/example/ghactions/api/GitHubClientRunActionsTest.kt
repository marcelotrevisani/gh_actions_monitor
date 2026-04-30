package com.example.ghactions.api

import com.example.ghactions.auth.AuthSource
import com.example.ghactions.domain.RunId
import com.example.ghactions.events.BoundRepo
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GitHubClientRunActionsTest {

    private val repo = BoundRepo(host = "https://api.github.com", owner = "o", repo = "r")

    private fun client(engine: MockEngine): GitHubClient {
        val http = GitHubHttp.create(
            baseUrl = "https://api.github.com",
            auth = AuthSource.Pat(host = "https://api.github.com", token = "x"),
            engine = engine
        )
        return GitHubClient(http)
    }

    @Test
    fun `cancelRun POSTs to the cancel endpoint and accepts 204`() = runTest {
        var seenMethod: HttpMethod? = null
        var seenUrl: String? = null
        val engine = MockEngine { request ->
            seenMethod = request.method
            seenUrl = request.url.toString()
            respond(content = "", status = HttpStatusCode.NoContent)
        }
        client(engine).cancelRun(repo, RunId(99))
        assertEquals(HttpMethod.Post, seenMethod)
        assertEquals("https://api.github.com/repos/o/r/actions/runs/99/cancel", seenUrl)
    }

    @Test
    fun `rerunRun POSTs to the rerun endpoint and accepts 201`() = runTest {
        var seenUrl: String? = null
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            respond(content = "", status = HttpStatusCode.Created)
        }
        client(engine).rerunRun(repo, RunId(99))
        assertEquals("https://api.github.com/repos/o/r/actions/runs/99/rerun", seenUrl)
    }

    @Test
    fun `rerunFailedJobs POSTs to the rerun-failed-jobs endpoint`() = runTest {
        var seenUrl: String? = null
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            respond(content = "", status = HttpStatusCode.Created)
        }
        client(engine).rerunFailedJobs(repo, RunId(99))
        assertEquals("https://api.github.com/repos/o/r/actions/runs/99/rerun-failed-jobs", seenUrl)
    }

    @Test
    fun `cancelRun maps 409 conflict (already terminal) to GitHubApiException`() = runTest {
        val engine = MockEngine { _ -> respond(content = "{}", status = HttpStatusCode.Conflict) }
        val ex = assertFailsWith<GitHubApiException> { client(engine).cancelRun(repo, RunId(99)) }
        assertEquals(409, ex.status)
    }
}

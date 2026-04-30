package com.example.ghactions.api

import com.example.ghactions.auth.AuthSource
import com.example.ghactions.domain.WorkflowId
import com.example.ghactions.events.BoundRepo
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitHubClientWorkflowsTest {

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
    fun `listWorkflows maps DTO to domain`() = runTest {
        val body = """
            {
              "total_count": 2,
              "workflows": [
                {"id": 1, "name": "CI", "path": ".github/workflows/ci.yml", "state": "active"},
                {"id": 2, "name": "Deploy", "path": ".github/workflows/deploy.yml", "state": "disabled_manually"}
              ]
            }
        """.trimIndent()
        val engine = MockEngine { _ ->
            respond(content = body, status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString())))
        }
        val workflows = client(engine).listWorkflows(repo)
        assertEquals(2, workflows.size)
        assertEquals(WorkflowId(1), workflows[0].id)
        assertEquals("CI", workflows[0].name)
        assertEquals("active", workflows[0].state)
    }

    @Test
    fun `listWorkflows surfaces 404 as GitHubApiException`() = runTest {
        val engine = MockEngine { _ -> respond(content = "{}", status = HttpStatusCode.NotFound) }
        val ex = assertFailsWith<GitHubApiException> { client(engine).listWorkflows(repo) }
        assertEquals(404, ex.status)
    }

    @Test
    fun `dispatchWorkflow POSTs ref body and accepts 204`() = runTest {
        var seenMethod: HttpMethod? = null
        var seenUrl: String? = null
        var seenBody: String? = null
        val engine = MockEngine { request ->
            seenMethod = request.method
            seenUrl = request.url.toString()
            seenBody = (request.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)
                ?.bytes()?.decodeToString()
                ?: (request.body as? io.ktor.http.content.TextContent)?.text
            respond(content = "", status = HttpStatusCode.NoContent)
        }
        client(engine).dispatchWorkflow(repo, WorkflowId(7), ref = "feat/login")
        assertEquals(HttpMethod.Post, seenMethod)
        assertEquals("https://api.github.com/repos/o/r/actions/workflows/7/dispatches", seenUrl)
        // Body is JSON {"ref":"feat/login"}.
        assertTrue(seenBody?.contains("\"ref\":\"feat/login\"") == true, "body missing ref: $seenBody")
    }

    @Test
    fun `dispatchWorkflow maps 422 unprocessable to GitHubApiException`() = runTest {
        val engine = MockEngine { _ -> respond(content = "{}", status = HttpStatusCode.UnprocessableEntity) }
        val ex = assertFailsWith<GitHubApiException> {
            client(engine).dispatchWorkflow(repo, WorkflowId(7), "main")
        }
        assertEquals(422, ex.status)
    }
}

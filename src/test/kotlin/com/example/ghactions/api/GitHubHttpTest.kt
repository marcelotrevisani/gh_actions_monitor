package com.example.ghactions.api

import com.example.ghactions.auth.AuthSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitHubHttpTest {

    private fun mockClient(capture: MutableList<io.ktor.client.request.HttpRequestData>) =
        GitHubHttp.create(
            baseUrl = "https://api.github.com",
            auth = AuthSource.Pat(host = "https://api.github.com", token = "ghp_test"),
            engine = MockEngine { request ->
                capture.add(request)
                respond(
                    content = """{"ok": true}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        )

    @Test
    fun `Pat auth source produces token pat Authorization header`() = runTest {
        val captured = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val client = mockClient(captured)
        client.get("/user")
        assertEquals(1, captured.size)
        assertEquals("token ghp_test", captured[0].headers[HttpHeaders.Authorization])
    }

    @Test
    fun `default Accept header is application vnd github plus json`() = runTest {
        val captured = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val client = mockClient(captured)
        client.get("/user")
        val accept = captured[0].headers[HttpHeaders.Accept]!!
        assertTrue(accept.contains("application/vnd.github+json"), "Accept was: $accept")
    }

    @Test
    fun `User-Agent identifies our plugin`() = runTest {
        val captured = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val client = mockClient(captured)
        client.get("/user")
        val ua = captured[0].headers[HttpHeaders.UserAgent]!!
        assertTrue(ua.startsWith("gh-actions-monitor/"), "User-Agent was: $ua")
    }

    @Test
    fun `relative path resolves against base URL`() = runTest {
        val captured = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val client = mockClient(captured)
        client.get("/user")
        assertEquals("https://api.github.com/user", captured[0].url.toString())
    }

    @Test
    fun `body deserializes via configured JSON`() = runTest {
        val captured = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val client = mockClient(captured)
        val response = client.get("/user")
        assertEquals("""{"ok": true}""", response.bodyAsText())
    }
}

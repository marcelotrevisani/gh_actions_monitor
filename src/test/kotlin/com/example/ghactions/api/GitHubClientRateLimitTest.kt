package com.example.ghactions.api

import com.example.ghactions.auth.AuthSource
import com.example.ghactions.events.BoundRepo
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GitHubClientRateLimitTest {

    private val repo = BoundRepo(
        host = "https://api.github.com",
        owner = "octocat",
        repo = "hello-world"
    )

    @Test
    fun `429 throws RateLimitedException carrying Retry-After`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = "{}",
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf(
                    "Retry-After" to listOf("90"),
                    "X-RateLimit-Remaining" to listOf("0"),
                    "X-RateLimit-Reset" to listOf("1714427200")
                )
            )
        }
        val http = GitHubHttp.create(
            baseUrl = "https://api.github.com",
            auth = AuthSource.Pat(host = "https://api.github.com", token = "x"),
            engine = engine
        )
        val client = GitHubClient(http)

        val ex = assertFailsWith<RateLimitedException> { client.listRunsForRepo(repo) }
        assertEquals(429, ex.status)
        assertEquals(90, ex.info.retryAfterSeconds)
        assertEquals(0, ex.info.remaining)
        assertTrue(ex.info.isHardLimited)
    }

    @Test
    fun `403 with Remaining 0 throws RateLimitedException`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"message": "API rate limit exceeded"}""",
                status = HttpStatusCode.Forbidden,
                headers = headersOf(
                    "X-RateLimit-Remaining" to listOf("0"),
                    "X-RateLimit-Reset" to listOf("1714427200")
                )
            )
        }
        val http = GitHubHttp.create(
            baseUrl = "https://api.github.com",
            auth = AuthSource.Pat(host = "https://api.github.com", token = "x"),
            engine = engine
        )
        val client = GitHubClient(http)

        val ex = assertFailsWith<RateLimitedException> { client.listRunsForRepo(repo) }
        assertEquals(403, ex.status)
        assertNotNull(ex.info.resetEpochSeconds)
        assertTrue(ex.info.isHardLimited)
    }

    @Test
    fun `403 without Remaining 0 stays a plain GitHubApiException`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"message": "Resource not accessible"}""",
                status = HttpStatusCode.Forbidden,
                headers = headersOf("X-RateLimit-Remaining" to listOf("4999"))
            )
        }
        val http = GitHubHttp.create(
            baseUrl = "https://api.github.com",
            auth = AuthSource.Pat(host = "https://api.github.com", token = "x"),
            engine = engine
        )
        val client = GitHubClient(http)

        val ex = assertFailsWith<GitHubApiException> { client.listRunsForRepo(repo) }
        assertEquals(403, ex.status)
        // We deliberately do NOT promote this to RateLimitedException —
        // it's a permission problem, not a rate-limit problem.
        assertTrue(ex !is RateLimitedException)
    }
}

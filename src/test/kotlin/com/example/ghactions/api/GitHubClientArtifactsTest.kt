package com.example.ghactions.api

import com.example.ghactions.auth.AuthSource
import com.example.ghactions.domain.ArtifactId
import com.example.ghactions.domain.RunId
import com.example.ghactions.events.BoundRepo
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitHubClientArtifactsTest {

    private val repo = BoundRepo(
        host = "https://api.github.com",
        owner = "octocat",
        repo = "hello-world"
    )

    private fun client(engine: MockEngine): GitHubClient {
        val http = GitHubHttp.create(
            baseUrl = "https://api.github.com",
            auth = AuthSource.Pat(host = "https://api.github.com", token = "x"),
            engine = engine
        )
        return GitHubClient(http)
    }

    @Test
    fun `listArtifacts maps DTO to domain`() = runTest {
        val body = """
            {
              "total_count": 2,
              "artifacts": [
                {"id": 11, "name": "logs", "size_in_bytes": 1024, "expired": false, "created_at": "2026-04-01T12:00:00Z"},
                {"id": 22, "name": "coverage", "size_in_bytes": 4096, "expired": true, "created_at": "2026-04-01T12:01:00Z"}
              ]
            }
        """.trimIndent()
        val engine = MockEngine { _ ->
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
            )
        }
        val artifacts = client(engine).listArtifacts(repo, RunId(99))
        assertEquals(2, artifacts.size)
        assertEquals(ArtifactId(11), artifacts[0].id)
        assertEquals("logs", artifacts[0].name)
        assertEquals(1024L, artifacts[0].sizeBytes)
        assertFalse(artifacts[0].expired)
        assertTrue(artifacts[1].expired)
    }

    @Test
    fun `listArtifacts surfaces 404 as GitHubApiException`() = runTest {
        val engine = MockEngine { _ ->
            respond(content = "{}", status = HttpStatusCode.NotFound)
        }
        val ex = assertFailsWith<GitHubApiException> {
            client(engine).listArtifacts(repo, RunId(99))
        }
        assertEquals(404, ex.status)
    }

    @Test
    fun `downloadArtifact returns the zip bytes`() = runTest {
        val payload = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x09, 0x09)  // "PK" + a few bytes
        val engine = MockEngine { _ ->
            respond(
                content = payload,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Zip.toString()))
            )
        }
        val bytes = client(engine).downloadArtifact(repo, ArtifactId(11))
        assertContentEquals(payload, bytes)
    }

    @Test
    fun `downloadArtifact maps 410 to GitHubApiException`() = runTest {
        val engine = MockEngine { _ ->
            respond(content = "{}", status = HttpStatusCode.Gone)
        }
        val ex = assertFailsWith<GitHubApiException> {
            client(engine).downloadArtifact(repo, ArtifactId(11))
        }
        assertEquals(410, ex.status)
    }
}

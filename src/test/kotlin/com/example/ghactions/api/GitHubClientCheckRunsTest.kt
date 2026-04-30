package com.example.ghactions.api

import com.example.ghactions.auth.AuthSource
import com.example.ghactions.domain.AnnotationLevel
import com.example.ghactions.domain.CheckRunId
import com.example.ghactions.events.BoundRepo
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GitHubClientCheckRunsTest {

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
    fun `getCheckRun maps output fields`() = runTest {
        val body = """
            {
              "id": 7,
              "output": {
                "title": "Build failed",
                "summary": "## Summary\nthings broke",
                "text": "details here",
                "annotations_count": 3
              }
            }
        """.trimIndent()
        val engine = MockEngine { _ ->
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
            )
        }
        val output = client(engine).getCheckRun(repo, CheckRunId(7))
        assertEquals("Build failed", output.title)
        assertEquals("## Summary\nthings broke", output.summary)
        assertEquals("details here", output.text)
        assertEquals(3, output.annotationsCount)
    }

    @Test
    fun `getCheckRun surfaces 404 as GitHubApiException`() = runTest {
        val engine = MockEngine { _ -> respond(content = "{}", status = HttpStatusCode.NotFound) }
        val ex = assertFailsWith<GitHubApiException> { client(engine).getCheckRun(repo, CheckRunId(7)) }
        assertEquals(404, ex.status)
    }

    @Test
    fun `listAnnotations maps fields and severity`() = runTest {
        val body = """
            [
              {"path": "src/main.kt", "start_line": 10, "end_line": 10,
               "annotation_level": "failure", "title": "boom",
               "message": "Unresolved reference"},
              {"path": "src/util.kt", "start_line": 1, "end_line": 5,
               "annotation_level": "warning", "message": "deprecated"}
            ]
        """.trimIndent()
        val engine = MockEngine { _ ->
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
            )
        }
        val anns = client(engine).listAnnotations(repo, CheckRunId(7))
        assertEquals(2, anns.size)
        assertEquals(AnnotationLevel.FAILURE, anns[0].level)
        assertEquals(10, anns[0].startLine)
        assertEquals("boom", anns[0].title)
        assertEquals(AnnotationLevel.WARNING, anns[1].level)
    }

    @Test
    fun `listAnnotations surfaces 404 as GitHubApiException`() = runTest {
        val engine = MockEngine { _ -> respond(content = "[]", status = HttpStatusCode.NotFound) }
        val ex = assertFailsWith<GitHubApiException> { client(engine).listAnnotations(repo, CheckRunId(7)) }
        assertEquals(404, ex.status)
    }
}

package com.example.ghactions.api

import com.example.ghactions.auth.AuthSource
import com.example.ghactions.domain.RunId
import com.example.ghactions.events.BoundRepo
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class GitHubClientGetArchiveTest {

    private val auth = AuthSource.Pat("https://api.github.com", "ghp_x")
    private val repo = BoundRepo(host = "https://api.github.com", owner = "octo", repo = "Hello-World")

    private fun fakeZip(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("build.txt"))
            zos.write("log content".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `getRunLogsArchive issues GET to actions runs logs endpoint`() = runTest {
        val captured = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val zipBytes = fakeZip()
        val engine = MockEngine { req ->
            captured += req
            respond(zipBytes, HttpStatusCode.OK, headersOf("Content-Type", "application/zip"))
        }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        client.getRunLogsArchive(repo, RunId(30433642L))

        assertEquals("GET", captured[0].method.value)
        assertEquals(
            "https://api.github.com/repos/octo/Hello-World/actions/runs/30433642/logs",
            captured[0].url.toString()
        )
    }

    @Test
    fun `getRunLogsArchive returns the response body as bytes`() = runTest {
        val zipBytes = fakeZip()
        val engine = MockEngine { _ ->
            respond(zipBytes, HttpStatusCode.OK, headersOf("Content-Type", "application/zip"))
        }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        val bytes = client.getRunLogsArchive(repo, RunId(30433642L))
        assertContentEquals(zipBytes, bytes)
    }

    @Test
    fun `getRunLogsArchive throws on 404 (in-progress run)`() = runTest {
        val engine = MockEngine { _ -> respond("Not Found", HttpStatusCode.NotFound) }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        try {
            client.getRunLogsArchive(repo, RunId(99L))
            error("expected exception")
        } catch (e: GitHubApiException) {
            assertEquals(404, e.status)
        }
    }

    @Test
    fun `getRunLogsArchive throws on 5xx`() = runTest {
        val engine = MockEngine { _ -> respond("server error", HttpStatusCode.InternalServerError) }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        try {
            client.getRunLogsArchive(repo, RunId(1L))
            error("expected exception")
        } catch (e: GitHubApiException) {
            assertEquals(500, e.status)
        }
    }
}

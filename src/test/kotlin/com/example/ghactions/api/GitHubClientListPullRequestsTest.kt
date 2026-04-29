package com.example.ghactions.api

import com.example.ghactions.auth.AuthSource
import com.example.ghactions.domain.PullRequestState
import com.example.ghactions.events.BoundRepo
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class GitHubClientListPullRequestsTest {

    private val auth = AuthSource.Pat("https://api.github.com", "ghp_x")
    private val repo = BoundRepo(host = "https://api.github.com", owner = "octo", repo = "Hello-World")

    private val sampleResponse = """
        [
          {
            "id": 1,
            "number": 1347,
            "state": "open",
            "title": "Add login flow",
            "user": { "login": "octocat", "id": 1 },
            "head": { "ref": "feat/login", "sha": "abc" },
            "base": { "ref": "main", "sha": "def" },
            "html_url": "https://github.com/octo/Hello-World/pull/1347",
            "created_at": "2020-01-22T19:33:08Z",
            "updated_at": "2020-01-22T19:33:08Z",
            "draft": false
          },
          {
            "id": 2,
            "number": 1348,
            "state": "open",
            "title": "Refactor cache",
            "user": { "login": "hubot", "id": 2 },
            "head": { "ref": "refactor/cache", "sha": "xyz" },
            "base": { "ref": "main", "sha": "ghi" },
            "html_url": "https://github.com/octo/Hello-World/pull/1348",
            "created_at": "2020-01-21T18:30:00Z",
            "updated_at": "2020-01-21T18:35:00Z",
            "draft": true
          }
        ]
    """.trimIndent()

    @Test
    fun `listPullRequests issues GET to pulls endpoint with state and per_page`() = runTest {
        val captured = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val engine = MockEngine { req ->
            captured += req
            respond(sampleResponse, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        client.listPullRequests(repo, state = PullRequestState.OPEN, perPage = 30)

        assertEquals("GET", captured[0].method.value)
        assertEquals(
            "https://api.github.com/repos/octo/Hello-World/pulls?state=open&per_page=30",
            captured[0].url.toString()
        )
    }

    @Test
    fun `listPullRequests returns mapped domain pull requests`() = runTest {
        val engine = MockEngine { _ ->
            respond(sampleResponse, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        val prs = client.listPullRequests(repo, state = PullRequestState.OPEN)

        assertEquals(2, prs.size)
        assertEquals("Add login flow", prs[0].title)
        assertEquals("feat/login", prs[0].headRef)
        assertEquals(false, prs[0].isDraft)
        assertEquals(true, prs[1].isDraft)
    }

    @Test
    fun `listPullRequests with ALL state passes state=all to the API`() = runTest {
        val captured = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val engine = MockEngine { req ->
            captured += req
            respond("[]", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        client.listPullRequests(repo, state = PullRequestState.ALL, perPage = 50)

        assertEquals(
            "https://api.github.com/repos/octo/Hello-World/pulls?state=all&per_page=50",
            captured[0].url.toString()
        )
    }

    @Test
    fun `listPullRequests throws on non-2xx`() = runTest {
        val engine = MockEngine { _ -> respond("Forbidden", HttpStatusCode.Forbidden) }
        val client = GitHubClient(GitHubHttp.create("https://api.github.com", auth, engine))

        try {
            client.listPullRequests(repo, state = PullRequestState.OPEN)
            error("expected exception")
        } catch (e: GitHubApiException) {
            assertEquals(403, e.status)
        }
    }
}

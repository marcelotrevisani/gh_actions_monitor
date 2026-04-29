package com.example.ghactions.api.dto

import com.example.ghactions.domain.PullRequestId
import com.example.ghactions.domain.PullRequestState
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PullRequestDtoMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val sample = """
        {
          "id": 1,
          "number": 1347,
          "state": "open",
          "title": "Amazing new feature",
          "user": { "login": "octocat", "id": 1 },
          "head": {
            "label": "octocat:new-topic",
            "ref": "new-topic",
            "sha": "6dcb09b5b57875f334f61aebed695e2e4193db5e"
          },
          "base": { "ref": "main", "sha": "abc" },
          "html_url": "https://github.com/octo/repo/pull/1347",
          "created_at": "2020-01-22T19:33:08Z",
          "updated_at": "2020-01-22T19:33:08Z",
          "draft": false
        }
    """.trimIndent()

    @Test
    fun `PullRequestDto deserializes a real GitHub response`() {
        val dto = json.decodeFromString<PullRequestDto>(sample)
        assertEquals(1L, dto.id)
        assertEquals(1347, dto.number)
        assertEquals("open", dto.state)
        assertEquals("octocat", dto.user?.login)
        assertEquals("new-topic", dto.head.ref)
        assertEquals("6dcb09b5b57875f334f61aebed695e2e4193db5e", dto.head.sha)
        assertEquals(false, dto.draft)
    }

    @Test
    fun `PullRequestDto handles closed state and null user`() {
        val closed = sample
            .replace(""""state": "open"""", """"state": "closed"""")
            .replace(""""user": { "login": "octocat", "id": 1 }""", """"user": null""")
        val dto = json.decodeFromString<PullRequestDto>(closed)
        assertEquals("closed", dto.state)
        assertNull(dto.user)
    }

    @Test
    fun `PullRequestDto toDomain produces correct PullRequest`() {
        val dto = json.decodeFromString<PullRequestDto>(sample)
        val pr = dto.toDomain()
        assertEquals(PullRequestId(1L), pr.id)
        assertEquals(1347, pr.number)
        assertEquals(PullRequestState.OPEN, pr.state)
        assertEquals("Amazing new feature", pr.title)
        assertEquals("octocat", pr.authorLogin)
        assertEquals("new-topic", pr.headRef)
        assertEquals(false, pr.isDraft)
        assertEquals(Instant.parse("2020-01-22T19:33:08Z"), pr.updatedAt)
    }
}

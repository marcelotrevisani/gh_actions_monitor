package com.example.ghactions.domain

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class PullRequestEntitiesTest {

    @Test
    fun `PullRequestId wraps a long`() {
        val id = PullRequestId(42L)
        assertEquals(42L, id.value)
    }

    @Test
    fun `PullRequest holds the fields we surface`() {
        val pr = PullRequest(
            id = PullRequestId(1L),
            number = 1347,
            state = PullRequestState.OPEN,
            title = "Add login flow",
            authorLogin = "octocat",
            headRef = "feat/login",
            headSha = "abc1234",
            htmlUrl = "https://github.com/octo/repo/pull/1347",
            isDraft = false,
            updatedAt = Instant.parse("2026-04-29T10:00:00Z")
        )
        assertEquals(1347, pr.number)
        assertEquals(PullRequestState.OPEN, pr.state)
        assertEquals("feat/login", pr.headRef)
        assertEquals("octocat", pr.authorLogin)
    }
}

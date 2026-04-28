package com.example.ghactions.auth

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubAccountResolverTest {

    private fun fakeIdeSource(vararg accounts: Pair<String, String>) =
        object : IdeGithubAccountSource {
            override fun listAccounts() = accounts.map { (id, host) -> IdeAccountInfo(id, host) }
        }

    private fun resolver(
        ideAccounts: List<Pair<String, String>> = emptyList(),
        patHosts: Map<String, String> = emptyMap()
    ): GitHubAccountResolver {
        val pats = object : PatLookup {
            override fun getToken(host: String) = patHosts[host]
        }
        return GitHubAccountResolver(
            ideSource = fakeIdeSource(*ideAccounts.toTypedArray()),
            patLookup = pats,
            preferredAccountId = null
        )
    }

    @Test
    fun `returns null when neither source has credentials for host`() {
        val r = resolver()
        assertNull(r.resolve("https://api.github.com"))
    }

    @Test
    fun `prefers ide account when available for host`() {
        val r = resolver(
            ideAccounts = listOf("acct-1" to "https://api.github.com"),
            patHosts = mapOf("https://api.github.com" to "ghp_xxx")
        )
        val result = r.resolve("https://api.github.com")
        assertTrue(result is AuthSource.IdeAccount)
        assertEquals("acct-1", (result as AuthSource.IdeAccount).accountId)
    }

    @Test
    fun `falls back to pat when no matching ide account`() {
        val r = resolver(
            ideAccounts = listOf("acct-1" to "https://ghe.example.com/api/v3"),
            patHosts = mapOf("https://api.github.com" to "ghp_xxx")
        )
        val result = r.resolve("https://api.github.com")
        assertTrue(result is AuthSource.Pat)
        assertEquals("ghp_xxx", (result as AuthSource.Pat).token)
    }

    @Test
    fun `prefers configured account id when multiple ide accounts match host`() {
        val pats = object : PatLookup {
            override fun getToken(host: String) = null
        }
        val ideSource = object : IdeGithubAccountSource {
            override fun listAccounts() = listOf(
                IdeAccountInfo("acct-1", "https://api.github.com"),
                IdeAccountInfo("acct-2", "https://api.github.com")
            )
        }
        val r = GitHubAccountResolver(ideSource, pats, preferredAccountId = "acct-2")

        val result = r.resolve("https://api.github.com") as AuthSource.IdeAccount
        assertEquals("acct-2", result.accountId)
    }

    @Test
    fun `host comparison is case insensitive and normalized`() {
        val r = resolver(
            ideAccounts = listOf("acct-1" to "https://API.github.com")
        )
        val result = r.resolve("https://api.github.com")
        assertTrue(result is AuthSource.IdeAccount)
    }
}

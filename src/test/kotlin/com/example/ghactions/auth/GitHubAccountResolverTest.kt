package com.example.ghactions.auth

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubAccountResolverTest {

    private fun fakeIdeSource(vararg accounts: Pair<String, String>) =
        object : IdeGithubAccountSource {
            override fun listAccounts() = accounts.map { (id, host) -> IdeAccountInfo(id = id, name = id, host = host) }
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
                IdeAccountInfo(id = "acct-1", name = "acct-1", host = "https://api.github.com"),
                IdeAccountInfo(id = "acct-2", name = "acct-2", host = "https://api.github.com")
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

    private fun fakePatSource(tokens: Map<String, String>) = object : PatLookup {
        override fun getToken(host: String) = tokens[host]
    }

    private fun fakeIdeSourceWithToken(
        accounts: List<Pair<String, String>>,
        tokenFor: Map<String, String> = emptyMap()
    ) = object : IdeGithubAccountSource {
        override fun listAccounts() = accounts.map { (id, host) -> IdeAccountInfo(id = id, name = id, host = host) }
        override suspend fun findToken(accountId: String): String? = tokenFor[accountId]
    }

    @Test
    fun `resolveAuth returns null when nothing matches`() = kotlinx.coroutines.test.runTest {
        val r = GitHubAccountResolver(
            ideSource = fakeIdeSource(),
            patLookup = fakePatSource(emptyMap()),
            preferredAccountId = null
        )
        assertNull(r.resolveAuth("https://api.github.com"))
    }

    @Test
    fun `resolveAuth returns Pat with the stored token for a PAT host`() = kotlinx.coroutines.test.runTest {
        val r = GitHubAccountResolver(
            ideSource = fakeIdeSource(),
            patLookup = fakePatSource(mapOf("https://api.github.com" to "ghp_xxx")),
            preferredAccountId = null
        )
        val resolved = r.resolveAuth("https://api.github.com")!!
        assertTrue(resolved.auth is AuthSource.Pat)
        assertEquals("ghp_xxx", resolved.token)
    }

    @Test
    fun `resolveAuth returns IdeAccount with the IDE-fetched token for an IDE account`() =
        kotlinx.coroutines.test.runTest {
            val r = GitHubAccountResolver(
                ideSource = fakeIdeSourceWithToken(
                    accounts = listOf("acct-1" to "https://api.github.com"),
                    tokenFor = mapOf("acct-1" to "ide-token-xyz")
                ),
                patLookup = fakePatSource(mapOf("https://api.github.com" to "fallback-pat")),
                preferredAccountId = null
            )
            val resolved = r.resolveAuth("https://api.github.com")!!
            assertTrue(resolved.auth is AuthSource.IdeAccount)
            assertEquals("acct-1", (resolved.auth as AuthSource.IdeAccount).accountId)
            assertEquals("ide-token-xyz", resolved.token)
        }

    @Test
    fun `resolveAuth falls through to PAT when the matched IDE account has no stored token`() =
        kotlinx.coroutines.test.runTest {
            val r = GitHubAccountResolver(
                ideSource = fakeIdeSourceWithToken(
                    accounts = listOf("acct-1" to "https://api.github.com"),
                    tokenFor = emptyMap()
                ),
                patLookup = fakePatSource(mapOf("https://api.github.com" to "fallback-pat")),
                preferredAccountId = null
            )
            val resolved = r.resolveAuth("https://api.github.com")!!
            assertTrue(resolved.auth is AuthSource.Pat, "Should have fallen through to PAT; got ${resolved.auth}")
            assertEquals("fallback-pat", resolved.token)
        }
}

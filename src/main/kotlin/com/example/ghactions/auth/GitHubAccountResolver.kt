package com.example.ghactions.auth

/** Anything that can list IDE-configured GitHub accounts and look up their tokens. */
interface IdeGithubAccountSource {
    fun listAccounts(): List<IdeAccountInfo>

    /**
     * Returns the persistent token for the account identified by [accountId], or null if
     * the account doesn't exist, has no credentials stored, or the lookup fails.
     *
     * Default returns null — convenient for tests that only need the listing surface
     * (the resolver still works in PAT-only mode when this returns null).
     */
    suspend fun findToken(accountId: String): String? = null
}

data class IdeAccountInfo(val id: String, val host: String)

/** Anything that can look up a PAT for a host. Pulled out for testability. */
interface PatLookup {
    fun getToken(host: String): String?
}

/**
 * Resolves credentials for a target [host] in priority order:
 * 1. IDE-configured GitHub account whose host matches.
 *    If multiple match, prefer the one whose id == [preferredAccountId]; otherwise the first.
 * 2. PAT stored in PasswordSafe for that host.
 * 3. null — caller should show empty state.
 */
class GitHubAccountResolver(
    private val ideSource: IdeGithubAccountSource,
    private val patLookup: PatLookup,
    private val preferredAccountId: String?
) {

    fun resolve(host: String): AuthSource? {
        val normalizedTarget = normalize(host)

        val matchingIdeAccounts = ideSource.listAccounts()
            .filter { normalize(it.host) == normalizedTarget }

        if (matchingIdeAccounts.isNotEmpty()) {
            val chosen = matchingIdeAccounts.firstOrNull { it.id == preferredAccountId }
                ?: matchingIdeAccounts.first()
            return AuthSource.IdeAccount(host = host, accountId = chosen.id)
        }

        patLookup.getToken(host)?.let {
            return AuthSource.Pat(host = host, token = it)
        }

        return null
    }

    private fun normalize(url: String): String =
        url.trim().lowercase().trimEnd('/')
}

/**
 * Adapter that reads accounts from the bundled `org.jetbrains.plugins.github` plugin.
 *
 * The bundled plugin's account API has changed across IDE versions; this adapter is
 * deliberately defensive — if the call throws (API mismatch, plugin disabled), it logs
 * and returns an empty list so the user can still fall back to PAT auth.
 *
 * Verified against IDE 2024.3: `GHAccountManager` is an application service registered
 * by the bundled plugin and extends `AccountManagerBase<GithubAccount, String>`. The
 * inherited `accountsState: StateFlow<Set<GithubAccount>>` exposes the configured accounts.
 */
class BundledGithubAccountSource : IdeGithubAccountSource {
    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(BundledGithubAccountSource::class.java)

    override fun listAccounts(): List<IdeAccountInfo> = try {
        val mgr = com.intellij.openapi.components.service<
            org.jetbrains.plugins.github.authentication.accounts.GHAccountManager>()
        mgr.accountsState.value.map { acct ->
            IdeAccountInfo(
                id = acct.id,
                host = acct.server.toApiUrl().removeSuffix("/")
            )
        }
    } catch (e: Throwable) {
        log.warn("Failed to read IDE GitHub accounts; falling back to empty list", e)
        emptyList()
    }

    override suspend fun findToken(accountId: String): String? = try {
        val mgr = com.intellij.openapi.components.service<
            org.jetbrains.plugins.github.authentication.accounts.GHAccountManager>()
        val account = mgr.accountsState.value.firstOrNull { it.id == accountId }
        if (account == null) {
            log.warn("findToken: no IDE GitHub account with id=$accountId")
            null
        } else {
            mgr.findCredentials(account)
        }
    } catch (e: Throwable) {
        log.warn("findToken: lookup failed for id=$accountId", e)
        null
    }
}
